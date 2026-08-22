package com.myfile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.myfile.ui.databinding.ActivityQrScannerBinding
import com.myfile.ui.util.ActivityTransitions
import com.myfile.ui.util.QrCodeUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Quét mã QR bằng camera thiết bị để đọc thông tin kết nối FTP/SFTP/SMB (do
 * ServerFragment tạo ra ở máy chủ) và trả kết quả về FtpConnectionActivity để tự điền form.
 */
@androidx.camera.core.ExperimentalGetImage
class QrScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    // Chỉ xử lý 1 lần: tránh mở nhiều dialog/kết thúc activity nhiều lần khi nhiều
    // frame liên tiếp đều đọc ra cùng 1 mã QR trong lúc camera vẫn đang chạy.
    private val handled = AtomicBoolean(false)

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
            ActivityTransitions.backward(this)
        }
        binding.btnGrantCamera.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPermissionDenied() {
        binding.layoutPermissionDenied.visibility = View.VISIBLE
    }

    private fun startCamera() {
        binding.layoutPermissionDenied.visibility = View.GONE
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            // cameraProviderFuture.get() có thể ném ExecutionException nếu CameraX khởi tạo thất
            // bại (thiết bị không có camera, lỗi driver/HAL) — trước đây try/catch chỉ bọc đoạn
            // bindToLifecycle() bên dưới, không bọc get(), nên trường hợp đó crash thẳng ra
            // ngoài thay vì được xử lý như 1 lỗi camera bình thường.
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                val scannerOptions = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                val scanner = BarcodeScanning.getClient(scannerOptions)

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processFrame(imageProxy, scanner)
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                com.myfile.ui.util.LogBus.error("Không thể khởi động camera quét QR", source = "APP", throwable = e)
                if (!isFinishing && !isDestroyed) {
                    android.widget.Toast.makeText(this, getString(R.string.error_generic), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processFrame(imageProxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || handled.get()) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                if (raw != null && handled.compareAndSet(false, true)) {
                    onQrDetected(raw)
                }
            }
            .addOnFailureListener {
                // Bỏ qua lỗi từng frame đơn lẻ — camera vẫn tiếp tục quét frame tiếp theo
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun onQrDetected(raw: String) {
        val profile = QrCodeUtils.parseConnectionUri(raw)
        runOnUiThread {
            if (profile == null) {
                android.widget.Toast.makeText(this, getString(R.string.qr_invalid_format), android.widget.Toast.LENGTH_LONG).show()
                // Cho phép quét lại thay vì phải thoát ra vào lại màn hình
                handled.set(false)
                return@runOnUiThread
            }
            android.widget.Toast.makeText(this, getString(R.string.qr_scan_success), android.widget.Toast.LENGTH_SHORT).show()
            val result = Intent().apply {
                putExtra(EXTRA_HOST, profile.host)
                putExtra(EXTRA_PORT, profile.port)
                putExtra(EXTRA_USERNAME, profile.username)
                putExtra(EXTRA_PASSWORD, profile.password)
                putExtra(EXTRA_TYPE, profile.type.name)
                putExtra(EXTRA_SMB_SHARE, profile.smbShareName)
            }
            setResult(RESULT_OK, result)
            finish()
            ActivityTransitions.backward(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_SMB_SHARE = "extra_smb_share"
    }
}
