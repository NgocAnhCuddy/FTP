package com.myfile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.myfile.ui.R
import com.myfile.ui.client.RemoteClient
import com.myfile.ui.databinding.ActivityFtpConnectionBinding
import com.myfile.ui.model.ConnectionType
import com.myfile.ui.model.FtpConnectionProfile
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.SecurePrefs
import kotlinx.coroutines.launch

class FtpConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFtpConnectionBinding
    private lateinit var prefs: SecurePrefs

    private val qrScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            applyQrResult(result.data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFtpConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = SecurePrefs.getInstance(this)

        binding.toolbar.setNavigationOnClickListener {
            finish()
            ActivityTransitions.backward(this)
        }

        binding.chipGroupType.setOnCheckedStateChangeListener { _, _ -> updateFieldsForType() }
        updateFieldsForType()

        binding.btnScanLan.setOnClickListener { scanLan() }
        binding.btnScanQr.setOnClickListener {
            qrScanLauncher.launch(Intent(this, QrScannerActivity::class.java))
        }
        binding.btnConnect.setOnClickListener { attemptConnect() }
    }

    /** Điền form kết nối từ kết quả trả về của QrScannerActivity. */
    private fun applyQrResult(data: Intent?) {
        data ?: return
        val host = data.getStringExtra(QrScannerActivity.EXTRA_HOST) ?: return
        val port = data.getIntExtra(QrScannerActivity.EXTRA_PORT, 21)
        val username = data.getStringExtra(QrScannerActivity.EXTRA_USERNAME).orEmpty()
        val password = data.getStringExtra(QrScannerActivity.EXTRA_PASSWORD).orEmpty()
        val typeName = data.getStringExtra(QrScannerActivity.EXTRA_TYPE) ?: ConnectionType.FTP.name
        val smbShare = data.getStringExtra(QrScannerActivity.EXTRA_SMB_SHARE).orEmpty()

        val chipId = when (ConnectionType.valueOf(typeName)) {
            ConnectionType.FTP -> binding.chipFtp.id
            ConnectionType.SFTP -> binding.chipSftp.id
            ConnectionType.SMB -> binding.chipSmb.id
        }
        binding.chipGroupType.check(chipId)
        binding.etHost.setText(host)
        binding.etPort.setText(port.toString())
        binding.etUsername.setText(username)
        binding.etPassword.setText(password)
        if (smbShare.isNotBlank()) {
            binding.etSmbShare.setText(smbShare)
        }
    }

    private fun selectedType(): ConnectionType = when (binding.chipGroupType.checkedChipId) {
        binding.chipSftp.id -> ConnectionType.SFTP
        binding.chipSmb.id -> ConnectionType.SMB
        else -> ConnectionType.FTP
    }

    /** Ẩn/hiện field riêng theo giao thức, và cập nhật cổng mặc định gợi ý khi đổi loại. */
    private fun updateFieldsForType() {
        val type = selectedType()
        binding.tilSmbShare.visibility = if (type == ConnectionType.SMB) View.VISIBLE else View.GONE
        binding.tilSmbDomain.visibility = if (type == ConnectionType.SMB) View.VISIBLE else View.GONE

        val currentPort = binding.etPort.text?.toString()?.toIntOrNull()
        val defaultPortsInUse = setOf(21, 22, 445)
        if (currentPort == null || currentPort in defaultPortsInUse) {
            binding.etPort.setText(
                when (type) {
                    ConnectionType.FTP -> "21"
                    ConnectionType.SFTP -> "22"
                    ConnectionType.SMB -> "445"
                }
            )
        }
    }

    /** Dò các host đang mở cổng FTP/SFTP/SMB trong mạng LAN hiện tại, để chọn nhanh thay vì gõ tay IP. */
    private fun scanLan() {
        binding.progress.visibility = View.VISIBLE
        binding.btnScanLan.isEnabled = false
        val type = selectedType()
        lifecycleScope.launch {
            val found = com.myfile.ui.util.LanScanner.scan(this@FtpConnectionActivity, type)
            binding.progress.visibility = View.GONE
            binding.btnScanLan.isEnabled = true
            if (found.isEmpty()) {
                com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.no_lan_devices_found), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            val labels = found.map { "${it.ip}:${it.port}" }.toTypedArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@FtpConnectionActivity)
                .setTitle(getString(R.string.title_lan_scan))
                .setItems(labels) { _, which ->
                    binding.etHost.setText(found[which].ip)
                    binding.etPort.setText(found[which].port.toString())
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun attemptConnect() {
        val type = selectedType()
        val host = binding.etHost.text?.toString()?.trim().orEmpty()
        val port = binding.etPort.text?.toString()?.toIntOrNull() ?: 21
        val username = binding.etUsername.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()
        val smbShare = binding.etSmbShare.text?.toString()?.trim().orEmpty()
        val smbDomain = binding.etSmbDomain.text?.toString()?.trim().orEmpty()

        if (host.isEmpty()) {
            binding.etHost.error = getString(R.string.hint_host)
            return
        }
        if (type == ConnectionType.SMB && smbShare.isEmpty()) {
            binding.etSmbShare.error = getString(R.string.hint_smb_share)
            return
        }

        val profile = FtpConnectionProfile(
            name = if (type == ConnectionType.SMB) "$host/$smbShare" else host,
            host = host,
            port = port,
            username = username,
            password = password,
            type = type,
            smbShareName = smbShare,
            smbDomain = smbDomain
        )

        binding.progress.visibility = View.VISIBLE
        binding.btnConnect.isEnabled = false

        lifecycleScope.launch {
            val client = RemoteClient.forProfile(profile)
            val result = client.connect(profile)
            binding.progress.visibility = View.GONE
            binding.btnConnect.isEnabled = true

            if (result.isSuccess) {
                client.disconnect()
                val existing = prefs.loadConnections()
                existing.removeAll { it.host == profile.host && it.username == profile.username && it.type == profile.type && it.smbShareName == profile.smbShareName }
                existing.add(profile)
                prefs.saveConnections(existing)

                val intent = Intent(this@FtpConnectionActivity, FileBrowserActivity::class.java)
                intent.putExtra(FileBrowserActivity.EXTRA_CONNECTION_ID, profile.id)
                startActivity(intent)
                ActivityTransitions.forward(this@FtpConnectionActivity)
                finish()
            } else {
                binding.etHost.error = result.exceptionOrNull()?.message ?: getString(R.string.connect_failed)
            }
        }
    }
}
