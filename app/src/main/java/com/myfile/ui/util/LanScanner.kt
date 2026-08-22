package com.myfile.ui.util

import android.content.Context
import com.myfile.ui.model.ConnectionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Dò các máy trong cùng mạng LAN (dải /24 của IP hiện tại) đang mở cổng của giao thức
 * đang chọn (FTP 21, SFTP 22, SMB 445). Quét song song 254 địa chỉ với timeout ngắn mỗi
 * kết nối để hoàn tất nhanh trên mạng WiFi gia đình/văn phòng thông thường.
 */
object LanScanner {

    data class FoundHost(val ip: String, val port: Int)

    private const val CONNECT_TIMEOUT_MS = 250
    private const val MAX_PARALLEL = 64

    suspend fun scan(context: Context, type: ConnectionType): List<FoundHost> = withContext(Dispatchers.IO) {
        val localIp = NetworkUtils.getLocalIpAddress(context) ?: return@withContext emptyList()
        val prefix = localIp.substringBeforeLast('.')
        val port = defaultPortFor(type)
        val results = mutableListOf<FoundHost>()

        // Chia 254 host thành từng lô để giới hạn số socket mở song song
        (1..254).chunked(MAX_PARALLEL).forEach { chunk ->
            val jobs = chunk.map { last ->
                async {
                    val ip = "$prefix.$last"
                    if (ip == localIp) return@async null
                    if (isPortOpen(ip, port)) FoundHost(ip, port) else null
                }
            }
            results.addAll(jobs.awaitAll().filterNotNull())
        }
        results.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    private fun isPortOpen(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun defaultPortFor(type: ConnectionType): Int = when (type) {
        ConnectionType.FTP -> 21
        ConnectionType.SFTP -> 22
        ConnectionType.SMB -> 445
    }
}
