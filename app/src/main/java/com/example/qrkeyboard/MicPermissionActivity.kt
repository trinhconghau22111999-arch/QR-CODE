package com.example.qrkeyboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Activity TOI GIAN (giong het [QrCameraPermissionActivity]) - CHi lam DUY
 * NHAT 1 viec: hien hop thoai xin quyen RECORD_AUDIO cua he thong (Android
 * BAT BUOC phai gan hop thoai xin quyen runtime voi 1 Activity, Service
 * khong the tu hien).
 *
 * THEM (theo yeu cau nguoi dung: "mic phải dùng mic riêng" - khong con uy
 * quyen hoan toan cho app tro ly ao/Google qua Intent popup rieng nhu
 * [VoiceInputActivity] cu nua, vi chat luong khong on dinh, phu thuoc app
 * mac dinh cua may): app nay gio TU dung [android.speech.SpeechRecognizer]
 * truc tiep ngay trong [QrKeyboardService] de nghe/nhan dien - can quyen
 * RECORD_AUDIO CUA CHINH APP thay vi dua vao quyen cua app khac.
 *
 * Ngay khi nguoi dung tra loi, dong lai NGAY va bao ket qua ve
 * [QrKeyboardService.notifyMicPermissionResult].
 */
class MicPermissionActivity : AppCompatActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "C\u1ea7n quy\u1ec1n Mic \u0111\u1ec3 nh\u1eadp li\u1ec7u b\u1eb1ng gi\u1ecdng n\u00f3i", Toast.LENGTH_SHORT).show()
            }
            QrKeyboardService.notifyMicPermissionResult(granted)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            QrKeyboardService.notifyMicPermissionResult(true)
            finish()
            return
        }

        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
