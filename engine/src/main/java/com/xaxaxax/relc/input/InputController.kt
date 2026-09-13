package com.xaxaxax.relc.input

import android.graphics.Matrix
import android.view.InputDevice
import android.view.MotionEvent
import com.xaxaxax.relc.IRelcV2Service
import timber.log.Timber

/**
 * High-level input controller that uses IRelcV2Service to inject events.
 */
class InputController(private val service: IRelcV2Service) {

    /**
     * Injects a MotionEvent into the specified display.
     * Supports optional transformation matrix for interactive forwarding (scaling, translation).
     */
    fun injectMotionEvent(
        event: MotionEvent,
        displayId: Int,
        transform: Matrix? = null
    ) {
        val eventCopy = MotionEvent.obtain(event)
        eventCopy.source = InputDevice.SOURCE_TOUCHSCREEN

        if (transform != null && !transform.isIdentity) {
            eventCopy.transform(transform)
        }

        try {
            service.injectMotionEvent(eventCopy, displayId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to inject motion event")
        } finally {
            eventCopy.recycle()
        }
    }
}
