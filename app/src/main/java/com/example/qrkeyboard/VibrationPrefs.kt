package com.example.qrkeyboard

import android.content.Context

/**
 * THEM (theo yeu cau nguoi dung: "cho phep tu chinh do rung theo y muon, lam
 * 1 thanh ngang de keo chon"): TRUOC DAY cuong do rung phim la 1 con so CO
 * DINH (amplitude = 200/255) khong the doi duoc. Gio luu lai muc do rung do
 * NGUOI DUNG TU CHON qua 1 SeekBar (thanh truot ngang) trong man Cai dat,
 * luu duoi dang PHAN TRAM 0-100 (0 = TAT HAN rung, khong con dung "cho phep
 * dung dung" theo dung yeu cau).
 *
 * MAC DINH: 100% (~amplitude 255) - giu nguyen cam giac "rung manh nhat" gan
 * dung voi hanh vi CU truoc khi co tinh nang nay (amplitude 200/255 ~ 78%,
 * lam tron len 100% cho de hieu/de chinh, khong anh huong dang ke).
 */
object VibrationPrefs {
    private const val PREFS_NAME = "qr_keyboard_vibration_prefs"
    private const val KEY_LEVEL_PERCENT = "level_percent"
    const val DEFAULT_LEVEL_PERCENT = 100

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 0-100 (0 = tắt hẳn rung khi gõ phím). */
    fun getLevelPercent(ctx: Context): Int =
        prefs(ctx).getInt(KEY_LEVEL_PERCENT, DEFAULT_LEVEL_PERCENT).coerceIn(0, 100)

    fun setLevelPercent(ctx: Context, percent: Int) {
        prefs(ctx).edit().putInt(KEY_LEVEL_PERCENT, percent.coerceIn(0, 100)).apply()
    }

    /** Đổi phần trăm (0-100) sang amplitude thật (1-255) dùng cho
     *  VibrationEffect.createOneShot() - amplitude 0 KHÔNG hợp lệ với API
     *  Android (ném IllegalArgumentException), nên khi percent > 0, luôn
     *  đảm bảo amplitude tối thiểu là 1 (rung rất nhẹ) thay vì làm tròn
     *  xuống 0. Gọi nơi dùng tự kiểm tra percent == 0 để BỎ QUA rung hẳn
     *  (xem [QrKeyboardService.vibrateKeyPress]) trước khi cần tới hàm này.
     */
    fun percentToAmplitude(percent: Int): Int =
        ((percent.coerceIn(1, 100) * 255) / 100).coerceIn(1, 255)
}
