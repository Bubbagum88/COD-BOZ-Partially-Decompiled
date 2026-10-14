package com.ideaworks3d.marmalade

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewParent
import kotlin.math.abs
import kotlin.math.sqrt

class s3eTouchpad {
    private var processPositionEvents = false
    private var inputDeviceId = -1

    fun s3eTouchpadInit(): Boolean {
        val ids = InputDevice.getDeviceIds()
        for (id in ids) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.sources and SOURCE_TOUCHPAD > 0) {
                val xRange = device.getMotionRange(0)
                val yRange = device.getMotionRange(1)
                if (xRange != null && yRange != null) {
                    m_Width = xRange.max.toInt()
                    m_Height = yRange.max.toInt()
                    trace("Detected touchpad with m_Width: $m_Width m_Height: $m_Height")
                    if (m_Width > 0 && m_Height > 0) {
                        if (setProcessPositionEvents(true)) {
                            inputDeviceId = id
                            return true
                        }
                        trace("setProcessPositionEvents failed")
                        return false
                    }
                }
            }
        }
        // No physical touchpad. Without a controller, behave exactly as before; with one,
        // expose a virtual Xperia Play-sized pad driven by its analog sticks.
        if (!hasAnalogController()) {
            trace("Couldn't detect touchpad or analog controller")
            return false
        }
        m_Width = VIRTUAL_WIDTH
        m_Height = VIRTUAL_HEIGHT
        inputDeviceId = VIRTUAL_DEVICE_ID
        virtualPad = true
        controllerHud = isControllerHud(LoaderActivity.m_Activity!!)
        trace("No touchpad found; using virtual stick pad m_Width: $m_Width m_Height: $m_Height")
        return true
    }

    fun s3eTouchpadTerminate() {
        if (processPositionEvents) {
            setProcessPositionEvents(false)
        }
    }

    fun s3eTouchpadGetInt(prop: Int): Int {
        if (prop == S3E_TOUCHPAD_AVAILABLE) {
            LoaderAPI.trace("Touchpad GetInt S3E_TOUCHPAD_AVAILABLE")
            if (inputDeviceId == -1) return 0
            // Virtual pad: "not available" keeps the original HUD and all touch schemes; "available"
            // switches to the compact Xperia HUD and disables the on-screen camera stick. The engine
            // reads the sticks either way, so this only follows the player's Controller HUD choice.
            if (inputDeviceId == VIRTUAL_DEVICE_ID) return if (controllerHud) 1 else 0
            val cfg: Configuration = LoaderActivity.m_Activity!!.resources.configuration
            val legacyId = "3.0.A.2."
            try {
                LoaderAPI.trace("Android build: ${Build.ID}")
                LoaderAPI.trace("Checking for legacy Xperia Play build ID: $legacyId")
                if (Build.ID.startsWith(legacyId) && Build.ID.substring(legacyId.length).toInt() <= 181) {
                    LoaderAPI.trace("Found... Using legacy Configuration enum")
                    return if (cfg.hardKeyboardHidden != 2) 1 else 0
                }
            } catch (_: NumberFormatException) {
                LoaderAPI.trace("Error parsing build ID")
            }
            val navAvailable = if (cfg.navigationHidden != 2) 1 else 0
            LoaderAPI.trace("Touchpad available: $navAvailable")
            return navAvailable
        } else if (prop == S3E_TOUCHPAD_WIDTH) {
            return m_Width
        } else if (prop == S3E_TOUCHPAD_HEIGHT) {
            return m_Height
        }
        return 0
    }

    fun setProcessPositionEvents(process: Boolean): Boolean {
        trace("setProcessPositionEvents$process")
        return try {
            val parent: ViewParent = LoaderActivity.m_Activity!!.window.decorView.rootView.parent
            val clazz = Class.forName("android.view.ViewRoot")
            val m = clazz.getMethod("setProcessPositionEvents", java.lang.Boolean.TYPE)
            m.invoke(parent, process)
            true
        } catch (e: Exception) {
            trace("Exception accessing trackpad:$e")
            false
        }
    }

    private fun trace(msg: String) {
        LoaderAPI.traceChan(className() + "-" + Thread.currentThread().name, msg)
    }

    private fun className(): String {
        val full = this::class.java.name
        return full.substring(full.lastIndexOf('.') + 1)
    }

    companion object {
        private const val SOURCE_TOUCHPAD = 1048584
        private var m_Width = 0
        private var m_Height = 0

        const val S3E_TOUCHPAD_AVAILABLE = 0
        const val S3E_TOUCHPAD_WIDTH = 1
        const val S3E_TOUCHPAD_HEIGHT = 2

        // Virtual touchpad (Xperia Play pad size) fed by the analog sticks.
        private const val VIRTUAL_DEVICE_ID = -2
        private const val VIRTUAL_WIDTH = 966
        private const val VIRTUAL_HEIGHT = 360
        // Action codes the engine expects: Android action + 4 (down 4, up 5, move 6).
        private const val S3E_DOWN = MotionEvent.ACTION_DOWN + 4
        private const val S3E_UP = MotionEvent.ACTION_UP + 4
        private const val S3E_MOVE = MotionEvent.ACTION_MOVE + 4
        private var virtualPad = false
        private val stickDown = booleanArrayOf(false, false)
        // Right stick (finger 1) looks around, with a gain over the 1:1 stick-to-pad mapping.
        private const val LOOK_POINTER = 1
        private const val LOOK_GAIN = 1.5f

        // "Controller HUD" setting: when on, the virtual pad reports available (compact Xperia HUD).
        private const val PREFS = "controller"
        private const val KEY_CONTROLLER_HUD = "controller_hud"
        private const val KEY_ASK_HUD = "ask_hud"
        @Volatile private var controllerHud = false

        /** True when a controller with analog sticks is connected. */
        @JvmStatic
        fun hasAnalogController(): Boolean = InputDevice.getDeviceIds().any { id ->
            val sources = InputDevice.getDevice(id)?.sources ?: 0
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Whether to ask Controller HUD vs Touch HUD at start-up (on by default). */
        @JvmStatic
        fun shouldAskHud(context: Context): Boolean = prefs(context).getBoolean(KEY_ASK_HUD, true)

        @JvmStatic
        fun setAskHud(context: Context, ask: Boolean) {
            prefs(context).edit().putBoolean(KEY_ASK_HUD, ask).apply()
        }

        @JvmStatic
        fun isControllerHud(context: Context): Boolean = prefs(context).getBoolean(KEY_CONTROLLER_HUD, false)

        /** Saves the setting and applies it to the running game's next availability query. */
        @JvmStatic
        fun setControllerHud(context: Context, on: Boolean) {
            prefs(context).edit().putBoolean(KEY_CONTROLLER_HUD, on).apply()
            controllerHud = on
        }

        /** True once the engine has initialised the touchpad and no physical one exists. */
        @JvmStatic
        fun hasVirtualPad(): Boolean = virtualPad

        /** Lifts both virtual fingers (controller switched, disconnected, or focus lost). */
        @JvmStatic
        fun releaseVirtualSticks() {
            for (pointerId in stickDown.indices) {
                updateVirtualStick(pointerId, 0f, 0f, 1f)
            }
        }

        /**
         * Left stick = finger 0 on the left half of the pad, right stick = finger 1 on the right half.
         * The finger touches down when the stick leaves its dead zone and lifts when it returns.
         */
        @JvmStatic
        fun updateVirtualStick(pointerId: Int, stickX: Float, stickY: Float, deadzone: Float) {
            if (!virtualPad) return
            val active = stickX * stickX + stickY * stickY > deadzone * deadzone
            // Sticks report a circle (a full diagonal is ~0.71 per axis) but the game reads the pad
            // per axis, so stretch the circle onto the square: full diagonals reach the corner and
            // move as fast as straight ahead; straight and partial pushes keep their feel.
            val magnitude = sqrt(stickX * stickX + stickY * stickY)
            val largest = maxOf(abs(stickX), abs(stickY))
            val stretch = if (largest > 0f) magnitude / largest else 1f
            val ax = (stickX * stretch).coerceIn(-1f, 1f)
            val ay = (stickY * stretch).coerceIn(-1f, 1f)
            val halfW = m_Width / 2
            val cx = if (pointerId == 0) halfW / 2 else halfW + halfW / 2
            val cy = m_Height / 2
            val radius = minOf(halfW, m_Height) / 2 - 1
            // Pad Y follows stick Y: stick up (negative AXIS_Y) is a lower pad Y.
            val x: Int
            val y: Int
            if (pointerId == LOOK_POINTER) {
                // Look gain on both axes, clamped to the pad's right half so the finger never
                // crosses into the movement half. The in-game sensitivity still scales this.
                x = (cx + (ax * LOOK_GAIN * radius).toInt()).coerceIn(halfW + 1, m_Width - 1)
                y = (cy + (ay * LOOK_GAIN * radius).toInt()).coerceIn(0, m_Height - 1)
            } else {
                x = cx + (ax * radius).toInt()
                y = cy + (ay * radius).toInt()
            }
            if (active && !stickDown[pointerId]) {
                onMotionEvent(pointerId, S3E_DOWN, cx, cy)
                onMotionEvent(pointerId, S3E_MOVE, x, y)
                stickDown[pointerId] = true
            } else if (active) {
                onMotionEvent(pointerId, S3E_MOVE, x, y)
            } else if (stickDown[pointerId]) {
                onMotionEvent(pointerId, S3E_UP, cx, cy)
                stickDown[pointerId] = false
            }
        }

        @JvmStatic
        external fun onMotionEvent(pointerId: Int, action: Int, x: Int, y: Int)

        @JvmStatic
        fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.source != SOURCE_TOUCHPAD) return false
            val action = event.action
            val actionIndex = event.actionIndex
            if (action == MotionEvent.ACTION_MOVE) {
                val count = event.pointerCount
                for (i in 0 until count) {
                    onMotionEvent(
                        event.getPointerId(i),
                        action + 4,
                        event.getX(i).toInt(),
                        m_Height - event.getY(i).toInt(),
                    )
                }
            } else if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) {
                val pointerId = event.getPointerId(actionIndex)
                val masked = action and 0xff
                val mapped = if (masked == 6 || masked == 5) masked - 5 else masked
                onMotionEvent(
                    pointerId,
                    mapped + 4,
                    event.getX(actionIndex).toInt(),
                    m_Height - event.getY(actionIndex).toInt(),
                )
            } else {
                val pointerId = event.getPointerId(0)
                onMotionEvent(pointerId, action + 4, event.x.toInt(), m_Height - event.y.toInt())
            }
            return true
        }
    }
}
