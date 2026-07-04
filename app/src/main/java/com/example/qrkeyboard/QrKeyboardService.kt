package com.example.qrkeyboard

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast

/**
 * Dich vu ban phim ao (Input Method Service). Hien thi mot ban phim QWERTY
 * don gian dung code (khong phu thuoc file layout XML), kem nut [QR] de mo
 * QrScanActivity quet ma QR va chen ket qua thang vao o nhap lieu dang mo.
 * Ho tro go tieng Viet kieu Telex (chuyen doi tu ban phim QWERTY chuan) khi
 * bat che do Tieng Viet bang cach nham giu phim cach 2 giay.
 */
class QrKeyboardService : InputMethodService() {

    companion object {
        /** Cac callback dang cho ket qua quet QR, duoc QrScanActivity goi
         *  khi quet thanh cong. Dung callback tinh (thay vi startActivityForResult)
         *  vi QrScanActivity duoc mo nhu mot cua so noi khong chiem focus. */
        private var pendingCallback: ((String) -> Unit)? = null

        /** Duoc QrScanActivity goi ngay khi quet duoc QR, tu bat ky thread nao. */
        fun deliverScanResult(text: String) {
            pendingCallback?.invoke(text)
        }

        /** Thoi gian (ms) phai nham giu phim cach de bat/tat che do Tieng Viet. */
        private const val LANGUAGE_TOGGLE_HOLD_MS = 2000L
    }

    /** Ba "trang" ban phim, dung trinh tu quen thuoc cua cac ban phim khac:
     *  chu cai (mac dinh) <-> so & ky hieu co ban (nut "?123") <-> ky hieu
     *  mo rong (nut "=\<" tren trang so, quay lai bang nut "?123"). */
    private enum class KeyboardMode { LETTERS, NUMBERS, SYMBOLS }

    private var mode = KeyboardMode.LETTERS
    private var isShiftOn = false

    /** Bat/tat go Tieng Viet kieu Telex, chuyen doi bang cach nham giu phim
     *  cach trong [LANGUAGE_TOGGLE_HOLD_MS] mili giay. */
    private var isVietnameseMode = false

    /** Bo dem chua cac ky tu (thuong, chua dau) cua "tu" dang go trong che do
     *  Tieng Viet, dung de bo dong bo Telex co the xoa/thay the dung phan da
     *  chen truoc do khi ap dau/mu. Duoc xoa moi khi gap dau cach, dau cau,
     *  Enter, hoac chuyen o nhap. */
    private var currentWord = StringBuilder()

    private val handler = Handler(Looper.getMainLooper())
    private var previewPopup: PopupWindow? = null

    private val letterRows = listOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm"
    )

    /** Trang so & ky hieu co ban (nut "?123"). Hang 3 duoc dung rieng
     *  (buildNumbersRow3) vi phim dau tien la nut chuyen sang trang ky hieu
     *  mo rong, khong phai mot ky tu de chen. */
    private val numberRows = listOf(
        "1234567890",
        "@#\u0111_&-+()/"
    )
    private val numberRow3Symbols = "*\"':;!?"

    /** Trang ky hieu mo rong (nut "=\<"). */
    private val extendedSymbolRows = listOf(
        "~`|\u2022\u221a\u03c0\u00f7\u00d7\u00b6\u0394",
        "\u00a3\u20ac$\u00a2^\u00b0={}\\"
    )
    private val extendedSymbolRow3 = "%\u00a9\u00ae\u2122\u2105[]"

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onCreateInputView(): View = buildKeyboardView()

    /** Ve lai toan bo ban phim theo [mode] hien tai. */
    private fun buildKeyboardView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#202124"))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        when (mode) {
            KeyboardMode.LETTERS -> {
                // Hang so (1234567890) luon hien thi co dinh phia tren cac hang
                // chu cai, khong can chuyen trang moi go duoc so.
                root.addView(buildCharRow(numberRows[0]))
                letterRows.forEachIndexed { index, row ->
                    val rowView = buildCharRow(row, applyShiftCase = true)
                    if (index == letterRows.lastIndex) {
                        // Nut xoa (鈱�) nam o CUOI hang chu cai cuoi cung (ben
                        // phai), giong vi tri quen thuoc tren da so ban phim
                        // khac, thay vi nam o hang duoi cung nhu truoc.
                        rowView.addView(buildKey("\u232b", weight = 1.5f) { deleteChar() })
                    }
                    root.addView(rowView)
                }
                root.addView(buildLettersBottomRow())
            }
            KeyboardMode.NUMBERS -> {
                numberRows.forEach { row -> root.addView(buildCharRow(row)) }
                root.addView(buildNumbersRow3())
                root.addView(buildNumbersBottomRow())
            }
            KeyboardMode.SYMBOLS -> {
                extendedSymbolRows.forEach { row -> root.addView(buildCharRow(row)) }
                root.addView(buildExtendedSymbolsRow3())
                root.addView(buildExtendedSymbolsBottomRow())
            }
        }

        return root
    }

    /** Chuyen sang trang [newMode] va ve lai ban phim ngay lap tuc. */
    private fun switchMode(newMode: KeyboardMode) {
        mode = newMode
        redrawKeyboard()
    }

    /** Ve lai ban phim voi [mode] hien tai (dung khi doi trang thai Shift,
     *  doi ngon ngu, ... nhung khong doi trang ban phim). */
    private fun redrawKeyboard() {
        setInputView(buildKeyboardView())
    }

    /** Moi lan mo lai ban phim o mot o nhap moi, luon quay ve trang chu cai,
     *  giong hanh vi quen thuoc cua cac ban phim khac (khong "ket dinh" o
     *  trang so/ky hieu tu lan truoc). */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentWord.clear()
        if (mode != KeyboardMode.LETTERS) {
            switchMode(KeyboardMode.LETTERS)
        }
    }

    /** Mot hang phim don gian: moi ky tu trong chuoi la mot nut cung do rong
     *  bang nhau (weight 1), chen nguyen van ky tu do khi bam. Dung chung cho
     *  ca hang chu cai (co ap dung Shift de HIEN THI hoa/thuong) lan hang
     *  so/ky hieu. */
    private fun buildCharRow(chars: String, applyShiftCase: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        chars.forEach { ch ->
            val label = if (applyShiftCase && isShiftOn) ch.uppercaseChar().toString() else ch.toString()
            row.addView(buildKey(label) { insertChar(ch) })
        }
        return row
    }

    private fun buildLettersBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(buildKey("?123", weight = 1.3f) { switchMode(KeyboardMode.NUMBERS) })
        row.addView(buildKey("\u21e7", weight = 1.3f, highlight = isShiftOn) {
            isShiftOn = !isShiftOn
            redrawKeyboard()
        })
        row.addView(buildKey("QR", weight = 1.1f, highlight = true) { openQrScanner() })
        row.addView(buildSpaceKey(weight = 4.0f))
        row.addView(buildKey("\u23ce", weight = 1.3f, highlight = true) { sendEnter() })

        return row
    }

    /** Hang 3 cua trang so: nut "=\<" chuyen sang trang ky hieu mo rong, roi
     *  toi cac ky hieu co ban, va nut xoa o cuoi - tat ca cung do rong nhu
     *  nhau, dong bo voi cach cac hang khac phan bo phim. */
    private fun buildNumbersRow3(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(buildKey("=\\<") { switchMode(KeyboardMode.SYMBOLS) })
        numberRow3Symbols.forEach { ch ->
            row.addView(buildKey(ch.toString()) { insertText(ch.toString()) })
        }
        row.addView(buildKey("\u232b") { deleteChar() })

        return row
    }

    /** Hang duoi cung cua trang so: bo nut "123" thua (vi dang o san trang
     *  so roi, khong can nut chuyen ve chinh trang hien tai nua) - chi con
     *  "ABC" de quay lai trang chu cai, dau phay, dau cach, dau cham va Enter. */
    private fun buildNumbersBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(buildKey("ABC", weight = 1.6f) { switchMode(KeyboardMode.LETTERS) })
        row.addView(buildKey(",", weight = 1f) { insertText(",") })
        row.addView(buildSpaceKey(weight = 4.6f))
        row.addView(buildKey(".", weight = 1f) { insertText(".") })
        row.addView(buildKey("\u23ce", weight = 1.6f, highlight = true) { sendEnter() })

        return row
    }

    /** Hang 3 cua trang ky hieu mo rong: nut "?123" de quay lai trang so,
     *  cac ky hieu %, 漏, 庐, 鈩�, 鈩�, [, ] va nut xoa - cung do rong nhu nhau. */
    private fun buildExtendedSymbolsRow3(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(buildKey("?123", weight = 1.3f) { switchMode(KeyboardMode.NUMBERS) })
        extendedSymbolRow3.forEach { ch ->
            row.addView(buildKey(ch.toString(), weight = 1f) { insertText(ch.toString()) })
        }
        row.addView(buildKey("\u232b", weight = 1.3f) { deleteChar() })

        return row
    }

    /** Hang duoi cung cua trang ky hieu mo rong: truoc day trang nay bi
     *  THIEU han hang dau cach - gio them lai, dong bo voi trang chu cai va
     *  trang so (nut "ABC" ve trang chu cai, phim cach, Enter). */
    private fun buildExtendedSymbolsBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(buildKey("ABC", weight = 1.4f) { switchMode(KeyboardMode.LETTERS) })
        row.addView(buildSpaceKey(weight = 4.8f))
        row.addView(buildKey("\u23ce", weight = 1.4f, highlight = true) { sendEnter() })

        return row
    }

    /** Phim cach: chuc nang chinh la chen dau cach; nham GIU trong 2 giay se
     *  chuyen doi giua go Tieng Viet (Telex) va Tieng Anh. Nhan giua chua du
     *  2 giay van chi chen dau cach binh thuong. Nhan chu vao trang thai
     *  hien tai (EN/VI) de nguoi dung biet dang o che do nao. */
    private fun buildSpaceKey(weight: Float): Button {
        val label = "\u2423 " + if (isVietnameseMode) "VI" else "EN"
        return buildKey(
            label = label,
            weight = weight,
            onLongPress2s = { toggleVietnameseMode() }
        ) { insertChar(' ') }
    }

    private fun toggleVietnameseMode() {
        isVietnameseMode = !isVietnameseMode
        currentWord.clear()
        Toast.makeText(
            this,
            if (isVietnameseMode) "Go Ti\u1ebfng Vi\u1ec7t (Telex)" else "Go Ti\u1ebfng Anh",
            Toast.LENGTH_SHORT
        ).show()
        redrawKeyboard()
    }

    /** Xay dung mot phim bam:
     *  - Rung nhe (haptic) khi nham xuong.
     *  - Neu la phim mot ky tu don, "noi" mot bong xem truoc phong to ngay
     *    phia tren phim trong luc dang nham.
     *  - Neu co [onLongPress2s], se kich hoat sau khi giu du 2000ms va HUY
     *    hanh dong click binh thuong (dung cho phim cach: giu 2s = doi ngon
     *    ngu, tha ra som = chen dau cach). */
    private fun buildKey(
        label: String,
        weight: Float = 1f,
        highlight: Boolean = false,
        onLongPress2s: (() -> Unit)? = null,
        onClick: () -> Unit
    ): Button {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(if (highlight) Color.parseColor("#1A73E8") else Color.parseColor("#303134"))
        }
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            // Chu nho hon o cac phim nhieu ky tu (vd "?123", "EN") va cho
            // hien thi tren MOT dong duy nhat, tranh bi xuong dong roi cat
            // mat chu (vd chi con thay dau "?" ma khong thay "123").
            textSize = when {
                label.length > 3 -> 11f
                label.length > 1 -> 13f
                else -> 16f
            }
            isSingleLine = true
            setPadding(dp(1), 0, dp(1), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                0, dp(44), weight
            ).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            gravity = Gravity.CENTER
            isHapticFeedbackEnabled = true
        }

        var longPressFired = false
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (label.length == 1) showKeyPreview(v, label)
                    if (onLongPress2s != null) {
                        longPressFired = false
                        handler.postDelayed({
                            longPressFired = true
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            hideKeyPreview()
                            onLongPress2s.invoke()
                        }, LANGUAGE_TOGGLE_HOLD_MS)
                    }
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideKeyPreview()
                    if (onLongPress2s != null) {
                        handler.removeCallbacksAndMessages(null)
                        if (longPressFired) {
                            // Da kich hoat giu-2s roi: nuot su kien nay de
                            // KHONG chen dau cach nua.
                            return@setOnTouchListener true
                        }
                    }
                    false
                }
                else -> false
            }
        }
        button.setOnClickListener { onClick() }
        return button
    }

    /** Hien mot bong nho, chu to, ngay phia tren phim dang nham, giong hieu
     *  ung "noi chu" quen thuoc tren cac ban phim ao khac. */
    private fun showKeyPreview(anchor: View, label: String) {
        hideKeyPreview()
        val bubble = TextView(this).apply {
            text = label
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#3C4043"))
            }
        }
        val popup = PopupWindow(
            bubble, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false
        ).apply {
            isClippingEnabled = false
        }
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        bubble.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val x = loc[0] + anchor.width / 2 - bubble.measuredWidth / 2
        val y = loc[1] - bubble.measuredHeight - dp(4)
        try {
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
            previewPopup = popup
        } catch (e: Exception) {
            // Bo qua neu window chua san sang de hien popup (hiem gap).
        }
    }

    private fun hideKeyPreview() {
        previewPopup?.dismiss()
        previewPopup = null
    }

    /** Chen mot ky tu chu cai. Neu dang o che do Tieng Viet, chuyen qua bo
     *  xu ly Telex; nguoc lai chen truc tiep nhu Tieng Anh (co ap dung Shift). */
    private fun insertChar(ch: Char) {
        if (isVietnameseMode && ch.isLetter()) {
            insertVietnameseChar(ch)
            return
        }
        val out = if (isShiftOn) ch.uppercaseChar() else ch
        insertText(out.toString())
    }

    /** Xu ly mot ky tu go theo kieu Telex: doi chieu voi phan "tu" da go tu
     *  truoc (currentWord) de biet co can xoa/thay the ky tu truoc do hay
     *  khong (vd go "a" roi "a" -> "芒", go nguyen am roi "s" -> them dau sac). */
    private fun insertVietnameseChar(ch: Char) {
        val ic = currentInputConnection ?: return
        val lower = ch.lowercaseChar()
        val oldLen = currentWord.length
        val newWordLower = VietnameseTelex.processKey(currentWord.toString(), lower)
        currentWord = StringBuilder(newWordLower)
        val display = if (isShiftOn) newWordLower.uppercase() else newWordLower

        if (oldLen > 0) {
            ic.deleteSurroundingText(oldLen, 0)
        }
        ic.commitText(display, 1)
    }

    private fun insertText(text: String) {
        currentInputConnection?.commitText(text, 1)
        // Dau cach/dau cau/ky hieu luon ket thuc "tu" hien tai.
        currentWord.clear()
    }

    private fun deleteChar() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        if (currentWord.isNotEmpty()) {
            currentWord.deleteCharAt(currentWord.length - 1)
        }
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        currentWord.clear()
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    /** Mo QrScanActivity nhu mot cua so noi, khong cuop focus ban phim,
     *  truyen kem chieu cao hien tai cua ban phim de QrScanActivity dat
     *  khung quet dung ngay phia tren. Ket qua quet se duoc tra ve qua
     *  companion callback [deliverScanResult]. Sau khi chen xong noi dung,
     *  tu dong xuong dong (chen ky tu "\n") de con tro nam san o dong moi,
     *  san sang cho lan quet/nhap tiep theo. */
    private fun openQrScanner() {
        pendingCallback = { text ->
            val ic = currentInputConnection
            ic?.commitText(text, 1)
            ic?.commitText("\n", 1)
            currentWord.clear()
            pendingCallback = null
        }

        val keyboardHeightPx = window?.window?.decorView?.height ?: 0

        val intent = Intent(this, QrScanActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(QrScanActivity.EXTRA_KEYBOARD_HEIGHT_PX, keyboardHeightPx)
        }
        startActivity(intent)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Neu nguoi dung roi khoi o nhap lieu ma khong quet, huy callback dang
        // cho de tranh chen nham du lieu vao o khac sau nay.
        pendingCallback = null
        hideKeyPreview()
    }
}

/**
 * Bo xu ly go Tieng Viet kieu Telex don gian: chuyen mot chuoi ky tu QWERTY
 * thuong (khong dau) thanh chuoi co dau Tieng Viet, dua tren "tu" dang go
 * (tu luc bat dau tu den ky tu hien tai). Cac quy tac Telex duoc ho tro:
 *   - aa -> 芒, aw -> 膬, ee -> 锚, oo -> 么, ow -> 啤, uw -> 瓢, dd -> 膽
 *   - s/f/r/x/j -> dau sac/huyen/hoi/nga/nang; z -> bo dau
 * Day la mot bo xu ly rut gon (khong xu ly het moi truong hop dac biet cua
 * chinh ta Tieng Viet), nhung dap ung tot phan lon cac tu thong dung.
 */
private object VietnameseTelex {

    // Moi nhom: ky tu goc (khong dau) + 6 bien the theo dau:
    // [khong dau, sac, huyen, hoi, nga, nang]
    private val vowelGroups: List<CharArray> = listOf(
        charArrayOf('a', '\u00e1', '\u00e0', '\u1ea3', '\u00e3', '\u1ea1'), // a
        charArrayOf('\u0103', '\u1eaf', '\u1eb1', '\u1eb3', '\u1eb5', '\u1eb7'), // 膬
        charArrayOf('\u00e2', '\u1ea5', '\u1ea7', '\u1ea9', '\u1eab', '\u1ead'), // 芒
        charArrayOf('e', '\u00e9', '\u00e8', '\u1ebb', '\u1ebd', '\u1eb9'), // e
        charArrayOf('\u00ea', '\u1ebf', '\u1ec1', '\u1ec3', '\u1ec5', '\u1ec7'), // 锚
        charArrayOf('i', '\u00ed', '\u00ec', '\u1ec9', '\u0129', '\u1ecb'), // i
        charArrayOf('o', '\u00f3', '\u00f2', '\u1ecf', '\u00f5', '\u1ecd'), // o
        charArrayOf('\u00f4', '\u1ed1', '\u1ed3', '\u1ed5', '\u1ed7', '\u1ed9'), // 么
        charArrayOf('\u01a1', '\u1edb', '\u1edd', '\u1edf', '\u1ee1', '\u1ee3'), // 啤
        charArrayOf('u', '\u00fa', '\u00f9', '\u1ee7', '\u0169', '\u1ee5'), // u
        charArrayOf('\u01b0', '\u1ee9', '\u1eeb', '\u1eed', '\u1eef', '\u1ef1'), // 瓢
        charArrayOf('y', '\u00fd', '\u1ef3', '\u1ef7', '\u1ef9', '\u1ef5')  // y
    )

    // Cac nhom "co mu/moc" (芒, 膬, 锚, 么, 啤, 瓢) duoc uu tien khi dat dau thanh
    // neu chung xuat hien trong cum nguyen am.
    private val modifiedGroupIndices = setOf(1, 2, 4, 7, 8, 10)

    private val charToGroupTone: Map<Char, Pair<Int, Int>> by lazy {
        val map = HashMap<Char, Pair<Int, Int>>()
        vowelGroups.forEachIndexed { groupIdx, tones ->
            tones.forEachIndexed { toneIdx, c -> map[c] = groupIdx to toneIdx }
        }
        map
    }

    /** Ap dung mot phim [keyLower] (da ve chu thuong) vao "tu" hien tai
     *  [word] (cung da ve chu thuong), tra ve tu moi sau khi bien doi Telex
     *  (neu co) hoac tu cu + ky tu do neu khong co bien doi nao ap dung. */
    fun processKey(word: String, keyLower: Char): String {
        applyDoubleModifier(word, keyLower)?.let { return it }
        applyTone(word, keyLower)?.let { return it }
        return word + keyLower
    }

    /** Xu ly cac cap chuyen doi: aa->芒, aw->膬, ee->锚, oo->么, ow->啤, uw->瓢,
     *  dd->膽. Chi ap dung khi ky tu ngay truoc do khop dung quy tac. */
    private fun applyDoubleModifier(word: String, key: Char): String? {
        if (word.isEmpty()) return null
        val last = word.last()
        return when (key) {
            'a' -> if (last == 'a') word.dropLast(1) + '\u00e2' else null
            'e' -> if (last == 'e') word.dropLast(1) + '\u00ea' else null
            'o' -> if (last == 'o') word.dropLast(1) + '\u00f4' else null
            'w' -> when (last) {
                'a' -> word.dropLast(1) + '\u0103'
                'o' -> word.dropLast(1) + '\u01a1'
                'u' -> word.dropLast(1) + '\u01b0'
                else -> null
            }
            'd' -> if (last == 'd') word.dropLast(1) + '\u0111' else null
            else -> null
        }
    }

    /** Xu ly cac phim dat dau thanh: s (sac), f (huyen), r (hoi), x (nga),
     *  j (nang), z (bo dau). Tim cum nguyen am o cuoi tu, uu tien nguyen am
     *  "co mu/moc" (芒, 锚, 么, 啤, 瓢, 膬) neu co, neu khong lay nguyen am cuoi
     *  cung trong cum. Neu khong tim thay nguyen am nao, tra ve null de ky
     *  tu duoc chen nhu binh thuong (vd "s", "r", "x" o dau tu la phu am). */
    private fun applyTone(word: String, key: Char): String? {
        val toneIdx = when (key) {
            's' -> 1
            'f' -> 2
            'r' -> 3
            'x' -> 4
            'j' -> 5
            'z' -> 0
            else -> return null
        }

        val clusterIndices = mutableListOf<Int>()
        var i = word.length - 1
        while (i >= 0 && charToGroupTone.containsKey(word[i])) {
            clusterIndices.add(0, i)
            i--
        }
        if (clusterIndices.isEmpty()) return null

        val preferred = clusterIndices.lastOrNull { pos ->
            charToGroupTone[word[pos]]!!.first in modifiedGroupIndices
        }
        val target = preferred ?: clusterIndices.last()

        val (groupIdx, _) = charToGroupTone[word[target]]!!
        val newChar = vowelGroups[groupIdx][toneIdx]
        return word.substring(0, target) + newChar + word.substring(target + 1)
    }
}
