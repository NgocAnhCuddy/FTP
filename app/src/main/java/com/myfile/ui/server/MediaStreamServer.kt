package com.myfile.ui.server

import com.myfile.ui.util.LogBus
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Máy chủ HTTP nhẹ để phát file (ảnh/video/audio) qua mạng LAN: TV hoặc điện thoại khác
 * chỉ cần mở link bằng trình duyệt hoặc dán vào VLC là xem/nghe được, không cần cài app.
 *
 * Hỗ trợ HTTP Range request (206 Partial Content) — bắt buộc để tua video, và để VLC/trình
 * duyệt không phải tải hết file mới phát được.
 *
 * Hai chế độ phục vụ file song song:
 *  - /stream/{token}/{ten}  : file được ĐĂNG KÝ trước (dùng khi "Phát lên TV" 1 file cụ thể
 *    qua DLNA push), token ngẫu nhiên không lộ đường dẫn thật.
 *  - /browse/{đường dẫn}    : DUYỆT TRỰC TIẾP toàn bộ thư mục gốc, trả về trang HTML danh sách
 *    file/thư mục để mở bằng trình duyệt TV/laptop mà không cần cài app nào — giống như mở
 *    một ổ đĩa mạng. Chỉ bật khi rootFolder được set (tức người dùng đã bật "Máy chủ Media").
 *
 * Ngoài ra còn phục vụ /description.xml và SOAP ContentDirectory (Browse action) tối giản để
 * Smart TV/loa hỗ trợ DLNA tự nhận diện server này trong danh sách nguồn media qua SSDP.
 */
class MediaStreamServer(port: Int, private val rootFolder: File? = null) : NanoHTTPD(port) {

    // token -> file thật trên máy (dùng cho /stream, đăng ký thủ công từng file)
    private val registry = ConcurrentHashMap<String, File>()

    /** Đăng ký 1 file để phát, trả về token dùng trong URL (không phải đường dẫn thật). */
    fun register(file: File): String {
        val token = UUID.randomUUID().toString()
        registry[token] = file
        return token
    }

    fun unregister(token: String) {
        registry.remove(token)
    }

    fun urlFor(host: String, token: String, fileName: String): String {
        val encodedName = URLEncoder.encode(fileName, "UTF-8")
        return "http://$host:${listeningPort}/stream/$token/$encodedName"
    }

    /** URL gốc để duyệt toàn bộ thư mục bằng trình duyệt (chỉ hợp lệ khi rootFolder != null). */
    fun browseUrlFor(host: String): String = "http://$host:${listeningPort}/browse/"

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                uri == "/" -> redirectToBrowse()
                uri.startsWith("/stream/") -> serveStream(session, uri)
                uri.startsWith("/browse") -> serveBrowse(session, uri)
                uri == "/description.xml" -> serveDeviceDescription(session)
                uri == "/ContentDirectory/scpd.xml" -> serveContentDirectoryScpd()
                uri == "/ContentDirectory/control" && session.method == Method.POST -> serveContentDirectorySoap(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Không tìm thấy")
            }
        } catch (e: Exception) {
            LogBus.error("Lỗi máy chủ media khi xử lý $uri", source = "STREAM", throwable = e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Lỗi máy chủ")
        }
    }

    // ---------------- /stream/{token}/{ten} ----------------

    private fun serveStream(session: IHTTPSession, uri: String): Response {
        val parts = uri.removePrefix("/").split("/")
        if (parts.size < 2 || parts[0] != "stream") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Không tìm thấy")
        }
        val token = parts[1]
        val file = registry[token]
        if (file == null || !file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File không tồn tại hoặc đã bị gỡ")
        }
        return serveFileWithRange(session, file)
    }

    // ---------------- /browse (duyệt thư mục trực tiếp) ----------------

    private fun serveBrowse(session: IHTTPSession, uri: String): Response {
        val root = rootFolder
        if (root == null || !root.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Chưa bật chia sẻ thư mục")
        }
        val relPath = URLDecoder.decode(uri.removePrefix("/browse").removePrefix("/"), "UTF-8")
        val target = File(root, relPath).canonicalFile
        val rootCanonical = root.canonicalFile

        // Chặn path traversal: file đích phải nằm trong root
        if (!target.path.startsWith(rootCanonical.path)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Không có quyền truy cập")
        }
        if (!target.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Không tìm thấy")
        }
        return if (target.isDirectory) {
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", renderDirectoryHtml(target, relPath))
        } else {
            serveFileWithRange(session, target)
        }
    }

    private fun renderDirectoryHtml(dir: File, relPath: String): String {
        val entries = (dir.listFiles() ?: emptyArray())
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })

        val parentLink = if (relPath.isNotBlank() && relPath != "/") {
            val parentRel = relPath.trimEnd('/').substringBeforeLast('/', "")
            """<a class="row up" href="/browse/${encodePath(parentRel)}">⬅ Thư mục cha</a>"""
        } else ""

        val rows = entries.joinToString("\n") { f ->
            val childRel = if (relPath.isBlank() || relPath == "/") f.name else "$relPath/${f.name}"
            val href = "/browse/${encodePath(childRel)}"
            val icon = when {
                f.isDirectory -> "📁"
                f.extension.lowercase() in setOf("mp4", "mkv", "webm", "avi", "mov") -> "🎬"
                f.extension.lowercase() in setOf("mp3", "m4a", "flac", "wav") -> "🎵"
                f.extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp") -> "🖼"
                else -> "📄"
            }
            val sizeLabel = if (f.isDirectory) "" else formatSize(f.length())
            """<a class="row" href="$href">$icon <span class="name">${escapeHtml(f.name)}</span><span class="size">$sizeLabel</span></a>"""
        }

        return """
            <!DOCTYPE html>
            <html lang="vi"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>MyFile Manager - Duyệt file</title>
            <style>
              body{background:#0d0f12;color:#e8eaed;font-family:-apple-system,Roboto,Arial,sans-serif;margin:0;padding:24px}
              h1{font-size:20px;color:#8ab4f8;margin-bottom:4px}
              .path{color:#9aa0a6;font-size:13px;margin-bottom:20px;word-break:break-all}
              .row{display:flex;align-items:center;gap:10px;padding:14px 16px;border-radius:12px;color:#e8eaed;
                   text-decoration:none;border:1px solid #2a2d31;margin-bottom:8px;background:#16181c}
              .row:hover{border-color:#8ab4f8}
              .row.up{color:#8ab4f8;font-weight:600}
              .name{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
              .size{color:#9aa0a6;font-size:12px}
            </style></head>
            <body>
              <h1>📂 MyFile Manager</h1>
              <div class="path">/${escapeHtml(relPath)}</div>
              $parentLink
              $rows
            </body></html>
        """.trimIndent()
    }

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun redirectToBrowse(): Response {
        val resp = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
        resp.addHeader("Location", "/browse/")
        return resp
    }

    // ---------------- DLNA: device description + ContentDirectory Browse tối giản ----------------

    private fun serveDeviceDescription(session: IHTTPSession): Response {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                <friendlyName>MyFile Manager</friendlyName>
                <manufacturer>MyFile</manufacturer>
                <modelName>MyFile Manager Media Server</modelName>
                <UDN>uuid:${DlnaIds.udn}</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                    <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
                    <controlURL>/ContentDirectory/control</controlURL>
                    <eventSubURL>/ContentDirectory/event</eventSubURL>
                    <SCPDURL>/ContentDirectory/scpd.xml</SCPDURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", xml)
    }

    /**
     * SCPD (Service Control Protocol Description) cho ContentDirectory — mô tả các action
     * (Browse, GetSearchCapabilities...) và biến trạng thái theo chuẩn UPnP. QUAN TRỌNG: dù
     * description.xml có khai báo đúng <SCPDURL>, nhiều client UPnP tuân thủ chuẩn nghiêm ngặt
     * (VD: libupnp mà VLC dùng) sẽ GET url này để xác thực service TRƯỚC khi tin tưởng thiết bị;
     * nếu thiếu route này (404), client âm thầm loại bỏ service ContentDirectory khỏi danh sách,
     * khiến "MyFile Manager" hoàn toàn không hiện ra dù SSDP/description.xml vẫn đúng.
     */
    private fun serveContentDirectoryScpd(): Response {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <actionList>
                <action>
                  <name>Browse</name>
                  <argumentList>
                    <argument><name>ObjectID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>
                    <argument><name>BrowseFlag</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable></argument>
                    <argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>
                    <argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>
                    <argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                    <argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>
                    <argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>
                    <argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                    <argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                    <argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable></argument>
                  </argumentList>
                </action>
                <action>
                  <name>GetSearchCapabilities</name>
                  <argumentList>
                    <argument><name>SearchCaps</name><direction>out</direction><relatedStateVariable>SearchCapabilities</relatedStateVariable></argument>
                  </argumentList>
                </action>
                <action>
                  <name>GetSortCapabilities</name>
                  <argumentList>
                    <argument><name>SortCaps</name><direction>out</direction><relatedStateVariable>SortCapabilities</relatedStateVariable></argument>
                  </argumentList>
                </action>
                <action>
                  <name>GetSystemUpdateID</name>
                  <argumentList>
                    <argument><name>Id</name><direction>out</direction><relatedStateVariable>SystemUpdateID</relatedStateVariable></argument>
                  </argumentList>
                </action>
              </actionList>
              <serviceStateTable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType>
                  <allowedValueList><allowedValue>BrowseMetadata</allowedValue><allowedValue>BrowseDirectChildren</allowedValue></allowedValueList>
                </stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>A_ARG_TYPE_UpdateID</name><dataType>ui4</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>SearchCapabilities</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="no"><name>SortCapabilities</name><dataType>string</dataType></stateVariable>
                <stateVariable sendEvents="yes"><name>SystemUpdateID</name><dataType>ui4</dataType></stateVariable>
              </serviceStateTable>
            </scpd>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", xml)
    }

    /**
     * Browse action theo chuẩn UPnP ContentDirectory:1 — đọc đúng BrowseFlag/StartingIndex/
     * RequestedCount từ SOAP request, phân biệt BrowseMetadata (trả metadata của chính
     * ObjectID đó) và BrowseDirectChildren (trả danh sách con), có phân trang và childCount
     * cho container. Nhiều TV (Samsung/LG) yêu cầu các field này đúng chuẩn mới hiển thị được
     * danh sách thay vì báo "không có nội dung".
     */
    private fun serveContentDirectorySoap(session: IHTTPSession): Response {
        val root = rootFolder ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
        val body = ByteArray(session.headers["content-length"]?.toIntOrNull() ?: 0)
        session.inputStream.read(body)
        val requestXml = String(body)

        // Nhiều client (BubbleUPnP, Samsung/LG) gọi các action "dò khả năng" này TRƯỚC khi
        // Browse — trả lỗi/404 ở đây khiến client bỏ cuộc dù Browse vẫn hoạt động bình thường.
        val soapAction = session.headers["soapaction"].orEmpty()
        when {
            soapAction.contains("GetSystemUpdateID") ->
                return buildSimpleSoapResponse("GetSystemUpdateID", "Id" to "1")
            soapAction.contains("GetSearchCapabilities") ->
                return buildSimpleSoapResponse("GetSearchCapabilities", "SearchCaps" to "")
            soapAction.contains("GetSortCapabilities") ->
                return buildSimpleSoapResponse("GetSortCapabilities", "SortCaps" to "")
        }

        fun extract(tag: String): String? =
            Regex("<$tag>(.*?)</$tag>").find(requestXml)?.groupValues?.get(1)

        val objectId = extract("ObjectID")?.takeIf { it.isNotBlank() } ?: "0"
        val browseFlag = extract("BrowseFlag") ?: "BrowseDirectChildren"
        val startingIndex = extract("StartingIndex")?.toIntOrNull() ?: 0
        val requestedCount = extract("RequestedCount")?.toIntOrNull() ?: 0 // 0 = không giới hạn

        val targetFile = if (objectId == "0") root else File(root, objectId)
        if (!targetFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Không tìm thấy")
        }

        val host = session.headers["host"] ?: ""

        if (browseFlag == "BrowseMetadata") {
            // Trả về metadata của CHÍNH object này (không phải con) — TV dùng để lấy tên/loại
            // trước khi quyết định gọi tiếp BrowseDirectChildren.
            val didl = buildString {
                append(DIDL_HEADER)
                append(renderNode(targetFile, root, parentId = parentIdOf(objectId), host = host))
                append("</DIDL-Lite>")
            }
            return buildSoapResponse(didl, numberReturned = 1, totalMatches = 1)
        }

        // BrowseDirectChildren
        val allChildren = (targetFile.listFiles() ?: emptyArray())
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        val paged = if (requestedCount > 0) {
            allChildren.drop(startingIndex).take(requestedCount)
        } else {
            allChildren.drop(startingIndex)
        }

        val didl = buildString {
            append(DIDL_HEADER)
            for (f in paged) {
                append(renderNode(f, root, parentId = objectId, host = host))
            }
            append("</DIDL-Lite>")
        }
        return buildSoapResponse(didl, numberReturned = paged.size, totalMatches = allChildren.size)
    }

    private fun parentIdOf(objectId: String): String {
        if (objectId == "0") return "-1"
        val idx = objectId.lastIndexOf('/')
        return if (idx <= 0) "0" else objectId.substring(0, idx)
    }

    /** Render 1 file/thư mục thành <container> hoặc <item> DIDL-Lite, gồm childCount và kích thước. */
    private fun renderNode(f: File, root: File, parentId: String, host: String): String {
        val relId = f.relativeTo(root).path
        return if (f.isDirectory) {
            val childCount = f.listFiles()?.size ?: 0
            """<container id="${escapeHtml(relId)}" parentID="$parentId" restricted="1" childCount="$childCount"><dc:title>${escapeHtml(f.name)}</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>"""
        } else {
            val mime = guessMime(f)
            val cls = when {
                mime.startsWith("video") -> "object.item.videoItem"
                mime.startsWith("audio") -> "object.item.audioItem"
                mime.startsWith("image") -> "object.item.imageItem"
                else -> "object.item"
            }
            val resUrl = "http://$host/browse/${encodePath(relId)}"
            // Nhiều Smart TV (đặc biệt Sony Bravia) LỌC BỎ HOÀN TOÀN item không có DLNA.ORG_PN
            // (profile chuẩn DLNA) trong protocolInfo — coi như thiết bị không tương thích, dù
            // server trả về danh sách không rỗng. Kết quả là TV hiện "Không có mục hiển thị"
            // ngay cả khi Browse trả đúng dữ liệu. Phải thêm dlna:profileID cho từng định dạng
            // phổ biến để TV nhận diện và hiển thị được.
            val dlnaPn = dlnaProfileFor(f.extension.lowercase(), mime)
            val dlnaFlags = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            val protocolInfo = if (dlnaPn != null) {
                "http-get:*:$mime:DLNA.ORG_PN=$dlnaPn;$dlnaFlags"
            } else {
                "http-get:*:$mime:$dlnaFlags"
            }
            """<item id="${escapeHtml(relId)}" parentID="$parentId" restricted="1"><dc:title>${escapeHtml(f.name)}</dc:title><upnp:class>$cls</upnp:class><res protocolInfo="$protocolInfo" size="${f.length()}">${escapeHtml(resUrl)}</res></item>"""
        }
    }

    /** Profile DLNA.ORG_PN chuẩn cho các định dạng phổ biến — bắt buộc để Sony Bravia và nhiều
     *  TV khác nhận diện item là tương thích thay vì lọc bỏ khỏi danh sách hiển thị. */
    private fun dlnaProfileFor(extension: String, mime: String): String? = when {
        extension == "mp4" || mime == "video/mp4" -> "AVC_MP4_MP_SD_AAC_MULT5"
        extension == "mp3" || mime == "audio/mpeg" -> "MP3"
        extension == "jpg" || extension == "jpeg" -> "JPEG_LRG"
        extension == "png" -> "PNG_LRG"
        else -> null
    }

    private fun buildSoapResponse(didl: String, numberReturned: Int, totalMatches: Int): Response {
        val escapedDidl = escapeHtml(didl)
        val soap = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                  <Result>$escapedDidl</Result>
                  <NumberReturned>$numberReturned</NumberReturned>
                  <TotalMatches>$totalMatches</TotalMatches>
                  <UpdateID>1</UpdateID>
                </u:BrowseResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", soap)
    }

    /** Phản hồi SOAP tối giản cho các action không cần dữ liệu file thật (chỉ 1 biến ra). */
    private fun buildSimpleSoapResponse(action: String, vararg outArgs: Pair<String, String>): Response {
        val argsXml = outArgs.joinToString("") { (name, value) -> "<$name>${escapeHtml(value)}</$name>" }
        val soap = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:${action}Response xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">$argsXml</u:${action}Response>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", soap)
    }

    companion object {
        // Thêm xmlns:dlna — bắt buộc cho thuộc tính DLNA.ORG_PN trong res protocolInfo ở trên,
        // thiếu namespace này 1 số TV coi cả response là không hợp lệ và bỏ qua toàn bộ danh sách.
        private const val DIDL_HEADER = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/">"""
    }

    // ---------------- range serving dùng chung ----------------

    private fun serveFileWithRange(session: IHTTPSession, file: File): Response {
        val mime = guessMime(file)
        val fileLen = file.length()
        val rangeHeader = session.headers["range"]

        if (rangeHeader == null) {
            val response = newFixedLengthResponse(
                Response.Status.OK, mime, FileInputStream(file), fileLen
            )
            response.addHeader("Accept-Ranges", "bytes")
            return response
        }

        val rangeValue = rangeHeader.trim().removePrefix("bytes=")
        val (startStr, endStr) = rangeValue.split("-", limit = 2).let {
            it.getOrElse(0) { "" } to it.getOrElse(1) { "" }
        }
        val start = startStr.toLongOrNull() ?: 0L
        val end = endStr.toLongOrNull() ?: (fileLen - 1)
        val safeEnd = end.coerceAtMost(fileLen - 1)
        val length = (safeEnd - start + 1).coerceAtLeast(0)

        if (start >= fileLen || length <= 0) {
            val resp = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, mime, "")
            resp.addHeader("Content-Range", "bytes */$fileLen")
            return resp
        }

        val fis = FileInputStream(file)
        fis.skip(start)
        val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, length)
        response.addHeader("Content-Range", "bytes $start-$safeEnd/$fileLen")
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    private fun guessMime(file: File): String {
        return URLConnection.guessContentTypeFromName(file.name)
            ?: when (file.extension.lowercase()) {
                "mp4", "mkv", "webm" -> "video/mp4"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                else -> "application/octet-stream"
            }
    }
}

/**
 * UDN cố định cho phiên chạy hiện tại (đủ dùng, không cần bền vững qua các lần khởi động).
 * PHẢI dùng CHUNG giá trị này ở cả description.xml (UDN) lẫn SsdpResponder (USN) — UPnP yêu
 * cầu 2 giá trị này khớp nhau; nếu lệch, nhiều TV strict (đặc biệt Sony Bravia) sẽ coi thiết
 * bị không hợp lệ và ÂM THẦM loại khỏi danh sách nguồn — dù description.xml/SCPD vẫn đúng và
 * SSDP responder vẫn "chạy" bình thường không báo lỗi gì.
 */
object DlnaIds {
    val udn: String by lazy { UUID.randomUUID().toString() }
}
