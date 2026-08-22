package com.myfile.ui

import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.myfile.ui.adapters.LocalFileAdapter
import com.myfile.ui.databinding.ActivitySearchBinding
import com.myfile.ui.model.LocalFile
import com.myfile.ui.util.ActivityTransitions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tìm kiếm file theo tên trong toàn bộ Bộ nhớ trong, tương đương nút tìm kiếm (kính lúp)
 * ở màn hình Home của Samsung My Files. Gõ tới đâu tìm tới đó (debounce 350ms).
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: LocalFileAdapter
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = LocalFileAdapter(
            iconRes = R.drawable.ic_file,
            onItemClick = { openFile(it) },
            onMoreClick = { _, _ -> }
        )
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter

        binding.btnBack.setOnClickListener {
            finish()
            ActivityTransitions.backward(this)
        }
        binding.btnClear.setOnClickListener {
            binding.etSearch.text?.clear()
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                binding.btnClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                scheduleSearch(query)
            }
        })

        binding.etSearch.requestFocus()
    }

    private fun scheduleSearch(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            adapter.submit(emptyList())
            binding.tvEmpty.visibility = View.GONE
            binding.progress.visibility = View.GONE
            return
        }
        searchJob = lifecycleScope.launch {
            delay(350) // debounce để không tìm liên tục khi đang gõ
            binding.progress.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
            val results = withContext(Dispatchers.IO) { searchFiles(query.trim()) }
            binding.progress.visibility = View.GONE
            adapter.submit(results)
            binding.tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** Duyệt đệ quy toàn bộ nhớ trong, khớp tên file/thư mục không phân biệt hoa thường. Giới hạn 500 kết quả và độ sâu để tránh treo máy. */
    private fun searchFiles(query: String): List<LocalFile> {
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        val lowerQuery = query.lowercase()
        val result = mutableListOf<LocalFile>()

        fun scan(dir: File, depth: Int) {
            if (result.size >= MAX_RESULTS || depth > MAX_DEPTH) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (result.size >= MAX_RESULTS) return
                if (f.name.startsWith(".")) continue // bỏ qua thư mục ẩn/thùng rác nội bộ
                if (f.name.lowercase().contains(lowerQuery)) {
                    result.add(
                        LocalFile(
                            name = f.name,
                            path = f.absolutePath,
                            size = if (f.isFile) f.length() else 0L,
                            modifiedTime = f.lastModified(),
                            isDirectory = f.isDirectory,
                            itemCount = if (f.isDirectory) (f.listFiles()?.size ?: 0) else 0
                        )
                    )
                }
                if (f.isDirectory) scan(f, depth + 1)
            }
        }

        try {
            scan(root, 0)
        } catch (e: Exception) {
            // bỏ qua thư mục không đọc được (thiếu quyền)
        }
        return result.sortedByDescending { it.modifiedTime }
    }

    // Các phần mở rộng file text/mã nguồn có thể sửa trực tiếp bằng CodeEditorActivity trong
    // app — đồng bộ với CategoryFilesActivity/FileBrowserActivity để hành vi nhất quán dù mở
    // file từ đâu trong app.
    private val editableExtensions = setOf(
        "kt", "java", "js", "ts", "jsx", "tsx", "html", "htm", "css", "json", "xml",
        "py", "c", "cpp", "h", "cs", "php", "rb", "go", "rs", "sh", "sql", "yml", "yaml",
        "gradle", "properties", "md", "txt", "log", "ini", "env"
    )

    private fun openFile(file: LocalFile) {
        if (file.isDirectory) return // mở thư mục trong kết quả tìm kiếm ít có ích, bỏ qua
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext in editableExtensions && !file.path.startsWith("content://")) {
            val intent = android.content.Intent(this, CodeEditorActivity::class.java)
                .putExtra(CodeEditorActivity.EXTRA_FILE_PATH, file.path)
            startActivity(intent)
            ActivityTransitions.forward(this)
            return
        }
        com.myfile.ui.util.FileOpenUtils.openDefault(this, binding.root, file.path, file.name)
    }

    companion object {
        private const val MAX_RESULTS = 500
        private const val MAX_DEPTH = 8
    }
}
