package com.example.qrkeyboard

import android.content.Context
import android.graphics.Color

/**
 * Luu/doc mau vien (accent color) + che do sang/toi cua ban phim - dung
 * CHUNG boi QrKeyboardService (ap dung thuc te len ban phim) VA
 * SettingsActivity (giao dien chinh mau MOI, da chuyen tu thanh chon mau
 * ngay tren trang Ky hieu cua ban phim vao muc "Mau sac" trong Cai dat -
 * theo yeu cau nguoi dung). Ca 2 noi dung CHUNG 1 SharedPreferences nen doi
 * mau o Cai dat se AP DUNG NGAY cho ban phim (khong can dong bo them gi).
 */
object KeyboardThemePrefs {
    private const val PREFS_NAME = "qr_keyboard_prefs"
    private const val PREF_ACCENT_COLOR = "accent_color"
    private const val PREF_IS_DARK_THEME = "is_dark_theme"

    /** Mau tim neon MAC DINH - giu nguyen y het mau glowColor cu truoc khi
     *  co tinh nang doi mau. */
    val DEFAULT_ACCENT_COLOR: Int = Color.parseColor("#B388FF")

    /** Bang mau co san de nguoi dung chon - giu NGUYEN VEN thu tu + gia tri
     *  cu tu QrKeyboardService (khong doi gi ca khi chuyen vi tri hien thi).
     *  THEM (theo yeu cau nguoi dung: "them mau trang va den vao cai dat mau
     *  sac"): them Trang (#FFFFFF) va Den (#000000) vao CUOI danh sach. */
    val ACCENT_COLORS: List<Int> = listOf(
        Color.parseColor("#FF3B30"), // Do
        Color.parseColor("#3B82F6"), // Xanh duong
        Color.parseColor("#34C759"), // Luc (xanh la)
        Color.parseColor("#FFD60A"), // Vang
        Color.parseColor("#FF2D8A"), // Hong
        Color.parseColor("#FF9500"), // Cam
        DEFAULT_ACCENT_COLOR,        // Tim neon (mac dinh)
        Color.parseColor("#8B5E3C"), // Nau
        Color.parseColor("#FFFFFF"), // Trang
        Color.parseColor("#000000")  // Den
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAccentColor(ctx: Context): Int =
        prefs(ctx).getInt(PREF_ACCENT_COLOR, DEFAULT_ACCENT_COLOR)

    fun setAccentColor(ctx: Context, color: Int) {
        prefs(ctx).edit().putInt(PREF_ACCENT_COLOR, color).apply()
    }

    // SUA (theo yeu cau nguoi dung: "mac dinh sau khi cai app: chon san nen
    // trang vien tim"): mac dinh TRUOC DAY la true (nen TOI/dark) - GIO la
    // false (nen SANG/trang). Vien tim ([DEFAULT_ACCENT_COLOR] o tren,
    // #B388FF) VON DA la mac dinh san tu truoc, khong doi gi them.
    fun isDarkTheme(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_IS_DARK_THEME, false)

    fun setDarkTheme(ctx: Context, dark: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_IS_DARK_THEME, dark).apply()
    }
}
