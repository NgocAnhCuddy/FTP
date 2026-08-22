package com.myfile.ui.util

import java.io.File

/**
 * Sao chép/di chuyển file hoặc thư mục (đệ quy) sang vị trí khác, có báo tiến trình theo byte
 * để hiển thị % + thời gian trên [ProgressDialogHelper]. Tự đổi tên khi trùng tên tại đích
 * (kiểu "tên (1).ext") thay vì ghi đè, giống hành vi mặc định của Samsung My Files.
 */
object FileOpsUtils {

    private const val BUFFER_SIZE = 8192
    private const val PROGRESS_THROTTLE_MS = 150L

    private fun totalSizeOf(files: List<File>): Long {
        var total = 0L
        fun walk(f: File) {
            if (f.isDirectory) f.listFiles()?.forEach { walk(it) } else total += f.length()
        }
        files.forEach { walk(it) }
        return total
    }

    /** Trả về tên không trùng trong [destDir]: "ảnh.jpg" -> "ảnh (1).jpg" nếu đã tồn tại. */
    private fun uniqueNameIn(destDir: File, name: String): String {
        var candidate = File(destDir, name)
        if (!candidate.exists()) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(destDir, "$base ($i)$ext")
            i++
        }
        return candidate.name
    }

    private fun copyFileWithProgress(
        src: File, dest: File, doneRef: LongArray, total: Long,
        onFile: (String) -> Unit, onProgress: (Long, Long) -> Unit
    ) {
        onFile(src.name)
        dest.parentFile?.mkdirs()
        src.inputStream().buffered().use { input ->
            dest.outputStream().buffered().use { output ->
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
        }
    }

    private fun copyRecursive(
        src: File, destDir: File, doneRef: LongArray, total: Long,
        onFile: (String) -> Unit, onProgress: (Long, Long) -> Unit
    ) {
        val name = uniqueNameIn(destDir, src.name)
        if (src.isDirectory) {
            val newDir = File(destDir, name)
            newDir.mkdirs()
            src.listFiles()?.forEach { child -> copyRecursive(child, newDir, doneRef, total, onFile, onProgress) }
        } else {
            copyFileWithProgress(src, File(destDir, name), doneRef, total, onFile, onProgress)
        }
    }

    /** Sao chép [sources] (file hoặc thư mục) vào [destDir]. Giữ nguyên bản gốc. */
    fun copy(
        sources: List<File>,
        destDir: File,
        onFile: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Unit> {
        return try {
            destDir.mkdirs()
            val total = totalSizeOf(sources)
            val doneRef = longArrayOf(0L)
            for (src in sources) copyRecursive(src, destDir, doneRef, total, onFile, onProgress)
            onProgress(total, total)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun deleteRecursive(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }

    /**
     * Di chuyển [sources] vào [destDir]. Thử renameTo trước (tức thì, cùng phân vùng) — chỉ khi
     * thất bại (khác phân vùng, VD trong -> thẻ SD) mới sao chép rồi xóa bản gốc, có báo tiến trình.
     */
    fun move(
        sources: List<File>,
        destDir: File,
        onFile: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Unit> {
        return try {
            destDir.mkdirs()
            val needCopyFallback = mutableListOf<File>()
            for (src in sources) {
                val name = uniqueNameIn(destDir, src.name)
                if (!src.renameTo(File(destDir, name))) needCopyFallback.add(src)
            }
            if (needCopyFallback.isNotEmpty()) {
                val total = totalSizeOf(needCopyFallback)
                val doneRef = longArrayOf(0L)
                for (src in needCopyFallback) {
                    copyRecursive(src, destDir, doneRef, total, onFile, onProgress)
                    deleteRecursive(src)
                }
                onProgress(total, total)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
