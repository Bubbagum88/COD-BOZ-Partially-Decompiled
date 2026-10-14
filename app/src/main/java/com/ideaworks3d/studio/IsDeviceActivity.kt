package com.ideaworks3d.studio

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.activision.boz.R
import com.ideaworks3d.marmalade.LoaderActivity
import com.ideaworks3d.marmalade.s3eTouchpad
import com.savegame.SavesRestoring
import java.io.File

open class IsDeviceActivity : LoaderActivity() {

    companion object {
        private const val TAG = "IsDeviceActivity"
        private const val REQ_CODE_ALL_FILES_ACCESS = 0x414c
        private const val REQ_CODE_WRITE_STORAGE = 0x5354
        @JvmStatic
        private var m_Activity: IsDeviceActivity? = null
        private const val KEY_HUD_ASKED = "hud_asked"

        // Called by Marmalade's s3eLibraryLoad / libIsDevice.so.
        // Native code resolves this exact static symbol by name — do not rename.
        @JvmStatic
        fun getInstance(): IsDeviceActivity? = m_Activity
    }

    /**
     * On Android 11+ (API 30+) the scoped-storage model ignores the legacy
     * WRITE_EXTERNAL_STORAGE permission for paths outside the app's own
     * external dirs. The Marmalade engine mounts its writable `rst://` drive
     * onto the raw external-storage root (LoaderThread.getRstDir()) and then
     * tries to mkdir `/sdcard/Android/obb/<pkg>/` — which fails with EACCES
     * there unless the app holds All files access. So before starting the
     * native loader (which performs those writes) we require the user to grant
     * MANAGE_EXTERNAL_STORAGE on API 30+. The manifest declares it; this
     * method drives the runtime grant.
     */
    private fun needsAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()

    private fun hasWriteExternalStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    /**
     * Ensure /sdcard/Android/obb/<pkg>/ exists and is writable before the
     * native loader runs. The engine writes the expansion data here; if the
     * directory is missing or unreadable it reports a misleading "not enough
     * storage" error (actually EACCES). context.obbDir is the canonical OBB
     * path and is auto-created/app-owned even under scoped storage, so we
     * prefer it; we also touch the raw /sdcard path so legacy engine code
     * that constructs the path itself finds an existing, writable dir.
     */
    private fun ensureObbDir(): Boolean {
        return try {
            val obbDir = this.obbDir
            if (!obbDir.exists() && !obbDir.mkdirs()) {
                Log.w(TAG, "obbDir.mkdirs() failed: ${obbDir.absolutePath}")
            }
            val probe = File(obbDir, ".boz_writable_probe")
            val ok = probe.createNewFile()
            if (ok) probe.delete()

            // Also pre-create the raw /sdcard/Android/obb/<pkg>/ path the
            // engine may construct directly via IsDevice.GetExpansionPath().
            val raw = File(
                Environment.getExternalStorageDirectory(),
                "Android/obb/$packageName"
            )
            if (!raw.exists()) raw.mkdirs()

            Log.i(TAG, "OBB dir ready: ${obbDir.absolutePath} (writable=$ok); raw=${raw.absolutePath} exists=${raw.exists()}")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "ensureObbDir failed", e)
            false
        }
    }

    private fun requestAllFilesAccess() {
        AlertDialog.Builder(this)
            .setTitle("Storage access required")
            .setMessage(
                "Call of Duty: Black Ops Zombies needs All files access " +
                "to create its game-data folder (Android/obb/com.activision.boz) " +
                "on your device. The next screen grants this. Tap the toggle, " +
                "press back, and the game will start."
            )
            .setCancelable(false)
            .setPositiveButton("Grant access") { _, _ ->
                try {
                    val uri = Uri.parse("package:$packageName")
                    @Suppress("DEPRECATION")
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        uri
                    )
                    startActivityForResult(intent, REQ_CODE_ALL_FILES_ACCESS)
                } catch (_: Exception) {
                    // OEM may not expose the per-app intent; open the
                    // generic all-files-access settings page as a fallback.
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                        )
                        startActivityForResult(intent, REQ_CODE_ALL_FILES_ACCESS)
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not open All files access settings", e)
                    }
                }
            }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .show()
    }

    // Per game screen, not per process: Android often keeps the process alive between boots,
    // so a process-wide flag skipped the question on later launches. Carried only across the
    // recreate() the prompt itself triggers (via saved state); every fresh boot asks again.
    private var hudAskedThisLaunch = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_HUD_ASKED, hudAskedThisLaunch)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        hudAskedThisLaunch = savedInstanceState?.getBoolean(KEY_HUD_ASKED, false) ?: false
        SavesRestoring.doSmth(this)

        // Set cutout mode before super.onCreate or setContentView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        super.onCreate(savedInstanceState)
        m_Activity = this
    }

    override fun onStart() {
        // Gate the native loader behind storage access. On API 30+ that means
        // All files access (MANAGE_EXTERNAL_STORAGE); on API 23-29 it means
        // the runtime WRITE_EXTERNAL_STORAGE grant. If the precondition isn't
        // met, defer the loader and prompt instead of starting the engine
        // (which would mkdir under /sdcard and fail with EACCES, surfaced by
        // the engine as a misleading "not enough storage" error).
        // super.onStart() must always be called (Android enforces this), so we
        // set the deferral flag first.
        m_DeferLoader = needsStorageAccess() || needsHudChoice()
        super.onStart()
        Log.v(TAG, "onStart")
        if (needsStorageAccess()) {
            Log.w(TAG, "Storage access not granted — prompting")
            promptForStorageAccess()
        } else if (needsHudChoice()) {
            promptHudChoice()
        } else {
            ensureObbDir()
        }
    }

    // Ask once per launch (when a controller is connected) whether to use the compact
    // Controller HUD. The loader waits for the answer so the HUD is right from the start.
    private fun needsHudChoice(): Boolean =
        !hudAskedThisLaunch && s3eTouchpad.shouldAskHud(this) && s3eTouchpad.hasAnalogController()

    private fun promptHudChoice() {
        val dontAsk = CheckBox(this).apply { setText(R.string.controller_hud_dont_ask) }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(dontAsk)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.controller_hud_prompt_title)
            .setMessage(R.string.controller_hud_prompt_message)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(R.string.controller_hud_choice_controller) { _, _ -> applyHudChoice(true, dontAsk.isChecked) }
            .setNegativeButton(R.string.controller_hud_choice_touch) { _, _ -> applyHudChoice(false, dontAsk.isChecked) }
            .show()
    }

    private fun applyHudChoice(controllerHud: Boolean, dontAskAgain: Boolean) {
        s3eTouchpad.setControllerHud(this, controllerHud)
        if (dontAskAgain) s3eTouchpad.setAskHud(this, false)
        hudAskedThisLaunch = true
        // Same pattern as the storage prompt: recreate re-runs onStart and the loader starts.
        recreate()
    }

    private fun needsStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            !Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            !hasWriteExternalStorage()
        } else {
            false
        }

    private fun promptForStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestAllFilesAccess()
        } else {
            @Suppress("DEPRECATION")
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_CODE_WRITE_STORAGE
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE_ALL_FILES_ACCESS) {
            // recreate() drives onCreate->onStart again so the gate re-checks;
            // if granted, the loader starts. If not, the prompt re-shows.
            recreate()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE_WRITE_STORAGE) {
            // Recreate re-runs onStart; if granted it proceeds to the loader
            // after ensureObbDir(), otherwise the prompt re-shows.
            recreate()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_POWER || keyCode == KeyEvent.KEYCODE_HOME) {
            Log.v(TAG, "onKeyDown Received: $keyCode")
            if (IsDevice.getInstance().IsActivated()) {
                IsDevice.getInstance().IsDeviceKeyCallback(keyCode)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        Log.v(TAG, "onPause")
        super.onPause()
    }

    override fun onResume() {
        Log.v(TAG, "onResume")
        super.onResume()
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyImmersiveMode() {
        val flags = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_IMMERSIVE)

        window.decorView.systemUiVisibility = flags
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ (API 30+)
                val insetsController = window.insetsController
                if (insetsController != null) {
                    insetsController.hide(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                    )
                    insetsController.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    )
                }
                window.setDecorFitsSystemWindows(false)
            } else {
                applyLegacyImmersiveMode()
            }
        }

        if (!hasFocus && IsDevice.getInstance().IsActivated()) {
            IsDevice.getInstance().IsDeviceKeyCallback(KeyEvent.KEYCODE_HOME)
        }
    }
}
