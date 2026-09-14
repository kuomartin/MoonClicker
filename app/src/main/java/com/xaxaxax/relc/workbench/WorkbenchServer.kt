package com.xaxaxax.relc.workbench

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Script Workbench 的內嵌 HTTP server。開關與生命週期由 [com.xaxaxax.relc.workbench.WorkbenchService]
 * 這個獨立的、使用者手動控制的前景服務承載，與腳本執行狀態無關。
 *
 * 監聽所有網卡（不只 loopback）是刻意的：VS Code 端在開發者的電腦上，透過同一個區網連進來，
 * milestone 1 沒有配對驗證（見 #53 Out of Scope），這是已知、記錄在案的風險，不是這裡能修的漏洞。
 */
@Singleton
class WorkbenchServer @Inject constructor() {
    private var server: EmbeddedServer<*, *>? = null

    /** @return 是否成功啟動——bind 失敗（例如 port 被佔用）時回傳 false，不讓例外往外拋。 */
    fun start(): Boolean {
        if (server != null) return true
        return try {
            // 不指定 host 會讓底層 bind 成 dual-stack IPv6 wildcard（已在真機上驗證：LISTEN
            // 位址是全零的 ::，不是 0.0.0.0）。Android 的 epoll-based Selector 對「剛 accept、
            // 來源是 IPv4-mapped-IPv6 位址」的 channel 會漏掉第一次 OP_READ 就緒事件，導致
            // 連線停在 ESTABLISHED 卻永遠讀不到資料——loopback 走純 IPv4 不會踩到，跨裝置走
            // WiFi 才會。直接 bind 裝置在區網上的實際 IPv4 位址，繞開 wildcard 的雙棧歧義。
            server = embeddedServer(
                CIO,
                port = PORT,
                host = localIpv4Address(),
                module = Application::workbenchModule,
            ).start(wait = false)
            true
        } catch (t: Throwable) {
            Timber.e(t, "WorkbenchServer failed to bind port $PORT")
            false
        }
    }

    /**
     * 找裝置在區網（WiFi）上的實際 IPv4 位址；找不到就退回 loopback——不能退回 "0.0.0.0"，
     * 那個字串本來就是預設值，退回它等於繞了一圈又回到會觸發 dual-stack wildcard 那個 bug
     * 的起點（已在真機上驗證：退回 wildcard 時連 loopback 都連不上，不只是跨裝置連不上）。
     */
    internal fun localIpv4Address(): String =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
            ?: "127.0.0.1"

    fun stop() {
        server?.stop()
        server = null
    }

    companion object {
        const val PORT = 8787
    }
}

fun Application.workbenchModule() {
    routing {
        get("/health") {
            call.respondText("OK")
        }
    }
}
