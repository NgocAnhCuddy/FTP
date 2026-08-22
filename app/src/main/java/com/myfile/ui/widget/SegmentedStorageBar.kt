package com.myfile.ui.widget

import com.myfile.ui.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Thanh dung lượng dạng nhiều đoạn màu liền nhau, giống thanh "Quản lý lưu trữ" của Samsung
 * (ảnh mẫu người dùng cung cấp): mỗi đoạn tỉ lệ với dung lượng 1 danh mục, các đoạn bo góc
 * 2 đầu thanh, khoảng cách nhỏ 1dp giữa các đoạn để phân biệt rõ ràng.
 */
class SegmentedStorageBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Segment(val value: Long, val color: Int)

    private var segments: List<Segment> = emptyList()
    private var totalCapacity: Long = 1L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Nền rãnh dùng màu theo theme (sáng/tối) thay vì xám đen cứng, để không bị lệch tông
        // với giao diện pastel sáng của app (trước đây dùng #3A3F47 cố định trông rất tương phản/xấu).
        color = com.myfile.ui.R.color.storage_bar_track.let {
            androidx.core.content.ContextCompat.getColor(context, it)
        }
    }
    // Bo góc và khoảng cách quy theo mật độ màn hình để hiển thị đồng nhất trên mọi máy,
    // thay vì dùng số pixel cố định (trước đây có thể quá to/nhỏ tùy mật độ màn hình).
    private val density = resources.displayMetrics.density
    private val cornerRadius = 9f * density
    private val gapPx = 2f * density

    fun setData(segments: List<Segment>, totalCapacity: Long) {
        this.segments = segments.filter { it.value > 0 }
        this.totalCapacity = totalCapacity.coerceAtLeast(1L)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Nền thanh (phần dung lượng trống)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), cornerRadius, cornerRadius, bgPaint)

        if (segments.isEmpty()) return

        // Tính vị trí biên (boundary) theo tỉ lệ % dựa trên tổng dồn tích, KHÔNG cộng dồn
        // sai số float qua từng vòng lặp như trước (nguyên nhân gây hở lệch giữa các đoạn
        // và đoạn cuối không chạm sát mép phải). Gap được "khoét" đối xứng vào giữa 2 đoạn
        // liền kề nên vị trí biên thật (theo %) luôn chính xác.
        val usableWidth = w
        val n = segments.size
        val halfGap = gapPx / 2f
        var cumulative = 0.0
        val boundaries = FloatArray(n + 1)
        boundaries[0] = 0f
        for (i in 0 until n) {
            cumulative += segments[i].value.toDouble()
            boundaries[i + 1] = (cumulative / totalCapacity.toDouble() * usableWidth).toFloat()
        }
        // Đảm bảo biên cuối luôn khớp chính xác mép phải, tránh hở do làm tròn số thực.
        boundaries[n] = w

        for (index in 0 until n) {
            val seg = segments[index]
            if (seg.value <= 0L) continue
            var left = boundaries[index]
            var right = boundaries[index + 1]
            if (right - left <= 0f) continue

            val isFirst = index == 0
            val isLast = index == n - 1

            // Chừa nửa gap ở mỗi cạnh tiếp giáp với đoạn khác (không chừa ở mép ngoài
            // cùng của thanh để đoạn đầu/cuối luôn khít với viền bo tròn của track).
            if (!isFirst) left += halfGap
            if (!isLast) right -= halfGap
            if (right <= left) continue

            paint.color = seg.color
            val rect = RectF(left, 0f, right, h)
            drawSegment(canvas, rect, isFirst, isLast)
        }
    }

    private fun drawSegment(canvas: Canvas, rect: RectF, roundLeft: Boolean, roundRight: Boolean) {
        val radii = floatArrayOf(
            if (roundLeft) cornerRadius else 0f, if (roundLeft) cornerRadius else 0f,
            if (roundRight) cornerRadius else 0f, if (roundRight) cornerRadius else 0f,
            if (roundRight) cornerRadius else 0f, if (roundRight) cornerRadius else 0f,
            if (roundLeft) cornerRadius else 0f, if (roundLeft) cornerRadius else 0f
        )
        val path = android.graphics.Path().apply { addRoundRect(rect, radii, android.graphics.Path.Direction.CW) }
        canvas.drawPath(path, paint)
    }
}
