package com.myfile.ui.dlna

import android.os.Handler
import android.os.Looper
import com.myfile.ui.util.LogBus
import fi.iki.elonen.NanoHTTPD
import java.util.UUID
import java.util.concurrent.CountDownLatch

/**
 * Máy chủ HTTP + trả lời SSDP cho vai trò UPnP MediaRenderer (khác với MediaStreamServer -
 * vai trò MediaServer). Cho phép ứng dụng/TV/loa khác trong mạng "Phát tới" (Play to /
 * DLNA cast) MyFile Manager giống như cách BubbleUPnP làm renderer cho các app khác.
 *
 * Luồng hoạt động:
 *  1. Controller (VD BubbleUPnP) dò SSDP, tìm thấy renderer này qua RendererSsdpResponder.
 *  2. Controller GET /renderer/description.xml để lấy danh sách service (AVTransport,
 *     RenderingControl) và URL điều khiển (control) + mô tả action (SCPD).
 *  3. Controller POST SOAP action SetAVTransportURI kèm URL media, rồi Play.
 *  4. RendererServer nhận SOAP, gọi RendererPlaybackService (ExoPlayer) để thực sự phát.
 *
 * ExoPlayer chỉ được gọi an toàn từ Main thread, trong khi NanoHTTPD xử lý mỗi request trên
 * 1 thread nền riêng — mọi lệnh điều khiển player đều phải post qua Handler(mainLooper) và
 * CHỜ (CountDownLatch) kết quả trước khi trả HTTP response, để tránh race điều kiện (VD trả
 * lời "đã Play" trước khi player thực sự nhận lệnh).
 */
class RendererServer(port: Int) : NanoHTTPD(port) {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                uri == "/renderer/description.xml" -> serveDescription()
                uri == "/renderer/AVTransport/scpd.xml" -> serveAvTransportScpd()
                uri == "/renderer/RenderingControl/scpd.xml" -> serveRenderingControlScpd()
                uri == "/renderer/AVTransport/control" && session.method == Method.POST -> serveAvTransportSoap(session)
                uri == "/renderer/RenderingControl/control" && session.method == Method.POST -> serveRenderingControlSoap(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Không tìm thấy")
            }
        } catch (e: Exception) {
            LogBus.error("Lỗi renderer server khi xử lý $uri", source = "DLNA", throwable = e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Lỗi máy chủ")
        }
    }

    // ---------------- device description ----------------

    private fun serveDescription(): Response {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>MyFile Manager</friendlyName>
                <manufacturer>MyFile</manufacturer>
                <modelName>MyFile Manager Renderer</modelName>
                <UDN>uuid:${RendererIds.udn}</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                    <controlURL>/renderer/AVTransport/control</controlURL>
                    <eventSubURL>/renderer/AVTransport/event</eventSubURL>
                    <SCPDURL>/renderer/AVTransport/scpd.xml</SCPDURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                    <controlURL>/renderer/RenderingControl/control</controlURL>
                    <eventSubURL>/renderer/RenderingControl/event</eventSubURL>
                    <SCPDURL>/renderer/RenderingControl/scpd.xml</SCPDURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", xml)
    }

    private fun serveAvTransportScpd(): Response {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <actionList>
                <action><name>SetAVTransportURI</name>
                  <argumentList>
                    <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
                    <argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
                    <argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
                  </argumentList>
                </action>
                <action><name>Play</name>
                  <argumentList>
                    <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
                    <argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
                  </argumentList>
                </action>
                <action><name>Pause</name>
                  <argumentList>
                    <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
                  </argumentList>
                </action>
                <action><name>Stop</name>
                  <argumentList>
                    <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
                  </argumentList>
                </action>
                <action><name>Seek</name>
                  <argumentList>
                    <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
                    <argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>
                    <argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>
                  </argumentList>
                </action>
                <action><name>GetTransportInfo</name>
                  <argumentList>
                    <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
                    <argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>
                    <argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>
                    <argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
                  </argumentList>
                </action>
                <action><name>GetPositionInfo</name>
                  <argumentList>
                    <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
                    <argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>
                    <argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>
                    <argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument>
                    <argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>
                  </argumentList>
                </action>
                <action><name>GetMediaInfo</name>
                  <argumentList>
                    <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
                    <argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument>
                    <argument><name>MediaDuration</name><direction>out</direction><relatedStateVariable>CurrentMediaDuration</relatedStateVariable></argument>
                    <argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
                  </argumentList>
                </action>
              </actionList>
              <serviceStateTable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="yes"><name>TransportState</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="yes"><name>TransportStatus</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="yes"><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="yes"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="yes"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="yes"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>
                <stateVariable sendEvents="yes"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="yes"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="yes"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="yes"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>
                <stateVariable sendEvents="yes"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>
              </serviceStateTable>
            </scpd>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", xml)
    }

    private fun serveRenderingControlScpd(): Response {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <actionList>
                <action><name>SetVolume</name>
                  <argumentList>
                    <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
                    <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
                    <argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
                  </argumentList>
                </action>
                <action><name>GetVolume</name>
                  <argumentList>
                    <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
                    <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
                    <argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
                  </argumentList>
                </action>
              </actionList>
              <serviceStateTable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="yes"><name>Volume</name><dataType>ui2</dataType></stateVariable>
              </serviceStateTable>
            </scpd>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", xml)
    }

    // ---------------- AVTransport SOAP ----------------

    private fun serveAvTransportSoap(session: IHTTPSession): Response {
        val requestXml = readBody(session)
        val action = session.headers["soapaction"].orEmpty()
        fun extract(tag: String): String? = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(requestXml)?.groupValues?.get(1)

        return when {
            action.contains("SetAVTransportURI") -> {
                val uriValue = extract("CurrentURI").orEmpty()
                    .replace("&amp;", "&")
                if (uriValue.isBlank()) {
                    return soapFault(718, "Invalid InstanceID")
                }
                runOnPlayerThread { it.setMedia(uriValue) }
                LogBus.success("Nhận lệnh phát media từ thiết bị khác (renderer)", source = "DLNA")
                emptyActionResponse("SetAVTransportURI")
            }
            action.contains("Play") -> {
                runOnPlayerThread { it.play() }
                emptyActionResponse("Play")
            }
            action.contains("Pause") -> {
                runOnPlayerThread { it.pause() }
                emptyActionResponse("Pause")
            }
            action.contains("Stop") -> {
                runOnPlayerThread { it.stop() }
                emptyActionResponse("Stop")
            }
            action.contains("Seek") -> {
                val target = extract("Target").orEmpty()
                val ms = parseUpnpTimeToMillis(target)
                if (ms != null) runOnPlayerThread { it.seekToMillis(ms) }
                emptyActionResponse("Seek")
            }
            action.contains("GetTransportInfo") -> {
                val svc = RendererPlaybackService.getRunningInstance()
                val state = when {
                    svc == null -> "NO_MEDIA_PRESENT"
                    svc.isPlaying() -> "PLAYING"
                    svc.isPaused() && svc.currentUri() != null -> "PAUSED_PLAYBACK"
                    else -> "STOPPED"
                }
                buildSoapResponse(
                    "GetTransportInfoResponse",
                    "CurrentTransportState" to state,
                    "CurrentTransportStatus" to "OK",
                    "CurrentSpeed" to "1"
                )
            }
            action.contains("GetPositionInfo") -> {
                val svc = RendererPlaybackService.getRunningInstance()
                buildSoapResponse(
                    "GetPositionInfoResponse",
                    "Track" to "1",
                    "TrackDuration" to formatUpnpTime(svc?.durationMillis() ?: 0L),
                    "TrackURI" to (svc?.currentUri() ?: ""),
                    "RelTime" to formatUpnpTime(svc?.positionMillis() ?: 0L)
                )
            }
            action.contains("GetMediaInfo") -> {
                val svc = RendererPlaybackService.getRunningInstance()
                buildSoapResponse(
                    "GetMediaInfoResponse",
                    "NrTracks" to "1",
                    "MediaDuration" to formatUpnpTime(svc?.durationMillis() ?: 0L),
                    "CurrentURI" to (svc?.currentUri() ?: "")
                )
            }
            else -> soapFault(401, "Invalid Action")
        }
    }

    // ---------------- RenderingControl SOAP ----------------

    private fun serveRenderingControlSoap(session: IHTTPSession): Response {
        val requestXml = readBody(session)
        val action = session.headers["soapaction"].orEmpty()
        fun extract(tag: String): String? = Regex("<$tag>(.*?)</$tag>").find(requestXml)?.groupValues?.get(1)

        return when {
            action.contains("SetVolume") -> {
                val vol = extract("DesiredVolume")?.toIntOrNull() ?: 50
                runOnPlayerThread { it.setVolumePercent(vol) }
                emptyActionResponse("SetVolume")
            }
            action.contains("GetVolume") -> {
                val svc = RendererPlaybackService.getRunningInstance()
                buildSoapResponse("GetVolumeResponse", "CurrentVolume" to (svc?.volumePercent() ?: 50).toString())
            }
            else -> soapFault(401, "Invalid Action")
        }
    }

    // ---------------- tiện ích chung ----------------

    /**
     * BUG: InputStream.read(ByteArray) KHÔNG đảm bảo đọc đủ toàn bộ mảng trong 1 lần gọi — đây
     * là hành vi chuẩn của Java I/O, đặc biệt rõ với luồng mạng/socket khi dữ liệu đến theo
     * nhiều gói TCP. Với body SOAP dài (VD SetAVTransportURI kèm CurrentURIMetaData là cả 1
     * khối XML DIDL-Lite), read() 1 lần trước đây có thể chỉ nhận được MỘT PHẦN dữ liệu, phần
     * còn lại của mảng body vẫn là byte 0x00 mặc định — String(body) ra 1 XML bị cắt cụt/lẫn
     * ký tự rác, khiến extract() không tìm thấy tag cần thiết và điều khiển từ TV/app cast
     * khác thất bại âm thầm hoặc sai lệch, dù server không hề crash hay báo lỗi rõ ràng.
     * Đọc theo vòng lặp cho tới khi đủ số byte content-length khai báo mới dừng.
     */
    private fun readBody(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ""
        val body = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val n = session.inputStream.read(body, totalRead, length - totalRead)
            if (n < 0) break // luồng kết thúc sớm hơn content-length khai báo — dừng, dùng phần đã đọc được
            totalRead += n
        }
        return String(body, 0, totalRead)
    }

    /**
     * ExoPlayer chỉ an toàn khi gọi từ Main thread; NanoHTTPD xử lý request trên thread nền.
     * post() lên Handler(mainLooper) rồi CHỜ latch để đảm bảo lệnh đã thực sự chạy xong
     * trước khi trả HTTP response — tránh trường hợp TV nhận "200 OK" nhưng player chưa
     * kịp cập nhật state, dẫn đến GetTransportInfo ngay sau đó trả về state cũ.
     *
     * Giới hạn await() bằng timeout: nếu main thread vì lý do bất thường nào đó không xử lý
     * được post() (VD app đang bị hệ thống đóng băng/kill giữa chừng), latch.await() không
     * timeout trước đây sẽ treo VĨNH VIỄN thread NanoHTTPD đang xử lý request này — không tự
     * crash ngay nhưng làm cạn dần thread pool nếu TV gửi nhiều lệnh liên tiếp trong lúc đó,
     * và khiến toàn bộ tính năng "Phát tới" ngừng phản hồi mà không có log lỗi rõ ràng.
     */
    private fun runOnPlayerThread(action: (RendererPlaybackService) -> Unit) {
        val svc = RendererPlaybackService.getRunningInstance() ?: return
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                action(svc)
            } catch (e: Exception) {
                // QUAN TRỌNG: post{} này chạy trên MAIN THREAD của toàn app — 1 exception không
                // bắt ở đây (VD ExoPlayer ném lỗi khi setMediaItem() với URL dị dạng do TV/app
                // điều khiển gửi tới) sẽ crash TOÀN BỘ APP, không chỉ riêng tính năng renderer.
                // finally bên dưới chỉ đảm bảo latch được nhả, không ngăn exception lan lên.
                LogBus.error("Lỗi khi thực thi lệnh renderer trên player thread", source = "DLNA", throwable = e)
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun emptyActionResponse(actionName: String): Response =
        buildSoapResponse("${actionName}Response")

    private fun buildSoapResponse(actionResponseName: String, vararg outArgs: Pair<String, String>): Response {
        val argsXml = outArgs.joinToString("") { (name, value) -> "<$name>${escapeXml(value)}</$name>" }
        val soap = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:$actionResponseName xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$argsXml</u:$actionResponseName>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", soap)
    }

    private fun soapFault(code: Int, description: String): Response {
        val soap = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <s:Fault>
                  <faultcode>s:Client</faultcode>
                  <faultstring>UPnPError</faultstring>
                  <detail>
                    <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                      <errorCode>$code</errorCode>
                      <errorDescription>${escapeXml(description)}</errorDescription>
                    </UPnPError>
                  </detail>
                </s:Fault>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/xml; charset=utf-8", soap)
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Chuyển "0:01:23" (định dạng thời gian UPnP H:MM:SS) sang mili-giây. */
    private fun parseUpnpTimeToMillis(time: String): Long? {
        val parts = time.trim().split(":")
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return ((h * 3600 + m * 60) * 1000) + (s * 1000).toLong()
    }

    private fun formatUpnpTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%d:%02d:%02d".format(h, m, s)
    }
}

/** UDN cố định cho phiên chạy hiện tại của renderer (tách riêng với UDN của MediaServer). */
private object RendererIds {
    val udn: String by lazy { UUID.randomUUID().toString() }
}
