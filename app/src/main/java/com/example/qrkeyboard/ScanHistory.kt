package com.example.qrkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Luu lai gioi han "so lan xuat du lieu ra LIEN TIEP GIONG HET NHAU cua 1
 *  ma QR/ma vach" - nguoi dung tu chinh trong man Cai dat (xem
 *  SettingsActivity.kt). KHONG anh huong gi toi ban Google Play (flavor
 *  "ggplay") - ban do luon BuildConfig.UNLIMITED_CONSECUTIVE_SCAN = true,
 *  bo qua han muc nay hoan toan (xem QrKeyboardService.processQrFrame). */
object ScanLimitPrefs {
    private const val PREFS = "qr_scan_limit_prefs"
    private const val KEY_LIMIT = "consecutive_limit"

    /** Mac dinh 2 lan lien tiep (theo dung yeu cau nguoi dung: "tối đa 2 lần
     *  liên tiếp thay vì 1 lần như hiện tại"). */
    const val DEFAULT_LIMIT = 2
    const val MIN_LIMIT = 1
    const val MAX_LIMIT = 20

    fun getConsecutiveLimit(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LIMIT, DEFAULT_LIMIT)

    fun setConsecutiveLimit(ctx: Context, value: Int) {
        val clamped = value.coerceIn(MIN_LIMIT, MAX_LIMIT)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LIMIT, clamped).apply()
    }
}

data class ScanEntry(val timestampMs: Long, val text: String)

/** Luu CUC BO (SharedPreferences, khong dong bo mang) toan bo du lieu quet
 *  duoc TRONG NGAY HOM NAY (gio + noi dung) - theo dung yeu cau nguoi dung:
 *  "lưu trữ dữ liệu quét ra trong ngày: giờ (của hôm nay), qua ngày thì tự
 *  xóa đi". Moi lan doc/ghi deu tu kiem tra xem da SANG NGAY MOI chua
 *  (so sanh voi ngay cua lan ghi gan nhat) - neu co, tu xoa sach du lieu cu
 *  truoc khi tiep tuc, KHONG can chay ngam/alarm rieng de don dep. */
object ScanHistoryStore {
    private const val PREFS = "qr_scan_history_prefs"
    private const val KEY_DAY = "day_key"
    private const val KEY_ENTRIES = "entries"

    private fun dayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Neu ngay hien tai KHAC ngay cua lan ghi gan nhat -> da qua ngay moi,
     *  tu xoa sach du lieu cu (chi giu du lieu quet DUNG TRONG HOM NAY). */
    private fun ensureFreshDay(ctx: Context) {
        val p = prefs(ctx)
        val today = dayKey()
        if (p.getString(KEY_DAY, null) != today) {
            p.edit().putString(KEY_DAY, today).putString(KEY_ENTRIES, "[]").apply()
        }
    }

    fun addEntry(ctx: Context, text: String) {
        ensureFreshDay(ctx)
        val p = prefs(ctx)
        val arr = readArray(p)
        arr.put(JSONObject().apply {
            put("t", System.currentTimeMillis())
            put("v", text)
        })
        p.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    /** Danh sach du lieu da quet HOM NAY, MOI NHAT truoc. */
    fun getTodayEntries(ctx: Context): List<ScanEntry> {
        ensureFreshDay(ctx)
        val arr = readArray(prefs(ctx))
        val list = mutableListOf<ScanEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(ScanEntry(o.optLong("t"), o.optString("v")))
        }
        return list.sortedByDescending { it.timestampMs }
    }

    private fun readArray(p: android.content.SharedPreferences): JSONArray = try {
        JSONArray(p.getString(KEY_ENTRIES, "[]"))
    } catch (e: Exception) {
        JSONArray()
    }
}
