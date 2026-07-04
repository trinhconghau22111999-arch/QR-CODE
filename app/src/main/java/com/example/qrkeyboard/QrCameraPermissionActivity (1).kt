package com.example.qrkeyboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Activity TOI GIAN, KHONG con chua bat ky giao dien quet QR nao (khong con
 * camera, preview, khung quet, nut Huy/Flash - tat ca nhung thu do da CHUYEN
 * HET vao QrKeyboardService duoi dang mot View noi duoc add truc tiep vao
 * cua so cua chinh Service do bang WindowManager, xem showQrOverlay() trong
 * QrKeyboardService.kt).
 *
 * Activity nay chi con DUY NHAT mot nhiem vu: hien HO THOAI XIN QUYEN CAMERA
 * cua he thong. Day la dieu BAT BUOC phai co mot Activity - Android khong
 * cho phep mot Service (InputMethodService la mot Service) tu minh hien hop
 * thoai xin quyen runtime. Ngay khi nguoi dung tra loi (cho phep hoac tu
 * choi), Activity nay dong lai NGAY LAP TUC va bao ket qua ve QrKeyboardService
 * qua companion callback tinh [QrKeyboardService.notifyCameraPermissionResult],
 * de ban phim tu dong mo tiep khung quet neu vua duoc cap quyen - nguoi dung
 * khong can bam lai nut [QR] lan thu hai.
 *
 * Vi Activity nay chi thoang qua trong tich tac (khong co giao dien rieng),
 * dung Theme.Transparent (khai bao trong AndroidManifest.xml) de khong tao
 * cam giac "nhay man hinh" khi mo/dong.
 */
class QrCameraPermissionActivity : AppCompatActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "C\u1ea7n quy\u1ec1n Camera \u0111\u1ec3 qu\u00e9t QR", Toast.LENGTH_SHORT).show()
            }
            QrKeyboardService.notifyCameraPermissionResult(granted)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Phong truong hop hiem: quyen da duoc cap tu truoc (vd nguoi dung tu
        // vao Settings bat tay) nhung QrKeyboardService lai kiem tra sai thoi
        // diem - kiem tra lai lan nua cho chac, tranh hien hop thoai thua.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            QrKeyboardService.notifyCameraPermissionResult(true)
            finish()
            return
        }

        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
}
