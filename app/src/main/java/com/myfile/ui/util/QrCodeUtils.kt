package com.myfile.ui.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.myfile.ui.model.ConnectionType
import com.myfile.ui.model.FtpConnectionProfile

/**
 * Sinh và đọc mã QR chứa thông tin kết nối FTP/SFTP/SMB, để 1 máy có thể quét mã hiển thị
 * trên máy kia thay vì gõ tay IP/port/user/pass. Định dạng URI dùng chung 1 quy ước đơn giản:
 *
 *   ftp://user:pass@host:port/           (FTP)
 *   sftp://user:pass@host:port/          (SFTP)
 *   smb://user:pass@host:port/shareName  (SMB, thêm query domain nếu có)
 */
object QrCodeUtils {

    /** Vẽ mã QR từ 1 chuỗi bất kỳ thành Bitmap để hiển thị trong ImageView. */
    fun encode(content: String, sizePx: Int = 720): Bitmap? {
        if (content.isBlank()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }

    /** Đóng gói 1 kết nối server (đang chạy trên chính máy này) thành URI để tạo mã QR. */
    fun buildServerUri(host: String, port: Int, username: String?, password: String?): String {
        val userInfo = if (!username.isNullOrBlank()) {
            "$username:${password.orEmpty()}@"
        } else ""
        return "ftp://$userInfo$host:$port/"
    }

    /**
     * Phân tích 1 chuỗi quét được từ mã QR thành FtpConnectionProfile để tự điền form kết nối.
     * Trả về null nếu chuỗi không đúng định dạng ftp://, sftp:// hoặc smb://.
     */
    fun parseConnectionUri(raw: String): FtpConnectionProfile? {
        val text = raw.trim()
        val scheme = when {
            text.startsWith("ftp://", ignoreCase = true) -> ConnectionType.FTP
            text.startsWith("sftp://", ignoreCase = true) -> ConnectionType.SFTP
            text.startsWith("smb://", ignoreCase = true) -> ConnectionType.SMB
            else -> return null
        }
        return try {
            val uri = java.net.URI(text)
            val host = uri.host ?: return null
            val defaultPort = when (scheme) {
                ConnectionType.FTP -> 21
                ConnectionType.SFTP -> 22
                ConnectionType.SMB -> 445
            }
            val port = if (uri.port > 0) uri.port else defaultPort
            var username = ""
            var password = ""
            uri.userInfo?.let { info ->
                val parts = info.split(":", limit = 2)
                username = parts.getOrNull(0).orEmpty()
                password = parts.getOrNull(1).orEmpty()
            }
            val share = uri.path?.trim('/').orEmpty()
            FtpConnectionProfile(
                name = if (scheme == ConnectionType.SMB) "$host/$share" else host,
                host = host,
                port = port,
                username = username,
                password = password,
                type = scheme,
                smbShareName = if (scheme == ConnectionType.SMB) share else ""
            )
        } catch (e: Exception) {
            null
        }
    }
}
