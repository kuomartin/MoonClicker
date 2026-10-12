package com.xaxaxax.moonclicker.service

/**
 * 讓基礎設施失敗以原本的訊息穿過 Binder 回到 client。`Parcel.writeException` 只認得少數幾種
 * 例外；其他型別在 client 端不會以原本的型別與訊息出現，所以包成 [IllegalStateException]。
 * cause 不會穿過 Binder，原因要寫進 message。
 */
internal fun Throwable.toBinderException(what: String): RuntimeException = when (this) {
    is IllegalStateException,
    is IllegalArgumentException,
    is SecurityException,
    is UnsupportedOperationException,
    is NullPointerException -> this as RuntimeException

    else -> IllegalStateException("$what: $this", this)
}
