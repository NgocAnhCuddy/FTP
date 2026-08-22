package com.myfile.ui

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.webkit.WebViewAssetLoader
import com.myfile.ui.adapters.ConsoleLogAdapter
import com.myfile.ui.databinding.ActivityHtmlViewerBinding
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.LogBus
import kotlinx.coroutines.launch
import java.io.File

/**
 * Chạy trực tiếp 1 file .html cục bộ trong app, giống mở bằng trình duyệt nhưng không cần
 * publish lên server nào — hữu ích để xem/preview trang HTML tải về qua FTP/SFTP/SMB hoặc
 * lưu trong máy. Có nút "Tải lại", bảng điều khiển JS console (log/warn/error kèm dòng gây lỗi),
 * và mọi lỗi tải trang được đẩy sang LogBus để xem trong Bảng điều khiển gỡ lỗi chung của app.
 */
class HtmlViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHtmlViewerBinding
    private lateinit var consoleAdapter: ConsoleLogAdapter
    private var currentFile: File? = null
    private var consoleVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHtmlViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
            ActivityTransitions.backward(this)
        }
        binding.toolbar.setOnMenuItemClickListener { onMenuItemSelected(it) }

        consoleAdapter = ConsoleLogAdapter()
        binding.rvConsole.layoutManager = LinearLayoutManager(this)
        binding.rvConsole.adapter = consoleAdapter

        binding.btnReload.setOnClickListener { loadCurrentFile() }

        setupWebView()
        resolveIncomingFile()
        loadCurrentFile()
    }

    /** Lấy file .html từ Intent: hỗ trợ cả mở qua "Mở bằng" hệ thống (content://) lẫn mở nội bộ (đường dẫn tuyệt đối). */
    private fun resolveIncomingFile() {
        val pathExtra = intent.getStringExtra(EXTRA_FILE_PATH)
        if (pathExtra != null) {
            currentFile = File(pathExtra)
            binding.toolbar.title = currentFile?.name
            return
        }
        val data: Uri = intent.data ?: return
        when (data.scheme) {
            "file" -> {
                currentFile = data.path?.let { File(it) }
                binding.toolbar.title = currentFile?.name
            }
            "content" -> {
                // "Mở bằng" từ trình quản lý file khác trả về content:// — không phải đường
                // dẫn thật, nên phải đọc qua ContentResolver rồi copy ra 1 file thật trước khi dùng.
                val name = queryDisplayName(data) ?: "shared_${System.currentTimeMillis()}.html"
                val target = File(cacheDir, name)
                try {
                    contentResolver.openInputStream(data)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    currentFile = target
                    binding.toolbar.title = name
                } catch (e: Exception) {
                    LogBus.error("Không thể đọc file HTML được chia sẻ: $name", source = "HTML", throwable = e)
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    // WebViewAssetLoader.InternalStoragePathHandler yêu cầu thư mục gốc phải là 1 THƯ MỤC CON
    // của cacheDir/filesDir (không được là chính filesDir) — nên dùng riêng 1 thư mục con cố định
    // để phục vụ file HTML, mọi file cần xem đều được đặt/copy vào đây trước khi nạp.
    private val previewDir by lazy { File(filesDir, "html_preview").apply { mkdirs() } }

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/local-html/", WebViewAssetLoader.InternalStoragePathHandler(this, previewDir))
            .build()
    }

    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Cho phép JS trong file HTML local tải ảnh/css/js kế bên bằng đường dẫn tương đối
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                binding.progressBar.visibility = View.VISIBLE
                binding.layoutError.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                binding.progressBar.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    binding.progressBar.visibility = View.GONE
                    binding.layoutError.visibility = View.VISIBLE
                    LogBus.error(
                        "Lỗi tải trang HTML: ${request.url}",
                        source = "HTML",
                        throwable = Exception("${error.errorCode}: ${error.description}")
                    )
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progressBar.progress = newProgress
            }

            /**
             * Bắt console.log/warn/error từ JS chạy trong trang, kèm TÊN FILE:SỐ DÒNG gây ra —
             * đây là "ô debug" chính cho người lập trình khi test file HTML local, tương đương
             * DevTools console nhưng gọn nhẹ ngay trong app.
             */
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val level = when (message.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> "error"
                    ConsoleMessage.MessageLevel.WARNING -> "warn"
                    else -> "log"
                }
                val fileName = message.sourceId()?.substringAfterLast('/') ?: "?"
                val line = "[$level] ${message.message()}  (${fileName}:${message.lineNumber()})"
                consoleAdapter.add(line)
                binding.rvConsole.scrollToPosition(consoleAdapter.itemCount - 1)
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    LogBus.error(
                        message.message(),
                        source = "HTML",
                        throwable = Exception("JS error tại $fileName:${message.lineNumber()}")
                    )
                }
                return true
            }
        }
    }

    private fun loadCurrentFile() {
        val file = currentFile
        if (file == null || !file.exists()) {
            binding.layoutError.visibility = View.VISIBLE
            return
        }
        binding.layoutError.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        // Nạp qua origin ảo https://appassets.androidplatform.net thay vì file:// trực tiếp:
        // tránh các hạn chế bảo mật WebView áp lên file:// (fetch/XHR tới file cùng thư mục
        // bị chặn), giúp các trang HTML có gọi JS/CSS/ảnh tương đối chạy đúng như trên server thật.
        //
        // copyIntoPreviewDir() sao chép MỌI file cùng thư mục cha (để phục vụ CSS/JS/ảnh mà
        // trang tham chiếu tương đối) — đây là thao tác I/O đồng bộ, trước đây chạy thẳng trên
        // main thread. Nếu file HTML nằm cùng thư mục với file lớn khác (video, ảnh RAW không
        // liên quan gì tới trang HTML), việc copy sẽ treo UI/ANR ngay khi mở màn hình. Chuyển
        // sang chạy nền, chỉ cập nhật UI khi vẫn còn ở màn hình này lúc copy xong.
        lifecycleScope.launch {
            val targetFile = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { copyIntoPreviewDir(file) }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.layoutError.visibility = View.VISIBLE
                LogBus.error("Không thể chuẩn bị file HTML để xem: ${file.path}", source = "HTML", throwable = e)
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            currentFile = targetFile
            binding.webView.loadUrl("https://appassets.androidplatform.net/local-html/${targetFile.name}")
        }
    }

    /**
     * Copy file HTML (và mọi file khác cùng thư mục cha — CSS/JS/ảnh mà trang có thể tham
     * chiếu bằng đường dẫn tương đối) vào [previewDir], nơi duy nhất WebViewAssetLoader
     * được phép phục vụ. Bỏ qua nếu file đã nằm sẵn trong previewDir (mở lại/tải lại).
     */
    private fun copyIntoPreviewDir(file: File): File {
        if (file.parentFile?.absolutePath == previewDir.absolutePath) return file
        file.parentFile?.listFiles()?.forEach { sibling ->
            if (sibling.isFile) {
                try {
                    sibling.copyTo(File(previewDir, sibling.name), overwrite = true)
                } catch (ignored: Exception) {
                    // Bỏ qua file lẻ copy lỗi (vd. quyền đọc) — không chặn việc mở file HTML chính
                }
            }
        }
        return File(previewDir, file.name)
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_reload -> loadCurrentFile()
            R.id.action_console -> toggleConsole()
            R.id.action_edit_source -> openInCodeEditor()
        }
        return true
    }

    private fun toggleConsole() {
        consoleVisible = !consoleVisible
        binding.rvConsole.visibility = if (consoleVisible) View.VISIBLE else View.GONE
    }

    private fun openInCodeEditor() {
        val file = currentFile ?: return
        val intent = android.content.Intent(this, CodeEditorActivity::class.java).apply {
            putExtra(CodeEditorActivity.EXTRA_FILE_PATH, file.absolutePath)
        }
        startActivity(intent)
        ActivityTransitions.forward(this)
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
    }
}
