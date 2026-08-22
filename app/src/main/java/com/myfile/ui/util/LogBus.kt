package com.myfile.ui.util

import android.content.Context
import com.myfile.ui.model.LogEntry
import com.myfile.ui.model.LogLevel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Bus log toàn cục, đơn giản (singleton in-memory), để FtpServerService phát log
 * và UI (LogActivity / MainActivity) lắng nghe hiển thị theo thời gian thực.
 *
 * QUAN TRỌNG — vì sao thêm ghi file: trước đây log CHỈ tồn tại trong RAM (MutableStateFlow).
 * Khi app crash do exception KHÔNG được bắt (NullPointerException, IndexOutOfBounds...) ở BẤT
 * KỲ đâu trong app, Android mặc định tự kill toàn bộ tiến trình và đưa người dùng về màn hình
 * chính NGAY LẬP TỨC — không có cơ chế nào tự ghi log lại, nên log trong RAM biến mất hoàn
 * toàn, và Bảng điều khiển gỡ lỗi trống trơn dù app vừa crash — đúng triệu chứng "tự out ra
 * màn hình chính mà không rõ lý do". Giờ MỌI log (đặc biệt log lỗi) được ghi ĐỒNG BỘ xuống 1
 * file text ngay khi log() được gọi — kể cả nếu tiến trình chết ngay sau đó 1 dòng code, dòng
 * log vẫn đã nằm an toàn trên đĩa. Kèm crash handler toàn cục trong MyFileApp.kt bắt mọi
 * exception chưa được xử lý, ghi đầy đủ stack trace vào đúng file này trước khi tiến trình bị
 * hệ thống kill, để lần sau mở app lên sẽ thấy NGAY nguyên nhân crash trong Bảng điều khiển.
 */
object LogBus {
    private const val MAX_ENTRIES = 500
    private const val MAX_FILE_LINES = 1000
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _events = MutableSharedFlow<LogEntry>(extraBufferCapacity = 64)
    val events: SharedFlow<LogEntry> = _events.asSharedFlow()

    private var logFile: File? = null

    /** Gọi 1 lần trong MyFileApp.onCreate() TRƯỚC khi cài crash handler. */
    fun init(context: Context) {
        val dir = context.filesDir
        logFile = File(dir, "debug_log.txt")
        loadPersistedLogs()
    }

    private fun loadPersistedLogs() {
        val file = logFile ?: return
        if (!file.exists()) return
        try {
            val restored = file.readLines().mapNotNull { line -> parseLine(line) }
            if (restored.isNotEmpty()) {
                _logs.value = (restored + _logs.value).takeLast(MAX_ENTRIES)
            }
        } catch (e: Exception) {
            // Đọc log cũ thất bại không được phép làm crash app lần nữa — bỏ qua lặng lẽ.
        }
    }

    // Định dạng 1 dòng file: "yyyy-MM-dd HH:mm:ss|LEVEL|source|message|detail(có thể rỗng, \n đã escape thành \\n)"
    private fun parseLine(line: String): LogEntry? {
        val parts = line.split("|", limit = 5)
        if (parts.size < 4) return null
        val level = try { LogLevel.valueOf(parts[1]) } catch (e: Exception) { return null }
        val detail = if (parts.size >= 5 && parts[4].isNotEmpty()) parts[4].replace("\\n", "\n") else null
        return LogEntry(level = level, message = parts[3], source = parts[2], detail = detail)
    }

    private fun persist(entry: LogEntry) {
        val file = logFile ?: return
        try {
            val detailEscaped = entry.detail?.replace("\n", "\\n") ?: ""
            val line = "${dateFmt.format(java.util.Date())}|${entry.level.name}|${entry.source}|${entry.message}|$detailEscaped\n"
            file.appendText(line)
            // Cắt bớt file nếu quá dài, tránh phình vô hạn qua nhiều phiên chạy app.
            val lines = file.readLines()
            if (lines.size > MAX_FILE_LINES) {
                file.writeText(lines.takeLast(MAX_FILE_LINES).joinToString("\n", postfix = "\n"))
            }
        } catch (e: Exception) {
            // Ghi file lỗi (hết dung lượng, quyền...) không được phép làm crash app — bỏ qua.
        }
    }

    fun log(level: LogLevel, message: String, source: String = "APP", detail: String? = null) {
        val entry = LogEntry(level = level, message = message, source = source, detail = detail)
        val updated = (_logs.value + entry).takeLast(MAX_ENTRIES)
        _logs.value = updated
        _events.tryEmit(entry)
        persist(entry)
    }

    fun info(msg: String, source: String = "APP") = log(LogLevel.INFO, msg, source)
    fun success(msg: String, source: String = "APP") = log(LogLevel.SUCCESS, msg, source)
    fun warning(msg: String, source: String = "APP") = log(LogLevel.WARNING, msg, source)

    /**
     * Log lỗi kèm chi tiết kỹ thuật để debug: nếu truyền [throwable], tự trích dòng gây lỗi
     * (file:số dòng) từ stack trace đầu tiên thuộc package của app (com.myfile.ui), giúp
     * xác định NHANH lỗi xảy ra ở đâu thay vì chỉ có thông báo lỗi chung chung.
     */
    fun error(msg: String, source: String = "APP", throwable: Throwable? = null) {
        val detail = throwable?.let { t ->
            val appFrame = t.stackTrace.firstOrNull { it.className.startsWith("com.myfile.ui") }
            buildString {
                append(t::class.java.simpleName)
                t.message?.let { append(": ").append(it) }
                if (appFrame != null) {
                    append("\n→ ").append(appFrame.fileName ?: "?").append(":").append(appFrame.lineNumber)
                    append(" (").append(appFrame.methodName).append(")")
                }
            }
        }
        log(LogLevel.ERROR, msg, source, detail)
    }

    /**
     * Log TOÀN BỘ stack trace (không chỉ dòng đầu app) — dùng riêng cho crash handler toàn
     * cục, vì lúc app tự crash cần thông tin đầy đủ nhất có thể để chẩn đoán, khác với error()
     * thông thường (chỉ cần gọn 1 dòng cho log nghiệp vụ hàng ngày).
     */
    fun crash(throwable: Throwable) {
        val fullTrace = android.util.Log.getStackTraceString(throwable)
        log(LogLevel.ERROR, "Ứng dụng bị đóng đột ngột: ${throwable::class.java.simpleName}", "CRASH", fullTrace)
    }

    fun clear() {
        _logs.value = emptyList()
        logFile?.let { if (it.exists()) it.writeText("") }
    }
}
