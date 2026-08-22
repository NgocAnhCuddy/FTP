package com.myfile.ui.util

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Một mục trong thùng rác: file thật được lưu ở [trashPath], nhớ đường dẫn gốc để khôi phục. */
data class TrashEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val originalPath: String,
    val trashPath: String,
    val name: String,
    val size: Long,
    val deletedAt: Long = System.currentTimeMillis()
)

/**
 * Quản lý "Thùng rác" thật cho bộ nhớ trong: khi xóa, file được DI CHUYỂN vào thư mục
 * ẩn .MyFileTrash thay vì xóa hẳn. Metadata (đường dẫn gốc) lưu trong SharedPreferences
 * để có thể khôi phục đúng vị trí ban đầu, giống hành vi Thùng rác của Samsung My Files.
 */
class TrashManager private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val trashDir: File by lazy {
        val base = Environment.getExternalStorageDirectory()
        File(base, ".MyFileTrash").apply { if (!exists()) mkdirs() }
    }

    private fun loadEntries(): MutableList<TrashEntry> {
        val json = prefs.getString(KEY_ENTRIES, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<TrashEntry>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveEntries(entries: List<TrashEntry>) {
        prefs.edit().putString(KEY_ENTRIES, gson.toJson(entries)).apply()
    }

    fun listEntries(): List<TrashEntry> = loadEntries().sortedByDescending { it.deletedAt }

    /** Chuyển 1 file/thư mục vào thùng rác. Trả về true nếu thành công. */
    fun moveToTrash(file: File): Boolean {
        if (!file.exists()) return false
        return try {
            val uniqueName = "${System.currentTimeMillis()}_${file.name}"
            val dest = File(trashDir, uniqueName)
            val moved = file.renameTo(dest) || run {
                // renameTo có thể fail qua filesystem khác nhau -> copy rồi xóa gốc
                file.copyRecursively(dest, overwrite = true)
                file.deleteRecursively()
            }
            if (moved || dest.exists()) {
                val entries = loadEntries()
                entries.add(
                    TrashEntry(
                        originalPath = file.absolutePath,
                        trashPath = dest.absolutePath,
                        name = file.name,
                        size = if (file.isFile) file.length() else dest.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    )
                )
                saveEntries(entries)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    /** Khôi phục 1 mục về đúng vị trí gốc. */
    fun restore(entry: TrashEntry): Boolean {
        return try {
            val src = File(entry.trashPath)
            if (!src.exists()) {
                removeEntry(entry.id)
                return false
            }
            val dest = File(entry.originalPath)
            dest.parentFile?.mkdirs()
            val ok = src.renameTo(dest) || run {
                src.copyRecursively(dest, overwrite = true)
                src.deleteRecursively()
                dest.exists()
            }
            if (ok) removeEntry(entry.id)
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** Xóa vĩnh viễn 1 mục khỏi thùng rác. */
    fun deleteForever(entry: TrashEntry): Boolean {
        return try {
            val f = File(entry.trashPath)
            val ok = !f.exists() || f.deleteRecursively()
            if (ok) removeEntry(entry.id)
            ok
        } catch (e: Exception) {
            false
        }
    }

    fun emptyTrash(): Boolean {
        return try {
            trashDir.listFiles()?.forEach { it.deleteRecursively() }
            saveEntries(emptyList())
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun removeEntry(id: String) {
        val entries = loadEntries()
        entries.removeAll { it.id == id }
        saveEntries(entries)
    }

    companion object {
        private const val PREFS_NAME = "trash_prefs"
        private const val KEY_ENTRIES = "trash_entries"

        @Volatile private var instance: TrashManager? = null

        fun getInstance(context: Context): TrashManager =
            instance ?: synchronized(this) {
                instance ?: TrashManager(context.applicationContext).also { instance = it }
            }
    }
}
