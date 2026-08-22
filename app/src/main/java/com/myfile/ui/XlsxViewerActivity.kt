package com.myfile.ui

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Xml
import android.view.View
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.myfile.ui.databinding.ActivityXlsxViewerBinding
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
 * Xem nhanh nội dung file .xlsx trực tiếp trong app - đọc thẳng xl/sharedStrings.xml +
 * xl/worksheets/sheet1.xml (1 file .xlsx thực chất là .zip chứa XML).
 *
 * GIỚI HẠN đã biết (đơn giản hoá có chủ đích, không phải bug):
 * - Chỉ đọc SHEET ĐẦU TIÊN theo tên file quy ước "sheet1.xml" (không đọc xl/workbook.xml để
 *   map chính xác thứ tự sheet thật - đa số file xlsx tạo bình thường vẫn đúng quy ước này).
 * - Không đọc công thức (chỉ đọc giá trị đã tính sẵn trong <v>), không đọc định dạng số/màu/
 *   độ rộng cột, không đọc biểu đồ/ảnh.
 * Dùng để xem nhanh dữ liệu, không thay thế Excel thật.
 */
class XlsxViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityXlsxViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXlsxViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
            ActivityTransitions.backward(this)
        }

        // Zoom 50%-300%: áp scale lên TableLayout — table đang nằm trong cả ScrollView (dọc) lẫn
        // HorizontalScrollView (ngang) nên pinch phải chặn CẢ 2 chiều cuộn khi zoom, ZoomController
        // đã xử lý việc này (chỉ chặn sự kiện khi thực sự có 2 điểm chạm trở lên).
        val zoomController = ZoomController(this, binding.tableContent) { scale ->
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
        loadXlsx(file)
    }

    private fun resolveIncomingFile(): File? {
        val pathExtra = intent.getStringExtra(EXTRA_FILE_PATH)
        if (pathExtra != null) return File(pathExtra)
        val data: Uri = intent.data ?: return null
        return when (data.scheme) {
            "file" -> data.path?.let { File(it) }
            "content" -> {
                val name = queryDisplayName(data) ?: "shared_${System.currentTimeMillis()}.xlsx"
                val target = File(cacheDir, name)
                try {
                    contentResolver.openInputStream(data)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target
                } catch (e: Exception) {
                    LogBus.error("Không thể đọc file XLSX được chia sẻ: $name", source = "XLSX", throwable = e)
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

    private fun loadXlsx(file: File) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val rows = try {
                withContext(Dispatchers.IO) { parseXlsx(file) }
            } catch (e: Exception) {
                LogBus.error("Không đọc được nội dung XLSX: ${file.path}", source = "XLSX", throwable = e)
                null
            }
            binding.progressBar.visibility = View.GONE
            if (isFinishing || isDestroyed) return@launch
            if (rows == null) {
                showError()
                return@launch
            }
            renderTable(rows)
        }
    }

    private fun parseXlsx(file: File): List<List<String>> {
        val sharedStrings = mutableListOf<String>()
        val rows = mutableListOf<MutableList<String>>()
        ZipFile(file).use { zip ->
            zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
                zip.getInputStream(entry).use { input ->
                    val parser = Xml.newPullParser()
                    parser.setInput(input, "UTF-8")
                    var event = parser.eventType
                    var current: StringBuilder? = null
                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                val n = localName(parser.name)
                                if (n == "si") current = StringBuilder()
                                if (n == "t" && current != null) {
                                    current!!.append(parser.nextText())
                                    event = parser.eventType
                                    continue
                                }
                            }
                            XmlPullParser.END_TAG -> {
                                if (localName(parser.name) == "si") {
                                    sharedStrings.add(current?.toString().orEmpty())
                                    current = null
                                }
                            }
                        }
                        event = parser.next()
                    }
                }
            }

            val sheetEntry = zip.getEntry("xl/worksheets/sheet1.xml")
                ?: throw IllegalStateException("Không tìm thấy xl/worksheets/sheet1.xml")
            zip.getInputStream(sheetEntry).use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, "UTF-8")
                var event = parser.eventType
                var currentRow: MutableList<String>? = null
                var cellType: String? = null
                var cellCol = 0
                var cellValue = ""
                var inlineText: StringBuilder? = null
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (localName(parser.name)) {
                                "row" -> currentRow = mutableListOf()
                                "c" -> {
                                    cellType = parser.getAttributeValue(null, "t")
                                    val ref = parser.getAttributeValue(null, "r")
                                    cellCol = ref?.let { columnIndex(it) } ?: (currentRow?.size ?: 0)
                                    cellValue = ""
                                    inlineText = null
                                }
                                "v" -> {
                                    cellValue = parser.nextText()
                                    event = parser.eventType
                                    continue
                                }
                                "is" -> inlineText = StringBuilder()
                                "t" -> {
                                    if (inlineText != null) {
                                        inlineText!!.append(parser.nextText())
                                        event = parser.eventType
                                        continue
                                    }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            when (localName(parser.name)) {
                                "c" -> {
                                    val display = when (cellType) {
                                        "s" -> cellValue.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
                                        "inlineStr" -> inlineText?.toString().orEmpty()
                                        else -> cellValue
                                    }
                                    currentRow?.let { row ->
                                        while (row.size <= cellCol) row.add("")
                                        row[cellCol] = display
                                    }
                                }
                                "row" -> currentRow?.let { rows.add(it) }
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        }
        return rows
    }

    /** "B7" -> cột index 1 (0-based). Hỗ trợ cả cột nhiều ký tự (AA, AB...). */
    private fun columnIndex(cellRef: String): Int {
        var idx = 0
        for (ch in cellRef) {
            if (!ch.isLetter()) break
            idx = idx * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return (idx - 1).coerceAtLeast(0)
    }

    private fun localName(name: String) = name.substringAfterLast(':')

    private fun renderTable(rows: List<List<String>>) {
        val table = binding.tableContent
        table.removeAllViews()
        val maxCols = rows.maxOfOrNull { it.size } ?: 0
        val cellBg = ContextCompat.getColor(this, R.color.surface)
        val textColor = ContextCompat.getColor(this, R.color.text_primary)
        rows.forEachIndexed { rIdx, row ->
            val tableRow = TableRow(this)
            for (c in 0 until maxCols) {
                val tv = TextView(this).apply {
                    text = row.getOrNull(c).orEmpty()
                    setPadding(24, 16, 24, 16)
                    minWidth = 220
                    setTextColor(textColor)
                    setBackgroundColor(cellBg)
                    if (rIdx == 0) setTypeface(typeface, Typeface.BOLD)
                }
                val lp = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
                lp.setMargins(1, 1, 0, 0)
                tv.layoutParams = lp
                tableRow.addView(tv)
            }
            table.addView(tableRow)
        }
    }

    private fun showError() {
        binding.layoutError.visibility = View.VISIBLE
        binding.scrollVertical.visibility = View.GONE
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
    }
}
