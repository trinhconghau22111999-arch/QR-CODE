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
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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

    companion object {
        /** THEM (theo yeu cau nguoi dung): key Intent extra - QrKeyboardService
         *  gan gia tri true khi mo man nay TU NUT "Cai dat" ngay ben trong
         *  ban phim, bao cho [onCreate] biet de BO QUA buoc kiem tra/chuyen
         *  huong sang trang chon ban phim he thong (chi thuc hien buoc do
         *  khi mo TU ICON APP tren man hinh chinh). */
        const val EXTRA_SKIP_KEYBOARD_CHECK = "extra_skip_keyboard_check"
    }

    private lateinit var colorSwatchContainer: LinearLayout
    private lateinit var themeToggleBtn: Button
    private lateinit var limitValueText: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var languageListContainer: LinearLayout
    private lateinit var languageStatusText: TextView
    /** THEM (theo yeu cau nguoi dung, kieu dau check): tap hop CAC MA NGON
     *  NGU dang duoc "tick" (chon) trong luc nguoi dung TUONG TAC voi phan
     *  Ngon ngu - dung LinkedHashSet de GIU DUNG THU TU tick (ngon ngu tick
     *  TRUOC la ngon ngu 1). Khoi tao tu 2 ngon ngu DA LUU san
     *  ([LanguagePrefs]). CHi thuc su GHI ("Luu") xuong [LanguagePrefs] khi
     *  tap hop nay co DUNG 2 phan tu tro lai (xem [toggleLanguage]) - trong
     *  luc chi co 0 hoac 1 ngon ngu duoc tick (nguoi dung vua bo tick 1/2 de
     *  chon lai), 2 ngon ngu DA LUU truoc do o [LanguagePrefs] VAN GIU
     *  NGUYEN khong doi (ban phim van dang dung dung cap ngon ngu CU cho
     *  toi khi nguoi dung tick du 2 ngon ngu MOI). */
    private lateinit var pendingSelectedLanguages: LinkedHashSet<String>
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

    /** THEM (theo yeu cau nguoi dung): mau nen THAT SU cua ban phim khi bat/
     *  tat nen Toi - PHAI khop CHINH XAC voi [keyboardBackgroundColor] trong
     *  QrKeyboardService.kt. Dung de nut chon Sang/Toi trong Cai dat HIEN
     *  THI DUNG mau nen ban phim thuc te, thay vi mot mau tim co dinh khong
     *  lien quan nhu truoc day - nguoi dung xem nut la thay ngay ket qua se
     *  giong nhu the nao tren ban phim. */
    private val keyboardBgDark = Color.parseColor("#050507")
    private val keyboardBgLight = Color.parseColor("#FAFAFA")

    private fun themeToggleBackground(borderColor: Int): GradientDrawable = GradientDrawable().apply {
        setColor(if (KeyboardThemePrefs.isDarkTheme(this@SettingsActivity)) keyboardBgDark else keyboardBgLight)
        cornerRadius = dp(10).toFloat()
        setStroke(dp(2), borderColor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // THEM (theo yeu cau nguoi dung): CHi kiem tra + chuyen huong sang
        // trang chon ban phim he thong khi mo TU ICON APP tren man hinh
        // chinh (KHONG co [EXTRA_SKIP_KEYBOARD_CHECK]) - khi mo TU NUT "Cai
        // dat" NGAY BEN TRONG ban phim thi BO QUA buoc nay hoan toan (co
        // [EXTRA_SKIP_KEYBOARD_CHECK] = true), vi ro rang ban phim DANG
        // duoc dung/da bat roi, kiem tra lai la thua va gay phien luc dang
        // go do.
        val skipKeyboardCheck = intent?.getBooleanExtra(EXTRA_SKIP_KEYBOARD_CHECK, false) ?: false
        if (!skipKeyboardCheck && !isKeyboardEnabled()) {
            try {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            } catch (e: Exception) {
                // Bo qua - khong de loi hiem gap o day chan nguoi dung.
            }
            // SUA LOI nguoi dung phan anh ("cai dat xong bam thoat ra bi
            // lap lai trang 1 cai roi moi thoat"): TRUOC DAY Activity nay
            // KHONG finish() o day - van con "song" duoi trang he thong vua
            // mo. Nguoi dung bat ban phim xong bam Back se QUAY LAI man Cai
            // dat nay (hien ra 1 lan nua), phai bam Back THEM 1 LAN NUA moi
            // thuc su thoat khoi app - "lap lai trang" dung nhu mo ta. SUA:
            // finish() NGAY va return - KHONG build giao dien Cai dat trong
            // truong hop nay - bam Back tu trang he thong se ra thang MAN
            // HINH CHINH/app truoc do, khong con quay lai day nua. Lan sau
            // mo lai app (da bat ban phim roi) se vao thang man Cai dat binh
            // thuong, khong con bi chuyen huong nua.
            finish()
            return
        }

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

        content.addView(buildLanguageSection())
        content.addView(spacer(24))
        content.addView(buildColorSection())
        content.addView(spacer(24))
        content.addView(buildRgbEffectSection())
        content.addView(spacer(24))
        content.addView(buildScanLimitSection())
        content.addView(spacer(24))
        content.addView(buildHistorySection())
    }

    /** THEM (theo yeu cau nguoi dung): kiem tra he thong DA bat Ban phim QR
     *  Keyboard trong danh sach ban phim duoc phep dung (Settings > He thong
     *  > Ban phim > Quan ly ban phim) hay CHUA - day la buoc RIENG, KHAC voi
     *  viec DA CHON no lam ban phim MAC DINH dang go (khong the/khong nen tu
     *  dong chuyen ban phim mac dinh thay nguoi dung). Boc try/catch phong
     *  ve - loi truy van (hiem gap) se coi nhu "da bat" (tra ve true) de
     *  KHONG chan nguoi dung vao man Cai dat vi 1 loi vat vanh. */
    private fun isKeyboardEnabled(): Boolean = try {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        val myImeId = "$packageName/${QrKeyboardService::class.java.name}"
        imm?.enabledInputMethodList?.any { it.id == myImeId } ?: true
    } catch (e: Exception) {
        true
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

    // ─────────────────── 0. Ngon ngu ban phim (1 hoac 2) ───────────────────

    /** THEM (theo yeu cau nguoi dung): chon 1 hoac 2 trong so cac ngon ngu
     *  duoc ho tro (xem [LanguagePrefs]) - neu chon 2, vuot ngang tren phim
     *  cach cua ban phim se chuyen doi qua lai GIUA 2 ngon ngu do, giong het
     *  co che VI/EN cu; neu CHi chon 1 (theo yeu cau nguoi dung "phải cho
     *  phép chỉ chọn 1 ngôn ngữ"), ban phim se CHi dung DUY NHAT ngon ngu do,
     *  khong con vuot phim cach de doi nua. MAC DINH van la Tieng Viet +
     *  Tieng Anh neu chua tung doi.
     *
     *  Cach chon (kieu dau check, theo dung yeu cau nguoi dung): moi ngon
     *  ngu la 1 dong co the TICK/BO TICK. Tick du 2 -> TU DONG LUU ngay. Tick
     *  DUNG 1 -> hien nut "Xac nhan chi dung 1 ngon ngu nay" de luu ngay o
     *  che do 1 ngon ngu (khong bat buoc phai chon them ngon ngu thu 2 nua).
     *  Muon doi 1 ngon ngu: BO TICH no truoc, roi TICK ngon ngu moi muon
     *  dung. KHONG the tick qua 2 ngon ngu cung luc (se bao can bo tick bot
     *  truoc). */
    private fun buildLanguageSection(): View {
        // SUA (lang2 gio co the null - dung 1 ngon ngu duy nhat): loc bo
        // phan tu null truoc khi dua vao LinkedHashSet<String> (khong con
        // dung Pair.toList() truc tiep nua vi no se tao List<String?> khi 1
        // trong 2 phan tu la nullable, khong khop kieu LinkedHashSet<String>).
        val (initialLang1, initialLang2) = LanguagePrefs.getSelectedLanguages(this)
        pendingSelectedLanguages = LinkedHashSet(listOfNotNull(initialLang1, initialLang2))

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        wrap.addView(sectionTitle("Ng\u00f4n ng\u1eef b\u00e0n ph\u00edm"))
        wrap.addView(sectionSubtitle(
            "M\u1eb7c \u0111\u1ecbnh Ti\u1ebfng Vi\u1ec7t + Ti\u1ebfng Anh nh\u01b0 c\u0169. C\u00f3 th\u1ec3 ch\u1ec9 ch\u1ecdn " +
            "1 ng\u00f4n ng\u1eef duy nh\u1ea5t (kh\u00f4ng c\u1ea7n v\u01b0\u1ee3t \u0111\u1ed5i), ho\u1eb7c \u0111\u00e1nh d\u1ea5u " +
            "2 ng\u00f4n ng\u1eef \u0111\u1ec3 v\u01b0\u1ee3t ngang tr\u00ean ph\u00edm c\u00e1ch chuy\u1ec3n \u0111\u1ed5i qua " +
            "l\u1ea1i gi\u1eefa 2 ng\u00f4n ng\u1eef \u0111\u00f3. Mu\u1ed1n \u0111\u1ed5i ng\u00f4n ng\u1eef: b\u1ecf d\u1ea5u " +
            "check ng\u00f4n ng\u1eef c\u0169 tr\u01b0\u1edbc r\u1ed3i ch\u1ecdn ng\u00f4n ng\u1eef m\u1edbi. Ch\u1ec9 ri\u00eang " +
            "Ti\u1ebfng Vi\u1ec7t c\u00f3 b\u1ed9 g\u00f5 d\u1ea5u Telex, c\u00e1c ng\u00f4n ng\u1eef kh\u00e1c g\u00f5 nh\u01b0 " +
            "b\u00ecnh th\u01b0\u1eddng."
        ))
        wrap.addView(spacer(10))

        languageStatusText = TextView(this).apply {
            setTextColor(accentNow)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        wrap.addView(languageStatusText)
        wrap.addView(spacer(10))

        languageListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(languageListContainer)
        renderLanguageRows()

        return wrap
    }

    private fun renderLanguageRows() {
        languageStatusText.text = when (pendingSelectedLanguages.size) {
            2 -> {
                val (a, b) = pendingSelectedLanguages.toList()
                "\u0110ang d\u00f9ng: ${LanguagePrefs.displayName(a)}  \u2194  ${LanguagePrefs.displayName(b)}"
            }
            // SUA (theo yeu cau nguoi dung "phải cho phép chỉ chọn 1 ngôn
            // ngữ"): TRUOC DAY chi tick 1 ngon ngu bi coi la "chua xong",
            // luon bat buoc phai tick THEM ngon ngu thu 2 moi luu duoc. GIO
            // cho phep DUNG LAI o 1 ngon ngu - hien dong chu XAC NHAN co the
            // bam de luu ngay (khong bat buoc chon them ngon ngu thu 2).
            1 -> "Ch\u1ec9 d\u00f9ng ${LanguagePrefs.displayName(pendingSelectedLanguages.first())} " +
                "(kh\u00f4ng vu\u1ed1t \u0111\u1ed5i ng\u00f4n ng\u1eef) - ho\u1eb7c ch\u1ecdn th\u00eam 1 ng\u00f4n " +
                "ng\u1eef n\u1eefa \u0111\u1ec3 vu\u1ed1t ph\u00edm c\u00e1ch \u0111\u1ed5i qua l\u1ea1i"
            else -> "H\u00e3y ch\u1ecdn \u00edt nh\u1ea5t 1 ng\u00f4n ng\u1eef"
        }

        languageListContainer.removeAllViews()
        LanguagePrefs.SUPPORTED_LANGUAGES.forEach { (code, name, _) ->
            val checked = pendingSelectedLanguages.contains(code)
            val row = TextView(this).apply {
                text = if (checked) "\u2611  $name" else "\u2610  $name"
                setTextColor(if (checked) accentNow else textSecondary)
                textSize = 15f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (checked) Color.parseColor("#221533") else Color.TRANSPARENT)
                    if (checked) setStroke(dp(1), accentNow)
                }
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                setOnClickListener { toggleLanguage(code) }
            }
            languageListContainer.addView(row)
        }

        // THEM: nut "Chi dung 1 ngon ngu nay" - CHi hien khi dung 1 ngon ngu
        // dang duoc tick (size==1), cho phep LUU NGAY o che do 1 ngon ngu ma
        // khong can tick them ngon ngu thu 2.
        if (pendingSelectedLanguages.size == 1) {
            val confirmBtn = TextView(this).apply {
                text = "\u2713  X\u00e1c nh\u1eadn ch\u1ec9 d\u00f9ng 1 ng\u00f4n ng\u1eef n\u00e0y"
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(accentNow)
                }
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                setOnClickListener {
                    val only = pendingSelectedLanguages.first()
                    LanguagePrefs.setSelectedLanguages(this@SettingsActivity, only, null)
                    Toast.makeText(
                        this@SettingsActivity,
                        "\u0110\u00e3 l\u01b0u: ch\u1ec9 d\u00f9ng ${LanguagePrefs.displayName(only)}",
                        Toast.LENGTH_SHORT
                    ).show()
                    renderLanguageRows()
                }
            }
            languageListContainer.addView(confirmBtn)
        }
    }

    private fun toggleLanguage(code: String) {
        if (pendingSelectedLanguages.contains(code)) {
            // Da duoc tick - BO TICH (cho phep giam ve 0 hoac 1, theo dung
            // yeu cau nguoi dung "bo check roi chon lai").
            pendingSelectedLanguages.remove(code)
        } else {
            if (pendingSelectedLanguages.size >= 2) {
                Toast.makeText(
                    this,
                    "Ch\u1ec9 \u0111\u01b0\u1ee3c ch\u1ecdn 2 ng\u00f4n ng\u1eef - b\u1ecf d\u1ea5u check 1 ng\u00f4n ng\u1eef tr\u01b0\u1edbc \u0111\u00e3",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            pendingSelectedLanguages.add(code)
        }
        // Du dung 2 tick - TU DONG LUU ngay (khong can nut "Luu" rieng).
        // Neu dang < 2 tick, KHONG ghi gi ca - 2 ngon ngu CU van con nguyen
        // trong LanguagePrefs cho toi khi nguoi dung tick du 2 ngon ngu MOI
        // (hoac bam nut "Xac nhan chi dung 1 ngon ngu" o che do 1 ngon ngu -
        // xem [renderLanguageRows]).
        if (pendingSelectedLanguages.size == 2) {
            val (a, b) = pendingSelectedLanguages.toList()
            LanguagePrefs.setSelectedLanguages(this, a, b)
        }
        renderLanguageRows()
    }

    // ─────────────────────────── 1. Mau sac ───────────────────────────

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
                    themeToggleBtn.background = themeToggleBackground(color)
                    // SUA (dong bo mau ngay lap tuc): muc "Ngon ngu" phia
                    // TREN cung dung [accentNow] de to mau cac dong ngon ngu
                    // DANG duoc tick - truoc day doi mau vien o day KHONG lam
                    // muc do ve lai, nen phai DOI qua lan mo Cai dat SAU moi
                    // thay dung mau moi, du mau THAT SU da luu dung ngay.
                    renderLanguageRows()
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
        // THEM (theo yeu cau nguoi dung): nen nut = DUNG mau nen ban phim
        // that (khong con mau tim co dinh) - chu cung doi Trang/Den theo,
        // giong het cach primaryTextColor() lam trong QrKeyboardService.kt,
        // de chu luon doc duoc tren nen moi.
        themeToggleBtn.background = themeToggleBackground(accentNow)
        themeToggleBtn.setTextColor(if (dark) Color.WHITE else Color.BLACK)
    }

    // ─────────────────── 1b. Hieu ung den RGB chay ───────────────────

    private lateinit var rgbToggleBtn: Button
    private lateinit var rgbDirectionRow: LinearLayout

    /** THEM (theo yeu cau nguoi dung): hieu ung "den RGB chay" tren vien
     *  phim, giong ban phim co gaming that. MAC DINH TAT (khong doi hanh vi
     *  nguoi dung dang quen, va vi hieu ung nay ton pin hon mau tinh binh
     *  thuong). Bat len se hien them 3 nut chon huong chay. */
    private fun buildRgbEffectSection(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        wrap.addView(sectionTitle("Hi\u1ec7u \u1ee9ng \u0111\u00e8n RGB ch\u1ea1y"))
        wrap.addView(sectionSubtitle(
            "M\u00e0u vi\u1ec1n ph\u00edm t\u1ef1 \u0111\u1ed9ng \u201cch\u1ea1y\u201d li\u00ean t\u1ee5c qua d\u1ea3i m\u00e0u c\u1ea7u v\u1ed3ng " +
            "(gi\u1ed1ng b\u00e0n ph\u00edm c\u01a1 gaming th\u1eadt). M\u1eb7c \u0111\u1ecbnh t\u1eaft (t\u1ed1n pin h\u01a1n m\u00e0u t\u0129nh b\u00ecnh th\u01b0\u1eddng)."
        ))
        wrap.addView(spacer(10))

        rgbToggleBtn = neonButton("", accentNow) { toggleRgbEffect() }
        wrap.addView(rgbToggleBtn)
        wrap.addView(spacer(10))

        rgbDirectionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wrap.addView(rgbDirectionRow)

        refreshRgbEffectUi()
        return wrap
    }

    private fun toggleRgbEffect() {
        RgbEffectPrefs.setEnabled(this, !RgbEffectPrefs.isEnabled(this))
        refreshRgbEffectUi()
    }

    private fun setRgbDirection(direction: String) {
        RgbEffectPrefs.setDirection(this, direction)
        refreshRgbEffectUi()
    }

    private fun refreshRgbEffectUi() {
        val enabled = RgbEffectPrefs.isEnabled(this)
        rgbToggleBtn.text = if (enabled) "\u2705  \u0110ang B\u1eacT hi\u1ec7u \u1ee9ng RGB ch\u1ea1y" else "\u26aa  \u0110ang T\u1eaeT hi\u1ec7u \u1ee9ng RGB ch\u1ea1y"
        rgbToggleBtn.setTextColor(textPrimary)

        rgbDirectionRow.removeAllViews()
        if (!enabled) return

        val currentDir = RgbEffectPrefs.getDirection(this)
        val directions = listOf(
            RgbEffectPrefs.DIRECTION_LEFT_TO_RIGHT,
            RgbEffectPrefs.DIRECTION_TOP_TO_BOTTOM,
            RgbEffectPrefs.DIRECTION_DIAGONAL
        )
        directions.forEachIndexed { i, dir ->
            val selected = dir == currentDir
            val btn = TextView(this).apply {
                text = RgbEffectPrefs.directionDisplayName(dir)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (selected) accentNow else textSecondary)
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (selected) Color.parseColor("#221533") else Color.TRANSPARENT)
                    if (selected) setStroke(dp(1), accentNow)
                }
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(6)
                }
                setOnClickListener { setRgbDirection(dir) }
            }
            rgbDirectionRow.addView(btn)
        }
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
                // SUA LOI (crash "app tu tat khi chuyen man"): TRUOC DAY dung
                // Uri.fromFile(file) - tao ra "file://" Uri. App nay target
                // API 34 (>= 24) nen he thong CAM TUYET DOI truyen loai Uri
                // nay qua Intent sang app khac (vd o nhanh "share" ngay ben
                // duoi ham nay) - se nem FileUriExposedException, CRASH APP
                // NGAY LAP TUC khi man hinh chia se cua he thong vua hien
                // len. Dung FileProvider (xem AndroidManifest.xml +
                // res/xml/file_paths.xml) de tao "content://" Uri AN TOAN
                // thay the - hoat dong dung tren MOI phien ban Android.
                androidx.core.content.FileProvider.getUriForFile(
                    this, "${packageName}.fileprovider", file
                )
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
