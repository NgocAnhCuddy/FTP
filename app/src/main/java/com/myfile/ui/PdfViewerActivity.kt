package com.myfile.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myfile.ui.databinding.ActivityPdfViewerBinding
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.LogBus
import com.myfile.ui.util.ZoomController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Xem file .pdf trực tiếp trong app, không cần app ngoài. Dùng android.graphics.pdf.PdfRenderer
 * có sẵn của Android (KHÔNG cần thư viện thứ 3) - render từng trang ra Bitmap, cuộn dọc liên tục.
 * Chỉ đọc (không chỉnh sửa/annotate/tìm kiếm text).
 */
class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val adapter = PageAdapter()
    private lateinit var zoomController: ZoomController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
            ActivityTransitions.backward(this)
        }

        binding.rvPages.layoutManager = LinearLayoutManager(this)
        binding.rvPages.adapter = adapter

        // Zoom 50%-300%: áp scale lên chính RecyclerView (chứa các trang PDF đã render) — pinch
        // trực tiếp trên trang PDF, hoặc dùng nút +/- trên thanh công cụ cho thao tác chính xác.
        zoomController = ZoomController(this, binding.rvPages) { scale ->
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
        loadPdf(file)
    }

    private fun resolveIncomingFile(): File? {
        val pathExtra = intent.getStringExtra(EXTRA_FILE_PATH)
        if (pathExtra != null) return File(pathExtra)
        val data: Uri = intent.data ?: return null
        return when (data.scheme) {
            "file" -> data.path?.let { File(it) }
            "content" -> {
                val name = queryDisplayName(data) ?: "shared_${System.currentTimeMillis()}.pdf"
                val target = File(cacheDir, name)
                try {
                    contentResolver.openInputStream(data)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target
                } catch (e: Exception) {
                    LogBus.error("Không thể đọc file PDF được chia sẻ: $name", source = "PDF", throwable = e)
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

    private fun loadPdf(file: File) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val pageCount = try {
                withContext(Dispatchers.IO) {
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    pfd = fd
                    val r = PdfRenderer(fd)
                    renderer = r
                    r.pageCount
                }
            } catch (e: Exception) {
                LogBus.error("Không mở được PDF: ${file.path}", source = "PDF", throwable = e)
                -1
            }
            binding.progressBar.visibility = View.GONE
            if (isFinishing || isDestroyed) return@launch
            if (pageCount <= 0) {
                showError()
                return@launch
            }
            adapter.pageCount = pageCount
            adapter.notifyDataSetChanged()
        }
    }

    private fun showError() {
        binding.layoutError.visibility = View.VISIBLE
        binding.rvPages.visibility = View.GONE
    }

    /** Render 1 trang PDF ra Bitmap theo độ rộng màn hình. PdfRenderer không thread-safe khi mở nhiều page cùng lúc -> synchronized. */
    private suspend fun renderPage(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val r = renderer ?: return@withContext null
        synchronized(r) {
            var page: PdfRenderer.Page? = null
            try {
                page = r.openPage(index)
                val scale = widthPx.toFloat() / page.width
                val bmp = Bitmap.createBitmap(widthPx, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } catch (e: Exception) {
                null
            } finally {
                page?.close()
            }
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        var pageCount = 0

        inner class VH(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                adjustViewBounds = true
                setPadding(0, 4, 0, 4)
            }
            return VH(iv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.imageView.setImageBitmap(null)
            val widthPx = binding.rvPages.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            lifecycleScope.launch {
                val bmp = renderPage(position, widthPx)
                if (holder.bindingAdapterPosition == position) {
                    holder.imageView.setImageBitmap(bmp)
                }
            }
        }

        override fun getItemCount() = pageCount
    }

    override fun onDestroy() {
        renderer?.close()
        pfd?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
    }
}
