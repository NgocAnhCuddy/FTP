package com.myfile.ui.adapters

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.myfile.ui.databinding.ItemUnusedAppBinding
import java.text.DecimalFormat

/** 1 ung dung lau khong dung: ten, icon, so ngay khong mo, dung luong da cai dat. */
data class UnusedAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val daysUnused: Int,
    val sizeBytes: Long
)

class UnusedAppAdapter(
    private val onUninstall: (UnusedAppInfo) -> Unit
) : RecyclerView.Adapter<UnusedAppAdapter.VH>() {

    private val items = mutableListOf<UnusedAppInfo>()

    fun submit(newItems: List<UnusedAppInfo>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun removeByPackage(packageName: String) {
        val idx = items.indexOfFirst { it.packageName == packageName }
        if (idx >= 0) {
            items.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    inner class VH(val binding: ItemUnusedAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemUnusedAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        holder.binding.tvAppName.text = app.label
        holder.binding.ivAppIcon.setImageDrawable(app.icon)
        holder.binding.tvAppMeta.text =
            "Không dùng ${app.daysUnused} ngày • ${formatSize(app.sizeBytes)}"
        holder.binding.btnUninstall.setOnClickListener { onUninstall(app) }
    }

    override fun getItemCount(): Int = items.size

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val safeGroup = digitGroups.coerceIn(0, units.size - 1)
        return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, safeGroup.toDouble())) + " " + units[safeGroup]
    }
}
