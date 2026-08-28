package com.example.qrkeyboard

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            checkAndRoute()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRoute()
    }

    override fun onResume() {
        super.onResume()
        // Nếu đang hiện màn hình log, refresh log mỗi lần resume
        if (isShowingLog) showLogScreen()
    }

    private var isShowingLog = false

    private fun checkAndRoute() {
        // Nếu có log bàn phím tự đóng → hiện màn hình log thay vì đi thẳng Settings
        val prefs = getSharedPreferences("kb_hide_log", Context.MODE_PRIVATE)
        val log = prefs.getString("log", "")
        if (!log.isNullOrBlank()) {
            isShowingLog = true
            showLogScreen()
            return
        }
        // Không có log → flow bình thường
        routeNormal()
    }

    private fun showLogScreen() {
        val prefs = getSharedPreferences("kb_hide_log", Context.MODE_PRIVATE)
        val log = prefs.getString("log", "(chưa có log)") ?: "(chưa có log)"

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            setBackgroundColor(0xFF111111.toInt())
        }

        val title = TextView(this).apply {
            text = "🪲 Log bàn phím tự đóng"
            textSize = 18f
            setTextColor(0xFFFF6666.toInt())
            setPadding(0, 0, 0, 16)
        }

        val logView = TextView(this).apply {
            text = log
            textSize = 11f
            setTextColor(0xFFCCCCCC.toInt())
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val scroll = ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        scroll.addView(logView)

        val btnCopy = Button(this).apply {
            text = "📋 Copy toàn bộ log"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("kb_log", log))
                Toast.makeText(this@MainActivity, "Đã copy! Dán vào chat để gửi.", Toast.LENGTH_SHORT).show()
            }
        }

        val btnClear = Button(this).apply {
            text = "🗑 Xóa log"
            setOnClickListener {
                prefs.edit().remove("log").apply()
                isShowingLog = false
                routeNormal()
            }
        }

        val btnSettings = Button(this).apply {
            text = "⚙️ Mở cài đặt bàn phím"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }

        root.addView(title)
        root.addView(scroll)
        root.addView(btnCopy)
        root.addView(btnClear)
        root.addView(btnSettings)
        setContentView(root)
    }

    private fun routeNormal() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            finish()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}
