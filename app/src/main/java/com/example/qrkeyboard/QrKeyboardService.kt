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
            pendingScanResult = text
            activeService?.triggerShowKeyboard()
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

        val qrBtn = makeKey("[QR] Qu茅t m茫", weight = 2f, isSpecial = true)
        qrBtn.setBackgroundColor(Color.parseColor("#1A73E8"))
        qrBtn.setTextColor(Color.WHITE)
        qrBtn.setOnClickListener { openQrScanner() }

        val globeBtn = makeKey("馃寪", weight = 1f, isSpecial = true)
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

        val shiftBtn = makeKey("鈬�", weight = 1.5f, isSpecial = true)
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

        val backspaceBtn = makeKey("鈱�", weight = 1.5f, isSpecial = true)
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

        val spaceBtn = makeKey("kho岷g c谩ch", weight = 4f)
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

        val enterBtn = makeKey("鈴�", weight = 1.5f, isSpecial = true)
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
        'a' to charArrayOf('a', '谩', '脿', '岷�', '茫', '岷�'),
        '膬' to charArrayOf('膬', '岷�', '岷�', '岷�', '岷�', '岷�'),
        '芒' to charArrayOf('芒', '岷�', '岷�', '岷�', '岷�', '岷�'),
        'e' to charArrayOf('e', '茅', '猫', '岷�', '岷�', '岷�'),
        '锚' to charArrayOf('锚', '岷�', '峄�', '峄�', '峄�', '峄�'),
        'i' to charArrayOf('i', '铆', '矛', '峄�', '末', '峄�'),
        'o' to charArrayOf('o', '贸', '貌', '峄�', '玫', '峄�'),
        '么' to charArrayOf('么', '峄�', '峄�', '峄�', '峄�', '峄�'),
        '啤' to charArrayOf('啤', '峄�', '峄�', '峄�', '峄�', '峄�'),
        'u' to charArrayOf('u', '煤', '霉', '峄�', '农', '峄�'),
        '瓢' to charArrayOf('瓢', '峄�', '峄�', '峄�', '峄�', '峄�'),
        'y' to charArrayOf('y', '媒', '峄�', '峄�', '峄�', '峄�')
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

        // Buoc 1: ghep cap ky tu dac biet (aa->芒, aw->膬, ee->锚, oo->么, ow->啤, uw/w->瓢, dd->膽)
        val stage1 = mutableListOf<CChar>()
        var i = 0
        while (i < lower.size) {
            if (i + 1 < lower.size) {
                val pair = "" + lower[i] + lower[i + 1]
                val replacement = when (pair) {
                    "aa" -> '芒'
                    "aw" -> '膬'
                    "ee" -> '锚'
                    "oo" -> '么'
                    "ow" -> '啤'
                    "uw" -> '瓢'
                    "dd" -> '膽'
                    else -> null
                }
                if (replacement != null) {
                    stage1.add(CChar(replacement, upperFlags[i] || upperFlags[i + 1]))
                    i += 2
                    continue
                }
            }
            if (lower[i] == 'w') {
                stage1.add(CChar('瓢', upperFlags[i]))
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
        // Uu tien nguyen am co dau mu/moc (膬,芒,锚,么,啤,瓢) - lay lan xuat hien cuoi
        val modified = vowelIdxs.filter { idx ->
            val base = charToVowelInfo[body[idx].ch]?.first
            base != null && base in listOf('膬', '芒', '锚', '么', '啤', '瓢')
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
