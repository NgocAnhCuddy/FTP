package com.myfile.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.myfile.ui.databinding.ActivityAppLockBinding
import com.myfile.ui.util.AppLockUtils
import com.myfile.ui.util.SecurePrefs

/**
 * Màn hình khoá app bằng mã PIN (4-6 chữ số) + tuỳ chọn vân tay/khuôn mặt.
 *
 * Có 2 chế độ, xác định qua extra [EXTRA_MODE]:
 *  - [MODE_CREATE]: người dùng đang BẬT tính năng khoá lần đầu — yêu cầu nhập PIN 2 lần
 *    (nhập + xác nhận) để tránh gõ nhầm rồi tự khoá mình ra khỏi app.
 *  - [MODE_UNLOCK]: màn khoá thật sự hiện ra mỗi khi mở app / quay lại từ nền — chỉ cần nhập
 *    đúng PIN đã lưu, hoặc dùng sinh trắc học nếu đã bật.
 *
 * finish() với RESULT_OK khi mở khoá/tạo PIN thành công; caller (MyFileApp / SettingsActivity)
 * tự quyết định làm gì tiếp theo. Activity này CHẶN nút Back ở MODE_UNLOCK — không cho thoát ra
 * ngoài mà chưa nhập đúng PIN, vì đây chính là mục đích của tính năng.
 */
class AppLockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppLockBinding
    private lateinit var mode: String
    private var enteredPin = StringBuilder()
    private var firstPinEntry: String? = null // dùng ở MODE_CREATE — lưu lần nhập đầu để so khớp lần 2
    private var wrongAttempts = 0

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_CREATE = "create"
        const val MODE_UNLOCK = "unlock"
        const val MAX_PIN_LENGTH = 6
        const val MIN_PIN_LENGTH = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK
        binding.tvLockTitle.text = if (mode == MODE_CREATE) getString(R.string.app_lock_create_pin) else getString(R.string.app_lock_enter_pin)

        setupNumpad()
        setupDots()
        setupBiometricButton()

        // Bàn phím số nằm ở phần dưới màn hình — thêm padding động tránh bị gesture bar
        // OneUI/HyperOS che mất hàng số cuối (0, backspace) trên các máy có gesture bar cao.
        com.myfile.ui.util.WindowInsetsUtils.applyBottomInsetPadding(binding.root)
    }

    /** Chặn Back khi đang ở màn khoá thật sự — người dùng phải nhập đúng PIN mới thoát được. */
    override fun onBackPressed() {
        if (mode == MODE_UNLOCK) {
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }

    private fun setupNumpad() {
        val digitButtons = listOf(
            binding.btnNum0 to "0", binding.btnNum1 to "1", binding.btnNum2 to "2",
            binding.btnNum3 to "3", binding.btnNum4 to "4", binding.btnNum5 to "5",
            binding.btnNum6 to "6", binding.btnNum7 to "7", binding.btnNum8 to "8",
            binding.btnNum9 to "9"
        )
        digitButtons.forEach { (button, digit) ->
            button.setOnClickListener { onDigitPressed(digit) }
        }
        binding.btnBackspace.setOnClickListener { onBackspacePressed() }
    }

    private fun onDigitPressed(digit: String) {
        if (enteredPin.length >= MAX_PIN_LENGTH) return
        binding.tvLockError.visibility = View.INVISIBLE
        enteredPin.append(digit)
        refreshDots()
        // Tự động xử lý khi đủ độ dài tối thiểu VÀ người dùng đã dừng gõ ở đúng độ dài đó —
        // nhưng vì PIN có thể dài 4, 5, hoặc 6 số, không thể "tự đoán" khi nào người dùng gõ
        // xong chỉ dựa vào độ dài. Cách chuẩn (giống khoá màn hình Android/iOS thật) là CHỈ tự
        // xử lý khi đạt đúng MAX_PIN_LENGTH, còn với PIN ngắn hơn, thêm 1 khoảng trễ ngắn để
        // phát hiện "người dùng đã dừng gõ" thay vì yêu cầu bấm nút Xong riêng — đơn giản hoá
        // bằng cách tự xử lý ngay khi đạt MIN_PIN_LENGTH trở lên và có debounce nhỏ.
        if (enteredPin.length >= MIN_PIN_LENGTH) {
            binding.dotsContainer.removeCallbacks(autoSubmitRunnable)
            binding.dotsContainer.postDelayed(autoSubmitRunnable, 250)
        }
    }

    private val autoSubmitRunnable = Runnable { handlePinComplete() }

    private fun onBackspacePressed() {
        if (enteredPin.isEmpty()) return
        binding.dotsContainer.removeCallbacks(autoSubmitRunnable)
        enteredPin.deleteCharAt(enteredPin.length - 1)
        binding.tvLockError.visibility = View.INVISIBLE
        refreshDots()
    }

    private fun setupDots() {
        refreshDots()
    }

    /**
     * Vẽ lại dãy chấm tròn theo đúng độ dài PIN đã nhập — MAX_PIN_LENGTH chấm cố định, chấm nào
     * trong phạm vi đã gõ thì hiển thị đặc (đã nhập), còn lại rỗng.
     */
    private fun refreshDots() {
        binding.dotsContainer.removeAllViews()
        val dotSize = (14 * resources.displayMetrics.density).toInt()
        val margin = (6 * resources.displayMetrics.density).toInt()
        for (i in 0 until MAX_PIN_LENGTH) {
            val dot = ImageView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginStart = margin
                    marginEnd = margin
                }
                setImageDrawable(
                    ContextCompat.getDrawable(
                        this@AppLockActivity,
                        if (i < enteredPin.length) R.drawable.shape_pin_dot_filled else R.drawable.shape_pin_dot_empty
                    )
                )
            }
            binding.dotsContainer.addView(dot)
        }
    }

    private fun handlePinComplete() {
        val pin = enteredPin.toString()
        when (mode) {
            MODE_CREATE -> handleCreatePinStep(pin)
            else -> handleUnlockAttempt(pin)
        }
    }

    private fun handleCreatePinStep(pin: String) {
        val first = firstPinEntry
        if (first == null) {
            // Lần nhập thứ 1: lưu tạm, yêu cầu nhập lại để xác nhận.
            firstPinEntry = pin
            enteredPin = StringBuilder()
            binding.tvLockTitle.text = getString(R.string.app_lock_confirm_pin)
            refreshDots()
        } else {
            // Lần nhập thứ 2: phải khớp lần 1 mới được lưu.
            if (pin == first) {
                SecurePrefs.getInstance(this).apply {
                    appLockPinHash = AppLockUtils.hashPin(pin)
                    appLockEnabled = true
                }
                android.widget.Toast.makeText(this, getString(R.string.app_lock_pin_created), android.widget.Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } else {
                binding.tvLockError.text = getString(R.string.app_lock_pin_mismatch)
                binding.tvLockError.visibility = View.VISIBLE
                shakeDots()
                firstPinEntry = null
                enteredPin = StringBuilder()
                binding.tvLockTitle.text = getString(R.string.app_lock_create_pin)
                refreshDots()
            }
        }
    }

    private fun handleUnlockAttempt(pin: String) {
        val storedHash = SecurePrefs.getInstance(this).appLockPinHash
        if (storedHash != null && AppLockUtils.verifyPin(pin, storedHash)) {
            setResult(RESULT_OK)
            finish()
        } else {
            wrongAttempts++
            binding.tvLockError.text = getString(R.string.app_lock_wrong_pin)
            binding.tvLockError.visibility = View.VISIBLE
            shakeDots()
            enteredPin = StringBuilder()
            refreshDots()
        }
    }

    private fun shakeDots() {
        val shake = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.shake)
        binding.dotsContainer.startAnimation(shake)
    }

    // ---------- Sinh trắc học ----------

    private fun setupBiometricButton() {
        if (mode != MODE_UNLOCK) return // sinh trắc học chỉ dùng để MỞ KHOÁ, không dùng khi tạo PIN mới
        val prefs = SecurePrefs.getInstance(this)
        if (!prefs.appLockBiometricEnabled) return

        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) return // không có cảm biến / chưa đăng ký vân tay nào — ẩn nút, chỉ còn PIN

        binding.btnBiometric.visibility = View.VISIBLE
        binding.btnBiometric.setOnClickListener { showBiometricPrompt() }
        // Tự động hiện ngay khi vào màn hình để đỡ phải bấm thêm 1 lần — giống hành vi khoá màn
        // hình quen thuộc trên hầu hết điện thoại Android hiện đại.
        showBiometricPrompt()
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                setResult(RESULT_OK)
                finish()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Người dùng tự huỷ (bấm Huỷ, hoặc quá nhiều lần sai bị khoá tạm) — không coi là
                // lỗi nghiêm trọng, chỉ đơn giản quay về nhập PIN như bình thường.
            }
            override fun onAuthenticationFailed() {
                // 1 lần quét vân tay sai — BiometricPrompt tự cho thử lại, không cần xử lý thêm.
            }
        }
        val prompt = BiometricPrompt(this, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_lock_biometric_prompt_title))
            .setSubtitle(getString(R.string.app_lock_biometric_prompt_subtitle))
            .setNegativeButtonText(getString(R.string.cancel))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(promptInfo)
    }
}
