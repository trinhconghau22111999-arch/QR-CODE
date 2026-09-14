package com.example.qrkeyboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * THEM (theo yeu cau nguoi dung: "boc no lai khong cho no tat" - sau khi
 * log "onDestroy - service bị kill" cho thay ban phim VAN thinh thoang bi
 * He Dieu Hanh tu dong giet tien trinh nen, du da co [QrKeyboardService.onTrimMemory]/
 * [onLowMemory]).
 *
 * ĐÂY LÀ GÌ: 1 Foreground Service RIENG, TOI THIEU (khong lam gi ca ngoai
 * viec "ton tai" voi 1 thong bao uu tien THAP NHAT, gan nhu vo hinh) - theo
 * co che cua Android, Foreground Service LUON duoc xep vao nhom "quan
 * trong" cao hon nhieu so voi 1 Service/IME thong thuong chay nen, khien
 * TOAN BO TIEN TRINH (process) chua no - trong do co CA [QrKeyboardService]
 * neu chung cung 1 tien trinh, dung nhu truong hop nay - it bi Low Memory
 * Killer (LMK) cua He Dieu Hanh chon de giai phong RAM hon.
 *
 * GIOI HAN THAT SU (QUAN TRONG, khong "boc" duoc 100%): ky thuat nay CHi
 * giup ich voi kieu giet do THIEU RAM THAT SU (LMK tieu chuan cua Android)
 * - hoan toan KHONG giup duoc gi truoc cac trinh "toi uu pin" RIENG cua
 * mot so hang (MIUI/ColorOS/FuntouchOS/One UI...), vi nhung trinh do dung
 * DANH SACH/QUY TAC RIENG cua hang (vd "ung dung lau khong dung se bi dong")
 * HOAN TOAN NAM NGOAI co che chuan cua Android va tien trinh Foreground -
 * KHONG co ky thuat nao trong app co the ngan duoc kieu nay, CHi nguoi
 * dung tu tay loai tru trong Cai dat pin cua may (rieng) moi giai quyet
 * duoc trong truong hop do.
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "qr_keyboard_keepalive"
        private const val NOTIFICATION_ID = 4271

        /** Goi ham nay tu [QrKeyboardService] (vd trong onCreate()) de dam
         *  bao Service nay dang chay - an toan goi NHIEU LAN (Android tu
         *  bo qua neu da chay san, khong tao ban sao thu 2). */
        fun ensureRunning(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Bo qua - hiem gap (vd thieu quyen POST_NOTIFICATIONS tren
                // May chua cap, hoac gioi han nen cua may) - KHONG duoc lam
                // crash ca ban phim chi vi Service phu tro nay loi.
                android.util.Log.w("KeepAliveService", "Khong the bat: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QR Keyboard")
            .setContentText("Đang chạy nền để gõ không bị gián đoạn")
            .setSmallIcon(R.mipmap.ic_launcher)
            // Uu tien THAP NHAT - khong am thanh, khong rung, khong hien
            // len thanh 1 thong bao "moi" gay chu y, chi nam yen trong khay
            // thong bao neu nguoi dung tu keo xuong xem.
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            android.util.Log.w("KeepAliveService", "Khong the startForeground: ${e.message}")
            stopSelf()
        }
        // START_STICKY: neu Service nay (rieng no) bi He Dieu Hanh giet vi
        // thieu RAM, xin He Thong TU DONG khoi dong lai NGAY khi co du RAM
        // tro lai - giup "hoi phuc" nhanh hon la de no chet han.
        return START_STICKY
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Duy trì bàn phím",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Giữ bàn phím chạy ổn định, không tự động tắt giữa chừng"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
