package com.example.qrkeyboard

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** THEM (theo yeu cau nguoi dung: "van bi vang, them code de lan sau mo len
 *  hien trang loi"): ghi lai bat ky loi UNCAUGHT nao xay ra trong CA tien
 *  trinh app (ca Activity lan QrKeyboardService - hai thu nay mac dinh chay
 *  CHUNG 1 tien trinh he dieu hanh, nen 1 handler duy nhat cai o
 *  [QrKeyboardApp] la bat duoc TAT CA) ra mot file don gian trong bo nho
 *  noi bo cua app - de lan mo app KE TIEP (qua SettingsActivity, man
 *  LAUNCHER) co the doc lai va hien "trang loi" (hop thoai) cho nguoi dung
 *  xem. Ly do can co: da so nguoi dung KHONG co san may tinh + cap USB +
 *  Android Studio de tu lay logcat khi app vang - truoc day loi bay hoi
 *  hoan toan, khong con dau vet gi de dieu tra tiep. */
object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Cai dat 1 [Thread.UncaughtExceptionHandler] TOAN CUC, boc LAY (khong
     *  THAY THE han) handler mac dinh cua he thong - GHI LOG TRUOC, sau do
     *  van GIAO LAI cho handler mac dinh xu ly tiep (dong app, hien hop
     *  thoai "ung dung da dung" tieu chuan cua Android neu co) - dam bao
     *  KHONG thay doi hanh vi crash thuc te nguoi dung thay, CHi THEM buoc
     *  ghi lai truoc khi tien trinh bi dong. Goi 1 LAN DUY NHAT, cang som
     *  cang tot (xem [QrKeyboardApp.onCreate]) de bat duoc CA nhung loi xay
     *  ra rat som trong vong doi app. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(appContext, thread, throwable)
            } catch (e: Throwable) {
                // Danh chiu - TUYET DOI khong de chinh buoc GHI LOG gay ra
                // loi THEM (vd het bo nho luc ghi file) - du gi xay ra cung
                // phai roi xuong duoc dong duoi de giao lai cho handler mac
                // dinh, khong duoc "nuot" ca tien trinh xu ly crash goc.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val content = buildString {
            append("Thoi gian: ").append(timestamp).append('\n')
            append("Luong (thread): ").append(thread.name).append('\n')
            append('\n')
            append(sw.toString())
        }
        // Ghi DONG BO ngay trong chinh handler (khong day sang thread/
        // coroutine khac) - tien trinh sap bi he dieu hanh dong NGAY SAU KHI
        // handler nay return, nen PHAI ghi xong truoc khi return, neu khong
        // se mat du lieu (thread rieng co the chua kip chay xong da bi giet).
        file(context).writeText(content)
    }

    /** Doc lai loi crash GAN NHAT (neu co) - goi tu [SettingsActivity.onCreate].
     *  Tra ve null neu app chua tung crash lan nao, hoac nguoi dung da xem/
     *  bam "Da hieu" (xoa log) roi. */
    fun readLastCrash(context: Context): String? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            f.readText().ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Xoa log loi SAU KHI nguoi dung da xem (bam "Da hieu") - tranh hien
     *  lai CUNG 1 loi cu moi lan mo app ve sau, khi khong con crash moi nao
     *  xay ra them. */
    fun clearLastCrash(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
            // Bo qua - hiem gap, khong anh huong chuc nang chinh.
        }
    }
}
