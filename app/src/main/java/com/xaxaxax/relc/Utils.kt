package com.xaxaxax.relc

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager

/**
 * 更新清單中指定索引處的值
 */
fun <T> List<T>.update(index: Int, item: T): List<T> {
    return slice(0 until index) + item + slice(index + 1 until size)
}

/**
 * 更新清單中指定索引處的值
 */
fun <T> List<T>.update(index: Int, transform: (T) -> T): List<T> {
    val item = this.getOrNull(index) ?: return this.toList()
    return slice(0 until index) + transform(item) + slice(index + 1 until size)
}

fun getDefaultLayoutParams() = WindowManager.LayoutParams(
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.TOP or Gravity.START
}