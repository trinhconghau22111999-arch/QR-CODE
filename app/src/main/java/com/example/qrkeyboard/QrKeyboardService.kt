package com.example.qrkeyboard

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class QrKeyboardService : InputMethodService() {

    private var isShifted = false
    private var isSymbols = false
    private val letterButtons = mutableListOf<Button>()

    // Lop phu (noi) de hien thi bong bong "noi phim" phia tren phim dang nhan
    private lateinit var overlayContainer: FrameLayout
    private val locAnchor = IntArray(2)
    private val locOverlay = IntArray(2)

    // ----- Trang thai bo go Telex (tieng Viet co dau) -----
    private val currentWordRaw = mutableListOf<Char>()
    private var currentRenderedLength = 0

    // ----- Bang mau giao dien toi (Gboard-style) -----
    private val colorBg = Color.parseColor("#202124")
    private val colorKeyNormal = Color.parseColor("#303134")
    private val colorKeyPressed = Color.parseColor("#48494D")
    private val colorKeySpecial = Color.parseColor("#2B2C2F")
    private val colorKeySpecialPressed = Color.parseColor("#3E3F43")
    private val colorAccent = Color.parseColor("#4C8DF6")
    private val colorAccentPressed = Color.parseColor("#6FA3FF")
    private val colorTextPrimary = Color.parseColor("#E8EAED")
    private val colorPopupBg = Color.parseColor("#46474B")

    companion object {
        private var activeService: QrKeyboardService? = null
        private var pendingScanResult: String? = null

        /** QrScanActivity goi ham nay khi quet duoc ma. Ket qua duoc luu tam,
         *  se duoc dien vao o nhap lieu ngay khi ban phim ket noi lai (onStartInputView). */
        fun deliverScanResult(text: String) {
            // QUAN TRONG: KHONG duoc commitText() ngay tai day.
            // Ly do: luc ham nay chay, QrScanActivity dang che man hinh nen o nhap lieu
            // goc DA MAT FOCUS -> InputConnection cu (svc.currentInputConnection) tren
            // thuc te da "chet" (finished), nhung no van tra ve mot object KHAC null.
            // => neu kiem tra "ic != null" roi goi commitText(), lenh nay se am tham
            // khong co tac dung gi (khong loi, khong insert duoc chu), va vi ic != null
            // nen nhanh du phong luu pendingScanResult cung khong duoc chay -> mat chu.
            //
            // Giai phap: luon luu ket qua vao pendingScanResult, roi de onStartInputView()
            // (chay khi ban phim THAT SU ket noi lai voi o nhap sau khi Activity dong)
            // dam nhan viec dien chu. Cach nay dam bao chu luon duoc dien dung luc.
            pendingScanResult = text

            val svc = activeService
            if (svc != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    svc.requestShowSelf(0)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeService === this) activeService = null
    }

    override fun onCreateInputView(): View {
        return buildKeyboardView()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Moi lan chuyen sang o nhap khac, reset bo dem tu dang go
        resetTelexWord()

        // Neu vua co ket qua quet QR dang cho, dien vao o nhap lieu ngay luc nay
        val result = pendingScanResult
        if (result != null) {
            pendingScanResult = null
            currentInputConnection?.commitText(result, 1)
            sendEnter()
        }
    }

    /** Cho phep goi requestShowSelf (protected) tu companion object. */
    fun triggerShowKeyboard() {
        requestShowSelf(0)
    }

    // ----------------- Xu ly ket qua quet QR -----------------

    private fun openQrScanner() {
        val intent = Intent(this, QrScanActivity::class.java)
        // FLAG_ACTIVITY_MULTIPLE_TASK + taskAffinity="" (trong manifest) dam bao
        // QrScanActivity luon mo trong 1 task rieng, tam thoi. Neu thieu 2 thu nay,
        // Android co the gop QrScanActivity vao task cua chinh app QrKeyboard (vi
        // trung taskAffinity mac dinh), khien sau khi finish() man hinh quay ve
        // MainActivity/task cu cua app nay thay vi quay dung ve o nhap lieu goc
        // -> chu quet duoc se KHONG duoc dien vao dau ca.
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        )
        startActivity(intent)
    }

    // ----------------- Dung ban phim bang code -----------------

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun dpF(value: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)

    private fun buildKeyboardView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        content.addView(buildTopRow())

        letterButtons.clear()
        content.addView(buildRow(currentRow1()))
        content.addView(buildRow(currentRow2(), sideMargin = dp(16)))
        content.addView(buildRow3())

        content.addView(buildBottomRow())

        val root = FrameLayout(this).apply {
            setBackgroundColor(colorBg)
            clipChildren = false
            clipToPadding = false
        }
        root.addView(
            content,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )

        overlayContainer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        root.addView(
            overlayContainer,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        return root
    }

    private fun currentRow1(): List<String> =
        if (isSymbols) listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        else "qwertyuiop".map { it.toString() }

    private fun currentRow2(): List<String> =
        if (isSymbols) listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/")
        else "asdfghjkl".map { it.toString() }

    private fun currentRow3Letters(): List<String> =
        if (isSymbols) listOf("*", "\"", "'", ":", ";", "!", "?")
        else "zxcvbnm".map { it.toString() }

    private fun buildTopRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, dp(8))
        }

        val qrBtn = makeKey("[QR] Qu\u00e9t m\u00e3", weight = 2f, isSpecial = true, enablePopup = false)
        qrBtn.background = roundedDrawable(colorAccent, colorAccentPressed, dpF(8))
        qrBtn.setTextColor(Color.WHITE)
        qrBtn.setOnClickListener { openQrScanner() }

        val globeBtn = makeKey("\ud83c\udf10", weight = 1f, isSpecial = true, enablePopup = false)
        globeBtn.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        row.addView(qrBtn)
        row.addView(globeBtn)
        return row
    }

    private fun buildRow(keys: List<String>, sideMargin: Int = 0): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(sideMargin, 0, sideMargin, dp(6)) }
        }
        keys.forEach { label ->
            val btn = makeKey(displayLabel(label))
            btn.setOnClickListener { onKeyPressed(label) }
            if (!isSymbols) letterButtons.add(btn)
            row.addView(btn)
        }
        return row
    }

    private fun buildRow3(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(6)) }
        }

        val shiftBtn = makeKey("\u21e7", weight = 1.5f, isSpecial = true, enablePopup = false)
        shiftBtn.setOnClickListener {
            if (!isSymbols) {
                isShifted = !isShifted
                refreshLetterCase()
            }
        }
        row.addView(shiftBtn)

        currentRow3Letters().forEach { label ->
            val btn = makeKey(displayLabel(label))
            btn.setOnClickListener { onKeyPressed(label) }
            if (!isSymbols) letterButtons.add(btn)
            row.addView(btn)
        }

        val backspaceBtn = makeKey("\u232b", weight = 1.5f, isSpecial = true, enablePopup = false)
        backspaceBtn.setOnClickListener { onBackspacePressed() }
        row.addView(backspaceBtn)

        return row
    }

    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val symBtn = makeKey(if (isSymbols) "ABC" else "123", weight = 1.5f, isSpecial = true, enablePopup = false)
        symBtn.setOnClickListener {
            resetTelexWord()
            isSymbols = !isSymbols
            setInputView(buildKeyboardView())
        }
        row.addView(symBtn)

        val commaBtn = makeKey(",", weight = 1f)
        commaBtn.setOnClickListener {
            resetTelexWord()
            currentInputConnection?.commitText(",", 1)
        }
        row.addView(commaBtn)

        val spaceBtn = makeKey("kho\u1ea3ng c\u00e1ch", weight = 4f, enablePopup = false)
        spaceBtn.setOnClickListener {
            resetTelexWord()
            currentInputConnection?.commitText(" ", 1)
        }
        row.addView(spaceBtn)

        val periodBtn = makeKey(".", weight = 1f)
        periodBtn.setOnClickListener {
            resetTelexWord()
            currentInputConnection?.commitText(".", 1)
        }
        row.addView(periodBtn)

        val enterBtn = makeKey("\u23ce", weight = 1.5f, isSpecial = true, enablePopup = false)
        enterBtn.background = roundedDrawable(colorAccent, colorAccentPressed, dpF(8))
        enterBtn.setTextColor(Color.WHITE)
        enterBtn.setOnClickListener {
            resetTelexWord()
            sendEnter()
        }
        row.addView(enterBtn)

        return row
    }

    private fun makeKey(
        label: String,
        weight: Float = 1f,
        isSpecial: Boolean = false,
        enablePopup: Boolean = true
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = if (isSpecial) 15f else 18f
            setTypeface(typeface, if (isSpecial) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(colorTextPrimary)
            background = if (isSpecial) {
                roundedDrawable(colorKeySpecial, colorKeySpecialPressed, dpF(8))
            } else {
                roundedDrawable(colorKeyNormal, colorKeyPressed, dpF(8))
            }
            stateListAnimator = null
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply {
                setMargins(dp(3), dp(2), dp(3), dp(2))
            }
            attachPressEffect(this, enablePopup)
        }
    }

    /** Bo goc mem + doi mau khi nhan, dung chung cho moi phim. */
    private fun roundedDrawable(colorNormal: Int, colorPressed: Int, radius: Float): Drawable {
        val normal = GradientDrawable().apply {
            cornerRadius = radius
            setColor(colorNormal)
        }
        val pressed = GradientDrawable().apply {
            cornerRadius = radius
            setColor(colorPressed)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    /** Them rung nhe (haptic) + hieu ung "noi phim" (phong to + nhac len) khi nhan giu,
     *  va bong bong xem truoc ky tu phia tren phim (kieu Gboard). */
    private fun attachPressEffect(button: Button, enablePopup: Boolean) {
        button.isHapticFeedbackEnabled = true
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                    )
                    v.animate()
                        .scaleX(1.12f)
                        .scaleY(1.12f)
                        .translationY(-dpF(2))
                        .setDuration(45)
                        .start()
                    if (enablePopup) showKeyPopup(v as Button)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .setDuration(90)
                        .start()
                    if (enablePopup) hideKeyPopup(v)
                }
            }
            false // khong tieu thu su kien -> onClickListener van hoat dong binh thuong
        }
    }

    /** Hien bong bong ky tu phong to ngay phia tren phim dang duoc nhan. */
    private fun showKeyPopup(anchor: Button) {
        if (!::overlayContainer.isInitialized) return

        anchor.getLocationOnScreen(locAnchor)
        overlayContainer.getLocationOnScreen(locOverlay)
        val relX = locAnchor[0] - locOverlay[0]
        val relY = locAnchor[1] - locOverlay[1]

        val keyW = anchor.width
        val keyH = anchor.height
        val popupW = keyW.coerceAtLeast(dp(40))
        val popupH = (keyH * 1.9f).toInt()

        val popup = TextView(this).apply {
            text = anchor.text
            isAllCaps = false
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedDrawable(colorPopupBg, colorPopupBg, dpF(10))
            elevation = dpF(6)
            alpha = 0f
            scaleX = 0.7f
            scaleY = 0.7f
        }
        val lp = FrameLayout.LayoutParams(popupW, popupH).apply {
            leftMargin = relX - (popupW - keyW) / 2
            topMargin = relY - popupH + keyH - dp(2)
        }
        overlayContainer.addView(popup, lp)
        popup.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(60).start()

        anchor.tag = popup
    }

    private fun hideKeyPopup(anchor: View) {
        val popup = anchor.tag as? View ?: return
        anchor.tag = null
        popup.animate()
            .alpha(0f)
            .scaleX(0.75f)
            .scaleY(0.75f)
            .setDuration(80)
            .withEndAction { overlayContainer.removeView(popup) }
            .start()
    }

    private fun displayLabel(label: String): String =
        if (!isSymbols && isShifted) label.uppercase() else label

    private fun refreshLetterCase() {
        letterButtons.forEach { btn ->
            val current = btn.text.toString()
            btn.text = if (isShifted) current.uppercase() else current.lowercase()
        }
    }

    // ----------------- Xu ly phim bam (co ho tro Telex) -----------------

    private fun onKeyPressed(label: String) {
        if (isSymbols) {
            // Hang so/ky tu: go thang, khong qua bo go Telex
            resetTelexWord()
            currentInputConnection?.commitText(label, 1)
            return
        }

        val typedChar = if (isShifted) label.uppercase()[0] else label.lowercase()[0]
        currentWordRaw.add(typedChar)

        val newRendered = telexTransform(currentWordRaw)
        if (currentRenderedLength > 0) {
            currentInputConnection?.deleteSurroundingText(currentRenderedLength, 0)
        }
        currentInputConnection?.commitText(newRendered, 1)
        currentRenderedLength = newRendered.length

        if (isShifted) {
            isShifted = false
            refreshLetterCase()
        }
    }

    private fun onBackspacePressed() {
        if (currentWordRaw.isNotEmpty()) {
            currentWordRaw.removeAt(currentWordRaw.size - 1)
            val newRendered = telexTransform(currentWordRaw)
            if (currentRenderedLength > 0) {
                currentInputConnection?.deleteSurroundingText(currentRenderedLength, 0)
            }
            if (newRendered.isNotEmpty()) {
                currentInputConnection?.commitText(newRendered, 1)
            }
            currentRenderedLength = newRendered.length
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun resetTelexWord() {
        currentWordRaw.clear()
        currentRenderedLength = 0
    }

    /** Gui to hop Alt+Enter (xuong dong) thay vi Enter thuong (hay bi app hieu la "gui/submit").
     *  KHONG dung performEditorAction() vi no bo qua trang thai Alt va luon submit form
     *  bat ke co giu Alt hay khong. */
    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val altMeta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 0, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, altMeta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, altMeta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 0, 0))
    }

    // ----------------- Dong co bo go Telex tieng Viet -----------------

    private data class CChar(val ch: Char, val upper: Boolean)

    private val vowelTable: Map<Char, CharArray> = mapOf(
        'a' to charArrayOf('a', '\u00e1', '\u00e0', '\u1ea3', '\u00e3', '\u1ea1'),
        '\u0103' to charArrayOf('\u0103', '\u1eaf', '\u1eb1', '\u1eb3', '\u1eb5', '\u1eb7'),
        '\u00e2' to charArrayOf('\u00e2', '\u1ea5', '\u1ea7', '\u1ea9', '\u1eab', '\u1ead'),
        'e' to charArrayOf('e', '\u00e9', '\u00e8', '\u1ebb', '\u1ebd', '\u1eb9'),
        '\u00ea' to charArrayOf('\u00ea', '\u1ebf', '\u1ec1', '\u1ec3', '\u1ec5', '\u1ec7'),
        'i' to charArrayOf('i', '\u00ed', '\u00ec', '\u1ec9', '\u0129', '\u1ecb'),
        'o' to charArrayOf('o', '\u00f3', '\u00f2', '\u1ecf', '\u00f5', '\u1ecd'),
        '\u00f4' to charArrayOf('\u00f4', '\u1ed1', '\u1ed3', '\u1ed5', '\u1ed7', '\u1ed9'),
        '\u01a1' to charArrayOf('\u01a1', '\u1edb', '\u1edd', '\u1edf', '\u1ee1', '\u1ee3'),
        'u' to charArrayOf('u', '\u00fa', '\u00f9', '\u1ee7', '\u0169', '\u1ee5'),
        '\u01b0' to charArrayOf('\u01b0', '\u1ee9', '\u1eeb', '\u1eed', '\u1eef', '\u1ef1'),
        'y' to charArrayOf('y', '\u00fd', '\u1ef3', '\u1ef7', '\u1ef9', '\u1ef5')
    )

    private val charToVowelInfo: Map<Char, Pair<Char, Int>> by lazy {
        val map = mutableMapOf<Char, Pair<Char, Int>>()
        vowelTable.forEach { (base, arr) ->
            arr.forEachIndexed { idx, c -> map[c] = base to idx }
        }
        map
    }

    private fun isVowelChar(c: Char): Boolean = charToVowelInfo.containsKey(c)

    /** Chuyen 1 chuoi phim tho (raw keystrokes) thanh chu tieng Viet theo kieu go Telex. */
    private fun telexTransform(rawKeys: List<Char>): String {
        if (rawKeys.isEmpty()) return ""

        val lower = rawKeys.map { it.lowercaseChar() }
        val upperFlags = rawKeys.map { it.isUpperCase() }

        // Buoc 1: ghep cap ky tu dac biet (aa->\u00e2, aw->\u0103, ee->\u00ea, oo->\u00f4, ow->\u01a1, uw/w->\u01b0, dd->\u0111)
        val stage1 = mutableListOf<CChar>()
        var i = 0
        while (i < lower.size) {
            if (i + 1 < lower.size) {
                val pair = "" + lower[i] + lower[i + 1]
                val replacement = when (pair) {
                    "aa" -> '\u00e2'
                    "aw" -> '\u0103'
                    "ee" -> '\u00ea'
                    "oo" -> '\u00f4'
                    "ow" -> '\u01a1'
                    "uw" -> '\u01b0'
                    "dd" -> '\u0111'
                    else -> null
                }
                if (replacement != null) {
                    stage1.add(CChar(replacement, upperFlags[i] || upperFlags[i + 1]))
                    i += 2
                    continue
                }
            }
            if (lower[i] == 'w') {
                stage1.add(CChar('\u01b0', upperFlags[i]))
            } else {
                stage1.add(CChar(lower[i], upperFlags[i]))
            }
            i++
        }

        // Buoc 2: ap dau thanh dua vao ky tu kich hoat cuoi cung (s/f/r/x/j, z de xoa dau)
        if (stage1.isNotEmpty()) {
            val last = stage1.last()
            val toneLevel = when (last.ch) {
                's' -> 1
                'f' -> 2
                'r' -> 3
                'x' -> 4
                'j' -> 5
                'z' -> 0
                else -> -1
            }
            if (toneLevel >= 0) {
                // Phai copy ra list moi (toList), khong duoc giu nguyen subList:
                // subList() tra ve MOT VIEW gan voi stage1, neu sau do goi
                // stage1.removeAt(...) thi view nay se bi "hong" (ConcurrentModificationException)
                // ngay khi pickToneTargetIndex() truy cap lai body ben duoi.
                val body = stage1.subList(0, stage1.size - 1).toList()
                val vowelIdxs = body.indices.filter { isVowelChar(body[it].ch) }
                if (vowelIdxs.isNotEmpty()) {
                    stage1.removeAt(stage1.size - 1)
                    val targetIdx = pickToneTargetIndex(body, vowelIdxs)
                    if (targetIdx != null) {
                        val cc = stage1[targetIdx]
                        val base = charToVowelInfo[cc.ch]?.first ?: cc.ch
                        val newChar = vowelTable[base]?.getOrNull(toneLevel) ?: cc.ch
                        stage1[targetIdx] = CChar(newChar, cc.upper)
                    }
                }
            }
        }

        val sb = StringBuilder()
        for (cc in stage1) {
            sb.append(if (cc.upper) cc.ch.uppercaseChar() else cc.ch)
        }
        return sb.toString()
    }

    /** Chon vi tri nguyen am se mang dau thanh, theo quy tac don gian hoa. */
    private fun pickToneTargetIndex(body: List<CChar>, vowelIdxs: List<Int>): Int? {
        // Uu tien nguyen am co dau mu/moc (\u0103,\u00e2,\u00ea,\u00f4,\u01a1,\u01b0) - lay lan xuat hien cuoi
        val modified = vowelIdxs.filter { idx ->
            val base = charToVowelInfo[body[idx].ch]?.first
            base != null && base in listOf('\u0103', '\u00e2', '\u00ea', '\u00f4', '\u01a1', '\u01b0')
        }
        if (modified.isNotEmpty()) return modified.last()

        if (vowelIdxs.size == 1) return vowelIdxs[0]

        val lastVowelIdx = vowelIdxs.last()
        val hasTrailingConsonant = lastVowelIdx != body.size - 1
        return if (hasTrailingConsonant) {
            lastVowelIdx
        } else {
            vowelIdxs[vowelIdxs.size - 2]
        }
    }
}
