package com.myfile.ui.util

import com.github.junrar.rarfile.FileHeader
import com.myfile.ui.model.ArchiveNode
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Tiện ích nén/giải nén dùng CHUNG cho toàn app: Bộ nhớ trong, FTP client (nén trước khi
 * upload, giải nén sau khi tải về), và Cloud (tương tự). ZIP dùng java.util.zip có sẵn
 * trong JDK, 7Z (chỉ giải nén — tạo 7z cần mã hoá LZMA phức tạp hơn nên tạm hỗ trợ ZIP khi nén)
 * dùng Apache Commons Compress. RAR (chỉ giải nén, kể cả file có mật khẩu — không thể TẠO
 * file .rar mới vì đây là định dạng độc quyền, đòi hỏi giấy phép thương mại từ RARLAB) dùng
 * thư viện junrar.
 */
object ArchiveUtils {

    private const val BUFFER_SIZE = 8192
    private const val PROGRESS_THROTTLE_MS = 150L

    /** Tính tổng dung lượng (đệ quy vào thư mục con) — dùng làm mẫu số % cho tiến trình nén. */
    private fun totalSizeOf(files: List<File>): Long {
        var total = 0L
        fun walk(f: File) {
            if (f.isDirectory) f.listFiles()?.forEach { walk(it) } else total += f.length()
        }
        files.forEach { walk(it) }
        return total
    }

    /** Copy có báo tiến trình (điều tiết theo thời gian để không spam callback trên file lớn). */
    private fun copyWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        doneRef: LongArray,
        total: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var lastReportAt = 0L
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            doneRef[0] = doneRef[0] + read.toLong()
            val now = System.currentTimeMillis()
            if (now - lastReportAt >= PROGRESS_THROTTLE_MS) {
                lastReportAt = now
                onProgress(doneRef[0], total)
            }
        }
    }

    /**
     * Nén 1 hoặc nhiều file/thư mục vào 1 file .zip tại [destZip]. Giữ cấu trúc thư mục con.
     * Nếu [password] khác null/rỗng, dùng zip4j để tạo zip MÃ HOÁ AES-256 (java.util.zip
     * chuẩn JDK không hỗ trợ mã hoá dưới bất kỳ hình thức nào) — khi đó [onFile]/[onProgress]
     * vẫn được gọi nhưng zip4j tự quản lý tiến trình nội bộ khác cơ chế copyWithProgress nên độ
     * mượt của progress bar sẽ khác đôi chút so với zip thường (chấp nhận được, ưu tiên đúng
     * chức năng mã hoá hơn là độ mượt tuyệt đối của thanh tiến trình).
     */
    fun zip(
        sources: List<File>,
        destZip: File,
        onFile: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        password: String? = null
    ): Result<Unit> {
        if (!password.isNullOrEmpty()) {
            return zipWithPassword(sources, destZip, password, onFile, onProgress)
        }
        return try {
            destZip.parentFile?.mkdirs()
            val total = totalSizeOf(sources)
            val doneRef = longArrayOf(0L)
            ZipOutputStream(destZip.outputStream().buffered()).use { zos ->
                for (src in sources) {
                    if (src.isDirectory) {
                        addDirToZip(zos, src, src.name, doneRef, total, onFile, onProgress)
                    } else {
                        addFileToZip(zos, src, src.name, doneRef, total, onFile, onProgress)
                    }
                }
            }
            onProgress(total, total)
            Result.success(Unit)
        } catch (e: Exception) {
            destZip.delete()
            Result.failure(e)
        }
    }

    private fun addDirToZip(
        zos: ZipOutputStream, dir: File, entryPrefix: String,
        doneRef: LongArray, total: Long, onFile: (String) -> Unit, onProgress: (Long, Long) -> Unit
    ) {
        val children = dir.listFiles() ?: return
        if (children.isEmpty()) {
            zos.putNextEntry(ZipEntry("$entryPrefix/"))
            zos.closeEntry()
            return
        }
        for (child in children) {
            val entryName = "$entryPrefix/${child.name}"
            if (child.isDirectory) addDirToZip(zos, child, entryName, doneRef, total, onFile, onProgress)
            else addFileToZip(zos, child, entryName, doneRef, total, onFile, onProgress)
        }
    }

    private fun addFileToZip(
        zos: ZipOutputStream, file: File, entryName: String,
        doneRef: LongArray, total: Long, onFile: (String) -> Unit, onProgress: (Long, Long) -> Unit
    ) {
        onFile(file.name)
        zos.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { copyWithProgress(it, zos, doneRef, total, onProgress) }
        zos.closeEntry()
    }

    /** Tạo file .zip MÃ HOÁ AES-256 bằng zip4j — dùng nội bộ bởi zip() khi có [password]. */
    private fun zipWithPassword(
        sources: List<File>,
        destZip: File,
        password: String,
        onFile: (String) -> Unit,
        onProgress: (Long, Long) -> Unit
    ): Result<Unit> {
        return try {
            destZip.parentFile?.mkdirs()
            if (destZip.exists()) destZip.delete()
            val total = totalSizeOf(sources)
            var done = 0L
            val zipFile = net.lingala.zip4j.ZipFile(destZip, password.toCharArray())
            val params = net.lingala.zip4j.model.ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
            }
            for (src in sources) {
                onFile(src.name)
                if (src.isDirectory) {
                    zipFile.addFolder(src, params)
                } else {
                    zipFile.addFile(src, params)
                }
                done += if (src.isDirectory) totalSizeOf(listOf(src)) else src.length()
                onProgress(done, total)
            }
            onProgress(total, total)
            Result.success(Unit)
        } catch (e: Exception) {
            destZip.delete()
            Result.failure(e)
        }
    }

    /**
     * Giải nén file .zip vào thư mục đích [destDir], giữ nguyên cấu trúc thư mục con.
     * Nếu [password] khác null/rỗng, dùng zip4j để giải mã (java.util.zip chuẩn JDK không đọc
     * được zip mã hoá dưới bất kỳ hình thức nào, kể cả khi biết đúng mật khẩu).
     */
    fun unzip(
        zipFile: File,
        destDir: File,
        onFile: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        password: String? = null
    ): Result<Unit> {
        if (!password.isNullOrEmpty()) {
            return unzipWithPassword(zipFile, destDir, password, onFile, onProgress)
        }
        return try {
            destDir.mkdirs()
            // Đọc trước qua ZipFile (central directory) để lấy tổng dung lượng thật — ZipInputStream
            // đọc tuần tự nên nhiều trường hợp entry.size trả về -1 (chưa đọc tới data descriptor).
            val total = java.util.zip.ZipFile(zipFile).use { zf ->
                zf.entries().asSequence().filter { !it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
            }
            val doneRef = longArrayOf(0L)
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = safeDestFile(destDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        onFile(entry.name.substringAfterLast('/'))
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { copyWithProgress(zis, it, doneRef, total, onProgress) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            onProgress(total, total)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Giải nén file .zip MÃ HOÁ bằng zip4j — dùng nội bộ bởi unzip() khi có [password]. */
    private fun unzipWithPassword(
        zipFile: File,
        destDir: File,
        password: String,
        onFile: (String) -> Unit,
        onProgress: (Long, Long) -> Unit
    ): Result<Unit> {
        return try {
            destDir.mkdirs()
            val zf = net.lingala.zip4j.ZipFile(zipFile, password.toCharArray())
            val headers = zf.fileHeaders
            val total = headers.filterNot { it.isDirectory }.sumOf { it.uncompressedSize }
            var done = 0L
            for (header in headers) {
                if (!header.isDirectory) onFile(header.fileName.substringAfterLast('/'))
                zf.extractFile(header, destDir.absolutePath)
                if (!header.isDirectory) {
                    done += header.uncompressedSize
                    onProgress(done, total)
                }
            }
            onProgress(total, total)
            Result.success(Unit)
        } catch (e: net.lingala.zip4j.exception.ZipException) {
            // zip4j ném ZipException riêng cho cả 2 trường hợp: sai mật khẩu VÀ file hỏng —
            // không phân biệt rạch ròi bằng type riêng, Activity gọi hàm này tự suy luận "khả
            // năng cao là sai mật khẩu" nếu người dùng VỪA nhập mật khẩu (xem CategoryFilesActivity).
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** true nếu file .zip này có mật khẩu bảo vệ — dùng để hỏi mật khẩu người dùng TRƯỚC khi giải nén. */
    fun isZipPasswordProtected(zipFile: File): Boolean {
        return try {
            net.lingala.zip4j.ZipFile(zipFile).isEncrypted
        } catch (e: Exception) {
            false
        }
    }


    fun un7z(
        sevenZFile: File,
        destDir: File,
        onFile: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Unit> {
        return try {
            destDir.mkdirs()
            val total = listEntries(sevenZFile).getOrNull()
                ?.filter { !it.isDirectory }?.sumOf { it.size.coerceAtLeast(0) } ?: 0L
            val doneRef = longArrayOf(0L)
            SevenZFile.builder().setFile(sevenZFile).get().use { sevenZ ->
                var next: SevenZArchiveEntry? = sevenZ.nextEntry
                while (next != null) {
                    val entry = next
                    val outFile = safeDestFile(destDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        onFile(entry.name.substringAfterLast('/'))
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var lastReportAt = 0L
                            var read: Int
                            while (sevenZ.read(buffer).also { read = it } != -1) {
                                out.write(buffer, 0, read)
                                doneRef[0] = doneRef[0] + read.toLong()
                                val now = System.currentTimeMillis()
                                if (now - lastReportAt >= PROGRESS_THROTTLE_MS) {
                                    lastReportAt = now
                                    onProgress(doneRef[0], total)
                                }
                            }
                        }
                    }
                    next = sevenZ.nextEntry
                }
            }
            onProgress(total, total)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Giải nén file .rar vào thư mục đích [destDir] bằng junrar (hỗ trợ RAR tới v7, kể cả file
     * có mật khẩu qua tham số [password] và archive nhiều phần .partN.rar — chỉ cần trỏ vào
     * đúng phần đầu tiên .part1.rar, junrar tự tìm các phần tiếp theo trong cùng thư mục).
     *
     * LƯU Ý BẢO MẬT: dù dependency đã ở bản 7.5.10 (đã vá CVE-2026-28208/CVE-2026-41245— lỗ
     * hổng path-traversal qua dấu \ trong tên entry), app vẫn CHỦ ĐỘNG áp thêm [safeDestFile]
     * làm lớp phòng thủ kép độc lập với bản vá của thư viện — cùng nguyên tắc phòng thủ theo
     * chiều sâu đã áp dụng cho unzip()/un7z() (chống Zip Slip), không phụ thuộc hoàn toàn vào
     * 1 lớp bảo vệ duy nhất.
     */
    fun unrar(
        rarFile: File,
        destDir: File,
        password: String? = null,
        onFile: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Unit> {
        return try {
            destDir.mkdirs()
            val total = listRarEntries(rarFile, password).getOrNull()
                ?.filter { !it.isDirectory }?.sumOf { it.size.coerceAtLeast(0) } ?: 0L
            val doneRef = longArrayOf(0L)

            openRarArchive(rarFile, password).use { archive ->
                var header: FileHeader? = archive.nextFileHeader()
                while (header != null) {
                    val h = header
                    val entryName = h.fileNameString.replace('\\', '/')
                    val outFile = safeDestFile(destDir, entryName)
                    if (h.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        onFile(entryName.substringAfterLast('/'))
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            archive.getInputStream(h).use { inp ->
                                copyWithProgress(inp, out, doneRef, total, onProgress)
                            }
                        }
                    }
                    header = archive.nextFileHeader()
                }
            }
            onProgress(total, total)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Mở archive RAR, tự thử KHÔNG mật khẩu trước — junrar cần Archive(File, password) khi có mật khẩu. */
    private fun openRarArchive(rarFile: File, password: String?): com.github.junrar.Archive {
        return if (password.isNullOrEmpty()) {
            com.github.junrar.Archive(rarFile)
        } else {
            com.github.junrar.Archive(rarFile, password)
        }
    }

    /** Liệt kê nội dung .rar mà KHÔNG giải nén ra đĩa — dùng cho màn hình "Xem trước". */
    fun listRarEntries(rarFile: File, password: String? = null): Result<List<ArchiveEntryInfo>> {
        return try {
            val entries = mutableListOf<ArchiveEntryInfo>()
            openRarArchive(rarFile, password).use { archive ->
                var header: FileHeader? = archive.nextFileHeader()
                while (header != null) {
                    val h = header
                    val name = h.fileNameString.replace('\\', '/')
                    entries.add(ArchiveEntryInfo(name, if (h.isDirectory) 0L else h.fullUnpackSize, h.isDirectory))
                    header = archive.nextFileHeader()
                }
            }
            Result.success(entries.sortedBy { it.name.lowercase() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** true nếu file .rar này có mật khẩu bảo vệ — dùng để hỏi mật khẩu người dùng TRƯỚC khi giải nén. */
    fun isRarPasswordProtected(rarFile: File): Boolean {
        return try {
            com.github.junrar.Archive(rarFile).use { archive ->
                archive.mainHeader?.isEncrypted ?: false
            }
        } catch (e: com.github.junrar.exception.RarException) {
            // Nhiều bản RAR ném exception ngay khi mở nếu có mật khẩu (không đọc được header
            // mà không giải mã) — coi đây cũng là dấu hiệu có mật khẩu, để UI hỏi người dùng.
            true
        } catch (e: Exception) {
            false
        }
    }


    private fun safeDestFile(destDir: File, entryName: String): File {
        val outFile = File(destDir, entryName)
        val destPath = destDir.canonicalPath
        val outPath = outFile.canonicalPath
        if (!outPath.startsWith(destPath + File.separator) && outPath != destPath) {
            throw SecurityException("Đường dẫn không hợp lệ trong file nén: $entryName")
        }
        return outFile
    }

    /** Thông tin 1 mục bên trong file nén, dùng để xem trước nội dung mà không cần giải nén ra đĩa. */
    data class ArchiveEntryInfo(val name: String, val size: Long, val isDirectory: Boolean)

    /** Liệt kê nội dung file .zip, .7z hoặc .rar mà KHÔNG giải nén ra đĩa — dùng cho màn hình "Xem trước". */
    fun listEntries(archiveFile: File): Result<List<ArchiveEntryInfo>> {
        if (isRar(archiveFile.name)) return listRarEntries(archiveFile)
        return try {
            val entries = mutableListOf<ArchiveEntryInfo>()
            if (isZip(archiveFile.name)) {
                ZipInputStream(archiveFile.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        entries.add(ArchiveEntryInfo(entry.name, if (entry.isDirectory) 0L else entry.size, entry.isDirectory))
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } else {
                SevenZFile.builder().setFile(archiveFile).get().use { sevenZ ->
                    var next: SevenZArchiveEntry? = sevenZ.nextEntry
                    while (next != null) {
                        val entry = next
                        entries.add(ArchiveEntryInfo(entry.name, if (entry.isDirectory) 0L else entry.size, entry.isDirectory))
                        next = sevenZ.nextEntry
                    }
                }
            }
            Result.success(entries.sortedBy { it.name.lowercase() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isZip(name: String): Boolean = name.endsWith(".zip", ignoreCase = true)
    fun is7z(name: String): Boolean = name.endsWith(".7z", ignoreCase = true)
    fun isRar(name: String): Boolean = name.endsWith(".rar", ignoreCase = true)
    fun isArchive(name: String): Boolean = isZip(name) || is7z(name) || isRar(name)

    /**
     * Dựng cây thư mục từ danh sách entry phẳng của [listEntries], phục vụ màn hình "Xem trước"
     * điều hướng theo breadcrumb (giống Files/My Files). Trả về node gốc ảo (entryPath rỗng).
     */
    fun buildTree(entries: List<ArchiveEntryInfo>): ArchiveNode {
        val root = ArchiveNode(name = "", entryPath = "", isDirectory = true)
        val nodesByPath = mutableMapOf("" to root)

        fun getOrCreateDir(path: String): ArchiveNode {
            nodesByPath[path]?.let { return it }
            val parentPath = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val parent = getOrCreateDir(parentPath)
            val node = ArchiveNode(name = name, entryPath = path, isDirectory = true)
            parent.children.add(node)
            nodesByPath[path] = node
            return node
        }

        for (entry in entries) {
            val cleanPath = entry.name.trim('/')
            if (cleanPath.isEmpty()) continue
            if (entry.isDirectory) {
                getOrCreateDir(cleanPath)
            } else {
                val parentPath = cleanPath.substringBeforeLast('/', "")
                val name = cleanPath.substringAfterLast('/')
                val parent = getOrCreateDir(parentPath)
                // Tránh trùng nếu entry file xuất hiện 2 lần (hiếm nhưng có thể xảy ra với zip lỗi)
                if (parent.children.none { !it.isDirectory && it.entryPath == cleanPath }) {
                    parent.children.add(ArchiveNode(name = name, entryPath = cleanPath, isDirectory = false, size = entry.size))
                }
            }
        }

        fun sortRecursive(node: ArchiveNode) {
            node.children.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            node.children.forEach { sortRecursive(it) }
        }
        sortRecursive(root)
        return root
    }

    /**
     * Giải nén CHỈ những entry có đường dẫn nằm trong [selectedPaths] (hoặc là con cháu của 1 thư mục
     * đã chọn) vào [destDir]. Dùng cho màn hình "Xem trước" khi người dùng chỉ tick chọn 1 phần.
     * Nếu [selectedPaths] rỗng, giải nén toàn bộ (tương đương [unzip]/[un7z]).
     */
    fun extractSelected(archiveFile: File, destDir: File, selectedPaths: Set<String>, password: String? = null): Result<Unit> {
        if (selectedPaths.isEmpty()) {
            return when {
                isZip(archiveFile.name) -> unzip(archiveFile, destDir, password = password)
                isRar(archiveFile.name) -> unrar(archiveFile, destDir, password)
                else -> un7z(archiveFile, destDir)
            }
        }
        fun isSelected(entryName: String): Boolean {
            val clean = entryName.trim('/')
            return selectedPaths.any { sel -> clean == sel || clean.startsWith("$sel/") }
        }
        if (isRar(archiveFile.name)) {
            return try {
                destDir.mkdirs()
                openRarArchive(archiveFile, password).use { archive ->
                    var header: FileHeader? = archive.nextFileHeader()
                    while (header != null) {
                        val h = header
                        val entryName = h.fileNameString.replace('\\', '/')
                        if (isSelected(entryName)) {
                            val outFile = safeDestFile(destDir, entryName)
                            if (h.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { out ->
                                    archive.getInputStream(h).use { inp -> inp.copyTo(out) }
                                }
                            }
                        }
                        header = archive.nextFileHeader()
                    }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        if (isZip(archiveFile.name) && !password.isNullOrEmpty()) {
            return try {
                destDir.mkdirs()
                val zf = net.lingala.zip4j.ZipFile(archiveFile, password.toCharArray())
                for (header in zf.fileHeaders) {
                    val entryName = header.fileName.replace('\\', '/')
                    if (isSelected(entryName)) {
                        // safeDestFile() chỉ dùng để KIỂM TRA an toàn đường dẫn (chống path
                        // traversal) — zip4j tự quản lý việc ghi file thật qua extractFile(),
                        // nên chỉ cần gọi safeDestFile() để validate rồi bỏ qua kết quả trả về.
                        safeDestFile(destDir, entryName)
                        zf.extractFile(header, destDir.absolutePath)
                    }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        return try {
            destDir.mkdirs()
            if (isZip(archiveFile.name)) {
                ZipInputStream(archiveFile.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (isSelected(entry.name)) {
                            val outFile = safeDestFile(destDir, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { zis.copyTo(it) }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } else {
                SevenZFile.builder().setFile(archiveFile).get().use { sevenZ ->
                    var next: SevenZArchiveEntry? = sevenZ.nextEntry
                    while (next != null) {
                        val entry = next
                        if (isSelected(entry.name)) {
                            val outFile = safeDestFile(destDir, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { out ->
                                    val buffer = ByteArray(8192)
                                    var read: Int
                                    while (sevenZ.read(buffer).also { read = it } != -1) {
                                        out.write(buffer, 0, read)
                                    }
                                }
                            }
                        }
                        next = sevenZ.nextEntry
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
