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

    companion object {
        private var activeService: QrKeyboardService? = null

        /** Duoc QrScanActivity goi khi quet duoc ma, de chen text vao o dang nhap. */
        val pendingResultCallback: ((String) -> Unit)?
            get() = activeService?.let { svc -> { text: String -> svc.insertScannedText(text) } }
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

    // ----------------- Xu ly ket qua quet QR -----------------

    private fun insertScannedText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

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

        // Hang chuc nang tren cung: QR + chuyen ban phim
        root.addView(buildTopRow())

        // Cac hang chu cai / ky tu
        letterButtons.clear()
        root.addView(buildRow(currentRow1()))
        root.addView(buildRow(currentRow2(), sideMargin = dp(16)))
        root.addView(buildRow3())

        // Hang duoi cung: 123, dau phay, space, dau cham, enter
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

        val qrBtn = makeKey("[QR] Quét mã", weight = 2f, isSpecial = true)
        qrBtn.setBackgroundColor(Color.parseColor("#1A73E8"))
        qrBtn.setTextColor(Color.WHITE)
        qrBtn.setOnClickListener { openQrScanner() }

        val globeBtn = makeKey("🌐", weight = 1f, isSpecial = true)
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

        val shiftBtn = makeKey("⇧", weight = 1.5f, isSpecial = true)
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

        val backspaceBtn = makeKey("⌫", weight = 1.5f, isSpecial = true)
        backspaceBtn.setOnClickListener {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
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
            isSymbols = !isSymbols
            setInputView(buildKeyboardView())
        }
        row.addView(symBtn)

        val commaBtn = makeKey(",", weight = 1f)
        commaBtn.setOnClickListener { currentInputConnection?.commitText(",", 1) }
        row.addView(commaBtn)

        val spaceBtn = makeKey("khoảng cách", weight = 4f)
        spaceBtn.setOnClickListener { currentInputConnection?.commitText(" ", 1) }
        row.addView(spaceBtn)

        val periodBtn = makeKey(".", weight = 1f)
        periodBtn.setOnClickListener { currentInputConnection?.commitText(".", 1) }
        row.addView(periodBtn)

        val enterBtn = makeKey("⏎", weight = 1.5f, isSpecial = true)
        enterBtn.setOnClickListener { sendEnter() }
        row.addView(enterBtn)

        return row
    }

    private fun makeKey(label: String, weight: Float = 1f, isSpecial: Boolean = false): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTypeface(typeface, if (isSpecial) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (isSpecial) Color.parseColor("#1A1A1A") else Color.parseColor("#1A1A1A"))
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

    private fun onKeyPressed(label: String) {
        val charToSend = if (!isSymbols && isShifted) label.uppercase() else label
        currentInputConnection?.commitText(charToSend, 1)
        if (!isSymbols && isShifted) {
            // Shift chi ap dung cho 1 ky tu, giong hanh vi ban phim thong thuong
            isShifted = false
            refreshLetterCase()
        }
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
}
