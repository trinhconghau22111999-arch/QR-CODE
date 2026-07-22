package com.example.qrkeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import android.content.ContentValues
import android.content.ClipDescription
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dich vu ban phim ao (Input Method Service).
 *
 * PHIEN BAN NAY (toi uu hoa hieu nang + sua loi, theo yeu cau nguoi dung
 * "toi uu cho phim nhanh hon, sua loi het"): xem cac ghi chu danh dau
 * "TOI UU"/"SUA LOI" ranh rot trong tung ham lien quan de biet CHINH XAC
 * cho nao da doi va TAI SAO - tom tat cac thay doi chinh:
 *
 *  1) LAZY-BUILD tung trang ban phim (SUA LOI HIEU NANG QUAN TRONG NHAT):
 *     TRUOC DAY, moi khi [onCreateInputView] duoc goi (xay ra RAT THUONG
 *     XUYEN - moi lan chuyen o nhap/ung dung, he thong co the tao lai View
 *     ban phim), ham nay XOA SACH cache cua CA 3 trang con lai (So, Ky
 *     hieu, So-rieng) roi [buildKeyboardContainer] LAP TUC xay dung lai TAT
 *     CA 4 trang cung luc - bao gom trang So voi HANG EMOJI ~150 nut rieng
 *     le (moi nut lai tao rieng GradientDrawable+LayerDrawable) - DU nguoi
 *     dung dang go tren trang Chu cai va CO THE khong bao gio cham toi may
 *     trang kia trong ca phien go do. Day chinh la nguyen nhan gay "khung/
 *     giat nhe" (qua nhieu View duoc dung/huy lien tuc tren luong chinh) VA
 *     "thinh thoang khong len ban phim" (dung khong du thoi luong he thong
 *     danh cho viec ve khung hinh dau tien cua IME). GIO DAY: chi build
 *     TRANG LETTERS (luon can, vi mang trang thai dong) + DUY NHAT trang
 *     TRUNG voi [mode] hien tai; 3 trang con lai CHI duoc xay dung that su
 *     khi nguoi dung THAT SU chuyen toi (qua [switchMode]).
 *  2) TAI SU DUNG View da cache XUYEN SUOT nhieu lan [onCreateInputView]
 *     (thay vi xoa cache MOI LAN goi nhu truoc): chi xoa cache khi kich
 *     thuoc phim ([keyHeightDp]) THAT SU doi (xoay man hinh, chia doi cua
 *     so...) - xem [lastBuiltKeyHeightDp]. Truong hop PHO BIEN NHAT (chi
 *     chuyen o nhap/ung dung, man hinh khong doi) gio KHONG can xay dung lai
 *     hang emoji/cac phim So/Ky hieu tu dau nua.
 *  3) Cache san CHUOI MAU vien da tinh (tranh goi String.format/parseColor
 *     lap lai tren MOI phim moi lan build trang) - xem [outerGlowColorFor].
 *  4) Hang emoji (~150 nut): DUNG CHUNG mot Drawable nen duy nhat cho ca
 *     hang (thay vi 150 Drawable rieng biet) - xem [buildEmojiRow].
 *  5) [vibrateKeyPress]: nho lai (cache) BUOC rung nao da thanh cong o lan
 *     truoc, lan sau di THANG vao buoc do thay vi phai thu lai tu dau (voi
 *     try/catch + log) tren MOI phim go - xem [resolvedVibrationStep].
 *  6) Bo bot [View.performHapticFeedback] (goi rung qua co che co san cua
 *     Android, phu thuoc cai dat he thong hay khong on dinh - ly do ban dau
 *     phai them [vibrateKeyPress] rung TRUC TIEP) tren MOI lan cham phim -
 *     giu lai DUY NHAT [vibrateKeyPress] (dang tin cay hon), tranh ban phim
 *     phai goi 2 co che rung khac nhau (2 lan IPC toi he thong) cho CUNG
 *     mot lan cham.
 *
 * ---- Tai lieu goc (giu nguyen, khong doi noi dung mo ta chuc nang) ----
 *
 * Hien thi mot ban phim QWERTY don gian dung code (khong phu thuoc file
 * layout XML), kem nut [QR] de mo khung quet QR noi (xem [showQrOverlay])
 * va chen ket qua thang vao o nhap lieu dang mo.
 * Ho tro go tieng Viet kieu Telex (chuyen doi tu ban phim QWERTY chuan). Bat/
 * tat che do Tieng Viet bang cach VUOT tren phim cach: vuot TU TRAI SANG PHAI
 * de chuyen ve Tieng Anh, vuot TU PHAI SANG TRAI de chuyen sang Tieng Viet
 * (xem [buildSpaceKey]) - thay cho kieu cham nhanh 2 lan (double-tap) truoc
 * day, vi double-tap de bi kich hoat nham khi go nhanh lien tuc 2 dau cach
 * gan nhau (vd giua 2 cau), gay doi ngon ngu ngoai y muon.
 *
 * KHUNG QUET QR: mot VIEW NOI duoc add thang vao cua so cua CHINH
 * InputMethodService nay bang WindowManager (xem [showQrOverlay]), khong
 * dung Activity nao de quet ca. Vi cua so chu (cua so ban phim) khong doi,
 * he thong khong bao gio coi la mat focus, nen ban phim va khung quet chac
 * chan cung ton tai, InputConnection voi o nhap khong bi gian doan. CameraX
 * can mot LifecycleOwner de bind/unbind camera dung luc, nen Service nay tu
 * implement LifecycleOwner (xem [lifecycle]).
 *
 * Nut QR: CA cham 1 lan LAN cham 2 lan (dup-tap) deu mo khung quet o CHE DO
 * QUET LIEN TUC (quet xong 1 ma khong tu dong dong, tiep tuc quet ma tiep
 * theo) - CHI dong khi nguoi dung tu bam nut "Huy" tren khung quet (xem
 * [buildNumbersBottomRow]). Co che PHAT HIEN dup-tap van con (dung lam
 * "cong tac du phong" cho tuong lai) nhung ca hai nhanh gio dan toi CUNG
 * mot ket qua continuous = true.
 *
 * BAN PHIM SO RIENG (NUMPAD): tu dong duoc chon khi mo mot o nhap CHI NHAN
 * SO (ma PIN/OTP, so dien thoai - xem [isNumericOnlyField]), dang 3 hang so
 * + 1 hang hanh dong (ABC/0/xoa/Enter) dong bo kieu voi 3 trang con lai -
 * xem [buildNumpadPage].
 *
 * TU DONG VE TIENG ANH KHI GO MAT KHAU: mo mot o nhap MAT KHAU (password) se
 * tu dong tat che do Telex Tieng Viet neu dang bat - xem [isPasswordField].
 *
 * THANH CAI DAT MAU + SANG/TOI: hien san (khong can nut bat/tat rieng) tren
 * trang Ky hieu mo rong - 8 o mau vien dang dung chon lam mau vien CHUNG cho
 * ca ban phim, 1 nut tron doi nen sang<->toi - xem [buildKeyboardSettingsBar].
 */
class QrKeyboardService : InputMethodService(), LifecycleOwner {

    /** LifecycleRegistry rieng cho Service nay, dung CHI de cung cap cho
     *  CameraX.bindToLifecycle() (CameraX bat buoc phai co mot LifecycleOwner).
     *  Chuyen sang RESUMED khi khung quet dang mo ([showQrOverlay]), CREATED
     *  khi dong lai ([hideQrOverlay]) - KHONG bao gio DESTROYED cho toi khi
     *  chinh Service bi huy ([onDestroy]), de co the mo/dong khung quet nhieu
     *  lan trong suot vong doi cua ban phim ma khong can tao lai registry. */
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    companion object {
        /** Callback tinh, duoc QrCameraPermissionActivity goi ngay sau khi
         *  nguoi dung tra loi hop thoai xin quyen Camera he thong. Day la
         *  Activity DUY NHAT con lai trong luong quet QR - no KHONG chua giao
         *  dien quet nao ca, chi lam mot viec: hien ho thoai xin quyen (bat
         *  buoc phai gan voi mot Activity, InputMethodService/Service khong
         *  the tu hien hop thoai nay), roi bao ket qua ve day va tu dong. */
        private var onCameraPermissionResult: ((granted: Boolean) -> Unit)? = null

        fun notifyCameraPermissionResult(granted: Boolean) {
            onCameraPermissionResult?.invoke(granted)
            onCameraPermissionResult = null
        }

        /** Khoang cach toi thieu (dp) ngon tay phai di chuyen theo chieu
         *  ngang tren phim cach de tinh la mot cu VUOT (swipe) doi ngon ngu,
         *  thay vi mot cai CHAM (tap) chen dau cach binh thuong. */
        private const val SPACE_SWIPE_THRESHOLD_DP = 24

        /** Phim xoa (⌫): thoi gian nham giu truoc khi bat dau tu dong xoa
         *  LIEN TUC (ms), va khoang cach (ms) giua cac lan xoa lien tiep sau
         *  do. Nham giu qua [DELETE_REPEAT_INITIAL_DELAY_MS] se kich hoat
         *  xoa lap lai moi [DELETE_REPEAT_INTERVAL_MS] cho den khi tha tay,
         *  thay vi truoc day moi lan bam chi xoa dung 1 ky tu. */
        private const val DELETE_REPEAT_INITIAL_DELAY_MS = 400L
        private const val DELETE_REPEAT_INTERVAL_MS = 50L

        /** Khoang thoi gian toi da (ms) giua 2 lan cham nut [QR] de tinh la
         *  mot cu DUP-TAP (cham dup 2 lan) - CO CHE PHAT HIEN nay VAN GIU
         *  NGUYEN (du phong cho tuong lai), nhung theo yeu cau nguoi dung,
         *  CA cham 1 lan LAN dup-tap gio deu dan toi CUNG mot ket qua (mo
         *  khung quet o CHE DO LIEN TUC) - xem [buildNumbersBottomRow]. */
        private const val QR_DOUBLE_TAP_MAX_INTERVAL_MS = 350L

        /** Khoang thoi gian (ms) toi da giua 2 lan cham nut Shift (⇧) de tinh
         *  la mot cu DUP-TAP (cham 2 lan lien tiep, trong khoang thoi gian
         *  nay). Xem [buildKeyboardView] (buildLettersPage), phan xu ly nut
         *  Shift trong trang chu cai. */
        private const val SHIFT_DOUBLE_TAP_MAX_INTERVAL_MS = 350L

        /** Khoang thoi gian (ms) toi da giua 2 lan cham nut "Chup anh" (giua
         *  Flash va Huy trong khung quet QR) de tinh la mot cu DUP-TAP - dung
         *  de BAT/TAT che do "TU DONG CHUP ANH THEO MA QUET" (xem
         *  [qrAutoCapturePerScan]). */
        private const val CAPTURE_DOUBLE_TAP_MAX_INTERVAL_MS = 350L

        /** So dp them vao vien DUOI CUNG cua toan bo ban phim de NHICH CA
         *  BAN PHIM LEN cao hon mot chut so voi day man hinh/thanh dieu
         *  huong, theo phan anh cua nguoi dung. */
        private const val EXTRA_BOTTOM_LIFT_DP = 1

        /** Khoang thoi gian (ms) TRE truoc khi thuc su dong khung quet QR +
         *  coi la "roi ban phim" sau khi he thong bao [onFinishInputView] voi
         *  finishingInput = true. */
        private const val FINISH_INPUT_HIDE_DEBOUNCE_MS = 500L

        /** Khoang thoi gian toi da (ms) ke tu luc khung quet QR TU DONG dong
         *  cho toi luc ban phim mo lai, de con duoc coi la "tu mo lai khung
         *  quet" - xem [reopenQrScannerOnNextStart]. */
        private const val QR_AUTO_REOPEN_WINDOW_MS = 4000L

        /** Ten file SharedPreferences + cac khoa dung de LUU lai lua chon
         *  mau vien va che do sang/toi cua nguoi dung - xem [setAccentColor]/
         *  [toggleTheme] - de mo lai ban phim (thoat app, khoi dong lai may)
         *  van giu nguyen cai dat da chon, khong bi reset ve mac dinh. */
        private const val PREFS_NAME = "qr_keyboard_prefs"
        private const val PREF_ACCENT_COLOR = "accent_color"
        private const val PREF_IS_DARK_THEME = "is_dark_theme"

        /** Mau tim neon MAC DINH (giu nguyen y het mau glowColor cu truoc
         *  khi co tinh nang doi mau). */
        private val DEFAULT_ACCENT_COLOR = Color.parseColor("#B388FF")
    }

    /** Thoi diem (uptimeMillis) cua lan cham nut [QR] gan nhat, dung de phat
     *  hien cu dup-tap (xem [QR_DOUBLE_TAP_MAX_INTERVAL_MS]) o [buildNumbersBottomRow]. */
    private var lastQrKeyTapTime = 0L

    /** Thoi diem (uptimeMillis) cua lan cham nut Shift (⇧) gan nhat, dung de
     *  phat hien cu dup-tap (xem [SHIFT_DOUBLE_TAP_MAX_INTERVAL_MS]). */
    private var lastShiftTapTime = 0L

    /** Handler dung rieng cho vong lap xoa lien tuc khi giu phim ⌫. */
    private val deleteRepeatHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Handler + lenh "hoan" dung rieng cho co che TRE truoc khi dong khung
     *  quet QR sau [onFinishInputView] (xem [FINISH_INPUT_HIDE_DEBOUNCE_MS]).
     *  [pendingFinishHide] la lenh dong dang cho - null nghia la khong co
     *  lenh nao dang cho ca (da bi huy hoac da chay xong). */
    private val finishInputHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingFinishHide: Runnable? = null

    /** Huy lenh dong khung quet dang "hoan" (neu co) - goi khi ban phim thuc
     *  su duoc mo lai ([onStartInputView]), chung minh lan finishingInput =
     *  true truoc do chi la gian doan tam thoi, khong phai nguoi dung thuc
     *  su roi o nhap. */
    private fun cancelPendingFinishHide() {
        pendingFinishHide?.let { finishInputHideHandler.removeCallbacks(it) }
        pendingFinishHide = null
    }

    /** Bon "trang" ban phim: chu cai (mac dinh) <-> so & ky hieu co ban (nut
     *  "?123") <-> ky hieu mo rong (nut "=\<" tren trang so) <-> ban phim SO
     *  RIENG (NUMPAD, dang PIN/dien thoai - tu dong chon cho o nhap chi nhan
     *  so, xem [isNumericOnlyField]). */
    private enum class KeyboardMode { LETTERS, NUMBERS, SYMBOLS, NUMPAD }

    private var mode = KeyboardMode.LETTERS
    private var isShiftOn = false

    /** Bat/tat go Tieng Viet kieu Telex, chuyen doi bang cach VUOT ngang tren
     *  phim cach (xem [buildSpaceKey]). */
    private var isVietnameseMode = false

    /** True neu ky tu (CHU CAI) tiep theo can duoc TU DONG VIET HOA - dat
     *  thanh true ngay sau khi go dau "." (xem nut "." trong
     *  [buildLettersBottomRow]), giong hanh vi quen thuoc cua hau het ban
     *  phim khac (tu dong hoa dau cau moi sau khi ket thuc cau). Chi anh
     *  huong DUY NHAT MOT chu cai (khong phai ca tu, khong phai bat Caps
     *  Lock) - dau cach/dau cau go giua "." va chu cai do (vd ". " -> vua go
     *  dau cach) KHONG lam mat co hieu luc cua co nay, chi khi mot CHU CAI
     *  thuc su duoc go moi tinh la "da dung", xem [insertChar] va
     *  [insertVietnameseChar]. */
    private var capitalizeNextLetter = false

    /** CHI dung de quyet dinh HIEN THI (nhan chu tren cac phim + highlight
     *  nut Shift) - TACH RIENG khoi [capitalizeNextLetter] (co chuc nang,
     *  anh huong toi CHU THAT SU se duoc chen). Xem giai thich chi tiet o
     *  ban goc: tach ra de ban phim quay ve hien chu THUONG ngay sau 1 lan
     *  go, khong bi "ket dinh" hien hoa lau hon can thiet. */
    private var showCapitalPreview = false

    /** Vi tri (commonPrefixLen) tai do [capitalizeNextLetter] VUA duoc AP
     *  DUNG (viet hoa) LAN GAN NHAT - null nghia la CHUA ap dung lan nao. */
    private var capitalizeAppliedAtPrefixLen: Int? = null

    /** Bo dem chua cac ky tu (thuong, chua dau) cua "tu" dang go trong che do
     *  Tieng Viet, dung de bo dong bo Telex co the xoa/thay the dung phan da
     *  chen truoc do khi ap dau/mu. Duoc xoa moi khi gap dau cach, dau cau,
     *  Enter, hoac chuyen o nhap. */
    private var currentWord = StringBuilder()

    /** THEM (theo yeu cau nguoi dung): ban sao GIU NGUYEN hoa/thuong THAT SU
     *  cua [currentWord] (currentWord luon la chu THUONG, dung lam "goc" cho
     *  Telex xu ly) - CUNG do dai, CUNG vi tri voi [currentWord] tai MOI thoi
     *  diem (duoc dong bo lai TU DONG cung luc voi currentWord trong
     *  [resyncCurrentWordFromInputConnection]). Dung DUY NHAT de kiem tra
     *  "co bi LECH hoa/thuong giua ky tu DA co san va phim MOI vua go hay
     *  khong" trong [VietnameseTelex.applyDoubleModifier] - xem giai thich
     *  chi tiet o do: LECH hoa/thuong (vd "A" hoa + "a" thuong) se BO QUA
     *  viec hop nhat (aa->â, ee->ê, oo->ô, dd->đ), giu nguyen 2 ky tu rieng
     *  biet, giong quy uoc "go lech hoa/thuong de thoat Telex" cua cac bo go
     *  Tieng Viet khac. */
    private var currentWordCased = StringBuilder()

    private var previewPopup: PopupWindow? = null
    private var previewBubble: TextView? = null

    /** Goi y sua loi Tieng Viet (xem [VietnameseAutocorrect]). */
    private var pendingSuggestion: String? = null
    private var pendingSuggestionOriginalWord: String? = null

    /** Danh dau lan thay doi selection/con tro SAP TOI trong o nhap lieu la
     *  do CHINH ban phim nay gay ra (qua commitText/deleteSurroundingText). */
    private var selfInitiatedChange = false

    /** Cache View cho trang So, Ky hieu va So-rieng (NUMPAD) - xem giai
     *  thich chi tiet o dau file (phan "TOI UU") va o [buildKeyboardContainer]/
     *  [switchMode]: CHI duoc xay dung THAT SU khi nguoi dung THAT SU chuyen
     *  toi trang do, va duoc GIU LAI xuyen suot nhieu lan [onCreateInputView]
     *  min la kich thuoc phim ([keyHeightDp]) khong doi - xem
     *  [lastBuiltKeyHeightDp]. */
    private var cachedNumbersView: View? = null
    private var cachedSymbolsView: View? = null
    private var cachedNumpadView: View? = null

    /** Kich thuoc phim ([keyHeightDp]) tai lan GAN NHAT cac trang duoc xay
     *  dung - dung de [onCreateInputView] biet co CAN xoa cache (xoay man
     *  hinh/doi chia doi cua so lam kich thuoc phim doi that su) hay KHONG
     *  can (truong hop pho bien nhat: chi chuyen o nhap/ung dung, kich thuoc
     *  man hinh khong doi) - xem giai thich "TOI UU" o dau file. */
    private var lastBuiltKeyHeightDp: Int = -1

    /** FrameLayout boc toan bo ban phim, setInputView chi goi 1 lan voi no.
     *  Khi chuyen trang, chi can doi visibility cua cac trang ben trong. */
    private var keyboardRootContainer: FrameLayout? = null
    private var lettersPageView: View? = null

    /** AudioManager dung de phat am thanh gõ phim (xem [playKeyClickTone]). */
    private val audioManager: AudioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }

    private fun playKeyClickTone() {
        try {
            audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
        } catch (e: Exception) {
            // Bo qua neu audio chua san sang (hiem gap), hoac nguoi dung da
            // tat "am thanh cham" trong Settings he thong.
        }
    }

    // ---------------------------------------------------------------------
    // KHUNG QUET QR NOI (View nong them qua WindowManager cua chinh Service)
    // ---------------------------------------------------------------------

    private val qrWindowManager: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private var qrOverlayView: View? = null
    private var qrPreviewView: PreviewView? = null
    private var qrCameraExecutor: ExecutorService? = null
    private var qrCamera: Camera? = null
    private var qrFlashOn = false
    private var qrFlashButton: Button? = null

    private var qrImageCapture: ImageCapture? = null
    private var qrCaptureButton: Button? = null
    private var qrCaptureInProgress = false
    private var qrAutoCapturePerScan = false
    private var lastCaptureTapTime = 0L
    private val captureButtonTapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingCaptureTap: Runnable? = null
    private var qrOverlayRootLayout: FrameLayout? = null
    private var qrCapturedPhotoView: android.widget.ImageView? = null
    private var qrShowingCapturedPhoto = false
    private var qrOverlaySessionKey: String? = null

    private fun editorSessionKey(info: EditorInfo?): String {
        if (info == null) return "null"
        return "${info.packageName}:${info.fieldId}:${info.inputType}"
    }

    private val qrFrameHandled = AtomicBoolean(false)
    private var qrLastDeliveredText: String? = null
    private var qrContinuousMode = false
    private var reopenQrScannerOnNextStart = false
    private var reopenQrScannerDeadline = 0L
    private var lastEditorSessionKey: String? = null

    /** THEM (theo yeu cau nguoi dung): true nghia la ban phim VUA bi TAT
     *  THAT SU (nguoi dung tat/dong ban phim - qua [onFinishInputView] voi
     *  finishingInput = true, DA duoc xac nhan qua debounce, xem
     *  [FINISH_INPUT_HIDE_DEBOUNCE_MS]) MA LUC DO khung quet QR KHONG dang
     *  mo - dung de bao [onStartInputView] lan MO LAI KE TIEP TU DONG quay
     *  ve trang Chu cai (KeyboardMode.LETTERS), BAT KE dang o CUNG mot o
     *  nhap hay khong (truoc day, cung mot o nhap se GIU NGUYEN trang dang
     *  dung, khong reset). Neu khung quet QR dang mo luc tat may (truong
     *  hop [reopenQrScannerOnNextStart] duoc dat thay vi co nay), co nay se
     *  KHONG duoc dat (giu false) - dung y "tru phi co mo qr quet" ma nguoi
     *  dung yeu cau: luc do GIU NGUYEN trang dang dung (khong ep ve Chu cai)
     *  de khung quet mo lai dung tren trang phim nhu truoc. */
    private var shouldResetModeToLettersOnNextStart = false

    private val qrToneGenerator: ToneGenerator by lazy {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    }

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
    }

    private val touchAudioAttributes: android.media.AudioAttributes by lazy {
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private val touchVibrationAttributes: android.os.VibrationAttributes by lazy {
        android.os.VibrationAttributes.Builder()
            .setUsage(android.os.VibrationAttributes.USAGE_TOUCH)
            .build()
    }

    private var loggedNoVibrator = false

    /** TOI UU (theo yeu cau nguoi dung "toi uu cho phim nhanh hon"): TRUOC
     *  DAY, MOI LAN go phim deu phai thu LAN LUOT tu buoc 1 (VibrationAttributes)
     *  xuong buoc 4 (deprecated vibrate(Long)) - tren cac may/ROM chan cac
     *  buoc dau (khong throw Exception, chi "khong rung"), dieu nay co nghia
     *  la MOI LAN go phim deu phai chiu chi phi cua 2-3 lan goi ham + log
     *  that bai truoc khi toi duoc buoc THAT SU hoat dong - lap lai HANG
     *  TRAM lan trong mot phien go nhanh, la mot phan nguyen nhan gay cam
     *  giac "chậm". GIO DAY: nho lai (cache) CHINH XAC buoc nao da THANH
     *  CONG o lan go truoc ([resolvedVibrationStep]) - tu lan go TIEP THEO,
     *  di THANG vao buoc do, KHONG thu lai cac buoc truoc nua. Chi khi buoc
     *  da luu bat ngo that bai (hiem gap, vd doi cai dat he thong giua
     *  chung) moi quay lai do tim tu dau va cache lai buoc moi. */
    private var resolvedVibrationStep = 0 // 0 = chua xac dinh, 1..4 = buoc da biet hoat dong

    private fun vibrateKeyPress() {
        if (!vibrator.hasVibrator()) {
            if (!loggedNoVibrator) {
                loggedNoVibrator = true
                android.util.Log.w("QrKeyboardService", "Thiet bi khong co dong co rung (hasVibrator = false) - thuong xay ra khi chay tren may ao (emulator)")
            }
            return
        }
        val effect = VibrationEffect.createOneShot(40L, 200)

        fun tryStep1(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            return try {
                vibrator.vibrate(effect, touchVibrationAttributes); true
            } catch (e: Exception) { false }
        }
        fun tryStep2(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            return try {
                vibrator.vibrate(effect, touchAudioAttributes); true
            } catch (e: Exception) { false }
        }
        fun tryStep3(): Boolean = try {
            vibrator.vibrate(effect, null); true
        } catch (e: Exception) { false }
        fun tryStep4() {
            try {
                @Suppress("DEPRECATION")
                vibrator.vibrate(40L)
            } catch (e: Exception) {
                android.util.Log.w("QrKeyboardService", "TAT CA cac cach rung deu that bai: ${e.message}")
            }
        }

        // Neu da biet buoc nao hoat dong tu lan truoc, di THANG vao do.
        when (resolvedVibrationStep) {
            1 -> if (tryStep1()) return else resolvedVibrationStep = 0
            2 -> if (tryStep2()) return else resolvedVibrationStep = 0
            3 -> if (tryStep3()) return else resolvedVibrationStep = 0
            4 -> { tryStep4(); return }
        }

        // Chua biet (lan dau) hoac buoc cu vua that bai - do tim lai tu dau,
        // CHI LOG mot lan duy nhat khi vua XAC DINH duoc buoc hoat dong
        // (khong log lien tuc moi phim nhu truoc).
        if (tryStep1()) {
            resolvedVibrationStep = 1
            android.util.Log.d("QrKeyboardService", "Rung OK (VibrationAttributes)")
            return
        }
        if (tryStep2()) {
            resolvedVibrationStep = 2
            android.util.Log.d("QrKeyboardService", "Rung OK (AudioAttributes)")
            return
        }
        if (tryStep3()) {
            resolvedVibrationStep = 3
            android.util.Log.d("QrKeyboardService", "Rung OK (VibrationEffect tran, khong attributes)")
            return
        }
        resolvedVibrationStep = 4
        tryStep4()
        android.util.Log.d("QrKeyboardService", "Rung OK (deprecated vibrate(Long))")
    }

    // ---------------------------------------------------------------------
    // MAU SAC: vien phat sang (co the doi) + nen sang/toi (co the doi)
    // ---------------------------------------------------------------------

    /** Mau VIEN PHAT SANG dung CHUNG cho MOI phim, tren CA 4 trang (chu cai,
     *  so, ky hieu, so-rieng NUMPAD) - xem [buildGlowKeyBackground]. TRUOC
     *  DAY la hang so co dinh (luon la mau tim) - GIO DAY la BIEN, nguoi
     *  dung co the doi sang 1 trong 8 mau qua [buildKeyboardSettingsBar]
     *  (xem [setAccentColor]). Gia tri KHOI TAO se duoc GHI DE lai trong
     *  [onCreate] bang mau da luu tu lan truoc (neu co). */
    private var glowColor: Int = DEFAULT_ACCENT_COLOR

    /** True = NEN toi (den), chu TRANG (mac dinh, giu nguyen giao dien cu).
     *  False = NEN sang (trang), chu DEN. Doi qua nut tron trong
     *  [buildKeyboardSettingsBar] - xem [toggleTheme]. */
    private var isDarkTheme: Boolean = true

    /** SharedPreferences dung de luu/doc lai mau vien + che do sang toi da
     *  chon - xem [PREFS_NAME]. */
    private val keyboardPrefs: android.content.SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    /** Danh sach 8 mau nguoi dung co the chon cho vien phim, hien duoi dang
     *  8 o vuong trong [buildKeyboardSettingsBar]. */
    private val keyboardAccentColors = listOf(
        Color.parseColor("#FF3B30"), // Do
        Color.parseColor("#3B82F6"), // Xanh duong
        Color.parseColor("#34C759"), // Luc (xanh la)
        Color.parseColor("#FFD60A"), // Vang
        Color.parseColor("#FF2D8A"), // Hong
        Color.parseColor("#FF9500"), // Cam
        DEFAULT_ACCENT_COLOR,        // Tim neon (mac dinh)
        Color.parseColor("#8B5E3C")  // Nau
    )

    private fun keyboardBackgroundColor(): Int =
        if (isDarkTheme) Color.parseColor("#050507") else Color.parseColor("#FAFAFA")

    private fun keyFillColor(): Int =
        if (isDarkTheme) Color.parseColor("#0A0A0F") else Color.parseColor("#F1F1F4")

    private fun primaryTextColor(): Int =
        if (isDarkTheme) Color.WHITE else Color.BLACK

    /** TOI UU: cache CHUOI MAU VIEN NGOAI (lop "bloom" mo) da tinh san cho
     *  MAU HIEN TAI - tranh phai goi String.format() + parseColor() (tuong
     *  doi ton chi phi) tren MOI LAN xay dung MOI phim (co hang chuc, hang
     *  tram phim duoc build moi lan mo trang). Chi tinh LAI khi mau nguon
     *  ([borderColor]) khac lan truoc. */
    private var cachedOuterGlowSourceColor: Int = 0
    private var cachedOuterGlowResultColor: Int = 0
    private var hasCachedOuterGlow = false

    private fun outerGlowColorFor(borderColor: Int): Int {
        if (hasCachedOuterGlow && borderColor == cachedOuterGlowSourceColor) {
            return cachedOuterGlowResultColor
        }
        val alpha = (Color.alpha(borderColor) * 0.25f).toInt().coerceIn(0, 255)
        val rgb = borderColor and 0x00FFFFFF
        val result = (alpha shl 24) or rgb
        cachedOuterGlowSourceColor = borderColor
        cachedOuterGlowResultColor = result
        hasCachedOuterGlow = true
        return result
    }

    /** Nen phim kieu "kinh toi + vien tim phat sang", gom 2 lop GradientDrawable
     *  chong len nhau (dung LayerDrawable):
     *   - Lop NGOAI: nen theo [keyFillColor], vien DAY hon nhung alpha THAP
     *     (~25%) -> tao cam giac quang sang lan ra ngoai (bloom gia lap).
     *   - Lop TRONG: vien MANH (mac dinh 1dp), mau [borderColor] (khong
     *     alpha) -> duong net sac.
     *  Dung CHUNG cho moi phim thuong tren CA 4 trang ban phim. */
    private fun buildGlowKeyBackground(
        cornerDp: Int = 6,
        borderColor: Int = glowColor,
        // Do day (dp) cua VIEN TRONG - dp(1) mac dinh nhu truoc. Chi cac O
        // MAU trong [buildKeyboardSettingsBar] truyen gia tri lon hon cho o
        // DANG DUOC CHON, de nguoi dung nhan biet ngay minh dang chon mau nao.
        borderWidthDp: Int = 1
    ): Drawable {
        val outerGlow = GradientDrawable().apply {
            cornerRadius = dp(cornerDp + 2).toFloat()
            setColor(keyFillColor())
            setStroke(dp(borderWidthDp + 3), outerGlowColorFor(borderColor))
        }
        val innerLine = GradientDrawable().apply {
            cornerRadius = dp(cornerDp).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(borderWidthDp), borderColor)
        }
        return LayerDrawable(arrayOf(outerGlow, innerLine)).apply {
            setLayerInset(1, dp(2), dp(2), dp(2), dp(2))
        }
    }

    private val letterRows = listOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm"
    )

    private val numberRows = listOf(
        "1234567890",
        "@#\u0111_&-+()/"
    )
    private val numberRow3Symbols = "*\"':;!?="

    /** Danh sach emoji cho hang emoji co the TRUOT NGANG (xem [buildEmojiRow]),
     *  hien tren trang so (trang thu 2). */
    private val emojiList = listOf(
        // --- Mat cuoi / Bieu cam ---
        "\ud83d\ude00", "\ud83d\ude01", "\ud83d\ude02", "\ud83e\udd23", "\ud83d\ude03",
        "\ud83d\ude04", "\ud83d\ude05", "\ud83d\ude06", "\ud83d\ude09", "\ud83d\ude0a",
        "\ud83d\ude0d", "\ud83e\udd70", "\ud83d\ude18", "\ud83d\ude17", "\ud83d\ude19",
        "\ud83d\ude1a", "\ud83d\ude0b", "\ud83d\ude0e", "\ud83e\udd13", "\ud83e\udd17",
        "\ud83e\udd14", "\ud83d\ude10", "\ud83d\ude11", "\ud83d\ude36", "\ud83d\ude0f",
        "\ud83d\ude0c", "\ud83d\ude14", "\ud83d\ude2a", "\ud83d\ude34", "\ud83d\ude16",
        "\ud83d\ude1e", "\ud83d\ude15", "\ud83d\ude22", "\ud83d\ude2d", "\ud83d\ude20",
        "\ud83d\ude21", "\ud83e\udd2c", "\ud83e\udd2f", "\ud83d\ude31", "\ud83d\ude28",
        "\ud83d\ude30", "\ud83d\ude33", "\ud83e\udd75", "\ud83e\udd76", "\ud83d\ude35",
        "\ud83e\udd74", "\ud83e\udd22", "\ud83e\udd27", "\ud83d\ude37", "\ud83e\udd12",
        "\ud83e\udd11", "\ud83e\udd20", "\ud83e\udd21", "\ud83d\ude08", "\ud83d\udc7f",
        "\ud83d\udc80", "\ud83d\udc7d", "\ud83e\udd16",
        // --- Cu chi / Tay ---
        "\ud83d\udc4d", "\ud83d\udc4e", "\ud83d\udc4f", "\ud83d\ude4f", "\ud83e\udd1d",
        "\u270c\ufe0f", "\ud83e\udd1e", "\ud83e\udd1f", "\ud83e\udd18", "\ud83d\udc4c",
        "\ud83e\udd19", "\u261d\ufe0f", "\ud83d\udc46", "\ud83d\udc47", "\ud83d\udc48",
        "\ud83d\udc49", "\ud83d\udc4a", "\u270a", "\ud83e\udd1b", "\ud83e\udd1c",
        "\ud83d\udc4b", "\ud83e\udd1a", "\ud83d\udd90\ufe0f", "\u270b", "\ud83d\udc4e",
        "\ud83d\udcaa", "\ud83d\ude4c", "\ud83d\ude4b", "\ud83e\udd26", "\ud83e\udd37",
        // --- Tim / Cam xuc ---
        "\u2764\ufe0f", "\ud83e\udde1", "\ud83d\udc9b", "\ud83d\udc9a", "\ud83d\udc99",
        "\ud83d\udc9c", "\ud83d\udc97", "\ud83d\udc96", "\ud83d\udc95", "\ud83d\udc94",
        "\u2665\ufe0f", "\ud83d\udcaf", "\u2b50", "\ud83c\udf1f", "\ud83d\udd25",
        "\u26a1", "\ud83c\udf89", "\ud83c\udf8a",
        // --- Do an / Uong ---
        "\ud83c\udf55", "\ud83c\udf54", "\ud83c\udf5c", "\ud83c\udf5b", "\ud83c\udf63",
        "\ud83c\udf62", "\ud83e\udd6a", "\ud83c\udf2e", "\ud83c\udf2f", "\ud83e\udd57",
        "\ud83c\udf70", "\ud83c\udf82", "\ud83c\udf69", "\ud83c\udf6a", "\ud83c\udf6b",
        "\ud83c\udf6c", "\ud83c\udf6d", "\ud83e\udd64", "\ud83c\udf7a", "\ud83c\udf77",
        "\u2615", "\ud83c\udf75", "\ud83c\udf7d\ufe0f", "\ud83e\udd51", "\ud83c\udf4e",
        "\ud83c\udf4a", "\ud83c\udf4b", "\ud83c\udf49", "\ud83c\udf53", "\ud83c\udf47",
        // --- Dong vat ---
        "\ud83d\udc36", "\ud83d\udc31", "\ud83d\udc2d", "\ud83d\udc39", "\ud83d\udc30",
        "\ud83d\udc3b", "\ud83d\udc3c", "\ud83d\udc28", "\ud83d\udc2f", "\ud83e\udd81",
        "\ud83d\udc2e", "\ud83d\udc37", "\ud83d\udc24", "\ud83d\udc27", "\ud83d\udc26",
        "\ud83e\udd85", "\ud83d\udc2c", "\ud83d\udc33", "\ud83d\udc20", "\ud83d\udc19",
        "\ud83e\udd8b",
        // --- Thien nhien / Thoi tiet ---
        "\u2600\ufe0f", "\ud83c\udf24\ufe0f", "\u2601\ufe0f", "\ud83c\udf27\ufe0f", "\u26c4",
        "\ud83c\udf08", "\ud83c\udf0a", "\ud83c\udf38", "\ud83c\udf39", "\ud83c\udf3b",
        "\ud83c\udf3c", "\ud83c\udf40", "\ud83c\udf41", "\ud83c\udf3f", "\ud83c\udf0d",
        "\ud83c\udf19", "\ud83d\udc4b",
        // --- Di chuyen / Dia diem ---
        "\ud83d\ude97", "\ud83d\ude8c", "\ud83d\ude82", "\ud83d\udea2", "\u2708\ufe0f",
        "\ud83d\ude80", "\ud83d\udeb2", "\ud83c\udfe0", "\ud83c\udfe2", "\ud83c\udfd6\ufe0f",
        "\ud83c\udfd4\ufe0f", "\ud83d\uddfa\ufe0f", "\ud83d\udccd", "\ud83c\udf0f",
        // --- Do vat / Cong nghe ---
        "\ud83d\udcf1", "\ud83d\udcbb", "\u2328\ufe0f", "\ud83d\udda5\ufe0f", "\ud83d\udcf7",
        "\ud83c\udfa4", "\ud83c\udfa7", "\ud83d\udcfa", "\ud83d\udcda", "\ud83d\udcdd",
        "\ud83d\udce7", "\ud83d\udd14", "\ud83d\udcb0", "\ud83d\udcb3", "\ud83c\udf81",
        "\ud83d\udd12", "\ud83d\udd13", "\ud83d\udd0d", "\u2705", "\u274c",
        "\u23f0", "\ud83d\udcca", "\ud83d\udcc8", "\ud83d\udcc9"
    )

    private val extendedSymbolRows = listOf(
        "~`|\u2022\u221a\u03c0\u00f7\u00d7\u00b6\u0394",
        "<>$\u00a2^\u00b0={}\\"
    )
    private val extendedSymbolRow3 = "%\u00a9\u00ae\u2122\u00a7\u00b1[]"

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** LOI nguoi dung phan anh: man hinh NGANG (landscape) hoac cua so hep
     *  khien toan bo ban phim bi cat mat/khuat o phia tren. Tinh chieu cao
     *  MOI phim dua theo [screenHeightDp] THUC TE cua man hinh hien tai. */
    private val keyHeightDp: Int
        get() {
            val screenHeightDp = resources.configuration.screenHeightDp
            return when {
                screenHeightDp <= 400 -> 34
                screenHeightDp <= 550 -> 42
                else -> 48
            }
        }

    /** TOI UU (xem giai thich day du o dau file): CHI xoa cache cac trang
     *  So/Ky hieu/So-rieng khi kich thuoc phim THAT SU doi (xoay man hinh...);
     *  neu KHONG doi (truong hop pho bien nhat: chi chuyen o nhap/ung dung),
     *  GIU LAI cac View da cache, chi go chung ra khoi container CU (neu
     *  dang gan o do) de tranh crash "already has a parent" khi gan lai vao
     *  container MOI ngay sau day trong [buildKeyboardContainer]. */
    override fun onCreateInputView(): View {
        val currentKeyHeight = keyHeightDp
        if (currentKeyHeight != lastBuiltKeyHeightDp) {
            cachedNumbersView = null
            cachedSymbolsView = null
            cachedNumpadView = null
            lastBuiltKeyHeightDp = currentKeyHeight
        } else {
            detachFromParentIfAny(cachedNumbersView)
            detachFromParentIfAny(cachedSymbolsView)
            detachFromParentIfAny(cachedNumpadView)
        }
        keyboardRootContainer = null
        lettersPageView = null
        return buildKeyboardContainer()
    }

    /** Go [view] ra khoi ViewGroup cha hien tai cua no (neu co) - dung truoc
     *  khi gan lai mot View DA CACHE vao mot container KHAC, tranh
     *  IllegalStateException "The specified child already has a parent". */
    private fun detachFromParentIfAny(view: View?) {
        if (view == null) return
        (view.parent as? ViewGroup)?.removeView(view)
    }

    /** Tao container boc toi da 4 trang ban phim. TOI UU: KHONG con xay dung
     *  ca 4 trang cung luc nhu truoc - CHI trang LETTERS (luon can, mang
     *  trang thai dong: Shift/ngon ngu) duoc build NGAY, cong THEM DUY NHAT
     *  trang dang TRUNG voi [mode] hien tai (neu khac LETTERS). 3 trang con
     *  lai duoc de danh, CHI xay dung THAT SU khi nguoi dung chuyen toi qua
     *  [switchMode] - xem giai thich day du ("TOI UU") o dau file. */
    private fun buildKeyboardContainer(): View {
        val container = FrameLayout(this).apply {
            setBackgroundColor(keyboardBackgroundColor())
        }
        keyboardRootContainer = container

        val letters = buildLettersPage()
        lettersPageView = letters
        container.addView(letters)
        letters.visibility = if (mode == KeyboardMode.LETTERS) View.VISIBLE else View.GONE

        when (mode) {
            KeyboardMode.NUMBERS -> {
                val numbers = cachedNumbersView ?: buildNumbersPage().also { cachedNumbersView = it }
                container.addView(numbers)
                numbers.visibility = View.VISIBLE
            }
            KeyboardMode.SYMBOLS -> {
                val symbols = cachedSymbolsView ?: buildSymbolsPage().also { cachedSymbolsView = it }
                container.addView(symbols)
                symbols.visibility = View.VISIBLE
            }
            KeyboardMode.NUMPAD -> {
                val numpad = cachedNumpadView ?: buildNumpadPage().also { cachedNumpadView = it }
                container.addView(numpad)
                numpad.visibility = View.VISIBLE
            }
            KeyboardMode.LETTERS -> { /* da them o tren */ }
        }
        return container
    }

    /** Cap nhat visibility cho tat ca cac trang HIEN CO (cac trang con lai -
     *  [numbers]/[symbols]/[numpad] - co the null neu CHUA duoc xay dung,
     *  xem giai thich "TOI UU" o dau file: khong con bat buoc phai ton tai
     *  san nhu truoc). */
    private fun applyModeVisibility(
        container: FrameLayout,
        letters: View, numbers: View?, symbols: View?, numpad: View?
    ) {
        letters.visibility = if (mode == KeyboardMode.LETTERS) View.VISIBLE else View.GONE
        numbers?.visibility = if (mode == KeyboardMode.NUMBERS) View.VISIBLE else View.GONE
        symbols?.visibility = if (mode == KeyboardMode.SYMBOLS) View.VISIBLE else View.GONE
        numpad?.visibility = if (mode == KeyboardMode.NUMPAD) View.VISIBLE else View.GONE
    }

    /** Build trang chu cai (LETTERS). Ham nay duoc goi moi khi can cap nhat
     *  trang thai dong (Shift on/off, ngon ngu, pendingSuggestion). */
    private fun buildLettersPage(): View {
        val verticalPaddingDp = if (keyHeightDp < 48) 2 else 6
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(verticalPaddingDp), dp(4), dp(verticalPaddingDp + EXTRA_BOTTOM_LIFT_DP))
        }

        when (mode) {
            KeyboardMode.LETTERS -> {
                if (pendingSuggestion != null) {
                    root.addView(buildAutocorrectSuggestionRow())
                }
                root.addView(buildCharRow(numberRows[0]))
                letterRows.forEachIndexed { index, row ->
                    val rowView = buildCharRow(row, applyShiftCase = true)
                    if (index == letterRows.lastIndex) {
                        rowView.addView(
                            buildKey(
                                "\u2b06", weight = 1.5f,
                                highlight = isShiftOn || showCapitalPreview
                            ) {
                                val now = android.os.SystemClock.uptimeMillis()
                                val isDoubleTap = now - lastShiftTapTime <= SHIFT_DOUBLE_TAP_MAX_INTERVAL_MS
                                lastShiftTapTime = if (isDoubleTap) 0L else now
                                when {
                                    isDoubleTap -> {
                                        isShiftOn = !isShiftOn
                                        capitalizeNextLetter = false
                                        showCapitalPreview = false
                                        capitalizeAppliedAtPrefixLen = null
                                    }
                                    isShiftOn -> {
                                        isShiftOn = false
                                        capitalizeNextLetter = false
                                        showCapitalPreview = false
                                        capitalizeAppliedAtPrefixLen = null
                                    }
                                    else -> {
                                        capitalizeNextLetter = !capitalizeNextLetter
                                        showCapitalPreview = capitalizeNextLetter
                                        capitalizeAppliedAtPrefixLen = null
                                    }
                                }
                                redrawKeyboard()
                            },
                            0
                        )
                        rowView.addView(buildKey("\u232b", weight = 1.5f, onRepeat = { deleteChar() }) { deleteChar() })
                    }
                    root.addView(rowView)
                }
                root.addView(buildLettersBottomRow())
            }
            else -> { /* NUMBERS/SYMBOLS/NUMPAD duoc xu ly o cac ham build*Page rieng */ }
        }

        return root
    }

    /** Build trang so (NUMBERS) - CHI duoc goi khi nguoi dung THAT SU chuyen
     *  toi trang nay (xem [buildKeyboardContainer]/[switchMode]), ket qua
     *  duoc cache lai qua [cachedNumbersView]. */
    private fun buildNumbersPage(): View {
        val verticalPaddingDp = if (keyHeightDp < 48) 2 else 6
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(verticalPaddingDp), dp(4), dp(verticalPaddingDp + EXTRA_BOTTOM_LIFT_DP))
            addView(buildEmojiRow())
            numberRows.forEach { row -> addView(buildCharRow(row)) }
            addView(buildNumbersRow3())
            addView(buildNumbersBottomRow())
        }
    }

    /** Build trang ky hieu (SYMBOLS) - CHI duoc goi khi nguoi dung THAT SU
     *  chuyen toi trang nay, ket qua duoc cache lai qua [cachedSymbolsView].
     *  THEM: [buildKeyboardSettingsBar] - thanh chon mau vien + sang/toi,
     *  hien san ngay tren trang nay (khong can nut bat/tat rieng). */
    private fun buildSymbolsPage(): View {
        val verticalPaddingDp = if (keyHeightDp < 48) 2 else 6
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(verticalPaddingDp), dp(4), dp(verticalPaddingDp + EXTRA_BOTTOM_LIFT_DP))
            extendedSymbolRows.forEach { row -> addView(buildCharRow(row)) }
            addView(buildExtendedSymbolsRow3())
            addView(buildKeyboardSettingsBar())
            addView(buildExtendedSymbolsBottomRow())
        }
    }

    /** Trang BAN PHIM SO rieng (dang ban phim PIN/dien thoai: 3 hang so +
     *  mot hang hanh dong duoi cung), duoc TU DONG chon khi mo mot o nhap
     *  CHI NHAN SO (xem [isNumericOnlyField]). Dong bo HOAN TOAN cach dung
     *  phim (glow key background qua [buildKey]/[buildCharRow]) VA cau truc
     *  hang DUOI CUNG voi CA BA trang con lai - moi trang chi co DUY NHAT
     *  mot hang hanh dong o day cung, dung chung mot chieu cao co dinh
     *  dp(keyHeightDp + 2) + bottomMargin dp(6) + fillRowHeight = true cho
     *  moi phim trong hang do. */
    private fun buildNumpadPage(): View {
        val verticalPaddingDp = if (keyHeightDp < 48) 2 else 6
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(verticalPaddingDp), dp(4), dp(verticalPaddingDp + EXTRA_BOTTOM_LIFT_DP))
        }

        root.addView(buildCharRow("123"))
        root.addView(buildCharRow("456"))
        root.addView(buildCharRow("789"))

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(keyHeightDp + 2)
            ).apply { bottomMargin = dp(6) }
        }
        bottomRow.addView(buildKey("ABC", weight = 1.4f, fillRowHeight = true) { switchMode(KeyboardMode.LETTERS) })
        bottomRow.addView(buildKey("0", weight = 1f, fillRowHeight = true) { insertText("0") })
        bottomRow.addView(buildKey("\u232b", weight = 1.4f, fillRowHeight = true, onRepeat = { deleteChar() }) { deleteChar() })
        bottomRow.addView(buildKey("\u21b5", weight = 1.4f, highlight = true, fillRowHeight = true) { sendEnter() })
        root.addView(bottomRow)

        return root
    }

    /** True neu [info] khai bao o nhap CHI NHAN SO (vd o nhap ma PIN/OTP -
     *  TYPE_CLASS_NUMBER, hoac o nhap so dien thoai - TYPE_CLASS_PHONE).
     *  Dung de TU DONG chuyen sang [KeyboardMode.NUMPAD] - xem [onStartInputView]. */
    private fun isNumericOnlyField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        return inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE
    }

    /** True neu [info] khai bao o nhap la MAT KHAU (password) - ca lop TEXT
     *  (mat khau thuong/web/hien ro chu) lan lop NUMBER (ma PIN kieu mat
     *  khau so). Dung de TU DONG chuyen ve TIENG ANH khi mo cac o nhap loai
     *  nay - xem [onStartInputView]: go mat khau bang Telex Tieng Viet gan
     *  nhu luon SAI, nen tu dong tat Telex de tranh go nham. */
    private fun isPasswordField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val inputType = info.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** Chuyen sang trang [newMode]. TOI UU: chi build LAZY DUY NHAT trang
     *  DICH (neu chua co cache) - KHONG dong thoi build ca 3 trang con lai
     *  nhu code truoc day tung lam trong [redrawKeyboard]. Neu container
     *  chua duoc tao (hiem gap), fallback ve buildKeyboardContainer. */
    private fun switchMode(newMode: KeyboardMode) {
        mode = newMode
        val container = keyboardRootContainer
        val letters = lettersPageView
        if (container == null || letters == null) {
            setInputView(buildKeyboardContainer())
            return
        }
        when (newMode) {
            KeyboardMode.LETTERS -> { /* da co san */ }
            KeyboardMode.NUMBERS -> if (cachedNumbersView == null) {
                cachedNumbersView = buildNumbersPage().also { container.addView(it) }
            }
            KeyboardMode.SYMBOLS -> if (cachedSymbolsView == null) {
                cachedSymbolsView = buildSymbolsPage().also { container.addView(it) }
            }
            KeyboardMode.NUMPAD -> if (cachedNumpadView == null) {
                cachedNumpadView = buildNumpadPage().also { container.addView(it) }
            }
        }
        applyModeVisibility(container, letters, cachedNumbersView, cachedSymbolsView, cachedNumpadView)
    }

    /** Ve lai trang LETTERS (Shift/ngon ngu doi) va cap nhat visibility.
     *  TOI UU: KHONG con ep build cachedNumbersView/cachedSymbolsView/
     *  cachedNumpadView "cho chac" moi lan goi ham nay nhu truoc (dieu do
     *  TRUOC DAY khien vieC go phim/doi Shift tren trang Chu cai vo tinh kich
     *  hoat xay dung ca hang emoji 150 nut + trang Ky hieu, gay giat/khung -
     *  xem giai thich "TOI UU" o dau file) - chi dung [applyModeVisibility]
     *  voi CAC TRANG DA CO (co the null), cac trang chua build van GIU
     *  NGUYEN trang thai "chua build", se duoc build dung luc qua
     *  [switchMode] khi nguoi dung THAT SU can toi. */
    private fun redrawKeyboard() {
        val container = keyboardRootContainer
        if (container != null && mode == KeyboardMode.LETTERS) {
            lettersPageView?.let { container.removeView(it) }
            val newLetters = buildLettersPage()
            lettersPageView = newLetters
            container.addView(newLetters, 0)
            applyModeVisibility(container, newLetters, cachedNumbersView, cachedSymbolsView, cachedNumpadView)
        } else {
            setInputView(buildKeyboardContainer())
        }
    }

    /** Ban phim mo lai o mot o nhap THAT SU MOI se quay ve trang mac dinh
     *  phu hop (chu cai, hoac SO RIENG neu la o nhap chi nhan so - xem
     *  [isNumericOnlyField]); neu chi la ban phim tu tat/bat lai bat ngo tren
     *  CUNG mot o nhap cu (gian doan TAM THOI, khong phai nguoi dung THAT SU
     *  tat ban phim) thi GIU NGUYEN trang phim dang dung truoc do. THEM
     *  (theo yeu cau nguoi dung): neu nguoi dung THAT SU tat han ban phim
     *  roi bat lai (xac nhan qua debounce trong [onFinishInputView]) - CHO
     *  DU la cung mot o nhap cu - se TU DONG quay ve trang Chu cai, TRU PHI
     *  khung quet QR dang mo luc do (xem [shouldResetModeToLettersOnNextStart]).
     *  THEM: o nhap MAT KHAU se TU DONG tat Telex Tieng Viet neu dang bat -
     *  xem [isPasswordField]. */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        val sessionKey = editorSessionKey(info)
        val isSameFieldAsBefore = sessionKey == lastEditorSessionKey
        lastEditorSessionKey = sessionKey

        // THEM (theo yeu cau nguoi dung): "tat/bat lai ban phim thi auto ve
        // trang dau go chu, tru phi co mo qr quet" - doc + tieu thu ngay co
        // [shouldResetModeToLettersOnNextStart] (duoc [onFinishInputView]
        // dat khi ban phim THAT SU tat ma khung quet QR KHONG dang mo) tai
        // day, TRUOC khi bat ky nhanh nao khac co the doi [mode] - dam bao
        // co nay LUON duoc tieu thu dung 1 lan cho MOI lan mo ban phim, du
        // sau day la nhanh "cung o nhap" hay "o nhap khac".
        val forceLettersReset = shouldResetModeToLettersOnNextStart
        shouldResetModeToLettersOnNextStart = false

        if (qrOverlayView != null && sessionKey != qrOverlaySessionKey) {
            hideQrOverlay()
        }

        cancelPendingFinishHide()

        if (reopenQrScannerOnNextStart) {
            reopenQrScannerOnNextStart = false
            if (android.os.SystemClock.uptimeMillis() <= reopenQrScannerDeadline) {
                openQrScanner(continuous = qrContinuousMode)
            }
        }

        // O nhap MAT KHAU vua duoc mo (khac o nhap truoc do) - TU DONG tat
        // che do go Tieng Viet (Telex) neu dang bat, ve lai Tieng Anh - xem
        // giai thich chi tiet o [isPasswordField]. Dieu kien
        // "!isSameFieldAsBefore" de KHONG lam phien nguoi dung neu ho da tu
        // CHU DONG bat lai Tieng Viet ngay tren CHINH o mat khau nay trong
        // luc ban phim bi he thong tai tao View tam thoi.
        if (!isSameFieldAsBefore && isPasswordField(info) && isVietnameseMode) {
            isVietnameseMode = false
            currentWord.clear()
        }

        currentWord.clear()
        capitalizeNextLetter = false
        showCapitalPreview = false
        capitalizeAppliedAtPrefixLen = null
        val hadPendingSuggestion = pendingSuggestion != null
        clearAutocorrectSuggestion()
        if (!isSameFieldAsBefore) {
            // O nhap/ung dung THAT SU khac truoc - chon trang mac dinh: neu
            // la o nhap CHI NHAN SO (vd o nhap ma PIN/OTP, so dien thoai -
            // xem [isNumericOnlyField]), TU DONG mo trang ban phim SO RIENG
            // (NUMPAD - xem [buildNumpadPage]). Cac o nhap khac (chu cai/hon
            // hop) van ve trang chu cai nhu cu.
            val targetMode = if (isNumericOnlyField(info)) KeyboardMode.NUMPAD else KeyboardMode.LETTERS
            if (mode != targetMode) {
                switchMode(targetMode)
            }

            // TU DONG VIET HOA chu cai dau tien cua o nhap MOI - CHI co y
            // nghia voi trang chu cai, khong ap dung cho o nhap SO (NUMPAD).
            // Chi ap dung khi o nhap dang THAT SU TRONG (chua co ky tu nao
            // truoc con tro), tranh viet hoa oan khi nguoi dung quay lai o
            // nhap DA CO SAN noi dung.
            val didAutoCapitalize = if (targetMode == KeyboardMode.LETTERS) {
                val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(1, 0)
                val isEmpty = textBeforeCursor.isNullOrEmpty()
                if (isEmpty) {
                    capitalizeNextLetter = true
                    showCapitalPreview = true
                }
                isEmpty
            } else {
                false
            }

            // SUA LOI: dieu kien cu "targetMode == mode" LUON DUNG (vi [mode]
            // da duoc gan = [targetMode] boi khoi if ngay tren day), nen no
            // khong loc duoc gi ca - voi o nhap MA PIN dang MAT KHAU (targetMode
            // = NUMPAD), dieu kien cu VAN kich hoat redrawKeyboard(), va vi
            // [mode] luc do la NUMPAD (khac LETTERS), [redrawKeyboard] roi vao
            // nhanh REBUILD TOAN BO container (setInputView) mot cach vo ich
            // - pha vo toi uu lazy-build moi khi mo o nhap loai nay. GIO DAY:
            // chi redraw vi ly do "mat khau" khi THAT SU dang o trang LETTERS
            // (trang duy nhat co hien thi nhan V/EN tren phim cach can cap
            // nhat) - o NUMPAD khong co gi can ve lai nen bo qua.
            if (didAutoCapitalize || hadPendingSuggestion || (isPasswordField(info) && targetMode == KeyboardMode.LETTERS)) {
                redrawKeyboard()
            }
        } else if (forceLettersReset) {
            // THEM: ban phim VUA duoc "bat lai" sau khi THAT SU bi tat truoc
            // do (khong phai gian doan tam thoi), va khung quet QR KHONG
            // dang mo luc do - tu dong quay ve trang Chu cai, du dang o
            // CUNG mot o nhap cu (truong hop nay TRUOC DAY se GIU NGUYEN
            // trang dang dung, gio theo yeu cau moi se RESET ve Chu cai).
            if (mode != KeyboardMode.LETTERS) {
                switchMode(KeyboardMode.LETTERS)
            } else if (hadPendingSuggestion) {
                redrawKeyboard()
            }
        } else if (hadPendingSuggestion) {
            redrawKeyboard()
        }
    }

    /** Mot hang phim don gian: moi ky tu trong chuoi la mot nut cung do rong
     *  bang nhau (weight 1), chen nguyen van ky tu do khi bam. Dung chung cho
     *  ca hang chu cai (co ap dung Shift de HIEN THI hoa/thuong) lan hang
     *  so/ky hieu/NUMPAD. */
    private fun buildCharRow(chars: String, applyShiftCase: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        chars.forEach { ch ->
            val label = if (applyShiftCase && (isShiftOn || showCapitalPreview)) ch.uppercaseChar().toString() else ch.toString()
            row.addView(buildKey(label) { insertChar(ch) })
        }
        return row
    }

    /** Hang emoji co the TRUOT (vuot) NGANG bang tay. TOI UU: TRUOC DAY moi
     *  MOT trong ~150 nut emoji tu tao RIENG mot Drawable nen (2 lop
     *  GradientDrawable long nhau) - tuc ~150 lan phan bo Drawable object
     *  chi de hien thi CUNG mot kieu vien/mau giong het nhau. GIO DAY: build
     *  DUY NHAT MOT Drawable nen dung CHUNG cho CA HANG (moi nut co CUNG
     *  kich thuoc [emojiKeySizePx] nen chia se an toan), giam so luong
     *  Drawable object phai cap phat tu ~150 xuong con 1 - day la phan ton
     *  chi phi LON NHAT khi xay dung trang So (chi xay ra 1 lan nho co che
     *  cache [cachedNumbersView], nhung van dang gay giat neu la lan dau
     *  nguoi dung mo trang nay). */
    private fun buildEmojiRow(): View {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val emojiKeySizePx = dp(keyHeightDp - 8)
        val sharedEmojiBg = buildGlowKeyBackground(cornerDp = 4)
        emojiList.forEach { emoji ->
            val btn = Button(this).apply {
                text = emoji
                isAllCaps = false
                textSize = 20f
                includeFontPadding = true
                isSingleLine = true
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                gravity = Gravity.CENTER
                background = sharedEmojiBg
                stateListAnimator = null
                elevation = 0f
                outlineProvider = null
                isHapticFeedbackEnabled = true
                layoutParams = LinearLayout.LayoutParams(emojiKeySizePx, emojiKeySizePx).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                setOnClickListener {
                    vibrateKeyPress()
                    playKeyClickTone()
                    insertText(emoji)
                }
            }
            inner.addView(btn)
        }

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(keyHeightDp - 2)
            )
            addView(inner)
        }
    }

    /** Thanh goi y sua loi Tieng Viet: mot nut lon hien "Sua thanh: ..."
     *  va mot nut nho "\u2715" de bo qua goi y nay. */
    private fun buildAutocorrectSuggestionRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val suggestion = pendingSuggestion
        if (suggestion == null) return row

        val bg = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(Color.parseColor("#1A0F2E"))
            setStroke(dp(1), glowColor)
        }
        val suggestionBtn = Button(this).apply {
            text = "Sua th\u00e0nh: \u201c$suggestion\u201d"
            isAllCaps = false
            setTextColor(Color.parseColor("#D4BBFF"))
            textSize = 13f
            includeFontPadding = true
            isSingleLine = true
            background = bg
            gravity = Gravity.CENTER
            stateListAnimator = null
            elevation = 0f
            outlineProvider = null
            layoutParams = LinearLayout.LayoutParams(0, dp(keyHeightDp - 8), 6f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            setOnClickListener {
                vibrateKeyPress()
                playKeyClickTone()
                acceptAutocorrectSuggestion()
            }
        }
        row.addView(suggestionBtn)
        row.addView(buildKey("\u2715", weight = 1.2f) {
            clearAutocorrectSuggestion()
            redrawKeyboard()
        })
        return row
    }

    private fun checkAutocorrectSuggestion(word: String) {
        val suggestion = VietnameseAutocorrect.suggestFor(applicationContext, word)
        if (suggestion != null && suggestion != word) {
            pendingSuggestion = suggestion
            pendingSuggestionOriginalWord = word
            redrawKeyboard()
        } else {
            clearAutocorrectSuggestion()
        }
    }

    private fun acceptAutocorrectSuggestion() {
        val original = pendingSuggestionOriginalWord ?: return
        val suggestion = pendingSuggestion ?: return
        val ic = currentInputConnection
        if (ic != null) {
            selfInitiatedChange = true
            ic.beginBatchEdit()
            try {
                ic.deleteSurroundingText(original.length + 1, 0)
                ic.commitText("$suggestion ", 1)
            } finally {
                ic.endBatchEdit()
            }
        }
        clearAutocorrectSuggestion()
        redrawKeyboard()
    }

    private fun clearAutocorrectSuggestion() {
        if (pendingSuggestion != null || pendingSuggestionOriginalWord != null) {
            pendingSuggestion = null
            pendingSuggestionOriginalWord = null
        }
    }

    /** Hang duoi cung trang chu cai: "," / phim cach / "." / Enter, cung
     *  dung MOT chieu cao co dinh de tat ca phim chia deu, khong lech nhau. */
    private fun buildLettersBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(keyHeightDp + 2)
            ).apply {
                bottomMargin = dp(6)
            }
        }

        row.addView(buildKey("?123", weight = 1.4f, fillRowHeight = true) { switchMode(KeyboardMode.NUMBERS) })
        row.addView(buildKey(",", weight = 1f, fillRowHeight = true) { insertText(",") })
        row.addView(buildSpaceKey(weight = 4.2f))
        row.addView(buildKey(".", weight = 1f, fillRowHeight = true) {
            insertText(".")
            capitalizeNextLetter = true
            showCapitalPreview = true
            capitalizeAppliedAtPrefixLen = null
        })
        row.addView(buildKey("\u21b5", weight = 1.4f, highlight = true, fillRowHeight = true) { sendEnter() })

        return row
    }

    private fun buildNumbersRow3(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(buildKey("=\\<", weight = 1.3f) { switchMode(KeyboardMode.SYMBOLS) })
        numberRow3Symbols.forEach { ch ->
            row.addView(buildKey(ch.toString(), weight = 1f) { insertText(ch.toString()) })
        }
        row.addView(buildKey("\u232b", weight = 1.3f, onRepeat = { deleteChar() }) { deleteChar() })

        return row
    }

    /** Hang duoi cung cua trang so: nut "QR" - SUA theo yeu cau nguoi dung:
     *  CA cham 1 lan LAN dup-tap gio DEU mo khung quet o CHE DO QUET LIEN
     *  TUC (continuous = true) - CHI dong khi nguoi dung tu bam "Huy" tren
     *  khung quet. Co che PHAT HIEN dup-tap ([isDoubleTap]) van GIU NGUYEN
     *  (tinh toan y het truoc), chi khac o CHO SU DUNG ket qua do. */
    private fun buildNumbersBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(keyHeightDp + 2)
            ).apply { bottomMargin = dp(6) }
        }

        row.addView(buildKey("ABC", weight = 1.6f, fillRowHeight = true) { switchMode(KeyboardMode.LETTERS) })
        row.addView(buildKey("QR", weight = 1.2f, highlight = true, fillRowHeight = true) {
            val now = android.os.SystemClock.uptimeMillis()
            val isDoubleTap = now - lastQrKeyTapTime <= QR_DOUBLE_TAP_MAX_INTERVAL_MS
            lastQrKeyTapTime = if (isDoubleTap) 0L else now
            // SUA: du la cham 1 lan hay dup-tap, deu mo khung quet o CHE DO
            // LIEN TUC (continuous = true) - xem giai thich o dau ham nay.
            openQrScanner(continuous = true)
        })
        row.addView(buildSpaceKey(weight = 5.4f))
        row.addView(buildKey("\u21b5", weight = 1.6f, highlight = true, fillRowHeight = true) { sendEnter() })

        return row
    }

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
        row.addView(buildKey("\u232b", weight = 1.3f, onRepeat = { deleteChar() }) { deleteChar() })

        return row
    }

    private fun buildExtendedSymbolsBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(keyHeightDp + 2)
            ).apply { bottomMargin = dp(6) }
        }

        row.addView(buildKey("ABC", weight = 1.4f, fillRowHeight = true) { switchMode(KeyboardMode.LETTERS) })
        row.addView(buildSpaceKey(weight = 4.8f))
        row.addView(buildKey("\u21b5", weight = 1.4f, highlight = true, fillRowHeight = true) { sendEnter() })

        return row
    }

    /** "Thanh cai dat" mau vien + nen sang/toi cho toan bo ban phim - hien
     *  san tren trang Ky hieu mo rong, khong can nut bat/tat rieng. Xem giai
     *  thich chi tiet ve bo cuc/hanh vi trong tai lieu dau file. */
    private fun buildKeyboardSettingsBar(): View {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val swatchSizePx = dp((keyHeightDp - 10).coerceAtLeast(24))

        keyboardAccentColors.forEach { color ->
            val isSelected = color == glowColor
            val swatch = Button(this).apply {
                text = ""
                isAllCaps = false
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                stateListAnimator = null
                elevation = 0f
                outlineProvider = null
                background = buildGlowKeyBackground(
                    cornerDp = 6,
                    borderColor = color,
                    borderWidthDp = if (isSelected) 3 else 1
                )
                layoutParams = LinearLayout.LayoutParams(swatchSizePx, swatchSizePx).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                isHapticFeedbackEnabled = true
                setOnClickListener {
                    vibrateKeyPress()
                    playKeyClickTone()
                    setAccentColor(color)
                }
            }
            inner.addView(swatch)
        }

        val toggleBtn = Button(this).apply {
            text = ""
            isAllCaps = false
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            stateListAnimator = null
            elevation = 0f
            outlineProvider = null
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isDarkTheme) Color.WHITE else Color.BLACK)
                setStroke(dp(1), glowColor)
            }
            layoutParams = LinearLayout.LayoutParams(swatchSizePx, swatchSizePx).apply {
                setMargins(dp(12), dp(3), dp(3), dp(3))
            }
            isHapticFeedbackEnabled = true
            setOnClickListener {
                vibrateKeyPress()
                playKeyClickTone()
                toggleTheme()
            }
        }
        inner.addView(toggleBtn)

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, swatchSizePx + dp(6)
            )
            addView(inner)
        }
    }

    private fun setAccentColor(color: Int) {
        if (glowColor == color) return
        glowColor = color
        keyboardPrefs.edit().putInt(PREF_ACCENT_COLOR, color).apply()
        rebuildAllKeyboardPages()
    }

    private fun toggleTheme() {
        isDarkTheme = !isDarkTheme
        keyboardPrefs.edit().putBoolean(PREF_IS_DARK_THEME, isDarkTheme).apply()
        rebuildAllKeyboardPages()
    }

    /** Xoa TOAN BO cache (ca 4 trang) roi rebuild container tu dau - can
     *  thiet khi doi MAU VIEN hoac NEN/CHU (mau da "nuong" san vao tung
     *  Drawable/mau chu ngay luc build). [mode] (dang o trang nao) VAN GIU
     *  NGUYEN. Chi goi khi nguoi dung THAT SU doi cai dat (hiem gap, khong
     *  phai moi lan go phim) nen chi phi rebuild toan bo la chap nhan duoc -
     *  KHONG anh huong toi toi uu "lazy build" ap dung cho luong go phim
     *  binh thuong. */
    private fun rebuildAllKeyboardPages() {
        // SUA LOI nguoi dung phan anh ("doi mau xong, bam ve trang Chu cai
        // la treo ban phim luon"): ham nay duoc goi TU BEN TRONG onClick cua
        // CHINH nut vua duoc cham (o mau / nut tron doi nen) - nut do la con
        // cua trang Ky hieu SAP BI THAY THE ngay lap tuc qua setInputView()
        // neu goi DONG BO tai day. Thay the toan bo cay View NGAY TRONG LUC
        // he thong Android con dang xu ly/dispatch chinh su kien cham do
        // (onClick chi la MOT buoc giua chung cua qua trinh xu ly MotionEvent,
        // chua ket thuc hoan toan khi onClick tra ve) la nguyen nhan kinh
        // dien gay treo/crash IME - Android co the tiep tuc dong bo/goi
        // callback tren cay View VUA BI THAO ROI KHOI CHA, dan den loi
        // trang thai lam hong ca ban phim cho toi khi nguoi dung dong/mo lai
        // (dung y het hien tuong "phai khoi dong lai ca ban phim moi dung
        // duoc" nguoi dung phan anh).
        //
        // SUA: HOAN viec xoa cache + thay View lai bang Handler.post{} - dua
        // vao cuoi hang doi cua main thread, chi thuc su chay SAU KHI toan
        // bo qua trinh xu ly su kien cham hien tai (bao gom ca dispatch cua
        // Android sau khi onClick tra ve) da xong HOAN TOAN. Luc do cay View
        // cu khong con dang "ban ron" giua chung nua, thay the an toan,
        // khong con treo/crash, va mau/theme moi cung ap dung NGAY LAP TUC
        // (chi tre mot nhip rat nho, khong the nhan ra bang mat thuong) cho
        // CA trang dang hien (vd Ky hieu) LAN trang Chu cai/cac trang khac.
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            cachedNumbersView = null
            cachedSymbolsView = null
            cachedNumpadView = null
            keyboardRootContainer = null
            lettersPageView = null
            setInputView(buildKeyboardContainer())
        }
    }

    /** Phim cach: chuc nang chinh la chen dau cach khi CHAM binh thuong.
     *  Neu ngon tay VUOT ngang qua nguong [SPACE_SWIPE_THRESHOLD_DP] truoc
     *  khi tha ra, xem la mot cu vuot doi ngon ngu thay vi mot cai cham. */
    private fun buildSpaceKey(weight: Float): View {
        val bg = buildGlowKeyBackground()
        val container = FrameLayout(this).apply {
            background = bg
            stateListAnimator = null
            elevation = 0f
            outlineProvider = null
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            isHapticFeedbackEnabled = true
        }

        fun edgeColor(active: Boolean) =
            if (active) Color.parseColor("#8AB4F8") else Color.parseColor("#80868B")

        val vLabel = TextView(this).apply {
            text = "V"
            textSize = 12f
            setTextColor(edgeColor(isVietnameseMode))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START
            ).apply { setMargins(dp(10), 0, 0, 0) }
        }
        val eLabel = TextView(this).apply {
            text = "E"
            textSize = 12f
            setTextColor(edgeColor(!isVietnameseMode))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.END
            ).apply { setMargins(0, 0, dp(10), 0) }
        }
        val centerLabel = TextView(this).apply {
            text = "\u2423 " + if (isVietnameseMode) "VI" else "EN"
            textSize = 13f
            setTextColor(primaryTextColor())
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(vLabel)
        container.addView(eLabel)
        container.addView(centerLabel)

        val swipeThresholdPx = dp(SPACE_SWIPE_THRESHOLD_DP)
        var downX = 0f
        container.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    vibrateKeyPress()
                    playKeyClickTone()
                    downX = event.rawX
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.rawX - downX
                    if (kotlin.math.abs(deltaX) >= swipeThresholdPx) {
                        setLanguageMode(vietnamese = deltaX < 0)
                    } else {
                        insertChar(' ')
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        return container
    }

    private fun setLanguageMode(vietnamese: Boolean) {
        if (isVietnameseMode == vietnamese) return
        isVietnameseMode = vietnamese
        currentWord.clear()
        Toast.makeText(
            this,
            if (isVietnameseMode) "Go Ti\u1ebfng Vi\u1ec7t (Telex)" else "Go Ti\u1ebfng Anh",
            Toast.LENGTH_SHORT
        ).show()
        redrawKeyboard()
    }

    /** Xay dung mot phim bam. TOI UU: bo [View.performHapticFeedback] (co
     *  che rung "co san" cua Android, phu thuoc cai dat he thong "Rung khi
     *  cham" - co the KHONG hoat dong, chinh la ly do [vibrateKeyPress] ra
     *  doi de rung TRUC TIEP, dang tin cay hon) - giu lai DUY NHAT
     *  [vibrateKeyPress], tranh MOI lan cham phim phai goi 2 co che rung
     *  khac nhau (2 lan IPC toi he thong rung) trong khi chi co 1 co che
     *  thuc su co tac dung. Giam duoc mot loat goi ham/IPC lap lai tren
     *  MOI lan go, gop phan lam ban phim phan hoi nhanh hon khi go lien tuc. */
    private fun buildKey(
        label: String,
        weight: Float = 1f,
        highlight: Boolean = false,
        fillRowHeight: Boolean = false,
        onRepeat: (() -> Unit)? = null,
        onClick: () -> Unit
    ): Button {
        // SUA (theo yeu cau nguoi dung "dong nhat mau sac"): TRUOC DAY phim
        // highlight (Enter, Shift dang bat, nut QR) LUON dung mau VIEN CO
        // DINH rieng (#4FC3F7, xanh cyan) - KHONG doi theo mau vien nguoi
        // dung chon o [buildKeyboardSettingsBar], khien cac phim nay bi
        // "lac mau" so voi phan con lai cua ban phim moi khi doi mau. GIO
        // DAY: dung CHUNG [glowColor] (mau vien HIEN TAI, giong moi phim
        // khac) - CHI con giu VIEN DAY HON (borderWidthDp = 3 thay vi 1) de
        // van con phan biet duoc day la phim "dac biet/dang bat", nhung mau
        // sac thi HOAN TOAN dong bo voi ca ban phim.
        val bg: Drawable = if (highlight) {
            buildGlowKeyBackground(borderWidthDp = 3)
        } else {
            buildGlowKeyBackground()
        }
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(primaryTextColor())
            textSize = when {
                label == "\u21b5" || label == "\u2b06" -> 28f
                label.length > 3 -> 11f
                label.length > 1 -> 13f
                else -> 16f
            }
            isSingleLine = true
            includeFontPadding = true
            setPadding(dp(1), 0, dp(1), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            background = bg
            stateListAnimator = null
            elevation = 0f
            outlineProvider = null
            layoutParams = LinearLayout.LayoutParams(
                0,
                if (fillRowHeight) ViewGroup.LayoutParams.MATCH_PARENT else dp(keyHeightDp),
                weight
            ).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            gravity = Gravity.CENTER
            isHapticFeedbackEnabled = true
        }

        var repeatRunnable: Runnable? = null
        var repeatTriggered = false
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    vibrateKeyPress()
                    playKeyClickTone()
                    if (label.length == 1) showKeyPreview(v, label)
                    repeatTriggered = false
                    if (onRepeat != null) {
                        val runnable = object : Runnable {
                            override fun run() {
                                repeatTriggered = true
                                onRepeat.invoke()
                                deleteRepeatHandler.postDelayed(this, DELETE_REPEAT_INTERVAL_MS)
                            }
                        }
                        repeatRunnable = runnable
                        deleteRepeatHandler.postDelayed(runnable, DELETE_REPEAT_INITIAL_DELAY_MS)
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    hideKeyPreview()
                    repeatRunnable?.let { deleteRepeatHandler.removeCallbacks(it) }
                    repeatRunnable = null
                    if (repeatTriggered) {
                        return@setOnTouchListener true
                    }
                    false
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideKeyPreview()
                    repeatRunnable?.let { deleteRepeatHandler.removeCallbacks(it) }
                    repeatRunnable = null
                    false
                }
                else -> false
            }
        }
        button.setOnClickListener { onClick() }
        return button
    }

    private fun getOrCreatePreviewPopup(): Pair<PopupWindow, TextView> {
        val existingPopup = previewPopup
        val existingBubble = previewBubble
        if (existingPopup != null && existingBubble != null) {
            return existingPopup to existingBubble
        }
        val bubble = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = true
            setPadding(dp(14), dp(16), dp(14), dp(8))
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
        previewPopup = popup
        previewBubble = bubble
        return popup to bubble
    }

    private fun showKeyPreview(anchor: View, label: String) {
        val (popup, bubble) = getOrCreatePreviewPopup()
        bubble.text = label
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        bubble.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val x = loc[0] + anchor.width / 2 - bubble.measuredWidth / 2
        val y = loc[1] - bubble.measuredHeight - dp(4)
        try {
            if (popup.isShowing) {
                popup.update(x, y, -1, -1)
            } else {
                popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
            }
        } catch (e: Exception) {
            // Bo qua neu window chua san sang de hien popup (hiem gap).
        }
    }

    private fun hideKeyPreview() {
        previewPopup?.let { if (it.isShowing) it.dismiss() }
    }

    private fun insertChar(ch: Char) {
        if (isVietnameseMode && ch.isLetter()) {
            insertVietnameseChar(ch)
            return
        }
        val shouldCapitalize = capitalizeNextLetter && ch.isLetter()
        if (shouldCapitalize) {
            capitalizeNextLetter = false
            showCapitalPreview = false
            capitalizeAppliedAtPrefixLen = null
        }
        val out = if (isShiftOn || shouldCapitalize) ch.uppercaseChar() else ch
        insertText(out.toString())
        if (shouldCapitalize) redrawKeyboard()
    }

    private fun insertVietnameseChar(ch: Char) {
        val ic = currentInputConnection ?: return
        val hadPendingSuggestion = pendingSuggestion != null
        clearAutocorrectSuggestion()
        val lower = ch.lowercaseChar()

        resyncCurrentWordFromInputConnection(ic)

        val oldWordLower = currentWord.toString()
        // SUA LOI QUAN TRONG: ban phim ao LUON truyen [ch] o dang CHU
        // THUONG (lay tu chuoi nguon "qwertyuiop"... trong [buildCharRow] -
        // nhan HOA/thuong tren nut chi la hien thi, KHONG doi gia tri [ch]
        // thuc su gui vao ham nay) - nen KHONG the dung "ch.isUpperCase()"
        // (se LUON la false, bat ke Caps Lock co bat hay khong, lam hong
        // tinh nang "AA"->"Â" khi dang go IN HOA). THAY VAO DO: du doan hoa/
        // thuong cua ky tu MOI (NEU no KHONG hop nhat, se la mot ky tu rieng
        // MOI o CUOI tu) dua theo chinh cac co trang thai dang dung de quyet
        // dinh hien thi ([isShiftOn]/[capitalizeNextLetter]/
        // [capitalizeAppliedAtPrefixLen]) - xem [keyIsUpper] ben duoi va
        // [VietnameseTelex.applyDoubleModifier].
        val oldWordCased = currentWordCased.toString()
        val keyIsUpper = isShiftOn || (
            capitalizeNextLetter &&
                (capitalizeAppliedAtPrefixLen == null || capitalizeAppliedAtPrefixLen == oldWordLower.length)
        )
        val wordAlreadyHasLiteralFOrW = oldWordLower.any { it == 'f' || it == 'w' }
        val newWordLower = if (wordAlreadyHasLiteralFOrW) {
            oldWordLower + lower
        } else {
            VietnameseTelex.processKey(oldWordLower, lower, oldWordCased, keyIsUpper)
        }
        currentWord = StringBuilder(newWordLower)

        var commonPrefixLen = 0
        val minLen = minOf(oldWordLower.length, newWordLower.length)
        while (commonPrefixLen < minLen && oldWordLower[commonPrefixLen] == newWordLower[commonPrefixLen]) {
            commonPrefixLen++
        }
        val deleteCount = oldWordLower.length - commonPrefixLen
        val newSuffixLower = newWordLower.substring(commonPrefixLen)

        val touchesCapitalizeTarget = newSuffixLower.isNotEmpty() &&
            (capitalizeAppliedAtPrefixLen?.let { it == commonPrefixLen } ?: true)
        val wasCapitalizingWordStart = capitalizeNextLetter && touchesCapitalizeTarget
        if (wasCapitalizingWordStart) {
            capitalizeAppliedAtPrefixLen = commonPrefixLen
        }
        val newSuffixDisplay = when {
            wasCapitalizingWordStart -> {
                val restLower = newSuffixLower.drop(1)
                val rest = if (isShiftOn) restLower.uppercase() else restLower
                newSuffixLower.first().uppercaseChar() + rest
            }
            isShiftOn -> newSuffixLower.uppercase()
            else -> newSuffixLower
        }
        val justConsumedSingleShift = capitalizeNextLetter && !touchesCapitalizeTarget
        if (justConsumedSingleShift) {
            capitalizeNextLetter = false
            capitalizeAppliedAtPrefixLen = null
        }
        if (wasCapitalizingWordStart) {
            showCapitalPreview = false
        }

        selfInitiatedChange = true
        if (deleteCount > 0) {
            ic.beginBatchEdit()
            try {
                ic.deleteSurroundingText(deleteCount, 0)
                ic.commitText(newSuffixDisplay, 1)
            } finally {
                ic.endBatchEdit()
            }
        } else {
            ic.commitText(newSuffixDisplay, 1)
        }
        if (hadPendingSuggestion || justConsumedSingleShift || wasCapitalizingWordStart) redrawKeyboard()
    }

    private fun resyncCurrentWordFromInputConnection(ic: android.view.inputmethod.InputConnection) {
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return
        var i = before.length
        while (i > 0 && before[i - 1].isLetter()) i--
        val recoveredCased = before.substring(i)
        val recovered = recoveredCased.lowercase()
        if (recovered != currentWord.toString()) {
            currentWord = StringBuilder(recovered)
        }
        // THEM: luon dong bo lai ban CASED (hoa/thuong THAT SU) tu noi dung
        // THUC TE dang hien trong o nhap - xem giai thich o [currentWordCased].
        // Luon gan lai (khong dieu kien "khac moi gan" nhu currentWord o
        // tren) vi day la thao tac doc rat re, va can PHAN ANH DUNG case
        // THAT SU tai moi thoi diem de [VietnameseTelex.applyDoubleModifier]
        // so sanh chinh xac.
        currentWordCased = StringBuilder(recoveredCased)
    }

    private fun insertText(text: String) {
        val boundaryWord = currentWord.toString()
        selfInitiatedChange = true
        currentInputConnection?.commitText(text, 1)
        currentWord.clear()

        if (pendingSuggestion != null) {
            clearAutocorrectSuggestion()
            redrawKeyboard()
        }
    }

    private fun deleteChar() {
        val hadPendingSuggestion = pendingSuggestion != null
        clearAutocorrectSuggestion()
        selfInitiatedChange = true
        val ic = currentInputConnection
        val selectedText = ic?.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            ic.commitText("", 1)
            currentWord.clear()
            currentWordCased.clear()
        } else {
            ic?.deleteSurroundingText(1, 0)
            if (currentWord.isNotEmpty()) {
                currentWord.deleteCharAt(currentWord.length - 1)
            }
            if (currentWordCased.isNotEmpty()) {
                currentWordCased.deleteCharAt(currentWordCased.length - 1)
            }
        }

        // THEM (theo yeu cau nguoi dung): "xoa het viet lai thi van [tu dong
        // viet hoa chu dau]" - neu SAU khi xoa, O NHAP TRO THANH RONG HOAN
        // TOAN (khong con ky tu nao ca truoc LAN sau con tro), TU DONG "nap
        // lai" co viet hoa chu cai TIEP THEO, y het luc moi mo mot o nhap
        // MOI HOAN TOAN (xem [onStartInputView]) - de neu nguoi dung xoa
        // sach van ban roi go lai tu dau, chu dau tien VAN duoc tu dong viet
        // hoa, khong can tu bam Shift lai. Dieu kien "!capitalizeNextLetter"
        // de tranh goi redrawKeyboard() thua neu co nay von DA dang bat san.
        val isFieldNowEmpty = ic?.getTextBeforeCursor(1, 0).isNullOrEmpty() &&
            ic?.getTextAfterCursor(1, 0).isNullOrEmpty()
        val shouldRearmCapitalize = isFieldNowEmpty && !capitalizeNextLetter
        if (shouldRearmCapitalize) {
            capitalizeNextLetter = true
            showCapitalPreview = true
            capitalizeAppliedAtPrefixLen = null
        }
        if (hadPendingSuggestion || shouldRearmCapitalize) redrawKeyboard()
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        currentWord.clear()
        clearAutocorrectSuggestion()
        selfInitiatedChange = true
        val inputType = currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL
        val isMultiLine = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
            (inputType and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0
        if (isMultiLine) {
            ic.commitText("\n", 1)
            return
        }
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!selfInitiatedChange) {
            currentWord.clear()
            capitalizeNextLetter = false
            showCapitalPreview = false
            capitalizeAppliedAtPrefixLen = null
        }
        selfInitiatedChange = false
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        // Doc lai mau vien + che do sang/toi da luu tu lan truoc (neu co) -
        // xem [setAccentColor]/[toggleTheme]. Neu chua tung doi gi ca, dung
        // gia tri mac dinh (tim neon, nen toi).
        glowColor = keyboardPrefs.getInt(PREF_ACCENT_COLOR, DEFAULT_ACCENT_COLOR)
        isDarkTheme = keyboardPrefs.getBoolean(PREF_IS_DARK_THEME, true)
    }

    private fun openQrScanner(continuous: Boolean = false) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onCameraPermissionResult = { granted ->
                if (granted) openQrScanner(continuous)
            }
            val intent = Intent(this, QrCameraPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        }

        qrContinuousMode = continuous
        qrLastDeliveredText = null
        qrFrameHandled.set(false)

        if (qrOverlayView != null) return
        showQrOverlay()
    }

    private fun showQrOverlay() {
        val decorView = window?.window?.decorView ?: return
        val heightPx = decorView.height.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels / 3)

        val view = buildQrOverlayContentView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = heightPx
            token = decorView.windowToken
        }

        try {
            qrWindowManager.addView(view, params)
        } catch (e: Exception) {
            Toast.makeText(this, "Kh\u00f4ng m\u1edf \u0111\u01b0\u1ee3c khung qu\u00e9t", Toast.LENGTH_SHORT).show()
            return
        }
        qrOverlayView = view
        qrOverlaySessionKey = editorSessionKey(currentInputEditorInfo)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        startQrCamera()
    }

    private fun hideQrOverlay() {
        stopQrCamera()
        qrOverlayView?.let {
            try { qrWindowManager.removeView(it) } catch (e: Exception) { /* da bi go truoc do */ }
        }
        qrOverlayView = null
        qrOverlayRootLayout = null
        qrCapturedPhotoView = null
        qrShowingCapturedPhoto = false
        qrOverlaySessionKey = null
        qrPreviewView = null
        qrFlashButton = null
        qrFlashOn = false
        qrImageCapture = null
        qrCaptureInProgress = false
        qrCaptureButton = null
        pendingCaptureTap?.let { captureButtonTapHandler.removeCallbacks(it) }
        pendingCaptureTap = null
        lastCaptureTapTime = 0L
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    private fun buildQrOverlayContentView(): View {
        val root = FrameLayout(this)
        qrOverlayRootLayout = root

        val preview = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        qrPreviewView = preview
        root.addView(preview)

        val cancelBg = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#CC202124"))
        }
        val cancelBtn = Button(this).apply {
            text = "Hu\u1ef7"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = cancelBg
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { setMargins(0, 0, dp(12), dp(12)) }
            setOnClickListener {
                reopenQrScannerOnNextStart = false
                qrAutoCapturePerScan = false
                hideQrOverlay()
            }
        }
        root.addView(cancelBtn)

        val flashBtn = Button(this).apply {
            text = "\u26a1"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 18f
            includeFontPadding = true
            background = buildQrFlashButtonBackground(active = false)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, dp(12), dp(12), 0) }
            setOnClickListener { toggleQrFlash() }
        }
        qrFlashButton = flashBtn
        root.addView(flashBtn)

        val captureBtn = Button(this).apply {
            text = ""
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            stateListAnimator = null
            background = buildQrCaptureButtonBackground(active = qrAutoCapturePerScan)
            layoutParams = FrameLayout.LayoutParams(
                dp(52), dp(52),
                Gravity.CENTER_VERTICAL or Gravity.END
            ).apply { setMargins(0, 0, dp(16), 0) }
            setOnClickListener {
                val now = android.os.SystemClock.uptimeMillis()
                val isDoubleTap = now - lastCaptureTapTime <= CAPTURE_DOUBLE_TAP_MAX_INTERVAL_MS
                lastCaptureTapTime = if (isDoubleTap) 0L else now
                if (isDoubleTap) {
                    pendingCaptureTap?.let { captureButtonTapHandler.removeCallbacks(it) }
                    pendingCaptureTap = null
                    qrAutoCapturePerScan = !qrAutoCapturePerScan
                    qrCaptureButton?.background = buildQrCaptureButtonBackground(active = qrAutoCapturePerScan)
                    Toast.makeText(
                        this@QrKeyboardService,
                        if (qrAutoCapturePerScan)
                            "\u0110\u00e3 b\u1eadt: t\u1ef1 \u0111\u1ed9ng ch\u1EE5p \u1ea3nh m\u1ed7i l\u1ea7n qu\u00e9t \u0111\u01b0\u1ee3c m\u00e3"
                        else
                            "\u0110\u00e3 t\u1eaft ch\u1EE5p \u1ea3nh t\u1ef1 \u0111\u1ed9ng theo m\u00e3 qu\u00e9t",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    pendingCaptureTap?.let { captureButtonTapHandler.removeCallbacks(it) }
                    val tapRunnable = Runnable {
                        pendingCaptureTap = null
                        captureQrPhoto()
                    }
                    pendingCaptureTap = tapRunnable
                    captureButtonTapHandler.postDelayed(tapRunnable, CAPTURE_DOUBLE_TAP_MAX_INTERVAL_MS)
                }
            }
        }
        qrCaptureButton = captureBtn
        root.addView(captureBtn)

        return root
    }

    private fun buildQrCaptureButtonBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (active) {
            setColor(Color.parseColor("#CCFF3B30"))
            setStroke(dp(5), Color.WHITE)
        } else {
            setColor(Color.TRANSPARENT)
            setStroke(dp(5), Color.WHITE)
        }
    }

    private fun buildQrFlashButtonBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(if (active) Color.parseColor("#1A73E8") else Color.parseColor("#CC202124"))
    }

    private fun toggleQrFlash() {
        val cam = qrCamera
        if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "Thi\u1ebft b\u1ecb kh\u00f4ng c\u00f3 \u0111\u00e8n flash", Toast.LENGTH_SHORT).show()
            return
        }
        qrFlashOn = !qrFlashOn
        cam.cameraControl.enableTorch(qrFlashOn)
        qrFlashButton?.background = buildQrFlashButtonBackground(active = qrFlashOn)
    }

    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startQrCamera() {
        val preview = qrPreviewView ?: return
        qrCameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }
            val scanner = BarcodeScanning.getClient()
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val executor = qrCameraExecutor ?: return@addListener
            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                processQrFrame(imageProxy, scanner)
            }

            val imageCaptureUseCase = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                qrCamera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase, imageAnalysis, imageCaptureUseCase
                )
                qrImageCapture = imageCaptureUseCase
            } catch (e: Exception) {
                Toast.makeText(this, "Kh\u00f4ng m\u1edf \u0111\u01b0\u1ee3c camera: ${e.message}", Toast.LENGTH_SHORT).show()
                hideQrOverlay()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopQrCamera() {
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (e: Exception) {
            // Bo qua neu camera provider chua kip khoi tao xong.
        }
        qrCameraExecutor?.shutdown()
        qrCameraExecutor = null
        qrCamera = null
        qrImageCapture = null
    }

    private fun captureQrPhoto(silentAutoCapture: Boolean = false) {
        val imageCapture = qrImageCapture
        if (imageCapture == null) {
            if (!silentAutoCapture) {
                Toast.makeText(this, "Camera ch\u01b0a s\u1eb5n s\u00e0ng, th\u1eed l\u1ea1i sau", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (qrCaptureInProgress) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (!silentAutoCapture) {
                Toast.makeText(
                    this,
                    "H\u00e3y c\u1ea5p quy\u1ec1n L\u01b0u tr\u1eef cho \u1ee9ng d\u1ee5ng trong C\u00e0i \u0111\u1eb7t \u0111\u1ec3 d\u00f9ng ch\u1ee5p \u1ea3nh",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        qrCaptureInProgress = true
        if (!silentAutoCapture) qrCaptureButton?.isEnabled = false

        val fileName = "QR_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QrKeyboard")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ).build()

        val executor = qrCameraExecutor ?: Executors.newSingleThreadExecutor()
        imageCapture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && savedUri != null) {
                        val doneValues = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                        try {
                            contentResolver.update(savedUri, doneValues, null, null)
                        } catch (e: Exception) {
                            android.util.Log.w("QrKeyboardService", "Khong go duoc IS_PENDING: ${e.message}")
                        }
                    }
                    val mainHandler = Handler(Looper.getMainLooper())
                    mainHandler.post {
                        qrCaptureInProgress = false
                        if (!silentAutoCapture) qrCaptureButton?.isEnabled = true
                        vibrateKeyPress()
                        qrToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
                        if (savedUri != null) {
                            if (silentAutoCapture) {
                                Toast.makeText(
                                    this@QrKeyboardService,
                                    "\u0110\u00e3 l\u01b0u \u1ea3nh cho m\u00e3 v\u1eeba qu\u00e9t v\u00e0o th\u01b0 vi\u1ec7n",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                val sentToChat = tryCommitPhotoToChat(savedUri)
                                Toast.makeText(
                                    this@QrKeyboardService,
                                    if (sentToChat) "\u0110\u00e3 l\u01b0u \u1ea3nh v\u00e0 g\u1eedi v\u00e0o khung chat"
                                    else "\u0110\u00e3 l\u01b0u \u1ea3nh v\u00e0o thi\u1ebft b\u1ecb (\u1ee9ng d\u1ee5ng n\u00e0y kh\u00f4ng nh\u1eadn \u1ea3nh tr\u1ef1c ti\u1ebfp t\u1eeb b\u00e0n ph\u00edm)",
                                    Toast.LENGTH_LONG
                                ).show()
                                showCapturedPhotoPreview(savedUri)
                            }
                        } else {
                            if (!silentAutoCapture) {
                                Toast.makeText(this@QrKeyboardService, "\u0110\u00e3 ch\u1ee5p \u1ea3nh nh\u01b0ng kh\u00f4ng l\u1ea5y \u0111\u01b0\u1ee3c \u0111\u01b0\u1eddng d\u1eabn \u1ea3nh \u0111\u00e3 l\u01b0u", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    val mainHandler = Handler(Looper.getMainLooper())
                    mainHandler.post {
                        qrCaptureInProgress = false
                        if (!silentAutoCapture) qrCaptureButton?.isEnabled = true
                        Toast.makeText(this@QrKeyboardService, "Ch\u1ee5p \u1ea3nh th\u1ea5t b\u1ea1i: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun tryCommitPhotoToChat(uri: Uri): Boolean {
        val ic = currentInputConnection ?: return false
        val editorInfo = currentInputEditorInfo ?: return false
        val supportedMimeTypes = androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(editorInfo)
        val mimeType = "image/jpeg"
        val isSupported = supportedMimeTypes.any { ClipDescription.compareMimeTypes(mimeType, it) }
        if (!isSupported) return false

        val contentInfo = InputContentInfoCompat(
            uri,
            ClipDescription("QR Keyboard photo", arrayOf(mimeType)),
            null
        )
        return try {
            selfInitiatedChange = true
            InputConnectionCompat.commitContent(
                ic, editorInfo, contentInfo,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null
            )
        } catch (e: Exception) {
            android.util.Log.w("QrKeyboardService", "commitContent that bai: ${e.message}")
            false
        }
    }

    private fun showCapturedPhotoPreview(uri: Uri) {
        val root = qrOverlayRootLayout ?: return

        qrCapturedPhotoView?.let { root.removeView(it) }

        val imageView = android.widget.ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
            contentDescription = "\u1ea2nh v\u1eeba ch\u1ee5p - ch\u1ea1m \u0111\u1ec3 ti\u1ebfp t\u1ee5c qu\u00e9t"
            setOnClickListener { dismissCapturedPhotoPreview() }
        }
        root.addView(imageView)
        qrCapturedPhotoView = imageView
        qrShowingCapturedPhoto = true

        val executor = qrCameraExecutor ?: Executors.newSingleThreadExecutor()
        executor.execute {
            val bitmap = try {
                decodeSampledBitmap(uri, 1080)
            } catch (e: Exception) {
                null
            }
            Handler(Looper.getMainLooper()).post {
                if (qrCapturedPhotoView === imageView) {
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    } else {
                        Toast.makeText(
                            this@QrKeyboardService,
                            "Kh\u00f4ng hi\u1ec3n th\u1ecb \u0111\u01b0\u1ee3c \u1ea3nh v\u1eeba ch\u1ee5p, nh\u01b0ng \u1ea3nh v\u1eabn \u0111\u00e3 \u0111\u01b0\u1ee3c l\u01b0u",
                            Toast.LENGTH_SHORT
                        ).show()
                        dismissCapturedPhotoPreview()
                    }
                }
            }
        }
    }

    private fun dismissCapturedPhotoPreview() {
        qrCapturedPhotoView?.let { qrOverlayRootLayout?.removeView(it) }
        qrCapturedPhotoView = null
        qrShowingCapturedPhoto = false
        qrFrameHandled.set(false)
    }

    private fun decodeSampledBitmap(uri: Uri, maxDim: Int): android.graphics.Bitmap? {
        val boundsOptions = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri) ?: return null
        boundsStream.use { android.graphics.BitmapFactory.decodeStream(it, null, boundsOptions) }

        var sampleSize = 1
        while (boundsOptions.outWidth / sampleSize > maxDim || boundsOptions.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }

        val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decodeStream = contentResolver.openInputStream(uri) ?: return null
        return decodeStream.use { android.graphics.BitmapFactory.decodeStream(it, null, decodeOptions) }
    }

    private fun processQrFrame(imageProxy: ImageProxy, scanner: BarcodeScanner) {
        if (qrShowingCapturedPhoto) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (!qrFrameHandled.get()) {
                    val barcode = barcodes.firstOrNull {
                        it.valueType != Barcode.TYPE_UNKNOWN || it.rawValue != null || it.rawBytes != null
                    }
                    val value = barcode?.let { extractQrBarcodeText(it) }
                    if (!value.isNullOrEmpty() && !containsQrSpecialCharacter(value) &&
                        value != qrLastDeliveredText &&
                        qrFrameHandled.compareAndSet(false, true)
                    ) {
                        qrLastDeliveredText = value
                        onQrFound(value)
                    }
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private val qrAllowedCharacterRegex = Regex("^[\\p{L}\\p{N}\\s.,!?:;'\"()/@_-]*$")

    private fun containsQrSpecialCharacter(text: String): Boolean =
        !qrAllowedCharacterRegex.matches(text)

    private fun extractQrBarcodeText(barcode: Barcode): String? {
        barcode.rawValue?.let { if (it.isNotEmpty()) return it }
        barcode.displayValue?.let { if (it.isNotEmpty()) return it }
        val bytes = barcode.rawBytes ?: return null
        if (bytes.isEmpty()) return null
        return try {
            val utf8 = String(bytes, Charsets.UTF_8)
            if (utf8.contains('\uFFFD')) String(bytes, Charsets.ISO_8859_1) else utf8
        } catch (e: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun onQrFound(text: String) {
        val beepDurationMs = 150
        qrToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, beepDurationMs)

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            val ic = currentInputConnection
            selfInitiatedChange = true
            ic?.commitText(text, 1)
            ic?.commitText("\n", 1)
            currentWord.clear()
            val hadPendingSuggestion = pendingSuggestion != null
            clearAutocorrectSuggestion()
            if (hadPendingSuggestion) redrawKeyboard()
            Toast.makeText(this, "\u0110\u00e3 qu\u00e9t: $text", Toast.LENGTH_SHORT).show()

            if (qrAutoCapturePerScan) {
                captureQrPhoto(silentAutoCapture = true)
            }
        }

        mainHandler.postDelayed({
            if (qrContinuousMode) {
                qrFrameHandled.set(false)
            } else {
                reopenQrScannerOnNextStart = true
                reopenQrScannerDeadline =
                    android.os.SystemClock.uptimeMillis() + QR_AUTO_REOPEN_WINDOW_MS
                hideQrOverlay()
            }
        }, (beepDurationMs + 100).toLong())
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (finishingInput) {
            cancelPendingFinishHide()
            val hideRunnable = Runnable {
                pendingFinishHide = null
                if (qrOverlayView != null) {
                    // Khung quet QR dang mo luc ban phim THAT SU tat - GIU
                    // NGUYEN trang phim hien tai (KHONG dat co reset ve Chu
                    // cai), chi danh dau de tu MO LAI khung quet o lan mo
                    // ban phim ke tiep - dung yeu cau "tru phi co mo qr
                    // quet" cua nguoi dung.
                    reopenQrScannerOnNextStart = true
                    reopenQrScannerDeadline =
                        android.os.SystemClock.uptimeMillis() + QR_AUTO_REOPEN_WINDOW_MS
                } else {
                    // THEM (theo yeu cau nguoi dung): khung quet QR KHONG mo
                    // luc nay - day la luc ban phim THAT SU bi "tat" (da qua
                    // debounce, khong phai gian doan tam thoi) - danh dau de
                    // lan MO LAI ke tiep ([onStartInputView]) tu dong quay
                    // ve trang Chu cai, bat ke la cung mot o nhap cu hay o
                    // nhap moi.
                    shouldResetModeToLettersOnNextStart = true
                }
                hideQrOverlay()
            }
            pendingFinishHide = hideRunnable
            finishInputHideHandler.postDelayed(hideRunnable, FINISH_INPUT_HIDE_DEBOUNCE_MS)
        }
        hideKeyPreview()
        deleteRepeatHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingFinishHide()
        hideQrOverlay()
        qrToneGenerator.release()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        previewPopup?.let { if (it.isShowing) it.dismiss() }
        previewPopup = null
        previewBubble = null
        cachedNumbersView = null
        cachedSymbolsView = null
        cachedNumpadView = null
        keyboardRootContainer = null
        lettersPageView = null
    }
}

/**
 * Goi y sua loi chinh ta Tieng Viet don gian, dua tren mot tu dien co san
 * (file assets/vn_words.txt, ~6600 tu Tieng Viet thong dung, moi tu 1 dong).
 * LUU Y: tinh nang GOI Y (hien thanh goi y tren ban phim) hien dang KHONG
 * duoc goi toi trong luong go binh thuong (xem [insertText]/[checkAutocorrectSuggestion])
 * vi viec doc/duyet tu dien lam ban phim khung dung luc vua go xong 1 tu -
 * object nay van duoc GIU LAI (khong xoa) de co the bat lai de dang neu sau
 * nay toi uu duoc cach tra cuu (vd chuyen sang Trie/nen tang khac).
 */
private object VietnameseAutocorrect {

    private const val DICTIONARY_ASSET_PATH = "vn_words.txt"

    @Volatile
    private var dictionaryByLength: Map<Int, List<String>>? = null

    @Volatile
    private var dictionarySet: Set<String>? = null

    private fun ensureLoaded(context: android.content.Context) {
        if (dictionarySet != null) return
        synchronized(this) {
            if (dictionarySet != null) return
            val words = try {
                context.assets.open(DICTIONARY_ASSET_PATH)
                    .bufferedReader(Charsets.UTF_8)
                    .useLines { lines -> lines.filter { it.isNotBlank() }.toHashSet() }
            } catch (e: Exception) {
                emptySet()
            }
            dictionarySet = words
            dictionaryByLength = words.groupBy { it.length }
        }
    }

    fun suggestFor(context: android.content.Context, word: String): String? {
        ensureLoaded(context)
        val set = dictionarySet ?: return null
        if (word.isEmpty() || word in set) return null

        val byLength = dictionaryByLength ?: return null
        val pool = (byLength[word.length].orEmpty()) +
            (byLength[word.length - 1].orEmpty()) +
            (byLength[word.length + 1].orEmpty())

        val firstChar = word[0]
        return pool.firstOrNull { candidate ->
            candidate.isNotEmpty() && candidate[0] == firstChar &&
                isEditDistanceAtMostOne(word, candidate)
        }
    }

    private fun isEditDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        val lenA = a.length
        val lenB = b.length
        if (kotlin.math.abs(lenA - lenB) > 1) return false

        if (lenA == lenB) {
            var diffCount = 0
            for (i in a.indices) {
                if (a[i] != b[i]) {
                    diffCount++
                    if (diffCount > 1) return false
                }
            }
            return diffCount == 1
        }

        val longer = if (lenA > lenB) a else b
        val shorter = if (lenA > lenB) b else a
        var i = 0
        var j = 0
        var skipped = false
        while (i < longer.length && j < shorter.length) {
            if (longer[i] == shorter[j]) {
                i++
                j++
            } else if (!skipped) {
                skipped = true
                i++
            } else {
                return false
            }
        }
        return true
    }
}

/**
 * Bo xu ly go Tieng Viet kieu Telex don gian: chuyen mot chuoi ky tu QWERTY
 * thuong (khong dau) thanh chuoi co dau Tieng Viet, dua tren "tu" dang go.
 * SUA LOI (theo yeu cau nguoi dung): "qua"+sac ra dung "quá" (khong con
 * "qúa"), "gia"+sac ra dung "giá" (khong con "gía") - xem chi tiet trong
 * [applyTone], phan tinh [quOrGiGlideNucleus].
 */
private object VietnameseTelex {

    private val vowelGroups: List<CharArray> = listOf(
        charArrayOf('a', '\u00e1', '\u00e0', '\u1ea3', '\u00e3', '\u1ea1'), // a
        charArrayOf('\u0103', '\u1eaf', '\u1eb1', '\u1eb3', '\u1eb5', '\u1eb7'), // ă
        charArrayOf('\u00e2', '\u1ea5', '\u1ea7', '\u1ea9', '\u1eab', '\u1ead'), // â
        charArrayOf('e', '\u00e9', '\u00e8', '\u1ebb', '\u1ebd', '\u1eb9'), // e
        charArrayOf('\u00ea', '\u1ebf', '\u1ec1', '\u1ec3', '\u1ec5', '\u1ec7'), // ê
        charArrayOf('i', '\u00ed', '\u00ec', '\u1ec9', '\u0129', '\u1ecb'), // i
        charArrayOf('o', '\u00f3', '\u00f2', '\u1ecf', '\u00f5', '\u1ecd'), // o
        charArrayOf('\u00f4', '\u1ed1', '\u1ed3', '\u1ed5', '\u1ed7', '\u1ed9'), // ô
        charArrayOf('\u01a1', '\u1edb', '\u1edd', '\u1edf', '\u1ee1', '\u1ee3'), // ơ
        charArrayOf('u', '\u00fa', '\u00f9', '\u1ee7', '\u0169', '\u1ee5'), // u
        charArrayOf('\u01b0', '\u1ee9', '\u1eeb', '\u1eed', '\u1eef', '\u1ef1'), // ư
        charArrayOf('y', '\u00fd', '\u1ef3', '\u1ef7', '\u1ef9', '\u1ef5')  // y
    )

    private val modifiedGroupIndices = setOf(1, 2, 4, 7, 8, 10)

    /** THEM (theo yeu cau nguoi dung, lam ro lan 2): danh sach cac PHU AM
     *  GHEP (chu ghep) - neu TU dang go (TINH TOI THOI DIEM NGAY TRUOC
     *  keystroke dau thanh) hien dang KET THUC bang MOT trong cac cum nay,
     *  thi phim dau thanh (s/f/r/x/j) se KHONG duoc ap dung nua - xem
     *  [applyTone]. Liet ke "ngh" TRUOC "ng"/"gh" khong anh huong ket qua
     *  (chi can KHOP VOI BAT KY cum nao trong danh sach la du, xem cach
     *  dung qua [String.endsWith] trong [applyTone]). */
    private val toneBlockingEndClusters = listOf(
        "ngh", "tr", "th", "ph", "gh", "kh", "ch", "ng", "qu"
    )

    private val charToGroupTone: Map<Char, Pair<Int, Int>> by lazy {
        val map = HashMap<Char, Pair<Int, Int>>()
        vowelGroups.forEachIndexed { groupIdx, tones ->
            tones.forEachIndexed { toneIdx, c -> map[c] = groupIdx to toneIdx }
        }
        map
    }

    /** [wordCased]: ban GIU NGUYEN hoa/thuong THAT SU cua [word] (cung do
     *  dai, tung vi tri khop voi [word]) - dung DUY NHAT de kiem tra "lech
     *  hoa/thuong" trong [applyDoubleModifier] (xem giai thich chi tiet o
     *  do). [keyIsUpper]: case THAT SU (hoa hay thuong) cua CHINH phim vua
     *  go (truoc khi ha thanh [keyLower]). */
    fun processKey(word: String, keyLower: Char, wordCased: String, keyIsUpper: Boolean): String {
        applyDoubleModifier(word, keyLower, wordCased, keyIsUpper)?.let { return it }
        applyTone(word, keyLower)?.let { return it }
        return word + keyLower
    }

    /** SUA THEM (theo yeu cau nguoi dung): "Aa" (go 'A' hoa roi 'a' thuong)
     *  TRUOC DAY bi hop nhat thanh "Â" - giong het "aa" (cung hoa hoac cung
     *  thuong). Nguoi dung muon PHAN BIET: neu 2 ky tu LECH hoa/thuong voi
     *  nhau (mot hoa, mot thuong - vd "Aa" hoac "aA"), coi la nguoi dung CO
     *  CHU Y muon go 2 ky tu THUONG (khong phai Telex) rieng biet, tuong tu
     *  quy uoc "go lech hoa/thuong de thoat bien doi Telex" da co san o mot
     *  so bo go Tieng Viet khac (Unikey...) - ap dung cho CA 4 cap
     *  "gap-doi": Aa<->Â, Ee<->Ê, Oo<->Ô, Dd<->Đ. Xem tham so [wordCased]/
     *  [keyIsUpper] o [processKey]. */
    private fun applyDoubleModifier(word: String, key: Char, wordCased: String, keyIsUpper: Boolean): String? {
        if (word.isEmpty()) return null

        fun lastIndexOfGroup(groupIdx: Int): Int? =
            word.indices.lastOrNull { i -> charToGroupTone[word[i]]?.first == groupIdx }

        // Case (hoa/thuong) THAT SU cua ky tu dang o vi tri [pos] trong tu,
        // dua vao [wordCased] - false (coi nhu thuong) neu vi tri khong hop
        // le (hiem gap, phong ve).
        fun isUpperAt(pos: Int): Boolean = wordCased.getOrNull(pos)?.isUpperCase() ?: false

        fun toggleGroup(fromGroupIdx: Int, toGroupIdx: Int): String? {
            val fromIdx = lastIndexOfGroup(fromGroupIdx)
            val toIdx = lastIndexOfGroup(toGroupIdx)
            val toIsNearer = toIdx != null && (fromIdx == null || toIdx > fromIdx)
            if (toIsNearer) {
                val toneIdx = charToGroupTone[word[toIdx!!]]!!.second
                if (toneIdx != 0) return null
                val baseChar = vowelGroups[fromGroupIdx][0]
                return word.substring(0, toIdx) + baseChar + word.substring(toIdx + 1) + key
            }
            if (fromIdx != null) {
                // SUA THEM: neu ky tu GOC (vd 'a' dau tien, da co san trong
                // tu) duoc go VOI CASE KHAC voi phim MOI vua go (vd 'A' hoa +
                // 'a' thuong, hoac nguoc lai) - BO QUA hop nhat, tra ve null
                // de ky tu MOI duoc CHEN NGUYEN VAN (qua nhanh applyTone/
                // fallback "word + key" trong [processKey]), giu ca 2 ky tu
                // rieng biet dung nhu nguoi dung go (vd "Aa" van la "Aa").
                if (isUpperAt(fromIdx) != keyIsUpper) return null
                val toneIdx = charToGroupTone[word[fromIdx]]!!.second
                val newChar = vowelGroups[toGroupIdx][toneIdx]
                return word.substring(0, fromIdx) + newChar + word.substring(fromIdx + 1)
            }
            return null
        }

        return when (key) {
            'a' -> toggleGroup(0, 2)
            'e' -> toggleGroup(3, 4)
            'o' -> toggleGroup(6, 7)
            'w' -> {
                val uoIdx = (0 until word.length - 1).lastOrNull { i ->
                    charToGroupTone[word[i]]?.first == 9 && charToGroupTone[word[i + 1]]?.first == 6
                }
                val uuIdx = (0 until word.length - 1).lastOrNull { i ->
                    charToGroupTone[word[i]]?.first == 9 && charToGroupTone[word[i + 1]]?.first == 9
                }
                if (uoIdx != null) {
                    val toneU = charToGroupTone[word[uoIdx]]!!.second
                    val toneO = charToGroupTone[word[uoIdx + 1]]!!.second
                    val newU = vowelGroups[10][toneU]
                    val newO = vowelGroups[8][toneO]
                    word.substring(0, uoIdx) + newU + newO + word.substring(uoIdx + 2)
                } else if (uuIdx != null) {
                    val toneFirstU = charToGroupTone[word[uuIdx]]!!.second
                    val newFirstU = vowelGroups[10][toneFirstU]
                    word.substring(0, uuIdx) + newFirstU + word.substring(uuIdx + 1)
                } else {
                    val pairs = listOf(0 to 1, 6 to 8, 9 to 10)
                    val best = pairs.mapNotNull { (fromG, toG) ->
                        val pos = maxOf(lastIndexOfGroup(fromG) ?: -1, lastIndexOfGroup(toG) ?: -1)
                        if (pos < 0) null else Triple(pos, fromG, toG)
                    }.maxByOrNull { it.first }
                    if (best == null) null else toggleGroup(best.second, best.third)
                }
            }
            'd' -> {
                val dIdx = word.lastIndexOf('d')
                val dashIdx = word.lastIndexOf('\u0111')
                val useDash = dashIdx >= 0 && (dIdx < 0 || dashIdx > dIdx)
                when {
                    useDash -> word.substring(0, dashIdx) + 'd' + word.substring(dashIdx + 1) + 'd'
                    // SUA THEM: "Dd" (go 'D' hoa roi 'd' thuong) LECH
                    // hoa/thuong - BO QUA hop nhat thanh "Đ", giu nguyen 2 ky
                    // tu rieng biet (xem giai thich chi tiet o dau ham nay).
                    dIdx >= 0 && isUpperAt(dIdx) == keyIsUpper ->
                        word.substring(0, dIdx) + '\u0111' + word.substring(dIdx + 1)
                    else -> null
                }
            }
            else -> null
        }
    }

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

        // THEM (theo yeu cau nguoi dung, da tru "nh" ra khoi danh sach vi
        // "nh" la mot coda hop le RAT PHO BIEN, vd "nhanh"+s phai ra dung
        // "nhánh" - xem [toneBlockingEndClusters]): neu TU dang go (tinh
        // TOI THOI DIEM NAY, TRUOC keystroke nay) hien dang KET THUC bang
        // mot trong cac PHU AM GHEP "tr, th, ph, gh, kh, ch, ng, ngh, qu" -
        // KHONG ap dung dau thanh cho phim s/f/r/x/j nay nua, DU TRUOC do
        // trong tu co the DA co nguyen am roi (vd "sach" ket thuc bang "ch",
        // du co nguyen am "a" o giua). CHI CHEN NGUYEN VAN chinh ky tu vua go
        // (qua nhanh fallback "word + key" trong [processKey]), khong bien
        // doi gi ca. Nguoi dung muon go dau thanh cho tu loai nay se can go
        // dau TRUOC khi go am cuoi (vd "s,a,s,c,h" thay vi "s,a,c,h,s" cho
        // tu "sách").
        //
        // CHI ap dung cho DUNG 5 phim nguoi dung yeu cau (s/f/r/x/j, dat dau
        // thanh) - KHONG ap dung cho 'z' (BO dau thanh): neu khong loai tru,
        // mot tu DA CO dau thanh va DANG KET THUC bang mot trong cac cum
        // nay (vd "sách" - da co dau sac, ket thuc bang "ch") se KHONG THE
        // bo dau di duoc nua du nguoi dung go 'z', vi ban than tu VAN dang
        // "ket thuc bang ch" tai thoi diem go 'z' - day la mot cong dung
        // hop le, khac muc dich (THEM dau) ma nguoi dung mo ta.
        if (key != 'z' && toneBlockingEndClusters.any { word.endsWith(it) }) return null

        var end = word.length - 1
        while (end >= 0 && !charToGroupTone.containsKey(word[end])) end--
        if (end < 0) return null

        val isOpenSyllable = end == word.length - 1

        val clusterIndices = mutableListOf<Int>()
        var i = end
        while (i >= 0 && charToGroupTone.containsKey(word[i])) {
            clusterIndices.add(0, i)
            i--
        }

        // THEM (theo yeu cau nguoi dung, ve "qu"/"gi" la CHU GHEP/phu am
        // ghep): chu "u" ngay sau "q" (cum "qu") va chu "i" ngay sau "g"
        // (cum "gi") CHi la MOT PHAN CUA PHU AM GHEP (dong vai tro ban am
        // dem), khong phai la mot NGUYEN AM/VAN da hoan chinh - "qu"/"gi"
        // MOT MINH (chua co them nguyen am nao khac) KHONG PHAI la mot am
        // tiet Tieng Viet hop le (luon can it nhat 1 nguyen am THAT SU nua
        // phia sau, vd "qua", "gia"). Neu nguoi dung go dau thanh
        // (s/f/r/x/j) NGAY SAU KHI VUA GO XONG "qu"/"gi" - TRUOC KHI go them
        // BAT KY nguyen am nao khac - thi CHUA CO nguyen am THAT SU nao de
        // gan dau thanh vao ca. KHONG ap dung dau thanh trong truong hop nay
        // (tra ve null o day, ky tu duoc CHEN NGUYEN VAN nhu mot chu cai
        // binh thuong qua nhanh fallback "word + key" trong [processKey]) -
        // giong het cach applyTone da tu BO QUA khi CHUA co nguyen am nao
        // trong tu ([end < 0] o tren), chi khac la truong hop nay "u"/"i" VE
        // MAT KY TU thuoc vowelGroups nen khong roi vao nhanh do, can kiem
        // tra rieng.
        val isPrematureQuOrGiGlide = clusterIndices.size == 1 &&
            clusterIndices[0] > 0 &&
            when (word[clusterIndices[0] - 1]) {
                'q' -> charToGroupTone[word[clusterIndices[0]]]?.first == 9 // "qu"
                'g' -> charToGroupTone[word[clusterIndices[0]]]?.first == 5 // "gi"
                else -> false
            }
        if (isPrematureQuOrGiGlide) return null

        val preferred = clusterIndices.lastOrNull { pos ->
            charToGroupTone[word[pos]]!!.first in modifiedGroupIndices
        }

        val oGroupIdx = 6
        val aGroupIdx = 0
        val eGroupIdx = 3
        val startsWithOGlide = clusterIndices.size >= 2 &&
            charToGroupTone[word[clusterIndices.first()]]?.first == oGroupIdx &&
            charToGroupTone[word[clusterIndices[1]]]?.first.let { it == aGroupIdx || it == eGroupIdx }
        val oGlideNucleus: Int? = when {
            !startsWithOGlide -> null
            clusterIndices.size == 3 -> clusterIndices[1]
            clusterIndices.size == 2 -> clusterIndices[1]
            else -> null
        }

        // SUA LOI nguoi dung phan anh: "qua"+sac ra "qúa" (dau tren "u") thay
        // vi dung phai la "quá" (dau tren "a"); tuong tu "gia"+sac ra "gía"
        // thay vi "giá". NGUYEN NHAN: chu "u" ngay sau "q" (tao thanh "qu")
        // va chu "i" ngay sau "g" (tao thanh "gi") VE MAT CHINH TA khong
        // phai la nguyen am chinh cua van - chung chi la mot PHAN CUA PHU AM
        // GHEP ("qu", "gi"), dong vai tro giong het chu "o" dem trong cac cum
        // "oa"/"oe" (xem [oGlideNucleus] o tren). SUA: neu ky tu NGAY TRUOC
        // nguyen am dau cum la 'q' va nguyen am do thuoc nhom "u" (chi ap
        // dung khi cum CON nguyen am khac sau "u", vd "qua", "quy"); tuong tu
        // ky tu truoc la 'g' va nguyen am do thuoc nhom "i" (cum con nguyen
        // am khac sau "i", vd "gia", "giu") - thi BO QUA nguyen am dem do,
        // dat dau vao nguyen am KE TIEP trong cum thay vi nguyen am dau tien.
        val quOrGiGlideNucleus: Int? = if (clusterIndices.size >= 2) {
            val firstVowelPos = clusterIndices.first()
            val firstVowelGroup = charToGroupTone[word[firstVowelPos]]?.first
            val precedingChar = if (firstVowelPos > 0) word[firstVowelPos - 1] else null
            when {
                precedingChar == 'q' && firstVowelGroup == 9 -> clusterIndices[1]
                precedingChar == 'g' && firstVowelGroup == 5 -> clusterIndices[1]
                else -> null
            }
        } else null

        val target = when {
            preferred != null -> preferred
            isOpenSyllable && oGlideNucleus != null -> oGlideNucleus
            isOpenSyllable && quOrGiGlideNucleus != null -> quOrGiGlideNucleus
            isOpenSyllable && clusterIndices.size >= 2 -> clusterIndices.first()
            else -> clusterIndices.last()
        }

        val (groupIdx, currentToneIdx) = charToGroupTone[word[target]]!!

        if (currentToneIdx == toneIdx) {
            val revertedChar = vowelGroups[groupIdx][0]
            return word.substring(0, target) + revertedChar + word.substring(target + 1) + key
        }

        val newChar = vowelGroups[groupIdx][toneIdx]
        return word.substring(0, target) + newChar + word.substring(target + 1)
    }
}
