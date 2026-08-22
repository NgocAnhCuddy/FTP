package com.myfile.ui.util

import java.security.MessageDigest

/**
 * Băm mã PIN của tính năng Khoá app — KHÔNG BAO GIỜ lưu hay so sánh PIN dạng plain text, kể cả
 * khi nơi lưu trữ (SecurePrefs) đã tự mã hoá bằng Android Keystore. Đây là lớp phòng thủ độc
 * lập thứ 2 (defense in depth): nếu Keystore của thiết bị bị xâm phạm theo cách nào đó (thiết
 * bị đã root, lỗ hổng hệ điều hành...), PIN gốc 4-6 số của người dùng vẫn không lộ ra trực tiếp
 * từ giá trị lưu trong prefs.
 *
 * Dùng SALT CỐ ĐỊNH ở mức app (không phải salt ngẫu nhiên per-install) vì mục tiêu ở đây chỉ là
 * chống đọc trực tiếp giá trị lưu trữ, KHÔNG phải chống tấn công brute-force offline trên dữ
 * liệu bị đánh cắp hàng loạt (khác với hệ thống xác thực server quy mô lớn) — với không gian
 * PIN nhỏ (4-6 chữ số), salt cố định vẫn đủ dùng cho mục tiêu thực tế của tính năng khoá màn
 * hình cục bộ trên 1 thiết bị.
 */
object AppLockUtils {
    // Salt cố định riêng cho tính năng này — không phải secret cần giữ kín tuyệt đối (nếu lộ ra
    // cũng không tự nó cho phép đăng nhập, kẻ tấn công vẫn cần truy cập được prefs đã giải mã).
    private const val SALT = "myfile_applock_v1_9f3c7a2e"

    fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((SALT + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(pin: String, storedHash: String): Boolean = hashPin(pin) == storedHash

    /** PIN hợp lệ: 4-6 chữ số, giống chuẩn khoá màn hình phổ biến trên Android/iOS. */
    fun isValidPinFormat(pin: String): Boolean = pin.length in 4..6 && pin.all { it.isDigit() }
}
