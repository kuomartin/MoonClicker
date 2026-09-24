package com.xaxaxax.moonclicker.workbench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.xaxaxax.moonclicker.core.AppSettings
import com.xaxaxax.moonclicker.notification.ScriptStatusNotifier
import com.xaxaxax.moonclicker.script.ScriptSession
import com.xaxaxax.moonclicker.script.ScriptStore
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
import com.xaxaxax.moonclicker.ui.displaydetail.DisplayThumbnailCache
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真機上重現過的 bug：`embeddedServer` 綁 wildcard host 會變成 dual-stack IPv6 socket，
 * 連線能完成三次握手但永遠讀不到資料（ESTABLISHED 卡死）。loopback 走純 IPv4 不會踩到，
 * 只有實際透過網卡位址連線才會——所以這裡刻意連到 [WorkbenchServer.localIpv4Address]
 * 解出來的那個位址，而不是測 127.0.0.1，且用短逾時讓「卡死」直接變成測試失敗，
 * 不是把 CI 一起卡住。
 *
 * 不測 loopback：server 現在刻意只 bind 在裝置實際的 WiFi IPv4（見 [WorkbenchServer]），
 * 不再監聽 0.0.0.0，127.0.0.1 連不上是預期行為，不是這裡要守的東西——沒有任何功能依賴
 * 從裝置自己連自己的 workbench server。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class WorkbenchServerConnectivityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shizukuManager = ShizukuManager(context)
    private val server = WorkbenchServer(
        scriptStore = ScriptStore(context),
        scriptSession = ScriptSession(
            context = context,
            shizukuManager = shizukuManager,
            notifier = ScriptStatusNotifier(context),
            settings = AppSettings(context),
        ),
        shizukuManager = shizukuManager,
        thumbnailCache = DisplayThumbnailCache(context),
        appSettings = AppSettings(context),
        authStore = WorkbenchAuthStore(context),
    )

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun health_check_succeeds_over_the_address_the_server_actually_bound() {
        assertTrue("server failed to start", server.start())
        val host = server.localIpv4Address()
        // 退回 "0.0.0.0" 就是退回會觸發 dual-stack wildcard bug 的起點，見
        // WorkbenchServer.localIpv4Address 的說明——這裡直接把回歸擋在測試裡。
        assertNotEquals("must not fall back to the wildcard address", "0.0.0.0", host)
        assertHealthy(host)
    }

    /** 開一條真的 TCP 連線、送真的 HTTP request，逾時設短一點，卡死就是測試失敗。 */
    private fun assertHealthy(host: String) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, WorkbenchServer.PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            val statusLine = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
            assertEquals("HTTP/1.1 200 OK", statusLine)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3000
        const val READ_TIMEOUT_MS = 3000
    }
}
