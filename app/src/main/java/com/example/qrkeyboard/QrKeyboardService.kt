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

    private var isShiftOn = false
    private val rows = listOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm"
    )

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#202124"))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        rows.forEach { row ->
            root.addView(buildLetterRow(row))
        }

        root.addView(buildBottomRow())

        return root
    }

    private fun buildLetterRow(letters: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        letters.forEach { ch ->
            row.addView(buildKey(ch.toString()) { insertChar(ch) })
        }
        return row
    }

    private fun buildBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(buildKey("\u21e7", weight = 1.5f) {
            isShiftOn = !isShiftOn
        })
        row.addView(buildKey("QR", weight = 1.2f, highlight = true) { openQrScanner() })
        row.addView(buildKey("\u2423", weight = 4f) { insertChar(' ') })
        row.addView(buildKey("\u232b", weight = 1.5f) { deleteChar() })
        row.addView(buildKey("\u23ce", weight = 1.5f) { sendEnter() })

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
        currentInputConnection?.commitText(out.toString(), 1)
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
