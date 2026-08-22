package com.myfile.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.size.Scale
import com.myfile.ui.R
import com.myfile.ui.databinding.ItemRemoteFileBinding
import com.myfile.ui.model.LocalFile
import java.io.File
import java.text.DecimalFormat
import java.util.Locale

/**
 * Hiển thị danh sách file cục bộ dạng LIST (dòng ngang) — dùng cho Tài liệu/Download/APK/Audio,
 * File gần đây, và cho các thư mục con khi duyệt Bộ nhớ trong. Hỗ trợ cả thư mục (isDirectory)
 * lẫn file thường, và chọn nhiều mục bằng nhấn giữ (long-press) giống chế độ lưới Ảnh/Video.
 *
 * Dùng ListAdapter + DiffUtil: submit() chỉ rebind các dòng thực sự đổi thay vì vẽ lại
 * toàn bộ danh sách, giúp mượt hơn trên chip tầm trung/thấp khi thư mục nhiều file.
 */
class LocalFileAdapter(
    private val iconRes: Int,
    private val onItemClick: (LocalFile) -> Unit,
    private val onMoreClick: (LocalFile, android.view.View) -> Unit,
    private val onSelectionChanged: (() -> Unit)? = null
) : ListAdapter<LocalFile, LocalFileAdapter.VH>(DIFF) {

    private val selectedPaths = mutableSetOf<String>()
    var selectionMode: Boolean = false
        private set

    init {
        setHasStableIds(true)
    }

    fun submit(newItems: List<LocalFile>) {
        submitList(newItems.toList())
    }

    /**
     * QUAN TRỌNG: đây là ListAdapter dùng DiffUtil. selectionMode/selectedPaths là STATE RIÊNG
     * của adapter, không nằm trong LocalFile (data class dùng để so sánh trong DIFF) — nên
     * KHÔNG THỂ dùng submitList(currentList.toList()) để "refresh" khi chỉ có selection đổi:
     * DiffUtil sẽ thấy nội dung từng item giống hệt (areContentsTheSame so trên LocalFile) và
     * lặng lẽ BỎ QUA việc rebind, khiến checkbox chọn không cập nhật trên màn hình dù dữ liệu
     * selectedPaths bên trong đã đổi đúng — bug im lặng, dễ bị hiểu nhầm là "thỉnh thoảng bấm
     * chọn không ăn phải bấm lại".
     *
     * Ngược lại, gọi notifyItemRangeChanged() thủ công NGAY LẬP TỨC (trong lúc onBindViewHolder
     * đang chạy dở, ví dụ từ long-press) có thể đụng độ với 1 diff callback của DiffUtil đang
     * chờ chạy trên main thread, gây "IndexOutOfBoundsException: Inconsistency detected".
     *
     * Cách an toàn theo đúng khuyến nghị của Google cho ListAdapter: post() việc notify vào cuối
     * hàng đợi message của main thread, đảm bảo nó luôn chạy SAU khi layout pass/diff callback
     * hiện tại (nếu có) đã hoàn tất hẳn — không bao giờ chen ngang.
     */
    private var pendingSelectionRefresh = false
    private fun refreshAllRows() {
        if (pendingSelectionRefresh) return
        pendingSelectionRefresh = true
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            pendingSelectionRefresh = false
            notifyItemRangeChanged(0, itemCount)
        }
    }

    fun toggleSelection(file: LocalFile) {
        if (selectedPaths.contains(file.path)) selectedPaths.remove(file.path) else selectedPaths.add(file.path)
        // Không tự tắt selectionMode khi về 0 mục — giống Samsung My Files: người dùng
        // phải bấm "Thoát" chủ động. Tự tắt ngay khi bỏ chọn mục cuối khiến tap kế tiếp
        // vào đúng dòng đó bị hiểu nhầm là tap thường (mở file) thay vì vẫn đang ở chế độ chọn.
        refreshAllRows()
        onSelectionChanged?.invoke()
    }

    fun clearSelection() {
        selectedPaths.clear()
        selectionMode = false
        refreshAllRows()
        onSelectionChanged?.invoke()
    }

    /** Thoát hẳn chế độ chọn (nút "Thoát"), khác với việc bỏ chọn từng mục — luôn tắt mode. */
    fun exitSelectionMode() = clearSelection()

    /** Bật chế độ chọn từ menu "Chọn" mà chưa chọn mục nào — hiện checkbox trống cho mọi dòng. */
    fun enterSelectionModeEmpty() {
        if (selectionMode) return
        selectionMode = true
        refreshAllRows()
        onSelectionChanged?.invoke()
    }

    fun selectAll() {
        selectedPaths.clear()
        selectedPaths.addAll(currentList.map { it.path })
        selectionMode = selectedPaths.isNotEmpty()
        refreshAllRows()
        onSelectionChanged?.invoke()
    }

    fun getSelectedItems(): List<LocalFile> = currentList.filter { selectedPaths.contains(it.path) }

    fun selectedCount(): Int = selectedPaths.size

    inner class VH(val binding: ItemRemoteFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemId(position: Int): Long = getItem(position).path.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRemoteFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = getItem(position)
        holder.binding.tvFileName.text = file.name
        if (!file.isDirectory && isImageOrVideo(file)) {
            // Ảnh/Video ở chế độ danh sách: load thumbnail thật qua Coil thay vì icon tĩnh
            // (icon tĩnh khiến mọi ảnh/video trong danh sách trông giống hệt nhau, không phân
            // biệt được nội dung — đây là bug đã gặp khi chuyển từ lưới sang danh sách).
            if (file.path.startsWith("content://")) {
                holder.binding.ivIcon.load(android.net.Uri.parse(file.path)) {
                    scale(Scale.FILL)
                    placeholder(iconRes)
                    error(iconRes)
                }
            } else {
                holder.binding.ivIcon.load(File(file.path)) {
                    scale(Scale.FILL)
                    placeholder(iconRes)
                    error(iconRes)
                }
            }
        } else {
            // dispose() hủy ĐỒNG BỘ request Coil cũ (nếu view này vừa được tái sử dụng từ 1 dòng
            // ảnh/video có thumbnail load bất đồng bộ) — trước đây gọi load(null) tưởng là "hủy"
            // nhưng thực chất tạo 1 request MỚI, request đó có thể hoàn tất SAU setImageResource()
            // ngay dưới và ghi đè icon vừa gán trở lại thành rỗng (đây là bug icon thư mục/file
            // lúc ẩn lúc hiện, KHÁC với bug màu icon đã sửa trước đó).
            holder.binding.ivIcon.dispose()
            holder.binding.ivIcon.setImageResource(
                if (file.isDirectory) com.myfile.ui.util.FolderIcons.iconFor(file.name) else iconRes
            )
        }
        holder.binding.tvFileMeta.text = if (file.isDirectory) {
            String.format(Locale.getDefault(), holder.binding.root.context.getString(R.string.items_count), file.itemCount)
        } else formatSize(file.size)

        val isSelected = selectedPaths.contains(file.path)
        // Checkbox chọn nhiều là 1 ImageView RIÊNG BIỆT đứng trước icon file trong layout (xem
        // item_remote_file.xml) — không còn đè/che lên icon như cách làm trước đây. Icon gốc
        // (hoặc thumbnail thật) luôn hiển thị nguyên vẹn 100% dù đang ở chế độ chọn hay không.
        if (selectionMode) {
            holder.binding.ivSelectedCheck.visibility = View.VISIBLE
            holder.binding.ivSelectedCheck.setImageResource(
                if (isSelected) R.drawable.ic_check_circle else R.drawable.ic_check_circle_outline
            )
        } else {
            holder.binding.ivSelectedCheck.visibility = View.GONE
        }
        holder.binding.ivIcon.visibility = View.VISIBLE
        holder.binding.btnMore.visibility = if (selectionMode) View.GONE else View.VISIBLE
        holder.binding.root.alpha = if (selectionMode && isSelected) 0.7f else 1f

        holder.binding.root.setOnClickListener {
            if (selectionMode) toggleSelection(file) else onItemClick(file)
        }
        holder.binding.root.setOnLongClickListener {
            // Không gọi notifyItemRangeChanged() trực tiếp ở đây (đang ở giữa onBindViewHolder,
            // tức RecyclerView có thể đang trong layout pass) — bật cờ rồi để toggleSelection()
            // bên dưới lo việc refresh qua refreshAllRows() (post() an toàn, xem cài đặt ở trên).
            if (!selectionMode) {
                selectionMode = true
            }
            toggleSelection(file)
            true
        }
        holder.binding.btnMore.setOnClickListener { onMoreClick(file, it) }
    }

    private fun isImageOrVideo(file: LocalFile): Boolean {
        val mime = file.mimeType
        if (mime != null) return mime.startsWith("image/") || mime.startsWith("video/")
        val ext = file.name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_VIDEO_EXTENSIONS
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val safeGroup = digitGroups.coerceIn(0, units.size - 1)
        return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, safeGroup.toDouble())) + " " + units[safeGroup]
    }

    companion object {
        private val IMAGE_VIDEO_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif",
            "mp4", "mkv", "webm", "3gp", "mov", "avi"
        )
        private val DIFF = object : DiffUtil.ItemCallback<LocalFile>() {
            override fun areItemsTheSame(oldItem: LocalFile, newItem: LocalFile) = oldItem.path == newItem.path
            override fun areContentsTheSame(oldItem: LocalFile, newItem: LocalFile) = oldItem == newItem
        }
    }
}
