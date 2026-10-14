package com.ideaworks3d.marmalade

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Gamepad support on top of the Xperia Play-style controls.
 *
 * - Sticks drive the virtual touchpad in [s3eTouchpad] (left = move, right = look).
 *   Any connected controller works; the one engaged most recently drives the sticks,
 *   so switching controllers is automatic and two controllers never fight.
 * - Hat-axis D-pads become D-pad key presses, analog-only triggers become L2/R2 presses.
 * - Dead zones come from each controller's own reported flat range (with a minimum).
 * - Held input is released when a controller disconnects or the game loses focus.
 *
 * Not JNI-bound: free to rename or restructure (unlike LoaderActivity / s3eTouchpad).
 */
class GamepadInput(
    context: Context,
    private val sendKey: (keyCode: Int, down: Boolean) -> Unit,
) : InputManager.InputDeviceListener {

    private class DeviceState {
        var engaged = false           // a stick or trigger is outside its dead zone
        var sendsDpadKeys = false     // reports real D-pad key codes, so its hat axes are ignored
        var sendsTriggerKeys = false  // reports real L2/R2 key codes, so its trigger axes are ignored
        val heldKeys = HashSet<Int>() // key presses we synthesized and have not released yet
    }

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val devices = HashMap<Int, DeviceState>()
    private var activeDevice = NO_DEVICE
    private var screenTouched = false // a finger on the touch screen has priority over the sticks

    fun start() = inputManager.registerInputDeviceListener(this, null)

    fun stop() {
        inputManager.unregisterInputDeviceListener(this)
        releaseAll()
    }

    /** Returns false for keys that should bypass the engine (volume and mute stay with Android). */
    fun shouldForwardKey(event: KeyEvent): Boolean {
        if (isSystemKey(event.keyCode)) return false
        if (isGamepadSource(event.source)) {
            val state = state(event.deviceId)
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> state.sendsDpadKeys = true
                KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> state.sendsTriggerKeys = true
            }
            if (event.action == KeyEvent.ACTION_DOWN) activeDevice = event.deviceId
        }
        return true
    }

    /** Handles joystick motion. Returns false (Android default handling) when the virtual pad is not in use. */
    fun onMotion(event: MotionEvent): Boolean {
        if (!s3eTouchpad.hasVirtualPad()) return false
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val device = event.device ?: return false
        val id = event.deviceId
        val state = state(id)

        // Right stick is Z/RZ on most controllers, RX/RY on some.
        val hasZ = device.getMotionRange(MotionEvent.AXIS_Z, event.source) != null
        val rxAxis = if (hasZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX
        val ryAxis = if (hasZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY

        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)
        val rx = event.getAxisValue(rxAxis)
        val ry = event.getAxisValue(ryAxis)
        val leftDeadzone = deadzone(device, MotionEvent.AXIS_X, event.source)
        val rightDeadzone = deadzone(device, rxAxis, event.source)
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))

        val engagedNow = outside(lx, ly, leftDeadzone) || outside(rx, ry, rightDeadzone) ||
            lt > TRIGGER_RELEASE || rt > TRIGGER_RELEASE
        if (engagedNow && !state.engaged && id != activeDevice) {
            // A different controller just picked up: hand the sticks over to it.
            s3eTouchpad.releaseVirtualSticks()
            activeDevice = id
        }
        state.engaged = engagedNow

        if (id == activeDevice && !screenTouched) {
            s3eTouchpad.updateVirtualStick(0, lx, ly, leftDeadzone)
            s3eTouchpad.updateVirtualStick(1, rx, ry, rightDeadzone)
        }

        // D-pad and triggers act as buttons from any controller.
        if (!state.sendsDpadKeys) {
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            setHeld(state, KeyEvent.KEYCODE_DPAD_LEFT, hx < -HAT_THRESHOLD)
            setHeld(state, KeyEvent.KEYCODE_DPAD_RIGHT, hx > HAT_THRESHOLD)
            setHeld(state, KeyEvent.KEYCODE_DPAD_UP, hy < -HAT_THRESHOLD)
            setHeld(state, KeyEvent.KEYCODE_DPAD_DOWN, hy > HAT_THRESHOLD)
        }
        if (!state.sendsTriggerKeys) {
            setHeld(state, KeyEvent.KEYCODE_BUTTON_L2, triggerHeld(state, KeyEvent.KEYCODE_BUTTON_L2, lt))
            setHeld(state, KeyEvent.KEYCODE_BUTTON_R2, triggerHeld(state, KeyEvent.KEYCODE_BUTTON_R2, rt))
        }
        return true
    }

    /**
     * Touch-screen priority: while any finger is on the screen the sticks are paused (and
     * lifted), so on-screen move/look controls never fight the controller. Buttons are unaffected.
     */
    fun onScreenTouch(event: MotionEvent) {
        val touching = event.actionMasked != MotionEvent.ACTION_UP &&
            event.actionMasked != MotionEvent.ACTION_CANCEL
        if (touching && !screenTouched) s3eTouchpad.releaseVirtualSticks()
        screenTouched = touching
    }

    /** Releases every held stick and synthesized key (focus loss, pause). */
    fun releaseAll() {
        for (state in devices.values) {
            releaseKeys(state)
            state.engaged = false
        }
        s3eTouchpad.releaseVirtualSticks()
    }

    override fun onInputDeviceAdded(deviceId: Int) {}

    override fun onInputDeviceChanged(deviceId: Int) {}

    override fun onInputDeviceRemoved(deviceId: Int) {
        val state = devices.remove(deviceId) ?: return
        releaseKeys(state)
        if (deviceId == activeDevice) {
            s3eTouchpad.releaseVirtualSticks()
            activeDevice = NO_DEVICE
        }
    }

    private fun state(deviceId: Int): DeviceState = devices.getOrPut(deviceId) { DeviceState() }

    private fun setHeld(state: DeviceState, keyCode: Int, held: Boolean) {
        if (held == state.heldKeys.contains(keyCode)) return
        if (held) state.heldKeys.add(keyCode) else state.heldKeys.remove(keyCode)
        sendKey(keyCode, held)
    }

    private fun releaseKeys(state: DeviceState) {
        for (keyCode in state.heldKeys.toList()) {
            state.heldKeys.remove(keyCode)
            sendKey(keyCode, false)
        }
    }

    // Hysteresis: press past TRIGGER_PRESS, release below TRIGGER_RELEASE, so a half-pulled trigger does not flicker.
    private fun triggerHeld(state: DeviceState, keyCode: Int, value: Float): Boolean =
        if (state.heldKeys.contains(keyCode)) value > TRIGGER_RELEASE else value > TRIGGER_PRESS

    private fun deadzone(device: InputDevice, axis: Int, source: Int): Float =
        maxOf(device.getMotionRange(axis, source)?.flat ?: 0f, MIN_DEADZONE)

    private fun outside(x: Float, y: Float, deadzone: Float): Boolean = x * x + y * y > deadzone * deadzone

    private fun isGamepadSource(source: Int): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

    private fun isSystemKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

    companion object {
        private const val NO_DEVICE = -1
        private const val MIN_DEADZONE = 0.15f
        private const val HAT_THRESHOLD = 0.5f
        private const val TRIGGER_PRESS = 0.6f
        private const val TRIGGER_RELEASE = 0.35f
    }
}
