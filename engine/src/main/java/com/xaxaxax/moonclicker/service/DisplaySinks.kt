package com.xaxaxax.moonclicker.service

import android.os.IBinder
import android.os.RemoteException
import timber.log.Timber

/**
 * 掛在 GLES distributor 上的 consumer surface（DisplaySink，見 CONTEXT.md），以 client 的 token
 * 為 key。每個 sink 各自撐著它顯示器的 display group 不閒置逾時（見
 * [VirtualDisplayLifecycle.holdAwake]），在下列任一情況釋放，而且只釋放一次：
 *
 * - client 呼叫 [detach]；
 * - distributor 消失（resize、destroy、legacy 鏡像結束），由 [onDistributorUnregistered] 通知；
 * - client 的 token 死掉（app 進程結束）。
 *
 * 不以 displayId 計數：同一個 token 只對應一個 sink，重複或過期的 detach 碰不到別人的 sink。
 *
 * [S] 是 surface 的型別，正式環境是 `android.view.Surface`；這裡從不讀它，只轉交給
 * [attachSurface]，所以 JVM 測試可以換成別的型別。
 */
internal class DisplaySinks<S>(
    /** 要掛的 displayId 對應到哪個 distributor（實體螢幕走鏡像，見 [DisplayMirroring.resolveDistributorId]）。 */
    private val resolveDistributorId: (displayId: Int) -> Int,
    /** 回傳 distributor 內的 handle，失敗回負值。 */
    private val attachSurface: (distributorId: Int, surface: S) -> Int,
    private val detachSurface: (distributorId: Int, handle: Int) -> Unit,
    /** 回傳放手的函式；不需要撐的顯示器回 null。見 [VirtualDisplayLifecycle.holdAwake]。 */
    private val holdAwake: (displayId: Int) -> (() -> Unit)?,
) {
    private inner class Sink(
        val token: IBinder,
        val distributorId: Int,
        val handle: Int,
        val releaseAwake: (() -> Unit)?,
    ) : IBinder.DeathRecipient {
        override fun binderDied() {
            Timber.d("DisplaySink owner died: distributor=$distributorId handle=$handle")
            detach(token)
        }
    }

    private val sinks = HashMap<IBinder, Sink>()

    /** 同一個 [token] 已經掛著時回 false。 */
    @Synchronized
    fun attach(displayId: Int, surface: S, token: IBinder): Boolean {
        if (token in sinks) {
            Timber.w("attachDisplaySink: token already attached")
            return false
        }
        val distributorId = resolveDistributorId(displayId)
        val handle = attachSurface(distributorId, surface)
        if (handle < 0) {
            Timber.e("attachDisplaySink: distributor not found for id=$displayId (distributorId=$distributorId)")
            return false
        }
        val sink = Sink(token, distributorId, handle, holdAwake(displayId))
        try {
            token.linkToDeath(sink, 0)
        } catch (e: RemoteException) {
            // client 在呼叫途中就死了，不會有人再來 detach。
            detachSurface(distributorId, handle)
            sink.releaseAwake?.invoke()
            return false
        }
        sinks[token] = sink
        return true
    }

    /** 不認得的 [token]（從沒掛過、已經 detach、或跟著 distributor 消失了）回 false。 */
    @Synchronized
    fun detach(token: IBinder): Boolean {
        val sink = sinks.remove(token) ?: return false
        token.unlinkToDeath(sink, 0)
        detachSurface(sink.distributorId, sink.handle)
        sink.releaseAwake?.invoke()
        return true
    }

    /** distributor 已經從登記移除，上面的 surface 跟它一起消失，不再向它 detach。 */
    @Synchronized
    fun onDistributorUnregistered(distributorId: Int) {
        val gone = sinks.values.filter { it.distributorId == distributorId }
        gone.forEach { sink ->
            sinks.remove(sink.token)
            sink.token.unlinkToDeath(sink, 0)
            sink.releaseAwake?.invoke()
        }
    }
}
