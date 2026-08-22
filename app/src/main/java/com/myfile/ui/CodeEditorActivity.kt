package com.myfile.ui

import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.myfile.ui.databinding.ActivityCodeEditorBinding
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.LogBus
import kotlinx.coroutines.launch
import java.io.File

/**
 * Trình soạn thảo mã đơn giản, dùng chung cho mọi loại file text (.kt, .java, .js, .html,
 * .css, .json, .py, .xml, .txt, ...). Hỗ trợ:
 *  - Số dòng luôn đồng bộ với nội dung (cuộn chung 1 ScrollView).
 *  - Tìm kiếm theo TỪ (highlight tất cả kết quả, nhảy tiếp/trước) hoặc theo SỐ DÒNG
 *    (gõ số → nhảy thẳng tới dòng đó, dùng để debug khi biết lỗi ở dòng bao nhiêu).
 *  - Lưu file trực tiếp đè lên file gốc trên đĩa.
 *  - Nếu file đang mở là .html, có nút "Chạy" mở luôn bằng HtmlViewerActivity.
 */
class CodeEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodeEditorBinding
    private lateinit var file: File

    private var originalContent: String = ""
    private var isDirty = false

    // Kết quả tìm kiếm hiện tại: danh sách vị trí bắt đầu của mỗi lần khớp trong nội dung.
    private var findMatches: List<Int> = emptyList()
    private var currentMatchIndex = -1
    private var findBarVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodeEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_FILE_PATH)
        if (path.isNullOrBlank()) {
            finish()
            return
        }
        file = File(path)
        if (!file.exists()) {
            android.widget.Toast.makeText(this, getString(R.string.error_generic), android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.toolbar.title = file.name
        binding.toolbar.setNavigationOnClickListener { handleBackNavigation() }
        binding.toolbar.setOnMenuItemClickListener { onMenuItemSelected(it) }
        binding.toolbar.menu.findItem(R.id.action_run_html)?.isVisible =
            file.extension.lowercase() in setOf("html", "htm")

        onBackPressedDispatcher.addCallback(this) { handleBackNavigation() }

        loadFile()
        setupLineNumberSync()
        setupCursorPositionTracking()
        setupFindBar()
        setupEditorMinWidth()
    }

    /**
     * XML không cho phép minWidth="match_parent" (chỉ nhận số đo cụ thể), nên set bằng code:
     * chờ HorizontalScrollView cha layout xong để lấy width thật, rồi áp minWidth đó cho ô nhập
     * code — giữ đúng hiệu ứng ban đầu (khung nhập rộng tối thiểu bằng khung nhìn khi nội dung
     * ngắn, vẫn co giãn/cuộn ngang được khi 1 dòng dài hơn màn hình).
     */
    private fun setupEditorMinWidth() {
        binding.hsvCode.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val width = binding.hsvCode.width
                if (width > 0) {
                    binding.etCode.minWidth = width
                    binding.hsvCode.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })
    }

    /**
     * file.readText() đồng bộ trên main thread trước đây có thể treo UI/ANR khi mở file lớn
     * (log, JSON export dữ liệu...) — cùng họ lỗi với các chỗ đọc/ghi file khác trong app đã sửa.
     * Hiện overlay loading trong lúc đọc để người dùng biết máy đang xử lý, không phải bị đơ.
     */
    private fun loadFile() {
        binding.etCode.isEnabled = false
        lifecycleScope.launch {
            originalContent = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { file.readText() }
            } catch (e: Exception) {
                LogBus.error("Không thể đọc file để mở trình soạn thảo: ${file.path}", source = "APP", throwable = e)
                ""
            }
            if (isFinishing || isDestroyed) return@launch
            binding.etCode.isEnabled = true
            binding.etCode.setText(originalContent)
            updateLineNumbers(originalContent)
            binding.tvFileStatus.text = file.name
        }
    }

    /** Cập nhật cột số dòng mỗi khi nội dung đổi, và theo dõi thay đổi để cảnh báo khi thoát chưa lưu. */
    private fun setupLineNumberSync() {
        binding.etCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                updateLineNumbers(text)
                isDirty = text != originalContent
                updateSaveIndicator()
                // Nếu đang có kết quả tìm kiếm mà nội dung đổi, làm mới để tránh highlight lệch vị trí
                if (findMatches.isNotEmpty()) {
                    performFind(binding.etFind.text?.toString().orEmpty(), keepIndex = false)
                }
            }
        })
    }

    private fun updateLineNumbers(text: String) {
        val lineCount = text.count { it == '\n' } + 1
        val sb = StringBuilder()
        for (i in 1..lineCount) {
            sb.append(i)
            if (i != lineCount) sb.append('\n')
        }
        binding.tvLineNumbers.text = sb.toString()
    }

    private fun updateSaveIndicator() {
        binding.toolbar.title = if (isDirty) "${file.name} •" else file.name
    }

    /** Hiện "Dòng X, Cột Y" ở thanh trạng thái dưới cùng, cập nhật theo vị trí con trỏ hiện tại. */
    private fun setupCursorPositionTracking() {
        binding.etCode.setOnClickListener { updateCursorPosition() }
        binding.etCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateCursorPosition()
        })
        updateCursorPosition()
    }

    private fun updateCursorPosition() {
        val text = binding.etCode.text?.toString().orEmpty()
        val cursor = binding.etCode.selectionStart.coerceIn(0, text.length)
        val upToCursor = text.substring(0, cursor)
        val line = upToCursor.count { it == '\n' } + 1
        val col = cursor - (upToCursor.lastIndexOf('\n') + 1) + 1
        binding.tvCursorPosition.text = getString(R.string.editor_line_col, line, col)
    }

    // ---------- Tìm kiếm theo từ hoặc theo số dòng ----------

    private fun setupFindBar() {
        binding.btnFindClose.setOnClickListener { toggleFindBar(false) }
        binding.btnFindNext.setOnClickListener { jumpToMatch(currentMatchIndex + 1) }
        binding.btnFindPrev.setOnClickListener { jumpToMatch(currentMatchIndex - 1) }
        binding.etFind.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event?.keyCode == KeyEvent.KEYCODE_ENTER)) {
                performFind(binding.etFind.text?.toString().orEmpty())
                true
            } else false
        }
        binding.etFind.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                performFind(s?.toString().orEmpty())
            }
        })
    }

    private fun toggleFindBar(show: Boolean) {
        findBarVisible = show
        binding.layoutFindBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.etFind.requestFocus()
        } else {
            clearHighlights()
            binding.etFind.setText("")
        }
    }

    /**
     * Tìm kiếm trong nội dung: nếu query là số nguyên thuần (VD "42"), nhảy thẳng tới DÒNG đó
     * — cách nhanh nhất để debug khi đã biết số dòng gây lỗi từ thông báo lỗi/log. Ngược lại,
     * tìm theo TỪ (không phân biệt hoa/thường) và highlight toàn bộ kết quả khớp được.
     */
    private fun performFind(query: String, keepIndex: Boolean = true) {
        val text = binding.etCode.text?.toString().orEmpty()
        if (query.isBlank()) {
            clearHighlights()
            binding.tvFindCount.text = ""
            findMatches = emptyList()
            return
        }

        val lineNumber = query.trim().toIntOrNull()
        if (lineNumber != null) {
            jumpToLine(lineNumber)
            binding.tvFindCount.text = ""
            findMatches = emptyList()
            return
        }

        val matches = mutableListOf<Int>()
        var idx = text.indexOf(query, 0, ignoreCase = true)
        while (idx >= 0) {
            matches.add(idx)
            idx = text.indexOf(query, idx + 1, ignoreCase = true)
        }
        findMatches = matches
        highlightMatches(query, matches)

        if (matches.isEmpty()) {
            binding.tvFindCount.text = getString(R.string.find_no_match)
            currentMatchIndex = -1
        } else {
            currentMatchIndex = if (keepIndex && currentMatchIndex in matches.indices) currentMatchIndex else 0
            jumpToMatch(currentMatchIndex)
        }
    }

    private fun highlightMatches(query: String, matches: List<Int>) {
        val editable = binding.etCode.text ?: return
        // Xóa span cũ trước khi vẽ lại, tránh chồng lấp khi gõ tiếp trong ô tìm kiếm
        editable.getSpans(0, editable.length, BackgroundColorSpan::class.java).forEach {
            editable.removeSpan(it)
        }
        matches.forEach { start ->
            editable.setSpan(
                BackgroundColorSpan(0x66FFD54F.toInt()),
                start,
                (start + query.length).coerceAtMost(editable.length),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun clearHighlights() {
        val editable = binding.etCode.text ?: return
        editable.getSpans(0, editable.length, BackgroundColorSpan::class.java).forEach {
            editable.removeSpan(it)
        }
    }

    private fun jumpToMatch(index: Int) {
        if (findMatches.isEmpty()) return
        val wrapped = ((index % findMatches.size) + findMatches.size) % findMatches.size
        currentMatchIndex = wrapped
        val pos = findMatches[wrapped]
        binding.tvFindCount.text = getString(R.string.find_match_count, wrapped + 1, findMatches.size)
        binding.etCode.requestFocus()
        binding.etCode.setSelection(pos)
        scrollToCursor(pos)
    }

    /** Nhảy tới đầu dòng thứ [lineNumber] (1-based) — dùng cho tìm kiếm theo số dòng. */
    private fun jumpToLine(lineNumber: Int) {
        val text = binding.etCode.text?.toString().orEmpty()
        val lines = text.split("\n")
        val target = lineNumber.coerceIn(1, lines.size)
        var offset = 0
        for (i in 0 until target - 1) {
            offset += lines[i].length + 1
        }
        binding.etCode.requestFocus()
        binding.etCode.setSelection(offset.coerceAtMost(text.length))
        scrollToCursor(offset)
    }

    /** Cuộn ScrollView để dòng chứa con trỏ luôn nằm trong vùng nhìn thấy. */
    private fun scrollToCursor(offset: Int) {
        binding.etCode.post {
            val layout = binding.etCode.layout ?: return@post
            val safeOffset = offset.coerceIn(0, binding.etCode.text?.length ?: 0)
            val line = layout.getLineForOffset(safeOffset)
            val y = layout.getLineTop(line)
            binding.scrollEditor.smoothScrollTo(0, (y - 100).coerceAtLeast(0))
        }
    }

    // ---------- Menu / lưu file / điều hướng ----------

    private fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_find -> toggleFindBar(!findBarVisible)
            R.id.action_save -> saveFile()
            R.id.action_run_html -> runAsHtml()
        }
        return true
    }

    /**
     * file.writeText() đồng bộ trên main thread trước đây có thể treo UI/ANR khi lưu file lớn.
     * saveFile() giờ nhận callback [onDone] để các nơi gọi cần thứ tự chắc chắn sau khi lưu
     * xong (vd runAsHtml() phải đợi lưu xong mới mở HtmlViewerActivity, tránh mở lên bản cũ).
     */
    private fun saveFile(onDone: (() -> Unit)? = null) {
        val content = binding.etCode.text?.toString().orEmpty()
        lifecycleScope.launch {
            val ok = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { file.writeText(content) }
                true
            } catch (e: Exception) {
                LogBus.error("Lưu file thất bại: ${file.path}", source = "APP", throwable = e)
                false
            }
            if (isFinishing || isDestroyed) return@launch
            if (ok) {
                originalContent = content
                isDirty = false
                updateSaveIndicator()
                android.widget.Toast.makeText(this@CodeEditorActivity, getString(R.string.file_saved), android.widget.Toast.LENGTH_SHORT).show()
                LogBus.success("Đã lưu file: ${file.name}", source = "APP")
            } else {
                android.widget.Toast.makeText(this@CodeEditorActivity, getString(R.string.file_save_failed), android.widget.Toast.LENGTH_SHORT).show()
            }
            onDone?.invoke()
        }
    }

    private fun runAsHtml() {
        // Lưu trước khi chạy để HtmlViewerActivity luôn hiển thị đúng bản mới nhất đang sửa —
        // saveFile() giờ chạy nền, PHẢI đợi lưu xong (onDone) mới mở màn xem, nếu không
        // HtmlViewerActivity có thể mở lên bản cũ (đọc file trước khi ghi kịp hoàn tất).
        saveFile {
            val intent = android.content.Intent(this, HtmlViewerActivity::class.java).apply {
                putExtra(HtmlViewerActivity.EXTRA_FILE_PATH, file.absolutePath)
            }
            startActivity(intent)
            ActivityTransitions.forward(this)
        }
    }

    private fun handleBackNavigation() {
        if (!isDirty) {
            finish()
            ActivityTransitions.backward(this)
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.unsaved_changes_title))
            .setMessage(getString(R.string.unsaved_changes_message))
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                // finish() phải đợi lưu xong (onDone) — saveFile() giờ chạy nền, gọi finish()
                // ngay lập tức như trước có thể hủy Activity trước khi kịp ghi xong file.
                saveFile {
                    finish()
                    ActivityTransitions.backward(this)
                }
            }
            .setNegativeButton(getString(R.string.btn_discard)) { _, _ ->
                finish()
                ActivityTransitions.backward(this)
            }
            .setNeutralButton(getString(R.string.cancel), null)
            .show()
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
    }
}
