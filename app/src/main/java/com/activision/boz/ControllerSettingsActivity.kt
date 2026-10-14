package com.activision.boz

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.ideaworks3d.marmalade.s3eTouchpad

/**
 * Controller settings, reached by long-pressing the app icon ("Controller settings").
 * Kept outside the game's own Flash menus, which cannot take new widgets without new art.
 */
class ControllerSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.controller_settings_title)

        val pad = dp(20)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val hudSwitch = Switch(this).apply {
            setText(R.string.controller_hud_label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            isChecked = s3eTouchpad.isControllerHud(this@ControllerSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                s3eTouchpad.setControllerHud(this@ControllerSettingsActivity, checked)
            }
        }
        layout.addView(hudSwitch)

        layout.addView(TextView(this).apply {
            setText(R.string.controller_hud_warning)
            setPadding(0, dp(12), 0, 0)
        })

        layout.addView(Switch(this).apply {
            setText(R.string.controller_ask_label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, dp(20), 0, 0)
            isChecked = s3eTouchpad.shouldAskHud(this@ControllerSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                s3eTouchpad.setAskHud(this@ControllerSettingsActivity, checked)
            }
        })

        setContentView(layout)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
