package com.example.qrkeyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

/**
 * TRUOC DAY: man hinh nay hien mot nut "Mo cai dat" - nguoi dung phai tu
 * cham vao nut do moi duoc dua toi man hinh he thong de bat ban phim QR
 * Keyboard. Day chinh la "trang dau tien xuat hien sau khi cai dat" ma
 * nguoi dung muon BO QUA.
 *
 * GIO DAY: khong con hien giao dien nao ca (khong con setContentView) -
 * ngay khi Activity nay duoc mo (tuc ngay sau khi cai dat, nguoi dung bam
 * vao icon app lan dau), no TU DONG chuyen thang toi man hinh cai dat ban
 * phim he thong (Settings.ACTION_INPUT_METHOD_SETTINGS) roi tu finish()
 * chinh minh ngay - nguoi dung khong con thay "trang chinh" trung gian nao
 * nua, ma duoc dua thang toi dich can den.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        finish()
    }
}
