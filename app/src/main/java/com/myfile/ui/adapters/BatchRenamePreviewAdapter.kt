package com.myfile.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.myfile.ui.R
import com.myfile.ui.databinding.ItemBatchRenamePreviewBinding
import com.myfile.ui.util.BatchRenameUtils

/**
 * Hiển thị danh sách "tên cũ → tên mới" trong dialog Đổi tên hàng loạt. Các mục có tên mới bị
 * trùng với 1 mục khác trong cùng danh sách (theo [duplicateNames]) được tô màu đỏ ("error")
 * để cảnh báo TRƯỚC khi người dùng bấm Áp dụng, thay vì để họ chờ tới lúc đổi tên thật mới biết
 * có lỗi.
 */
class BatchRenamePreviewAdapter(
    private var items: List<BatchRenameUtils.RenamePlanItem> = emptyList(),
    private var duplicateNames: Set<String> = emptySet()
) : RecyclerView.Adapter<BatchRenamePreviewAdapter.VH>() {

    inner class VH(val binding: ItemBatchRenamePreviewBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(newItems: List<BatchRenameUtils.RenamePlanItem>, newDuplicates: Set<String>) {
        items = newItems
        duplicateNames = newDuplicates
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBatchRenamePreviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.binding.tvOldName.text = item.originalName
        holder.binding.tvNewName.text = item.newName
        val isDuplicate = duplicateNames.contains(item.newName)
        val colorRes = if (isDuplicate) R.color.error else R.color.primary
        holder.binding.tvNewName.setTextColor(ctx.getColor(colorRes))
    }
}
