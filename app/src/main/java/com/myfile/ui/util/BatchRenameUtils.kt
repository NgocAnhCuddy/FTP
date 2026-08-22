package com.myfile.ui.util

import java.io.File

/**
 * Tính toán tên mới cho tính năng "Đổi tên hàng loạt" (batch rename) — tách riêng logic thuần
 * (không phụ thuộc Android UI) để dễ kiểm tra và tái dùng ở nhiều màn hình khác nhau.
 *
 * 3 chế độ, giống các file manager phổ biến (Solid Explorer, Files by Google...):
 *  - NUMBERING: đổi tên theo mẫu "Tên gốc 1", "Tên gốc 2"... giữ nguyên phần mở rộng gốc.
 *  - FIND_REPLACE: tìm 1 chuỗi con trong tên (không tính phần mở rộng) và thay bằng chuỗi khác.
 *  - PREFIX_SUFFIX: thêm tiền tố vào đầu và/hoặc hậu tố vào cuối tên (trước phần mở rộng).
 */
object BatchRenameUtils {

    enum class Mode { NUMBERING, FIND_REPLACE, PREFIX_SUFFIX }

    data class RenamePlanItem(
        val originalPath: String,
        val originalName: String,
        val newName: String
    ) {
        /** true nếu tên không đổi — dùng để bỏ qua khi áp dụng, tránh gọi rename() vô ích. */
        val isUnchanged: Boolean get() = originalName == newName
    }

    /**
     * Tính trước toàn bộ danh sách tên mới cho [files] theo [mode] — dùng để hiển thị bảng xem
     * trước TRƯỚC KHI áp dụng thật, giúp người dùng phát hiện lỗi (trùng tên, tên rỗng...) sớm.
     * Thứ tự [files] truyền vào quyết định thứ tự đánh số ở chế độ NUMBERING — Activity gọi hàm
     * này cần tự sắp xếp theo đúng ý người dùng (vd. theo tên, theo ngày tạo) trước khi gọi.
     */
    fun buildPlan(
        files: List<File>,
        mode: Mode,
        baseName: String = "",
        startNumber: Int = 1,
        find: String = "",
        replace: String = "",
        prefix: String = "",
        suffix: String = ""
    ): List<RenamePlanItem> {
        return files.mapIndexed { index, file ->
            val original = file.name
            val newName = when (mode) {
                Mode.NUMBERING -> buildNumberedName(file, baseName, startNumber + index)
                Mode.FIND_REPLACE -> buildFindReplaceName(file, find, replace)
                Mode.PREFIX_SUFFIX -> buildPrefixSuffixName(file, prefix, suffix)
            }
            RenamePlanItem(file.absolutePath, original, newName)
        }
    }

    private fun buildNumberedName(file: File, baseName: String, number: Int): String {
        val ext = file.extension
        val base = baseName.ifBlank { file.nameWithoutExtension }
        return if (file.isDirectory) "$base $number" else if (ext.isEmpty()) "$base $number" else "$base $number.$ext"
    }

    private fun buildFindReplaceName(file: File, find: String, replace: String): String {
        if (find.isEmpty()) return file.name
        return if (file.isDirectory) {
            // Thư mục không có "phần mở rộng" theo nghĩa file — áp dụng lên toàn bộ tên.
            file.name.replace(find, replace)
        } else {
            val ext = file.extension
            val nameOnly = file.nameWithoutExtension.replace(find, replace)
            if (ext.isEmpty()) nameOnly else "$nameOnly.$ext"
        }
    }

    private fun buildPrefixSuffixName(file: File, prefix: String, suffix: String): String {
        return if (file.isDirectory) {
            "$prefix${file.name}$suffix"
        } else {
            val ext = file.extension
            val nameOnly = "$prefix${file.nameWithoutExtension}$suffix"
            if (ext.isEmpty()) nameOnly else "$nameOnly.$ext"
        }
    }

    data class ApplyResult(val successCount: Int, val failedItems: List<RenamePlanItem>)

    /**
     * Áp dụng thật sự đổi tên theo [plan] đã tính trước. Bỏ qua các mục [RenamePlanItem
     * .isUnchanged] (tên không đổi, vd. "Tìm" không khớp gì trong tên đó). Kiểm tra trùng tên
     * TRƯỚC khi rename từng file để tránh 1 file vô tình ghi đè lên file khác cùng thư mục —
     * an toàn hơn để File.renameTo() tự quyết (hành vi ghi đè âm thầm khác nhau tuỳ hệ điều hành).
     */
    fun apply(plan: List<RenamePlanItem>): ApplyResult {
        var success = 0
        val failed = mutableListOf<RenamePlanItem>()
        for (item in plan) {
            if (item.isUnchanged) continue
            val src = File(item.originalPath)
            val dest = File(src.parentFile, item.newName)
            if (!src.exists() || dest.exists()) {
                failed.add(item)
                continue
            }
            if (src.renameTo(dest)) success++ else failed.add(item)
        }
        return ApplyResult(success, failed)
    }

    /**
     * Phát hiện trùng tên NGAY TRONG chính danh sách kết quả dự kiến (vd. 2 file khác nhau
     * cùng bị đổi thành tên giống nhau do Tìm/Thay thế quá rộng) — hiển thị cảnh báo ở bảng
     * xem trước trước khi người dùng bấm Áp dụng, tránh phải chờ đến lúc apply() mới biết lỗi.
     */
    fun findDuplicateNewNames(plan: List<RenamePlanItem>): Set<String> {
        val counts = plan.filterNot { it.isUnchanged }.groupingBy { it.newName }.eachCount()
        return counts.filterValues { it > 1 }.keys
    }
}
