package com.myfile.ui

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.myfile.ui.databinding.ActivityStorageManagerBinding
import com.myfile.ui.databinding.ItemStorageActionBinding
import com.myfile.ui.databinding.ItemStorageCategoryBinding
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.TrashManager
import com.myfile.ui.widget.SegmentedStorageBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

/**
 * Man hinh "Quan ly luu tru" giong Samsung Storage Manager (anh mau nguoi dung cung cap):
 * thanh dung luong nhieu mau theo danh muc + danh sach chi tiet tung danh muc co cham mau
 * tuong ung, co the "Xem them"/"Hien it hon", va 4 muc thao tac nhanh ben duoi
 * (Thung rac, Ung dung khong dung, File trung lap, File lon). Mo tu menu 3 cham o man hinh Home.
 */
class StorageManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageManagerBinding
    private var isExpanded = false
    private var lastInfo: StorageInfo? = null

    /** So danh muc hien thi khi thu gon (giong anh: Anh, File nen, Cac file cai dat, Tai lieu). */
    private val collapsedCount = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
            ActivityTransitions.backward(this)
        }

        binding.tvToggleMore.setOnClickListener {
            isExpanded = !isExpanded
            lastInfo?.let { populateCategoryList(it) }
        }

        loadStorageInfo()
        populateActionList()
    }

    private fun loadStorageInfo() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { computeStorageInfo() }
            lastInfo = info

            val usedPercent = if (info.totalCapacity > 0) ((info.totalUsed * 100) / info.totalCapacity).toInt() else 0
            binding.tvPercentUsed.text = getString(R.string.storage_used_percent, usedPercent)
            binding.tvUsedTotal.text = getString(
                R.string.storage_quota_format,
                formatSize(info.totalUsed),
                formatSize(info.totalCapacity)
            )

            val segments = listOf(
                SegmentedStorageBar.Segment(info.photoSize, ContextCompat.getColor(this@StorageManagerActivity, R.color.category_photo)),
                SegmentedStorageBar.Segment(info.archiveSize, ContextCompat.getColor(this@StorageManagerActivity, R.color.category_archive)),
                SegmentedStorageBar.Segment(info.apkSize, ContextCompat.getColor(this@StorageManagerActivity, R.color.category_apk)),
                SegmentedStorageBar.Segment(info.docSize, ContextCompat.getColor(this@StorageManagerActivity, R.color.category_doc)),
                SegmentedStorageBar.Segment(info.videoSize, ContextCompat.getColor(this@StorageManagerActivity, R.color.category_video)),
                SegmentedStorageBar.Segment(info.audioSize, ContextCompat.getColor(this@StorageManagerActivity, R.color.category_audio)),
                SegmentedStorageBar.Segment(info.appSize, ContextCompat.getColor(this@StorageManagerActivity, R.color.category_apps)),
                SegmentedStorageBar.Segment(info.systemSize, ContextCompat.getColor(this@StorageManagerActivity, R.color.category_system)),
                SegmentedStorageBar.Segment(info.otherSize, ContextCompat.getColor(this@StorageManagerActivity, R.color.category_other)),
                SegmentedStorageBar.Segment(info.trashSize, ContextCompat.getColor(this@StorageManagerActivity, R.color.category_trash))
            )
            binding.storageBar.setData(segments, info.totalCapacity)

            populateCategoryList(info)
        }
    }

    /** Moi hang danh muc: ten hien thi, dung luong, mau cham, va loai de mo khi bam vao (null = khong mo duoc gi cu the). */
    private data class CategoryRow(val name: String, val size: Long, val colorRes: Int, val openType: CategoryType?)

    private fun allCategoryRows(info: StorageInfo): List<CategoryRow> = listOf(
        CategoryRow(getString(R.string.home_category_photo), info.photoSize, R.color.category_photo, CategoryType.IMAGE),
        CategoryRow(getString(R.string.storage_category_archive), info.archiveSize, R.color.category_archive, null),
        CategoryRow(getString(R.string.home_category_apk), info.apkSize, R.color.category_apk, CategoryType.APK),
        CategoryRow(getString(R.string.home_category_doc), info.docSize, R.color.category_doc, CategoryType.DOCUMENT),
        CategoryRow(getString(R.string.home_category_video), info.videoSize, R.color.category_video, CategoryType.VIDEO),
        CategoryRow(getString(R.string.home_category_audio), info.audioSize, R.color.category_audio, CategoryType.AUDIO),
        CategoryRow(getString(R.string.storage_category_apps), info.appSize, R.color.category_apps, null),
        CategoryRow(getString(R.string.storage_category_system), info.systemSize, R.color.category_system, null),
        CategoryRow(getString(R.string.storage_category_other), info.otherSize, R.color.category_other, CategoryType.INTERNAL),
        CategoryRow(getString(R.string.home_trash), info.trashSize, R.color.category_trash, null)
    )

    private fun populateCategoryList(info: StorageInfo) {
        binding.categoryList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val all = allCategoryRows(info)
        val rows = if (isExpanded) all else all.take(collapsedCount)

        for (row in rows) {
            val itemBinding = ItemStorageCategoryBinding.inflate(inflater, binding.categoryList, false)
            itemBinding.tvCategoryName.text = row.name
            itemBinding.tvCategorySize.text = formatSize(row.size)
            itemBinding.dotColor.backgroundTintList =
                android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, row.colorRes))

            // "Ứng dụng"/"Hệ thống"/"File khác" không mở được màn nào (openType null và không
            // phải Thùng rác) — trước đây vẫn hiện mũi tên ">" + hiệu ứng ripple khi bấm như
            // các dòng mở được khác dù bấm vào không có gì xảy ra, gây hiểu lầm là app bị treo/lỗi.
            // Khóa hẳn: ẩn mũi tên, tắt click/ripple để đúng bản chất "chỉ xem, không mở được".
            val isNavigable = row.openType != null || row.name == getString(R.string.home_trash)
            itemBinding.ivChevron.visibility = if (isNavigable) View.VISIBLE else View.GONE
            if (isNavigable) {
                itemBinding.root.isClickable = true
                itemBinding.root.setOnClickListener {
                    if (row.openType != null) {
                        val intent = Intent(this, CategoryFilesActivity::class.java).apply {
                            putExtra(CategoryFilesActivity.EXTRA_CATEGORY, row.openType.name)
                        }
                        ActivityTransitions.startForward(this, intent)
                    } else {
                        ActivityTransitions.startForward(this, Intent(this, TrashActivity::class.java))
                    }
                }
            } else {
                // Bỏ nền ripple (?attr/selectableItemBackground khai báo sẵn trong XML) để không
                // còn hiệu ứng bấm-mà-không-làm-gì, đúng với việc dòng này chỉ để xem, không mở được.
                itemBinding.root.background = null
                itemBinding.root.isClickable = false
                itemBinding.root.setOnClickListener(null)
            }
            binding.categoryList.addView(itemBinding.root)
        }

        binding.tvToggleMore.text = getString(
            if (isExpanded) R.string.storage_show_less else R.string.storage_show_more
        )
    }

    private fun openLargeFiles() {
        ActivityTransitions.startForward(this, Intent(this, LargeFilesActivity::class.java))
    }

    private fun populateActionList() {
        binding.actionList.removeAllViews()
        val inflater = LayoutInflater.from(this)

        data class ActionRow(val icon: Int, val title: String, val desc: String, val onClick: () -> Unit)

        val rows = listOf(
            ActionRow(
                R.drawable.ic_trash,
                getString(R.string.storage_action_trash),
                getString(R.string.storage_action_trash_desc)
            ) { ActivityTransitions.startForward(this, Intent(this, TrashActivity::class.java)) },
            ActionRow(
                R.drawable.ic_view_grid,
                getString(R.string.storage_action_unused_apps),
                getString(R.string.storage_action_unused_apps_desc)
            ) { ActivityTransitions.startForward(this, Intent(this, UnusedAppsActivity::class.java)) },
            ActionRow(
                R.drawable.ic_duplicate_files,
                getString(R.string.storage_action_duplicate),
                getString(R.string.storage_action_duplicate_desc)
            ) { ActivityTransitions.startForward(this, Intent(this, DuplicateFilesActivity::class.java)) },
            ActionRow(
                R.drawable.ic_large_files,
                getString(R.string.storage_action_large),
                getString(R.string.storage_action_large_desc)
            ) { openLargeFiles() }
        )

        for ((index, row) in rows.withIndex()) {
            val itemBinding = ItemStorageActionBinding.inflate(inflater, binding.actionList, false)
            itemBinding.ivActionIcon.setImageResource(row.icon)
            itemBinding.tvActionTitle.text = row.title
            itemBinding.tvActionDesc.text = row.desc
            itemBinding.tvActionSize.text = "0 B"
            itemBinding.root.setOnClickListener { row.onClick() }
            binding.actionList.addView(itemBinding.root)

            if (index != rows.lastIndex) {
                val divider = View(this)
                divider.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * resources.displayMetrics.density).toInt()
                )
                divider.setBackgroundColor(ContextCompat.getColor(this, R.color.divider))
                binding.actionList.addView(divider)
            }
        }

        lifecycleScope.launch {
            val trashSize = withContext(Dispatchers.IO) {
                TrashManager.getInstance(this@StorageManagerActivity).listEntries().sumOf { it.size }
            }
            if (binding.actionList.childCount > 0) {
                val trashItemRoot = binding.actionList.getChildAt(0)
                trashItemRoot.findViewById<android.widget.TextView>(R.id.tv_action_size)?.text = formatSize(trashSize)
            }
        }
    }

    private data class StorageInfo(
        val totalCapacity: Long,
        val totalUsed: Long,
        val photoSize: Long,
        val videoSize: Long,
        val audioSize: Long,
        val docSize: Long,
        val apkSize: Long,
        val archiveSize: Long,
        val appSize: Long,
        val systemSize: Long,
        val trashSize: Long,
        val otherSize: Long
    )

    private fun computeStorageInfo(): StorageInfo {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val totalCapacity = stat.blockCountLong * stat.blockSizeLong
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        val totalUsed = totalCapacity - availableBytes

        val photoSize = sumMediaStoreSize(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        val videoSize = sumMediaStoreSize(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        val audioSize = sumMediaStoreSize(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        val docSize = sumDocumentsSize()
        val apkSize = sumApkSize()
        val archiveSize = sumArchiveSize()
        val appSize = sumAppSize()
        val trashSize = TrashManager.getInstance(this).listEntries().sumOf { it.size }
        // Bo nho he thong (OS, cache he thong...) khong the doc truc tiep tu ung dung ben thu ba tren
        // Android hien dai; uoc luong bang phan con lai sau khi tru het cac danh muc do duoc.
        val knownSize = photoSize + videoSize + audioSize + docSize + apkSize + archiveSize + appSize + trashSize
        val remainder = (totalUsed - knownSize).coerceAtLeast(0L)
        val systemSize = (remainder * 6) / 10
        val otherSize = remainder - systemSize

        return StorageInfo(
            totalCapacity, totalUsed, photoSize, videoSize, audioSize, docSize,
            apkSize, archiveSize, appSize, systemSize, trashSize, otherSize
        )
    }

    private fun sumMediaStoreSize(uri: android.net.Uri): Long {
        var total = 0L
        try {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) total += cursor.getLong(idx)
            }
        } catch (e: Exception) {
            // bo qua neu khong truy van duoc
        }
        return total
    }

    private fun sumDocumentsSize(): Long {
        val exts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv")
        return sumFilesByExtension(exts, maxDepth = 4)
    }

    private fun sumApkSize(): Long = sumFilesByExtension(setOf("apk"), maxDepth = 4)

    private fun sumArchiveSize(): Long = sumFilesByExtension(setOf("zip", "rar", "7z", "tar", "gz"), maxDepth = 4)

    /** "Ung dung" (dung luong cai dat app) khong doc duoc truc tiep qua File API ben thu ba;
     * dung PackageManager de uoc luong theo kich thuoc file APK nguon cua cac app da cai. */
    private fun sumAppSize(): Long {
        return try {
            val pm = packageManager
            pm.getInstalledPackages(0).sumOf { pkg ->
                try {
                    java.io.File(pkg.applicationInfo?.sourceDir ?: "").length()
                } catch (e: Exception) {
                    0L
                }
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun sumFilesByExtension(exts: Set<String>, maxDepth: Int): Long {
        val root = Environment.getExternalStorageDirectory() ?: return 0L
        var total = 0L
        fun scan(dir: java.io.File, depth: Int) {
            if (depth > maxDepth) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.isDirectory) {
                    if (!f.name.startsWith(".")) scan(f, depth + 1)
                } else if (f.extension.lowercase() in exts) {
                    total += f.length()
                }
            }
        }
        try {
            scan(root, 0)
        } catch (e: Exception) {
            // bo qua thu muc khong doc duoc
        }
        return total
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val safeGroup = digitGroups.coerceIn(0, units.size - 1)
        return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, safeGroup.toDouble())) + " " + units[safeGroup]
    }
}
