package com.myfile.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.myfile.ui.databinding.ItemConsoleLogBinding

/** Danh sách log JavaScript console.log/warn/error bắt được từ WebView, hiển thị dạng terminal. */
class ConsoleLogAdapter : RecyclerView.Adapter<ConsoleLogAdapter.VH>() {

    private val lines = mutableListOf<String>()

    fun add(line: String) {
        lines.add(line)
        notifyItemInserted(lines.size - 1)
    }

    fun clear() {
        val size = lines.size
        lines.clear()
        notifyItemRangeRemoved(0, size)
    }

    inner class VH(val binding: ItemConsoleLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemConsoleLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.binding.tvConsoleLine.text = lines[position]
    }

    override fun getItemCount(): Int = lines.size
}
