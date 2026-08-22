package com.myfile.ui.dlna

import com.myfile.ui.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** 1 máy chủ MediaServer (NAS, điện thoại khác, chính MyFile Manager trên máy khác...) tìm thấy trong LAN. */
data class RemoteMediaServer(
    val friendlyName: String,
    val location: String,
    val controlUrl: String
)

/** 1 mục trong danh sách duyệt của máy chủ từ xa: thư mục (container) hoặc file phát được (item). */
data class RemoteDidlItem(
    val id: String,
    val title: String,
    val isContainer: Boolean,
    /** Chỉ có giá trị khi isContainer = false — URL để phát trực tiếp qua HTTP. */
    val resUrl: String? = null,
    val mimeType: String? = null
)

/**
 * Dò các máy chủ UPnP MediaServer khác trong mạng (ST=ContentDirectory) và duyệt nội dung của
 * chúng qua SOAP Browse — đây là phần "control point" giống BubbleUPnP khi nó duyệt NAS/TV
 * khác thay vì chỉ phát file của chính máy mình. Không tải file về máy, chỉ lấy URL để
 * ExoPlayer/trình phát trong app mở trực tiếp qua mạng.
 */
object RemoteContentDirectoryClient {

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:service:ContentDirectory:1"
    private const val TIMEOUT_MS = 3000

    suspend fun discoverServers(): List<RemoteMediaServer> = withContext(Dispatchers.IO) {
        val locations = sendSsdpSearch()
        // Lấy mô tả từng server SONG SONG (không phải tuần tự): trước đây dùng mapNotNull chạy
        // lần lượt, nên 1 server trả lời SSDP nhưng có URL mô tả bị treo/mất mạng (không timeout)
        // sẽ kéo dài toàn bộ quá trình quét, khiến màn hình "đơ" cho tới khi từng cái timeout
        // xong. async song song + mỗi request có timeout riêng giới hạn tổng thời gian chờ.
        locations.map { location ->
            async {
                try {
                    fetchServerInfo(location)
                } catch (e: Exception) {
                    LogBus.warning("Không đọc được mô tả máy chủ media: $location", source = "DLNA")
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun browse(server: RemoteMediaServer, objectId: String = "0"): List<RemoteDidlItem> =
        withContext(Dispatchers.IO) {
            val body = """<?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                <ObjectID>${escapeXml(objectId)}</ObjectID>
                <BrowseFlag>BrowseDirectChildren</BrowseFlag>
                <Filter>*</Filter>
                <StartingIndex>0</StartingIndex>
                <RequestedCount>0</RequestedCount>
                <SortCriteria></SortCriteria>
                </u:Browse>
                </s:Body>
                </s:Envelope>""".trimIndent()

            val conn = URL(server.controlUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                conn.setRequestProperty("SOAPAction", "\"$SEARCH_TARGET#Browse\"")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode !in 200..299) {
                    LogBus.error("Máy chủ ${server.friendlyName} từ chối yêu cầu Browse (HTTP ${conn.responseCode})", source = "DLNA")
                    return@withContext emptyList()
                }
                val responseXml = conn.inputStream.bufferedReader().use { it.readText() }
                parseBrowseResponse(responseXml)
            } finally {
                conn.disconnect()
            }
        }

    private fun parseBrowseResponse(soapXml: String): List<RemoteDidlItem> {
        // <Result> trong SOAP response được HTML-escape (chứa DIDL-Lite XML lồng bên trong) —
        // phải unescape trước khi tách container/item.
        val resultXml = Regex("<Result>(.*?)</Result>", RegexOption.DOT_MATCHES_ALL)
            .find(soapXml)?.groupValues?.get(1)
            ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")
            ?.replace("&apos;", "'")?.replace("&amp;", "&")
            ?: return emptyList()

        val items = mutableListOf<RemoteDidlItem>()

        Regex("<container([^>]*)>(.*?)</container>", RegexOption.DOT_MATCHES_ALL).findAll(resultXml).forEach { m ->
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            val id = Regex("""id="([^"]*)"""").find(attrs)?.groupValues?.get(1) ?: return@forEach
            val title = Regex("<dc:title>(.*?)</dc:title>").find(inner)?.groupValues?.get(1) ?: id
            items.add(RemoteDidlItem(id = id, title = title, isContainer = true))
        }

        Regex("<item([^>]*)>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(resultXml).forEach { m ->
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            val id = Regex("""id="([^"]*)"""").find(attrs)?.groupValues?.get(1) ?: return@forEach
            val title = Regex("<dc:title>(.*?)</dc:title>").find(inner)?.groupValues?.get(1) ?: id
            val resTag = Regex("<res([^>]*)>(.*?)</res>", RegexOption.DOT_MATCHES_ALL).find(inner)
            val resUrl = resTag?.groupValues?.get(2)?.trim()
            val protocolInfo = resTag?.groupValues?.get(1)?.let {
                Regex("""protocolInfo="([^"]*)"""").find(it)?.groupValues?.get(1)
            }
            val mime = protocolInfo?.split(":")?.getOrNull(2)
            items.add(RemoteDidlItem(id = id, title = title, isContainer = false, resUrl = resUrl, mimeType = mime))
        }

        return items
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
                    val full = response.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter("LOCATION:", "")
                        ?.trim()
                    if (!full.isNullOrBlank()) locations.add(full)
                } catch (e: java.net.SocketTimeoutException) {
                    break
                }
            }
        } catch (e: Exception) {
            LogBus.warning("Lỗi khi dò máy chủ media qua SSDP", source = "DLNA")
        } finally {
            socket.close()
        }
        return locations
    }

    private fun fetchServerInfo(location: String): RemoteMediaServer? {
        val conn = URL(location).openConnection() as HttpURLConnection
        // Trước đây dùng URL.readText() — không có timeout, nên 1 server "chết" (trả lời SSDP
        // nhưng URL mô tả không phản hồi HTTP) có thể treo request này vô thời hạn, kéo theo
        // toàn màn hình quét bị đơ. Giới hạn rõ ràng để luôn kết thúc trong vài giây.
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val xml = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val friendlyName = Regex("<friendlyName>(.*?)</friendlyName>").find(xml)?.groupValues?.get(1)
            ?: "Máy chủ không tên"

        val serviceBlockRegex = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL)
        val cdService = serviceBlockRegex.findAll(xml).map { it.groupValues[1] }
            .firstOrNull { it.contains("ContentDirectory") } ?: return null

        val controlPath = Regex("<controlURL>(.*?)</controlURL>").find(cdService)?.groupValues?.get(1)
            ?: return null

        val baseUrl = URL(location)
        val controlUrl = if (controlPath.startsWith("http")) controlPath else URL(baseUrl, controlPath).toString()

        return RemoteMediaServer(friendlyName = friendlyName, location = location, controlUrl = controlUrl)
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
