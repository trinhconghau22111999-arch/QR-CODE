package com.example.qrkeyboard

import android.content.Context

/** THEM (theo yeu cau nguoi dung): 2 CONG TAC rieng trong man Cai dat -
 *  "Goi y sua chinh ta" (Tieng Viet, dua tren tu dien co san - xem
 *  [VietnameseAutocorrect]) va "Goi y emoji" (vd go "hihi" -> goi y icon
 *  cuoi). CA HAI CUNG DUNG CHUNG 1 hang hien thi phia tren ban phim (xem
 *  [QrKeyboardService.buildLettersPage] - chi hien DUY NHAT 1 trong 2 tai 1
 *  thoi diem), nen theo dung yeu cau nguoi dung, 2 CONG TAC nay LOAI TRU LAN
 *  NHAU: bat cai nay se TU DONG tat cai kia.
 *
 *  MAC DINH: "Goi y sua chinh ta" TAT (tinh nang MOI, chua tung bat trong
 *  luong go binh thuong tu truoc gio - GIU NGUYEN hanh vi on dinh nguoi dung
 *  dang quen). "Goi y emoji" BAT (day la hanh vi MAC DINH CU, da hoat dong
 *  tu truoc - KHONG doi de khong lam nguoi dung dang dung bi bat ngo). */
object SuggestionPrefs {
    private const val PREFS_NAME = "qr_keyboard_suggestion_prefs"
    private const val KEY_AUTOCORRECT_ENABLED = "autocorrect_enabled"
    private const val KEY_EMOJI_ENABLED = "emoji_suggestion_enabled"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutocorrectEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTOCORRECT_ENABLED, false)

    fun isEmojiSuggestionEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_EMOJI_ENABLED, true)

    /** Bat "Goi y sua chinh ta" - neu [enabled] = true, TU DONG tat luon
     *  "Goi y emoji" (loai tru lan nhau, xem giai thich o dau file). */
    fun setAutocorrectEnabled(ctx: Context, enabled: Boolean) {
        val editor = prefs(ctx).edit().putBoolean(KEY_AUTOCORRECT_ENABLED, enabled)
        if (enabled) editor.putBoolean(KEY_EMOJI_ENABLED, false)
        editor.apply()
    }

    /** Bat "Goi y emoji" - neu [enabled] = true, TU DONG tat luon "Goi y sua
     *  chinh ta" (loai tru lan nhau, xem giai thich o dau file). */
    fun setEmojiSuggestionEnabled(ctx: Context, enabled: Boolean) {
        val editor = prefs(ctx).edit().putBoolean(KEY_EMOJI_ENABLED, enabled)
        if (enabled) editor.putBoolean(KEY_AUTOCORRECT_ENABLED, false)
        editor.apply()
    }
}
