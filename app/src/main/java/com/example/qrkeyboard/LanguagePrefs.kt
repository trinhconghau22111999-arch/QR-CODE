package com.example.qrkeyboard

import android.content.Context

/**
 * THEM (theo yeu cau nguoi dung): che do DA NGON NGU cho ban phim - MAC DINH
 * VAN LA "Tieng Viet + English" nhu tu truoc gio (KHONG doi hanh vi mac dinh
 * cua nguoi dung dang dung on dinh), nhung cho phep vao man Cai dat rieng
 * (SettingsActivity) de CHON LAI 2 trong so cac ngon ngu duoc ho tro - vuot
 * ngang tren phim cach van la thao tac chuyen doi qua lai GIUA 2 ngon ngu
 * DANG CHON, giong het co che VI/EN cu, chi khac la 2 ngon ngu do gio co the
 * la BAT KY 2 ngon ngu nao trong danh sach ben duoi (khong nhat thiet phai
 * co Tieng Viet).
 *
 * LUU Y QUAN TRONG ve gioi han ky thuat: app nay CHi co bo xu ly go dau THAT
 * SU (Telex) rieng cho DUY NHAT "vi" (Tieng Viet) - xem [QrKeyboardService]
 * (VietnameseTelex). Cac ngon ngu KHAC deu dung chung 1 kieu go THUONG/tran
 * (go ra dung phim vua bam, khong tu bien doi dau cau) - phu hop de go cac
 * ngon ngu dung bang chu Latin co ban (Anh, Phap, Y, Indonesia...) o muc "go
 * duoc binh thuong", nhung SE KHONG tu dong them dau phu (accent) rieng cua
 * tung ngon ngu do (vd tieng Phap "café" van phai tu go dau thu cong qua
 * phim giu lau tren cac ung dung/ban phim khac neu can - ngoai pham vi cua
 * app nay). Danh sach duoi day chi de PHAN LOAI/GAN NHAN cho ro rang trong
 * man Cai dat, khong dong nghia voi viec moi ngon ngu co bo go rieng.
 */
object LanguagePrefs {
    private const val PREFS_NAME = "qr_keyboard_language_prefs"
    private const val KEY_LANG_1 = "lang_1"
    private const val KEY_LANG_2 = "lang_2"

    const val DEFAULT_LANG_1 = "vi"
    const val DEFAULT_LANG_2 = "en"

    /** (ma ngon ngu, ten hien thi tieng Viet, nhan ngan hien tren phim cach).
     *  "vi" LUON o dau danh sach vi la ngon ngu DUY NHAT co bo go dau rieng. */
    val SUPPORTED_LANGUAGES: List<Triple<String, String, String>> = listOf(
        Triple("vi", "Ti\u1ebfng Vi\u1ec7t (Telex)", "VI"),
        Triple("en", "Ti\u1ebfng Anh", "EN"),
        Triple("fr", "Ti\u1ebfng Ph\u00e1p", "FR"),
        Triple("es", "Ti\u1ebfng T\u00e2y Ban Nha", "ES"),
        Triple("de", "Ti\u1ebfng \u0110\u1ee9c", "DE"),
        Triple("pt", "Ti\u1ebfng B\u1ed3 \u0110\u00e0o Nha", "PT"),
        Triple("it", "Ti\u1ebfng \u00dd", "IT"),
        Triple("id", "Ti\u1ebfng Indonesia", "ID"),
        Triple("ms", "Ti\u1ebfng M\u00e3 Lai", "MS"),
        Triple("tr", "Ti\u1ebfng Th\u1ed5 Nh\u0129 K\u1ef3", "TR")
    )

    fun displayName(code: String): String =
        SUPPORTED_LANGUAGES.firstOrNull { it.first == code }?.second ?: code

    fun shortLabel(code: String): String =
        SUPPORTED_LANGUAGES.firstOrNull { it.first == code }?.third ?: code.uppercase()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Tra ve (ma ngon ngu 1, ma ngon ngu 2) dang duoc chon - mac dinh
     *  ("vi","en") neu chua tung doi. */
    fun getSelectedLanguages(ctx: Context): Pair<String, String> {
        val p = prefs(ctx)
        val l1 = p.getString(KEY_LANG_1, DEFAULT_LANG_1) ?: DEFAULT_LANG_1
        val l2 = p.getString(KEY_LANG_2, DEFAULT_LANG_2) ?: DEFAULT_LANG_2
        return l1 to l2
    }

    fun setSelectedLanguages(ctx: Context, lang1: String, lang2: String) {
        prefs(ctx).edit()
            .putString(KEY_LANG_1, lang1)
            .putString(KEY_LANG_2, lang2)
            .apply()
    }
}
