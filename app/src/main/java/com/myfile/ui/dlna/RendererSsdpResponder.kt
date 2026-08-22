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
import java.util.UUID

/**
 * Giống SsdpResponder (vai trò MediaServer) nhưng quảng bá thiết bị MediaRenderer:1 —
 * để các app điều khiển DLNA khác (BubbleUPnP, TV, điện thoại khác) TỰ nhìn thấy
 * "MyFile Manager" trong danh sách "Phát tới" (cast target), không cần nhập IP thủ công.
 *
 * Một thiết bị UPnP có thể vừa là MediaServer vừa là MediaRenderer cùng lúc (2 device riêng
 * biệt, LOCATION khác nhau) — đây chính là cách BubbleUPnP hoạt động: vừa cho duyệt file từ
 * điện thoại (server), vừa nhận cast từ app khác (renderer).
 */
class RendererSsdpResponder(
    private val httpPort: Int,
    private val localIp: String,
    private val udn: String = UUID.randomUUID().toString()
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
                if (netIf != null) s.networkInterface = netIf
                s.timeToLive = 4
                socket = s
                LogBus.success("SSDP renderer sẵn sàng, thiết bị khác có thể \"Phát tới\" MyFile Manager", source = "DLNA")

                sendAliveAll(s, group)

                val buf = ByteArray(2048)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        s.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        if (text.startsWith("M-SEARCH", ignoreCase = true) && isRendererSearch(text)) {
                            sendSearchResponse(s, packet.address, packet.port)
                        }
                    } catch (e: Exception) {
                        if (isActive) LogBus.warning("Lỗi khi xử lý gói SSDP (renderer)", source = "DLNA")
                    }
                }
            } catch (e: Exception) {
                LogBus.error("Không thể khởi động SSDP responder cho renderer", source = "DLNA", throwable = e)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            socket?.leaveGroup(InetSocketAddress(InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT), findNetworkInterface())
        } catch (e: Exception) {
            // bỏ qua
        }
        socket?.close()
        socket = null
    }

    private fun isRendererSearch(request: String): Boolean {
        val st = request.lineSequence().firstOrNull { it.startsWith("ST:", ignoreCase = true) }
            ?.substringAfter(":", "")?.trim().orEmpty()
        return st == "ssdp:all" || st.contains("MediaRenderer") || st.contains("upnp:rootdevice") ||
            st.contains("AVTransport") || st.contains("RenderingControl")
    }

    private fun sendSearchResponse(socket: MulticastSocket, toAddress: InetAddress, toPort: Int) {
        // Trả lời cho cả rootdevice lẫn MediaRenderer để tương thích tối đa với các controller
        // dò theo kiểu khác nhau (1 số chỉ query "ssdp:all", số khác query đúng MediaRenderer).
        listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:RenderingControl:1"
        ).forEach { st ->
            val response = """
                HTTP/1.1 200 OK
                CACHE-CONTROL: max-age=1800
                EXT:
                LOCATION: http://$localIp:$httpPort/renderer/description.xml
                SERVER: Android/UPnP/1.0 MyFileManager/1.0
                ST: $st
                USN: uuid:$udn::$st

            """.trimIndent().replace("\n", "\r\n")
            val bytes = response.toByteArray()
            socket.send(DatagramPacket(bytes, bytes.size, toAddress, toPort))
        }
    }

    private fun sendAliveAll(socket: MulticastSocket, group: InetAddress) {
        listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:RenderingControl:1"
        ).forEach { nt ->
            val notify = """
                NOTIFY * HTTP/1.1
                HOST: $SSDP_ADDRESS:$SSDP_PORT
                CACHE-CONTROL: max-age=1800
                LOCATION: http://$localIp:$httpPort/renderer/description.xml
                SERVER: Android/UPnP/1.0 MyFileManager/1.0
                NT: $nt
                NTS: ssdp:alive
                USN: uuid:$udn::$nt

            """.trimIndent().replace("\n", "\r\n")
            val bytes = notify.toByteArray()
            try {
                socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
            } catch (e: Exception) {
                LogBus.warning("Không gửi được thông báo ssdp:alive (renderer)", source = "DLNA")
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
    }
}
