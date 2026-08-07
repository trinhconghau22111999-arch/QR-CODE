package com.example.qrkeyboard

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Activity TOI GIAN (giong het [QrCameraPermissionActivity]), CHi lam mot
 * nhiem vu: mo man hinh NHAN DIEN GIONG NOI cua he thong (Google/tro ly ao
 * co san tren may) qua [RecognizerIntent.ACTION_RECOGNIZE_SPEECH]. Day la
 * dieu BAT BUOC phai co mot Activity rieng - InputMethodService (Service)
 * khong the tu minh mo man hinh nay.
 *
 * QUAN TRONG: app nay KHONG tu xin quyen RECORD_AUDIO va KHONG tu ghi am -
 * toan bo viec ghi am + nhan dien duoc UY QUYEN HOAN TOAN cho app tro ly ao
 * cua may (thuong la Google) qua Intent chuan cua Android, giong het cach
 * [QrCameraPermissionActivity] uy quyen viec quet QR cho camera he thong -
 * an toan hon nhieu so voi tu dung SpeechRecognizer truc tiep trong Service
 * (da rut kinh nghiem tu cac loi crash lien quan Camera nhung trong Service
 * truoc day).
 *
 * Ngay khi co ket qua (hoac nguoi dung huy/loi), Activity nay dong lai NGAY
 * LAP TUC va bao ket qua ve QrKeyboardService qua companion callback tinh
 * [QrKeyboardService.notifyVoiceInputResult].
 */
class VoiceInputActivity : AppCompatActivity() {

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = try {
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
            // THEM: log chan doan (khong hien ra nguoi dung) - de xem lai
            // qua logcat neu van con truong hop "khong viet ra" sau ban sua
            // nay, biet duoc la do resultCode/EXTRA_RESULTS that su rong
            // (may/app nhan dien tra ve khong co gi) hay do loi khac.
            android.util.Log.d(
                "VoiceInputActivity",
                "resultCode=${result.resultCode} text=${text ?: "(null/rong)"}"
            )
            QrKeyboardService.notifyVoiceInputResult(text)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ma ngon ngu uu tien (vd "vi-VN", "en-US") duoc QrKeyboardService
        // truyen vao dua theo ngon ngu DANG CHON tren ban phim luc do (xem
        // LanguagePrefs) - de nhan dien dung ngon ngu nguoi dung dinh noi,
        // khong bat buoc (rong thi de he thong tu chon mac dinh cua may).
        val preferredLocale = intent.getStringExtra(EXTRA_LOCALE)

        val recognizeIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "N\u00f3i n\u1ed9i dung c\u1ea7n g\u00f5\u2026")
            if (!preferredLocale.isNullOrBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, preferredLocale)
            }
            // SUA LOI nguoi dung phan anh ("nghe va nhan dien duoc nhung
            // khong viet ra"): TRUOC DAY THIEU 2 extra nay - bo nhan dien
            // giong noi cua Google mac dinh co the CHO IM LANG RAT LAU
            // (hoac cho toi khi nguoi dung tu bam nut dung) truoc khi coi la
            // "noi xong" va TRA KET QUA VE cho app - neu nguoi dung khong tu
            // bam dung, ket qua khong bao gio duoc tra ve, giong het trieu
            // chung "nghe duoc nhung khong viet ra". 2 extra nay BAO Google
            // bo nhan dien: im lang lien tuc 2000ms (2 giay) la COI NHU DA
            // NOI XONG, TU DONG dung nghe va tra ket qua ve NGAY - dung yeu
            // cau "sau 2s ngung noi la phai viet van ban ra" cua nguoi dung.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        if (recognizeIntent.resolveActivity(packageManager) == null) {
            Toast.makeText(
                this,
                "M\u00e1y kh\u00f4ng h\u1ed7 tr\u1ee3 nh\u1eadp li\u1ec7u b\u1eb1ng gi\u1ecdng n\u00f3i",
                Toast.LENGTH_SHORT
            ).show()
            QrKeyboardService.notifyVoiceInputResult(null)
            finish()
            return
        }

        try {
            speechLauncher.launch(recognizeIntent)
        } catch (e: Exception) {
            QrKeyboardService.notifyVoiceInputResult(null)
            finish()
        }
    }

    companion object {
        const val EXTRA_LOCALE = "extra_locale"

        /** Doi ma ngon ngu ngan cua app (vd "vi","en","fr"...) sang ma locale
         *  chuan (vd "vi-VN","en-US","fr-FR") ma RecognizerIntent can. */
        fun localeForLangCode(code: String): String = when (code) {
            "vi" -> "vi-VN"
            "en" -> "en-US"
            "fr" -> "fr-FR"
            "es" -> "es-ES"
            "de" -> "de-DE"
            "pt" -> "pt-PT"
            "it" -> "it-IT"
            "id" -> "id-ID"
            "ms" -> "ms-MY"
            "tr" -> "tr-TR"
            else -> Locale.getDefault().toLanguageTag()
        }
    }
}
