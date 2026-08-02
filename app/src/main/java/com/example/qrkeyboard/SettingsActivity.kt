package com.example.qrkeyboard

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Man Cai dat CHINH THUC dau tien cua app (truoc day chi co MainActivity di
 * thang toi Cai dat he thong, KHONG co man cai dat rieng nao ca). Duoc mo tu
 * nut "Cai dat" moi tren trang Ky hieu cua ban phim (xem
 * QrKeyboardService.buildKeyboardSettingsBar).
 *
 * Gom 3 muc theo dung yeu cau nguoi dung:
 *   1. "Mau sac" - CHUYEN HAN tu thanh chon mau von nam ngay tren ban phim
 *      (xem KeyboardThemePrefs.kt - noi luu du lieu DUNG CHUNG voi ban phim).
 *   2. "Gioi han quet trung lap" - so lan toi da cho phep xuat ra CUNG 1 du
 *      lieu quet LIEN TIEP (xem ScanLimitPrefs.kt). An/khoa o ban Google Play
 *      (flavor "ggplay") vi ban do KHONG gioi han (BuildConfig.UNLIMITED_CONSECUTIVE_SCAN).
 *   3. "Du lieu quet hom nay" - xem lai + xuat Excel + chia se du lieu da
 *      quet trong ngay hom nay (xem ScanHistoryStore.kt + XlsxWriter.kt).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var colorSwatchContainer: LinearLayout
    private lateinit var themeToggleBtn: Button
    private lateinit var limitValueText: TextView
    private lateinit var historyContainer: LinearLayout
    private var historyPanelOpen = false
    private var pendingExportAction: (() -> Unit)? = null

    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingExportAction?.invoke()
        else Toast.makeText(this, "Cần quyền Lưu trữ để xuất file Excel", Toast.LENGTH_LONG).show()
        pendingExportAction = null
    }

    // ── Bang mau nen/chu dung chung cho toan man hinh (tim neon tren nen den) ──
    private val bgColor = Color.parseColor("#0A0510")
    private val cardColor = Color.parseColor("#150A22")
    private val textPrimary = Color.WHITE
    private val textSecondary = Color.parseColor("#B9A8D0")
    private val accentNow get() = KeyboardThemePrefs.getAccentColor(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        root.addView(content)
        setContentView(root)

        content.addView(sectionTitle("C\u00e0i \u0111\u1eb7t QR Keyboard", big = true))
        content.addView(spacer(20))

        content.addView(buildColorSection())
        content.addView(spacer(24))
        content.addView(buildScanLimitSection())
        content.addView(spacer(24))
        content.addView(buildHistorySection())
    }

    override fun onResume() {
        super.onResume()
        refreshLimitText()
    }

    // ───────────────────────── Tien ich UI ─────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h))
    }

    private fun sectionTitle(text: String, big: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        setTextColor(textPrimary)
        textSize = if (big) 24f else 17f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun sectionSubtitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(textSecondary)
        textSize = 13f
        setPadding(0, dp(4), 0, dp(10))
    }

    private fun cardBackground(borderColor: Int = Color.parseColor("#2A1A40")): GradientDrawable =
        GradientDrawable().apply {
            setColor(cardColor)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), borderColor)
        }

    private fun neonButton(text: String, borderColor: Int, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(textPrimary)
        textSize = 14f
        stateListAnimator = null
        elevation = 0f
        outlineProvider = null
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1C0F2E"))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(2), borderColor)
        }
        setPadding(dp(18), dp(10), dp(18), dp(10))
        setOnClickListener { onClick() }
    }

    // ───────────────────────── 1. Mau sac ─────────────────────────

    private fun buildColorSection(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        wrap.addView(sectionTitle("M\u00e0u s\u1eafc"))
        wrap.addView(sectionSubtitle("Ch\u1ecdn m\u00e0u vi\u1ec1n b\u00e0n ph\u00edm v\u00e0 giao di\u1ec7n s\u00e1ng/t\u1ed1i - \u00e1p d\u1ee5ng ngay cho b\u00e0n ph\u00edm, kh\u00f4ng c\u1ea7n kh\u1edfi \u0111\u1ed9ng l\u1ea1i."))

        colorSwatchContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(colorSwatchContainer)
        }
        wrap.addView(scroller)
        renderColorSwatches()

        wrap.addView(spacer(14))
        themeToggleBtn = neonButton("", accentNow) { toggleTheme() }
        refreshThemeToggleLabel()
        wrap.addView(themeToggleBtn)

        return wrap
    }

    private fun renderColorSwatches() {
        colorSwatchContainer.removeAllViews()
        val current = accentNow
        val size = dp(44)
        KeyboardThemePrefs.ACCENT_COLORS.forEach { color ->
            val isSelected = color == current
            val swatch = Button(this).apply {
                text = ""
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                stateListAnimator = null
                elevation = 0f
                outlineProvider = null
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(if (isSelected) 4 else 1), if (isSelected) Color.WHITE else Color.parseColor("#40FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(dp(6), dp(4), dp(6), dp(4)) }
                setOnClickListener {
                    KeyboardThemePrefs.setAccentColor(this@SettingsActivity, color)
                    renderColorSwatches()
                    themeToggleBtn.background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1C0F2E"))
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(2), color)
                    }
                }
            }
            colorSwatchContainer.addView(swatch)
        }
    }

    private fun toggleTheme() {
        val newDark = !KeyboardThemePrefs.isDarkTheme(this)
        KeyboardThemePrefs.setDarkTheme(this, newDark)
        refreshThemeToggleLabel()
    }

    private fun refreshThemeToggleLabel() {
        val dark = KeyboardThemePrefs.isDarkTheme(this)
        themeToggleBtn.text = if (dark) "\ud83c\udf19  \u0110ang d\u00f9ng n\u1ec1n T\u1ed1i" else "\u2600\ufe0f  \u0110ang d\u00f9ng n\u1ec1n S\u00e1ng"
    }

    // ─────────────────── 2. Gioi han quet trung lap ───────────────────

    private fun buildScanLimitSection(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        wrap.addView(sectionTitle("Gi\u1edbi h\u1ea1n qu\u00e9t tr\u00f9ng l\u1eb7p"))

        if (BuildConfig.UNLIMITED_CONSECUTIVE_SCAN) {
            wrap.addView(sectionSubtitle(
                "B\u1ea3n Google Play kh\u00f4ng gi\u1edbi h\u1ea1n - m\u1ed7i m\u00e3 QR/m\u00e3 v\u1ea1ch quét \u0111\u01b0\u1ee3c s\u1ebd lu\u00f4n \u0111\u01b0\u1ee3c xu\u1ea5t d\u1eef li\u1ec7u, quét li\u00ean t\u1ee5c c\u00f9ng 1 m\u00e3 bao nhi\u00eau l\u1ea7n c\u0169ng \u0111\u01b0\u1ee3c."
            ))
            return wrap
        }

        wrap.addView(sectionSubtitle(
            "Khi qu\u00e9t li\u00ean t\u1ee5c c\u00f9ng 1 m\u00e3 QR/m\u00e3 v\u1ea1ch nhi\u1ec1u l\u1ea7n li\u1ec1n nhau, ch\u1ec9 xu\u1ea5t d\u1eef li\u1ec7u t\u1ed1i \u0111a s\u1ed1 l\u1ea7n \u0111\u1eb7t d\u01b0\u1edbi \u0111\u00e2y r\u1ed3i t\u1ef1 d\u1eebng (qu\u00e9t m\u00e3 KH\u00c1C thì \u0111\u1ebfm l\u1ea1i t\u1eeb \u0111\u1ea7u)."
        ))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minusBtn = neonButton("\u2212", accentNow) { adjustLimit(-1) }
        val plusBtn = neonButton("+", accentNow) { adjustLimit(1) }
        limitValueText = TextView(this).apply {
            setTextColor(textPrimary)
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(minusBtn)
        row.addView(limitValueText)
        row.addView(plusBtn)
        wrap.addView(row)
        refreshLimitText()

        return wrap
    }

    private fun adjustLimit(delta: Int) {
        val cur = ScanLimitPrefs.getConsecutiveLimit(this)
        ScanLimitPrefs.setConsecutiveLimit(this, cur + delta)
        refreshLimitText()
    }

    private fun refreshLimitText() {
        if (!::limitValueText.isInitialized) return
        limitValueText.text = ScanLimitPrefs.getConsecutiveLimit(this).toString()
    }

    // ─────────────────── 3. Du lieu quet hom nay (Excel) ───────────────────

    private fun buildHistorySection(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        wrap.addView(sectionTitle("D\u1eef li\u1ec7u qu\u00e9t h\u00f4m nay"))
        wrap.addView(sectionSubtitle(
            "L\u01b0u c\u1ee5c b\u1ed9 tr\u00ean m\u00e1y (kh\u00f4ng g\u1eedi \u0111i \u0111\u00e2u c\u1ea3) gi\u1edd + n\u1ed9i dung m\u1ed7i l\u1ea7n qu\u00e9t \u0111\u01b0\u1ee3c trong h\u00f4m nay - qua ng\u00e0y m\u1edbi t\u1ef1 x\u00f3a."
        ))

        val toggleBtn = neonButton("Xem d\u1eef li\u1ec7u h\u00f4m nay", accentNow) {}
        wrap.addView(toggleBtn)

        historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        wrap.addView(historyContainer)

        toggleBtn.setOnClickListener {
            historyPanelOpen = !historyPanelOpen
            historyContainer.visibility = if (historyPanelOpen) View.VISIBLE else View.GONE
            toggleBtn.text = if (historyPanelOpen) "\u1ea8n d\u1eef li\u1ec7u h\u00f4m nay" else "Xem d\u1eef li\u1ec7u h\u00f4m nay"
            if (historyPanelOpen) renderHistoryPanel()
        }

        return wrap
    }

    private fun renderHistoryPanel() {
        historyContainer.removeAllViews()
        historyContainer.addView(spacer(12))

        val entries = ScanHistoryStore.getTodayEntries(this)
        if (entries.isEmpty()) {
            historyContainer.addView(TextView(this).apply {
                text = "Ch\u01b0a qu\u00e9t m\u00e3 n\u00e0o trong h\u00f4m nay."
                setTextColor(textSecondary)
                textSize = 14f
            })
            return
        }

        val fmt = SimpleDateFormat("HH:mm:ss", Locale("vi", "VN"))
        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F081A"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        entries.take(200).forEach { e ->
            listBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
                addView(TextView(this@SettingsActivity).apply {
                    text = fmt.format(Date(e.timestampMs))
                    setTextColor(textSecondary)
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = e.text
                    setTextColor(textPrimary)
                    textSize = 13f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
        }
        historyContainer.addView(listBox)
        historyContainer.addView(spacer(12))
        historyContainer.addView(TextView(this).apply {
            text = "T\u1ed5ng c\u1ed9ng: ${entries.size} m\u00e3 \u0111\u00e3 qu\u00e9t h\u00f4m nay"
            setTextColor(textSecondary)
            textSize = 12f
        })
        historyContainer.addView(spacer(12))

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(neonButton("Xu\u1ea5t Excel", accentNow) { runExportFlow(share = false) })
        btnRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 0) })
        btnRow.addView(neonButton("Chia s\u1ebb Excel", accentNow) { runExportFlow(share = true) })
        historyContainer.addView(btnRow)
    }

    private fun runExportFlow(share: Boolean) {
        val entries = ScanHistoryStore.getTodayEntries(this)
        if (entries.isEmpty()) {
            Toast.makeText(this, "Ch\u01b0a c\u00f3 d\u1eef li\u1ec7u \u0111\u1ec3 xu\u1ea5t", Toast.LENGTH_SHORT).show()
            return
        }
        val action = { doExport(entries, share) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExportAction = action
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        action()
    }

    /** Xuat du lieu quet hom nay ra file .xlsx that (khong dung Apache POI -
     *  xem XlsxWriter.kt). [share] = true: mo them menu chia se ngay sau khi
     *  xuat xong; false: chi luu vao thu muc Downloads va bao vi tri. */
    private fun doExport(entries: List<ScanEntry>, share: Boolean) {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale("vi", "VN"))
        val rows = mutableListOf(listOf("Gi\u1edd qu\u00e9t", "D\u1eef li\u1ec7u qu\u00e9t \u0111\u01b0\u1ee3c"))
        entries.sortedBy { it.timestampMs }.forEach { e ->
            rows.add(listOf(fmt.format(Date(e.timestampMs)), e.text))
        }

        val fileName = "QR_Keyboard_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.xlsx"
        val uri: Uri? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val outUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (outUri != null) {
                    contentResolver.openOutputStream(outUri)?.use { XlsxWriter.write(it, "QuetHomNay", rows) }
                }
                outUri
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { XlsxWriter.write(it, "QuetHomNay", rows) }
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            null
        }

        if (uri == null) {
            Toast.makeText(this, "Kh\u00f4ng xu\u1ea5t \u0111\u01b0\u1ee3c file Excel", Toast.LENGTH_SHORT).show()
            return
        }

        if (share) {
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Chia s\u1ebb file Excel"))
            } catch (e: Exception) {
                Toast.makeText(this, "Kh\u00f4ng th\u1ec3 m\u1edf menu chia s\u1ebb", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "\u0110\u00e3 l\u01b0u v\u00e0o Downloads/$fileName", Toast.LENGTH_LONG).show()
        }
    }
}
