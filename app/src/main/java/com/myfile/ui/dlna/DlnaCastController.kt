package com.myfile.ui.dlna

import com.myfile.ui.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gửi lệnh điều khiển tới thiết bị DLNA đã tìm thấy: nạp URL media (SetAVTransportURI)
 * rồi phát (Play). TV tự kéo dữ liệu từ MediaCastService (HTTP server trong app) về —
 * app không truyền dữ liệu qua lại, chỉ ra lệnh.
 */
object DlnaCastController {

    suspend fun playUrl(device: DlnaDevice, mediaUrl: String, mimeType: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val setUriBody = buildSetAvTransportUriBody(mediaUrl, mimeType)
                val setOk = sendSoapAction(device, "SetAVTransportURI", setUriBody)
                if (!setOk) return@withContext false

                val playBody = buildPlayBody()
                sendSoapAction(device, "Play", playBody)
            } catch (e: Exception) {
                LogBus.error("Lỗi khi gửi lệnh cast tới ${device.friendlyName}", source = "DLNA", throwable = e)
                false
            }
        }

    suspend fun stop(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            sendSoapAction(device, "Stop", buildStopBody())
        } catch (e: Exception) {
            false
        }
    }

    private fun sendSoapAction(device: DlnaDevice, action: String, body: String): Boolean {
        val conn = URL(device.controlUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPAction", "\"${device.serviceType}#$action\"")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code in 200..299) {
                LogBus.success("Đã gửi lệnh $action tới ${device.friendlyName}", source = "DLNA")
                true
            } else {
                LogBus.error("Thiết bị ${device.friendlyName} từ chối lệnh $action (HTTP $code)", source = "DLNA")
                false
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun buildSetAvTransportUriBody(mediaUrl: String, mimeType: String): String {
        val escapedUrl = mediaUrl.replace("&", "&amp;")
        val didl = """
            &lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;
            &lt;item id="1" parentID="0" restricted="1"&gt;
            &lt;dc:title&gt;MyFile Stream&lt;/dc:title&gt;
            &lt;upnp:class&gt;object.item.${if (mimeType.startsWith("video")) "videoItem" else "audioItem"}&lt;/upnp:class&gt;
            &lt;res protocolInfo="http-get:*:$mimeType:*"&gt;$escapedUrl&lt;/res&gt;
            &lt;/item&gt;
            &lt;/DIDL-Lite&gt;
        """.trimIndent().replace("\n", "")

        return """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
            <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
            <CurrentURI>$escapedUrl</CurrentURI>
            <CurrentURIMetaData>$didl</CurrentURIMetaData>
            </u:SetAVTransportURI>
            </s:Body>
            </s:Envelope>""".trimIndent()
    }

    private fun buildPlayBody(): String = """<?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
        <s:Body>
        <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
        <InstanceID>0</InstanceID>
        <Speed>1</Speed>
        </u:Play>
        </s:Body>
        </s:Envelope>""".trimIndent()

    private fun buildStopBody(): String = """<?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
        <s:Body>
        <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
        <InstanceID>0</InstanceID>
        </u:Stop>
        </s:Body>
        </s:Envelope>""".trimIndent()
}
