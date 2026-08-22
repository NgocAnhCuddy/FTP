package com.myfile.ui

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.myfile.ui.adapters.UnusedAppAdapter
import com.myfile.ui.adapters.UnusedAppInfo
import com.myfile.ui.databinding.ActivityUnusedAppsBinding
import com.myfile.ui.util.ActivityTransitions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Man hinh "Ung dung khong dung": liet ke cac ung dung nguoi dung da cai (khong tinh app he
 * thong) ma khong mo trong >= 30 ngay qua, dua tren UsageStatsManager. Can quyen dac biet
 * "Truy cap dữ liệu sử dụng" (PACKAGE_USAGE_STATS) — quyen nay chi xin duoc qua man hinh
 * Cai dat he thong, khong xin runtime binh thuong.
 */
class UnusedAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnusedAppsBinding
    private lateinit var adapter: UnusedAppAdapter

    private val uninstallLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { loadUnusedApps() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnusedAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
            ActivityTransitions.backward(this)
        }

        adapter = UnusedAppAdapter(onUninstall = { requestUninstall(it) })
        binding.rvUnusedApps.layoutManager = LinearLayoutManager(this)
        binding.rvUnusedApps.adapter = adapter

        binding.btnGrantUsagePermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    override fun onResume() {
        super.onResume()
        loadUnusedApps()
    }

    /**
     * getSystemService() có kiểu trả về nullable (Any?) — ép kiểu "as" cứng (non-null) trước đây
     * sẽ ném NullPointerException/ClassCastException nếu service không tồn tại (cực hiếm, chỉ
     * có thể xảy ra trên ROM tùy biến/thiết bị bất thường, nhưng vẫn nên phòng thủ đúng cách
     * thay vì để crash không rõ nguyên nhân).
     */
    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun loadUnusedApps() {
        if (!hasUsageAccess()) {
            binding.layoutPermissionNeeded.visibility = View.VISIBLE
            binding.rvUnusedApps.visibility = View.GONE
            binding.tvEmpty.visibility = View.GONE
            return
        }
        binding.layoutPermissionNeeded.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.rvUnusedApps.visibility = View.GONE

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { computeUnusedApps() }
            binding.progress.visibility = View.GONE
            if (apps.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.rvUnusedApps.visibility = View.GONE
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.rvUnusedApps.visibility = View.VISIBLE
                adapter.submit(apps)
            }
        }
    }

    private fun computeUnusedApps(): List<UnusedAppInfo> {
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val now = System.currentTimeMillis()
        val lookbackStart = now - TimeUnit.DAYS.toMillis(365)
        val statsList = usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, lookbackStart, now)
        val lastUsedByPackage = statsList.associateBy({ it.packageName }, { it.lastTimeUsed })

        val pm = packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val result = mutableListOf<UnusedAppInfo>()
        for (appInfo in installedApps) {
            // Bỏ qua app hệ thống không thể gỡ và chính app của mình.
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystemApp || appInfo.packageName == packageName) continue

            val lastUsed = lastUsedByPackage[appInfo.packageName] ?: 0L
            val daysUnused = if (lastUsed <= 0L) {
                // Chưa từng ghi nhận sử dụng trong dữ liệu usage stats — coi như cài lâu mà chưa mở.
                TimeUnit.MILLISECONDS.toDays(now - installTimeOf(appInfo)).toInt()
            } else {
                TimeUnit.MILLISECONDS.toDays(now - lastUsed).toInt()
            }
            if (daysUnused < MIN_DAYS_UNUSED) continue

            val label = try { pm.getApplicationLabel(appInfo).toString() } catch (e: Exception) { appInfo.packageName }
            val icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
            val size = try { File(appInfo.sourceDir).length() } catch (e: Exception) { 0L }

            result.add(UnusedAppInfo(appInfo.packageName, label, icon, daysUnused, size))
        }
        return result.sortedByDescending { it.daysUnused }
    }

    private fun installTimeOf(appInfo: ApplicationInfo): Long {
        return try {
            packageManager.getPackageInfo(appInfo.packageName, 0).firstInstallTime
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun requestUninstall(app: UnusedAppInfo) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
        uninstallLauncher.launch(intent)
    }

    companion object {
        private const val MIN_DAYS_UNUSED = 30
    }
}
