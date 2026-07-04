package com.example.qrkeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * TRUOC DAY: man hinh nay hien mot nut "Mo cai dat" - nguoi dung phai tu
 * cham vao nut do moi duoc dua toi man hinh he thong de bat ban phim QR
 * Keyboard. Day chinh la "trang dau tien xuat hien sau khi cai dat" ma
 * nguoi dung muon BO QUA.
 *
 * GIO DAY: khong con hien giao dien nao ca (khong con setContentView) -
 * ngay khi Activity nay duoc mo (tuc ngay sau khi cai dat, nguoi dung bam
 * vao icon app lan dau), no TU DONG xin luon quyen CAMERA (neu chua co) roi
 * moi chuyen thang toi man hinh cai dat ban phim he thong
 * (Settings.ACTION_INPUT_METHOD_SETTINGS) va tu finish() chinh minh ngay -
 * nguoi dung khong con thay "trang chinh" trung gian nao nua, ma duoc dua
 * thang toi dich can den.
 *
 * Xin quyen CAMERA ngay tu day (thay vi doi den luc nguoi dung bam nut [QR]
 * lan dau tren ban phim) de tranh viec QrKeyboardService phai mo
 * QrCameraPermissionActivity giua chung khi dang go dang - xin truoc mot lan
 * o day, hau het nguoi dung se khong bao gio thay hop thoai xin quyen do
 * nua trong suot qua trinh su dung ban phim.
 */
class MainActivity : AppCompatActivity() {

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Bo qua ket qua (duoc cap hay khong) - neu tu choi o day, ban
            // phim van hoat dong binh thuong cho van ban thuong, chi rieng
            // nut [QR] se tu xin lai quyen (qua QrCameraPermissionActivity)
            // vao lan dau nguoi dung thuc su bam nut do.
            goToInputMethodSettings()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
