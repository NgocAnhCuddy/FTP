package com.myfile.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.myfile.ui.R
import com.myfile.ui.databinding.ItemGridFileBinding
import com.myfile.ui.model.LocalFile
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Hiển thị Ảnh/Video dạng LƯỚI Ô VUÔNG với thumbnail, giống chế độ xem lưới của
 * Samsung My Files (ảnh mẫu người dùng cung cấp): 3-4 cột, ảnh phủ kín ô, video có
 * badge play + thời lượng ở góc. Hỗ trợ chọn nhiều mục (long-press) để xóa/chia sẻ hàng loạt.
 *
 * ListAdapter + DiffUtil: tránh gọi lại Coil.load() cho những ô ảnh không đổi khi submit()
 * lại danh sách (ví dụ chỉ thêm vài ảnh mới) — đỡ giật khi cuộn thư viện ảnh lớn trên máy yếu.
 */
class LocalGridAdapter(
    private val isVideo: Boolean,
    private val onItemClick: (LocalFile) -> Unit,
    private val onItemLongClick: (LocalFile) -> Unit
) : ListAdapter<LocalFile, LocalGridAdapter.VH>(DIFF) {

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
     * Xem giải thích đầy đủ trong LocalFileAdapter: selectionMode/selectedPaths là state riêng
     * của adapter, không nằm trong LocalFile nên submitList() lại y hệt danh sách sẽ bị DiffUtil
     * coi là "không đổi gì" và bỏ qua rebind (checkbox không cập nhật). Notify thủ công thì phải
     * post() để không đụng độ với diff callback đang chờ chạy — không bao giờ gọi notify ngay
     * lập tức trong lúc bind/long-press.
     */
    private var pendingSelectionRefresh = false
    private fun refreshAllItems() {
        if (pendingSelectionRefresh) return
        pendingSelectionRefresh = true
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            pendingSelectionRefresh = false
            notifyItemRangeChanged(0, itemCount)
        }
    }

    fun toggleSelection(file: LocalFile) {
        if (selectedPaths.contains(file.path)) selectedPaths.remove(file.path) else selectedPaths.add(file.path)
        // Không tự tắt selectionMode khi về 0 mục — xem giải thích trong LocalFileAdapter.
        refreshAllItems()
    }

    fun clearSelection() {
        selectedPaths.clear()
        selectionMode = false
        refreshAllItems()
    }

    /** Thoát hẳn chế độ chọn (nút "Thoát"), khác với bỏ chọn từng mục — luôn tắt mode. */
    fun exitSelectionMode() = clearSelection()

    /** Bật chế độ chọn từ menu "Chọn" mà chưa chọn mục nào — hiện checkbox trống cho mọi ô. */
    fun enterSelectionModeEmpty() {
        if (selectionMode) return
        selectionMode = true
        refreshAllItems()
    }

    fun getSelectedItems(): List<LocalFile> = currentList.filter { selectedPaths.contains(it.path) }

    fun selectedCount(): Int = selectedPaths.size

    inner class VH(val binding: ItemGridFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemId(position: Int): Long = getItem(position).path.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemGridFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // Ô vuông thật sự cần tính bằng code: app:layout_constraintDimensionRatio trong XML của
        // item chỉ có tác dụng khi root là con của 1 ConstraintLayout CHA — ở đây root lại là con
        // trực tiếp của RecyclerView (GridLayoutManager), dùng RecyclerView.LayoutParams, không hiểu
        // thuộc tính constraint nên bị bỏ qua lặng lẽ và layout_height="0dp" thành 0px thật (đây
        // chính là màn hình đen toàn bộ dù dữ liệu đã tải xong). Tính cạnh ô = bề rộng màn hình / số
        // cột rồi gán thẳng vào layoutParams thay vì trông chờ ratio trong XML.
        val spanCount = ((parent as? androidx.recyclerview.widget.RecyclerView)?.layoutManager as? androidx.recyclerview.widget.GridLayoutManager)?.spanCount ?: 4
        val itemSize = parent.resources.displayMetrics.widthPixels / spanCount
        binding.root.layoutParams = binding.root.layoutParams.apply { height = itemSize }
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = getItem(position)
        val uriOrPath = if (file.path.startsWith("content://")) file.path else File(file.path)

        val itemSize = holder.binding.root.layoutParams.height.takeIf { it > 0 }
        holder.binding.ivThumb.load(uriOrPath) {
            crossfade(true)
            scale(Scale.FILL)
            // Giới hạn kích thước giải mã đúng bằng ô lưới thay vì ảnh gốc (ảnh camera thường
            // 12-50MP) — giảm mạnh bộ nhớ + thời gian giải mã, cuộn lưới mượt hơn hẳn trên máy yếu.
            if (itemSize != null) size(itemSize, itemSize)
            placeholder(R.drawable.bg_category_card)
            error(if (isVideo) R.drawable.ic_cat_video else R.drawable.ic_cat_photo)
        }

        if (isVideo) {
            holder.binding.ivPlayBadge.visibility = View.VISIBLE
            holder.binding.tvDuration.visibility = View.VISIBLE
            holder.binding.tvDuration.text = formatDuration(file.modifiedTime.let { 0L } /* placeholder nếu chưa có duration */)
            holder.binding.tvDuration.visibility = View.GONE // ẩn khi không có dữ liệu thời lượng thật
        } else {
            holder.binding.ivPlayBadge.visibility = View.GONE
            holder.binding.tvDuration.visibility = View.GONE
        }

        val isSelected = selectedPaths.contains(file.path)
        holder.binding.ivSelectedCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.binding.cardRoot.alpha = if (selectionMode && isSelected) 0.7f else 1f

        holder.binding.root.setOnClickListener {
            if (selectionMode) onItemLongClick(file) else onItemClick(file)
        }
        holder.binding.root.setOnLongClickListener {
            onItemLongClick(file)
            true
        }
    }

    private fun formatDuration(millis: Long): String {
        if (millis <= 0) return ""
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LocalFile>() {
            override fun areItemsTheSame(oldItem: LocalFile, newItem: LocalFile) = oldItem.path == newItem.path
            override fun areContentsTheSame(oldItem: LocalFile, newItem: LocalFile) = oldItem == newItem
        }
    }
}
