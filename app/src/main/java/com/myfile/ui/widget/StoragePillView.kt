package com.myfile.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.myfile.ui.R

/**
 * Pill nhỏ hiển thị "đã dùng / tổng" kiểu Samsung My Files: BẢN THÂN pill được tô 2 màu theo
 * đúng tỉ lệ % đã dùng — phần bên trái (tương ứng usedBytes) tô xanh, phần còn lại (dung lượng
 * trống) tô xám nhạt, ranh giới 2 màu nằm ngay tại điểm chia tỉ lệ % thật, 2 đầu pill luôn bo
 * tròn đều bất kể tỉ lệ là bao nhiêu.
 *
 * View này TỰ VẼ CHỮ luôn bên trong (không tách riêng TextView chồng lớp qua FrameLayout như
 * lần trước) — lần trước dùng "FrameLayout wrap_content chứa StoragePillView match_parent +
 * TextView wrap_content" bị lỗi đo layout vòng lặp: cha wrap_content cần biết kích thước con
 * trước khi tự đo xong, còn con match_parent lại cần biết kích thước cha trước — 2 chiều phụ
 * thuộc lẫn nhau khiến MeasureSpec truyền xuống StoragePillView có lúc là UNSPECIFIED, và View
 * gốc không override onMeasure() sẽ fallback theo kích thước rất lớn/không xác định, gây pill
 * tràn full-width che kín cả màn hình đúng như ảnh lỗi thực tế. Gộp chữ vào onDraw() của chính
 * view này loại bỏ hoàn toàn vòng lặp đo lường đó: onMeasure() tự đo theo kích thước chữ (Paint
 * measureText) + padding cố định, chắc chắn ra wrap_content đúng nghĩa, không phụ thuộc view nào
 * khác.
 */
class StoragePillView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val cornerRadius = 20f * density
    private val paddingH = 12f * density
    private val paddingV = 5f * density

    private val usedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.storage_pill_internal)
    }
    private val freePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.storage_bar_track)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.storage_pill_text)
        textSize = 12f * density
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }

    private var usedRatio: Float = 0f
    private var label: String = ""

    /**
     * @param usedBytes dung lượng đã dùng
     * @param totalBytes tổng dung lượng (0 hoặc âm -> coi như 0%, tránh chia cho 0)
     * @param label chuỗi hiển thị, VD "57,6 GB / 106 GB" — view tự đo kích thước theo chuỗi này
     */
    fun setUsage(usedBytes: Long, totalBytes: Long, label: String) {
        usedRatio = if (totalBytes > 0) (usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
        this.label = label
        requestLayout() // độ dài chuỗi có thể đổi -> cần đo lại kích thước, không chỉ vẽ lại
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textWidth = textPaint.measureText(label)
        val fm = textPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent

        val desiredWidth = (textWidth + paddingH * 2).toInt()
        val desiredHeight = (textHeight + paddingV * 2).toInt()

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Vẽ toàn bộ nền pill bo tròn 2 đầu bằng màu "trống" trước, rồi phủ phần "đã dùng" lên
        // trên theo đúng tỉ lệ — dùng chung 1 clip path bo tròn cho cả 2 lớp để 2 màu luôn khớp
        // khít viền pill, không bị vuông góc lộ ra ở 2 đầu dù usedRatio gần 0% hoặc gần 100%.
        val path = Path().apply {
            addRoundRect(RectF(0f, 0f, w, h), cornerRadius, cornerRadius, Path.Direction.CW)
        }
        val save = canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(0f, 0f, w, h, freePaint)
        if (usedRatio > 0f) {
            canvas.drawRect(0f, 0f, w * usedRatio, h, usedPaint)
        }
        canvas.restoreToCount(save)

        val fm = textPaint.fontMetrics
        val textY = h / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, paddingH, textY, textPaint)
    }
}
