package com.example.qrkeyboard

import android.app.Application

/** THEM: Application class rieng cho ca app (truoc day KHONG khai bao, dung
 *  Application mac dinh trong suot cua Android) - CHi lam DUY NHAT 1 viec:
 *  cai [CrashReporter] NGAY LUC tien trinh app vua khoi dong, TRUOC CA
 *  Activity/Service dau tien duoc tao (Application.onCreate() luon chay
 *  som nhat trong vong doi tien trinh) - de bat duoc CA nhung loi xay ra
 *  rat som (vd ngay trong QrKeyboardService.onCreate() khi he thong vua
 *  bat ban phim len). Phai khai bao ten class nay trong AndroidManifest.xml
 *  (thuoc tinh android:name cua the <application>) thi Android moi biet
 *  dung class nay thay vi Application mac dinh. */
class QrKeyboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
