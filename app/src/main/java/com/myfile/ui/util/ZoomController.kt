package com.myfile.ui.util

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Zoom 50%-300% dùng chung cho PdfViewerActivity/DocxViewerActivity/XlsxViewerActivity.
 *
 * Áp scaleX/scaleY lên [target] (không phải lên view scroll cha) — pivot luôn đặt ở góc trên-trái
 * (pivotX/Y = 0) để nội dung phóng to/thu nhỏ đúng như kỳ vọng.
 *
 * QUAN TRỌNG — pan (di chuyển) khi đã zoom: chỉ scaleX/scaleY KHÔNG đủ để xem được phần nội dung
 * bị "tràn" ra ngoài màn hình sau khi phóng to, vì scale chỉ là biến đổi HÌNH ẢNH lúc vẽ — kích
 * thước layout thật của [target] trong mắt view cha (RecyclerView/ScrollView) không hề đổi, nên
 * không có gì để cuộn tới phần bị che (đúng triệu chứng "zoom chỉ phóng quanh 1 điểm, không kéo
 * sang được" người dùng gặp phải). Sửa bằng cách tự quản lý translationX/Y ngay trong class này:
 * khi đang ở scale > 1, 1 ngón kéo sẽ dịch chuyển [target] trong giới hạn (không cho kéo lộ viền
 * trắng ra ngoài nội dung đã phóng to) — độc lập với việc RecyclerView cha vẫn tự cuộn dọc bình
 * thường theo cơ chế riêng của nó khi chưa zoom.
 */
class ZoomController(
    context: Context,
    private val target: View,
    private val onScaleChanged: ((Float) -> Unit)? = null
) {
    var scale: Float = 1f
        private set

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPanning = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyScale(scale * detector.scaleFactor)
                return true
            }
        }
    )

    init {
        target.pivotX = 0f
        target.pivotY = 0f
    }

    /** Gắn lắng nghe cử chỉ pinch + kéo (pan) trực tiếp lên [target]. */
    fun attachPinchToZoom() {
        target.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    isPanning = false
                }
                MotionEvent.ACTION_MOVE -> {
                    // Chỉ pan bằng 1 ngón và CHỈ khi đang zoom > 100% — ở scale 1x không có gì để
                    // pan cả (nội dung vừa khít màn hình), giữ nguyên hành vi cuộn dọc mặc định
                    // của RecyclerView cha cho trường hợp bình thường.
                    if (event.pointerCount == 1 && scale > 1f && !scaleDetector.isInProgress) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        // Ngưỡng nhỏ tránh coi 1 cú chạm nhẹ (tap) thành pan.
                        if (isPanning || Math.abs(dx) > PAN_SLOP || Math.abs(dy) > PAN_SLOP) {
                            isPanning = true
                            applyTranslation(target.translationX + dx, target.translationY + dy)
                            lastTouchX = event.x
                            lastTouchY = event.y
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPanning = false
                }
            }

            // Giữ sự kiện (chặn RecyclerView cuộn dọc) khi: đang pinch 2 ngón, HOẶC đang thực sự
            // pan ngang/dọc bằng 1 ngón lúc đã zoom — ngoài 2 trường hợp đó trả về false để
            // RecyclerView tiếp tục cuộn dọc bình thường như chưa hề có zoom.
            event.pointerCount >= 2 || scaleDetector.isInProgress || isPanning
        }
    }

    fun zoomIn() = applyScale(scale + STEP)
    fun zoomOut() = applyScale(scale - STEP)

    private fun applyScale(newScale: Float) {
        val clamped = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (clamped == scale) return
        scale = clamped
        target.scaleX = clamped
        target.scaleY = clamped
        // Về lại scale 1x thì bỏ luôn mọi lệch pan trước đó — tránh nội dung bị "kẹt" lệch vị
        // trí khi người dùng zoom out về 100% sau khi đã pan.
        if (clamped <= 1f) {
            target.translationX = 0f
            target.translationY = 0f
        } else {
            // Giới hạn lại translation hiện tại cho khớp biên mới của scale vừa đổi, tránh hở
            // viền trắng khi zoom out từ mức pan sâu xuống mức pan nông hơn.
            applyTranslation(target.translationX, target.translationY)
        }
        onScaleChanged?.invoke(clamped)
    }

    /** Giới hạn translation trong khoảng không cho kéo lộ viền ngoài nội dung đã phóng to ra. */
    private fun applyTranslation(rawX: Float, rawY: Float) {
        val parent = target.parent as? View
        val viewportW = (parent?.width ?: target.width).toFloat()
        val viewportH = (parent?.height ?: target.height).toFloat()
        val scaledW = target.width * scale
        val scaledH = target.height * scale

        // Biên translation hợp lệ: nội dung đã phóng to luôn phải phủ kín viewport, không hở biên
        // trắng ở bất kỳ cạnh nào. minX/minY âm (kéo sang trái/lên trên để lộ phần bị tràn bên
        // phải/dưới), maxX/maxY = 0 (không cho kéo dư sang phải/xuống làm hở biên trái/trên).
        val minX = (viewportW - scaledW).coerceAtMost(0f)
        val minY = (viewportH - scaledH).coerceAtMost(0f)

        target.translationX = rawX.coerceIn(minX, 0f)
        target.translationY = rawY.coerceIn(minY, 0f)
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 3.0f
        private const val STEP = 0.25f
        private const val PAN_SLOP = 8f
    }
}
