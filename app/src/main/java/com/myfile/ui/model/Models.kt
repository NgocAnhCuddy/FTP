package com.myfile.ui.model

/** Người dùng cấu hình cho FTP Server chạy trên máy. */
data class FtpUser(
    val username: String,
    val password: String,
    val homeDirectory: String,
    val writePermission: Boolean = true,
    val maxUploadRate: Int = 0,   // 0 = không giới hạn
    val maxDownloadRate: Int = 0
)

/** Cấu hình runtime hiện tại của FTP server. */
data class FtpServerConfig(
    val port: Int = 2121,
    val rootPath: String,
    val users: MutableList<FtpUser> = mutableListOf(),
    val anonymousLogin: Boolean = false
)

/**
 * Cấu hình cho SFTP Server chạy trên máy (dùng chung root/port pattern với FTP,
 * nhưng SFTP luôn yêu cầu xác thực theo giao thức — không có kiểu "guest" thật sự
 * như SMB, nên khi [anonymousLogin] bật, ta tự tạo 1 user ẩn "anonymous" không mật khẩu
 * ở tầng ứng dụng để trải nghiệm gần giống "không cần pass" nhất có thể).
 */
data class SftpServerConfig(
    val port: Int = 2222,
    val anonymousLogin: Boolean = true
)

/**
 * Cấu hình cho SMB Server (chia sẻ mạng kiểu Windows Network Share) chạy trên máy.
 * Khi [guestAccess] bật, TV/thiết bị khác trong cùng mạng LAN thấy và mở share mà
 * không cần nhập tài khoản — giống hệt cách router TP-Link/NAS gia đình chia sẻ ổ đĩa.
 */
data class SmbServerConfig(
    val shareName: String = "MyFileShare",
    val guestAccess: Boolean = true,
    val port: Int = 445
)

/** Giao thức của 1 kết nối máy chủ từ xa mà client hỗ trợ. */
enum class ConnectionType { FTP, SFTP, SMB }

/** Một kết nối client đã lưu để dùng lại — FTP, SFTP hoặc SMB. */
data class FtpConnectionProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 21,
    val username: String,
    val password: String,
    val useFtps: Boolean = false,
    val passiveMode: Boolean = true,
    val type: ConnectionType = ConnectionType.FTP,
    // SMB: tên share (vd. "Public", "Data") — bắt buộc khi type = SMB
    val smbShareName: String = "",
    // SMB: tên domain đăng nhập (thường để trống với NAS/Samba thông thường)
    val smbDomain: String = "",
    // SFTP: đường dẫn tới private key trên máy (nếu đăng nhập bằng khóa thay vì mật khẩu)
    val sftpPrivateKeyPath: String = ""
)

/** Đại diện 1 file/thư mục trên FTP server hoặc cloud, dùng chung cho UI danh sách. */
data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val modifiedTime: Long = 0L,
    val cloudFileId: String? = null // id nội bộ của provider cloud (Drive/Box/Dropbox)
)

enum class CloudProvider {
    GOOGLE_DRIVE, DROPBOX, BOX
}

/** Trạng thái liên kết tài khoản cloud, lưu local để hiển thị UI. */
data class CloudAccount(
    val provider: CloudProvider,
    val displayName: String? = null,
    val email: String? = null,
    val isLinked: Boolean = false
)

/** Một file/thư mục cục bộ trên máy (không phải FTP/cloud), dùng cho màn hình duyệt theo thể loại và Bộ nhớ trong ở Home. */
data class LocalFile(
    val name: String,
    val path: String,
    val size: Long,
    val modifiedTime: Long,
    val mimeType: String? = null,
    val isDirectory: Boolean = false,
    val itemCount: Int = 0 // số mục con nếu là thư mục
)

/**
 * 1 nút trong cây thư mục của file nén (zip/7z), dùng cho màn hình "Xem trước" (ArchivePreviewActivity).
 * [entryPath] là đường dẫn đầy đủ bên trong file nén (vd "ftp-project/app/src/main"),
 * [children] chỉ chứa các mục con TRỰC TIẾP (không đệ quy hết cây), phục vụ điều hướng kiểu breadcrumb.
 */
data class ArchiveNode(
    val name: String,
    val entryPath: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val children: MutableList<ArchiveNode> = mutableListOf()
)

/** 1 mục (ảnh hoặc video) hiển thị trong trình xem media toàn màn hình, vuốt qua lại được. */
data class MediaItem(
    val uri: String,
    val name: String,
    val isVideo: Boolean,
    // Đường dẫn thật trên máy (vd /storage/emulated/0/DCIM/a.mp4), khác với [uri] khi uri là
    // content:// (qua FileProvider). Cần để MediaStreamServer đọc trực tiếp file khi "Phát lên TV";
    // null nếu file đến từ nguồn không có đường dẫn cục bộ (vd cloud).
    val realPath: String? = null
)

/** Một dòng log hoạt động của server (kết nối, upload, download, lỗi...). */
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val message: String,
    // Nguồn phát sinh log, vd. "FTP", "SFTP", "SMB", "HTML" — dùng để lọc trong bảng điều khiển gỡ lỗi.
    val source: String = "APP",
    // Chi tiết kỹ thuật (stack trace rút gọn / dòng gây lỗi), hiển thị khi người dùng mở rộng dòng log.
    val detail: String? = null
)

enum class LogLevel { INFO, SUCCESS, WARNING, ERROR }

/**
 * Thư mục cục bộ (Bộ nhớ trong/Thẻ nhớ SD) mà người dùng đã ghim để truy cập nhanh, hiển thị
 * ở màn Home dưới danh mục có sẵn (Ảnh, Video, Tải xuống...). [path] là đường dẫn tuyệt đối
 * thật trên đĩa — nếu thư mục bị xoá sau khi ghim, UI tự phát hiện qua File(path).exists() và
 * ẩn/báo lỗi thay vì để app crash khi bấm vào bookmark trỏ tới nơi không còn tồn tại.
 */
data class FolderBookmark(
    val path: String,
    val name: String,
    val addedAt: Long = System.currentTimeMillis()
)
