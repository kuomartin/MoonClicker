package com.xaxaxax.relc.shizuku

import android.content.Context
import android.hardware.input.IInputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object InputManager {
    private var INSTANCE: IInputManager? = null
    private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    fun getInstance(): IInputManager = INSTANCE ?: run {
        val binder = ShizukuBinderWrapper(
            SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)
        )
        IInputManager.Stub.asInterface(binder).also { INSTANCE = it }
    }

    fun touchDown(x: Int, y: Int, pointerId: Int, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now, now,
            MotionEvent.ACTION_DOWN,
            x.toFloat(), y.toFloat(),
            1.0f, // pressure
            1.0f, // size
            0, // metaState
            1.0f, // xPrecision
            1.0f, // yPrecision
            0, // deviceId
            0 // edgeFlags
        )
        event.apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        getInstance().injectInputEvent(event, INJECT_INPUT_EVENT_MODE_ASYNC)
        event.recycle()
    }

    fun touchMove(x: Int, y: Int, pointerId: Int, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now, now,
            MotionEvent.ACTION_MOVE,
            x.toFloat(), y.toFloat(),
            1.0f, // pressure
            1.0f, // size
            0, // metaState
            1.0f, // xPrecision
            1.0f, // yPrecision
            0, // deviceId
            0 // edgeFlags
        )
        event.apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        getInstance().injectInputEvent(event, INJECT_INPUT_EVENT_MODE_ASYNC)
        event.recycle()
    }

    fun touchUp(x: Int, y: Int, pointerId: Int, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now, now,
            MotionEvent.ACTION_UP,
            x.toFloat(), y.toFloat(),
            0.0f, // pressure (0 when up)
            1.0f, // size
            0, // metaState
            1.0f, // xPrecision
            1.0f, // yPrecision
            0, // deviceId
            0 // edgeFlags
        )
        event.apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        getInstance().injectInputEvent(event, INJECT_INPUT_EVENT_MODE_ASYNC)
        event.recycle()
    }

}