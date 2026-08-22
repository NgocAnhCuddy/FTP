package com.myfile.ui.server

import com.myfile.ui.model.FtpUser
import com.myfile.ui.util.LogBus
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.ftplet.FtpReply
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.PropertiesUserManager
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

/**
 * Bọc vòng đời của Apache MINA FtpServer: cấu hình user, thư mục gốc, cổng,
 * và log mọi sự kiện (đăng nhập, upload, download, lỗi) qua LogBus để UI hiển thị realtime.
 */
class FtpServerManager {

    private var ftpServer: FtpServer? = null

    val isRunning: Boolean
        get() = ftpServer?.isStopped == false

    fun start(port: Int, rootPath: String, users: List<FtpUser>, localIp: String? = null) {
        if (isRunning) return

        val serverFactory = FtpServerFactory()

        // Cấu hình dải cổng passive cố định: nếu không set, mỗi lần truyền dữ liệu
        // server chọn 1 cổng ngẫu nhiên bất kỳ, dễ bị router/firewall của mạng
        // (đặc biệt mạng di động/hotspot) chặn — khiến máy khác kết nối được lệnh (control
        // channel) nhưng không liệt kê/tải được file, tức là trông như "không hoạt động".
        // Cố định dải cổng để dễ chẩn đoán và (nếu cần dùng qua mạng ngoài LAN) port-forward.
        val dataConnConfig = org.apache.ftpserver.DataConnectionConfigurationFactory()
        dataConnConfig.setActiveEnabled(true)
        dataConnConfig.setPassivePorts(PASSIVE_PORT_RANGE)
        localIp?.let { ip -> dataConnConfig.setPassiveAddress(ip) }

        val listenerFactory = ListenerFactory()
        listenerFactory.port = port
        listenerFactory.setDataConnectionConfiguration(dataConnConfig.createDataConnectionConfiguration())
        serverFactory.addListener("default", listenerFactory.createListener())

        // User manager trong bộ nhớ (không cần file users.properties trên đĩa)
        val userManager = InMemoryUserManagerBuilder.build(rootPath, users)
        serverFactory.userManager = userManager

        // Ftplet để log các sự kiện quan trọng
        serverFactory.ftplets["logFtplet"] = object : DefaultFtplet() {
            override fun onConnect(session: FtpSession): FtpletResult {
                LogBus.info("Kết nối mới từ ${session.clientAddress?.address?.hostAddress}", source = "FTP")
                return FtpletResult.DEFAULT
            }

            override fun onDisconnect(session: FtpSession): FtpletResult {
                LogBus.info("Ngắt kết nối: ${session.clientAddress?.address?.hostAddress}", source = "FTP")
                return FtpletResult.DEFAULT
            }

            override fun onLogin(session: FtpSession, request: FtpRequest): FtpletResult {
                LogBus.success("Đăng nhập thành công: ${session.user?.name}", source = "FTP")
                return FtpletResult.DEFAULT
            }

            override fun onUploadEnd(session: FtpSession, request: FtpRequest): FtpletResult {
                LogBus.success("Tải lên hoàn tất: ${request.argument}", source = "FTP")
                return FtpletResult.DEFAULT
            }

            override fun onDownloadEnd(session: FtpSession, request: FtpRequest): FtpletResult {
                LogBus.success("Tải xuống hoàn tất: ${request.argument}", source = "FTP")
                return FtpletResult.DEFAULT
            }

            override fun onDeleteEnd(session: FtpSession, request: FtpRequest): FtpletResult {
                LogBus.warning("Đã xóa: ${request.argument}", source = "FTP")
                return FtpletResult.DEFAULT
            }

            override fun onMkdirEnd(session: FtpSession, request: FtpRequest): FtpletResult {
                LogBus.info("Tạo thư mục: ${request.argument}", source = "FTP")
                return FtpletResult.DEFAULT
            }
        }

        ftpServer = serverFactory.createServer()
        try {
            ftpServer?.start()
            LogBus.success("Máy chủ FTP đã khởi động trên cổng $port", source = "FTP")
        } catch (e: FtpException) {
            LogBus.error("Không thể khởi động máy chủ FTP trên cổng $port", source = "FTP", throwable = e)
            throw e
        }
    }

    fun stop() {
        ftpServer?.stop()
        ftpServer = null
        LogBus.info("Máy chủ FTP đã dừng", source = "FTP")
    }

    companion object {
        /** Dải cổng cho passive mode — đủ rộng cho vài kết nối đồng thời, dễ mở trên router nếu cần. */
        const val PASSIVE_PORT_RANGE = "60000-60050"
    }
}

/** Xây dựng UserManager trong bộ nhớ từ danh sách FtpUser, không cần ghi file properties ra đĩa. */
private object InMemoryUserManagerBuilder {
    fun build(rootPath: String, users: List<FtpUser>): UserManager {
        // PropertiesUserManager cần 1 file backing; ta tạo file tạm trong bộ nhớ ứng dụng.
        val tempPropsFile = File.createTempFile("ftpusers", ".properties")
        val manager = PropertiesUserManager(ClearTextPasswordEncryptor(), tempPropsFile, "admin")

        users.forEach { u ->
            val baseUser = BaseUser()
            baseUser.name = u.username
            baseUser.password = u.password
            val home = if (u.homeDirectory.isBlank()) rootPath else u.homeDirectory
            baseUser.homeDirectory = home

            val authorities = mutableListOf<Authority>()
            if (u.writePermission) {
                authorities.add(WritePermission())
            }
            authorities.add(ConcurrentLoginPermission(10, 5))
            if (u.maxUploadRate > 0 || u.maxDownloadRate > 0) {
                authorities.add(TransferRatePermission(u.maxDownloadRate, u.maxUploadRate))
            }
            baseUser.authorities = authorities
            baseUser.maxIdleTime = 300

            manager.save(baseUser)
        }
        return manager
    }
}
