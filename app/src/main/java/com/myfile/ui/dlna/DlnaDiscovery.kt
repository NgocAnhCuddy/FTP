package com.myfile.ui.dlna

import com.myfile.ui.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL

/** 1 thiết bị DLNA/UPnP tìm thấy trong mạng, đã lấy được URL điều khiển AVTransport. */
data class DlnaDevice(
    val friendlyName: String,
    val location: String,
    val controlUrl: String,
    val serviceType: String
)

/**
 * Dò thiết bị hỗ trợ DLNA (TV thông minh, loa, đầu phát mạng...) qua SSDP multicast,
 * sau đó tải file mô tả XML của từng thiết bị để lấy URL điều khiển AVTransport
 * (dùng để gửi lệnh "phát URL này").
 */
object DlnaDiscovery {

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val TIMEOUT_MS = 3000

    suspend fun discover(): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val locations = sendSsdpSearch()
        locations.mapNotNull { location ->
            try {
                fetchDeviceInfo(location)
            } catch (e: Exception) {
                LogBus.warning("Không đọc được mô tả thiết bị DLNA: $location", source = "DLNA")
                null
            }
        }
    }

    private fun sendSsdpSearch(): Set<String> {
        val locations = mutableSetOf<String>()
        val socket = DatagramSocket().apply { soTimeout = TIMEOUT_MS }
        try {
            val query = """
                M-SEARCH * HTTP/1.1
                HOST: $SSDP_ADDRESS:$SSDP_PORT
                MAN: "ssdp:discover"
                MX: 2
                ST: $SEARCH_TARGET

            """.trimIndent().replace("\n", "\r\n")

            val group = InetAddress.getByName(SSDP_ADDRESS)
            val sendPacket = DatagramPacket(query.toByteArray(), query.toByteArray().size, group, SSDP_PORT)
            socket.send(sendPacket)

            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                try {
                    val receivePacket = DatagramPacket(buf, buf.size)
                    socket.receive(receivePacket)
                    val response = String(receivePacket.data, 0, receivePacket.length)
                    val location = response.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(":", "")
                        ?.trim()
                    if (!location.isNullOrBlank()) {
                        // location có thể bắt đầu bằng "http" đã bị cắt mất "http" ở substringAfter(":")
                        // do dùng dấu ':' đầu tiên — nên tách lại đúng cách:
                        val full = response.lineSequence()
                            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                            ?.substringAfter("LOCATION:", "")
                            ?.trim()
                        if (!full.isNullOrBlank()) locations.add(full)
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    break
                }
            }
        } catch (e: Exception) {
            LogBus.warning("Lỗi khi dò thiết bị DLNA qua SSDP", source = "DLNA")
        } finally {
            socket.close()
        }
        return locations
    }

    private fun fetchDeviceInfo(location: String): DlnaDevice? {
        // URL.readText() không có timeout mặc định — nếu thiết bị trả lời SSDP nhưng sau đó
        // không phản hồi HTTP GET (kết nối treo, tường lửa 1 chiều), request có thể treo VÔ THỜI
        // HẠN. Dù chạy trên Dispatchers.IO nên không gây ANR UI trực tiếp, nó khiến discover()
        // không bao giờ hoàn tất, làm màn "Phát lên TV" kẹt ở trạng thái đang quét mãi mãi.
        val connection = URL(location).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        val xml = connection.inputStream.bufferedReader().use { it.readText() }
        val friendlyName = Regex("<friendlyName>(.*?)</friendlyName>").find(xml)?.groupValues?.get(1)
            ?: "Thiết bị không tên"

        // Tìm block <service> có AVTransport, lấy controlURL bên trong cùng block đó
        val serviceBlockRegex = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL)
        val avService = serviceBlockRegex.findAll(xml).map { it.groupValues[1] }
            .firstOrNull { it.contains("AVTransport") } ?: return null

        val serviceType = Regex("<serviceType>(.*?)</serviceType>").find(avService)?.groupValues?.get(1)
            ?: SEARCH_TARGET
        val controlPath = Regex("<controlURL>(.*?)</controlURL>").find(avService)?.groupValues?.get(1)
            ?: return null

        val baseUrl = URL(location)
        val controlUrl = if (controlPath.startsWith("http")) {
            controlPath
        } else {
            URL(baseUrl, controlPath).toString()
        }

        return DlnaDevice(friendlyName = friendlyName, location = location, controlUrl = controlUrl, serviceType = serviceType)
    }
}
