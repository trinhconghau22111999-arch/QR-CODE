package com.example.qrkeyboard

import android.content.Context

/**
 * THEM (theo yeu cau nguoi dung): hieu ung "den RGB chay" tren vien phim -
 * giong bàn phim co gaming that (Razer Chroma, SteelSeries...). MAC DINH
 * TAT (khong doi hanh vi on dinh nguoi dung dang quen) - nguoi dung tu bat
 * trong man Cai dat neu muon, vi hieu ung nay ton pin hon mau tinh binh
 * thuong (phai ve lai vien phim lien tuc ~15-20 lan/giay khi ban phim dang
 * hien).
 */
object RgbEffectPrefs {
    private const val PREFS_NAME = "qr_keyboard_rgb_effect_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DIRECTION = "direction"
    private const val KEY_COLOR_MODE = "color_mode"

    /** Trai -> Phai (mau "quet" ngang qua ban phim). */
    const val DIRECTION_LEFT_TO_RIGHT = "ltr"

    /** Tren -> Duoi (mau "quet" theo tung hang). */
    const val DIRECTION_TOP_TO_BOTTOM = "ttb"

    /** Cheo goc trai-tren -> phai-duoi. */
    const val DIRECTION_DIAGONAL = "diag"

    const val DEFAULT_DIRECTION = DIRECTION_LEFT_TO_RIGHT

    /** THEM (theo yeu cau nguoi dung: "có chạy led nhiều màu nhưng lại
     *  không có chạy 1 màu"): "Nhieu mau" (RAINBOW, MAC DINH/hanh vi CU giu
     *  nguyen - vien phim doi qua toan bo dai mau cau vong) va "1 mau"
     *  (SINGLE - vien phim VAN "chay"/sang-toi theo huong da chon, nhung
     *  CHi dung 1 mau DUY NHAT: chinh la mau vien dang dung trong Cai dat
     *  giao dien - xem [KeyboardThemePrefs]). */
    const val COLOR_MODE_RAINBOW = "rainbow"
    const val COLOR_MODE_SINGLE = "single"
    const val DEFAULT_COLOR_MODE = COLOR_MODE_RAINBOW

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getDirection(ctx: Context): String =
        prefs(ctx).getString(KEY_DIRECTION, DEFAULT_DIRECTION) ?: DEFAULT_DIRECTION

    fun setDirection(ctx: Context, direction: String) {
        prefs(ctx).edit().putString(KEY_DIRECTION, direction).apply()
    }

    fun getColorMode(ctx: Context): String =
        prefs(ctx).getString(KEY_COLOR_MODE, DEFAULT_COLOR_MODE) ?: DEFAULT_COLOR_MODE

    fun setColorMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(KEY_COLOR_MODE, mode).apply()
    }

    fun directionDisplayName(direction: String): String = when (direction) {
        DIRECTION_LEFT_TO_RIGHT -> "Tr\u00e1i -> Ph\u1ea3i"
        DIRECTION_TOP_TO_BOTTOM -> "Tr\u00ean -> D\u01b0\u1edbi"
        DIRECTION_DIAGONAL -> "Ch\u00e9o g\u00f3c"
        else -> direction
    }
}
