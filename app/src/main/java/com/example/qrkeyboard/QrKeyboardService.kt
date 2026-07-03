package com.example.qrkeyboard

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout

/**
 * Dich vu ban phim ao (Input Method Service). Hien thi mot ban phim QWERTY
 * don gian dung code (khong phu thuoc file layout XML), kem nut [QR] de mo
 * QrScanActivity quet ma QR va chen ket qua thang vao o nhap lieu dang mo.
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
    }

    /** Ba "trang" ban phim, dung trinh tu quen thuoc cua cac ban phim khac:
     *  chu cai (mac dinh) <-> so & ky hieu co ban (nut "?123") <-> ky hieu
     *  mo rong (nut "=\<" tren trang so, quay lai bang nut "?123"). */
    private enum class KeyboardMode { LETTERS, NUMBERS, SYMBOLS }

    private var mode = KeyboardMode.LETTERS
    private var isShiftOn = false

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
                letterRows.forEach { row -> root.addView(buildCharRow(row)) }
                root.addView(buildLettersBottomRow())
            }
            KeyboardMode.NUMBERS -> {
                numberRows.forEach { row -> root.addView(buildCharRow(row)) }
                root.addView(buildNumbersRow3())
                root.addView(buildNumbersBottomRow())
            }
            KeyboardMode.SYMBOLS -> {
                extendedSymbolRows.forEach { row -> root.addView(buildCharRow(row)) }
                root.addView(buildExtendedSymbolsBottomRow())
            }
        }

        return root
    }

    /** Chuyen sang trang [newMode] va ve lai ban phim ngay lap tuc. */
    private fun switchMode(newMode: KeyboardMode) {
        mode = newMode
        setInputView(buildKeyboardView())
    }

    /** Moi lan mo lai ban phim o mot o nhap moi, luon quay ve trang chu cai,
     *  giong hanh vi quen thuoc cua cac ban phim khac (khong "ket dinh" o
     *  trang so/ky hieu tu lan truoc). */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (mode != KeyboardMode.LETTERS) {
            switchMode(KeyboardMode.LETTERS)
        }
    }

    /** Mot hang phim don gian: moi ky tu trong chuoi la mot nut cung do rong
     *  bang nhau (weight 1), chen nguyen van ky tu do khi bam. Dung chung cho
     *  ca hang chu cai (co ap dung Shift) lan hang so/ky hieu. */
    private fun buildCharRow(chars: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        chars.forEach { ch ->
            row.addView(buildKey(ch.toString()) { insertChar(ch) })
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
        row.addView(buildKey("\u21e7", weight = 1.3f) { isShiftOn = !isShiftOn })
        row.addView(buildKey("QR", weight = 1.1f, highlight = true) { openQrScanner() })
        row.addView(buildKey("\u2423", weight = 3.4f) { insertChar(' ') })
        row.addView(buildKey("\u232b", weight = 1.3f) { deleteChar() })
        row.addView(buildKey("\u23ce", weight = 1.3f) { sendEnter() })

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

    private fun buildNumbersBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(buildKey("ABC", weight = 1.4f) { switchMode(KeyboardMode.LETTERS) })
        row.addView(buildKey("123", weight = 1.1f) { switchMode(KeyboardMode.NUMBERS) })
        row.addView(buildKey(",", weight = 1f) { insertText(",") })
        row.addView(buildKey("\u2423", weight = 4.2f) { insertChar(' ') })
        row.addView(buildKey(".", weight = 1f) { insertText(".") })
        row.addView(buildKey("\u23ce", weight = 1.4f, highlight = true) { sendEnter() })

        return row
    }

    /** Hang duoi cung cua trang ky hieu mo rong: nut "?123" de quay lai trang
     *  so (thay vi thang ve trang chu cai), cac ky hieu %, ©, ®, ™, ℅, [, ]
     *  va nut xoa - cung do rong nhu nhau, doi xung hai dau giong hang duoi
     *  cua trang chu cai / trang so. */
    private fun buildExtendedSymbolsBottomRow(): LinearLayout {
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

    private fun buildKey(
        label: String,
        weight: Float = 1f,
        highlight: Boolean = false,
        onClick: () -> Unit
    ): Button {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(if (highlight) Color.parseColor("#1A73E8") else Color.parseColor("#303134"))
        }
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 16f
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                0, dp(44), weight
            ).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
    }

    private fun insertChar(ch: Char) {
        val out = if (isShiftOn) ch.uppercaseChar() else ch
        insertText(out.toString())
    }

    private fun insertText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun deleteChar() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
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
    }
}
