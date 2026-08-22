package com.myfile.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.myfile.ui.R
import com.myfile.ui.databinding.ItemLogBinding
import com.myfile.ui.model.LogEntry
import com.myfile.ui.model.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Danh sách log của Bảng điều khiển gỡ lỗi. Mỗi dòng lỗi (ERROR) có thể kèm [LogEntry.detail]
 * chứa TÊN FILE + SỐ DÒNG gây ra lỗi — hiển thị ngay dưới thông báo để debug nhanh, không cần
 * mở logcat riêng.
 *
 * ListAdapter + DiffUtil: log dồn dập lúc FTP server chạy, notifyDataSetChanged() cũ sẽ vẽ lại
 * toàn bộ danh sách mỗi lần có dòng mới — rất tốn trên chip yếu khi log đã dài.
 */
class LogAdapter : ListAdapter<LogEntry, LogAdapter.VH>(DIFF) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun submit(newItems: List<LogEntry>) {
        submitList(newItems.asReversed()) // mới nhất lên đầu
    }

    /** Toàn bộ log hiện đang hiển thị, dùng để sao chép ra clipboard. */
    fun currentItemsAsText(): String {
        return currentList.joinToString("\n") { entry ->
            val time = timeFormat.format(Date(entry.timestamp))
            val base = "[$time][${entry.source}] ${entry.message}"
            if (!entry.detail.isNullOrBlank()) "$base\n    ${entry.detail}" else base
        }
    }

    inner class VH(val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        holder.binding.tvLogMessage.text = entry.message
        holder.binding.tvLogTime.text = timeFormat.format(Date(entry.timestamp))
        holder.binding.tvLogSource.text = entry.source

        val colorRes = when (entry.level) {
            LogLevel.INFO -> R.color.info
            LogLevel.SUCCESS -> R.color.success
            LogLevel.WARNING -> R.color.warning
            LogLevel.ERROR -> R.color.error
        }
        holder.binding.viewLevelDot.backgroundTintList =
            ContextCompat.getColorStateList(holder.itemView.context, colorRes)

        if (!entry.detail.isNullOrBlank()) {
            holder.binding.tvLogDetail.text = entry.detail
            holder.binding.tvLogDetail.visibility = View.VISIBLE
        } else {
            holder.binding.tvLogDetail.visibility = View.GONE
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LogEntry>() {
            override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry) =
                oldItem.timestamp == newItem.timestamp && oldItem.message == newItem.message
            override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry) = oldItem == newItem
        }
    }
}
