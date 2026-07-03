package com.example.qrkeyboard

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout

class QrKeyboardService : InputMethodService() {

    private var isShifted = false
    private var isSymbols = false
    private val letterButtons = mutableListOf<Button>()

    // ----- Trang thai bo go Telex (tieng Viet co dau) -----
    private val currentWordRaw = mutableListOf<Char>()
    private var currentRenderedLength = 0

    companion object {
        private var activeService: QrKeyboardService? = null
        private var pendingScanResult: String? = null

        /** QrScanActivity goi ham nay khi quet duoc ma. Ket qua duoc luu tam,
         *  se duoc dien vao o nhap lieu ngay khi ban phim ket noi lai (onStartInputView). */
        fun deliverScanResult(text: String) {
            val svc = activeService
            if (svc != null) {
                // Dien ngay vao o nhap lieu dang mo (chay tren main thread)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val ic = svc.currentInputConnection
                    if (ic != null) {
                        ic.commitText(text, 1)
                    } else {
                        // Neu chua co InputConnection, luu tam de dien khi ket noi lai
                        pendingScanResult = text
                    }
                    svc.requestShowSelf(0)
                }
            } else {
                pendingScanResult = text
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    // ----------------- Dung ban phim bang code -----------------

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun buildKeyboardView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DADCE0"))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        root.addView(buildTopRow())

        letterButtons.clear()
        root.addView(buildRow(currentRow1()))
        root.addView(buildRow(currentRow2(), sideMargin = dp(16)))
        root.addView(buildRow3())

        root.addView(buildBottomRow())

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
            setPadding(0, 0, 0, dp(6))
        }

        val qrBtn = makeKey("[QR] Qu\u00e9t m\u00e3", weight = 2f, isSpecial = true)
        qrBtn.setBackgroundColor(Color.parseColor("#1A73E8"))
        qrBtn.setTextColor(Color.WHITE)
        qrBtn.setOnClickListener { openQrScanner() }

        val globeBtn = makeKey("\ud83c\udf10", weight = 1f, isSpecial = true)
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
            ).apply { setMargins(sideMargin, 0, sideMargin, dp(4)) }
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
            ).apply { setMargins(0, 0, 0, dp(4)) }
        }

        val shiftBtn = makeKey("\u21e7", weight = 1.5f, isSpecial = true)
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

        val backspaceBtn = makeKey("\u232b", weight = 1.5f, isSpecial = true)
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

        val symBtn = makeKey(if (isSymbols) "ABC" else "123", weight = 1.5f, isSpecial = true)
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

        val spaceBtn = makeKey("kho\u1ea3ng c\u00e1ch", weight = 4f)
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

        val enterBtn = makeKey("\u23ce", weight = 1.5f, isSpecial = true)
        enterBtn.setOnClickListener {
            resetTelexWord()
            sendEnter()
        }
        row.addView(enterBtn)

        return row
    }

    private fun makeKey(label: String, weight: Float = 1f, isSpecial: Boolean = false): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTypeface(typeface, if (isSpecial) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(Color.parseColor("#1A1A1A"))
            setBackgroundColor(if (isSpecial) Color.parseColor("#C3C6CB") else Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(46), weight).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
        }
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

    private fun sendEnter() {
        val editorInfo: EditorInfo? = currentInputEditorInfo
        val action = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
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
                val body = stage1.subList(0, stage1.size - 1)
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
