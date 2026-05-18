package com.xaxaxax.relc

fun <T> List<T>.update(index: Int, item: T): List<T> {
    return slice(0 until index) + item + slice(index + 1 until size)
}

fun <T> List<T>.update(index: Int, transform: (T) -> T): List<T> {
    val item = this.getOrNull(index) ?: return this.toList()
    return slice(0 until index) + transform(item) + slice(index + 1 until size)
}