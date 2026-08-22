package com.myfile.ui.util

import com.myfile.ui.R

/**
 * Chọn icon thư mục phù hợp: các thư mục hệ thống quen thuộc (Android, DCIM, Download...)
 * hiện icon thư mục kèm badge nhỏ ở góc để dễ nhận biết bằng mắt, giống Samsung My Files —
 * nhưng badge vẫn giữ tông xanh dương pastel của app thay vì nhiều màu như ảnh mẫu.
 */
object FolderIcons {
    fun iconFor(folderName: String): Int = when (folderName.trim().lowercase()) {
        "android" -> R.drawable.ic_folder_android
        "dcim" -> R.drawable.ic_folder_dcim
        "download", "downloads" -> R.drawable.ic_folder_download
        "movies" -> R.drawable.ic_folder_movies
        "music" -> R.drawable.ic_folder_music
        "pictures" -> R.drawable.ic_folder_pictures
        "documents" -> R.drawable.ic_folder_documents
        else -> R.drawable.ic_folder
    }
}
