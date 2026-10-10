package com.xaxaxax.moonclicker

import android.os.Binder
import android.view.Surface
import androidx.annotation.Keep
import timber.log.Timber
import java.io.Closeable

/**
 * client 端的 DisplaySink（見 CONTEXT.md）：把 [surface] 掛到 [displayId] 的影格上，直到 [close]。
 *
 * 物件本身同步建立，在服務端的身分是它自己的 token，所以呼叫端拿到物件的當下就能關它，
 * 不必等 [attach] 回來。[attach] 與 [close] 互斥：[close] 先到時之後的 [attach] 直接回 false，
 * 不會留下一個沒人會關的 sink。進程死掉時服務端從 token 得知，自己釋放。
 *
 * 顯示器 resize 或銷毀時服務端會丟掉這個 sink，之後 [close] 只是 no-op；要繼續看就建新的。
 *
 * native 的 ScriptRuntime 以 JNI 呼叫 [close]，名稱不能被混淆。
 */
@Keep
class DisplaySink(
    private val displayId: Int,
    private val surface: Surface,
) : Closeable {
    private val token = Binder()
    private var service: IMoonClickerService? = null
    private var closed = false

    /** 已經關掉、已經掛上、或服務端拒絕時回 false。binder 呼叫本身的例外照常拋出。 */
    @Synchronized
    fun attach(service: IMoonClickerService): Boolean {
        if (closed || this.service != null) return false
        if (!service.attachDisplaySink(displayId, surface, token)) return false
        this.service = service
        return true
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val attachedTo = service ?: return
        service = null
        try {
            attachedTo.detachDisplaySink(token)
        } catch (t: Throwable) {
            // 服務已經不在：sink 跟著它一起消失了。
            Timber.w(t, "detachDisplaySink failed for display $displayId")
        }
    }
}
