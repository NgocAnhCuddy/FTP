package com.myfile.ui.dlna

import com.myfile.ui.util.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Lắng nghe SSDP multicast (239.255.255.250:1900) và trả lời các gói M-SEARCH tìm kiếm
 * MediaServer, để Smart TV/loa hỗ trợ DLNA TỰ nhìn thấy "MyFile Manager" trong danh sách
 * nguồn phát mà không cần người dùng nhập địa chỉ IP thủ công.
 *
 * Ngoài trả lời M-SEARCH, còn gửi định kỳ gói NOTIFY (ssdp:alive) để TV vẫn phát hiện được
 * ngay cả khi bỏ lỡ lúc server mới bật (một số TV chỉ quét định kỳ thay vì query chủ động).
 */
class SsdpResponder(
    private val httpPort: Int,
    private val localIp: String,
    private val udn: String
) {
    private var job: Job? = null
    private var socket: MulticastSocket? = null

    fun start() {
        if (job != null) return
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val group = InetAddress.getByName(SSDP_ADDRESS)
                val s = MulticastSocket(SSDP_PORT)
                s.reuseAddress = true
                val netIf = findNetworkInterface()
                s.joinGroup(InetSocketAddress(group, SSDP_PORT), netIf)
                // QUAN TRỌNG: nếu không gán rõ NetworkInterface để GỬI, hệ điều hành có thể chọn
                // sai interface (VD interface di động/VPN thay vì Wi-Fi) khiến MỌI packet gửi ra
                // multicast group đều rớt (ENETUNREACH) dù joinGroup() (nhận) vẫn thành công —
                // đây là nguyên nhân gây log lặp lại liên tục "Không gửi được thông báo ssdp:alive".
                if (netIf != null) s.networkInterface = netIf
                s.timeToLive = 4
                socket = s
                LogBus.success("SSDP responder đã sẵn sàng, TV có thể tự tìm thấy MyFile Manager", source = "DLNA")

                sendAlive(s, group)

                val buf = ByteArray(2048)
                var lastAliveAt = System.currentTimeMillis()
                s.soTimeout = ALIVE_INTERVAL_MS.toInt()
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        s.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        if (text.startsWith("M-SEARCH", ignoreCase = true) && isMediaServerSearch(text)) {
                            sendSearchResponse(s, packet.address, packet.port, text)
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Hết hạn chờ theo chu kỳ — đây là "nhịp tim" để gửi lại NOTIFY định kỳ,
                        // không phải lỗi thật.
                    } catch (e: Exception) {
                        if (isActive) LogBus.warning("Lỗi khi xử lý gói SSDP", source = "DLNA")
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastAliveAt >= ALIVE_INTERVAL_MS) {
                        sendAlive(s, group)
                        lastAliveAt = now
                    }
                }
            } catch (e: Exception) {
                LogBus.error("Không thể khởi động SSDP responder", source = "DLNA", throwable = e)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            socket?.leaveGroup(InetSocketAddress(InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT), findNetworkInterface())
        } catch (e: Exception) {
            // bỏ qua, socket có thể đã đóng
        }
        socket?.close()
        socket = null
    }

    private fun isMediaServerSearch(request: String): Boolean {
        val st = request.lineSequence().firstOrNull { it.startsWith("ST:", ignoreCase = true) }
            ?.substringAfter(":", "")?.trim().orEmpty()
        return st == "ssdp:all" || st == "upnp:rootdevice" || st.startsWith("uuid:") ||
            st.contains("MediaServer") || st.contains("ContentDirectory")
    }

    private fun requestedSt(request: String): String =
        request.lineSequence().firstOrNull { it.startsWith("ST:", ignoreCase = true) }
            ?.substringAfter(":", "")?.trim().orEmpty()

    /**
     * QUAN TRỌNG: chuẩn UPnP yêu cầu response phải ECHO đúng ST mà client hỏi (không phải luôn
     * trả về 1 giá trị cố định). Client dùng libupnp (VLC dùng thư viện này cho "Mạng cục bộ")
     * kiểm tra rất nghiêm: nếu hỏi "ST: upnp:rootdevice" mà nhận lại "ST: ...MediaServer:1" thì
     * coi là không khớp và ÂM THẦM BỎ QUA response - khiến VLC không hiện thiết bị dù TV (dễ
     * tính hơn) vẫn nhìn thấy. "ssdp:all" thì phải trả về NHIỀU response (rootdevice + uuid +
     * device type), không phải 1 response duy nhất.
     */
    private fun sendSearchResponse(socket: MulticastSocket, toAddress: InetAddress, toPort: Int, request: String) {
        val st = requestedSt(request)
        val targets = when {
            st == "upnp:rootdevice" -> listOf("upnp:rootdevice" to "uuid:$udn::upnp:rootdevice")
            st.startsWith("uuid:") -> listOf("uuid:$udn" to "uuid:$udn")
            st == "ssdp:all" -> listOf(
                "upnp:rootdevice" to "uuid:$udn::upnp:rootdevice",
                "uuid:$udn" to "uuid:$udn",
                "urn:schemas-upnp-org:device:MediaServer:1" to "uuid:$udn::urn:schemas-upnp-org:device:MediaServer:1",
                "urn:schemas-upnp-org:service:ContentDirectory:1" to "uuid:$udn::urn:schemas-upnp-org:service:ContentDirectory:1"
            )
            st.contains("ContentDirectory") -> listOf(
                "urn:schemas-upnp-org:service:ContentDirectory:1" to "uuid:$udn::urn:schemas-upnp-org:service:ContentDirectory:1"
            )
            else -> listOf(
                "urn:schemas-upnp-org:device:MediaServer:1" to "uuid:$udn::urn:schemas-upnp-org:device:MediaServer:1"
            )
        }
        targets.forEach { (stValue, usn) ->
            val response = """
                HTTP/1.1 200 OK
                CACHE-CONTROL: max-age=1800
                EXT:
                LOCATION: http://$localIp:$httpPort/description.xml
                SERVER: Android/UPnP/1.0 MyFileManager/1.0
                ST: $stValue
                USN: $usn

            """.trimIndent().replace("\n", "\r\n")
            val bytes = response.toByteArray()
            socket.send(DatagramPacket(bytes, bytes.size, toAddress, toPort))
        }
    }

    /**
     * Gửi đủ 4 gói NOTIFY (rootdevice, uuid, device type, service ContentDirectory) thay vì chỉ
     * 1 gói device-type như trước - đây là bộ advertisement tối thiểu chuẩn UPnP mà các control
     * point nghiêm ngặt (libupnp/VLC) cần thấy đủ mới build được danh sách thiết bị.
     */
    private fun sendAlive(socket: MulticastSocket, group: InetAddress) {
        val targets = listOf(
            "upnp:rootdevice" to "uuid:$udn::upnp:rootdevice",
            "uuid:$udn" to "uuid:$udn",
            "urn:schemas-upnp-org:device:MediaServer:1" to "uuid:$udn::urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:service:ContentDirectory:1" to "uuid:$udn::urn:schemas-upnp-org:service:ContentDirectory:1"
        )
        targets.forEach { (nt, usn) ->
            val notify = """
                NOTIFY * HTTP/1.1
                HOST: $SSDP_ADDRESS:$SSDP_PORT
                CACHE-CONTROL: max-age=1800
                LOCATION: http://$localIp:$httpPort/description.xml
                SERVER: Android/UPnP/1.0 MyFileManager/1.0
                NT: $nt
                NTS: ssdp:alive
                USN: $usn

            """.trimIndent().replace("\n", "\r\n")
            val bytes = notify.toByteArray()
            try {
                socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
            } catch (e: Exception) {
                LogBus.warning("Không gửi được thông báo ssdp:alive ($nt)", source = "DLNA")
            }
        }
    }

    private fun findNetworkInterface(): NetworkInterface? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .firstOrNull { intf ->
                    intf.isUp && !intf.isLoopback &&
                        intf.inetAddresses.asSequence().any { it.hostAddress == localIp }
                }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        // Khoảng gửi lại NOTIFY ssdp:alive định kỳ — nhiều Smart TV (Sony Bravia, Samsung...)
        // build danh sách "Lựa chọn thiết bị" bằng cách lắng nghe NOTIFY nền theo chu kỳ, thay
        // vì (hoặc thêm vào) việc chủ động gửi M-SEARCH mỗi khi người dùng mở màn nguồn. Gói
        // alive gửi 1 lần duy nhất lúc bật server rất dễ bị bỏ lỡ (rớt gói UDP, hoặc TV chưa mở
        // màn nguồn đúng lúc đó) khiến TV báo "Không có mục hiển thị" dù server vẫn chạy tốt.
        private const val ALIVE_INTERVAL_MS = 20_000L
    }
}
