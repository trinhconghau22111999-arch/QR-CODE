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
    // THEM (theo yeu cau nguoi dung, xem anh chup man hinh: "them 1 lop dat
    // nen bang hinh anh...bam vao la mo thu muc chon anh...chon anh de dat
    // nen...phai cho chon vung anh...chon xong la dat nen luon"): luu
    // DUONG DAN FILE (khong phai Uri goc tu thu vien anh - Uri content://
    // co the MAT quyen doc SAU KHI app khoi dong lai/thu vien anh doi cau
    // truc noi bo) - anh da CAT XONG duoc GHI RIENG 1 BAN thanh file JPEG
    // trong bo nho NOI BO cua app (filesDir, luon con nguyen ven, KHONG can
    // xin quyen gi them de doc lai ve sau).
    private const val PREF_BACKGROUND_IMAGE_FILENAME = "background_image_filename"
    const val BACKGROUND_IMAGE_FILENAME = "keyboard_bg.jpg"

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

    /** Duong dan file THAT SU (trong filesDir noi bo cua app) toi anh nen
     *  DA CAT XONG - dung ham nay de LAY duong dan GHI/DOC file, khong
     *  lien quan gi den SharedPreferences (chi 1 file duy nhat, luon ghi
     *  DE LEN ban cu moi lan doi anh moi). */
    fun backgroundImageFile(ctx: Context) =
        java.io.File(ctx.filesDir, BACKGROUND_IMAGE_FILENAME)

    /** Co dang su dung anh lam nen hay khong (bat/tat rieng voi viec FILE
     *  anh co ton tai hay khong - "Xoa hinh nen, dung lai mau" chi TAT co
     *  nay, KHONG can xoa han file, de "Doi anh khac" sau co the tai su
     *  dung neu muon - nhung don gian hoa, "Xoa" SE xoa han file luon, xem
     *  [clearBackgroundImage]). */
    fun hasBackgroundImage(ctx: Context): Boolean =
        prefs(ctx).getBoolean(PREF_BACKGROUND_IMAGE_FILENAME, false) && backgroundImageFile(ctx).exists()

    fun setHasBackgroundImage(ctx: Context, has: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_BACKGROUND_IMAGE_FILENAME, has).apply()
    }

    fun clearBackgroundImage(ctx: Context) {
        setHasBackgroundImage(ctx, false)
        try {
            backgroundImageFile(ctx).delete()
        } catch (e: Exception) {
            // Bo qua - hiem gap, khong quan trong (file se bi GHI DE lan
            // sau nguoi dung chon anh moi, du chua xoa duoc ngay bay gio).
        }
    }
}
