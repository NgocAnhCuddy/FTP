package com.myfile.ui

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.util.Xml
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.myfile.ui.databinding.ActivityDocxViewerBinding
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.LogBus
import com.myfile.ui.util.ZoomController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/**
 * Xem nhanh nội dung file .docx trực tiếp trong app - đọc thẳng word/document.xml bên trong
 * file .docx (bản chất là 1 file .zip chứa XML), lấy text kèm in đậm/in nghiêng. KHÔNG render
 * layout/ảnh/bảng/cột như Word thật - chỉ để đọc nội dung nhanh, không thay thế Word.
 */
class DocxViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocxViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocxViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
            ActivityTransitions.backward(this)
        }

        // Zoom 50%-300%: áp scale lên chính TextView nội dung — pinch bằng 2 ngón để zoom,
        // vẫn giữ được bôi đen/chọn text bằng 1 ngón (textIsSelectable) như trước vì
        // ZoomController chỉ chặn sự kiện chạm khi phát hiện thực sự có 2 điểm chạm trở lên.
        val zoomController = ZoomController(this, binding.tvContent) { scale ->
            binding.tvZoomLevel.text = "${(scale * 100).toInt()}%"
        }
        zoomController.attachPinchToZoom()
        binding.btnZoomIn.setOnClickListener { zoomController.zoomIn() }
        binding.btnZoomOut.setOnClickListener { zoomController.zoomOut() }

        val file = resolveIncomingFile()
        if (file == null || !file.exists()) {
            showError()
            return
        }
        binding.toolbar.title = file.name
        loadDocx(file)
    }

    private fun resolveIncomingFile(): File? {
        val pathExtra = intent.getStringExtra(EXTRA_FILE_PATH)
        if (pathExtra != null) return File(pathExtra)
        val data: Uri = intent.data ?: return null
        return when (data.scheme) {
            "file" -> data.path?.let { File(it) }
            "content" -> {
                val name = queryDisplayName(data) ?: "shared_${System.currentTimeMillis()}.docx"
                val target = File(cacheDir, name)
                try {
                    contentResolver.openInputStream(data)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target
                } catch (e: Exception) {
                    LogBus.error("Không thể đọc file DOCX được chia sẻ: $name", source = "DOCX", throwable = e)
                    null
                }
            }
            else -> null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    private fun loadDocx(file: File) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val content = try {
                withContext(Dispatchers.IO) { parseDocx(file) }
            } catch (e: Exception) {
                LogBus.error("Không đọc được nội dung DOCX: ${file.path}", source = "DOCX", throwable = e)
                null
            }
            binding.progressBar.visibility = View.GONE
            if (isFinishing || isDestroyed) return@launch
            if (content == null) {
                showError()
                return@launch
            }
            binding.tvContent.text = content
        }
    }

    /**
     * Parse thủ công word/document.xml (namespace w:) lấy text kèm bold/italic ở mức run
     * (<w:r>). <w:b/>, <w:i/> là thẻ tự đóng nằm trong <w:rPr> của run - reset cờ ở </w:r>
     * chứ KHÔNG reset ở </w:b> (thẻ tự đóng đóng ngay sau khi mở).
     */
    private fun parseDocx(file: File): CharSequence {
        val ssb = SpannableStringBuilder()
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml")
                ?: throw IllegalStateException("Không tìm thấy word/document.xml (file có thể không phải .docx hợp lệ)")
            zip.getInputStream(entry).use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, "UTF-8")
                var bold = false
                var italic = false
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (localName(parser.name)) {
                                "b" -> bold = true
                                "i" -> italic = true
                                "tab" -> ssb.append('\t')
                                "br" -> ssb.append('\n')
                                "t" -> {
                                    val text = parser.nextText()
                                    val start = ssb.length
                                    ssb.append(text)
                                    if (bold) ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    if (italic) ssb.setSpan(StyleSpan(Typeface.ITALIC), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    // nextText() đã đưa parser tới END_TAG "t" - đồng bộ lại event rồi continue,
                                    // tránh gọi parser.next() lần nữa bên dưới làm nhảy quá 1 sự kiện
                                    event = parser.eventType
                                    continue
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            when (localName(parser.name)) {
                                "r" -> { bold = false; italic = false }
                                "p" -> ssb.append("\n\n")
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        }
        return ssb
    }

    private fun localName(name: String) = name.substringAfterLast(':')

    private fun showError() {
        binding.layoutError.visibility = View.VISIBLE
        binding.scrollContent.visibility = View.GONE
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
    }
}
