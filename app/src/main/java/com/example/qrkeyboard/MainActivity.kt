package com.example.qrkeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            goToInputMethodSettings()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // LỖI ĐÃ SỬA: trước đây onCreate() LUÔN nhảy thẳng sang trang "Bàn phím trên màn hình"
        // (Settings.ACTION_INPUT_METHOD_SETTINGS) rồi finish() ngay lập tức - kể cả khi có lỗi
        // (crash) đã được CrashReporter ghi lại từ lần chạy trước, nên bấm icon app để COPY lỗi
        // là không thể - màn hình lỗi không bao giờ có cơ hội hiện ra. Giờ kiểm tra lỗi đã lưu
        // TRƯỚC, hiện hộp thoại có nút "Sao chép" cho người dùng đọc/copy xong mới đi tiếp -
        // luồng "vào thẳng Cài đặt bàn phím" như cũ CHỈ chạy khi KHÔNG có lỗi nào được ghi.
        val crashLog = CrashReporter.readLastCrash(this)
        if (crashLog != null) {
            showCrashLogDialog(crashLog)
        } else {
            proceedToPermissionThenSettings()
        }
    }

    private fun showCrashLogDialog(log: String) {
        val scrollableText = android.widget.TextView(this).apply {
            text = log
            setTextIsSelectable(true)
            textSize = 12f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = android.widget.ScrollView(this).apply { addView(scrollableText) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Lần trước app gặp sự cố")
            .setView(scroll)
            .setCancelable(false)
            .setPositiveButton("Sao chép") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash_log", log))
                android.widget.Toast.makeText(this, "Đã sao chép", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .setOnDismissListener {
                // Chạy ĐÚNG 1 LẦN dù người dùng bấm nút nào (cả 2 nút đều tự dismiss dialog) -
                // dọn lỗi đã xem xong rồi mới đi tiếp luồng bình thường (mở Cài đặt bàn phím).
                CrashReporter.clearLastCrash(this)
                proceedToPermissionThenSettings()
            }
            .show()
    }

    private fun proceedToPermissionThenSettings() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            goToInputMethodSettings()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun goToInputMethodSettings() {
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        finish()
    }
}
