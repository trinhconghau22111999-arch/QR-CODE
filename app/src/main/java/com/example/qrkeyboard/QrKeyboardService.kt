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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
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
 * Dich vu ban phim ao (Input Method Service). Hien thi mot ban phim QWERTY
 * don gian dung code (khong phu thuoc file layout XML), kem nut [QR] de mo
 * khung quet QR noi (xem [showQrOverlay]) va chen ket qua thang vao o nhap
 * lieu dang mo.
 * Ho tro go tieng Viet kieu Telex (chuyen doi tu ban phim QWERTY chuan). Bat/
 * tat che do Tieng Viet bang cach VUOT tren phim cach: vuot TU TRAI SANG PHAI
 * de chuyen ve Tieng Anh, vuot TU PHAI SANG TRAI de chuyen sang Tieng Viet
 * (xem [buildSpaceKey]) - thay cho kieu cham nhanh 2 lan (double-tap) truoc
 * day, vi double-tap de bi kich hoat nham khi go nhanh lien tuc 2 dau cach
 * gan nhau (vd giua 2 cau), gay doi ngon ngu ngoai y muon.
 *
 * KHUNG QUET QR: TRUOC DAY duoc thuc hien bang cach mo QrScanActivity nhu mot
 * "cua so noi" (Activity trong suot, dat FLAG_NOT_FOCUSABLE) de co gang khong
 * cuop focus ban phim. Cach do van la mot Activity that - ve ly thuyet he
 * thong van co the coi day la chuyen "mat focus" trong mot so tinh huong/dong
 * may (mot so ROM nhu MIUI, One UI), khien onFinishInputView() bi goi oan.
 *
 * GIO DAY: khung quet (preview camera + nut Huy/Flash) la MOT VIEW NOI duoc
 * add thang vao cua so cua CHINH InputMethodService nay bang WindowManager
 * (xem [showQrOverlay]), khong con Activity nao dung de quet nua. Vi cua so
 * chu (cua so ban phim) khong doi, he thong khong bao gio coi la mat focus,
 * nen ban phim va khung quet chac chan cung ton tai, InputConnection voi o
 * nhap khong bi gian doan. CameraX can mot LifecycleOwner de bind/unbind camera
 * dung luc, nen Service nay tu implement LifecycleOwner (xem [lifecycle]).
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
         *  mot cu DUP-TAP (cham dup 2 lan) - mo che do QUET LIEN TUC (quet
         *  hoai, khong tu dong dong, cho den khi nguoi dung tu bam "Huy").
         *  Cham 1 lan binh thuong (qua khoang thoi gian nay moi cham lan 2,
         *  hoac chi cham 1 lan) van giu hanh vi cu: quet duoc 1 ma la tu dong
         *  dong man hinh quet ngay. */
        private const val QR_DOUBLE_TAP_MAX_INTERVAL_MS = 350L

        /** Khoang thoi gian (ms) toi da giua 2 lan cham nut Shift (⇧) de tinh
         *  la mot cu DUP-TAP (cham 2 lan lien tiep) - dung de phan biet:
         *   - Cham 1 lan (don): chi VIET HOA CHU CAI KE TIEP, sau do TU DONG
         *     tat, giong hanh vi Shift thuong tren cac ban phim khac.
         *   - Dup-tap (2 lan lien tiep, trong khoang thoi gian nay): bat CAPS
         *     LOCK - giu viet hoa LIEN TUC cho toi khi nguoi dung tu cham lai
         *     nut Shift mot lan nua de tat (hanh vi Caps Lock cu, khong doi).
         *  Xem [buildKeyboardView], phan xu ly nut Shift trong trang chu cai. */
        private const val SHIFT_DOUBLE_TAP_MAX_INTERVAL_MS = 350L

        /** So dp them vao vien DUOI CUNG cua toan bo ban phim (ngoai vien
         *  [verticalPaddingDp] mac dinh trong [buildKeyboardView]) de NHICH
         *  CA BAN PHIM LEN cao hon mot chut so voi day man hinh/thanh dieu
         *  huong, theo phan anh cua nguoi dung. Chi tang vien DUOI (khong
         *  dong vien tren) vi cua so IME neo o day man hinh - them vien duoi
         *  se day toan bo noi dung len cao hon ma khong lam xe lech vi tri
         *  tuong doi giua cac hang phim voi nhau.
         *
         *  SUA (theo phan anh moi cua nguoi dung): gia tri 4 truoc day nhich
         *  ban phim LEN QUA NHIEU, khien hang cuoi cung (hang co dau cach)
         *  nam CACH XA 3 phim dieu huong he thong (Back/Home/Recent) o cả 3
         *  trang ban phim (vi day la padding o cap ROOT, dung CHUNG cho moi
         *  che do LETTERS/NUMBERS/SYMBOLS). GIAM xuong con 1 de hang cuoi
         *  ha THAP XUONG mot chut, gan lai voi 3 phim dieu huong hon, ma
         *  van con giu mot khoang cach nho (khac 0) de khong dinh sat vao
         *  thanh dieu huong. */
        private const val EXTRA_BOTTOM_LIFT_DP = 1

        /** Khoang thoi gian (ms) TRE truoc khi thuc su dong khung quet QR +
         *  coi la "roi ban phim" sau khi he thong bao [onFinishInputView] voi
         *  finishingInput = true. LY DO: mot so thong bao/popup thoang qua
         *  (thong bao he thong keo xuong roi thu lai ngay, popup xin quyen
         *  cua trinh duyet, hop thoai he thong khac cuop focus trong tich
         *  tac...) khien he thong bao finishingInput = true NGAY CA KHI
         *  nguoi dung KHONG thuc su roi khoi o nhap - roi ngay sau do goi lai
         *  [onStartInputView] binh thuong. TRUOC DAY ham nay dong khung quet
         *  NGAY LAP TUC moi khi thay finishingInput = true, nen nhung truong
         *  hop thoang qua nay cung lam camera/khung quet bi tat oan, dung y
         *  het hien tuong nguoi dung phan anh (khung quet + ban phim bi an
         *  di khi trang web hien thong bao). GIO DAY: khi finishingInput =
         *  true, KHONG dong ngay, ma dat mot lenh dong "hoan" sau khoang thoi
         *  gian nay - neu [onStartInputView] duoc goi lai truoc khi lenh do
         *  kip chay (dau hieu day chi la gian doan tam thoi), lenh dong se bi
         *  HUY, khung quet va ban phim duoc giu nguyen nhu cu. Chi khi qua
         *  het khoang thoi gian nay ma ban phim van chua duoc mo lai, moi coi
         *  la nguoi dung THAT SU roi di va dong khung quet. */
        private const val FINISH_INPUT_HIDE_DEBOUNCE_MS = 500L
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

    /** Ba "trang" ban phim, dung trinh tu quen thuoc cua cac ban phim khac:
     *  chu cai (mac dinh) <-> so & ky hieu co ban (nut "?123") <-> ky hieu
     *  mo rong (nut "=\<" tren trang so, quay lai bang nut "?123"). */
    private enum class KeyboardMode { LETTERS, NUMBERS, SYMBOLS }

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
     *  anh huong toi CHU THAT SU se duoc chen).
     *
     *  LY DO can tach: o che do Tieng Viet, [capitalizeNextLetter] PHAI duoc
     *  GIU NGUYEN la true ngay sau khi da go xong 1 chu cai dau tu (xem
     *  [insertVietnameseChar]) - vi phim Telex TIEP THEO co the con tiep tuc
     *  bien doi DUNG vi tri chu cai do (vd go "A" (hoa) roi go them "a" ->
     *  phai ra "Â" hoa, khong phai "âa" hay "aA"), nen chua the "tat" that su
     *  luc nay. NHUNG neu dung chinh [capitalizeNextLetter] de quyet dinh
     *  hien thi ban phim, cac phim se bi "ket dinh" hien chu HOA lau hon can
     *  thiet: nguoi dung go xong 1 chu (vd "Anh") thi ban phim VAN con hien
     *  toan chu hoa cho toi khi ho go THEM 1 chu cai nua moi chiu ve lai chu
     *  thuong - tao cam giac "cham tre 1 nhip" rat de gay hieu lam la Shift
     *  chua tat.
     *
     *  GIO DAY: [showCapitalPreview] duoc TAT ngay lap tuc ngay sau khi CHU
     *  CAI DAU TIEN da duoc chen xong (xem [insertVietnameseChar]), bat ke
     *  [capitalizeNextLetter] (logic) co con duoc giu true hay khong - nen
     *  ban phim luon quay ve hien chu THUONG ngay sau 1 lan go, dung nhu
     *  nguoi dung mong doi, ma khong lam sai lech kha nang Telex tiep tuc
     *  bien doi dung chu cai vua go hoa do. */
    private var showCapitalPreview = false

    /** Bo dem chua cac ky tu (thuong, chua dau) cua "tu" dang go trong che do
     *  Tieng Viet, dung de bo dong bo Telex co the xoa/thay the dung phan da
     *  chen truoc do khi ap dau/mu. Duoc xoa moi khi gap dau cach, dau cau,
     *  Enter, hoac chuyen o nhap. */
    private var currentWord = StringBuilder()

    private var previewPopup: PopupWindow? = null
    private var previewBubble: TextView? = null

    /** Goi y sua loi Tieng Viet (xem [VietnameseAutocorrect]): sau khi go
     *  xong mot "tu" (bang dau cach) ma tu do KHONG co trong tu dien va tim
     *  duoc mot tu rat gan (sai lech 1 ky tu) trong tu dien, hien mot thanh
     *  goi y phia tren ban phim de nguoi dung cham vao sua nhanh, thay vi tu
     *  dong sua (de tranh sua nham nhung tu dung nhung hiem/khong co trong
     *  tu dien). [pendingSuggestionOriginalWord] la tu (chu thuong) NGUOI
     *  DUNG DA GO (dung de biet xoa bao nhieu ky tu khi cham nhan goi y). */
    private var pendingSuggestion: String? = null
    private var pendingSuggestionOriginalWord: String? = null

    /** Danh dau lan thay doi selection/con tro SAP TOI trong o nhap lieu la
     *  do CHINH ban phim nay gay ra (qua commitText/deleteSurroundingText),
     *  duoc dat true ngay TRUOC moi lan ban phim tu goi cac ham do, roi
     *  onUpdateSelection() se doc co nay de phan biet: neu con tro doi vi tri
     *  vi mot ly do KHAC (nguoi dung cham vao van ban de doi cho, dung phim
     *  mui ten, ung dung tu thay doi noi dung, chuyen o nhap, ...) thi phai
     *  xoa bo dem [currentWord] - neu khong, lan go tiep theo se bi ap dung
     *  bien doi Telex nham vao "tu" cu (khong con nam canh con tro nua), gay
     *  loi kieu chen lai/nhac lai chu vua go truoc do. */
    private var selfInitiatedChange = false

    /** AudioManager dung de phat am thanh gõ phim (xem [playKeyClickTone]).
     *  TRUOC DAY dung ToneGenerator phat mot tieng "tin" (beep dien tu tong
     *  hop) - nguoi dung phan anh nghe khong hay VA moi lan goi startTone()
     *  la mot lenh dong bo toi audio HAL, lam CHAM luc go nhanh (tich luy dan
     *  qua tung phim, gay cam giac "khong theo kip"/mat chu). GIO DAY doi
     *  sang AudioManager.playSoundEffect(FX_KEYPRESS_STANDARD) - day CHINH LA
     *  am thanh "tach" nhe chuan he thong Android dung cho ban phim (khac
     *  han tieng "tin" truoc do), va ban than API nay duoc thiet ke de goi
     *  LIEN TUC, TAN SO CAO (moi lan go phim) ma khong lam nghen luong UI. */
    private val audioManager: AudioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }

    private fun playKeyClickTone() {
        try {
            audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
        } catch (e: Exception) {
            // Bo qua neu audio chua san sang (hiem gap), hoac nguoi dung da
            // tat "am thanh cham" trong Settings he thong (khi do API nay se
            // tu im lang, khong throw, nhung bat try/catch de an toan).
        }
    }

    // ---------------------------------------------------------------------
    // KHUNG QUET QR NOI (View nong them qua WindowManager cua chinh Service)
    // ---------------------------------------------------------------------

    private val qrWindowManager: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    /** View goc (FrameLayout chua preview camera + nut Huy/Flash) dang duoc
     *  add vao cua so he thong; null khi khung quet dang dong. Dung de biet
     *  khung quet co dang mo hay khong, va de go (removeView) khi dong lai. */
    private var qrOverlayView: View? = null
    private var qrPreviewView: PreviewView? = null
    private var qrCameraExecutor: ExecutorService? = null
    private var qrCamera: Camera? = null
    private var qrFlashOn = false
    private var qrFlashButton: Button? = null

    /** Tuong tu `handled` truoc day o QrScanActivity: chan viec xu ly nhieu
     *  frame camera cung luc trong khoang thoi gian tu luc tim thay 1 ma QR
     *  toi luc phat xong tieng bip/dong khung (xem [onQrFound]). */
    private val qrFrameHandled = AtomicBoolean(false)

    /** Ma QR VUA xuat ra o nhap gan nhat - chan xuat LAP LIEN TIEP cung mot
     *  noi dung (xem giai thich chi tiet o [processQrFrame]). */
    private var qrLastDeliveredText: String? = null

    /** True neu khung quet dang o CHE DO QUET LIEN TUC (mo bang dup-tap nut
     *  QR): quet duoc 1 ma xong KHONG tu dong dong, tiep tuc quet ma tiep
     *  theo cho den khi nguoi dung tu bam "Huy". */
    private var qrContinuousMode = false

    private val qrToneGenerator: ToneGenerator by lazy {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    }

    /** Vibrator cua thiet bi, dung de RUNG THAT (goi truc tiep, khong qua
     *  performHapticFeedback) moi khi nguoi dung nham mot phim - xem
     *  [vibrateKeyPress]. Lay theo cach phu hop voi tung phien ban Android:
     *  tu API 31 (Android 12) tro di phai qua VibratorManager, cac ban truoc
     *  do lay truc tiep tu Context. */
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
    }

    /** Rung nhe (~15ms) khi nham phim - goi TRUC TIEP vao Vibrator cua may
     *  thay vi chi dua vao View.performHapticFeedback(). LY DO: performHapticFeedback
     *  phu thuoc cai dat he thong "Rung khi cham" (Settings.System.HAPTIC_FEEDBACK_ENABLED)
     *  - neu nguoi dung (hoac mac dinh cua may) tat cai dat do, phim se KHONG
     *  rung du code da goi dung ham, gay hien tuong "bam phim khong thay
     *  rung" nguoi dung phan anh. Goi truc tiep Vibrator.vibrate() KHONG bi
     *  chi phoi boi cai dat do, nen chac chan rung moi lan cham phim, tru khi
     *  chinh may khong co dong co rung (hasVibrator() = false) hoac nguoi
     *  dung tat han quyen rung o cap he thong khac. */
    /** Danh dau lenh rung la loai "cham/go phim" (USAGE_TOUCH / tuong duong)
     *  thay vi khong phan loai - MOT SO MAY (dac biet Samsung/OEM tuy bien)
     *  chi ap dung thanh truot "Rung khi cham" he thong (cai ma ban phim
     *  MAC DINH dang dung) cho cac lenh rung co gan dung loai nay; lenh rung
     *  "tran" (khong AudioAttributes/VibrationAttributes) co the bi may xep
     *  vao muc "Rung he thong" chung - muc nay nhieu may de mac dinh la 0,
     *  nen goi vibrate() tran khong co tac dung gi ca du hasVibrator() = true
     *  va may van rung binh thuong voi ban phim mac dinh. */
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

    private fun vibrateKeyPress() {
        if (!vibrator.hasVibrator()) {
            if (!loggedNoVibrator) {
                loggedNoVibrator = true
                android.util.Log.w("QrKeyboardService", "Thiet bi khong co dong co rung (hasVibrator = false) - thuong xay ra khi chay tren may ao (emulator)")
            }
            return
        }
        try {
            // Do dai 40ms + bien do 200/255 - manh va ro rang hon muc mac
            // dinh, de dam bao cam nhan duoc ke ca tren may co dong co rung
            // yeu, nhung van gan dung loai "cham phim" de duoc he thong ap
            // dung dung thanh truot cuong do "Rung khi cham".
            val effect = VibrationEffect.createOneShot(40L, 200)
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    vibrator.vibrate(effect, touchVibrationAttributes)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    vibrator.vibrate(effect, touchAudioAttributes)
                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(40L)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("QrKeyboardService", "Loi khi goi vibrate(): ${e.message}")
        }
    }


    /** Mau tim neon dung CHUNG cho VIEN phat sang cua tat ca cac phim, tren
     *  CA BA trang (chu cai, so, ky hieu) - xem [buildGlowKeyBackground]. */
    private val glowColor = Color.parseColor("#B388FF")

    /** Nen phim kieu "kinh toi + vien tim phat sang", gom 2 lop GradientDrawable
     *  chong len nhau (dung LayerDrawable):
     *   - Lop NGOAI: nen gan den (hoi anh xanh), vien DAY hon nhung alpha THAP
     *     (~25%) -> tao cam giac quang sang lan ra ngoai (bloom gia lap, vi
     *     Android khong co blur re cho hang chuc phim ve cung luc).
     *   - Lop TRONG: vien MANH (1dp), mau tim DAM (khong alpha) -> duong net
     *     sac, giong duong ke tim trong anh mau.
     *  Dung CHUNG cho moi phim thuong tren CA 3 trang ban phim (chu/so/ky
     *  hieu) - xem [buildKey]. */
    private fun buildGlowKeyBackground(cornerDp: Int = 6, borderColor: Int = glowColor): Drawable {
        val outerAlphaHex = String.format("%02X", (Color.alpha(borderColor) * 0.25f).toInt().coerceIn(0, 255))
        val outerColorHex = String.format("%06X", 0xFFFFFF and borderColor)
        val outerGlow = GradientDrawable().apply {
            cornerRadius = dp(cornerDp + 2).toFloat()
            setColor(Color.parseColor("#0A0A0F"))
            setStroke(dp(4), Color.parseColor("#$outerAlphaHex$outerColorHex"))
        }
        val innerLine = GradientDrawable().apply {
            cornerRadius = dp(cornerDp).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), borderColor)
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

    /** Trang so & ky hieu co ban (nut "?123"). Hang 3 duoc dung rieng
     *  (buildNumbersRow3) vi phim dau tien la nut chuyen sang trang ky hieu
     *  mo rong, khong phai mot ky tu de chen. */
    private val numberRows = listOf(
        "1234567890",
        "@#\u0111_&-+()/"
    )
    // SUA: truoc day chi co 7 ky tu (cong voi "=\<" o dau va "⌫" o cuoi ->
    // hang nay chi co 9 phim), trong khi 2 hang tren (numberRows) co dung 10
    // phim moi hang. Vi tat ca deu dung weight = 1 bang nhau, hang co IT phim
    // hon se bi TU DONG gian RONG HON de lap day dong chieu rong man hinh ->
    // cot cua hang nay khong con thang voi cot cua 2 hang tren, nhin "lech
    // hang, khong can doi". Them dau "=" (vua thieu, vua huu ich cho van ban
    // ky thuat) de du 8 ky tu, cong voi "=\<" + "⌫" thanh dung 10 phim, khop
    // cot voi 2 hang tren.
    private val numberRow3Symbols = "*\"':;!?="

    /** Danh sach emoji cho hang emoji co the TRUOT NGANG (xem [buildEmojiRow]),
     *  hien tren trang so (trang thu 2). Mo rong len ~150 emoji, chia nhom:
     *  mat cuoi -> cu chi/tay -> cam xuc/tim -> hoat dong -> do an -> dong vat
     *  -> thien nhien/thoi tiet -> di chuyen/dia diem -> do vat -> ky hieu. */
    private val emojiList = listOf(
        // --- Mat cuoi / Bieu cam ---
        "\ud83d\ude00", // 😀 grin
        "\ud83d\ude01", // 😁 beam
        "\ud83d\ude02", // 😂 tears of joy
        "\ud83e\udd23", // 🤣 rofl
        "\ud83d\ude03", // 😃 big smile
        "\ud83d\ude04", // 😄 smile eyes
        "\ud83d\ude05", // 😅 sweat smile
        "\ud83d\ude06", // 😆 laughing
        "\ud83d\ude09", // 😉 wink
        "\ud83d\ude0a", // 😊 blush
        "\ud83d\ude0d", // 😍 heart eyes
        "\ud83e\udd70", // 🥰 hearts face
        "\ud83d\ude18", // 😘 kiss
        "\ud83d\ude17", // 😗 kissing
        "\ud83d\ude19", // 😙 kiss smile
        "\ud83d\ude1a", // 😚 kiss closed
        "\ud83d\ude0b", // 😋 yum
        "\ud83d\ude0e", // 😎 sunglasses
        "\ud83e\udd13", // 🤓 nerd
        "\ud83e\udd17", // 🤗 hugging
        "\ud83e\udd14", // 🤔 thinking
        "\ud83d\ude10", // 😐 neutral
        "\ud83d\ude11", // 😑 expressionless
        "\ud83d\ude36", // 😶 no mouth
        "\ud83d\ude0f", // 😏 smirk
        "\ud83d\ude0c", // 😌 relieved
        "\ud83d\ude14", // 😔 pensive
        "\ud83d\ude2a", // 😪 sleepy
        "\ud83d\ude34", // 😴 sleeping
        "\ud83d\ude16", // 😖 confounded
        "\ud83d\ude1e", // 😞 disappointed
        "\ud83d\ude15", // 😕 confused
        "\ud83d\ude22", // 😢 cry
        "\ud83d\ude2d", // 😭 sob
        "\ud83d\ude20", // 😠 angry
        "\ud83d\ude21", // 😡 rage
        "\ud83e\udd2c", // 🤬 cursing
        "\ud83e\udd2f", // 🤯 exploding head
        "\ud83d\ude31", // 😱 scream
        "\ud83d\ude28", // 😨 fearful
        "\ud83d\ude30", // 😰 cold sweat
        "\ud83d\ude33", // 😳 flushed
        "\ud83e\udd75", // 🥵 hot face
        "\ud83e\udd76", // 🥶 cold face
        "\ud83d\ude35", // 😵 dizzy
        "\ud83e\udd74", // 🥴 woozy
        "\ud83e\udd22", // 🤢 nauseated
        "\ud83e\udd27", // 🤧 sneezing
        "\ud83d\ude37", // 😷 mask
        "\ud83e\udd12", // 🤒 thermometer face
        "\ud83e\udd11", // 🤑 money mouth
        "\ud83e\udd20", // 🤠 cowboy
        "\ud83e\udd21", // 🤡 clown
        "\ud83d\ude08", // 😈 devil smile
        "\ud83d\udc7f", // 👿 angry devil
        "\ud83d\udc80", // 💀 skull
        "\ud83d\udc7d", // 👽 alien
        "\ud83e\udd16", // 🤖 robot

        // --- Cu chi / Tay ---
        "\ud83d\udc4d", // 👍 thumbs up
        "\ud83d\udc4e", // 👎 thumbs down
        "\ud83d\udc4f", // 👏 clap
        "\ud83d\ude4f", // 🙏 pray/thanks
        "\ud83e\udd1d", // 🤝 handshake
        "\u270c\ufe0f", // ✌️ victory
        "\ud83e\udd1e", // 🤞 crossed fingers
        "\ud83e\udd1f", // 🤟 love you hand
        "\ud83e\udd18", // 🤘 rock on
        "\ud83d\udc4c", // 👌 ok
        "\ud83e\udd19", // 🤙 call me
        "\u261d\ufe0f", // ☝️ point up
        "\ud83d\udc46", // 👆 point up 2
        "\ud83d\udc47", // 👇 point down
        "\ud83d\udc48", // 👈 point left
        "\ud83d\udc49", // 👉 point right
        "\ud83d\udc4a", // 👊 fist bump
        "\u270a", // ✊ raised fist
        "\ud83e\udd1b", // 🤛 left fist
        "\ud83e\udd1c", // 🤜 right fist
        "\ud83d\udc4b", // 👋 wave
        "\ud83e\udd1a", // 🤚 raised back hand
        "\ud83d\udd90\ufe0f", // 🖐️ hand splayed
        "\u270b", // ✋ raised hand
        "\ud83d\udc4e", // 👎 dislike
        "\ud83d\udcaa", // 💪 muscle
        "\ud83d\ude4c", // 🙌 celebrate hands
        "\ud83d\ude4b", // 🙋 hand raise person
        "\ud83e\udd26", // 🤦 facepalm
        "\ud83e\udd37", // 🤷 shrug

        // --- Tim / Cam xuc ---
        "\u2764\ufe0f", // ❤️ red heart
        "\ud83e\udde1", // 🧡 orange heart
        "\ud83d\udc9b", // 💛 yellow heart
        "\ud83d\udc9a", // 💚 green heart
        "\ud83d\udc99", // 💙 blue heart
        "\ud83d\udc9c", // 💜 purple heart
        "\ud83d\udc97", // 💗 growing heart
        "\ud83d\udc96", // 💖 sparkling heart
        "\ud83d\udc95", // 💕 two hearts
        "\ud83d\udc94", // 💔 broken heart
        "\u2665\ufe0f", // ♥️ heart suit
        "\ud83d\udcaf", // 💯 100
        "\u2b50", // ⭐ star
        "\ud83c\udf1f", // 🌟 glowing star
        "\ud83d\udd25", // 🔥 fire
        "\u26a1", // ⚡ lightning
        "\ud83c\udf89", // 🎉 party popper
        "\ud83c\udf8a", // 🎊 confetti

        // --- Do an / Uong ---
        "\ud83c\udf55", // 🍕 pizza
        "\ud83c\udf54", // 🍔 burger
        "\ud83c\udf5c", // 🍜 noodles
        "\ud83c\udf5b", // 🍛 curry
        "\ud83c\udf63", // 🍣 sushi
        "\ud83c\udf62", // 🍢 oden
        "\ud83e\udd6a", // 🥪 sandwich
        "\ud83c\udf2e", // 🌮 taco
        "\ud83c\udf2f", // 🌯 burrito
        "\ud83e\udd57", // 🥗 salad
        "\ud83c\udf70", // 🎰 cake slice
        "\ud83c\udf82", // 🎂 birthday cake
        "\ud83c\udf69", // 🍩 donut
        "\ud83c\udf6a", // 🍪 cookie
        "\ud83c\udf6b", // 🍫 chocolate
        "\ud83c\udf6c", // 🍬 candy
        "\ud83c\udf6d", // 🍭 lollipop
        "\ud83e\udd64", // 🥤 cup straw
        "\ud83c\udf7a", // 🍺 beer
        "\ud83c\udf77", // 🍷 wine
        "\u2615", // ☕ coffee
        "\ud83c\udf75", // 🍵 tea
        "\ud83c\udf7d\ufe0f", // 🍽️ fork knife plate
        "\ud83e\udd51", // 🥑 avocado
        "\ud83c\udf4e", // 🍎 apple
        "\ud83c\udf4a", // 🍊 orange
        "\ud83c\udf4b", // 🍋 lemon
        "\ud83c\udf49", // 🍉 watermelon
        "\ud83c\udf53", // 🍓 strawberry
        "\ud83c\udf47", // 🍇 grapes

        // --- Dong vat ---
        "\ud83d\udc36", // 🐶 dog
        "\ud83d\udc31", // 🐱 cat
        "\ud83d\udc2d", // 🐭 mouse
        "\ud83d\udc39", // 🐹 hamster
        "\ud83d\udc30", // 🐰 rabbit
        "\ud83d\udc3b", // 🐻 bear
        "\ud83d\udc3c", // 🐼 panda
        "\ud83d\udc28", // 🐨 koala
        "\ud83d\udc2f", // 🐯 tiger
        "\ud83e\udd81", // 🦁 lion
        "\ud83d\udc2e", // 🐮 cow
        "\ud83d\udc37", // 🐷 pig
        "\ud83d\udc24", // 🐤 chick
        "\ud83d\udc27", // 🐧 penguin
        "\ud83d\udc26", // 🐦 bird
        "\ud83e\udd85", // 🦅 eagle
        "\ud83d\udc2c", // 🐬 dolphin
        "\ud83d\udc33", // 🐳 whale
        "\ud83d\udc20", // 🐠 tropical fish
        "\ud83d\udc19", // 🐙 octopus
        "\ud83e\udd8b", // 🦋 butterfly

        // --- Thien nhien / Thoi tiet ---
        "\u2600\ufe0f", // ☀️ sun
        "\ud83c\udf24\ufe0f", // 🌤️ sun behind cloud
        "\u2601\ufe0f", // ☁️ cloud
        "\ud83c\udf27\ufe0f", // 🌧️ rain
        "\u26c4", // ⛄ snowman
        "\ud83c\udf08", // 🌈 rainbow
        "\ud83c\udf0a", // 🌊 wave
        "\ud83c\udf38", // 🌸 cherry blossom
        "\ud83c\udf39", // 🌹 rose
        "\ud83c\udf3b", // 🌻 sunflower
        "\ud83c\udf3c", // 🌼 blossom
        "\ud83c\udf40", // 🍀 four leaf clover
        "\ud83c\udf41", // 🍁 maple leaf
        "\ud83c\udf3f", // 🌿 herb
        "\ud83c\udf0d", // 🌍 earth africa
        "\ud83c\udf19", // 🌙 crescent moon
        "\ud83d\udc4b", // 👋 wave hi

        // --- Di chuyen / Dia diem ---
        "\ud83d\ude97", // 🚗 car
        "\ud83d\ude8c", // 🚌 bus
        "\ud83d\ude82", // 🚂 train
        "\ud83d\udea2", // 🚢 ship
        "\u2708\ufe0f", // ✈️ airplane
        "\ud83d\ude80", // 🚀 rocket
        "\ud83d\udeb2", // 🚲 bicycle
        "\ud83c\udfe0", // 🏠 house
        "\ud83c\udfe2", // 🏢 office
        "\ud83c\udfd6\ufe0f", // 🏖️ beach
        "\ud83c\udfd4\ufe0f", // 🏔️ mountain
        "\ud83d\uddfa\ufe0f", // 🗺️ map
        "\ud83d\udccd", // 📍 pin
        "\ud83c\udf0f", // 🌏 earth asia

        // --- Do vat / Cong nghe ---
        "\ud83d\udcf1", // 📱 phone
        "\ud83d\udcbb", // 💻 laptop
        "\u2328\ufe0f", // ⌨️ keyboard
        "\ud83d\udda5\ufe0f", // 🖥️ desktop
        "\ud83d\udcf7", // 📷 camera
        "\ud83c\udfa4", // 🎤 microphone
        "\ud83c\udfa7", // 🎧 headphone
        "\ud83d\udcfa", // 📺 tv
        "\ud83d\udcda", // 📚 books
        "\ud83d\udcdd", // 📝 memo
        "\ud83d\udce7", // 📧 email
        "\ud83d\udd14", // 🔔 bell
        "\ud83d\udcb0", // 💰 money bag
        "\ud83d\udcb3", // 💳 credit card
        "\ud83c\udf81", // 🎁 gift
        "\ud83d\udd12", // 🔒 lock
        "\ud83d\udd13", // 🔓 unlock
        "\ud83d\udd0d", // 🔍 search
        "\u2705", // ✅ check
        "\u274c", // ❌ cross
        "\u23f0", // ⏰ alarm clock
        "\ud83d\udcca", // 📊 chart
        "\ud83d\udcc8", // 📈 up chart
        "\ud83d\udcc9"  // 📉 down chart
    )

    /** Trang ky hieu mo rong (nut "=\<"). Truoc day 2 phim dau hang thu 2 la
     *  £, € (ky hieu tien te it dung) - doi thanh <, > (dau ngoac nhon) de
     *  huu ich hon cho viec go code/van ban ky thuat. */
    private val extendedSymbolRows = listOf(
        "~`|\u2022\u221a\u03c0\u00f7\u00d7\u00b6\u0394",
        "<>$\u00a2^\u00b0={}\\"
    )
    // SUA: ky tu \u2105 ("care of", trong nhu chu "c/o" viet tat) o co nho
    // hien thi TRONG GAN GIONG dau "%" ben canh, khien nguoi dung thay nham
    // nhu co 2 dau "%" trung nhau tren ban phim. Bo ky tu do, thay bang 2 ky
    // hieu ro rang, khac biet va huu ich hon (§ dau muc/dieu luat, ± cong-tru)
    // - vua het nham lan, vua du 8 ky tu (cong voi "?123" + "⌫" thanh dung 10
    // phim, khop cot voi 2 hang tren, xem [numberRow3Symbols] cho ly do tuong
    // tu ben trang So).
    private val extendedSymbolRow3 = "%\u00a9\u00ae\u2122\u00a7\u00b1[]"

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** LOI nguoi dung phan anh: man hinh NGANG (landscape) hoac cua so hep
     *  (chieu cao man hinh nho) khien toan bo ban phim (5 hang x 48dp/hang =
     *  ~262dp, TRUOC DAY chieu cao phim la SO CO DINH, khong doi theo man
     *  hinh) CAO HON khong gian thuc te danh cho ban phim. Vi cua so IME luon
     *  duoc neo o DAY man hinh va "moc" len TREN, phan vuot qua bi day len
     *  TREN DINH man hinh - tuc bi CAT MAT/KHUAT hoan toan (khong phai cuon
     *  duoc, chi la khong con cho de ve), dung y het hien tuong nguoi dung
     *  chup man hinh: chi con thay 2 hang duoi cung (zxcvbnm + hang phim
     *  cach), hang zxcvbnm bi "cat" o mep tren vi no la hang DAU TIEN con
     *  vua may cham toi vach tren cung con hien duoc.
     *
     *  KHAC PHUC: tinh chieu cao MOI phim (dung chung cho toan bo ban phim,
     *  thay cho hang so 48 co dinh) dua theo [screenHeightDp] THUC TE cua
     *  man hinh hien tai (tu dong cap nhat lai moi khi ban phim duoc ve lai,
     *  vd sau khi xoay man hinh) - man hinh cang thap (ngang, cua so nho) thi
     *  phim cang thap theo TY LE, dam bao TOAN BO 5 hang luon vua du trong
     *  khong gian thuc te, khong con hang nao bi day khuat len tren nua. Man
     *  hinh doc binh thuong (screenHeightDp lon) van giu nguyen 48dp nhu cu,
     *  khong doi gi ca. */
    private val keyHeightDp: Int
        get() {
            val screenHeightDp = resources.configuration.screenHeightDp
            return when {
                screenHeightDp <= 400 -> 34 // man hinh ngang (landscape) tren hau het dien thoai
                screenHeightDp <= 550 -> 42 // man hinh doc nhung thap (dien thoai nho, cua so chia doi)
                else -> 48 // man hinh doc binh thuong - giu nguyen nhu cu
            }
        }

    override fun onCreateInputView(): View = buildKeyboardView()

    /** Ve lai toan bo ban phim theo [mode] hien tai. */
    private fun buildKeyboardView(): View {
        // Vien tren/duoi cua toan bo ban phim cung co giam theo [keyHeightDp]
        // de danh them chut khong gian doc khi man hinh thap (xem [keyHeightDp]).
        val verticalPaddingDp = if (keyHeightDp < 48) 2 else 6
        // NHICH CA BAN PHIM LEN mot chut (theo phan anh nguoi dung): them
        // rieng vao vien DUOI CUNG (khong dong vao vien tren) mot khoang dem
        // nho [EXTRA_BOTTOM_LIFT_DP]. Vi cua so ban phim (IME) tu dong tinh
        // chieu cao theo WRAP_CONTENT va luon dinh (neo) o SAT DAY man hinh,
        // them khoang dem o DUOI se day toan bo cac hang phim len cao hon
        // mot chut so voi day man hinh/thanh dieu huong, ma KHONG lam xe dich
        // vi tri tuong doi giua cac hang voi nhau (khac voi viec doi
        // [verticalPaddingDp] o tren, vi do la vien CHUNG ca tren lan duoi).
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050507"))
            setPadding(dp(4), dp(verticalPaddingDp), dp(4), dp(verticalPaddingDp + EXTRA_BOTTOM_LIFT_DP))
        }

        when (mode) {
            KeyboardMode.LETTERS -> {
                // Neu dang co goi y sua loi Tieng Viet dang cho (xem
                // [pendingSuggestion]), hien thanh goi y NGAY TREN CUNG,
                // truoc ca hang so, de de thay va cham vao ngay.
                if (pendingSuggestion != null) {
                    root.addView(buildAutocorrectSuggestionRow())
                }
                // Hang so (1234567890) luon hien thi co dinh phia tren cac hang
                // chu cai, khong can chuyen trang moi go duoc so.
                root.addView(buildCharRow(numberRows[0]))
                letterRows.forEachIndexed { index, row ->
                    val rowView = buildCharRow(row, applyShiftCase = true)
                    if (index == letterRows.lastIndex) {
                        // SUA loi nguoi dung phan anh (lan 2): ban vá truoc day
                        // (topMargin -= dp(5) o day, cong topMargin = dp(5) o
                        // [buildLettersBottomRow]) tuy giu TONG chieu cao ban
                        // phim khong doi, nhung lai lam 2 khoang cach quanh
                        // hang nay LECH NHAU ro ret: khoang cach PHIA TREN
                        // (voi hang asdfghjkl) bi am (-3dp, tuc la CHONG LEN
                        // nhau/"sat vao nhau"), trong khi khoang cach PHIA
                        // DUOI (voi hang ?123/,/cach/./Enter) lai thanh +7dp -
                        // gap hon 3 lan khoang cach binh thuong (2dp) giua cac
                        // hang khac. KHONG con chinh topMargin rieng cho hang
                        // nay nua - de no dung khoang cach MAC DINH (giong het
                        // cac hang chu cai khac o tren), tu do deu voi ca hang
                        // tren lan hang duoi.
                        // Hang chu cai cuoi cung (zxcvbnm): nut Shift (⇧) o
                        // DAU hang (ben trai), nut xoa (⌫) o CUOI hang (ben
                        // phai) - giu nguyen vi tri nay (KHONG chuyen xuong
                        // hang duoi cung), chi sua loi "Shift bi lech cao"
                        // bang isBaselineAligned = false o [buildCharRow].
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
                                    }
                                    isShiftOn -> {
                                        isShiftOn = false
                                        capitalizeNextLetter = false
                                        showCapitalPreview = false
                                    }
                                    else -> {
                                        capitalizeNextLetter = !capitalizeNextLetter
                                        showCapitalPreview = capitalizeNextLetter
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
            KeyboardMode.NUMBERS -> {
                // Hang emoji co the TRUOT NGANG, nam TREN CUNG trang so, de
                // nguoi dung luot tim emoji mong muon ma khong can chuyen
                // trang hay mo ban phim emoji rieng cua he thong.
                root.addView(buildEmojiRow())
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
        // Ban phim vua duoc mo lai (du la lan dau hay do he thong tai tao
        // sau mot lan finishingInput = true "hu") - huy moi lenh dong khung
        // quet QR dang cho, giu nguyen khung quet + camera nhu truoc do.
        cancelPendingFinishHide()
        currentWord.clear()
        capitalizeNextLetter = false
        showCapitalPreview = false
        val hadPendingSuggestion = pendingSuggestion != null
        clearAutocorrectSuggestion()
        if (mode != KeyboardMode.LETTERS) {
            switchMode(KeyboardMode.LETTERS)
        } else if (hadPendingSuggestion) {
            redrawKeyboard()
        }
    }

    /** Mot hang phim don gian: moi ky tu trong chuoi la mot nut cung do rong
     *  bang nhau (weight 1), chen nguyen van ky tu do khi bam. Dung chung cho
     *  ca hang chu cai (co ap dung Shift de HIEN THI hoa/thuong) lan hang
     *  so/ky hieu. */
    private fun buildCharRow(chars: String, applyShiftCase: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // SUA loi nguoi dung phan anh: phim Shift (⇧, textSize 28f) nam
            // "cao hon" han cac phim chu thuong (z, x, c... textSize 16f)
            // CUNG HANG, DU tat ca deu khai bao chieu cao dp(keyHeightDp) y
            // het nhau. NGUYEN NHAN: LinearLayout mac dinh (isBaselineAligned
            // = true) CANH cac phim con theo BASELINE CHU (duong ke chan
            // chu), khong phai theo KHUNG phim - chu cang to (28f) thi
            // baseline cang nam THAP hon trong khung phim (de danh cho phan
            // "duoi dong" - descent), buoc Android phai DAY LECH VI TRI ca
            // khung phim do de baseline khop voi cac phim chu nho hon, dan
            // toi hien tuong "phim to chu bi day cao/thap hon" nhu phan anh.
            // TAT han (isBaselineAligned = false) de MOI phim duoc dat ngay
            // ngan theo dung KHUNG (top/height thuc te, giong het nhau) -
            // khong con phu thuoc kich thuoc chu ben trong nua.
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        chars.forEach { ch ->
            // Hien thi chu HOA tren phim khi dang bat Caps Lock (isShiftOn)
            // HOAC dang bat "viet hoa 1 chu ke tiep" (capitalizeNextLetter) -
            // truoc day chi kiem tra isShiftOn nen o trang thai Shift "don"
            // (1 chu), phim van hien chu thuong, khong khop voi chu THAT SU
            // se duoc chen ra (da viet hoa, xem [insertChar]).
            val label = if (applyShiftCase && (isShiftOn || showCapitalPreview)) ch.uppercaseChar().toString() else ch.toString()
            row.addView(buildKey(label) { insertChar(ch) })
        }
        return row
    }

    /** Hang emoji co the TRUOT (vuot) NGANG bang tay de tim emoji mong muon,
     *  hien o dau trang so (trang thu 2). Dung HorizontalScrollView boc
     *  quanh mot LinearLayout ngang chua tung nut emoji co CHIEU RONG CO
     *  DINH (khac voi cac hang phim khac dung "weight" chia deu, vi ben
     *  trong ScrollView khong the dung weight - noi dung phai co chieu rong
     *  that, TRAN ra ngoai man hinh, moi truot/vuot duoc). Cham vao emoji se
     *  chen truc tiep emoji do vao o nhap lieu, giong nhu mot phim thuong. */
    private fun buildEmojiRow(): View {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        // Kich thuoc phim emoji CUNG co giai theo [keyHeightDp] (tru di 8dp
        // de danh vien margin), dong bo voi cac phim khac tren ban phim khi
        // man hinh ngang/thap.
        val emojiKeySizePx = dp(keyHeightDp - 8)
        emojiList.forEach { emoji ->
            val bg = buildGlowKeyBackground(cornerDp = 4)
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
                background = bg
                stateListAnimator = null
                elevation = 0f
                outlineProvider = null
                isHapticFeedbackEnabled = true
                layoutParams = LinearLayout.LayoutParams(emojiKeySizePx, emojiKeySizePx).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
     *  (cham vao de ap dung goi y) va mot nut nho "\u2715" de bo qua goi y nay. */
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
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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

    /** Kiem tra "tu" [word] (chu thuong) nguoi dung vua go xong (ket thuc
     *  bang dau cach) co trong tu dien khong; neu KHONG co va tim duoc mot
     *  tu gan giong (xem [VietnameseAutocorrect.suggestFor]), luu lai thanh
     *  [pendingSuggestion] de [buildAutocorrectSuggestionRow] hien ra. */
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

    /** Ap dung goi y dang cho: XOA lai tu da go (cong them 1 ky tu cho dau
     *  cach ket thuc tu do) roi CHEN tu goi y + dau cach vao thay the. */
    private fun acceptAutocorrectSuggestion() {
        val original = pendingSuggestionOriginalWord ?: return
        val suggestion = pendingSuggestion ?: return
        val ic = currentInputConnection
        if (ic != null) {
            selfInitiatedChange = true
            ic.beginBatchEdit()
            try {
                // +1 vi tu do da co mot dau cach ngay sau khi duoc chen.
                ic.deleteSurroundingText(original.length + 1, 0)
                ic.commitText("$suggestion ", 1)
            } finally {
                ic.endBatchEdit()
            }
        }
        clearAutocorrectSuggestion()
        redrawKeyboard()
    }

    /** Bo goi y dang cho (khong ap dung) - goi khi nguoi dung go tiep, xoa,
     *  Enter, chuyen o nhap, hoac tu cham nut "\u2715" bo qua. */
    private fun clearAutocorrectSuggestion() {
        if (pendingSuggestion != null || pendingSuggestionOriginalWord != null) {
            pendingSuggestion = null
            pendingSuggestionOriginalWord = null
        }
    }

    /** Hang duoi cung trang chu cai: nut "," (thay cho nut QR truoc day -
     *  QR da chuyen sang trang so, doi cho voi dau ",") va dau "." moi CHUYEN
     *  TU trang so SANG day, dat ngay ben phai phim cach (giua phim cach va
     *  Enter) de go cau nhanh hon ma khong can chuyen trang. */
    private fun buildLettersBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // SUA loi "5 phim trong hang nay bi lech nhau" (?123/,/phim
            // cach/./Enter khong ngang hang, xem giai thich day du trong
            // [buildSpaceKey]): hang nay GIO dung CHIEU CAO CO DINH (thay
            // cho WRAP_CONTENT truoc day), de tat ca phim ben trong co the
            // dung MATCH_PARENT (fillRowHeight = true) ma chia deu chinh
            // xac chieu cao nay - khong con phep tinh margin rieng biet nao
            // co the khien mot phim "ra" ket qua khac phim khac nua.
            //
            // Khoang trong PHIA DUOI hang nay (de "nhich" ca hang len mot
            // chut, tach voi vien duoi cung ban phim - hieu ung tuong tu
            // verticalNudgeDp=-3 truoc day) GIO dat MOT LAN DUY NHAT o day,
            // cho CA HANG, thay vi tung phim rieng - dam bao 100% khong the
            // co truong hop mot phim "quen" nhich theo hang.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(keyHeightDp + 2)
            ).apply {
                // KHONG con dat topMargin rieng o day nua (xem giai thich
                // chi tiet tai [buildKeyboardView], cho nhanh xu ly hang
                // Shift/zxcvbnm/⌫) - phep "tra lai" topMargin = dp(5) truoc
                // day thuc ra lam khoang cach hang nay QUA XA so voi hang
                // tren no, trong khi hang tren lai bi keo CHONG LEN hang
                // TREN NO NUA. Bo ca hai cho khoang cach mac dinh, deu nhau.
                bottomMargin = dp(6)
            }
        }

        row.addView(buildKey("?123", weight = 1.4f, fillRowHeight = true) { switchMode(KeyboardMode.NUMBERS) })
        row.addView(buildKey(",", weight = 1f, fillRowHeight = true) { insertText(",") })
        row.addView(buildSpaceKey(weight = 4.2f))
        row.addView(buildKey(".", weight = 1f, fillRowHeight = true) {
            insertText(".")
            // Bat co "viet hoa chu tiep theo" - xem giai thich o khai bao
            // [capitalizeNextLetter].
            capitalizeNextLetter = true
            showCapitalPreview = true
        })
        row.addView(buildKey("\u21b5", weight = 1.4f, highlight = true, fillRowHeight = true) { sendEnter() })

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

        // Dung CUNG mot ty le weight (phim bien 1.3f, phim ky tu 1f) nhu
        // [buildExtendedSymbolsRow3] - truoc day hang nay dung toan weight
        // mac dinh 1f cho tat ca (ke ca "=\<" va "⌫"), khien cot cua trang So
        // va trang Ky hieu mo rong LECH nhau du ca hai deu co 10 phim/hang,
        // vi ty le rong phim bien/phim thuong khac nhau giua 2 trang.
        row.addView(buildKey("=\\<", weight = 1.3f) { switchMode(KeyboardMode.SYMBOLS) })
        numberRow3Symbols.forEach { ch ->
            row.addView(buildKey(ch.toString(), weight = 1f) { insertText(ch.toString()) })
        }
        row.addView(buildKey("\u232b", weight = 1.3f, onRepeat = { deleteChar() }) { deleteChar() })

        return row
    }

    /** Hang duoi cung cua trang so: nut "QR" (thay cho dau "," truoc day -
     *  da doi cho sang trang chu cai) de mo may quet QR ngay tu trang so ma
     *  khong can chuyen ve trang chu cai truoc. Dau "." da CHUYEN SANG trang
     *  chu cai (canh phim cach) nen khong con o day nua.
     *
     *  Nut QR gio phan biet CHAM 1 LAN va DUP-TAP (cham 2 lan lien tiep,
     *  trong vong [QR_DOUBLE_TAP_MAX_INTERVAL_MS]):
     *   - Cham 1 lan: mo man hinh quet o che do BINH THUONG - quet duoc 1 ma
     *     la tu dong dong lai ngay (hanh vi cu, khong doi).
     *   - Dup-tap: mo man hinh quet o che do LIEN TUC - sau khi quet duoc 1
     *     ma, KHONG tu dong dong, ma tiep tuc quet ma tiep theo, cho den khi
     *     nguoi dung tu bam nut "Huy" tren man hinh quet. */
    private fun buildNumbersBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Dong bo chieu cao + khoang trong phia duoi voi
            // [buildLettersBottomRow] (xem giai thich chi tiet o do va o
            // [buildSpaceKey]) - dam bao ca 4 phim (ABC/QR/phim cach/Enter)
            // luon ngang hang tuyet doi, khong con phep tinh margin rieng
            // biet nao co the khien mot phim lech khoi cac phim con lai.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(keyHeightDp + 2)
            ).apply { bottomMargin = dp(6) }
        }

        row.addView(buildKey("ABC", weight = 1.6f, fillRowHeight = true) { switchMode(KeyboardMode.LETTERS) })
        row.addView(buildKey("QR", weight = 1.2f, highlight = true, fillRowHeight = true) {
            val now = android.os.SystemClock.uptimeMillis()
            val isDoubleTap = now - lastQrKeyTapTime <= QR_DOUBLE_TAP_MAX_INTERVAL_MS
            // Dat lai ve 0 sau khi da tinh la dup-tap, de mot cham thu 3 lien
            // ngay sau do khong bi hieu nham la dup-tap cua cap tiep theo.
            lastQrKeyTapTime = if (isDoubleTap) 0L else now
            openQrScanner(continuous = isDoubleTap)
        })
        row.addView(buildSpaceKey(weight = 5.4f))
        row.addView(buildKey("\u21b5", weight = 1.6f, highlight = true, fillRowHeight = true) { sendEnter() })

        return row
    }

    /** Hang 3 cua trang ky hieu mo rong: nut "?123" de quay lai trang so,
     *  cac ky hieu %, ©, ®, ™, §, ±, [, ] va nut xoa - 10 phim, cung ty le
     *  rong (weight) voi [buildNumbersRow3] de cot thang deu giua 2 trang. */
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

    /** Hang duoi cung cua trang ky hieu mo rong: truoc day trang nay bi
     *  THIEU han hang dau cach - gio them lai, dong bo voi trang chu cai va
     *  trang so (nut "ABC" ve trang chu cai, phim cach, Enter). */
    private fun buildExtendedSymbolsBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Dong bo chieu cao + khoang trong phia duoi voi 2 trang kia -
            // xem giai thich chi tiet trong [buildLettersBottomRow] va
            // [buildSpaceKey]. Ca 3 phim (ABC/phim cach/Enter) gio dung
            // fillRowHeight = true nen luon chia deu dung chieu cao hang
            // nay, khong the lech nhau.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(keyHeightDp + 2)
            ).apply { bottomMargin = dp(6) }
        }

        row.addView(buildKey("ABC", weight = 1.4f, fillRowHeight = true) { switchMode(KeyboardMode.LETTERS) })
        row.addView(buildSpaceKey(weight = 4.8f))
        row.addView(buildKey("\u21b5", weight = 1.4f, highlight = true, fillRowHeight = true) { sendEnter() })

        return row
    }

    /** Phim cach: chuc nang chinh la chen dau cach khi CHAM binh thuong
     *  (khong keo ngang qua nguong [SPACE_SWIPE_THRESHOLD_DP]). Neu ngon tay
     *  VUOT ngang qua nguong do truoc khi tha ra, xem la mot cu vuot doi ngon
     *  ngu thay vi mot cai cham:
     *   - Vuot TU TRAI SANG PHAI (deltaX duong)  -> ve Tieng Anh.
     *   - Vuot TU PHAI SANG TRAI (deltaX am)     -> sang Tieng Viet.
     *  Hai chu "V" (trai) va "E" (phai) ghim co dinh o hai dau phim, chu nao
     *  ung voi ngon ngu DANG BAT thi to/sang mau xanh de nguoi dung biet minh
     *  dang o che do nao va vuot ve huong nao de doi. Khong con dung kieu
     *  cham nhanh 2 lan (double-tap) nhu truoc, vi kieu do de bi kich hoat
     *  nham khi go nhanh lien tiep 2 dau cach gan nhau (vd giua 2 cau), gay
     *  doi ngon ngu ngoai y muon giua chung. */
    private fun buildSpaceKey(weight: Float): View {
        val bg = buildGlowKeyBackground()
        val container = FrameLayout(this).apply {
            background = bg
            stateListAnimator = null
            elevation = 0f
            outlineProvider = null
            // SUA TRIET DE loi "hang duoi cung bi lech" (phim cach thap/cao
            // hon cac phim Button ben canh du code TUONG duoc viet giong
            // nhau): TRUOC DAY ham nay tinh margin tren/duoi rieng theo cong
            // thuc verticalNudgeDp/baseMarginDp, y het cong thuc trong
            // [buildKey] - VE LY THUYET hai cong thuc giong nhau nen phai ra
            // cung mot vi tri, nhung Button va FrameLayout (View nay) la HAI
            // LOAI VIEW KHAC NHAU, khong dam bao Android do/dat chung theo
            // dung tung px nhu nhau du cung mot con so margin - dan den vien
            // sang cua phim cach van bi lech vai px so voi phim ben canh tren
            // may thuc te (nhu anh chup nguoi dung gui).
            //
            // CACH SUA GOC RE: KHONG con dat CHIEU CAO rieng + margin tren/
            // duoi rieng cho tung phim trong hang nay nua. Thay vao do, phim
            // cach (cung nhu tat ca phim Button dung fillRowHeight=true trong
            // [buildKey]) LUON dung MATCH_PARENT de chiem TRON VEN chieu cao
            // cua CHINH HANG chua no (hang da duoc gan mot chieu cao CO DINH
            // - xem [buildLettersBottomRow]/[buildNumbersBottomRow]/
            // [buildExtendedSymbolsBottomRow]), cong mot margin DOI XUNG y
            // het nhau (dp(1) ca 4 canh) cho MOI phim khong phan biet loai
            // View. Vi khong con phep tinh nudge/base rieng biet nua, KHONG
            // THE co truong hop hai loai View "tinh ra" hai ket qua khac
            // nhau - chung don gian CHIA DEU chieu cao hang, tuyet doi khong
            // the lech. Muon "nhich" ca hang len/xuong (vd tao khoang trong
            // phia duoi nhu thiet ke cu) thi chinh margin cua CA HANG (mot
            // lan, o ham build*BottomRow), khong chinh tung phim rieng le.
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
            setTextColor(Color.WHITE)
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
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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

    /** Chuyen thang sang che do [vietnamese] (khong phai toggle) - dung boi
     *  cu vuot tren phim cach, moi huong vuot ung voi dung MOT ngon ngu cu
     *  the (xem [buildSpaceKey]), khong phu thuoc trang thai hien tai. */
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

    /** Xay dung mot phim bam:
     *  - Rung nhe (haptic) khi nham xuong.
     *  - Neu la phim mot ky tu don, "noi" mot bong xem truoc phong to ngay
     *    phia tren phim trong luc dang nham.
     *  - Neu co [onRepeat], nham GIU phim se tu dong goi lai [onRepeat] lien
     *    tuc (sau [DELETE_REPEAT_INITIAL_DELAY_MS] dau tien, roi lap lai moi
     *    [DELETE_REPEAT_INTERVAL_MS]) cho den khi tha tay ra - dung cho phim
     *    xoa (⌫) de "giu la xoa hoai". Neu chi cham nhanh (tha ra truoc khi
     *    kich hoat lap lai), van goi [onClick] binh thuong dung 1 lan. */
    private fun buildKey(
        label: String,
        weight: Float = 1f,
        highlight: Boolean = false,
        // Dat true CHI cho phim nam trong mot hang co CHIEU CAO CO DINH va
        // co CHUA phim cach (FrameLayout tu [buildSpaceKey]) canh ben, vd
        // hang duoi cung cua ca 3 trang ban phim - xem
        // [buildLettersBottomRow]/[buildNumbersBottomRow]/
        // [buildExtendedSymbolsBottomRow]. Khi true, phim dung MATCH_PARENT
        // cho chieu cao (chiem TRON VEN chieu cao hang, cong margin dp(1)
        // doi xung) THAY CHO chieu cao rieng dp(keyHeightDp) + margin tinh
        // theo cong thuc nudge nhu truoc - day la CACH SUA GOC RE cho loi
        // "hang duoi cung bi lech" (xem giai thich chi tiet trong
        // [buildSpaceKey]): vi phim Button va phim cach (FrameLayout) la hai
        // loai View khac nhau, KHONG the tin tuong rang Android se do/dat
        // chung ra CUNG mot vi tri du duoc truyen cung mot cong thuc margin
        // - chi co cach chia deu chieu cao hang (MATCH_PARENT) la dam bao
        // tuyet doi khong the lech, vi khong con phep tinh rieng biet nao
        // nua de hai loai View co the "ra" ket qua khac nhau.
        fillRowHeight: Boolean = false,
        onRepeat: (() -> Unit)? = null,
        onClick: () -> Unit
    ): Button {
        // Phim highlight (Enter, Shift dang bat, nut QR): VAN dung nen kinh
        // TOI GIONG HET phim thuong (khong con to DAC mot khoi mau xanh nhu
        // truoc - nhin lac tong so voi cac phim khac), CHI DOI mau VIEN sang
        // xanh duong (thay vi tim) de van de nhan biet la phim "dang bat"/
        // dac biet, ma van dong bo phong cach kinh + vien sang voi toan bo
        // ban phim.
        val bg: Drawable = if (highlight) {
            buildGlowKeyBackground(borderColor = Color.parseColor("#4FC3F7"))
        } else {
            buildGlowKeyBackground()
        }
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            // Chu nho hon o cac phim nhieu ky tu (vd "?123", "EN") va cho
            // hien thi tren MOT dong duy nhat, tranh bi xuong dong roi cat
            // mat chu (vd chi con thay dau "?" ma khong thay "123").
            // RIENG hai phim bieu tuong Enter (\u21b5) va Shift/in hoa (\u2b06):
            // TO HON HAN cac phim mot-ky-tu thong thuong - nguoi dung phan
            // anh 2 phim nay VAN CON hoi nho (24f) sau lan chinh truoc, tang
            // tiep len 28f de thay ro han nua.
            textSize = when {
                label == "\u21b5" || label == "\u2b06" -> 28f
                label.length > 3 -> 11f
                label.length > 1 -> 13f
                else -> 16f
            }
            isSingleLine = true
            // QUAN TRONG: sua loi dau cau Tieng Viet chu HOA bi "thut xuong"/
            // bi cat mat. Theme Button mac dinh cua Android dat
            // includeFontPadding = false de chu gon hon, nhung dieu nay lam
            // Android CAT MAT phan tren cua dau (dac biet dau sac/nga/hoi tren
            // chu hoa co dau mu nhu "Ấ", "Ế", "Ổ"...), khien dau nhin nhu bi
            // "thut" xuong de/nam len chu. Bat lai includeFontPadding = true
            // de danh du khong gian (line spacing) cho dau hien day du, dung
            // vi tri, khong bi vat cat.
            includeFontPadding = true
            setPadding(dp(1), 0, dp(1), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            background = bg
            // LOI nguoi dung phan anh: cac phim trong CUNG MOT HANG nhin
            // "cao thap khong deu", phim nay nhu bi day len/xuong so voi phim
            // kia du cung khai bao chieu cao dp(keyHeightDp) y het nhau.
            // NGUYEN NHAN: Button (khac voi FrameLayout dung cho rieng phim
            // cach) mac dinh KE THUA elevation/StateListAnimator tu theme he
            // thong (Material) - tao mot lop BONG DO (shadow) mo o VIEN
            // DUOI/QUANH moi nut, dich sang mot huong khi nhan. Lop bong nay
            // VE RA NGOAI ranh gioi thuc cua nut, khien mat thuong thay nut
            // nhu bi "nhoe/lech" xuong duoi vai px so voi phim cach (FrameLayout,
            // khong co bong nay) - du kich thuoc THAT SU (do vien tim/xanh)
            // cua ca hai la HOAN TOAN bang nhau. Tat het elevation/animator
            // de moi phim phang tuyet doi, dong bo 100% voi phim cach.
            stateListAnimator = null
            elevation = 0f
            outlineProvider = null
            layoutParams = LinearLayout.LayoutParams(
                // Chieu cao phim GIO CO GIAN theo man hinh thuc te (xem
                // [keyHeightDp]) thay vi so co dinh 48dp nhu truoc - man hinh
                // doc binh thuong van la 48dp nhu cu, chi giam khi man hinh
                // ngang/thap de tranh bi khuat hang tren cung.
                //
                // RIENG khi [fillRowHeight] = true (hang duoi cung co phim
                // cach canh ben): dung MATCH_PARENT thay vi dp(keyHeightDp)
                // rieng le - xem giai thich chi tiet tai khai bao
                // [fillRowHeight] o tren va tai [buildSpaceKey].
                0,
                if (fillRowHeight) ViewGroup.LayoutParams.MATCH_PARENT else dp(keyHeightDp),
                weight
            ).apply {
                // Margin doi xung dp(1) o CA 4 canh, GIONG NHAU cho MOI phim
                // (khong con phan biet nudge/base nhu truoc) - don gian va
                // khong the lech, xem giai thich tai [buildSpaceKey].
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            gravity = Gravity.CENTER
            isHapticFeedbackEnabled = true
        }

        // Voi phim co [onRepeat] (vd phim xoa): dung mot Runnable tu lap lai
        // qua deleteRepeatHandler. Bat dau dem gio tu luc ACTION_DOWN; neu
        // ngon tay con giu qua DELETE_REPEAT_INITIAL_DELAY_MS, Runnable bat
        // dau chay lien tuc moi DELETE_REPEAT_INTERVAL_MS (goi onRepeat moi
        // lan) cho toi khi ACTION_UP/ACTION_CANCEL huy no di. Neu ngon tay
        // tha ra TRUOC khi kich hoat lap lai (cham binh thuong), Runnable
        // chua kip chay lan nao thi da bi huy - luc do de framework tu goi
        // onClick() nhu mot phim thuong (xoa dung 1 ky tu).
        var repeatRunnable: Runnable? = null
        var repeatTriggered = false
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                        // Da xoa lien tuc trong luc giu: nuot su kien de
                        // KHONG kich hoat them onClick() (tranh xoa du 1 ky
                        // tu nua ngay khi vua tha tay).
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

    /** Tao (chi mot lan duy nhat, dung lai cho nhung lan sau) cap PopupWindow
     *  + TextView dung de "noi chu" xem truoc. TRUOC DAY moi lan nham mot
     *  phim la mot cap PopupWindow/TextView MOI duoc tao ra roi huy di ngay
     *  sau do - viec them/bot cua so qua WindowManager (System IPC) nhieu
     *  lan lien tuc nhu vay la mot nguyen nhan chinh khien ban phim bi "do"
     *  (nhap khong kip) khi go nhanh. Gio day chi tao MOT LAN, nhung lan sau
     *  chi doi noi dung chu (bubble.text) va vi tri (popup.update(...)) cua
     *  cung mot cua so co san, re hon rat nhieu so voi tao cua so moi. */
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
            // Tang padding TREN len dp(16) (tu dp(8)) de cac chu Tieng Viet
            // co 2 dau chong nhau (vd "Ầ", "Ổ", "Ẫ" - dau thanh dat TREN
            // dau mu/moc) khong bi cat mat phan dinh nhat - Android tinh
            // chieu cao WRAP_CONTENT cua TextView dua tren font metrics
            // thong thuong, khong luon tinh du cho ky tu co dau cao bat thuong.
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

    /** Hien mot bong nho, chu to, ngay phia tren phim dang nham, giong hieu
     *  ung "noi chu" quen thuoc tren cac ban phim ao khac. */
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
                // Cua so co san dang hien: chi di chuyen no toi vi tri moi,
                // khong tao/them cua so moi vao WindowManager.
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

    /** Chen mot ky tu chu cai. Neu dang o che do Tieng Viet, chuyen qua bo
     *  xu ly Telex; nguoc lai chen truc tiep nhu Tieng Anh (co ap dung Shift). */
    private fun insertChar(ch: Char) {
        if (isVietnameseMode && ch.isLetter()) {
            insertVietnameseChar(ch)
            return
        }
        // Che do go thuong (khong Telex): moi ky tu la mot lan commit doc
        // lap, khong co bien doi nhieu buoc nao co the "danh mat" chu hoa da
        // ap, nen co the ap dung va TIEU THU (tat) co ngay tai day.
        val shouldCapitalize = capitalizeNextLetter && ch.isLetter()
        if (shouldCapitalize) {
            capitalizeNextLetter = false
            showCapitalPreview = false
        }
        val out = if (isShiftOn || shouldCapitalize) ch.uppercaseChar() else ch
        insertText(out.toString())
        // Vua "tieu thu" xong Shift "don" (viet hoa 1 chu) - ve lai ban phim
        // de cac phim tro ve hien thi chu THUONG, dong bo voi trang thai moi
        // (xem [buildCharRow], gio co hien preview chu hoa theo ca
        // [capitalizeNextLetter], khong chi [isShiftOn] nhu truoc).
        if (shouldCapitalize) redrawKeyboard()
    }

    /** Xu ly mot ky tu go theo kieu Telex: doi chieu voi phan "tu" da go tu
     *  truoc (currentWord) de biet co can xoa/thay the ky tu truoc do hay
     *  khong (vd go "a" roi "a" -> "â", go nguyen am roi "s" -> them dau sac).
     *
     *  TRUOC DAY: moi lan go 1 ky tu, ham nay LUON xoa TOAN BO tu dang go
     *  (deleteSurroundingText(oldLen,0)) roi CHEN LAI TOAN BO tu moi - vd go
     *  ky tu thu 8 cua mot tu 8 ky tu se xoa+chen lai ca 8 ky tu, du 7 ky tu
     *  dau khong doi gi ca. Voi tu dai, moi keystroke la 2 IPC round-trip
     *  ngay cang nang (ti le voi do dai tu), day chinh la NGUYEN NHAN CHINH
     *  khien ban phim "khong theo kip" (giat, mat/thieu chu) khi go nhanh -
     *  vi thoi gian xu ly 1 phim tang dan theo do dai tu dang go.
     *
     *  GIO DAY: tim DO DAI TIEN TO CHUNG giua tu cu va tu moi (vd go them 1
     *  ky tu binh thuong, khong kich hoat bien doi Telex nao ca, thi toan bo
     *  tu cu la tien to chung, CHI CAN commit dung 1 ky tu moi, KHONG can xoa
     *  gi ca). Chi khi mot phep bien doi Telex THUC SU thay doi mot ky tu o
     *  giua/gan cuoi tu (vd "tiep"+"e" -> "tiêp") thi moi can xoa+chen phan
     *  DUOI KHAC NHAU ke tu diem do - van dung, nhung nho hon nhieu so voi
     *  xoa+chen ca tu. */
    private fun insertVietnameseChar(ch: Char) {
        val ic = currentInputConnection ?: return
        val hadPendingSuggestion = pendingSuggestion != null
        clearAutocorrectSuggestion()
        val lower = ch.lowercaseChar()

        // LOI TRUOC DAY: go xong 1 tu (vd "hau") + dau cach se lam currentWord
        // bi XOA TRANG (xem [insertText] - dung de danh dau tu da "chot").
        // Neu sau do nguoi dung XOA dau cach do di (bang phim ⌫) de noi lai
        // vao cuoi tu ("hau"), currentWord VAN DANG TRONG (khong tu dong
        // "khoi phuc" lai thanh "hau"), nen go them ky tu Telex tiep theo
        // (vd "a" de "hau"+"a" -> "hâu") se bi hieu la BAT DAU MOT TU MOI, ra
        // ket qua sai (vd "haua" thay vi "hâu" dung mong doi).
        // KHAC PHUC: dong bo lai currentWord tu NOI DUNG THUC TE truoc con
        // tro (doc qua InputConnection) - lay chuoi CHU CAI lien tuc gan con
        // tro nhat (dung [Char.isLetter], bao gom ca chu Tieng Viet co dau)
        // lam currentWord moi, GIONG NHU tu do van dang duoc go tiep, thay vi
        // coi la tu moi hoan toan.
        //
        // LOI CUC NGHIEM TRONG nguoi dung phan anh: TRUOC DAY chi dong bo khi
        // currentWord dang TRONG. Nhung neu o nhap bi XOA HET boi mot nguon
        // KHAC voi chinh ban phim nay (vd nut "x" xoa nhanh co san ngay
        // trong o nhap cua nhieu app/trinh duyet, khac voi phim ⌫ cua ban
        // phim), thi ve ly thuyet onUpdateSelection() se duoc goi va xoa
        // currentWord - NHUNG thong bao do di qua IPC bat dong bo, co the
        // toi CHAM hon so voi luc nguoi dung da kip go ky tu dau tien cua tu
        // moi. Luc do currentWord VAN CON giu "tu cu" (vd "hậu" tu cau truoc
        // do da xoa), ma dieu kien "chi dong bo khi TRONG" khien no bi BO
        // QUA (vi currentWord dang "khong trong" - du da la du lieu CU, sai
        // lech voi o nhap THAT SU luc nay dang trong). Ket qua: ky tu tu moi
        // dang go bi ghep nham vao "tu ma" nay, gay loi kieu go "trịnh" (sau
        // khi da xoa het chu "trịnh công hậu") lai ra "ẩutịnh" (dau hoi ap
        // nham vao "hậu" con sot lai khi go chu "r" trong "tr").
        //
        // GIO DAY: LUON so sanh currentWord voi noi dung CHU CAI THUC TE
        // dang co ngay truoc con tro (khong con dieu kien "chi khi trong"
        // nua) - neu khac nhau (bao gom truong hop currentWord con "sot"
        // trong khi o nhap that su da trong, hoac nguoc lai), dong bo lai
        // currentWord cho DUNG voi thuc te, bat ke truoc do no co trong hay
        // khong. Vi thao tac nay chi la MOT lan doc (khong xoa/chen gi), chi
        // phi hau nhu khong dang ke so voi loi nghiem trong no ngan chan.
        resyncCurrentWordFromInputConnection(ic)

        val oldWordLower = currentWord.toString()
        val newWordLower = VietnameseTelex.processKey(oldWordLower, lower)
        currentWord = StringBuilder(newWordLower)

        var commonPrefixLen = 0
        val minLen = minOf(oldWordLower.length, newWordLower.length)
        while (commonPrefixLen < minLen && oldWordLower[commonPrefixLen] == newWordLower[commonPrefixLen]) {
            commonPrefixLen++
        }
        val deleteCount = oldWordLower.length - commonPrefixLen
        val newSuffixLower = newWordLower.substring(commonPrefixLen)

        // Neu [capitalizeNextLetter] dang bat VA lan go nay dong den ky tu
        // O VI TRI DAU TU (commonPrefixLen == 0, tuc dang viet lai tu vi tri
        // dau), viet hoa DUY NHAT ky tu dau tien cua phan hau to moi, giu
        // nguyen (theo isShiftOn) cho phan con lai. KHONG tat co ngay - vi
        // ky tu dau tu CO THE con bi mot phim Telex tiep theo bien doi THEM
        // (vd go "a" (cap) roi go "a" lan 2 -> "â", hoac "d" (cap) roi "d"
        // lan 2 -> "đ") ma van phai tiep tuc duoc viet hoa. Chi tat co khi
        // gap lan go KHONG dong den vi tri dau tu nua (commonPrefixLen > 0)
        // - dau hieu ky tu dau tu da "on dinh", khong con bi ghi de lai.
        val touchesWordStart = commonPrefixLen == 0 && newSuffixLower.isNotEmpty()
        val wasCapitalizingWordStart = capitalizeNextLetter && touchesWordStart
        val newSuffixDisplay = when {
            wasCapitalizingWordStart -> {
                val restLower = newSuffixLower.drop(1)
                val rest = if (isShiftOn) restLower.uppercase() else restLower
                newSuffixLower.first().uppercaseChar() + rest
            }
            isShiftOn -> newSuffixLower.uppercase()
            else -> newSuffixLower
        }
        // [capitalizeNextLetter] vua duoc TIEU THU (tat) o day - can ve lai
        // ban phim de cac phim CHU CAI tro ve hien thi CHU THUONG (dong bo
        // voi trang thai moi), neu khong phim se "ket dinh" hien chu HOA sai
        // cho toi khi co ly do khac lam ban phim ve lai (xem [buildCharRow]
        // - gio co kiem tra ca [capitalizeNextLetter] de hien preview chu hoa
        // ngay khi Shift "don" dang bat).
        val justConsumedSingleShift = capitalizeNextLetter && !touchesWordStart
        if (justConsumedSingleShift) {
            capitalizeNextLetter = false
        }
        // SUA THEM (theo yeu cau nguoi dung): ngay khi CHU CAI DAU TIEN sau
        // Shift "don" da duoc chen xong (wasCapitalizingWordStart == true),
        // TAT [showCapitalPreview] NGAY LAP TUC de ban phim quay ve hien chu
        // THUONG tu day - KHONG doi them 1 lan go nua. Truoc day ham nay chi
        // ve lai ban phim khi [justConsumedSingleShift] (tuc phai doi den khi
        // gap mot phim TIEP THEO khong dong den vi tri dau tu), nen nguoi
        // dung thay ban phim "cham tre 1 nhip": go xong chu hoa dau tien roi
        // ma cac phim VAN con hien chu hoa cho toi khi go THEM 1 chu nua moi
        // chiu doi. Luu y: [capitalizeNextLetter] (logic, khac voi
        // [showCapitalPreview] chi de hien thi) VAN duoc giu nguyen gia tri
        // true trong truong hop nay - vi phim Telex tiep theo van co the con
        // bien doi dung vi tri chu cai vua go (vd "A"+"a" -> "Â"), chi rieng
        // PHAN HIEN THI la duoc tat som hon it hon.
        if (wasCapitalizingWordStart) {
            showCapitalPreview = false
        }

        selfInitiatedChange = true
        // Goi xoa + chen trong CUNG mot batch edit: bao dam ung dung dich
        // (o nhap lieu ben duoi) coi day la MOT thao tac lien tuc duy nhat
        // thay vi 2 thao tac rieng le - vua tranh viec ung dung ve lai giao
        // dien 2 lan (do trung gian) gay giat/cham khi go nhanh, vua tranh
        // truong hop hiem gap 2 IPC rieng le bi xu ly khong dung thu tu.
        if (deleteCount > 0) {
            ic.beginBatchEdit()
            try {
                ic.deleteSurroundingText(deleteCount, 0)
                ic.commitText(newSuffixDisplay, 1)
            } finally {
                ic.endBatchEdit()
            }
        } else {
            // Truong hop pho bien nhat (go them ky tu moi, khong bien doi gi
            // ky tu cu): khong can xoa gi ca, chi 1 lenh commit duy nhat.
            ic.commitText(newSuffixDisplay, 1)
        }
        if (hadPendingSuggestion || justConsumedSingleShift || wasCapitalizingWordStart) redrawKeyboard()
    }

    /** Doc mot doan van ban truoc con tro (qua InputConnection) va, neu doan
     *  ngay truoc con tro la mot day CHU CAI lien tuc (khong bi ngat boi dau
     *  cach/dau cau/ky tu khac), dat day chu cai do (ve chu thuong) lam
     *  [currentWord] moi - de cac phep bien doi Telex tiep theo (aa->â, dau
     *  thanh s/f/r/x/j...) ap dung DUNG vao tu dang go, thay vi nham la dang
     *  bat dau mot tu hoan toan moi. Neu ky tu ngay truoc con tro KHONG phai
     *  chu cai (vd dang thuc su dung sau dau cach/dau cau, hoac dau dong van
     *  ban), [currentWord] duoc dat VE TRONG (khong con giu du lieu cu sai
     *  lech nua - xem giai thich chi tiet o noi goi ham nay trong
     *  [insertVietnameseChar] ve ly do PHAI dong bo ca chieu "ve trong" nay,
     *  khong chi chieu "khoi phuc tu"). */
    private fun resyncCurrentWordFromInputConnection(ic: android.view.inputmethod.InputConnection) {
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return
        var i = before.length
        while (i > 0 && before[i - 1].isLetter()) i--
        val recovered = before.substring(i).lowercase()
        if (recovered != currentWord.toString()) {
            currentWord = StringBuilder(recovered)
        }
    }

    /** Chen dau cach/dau cau/ky hieu - luon ket thuc "tu" hien tai. Neu vua
     *  go xong mot "tu" Tieng Viet (ket thuc bang dau cach, khong dang bat
     *  Shift), kiem tra xem tu do co the bi go sai chinh ta khong (xem
     *  [checkAutocorrectSuggestion]) truoc khi xoa bo dem [currentWord]. */
    private fun insertText(text: String) {
        val boundaryWord = currentWord.toString()
        selfInitiatedChange = true
        currentInputConnection?.commitText(text, 1)
        currentWord.clear()

        // DA BO: goi y sua loi Tieng Viet (checkAutocorrectSuggestion) sau
        // moi dau cach - viec do tu dien (VietnameseAutocorrect, doc/duyet
        // ~6600 tu trong vn_words.txt) lam ban phim bi KHUNG lai dung luc
        // vua go xong mot tu, gay mat chu/lag khi go nhanh lien tuc.
        if (pendingSuggestion != null) {
            clearAutocorrectSuggestion()
            redrawKeyboard()
        }
    }

    /** Xoa 1 ky tu truoc con tro - HOAC neu nguoi dung dang co mot vung van
     *  ban duoc BOI DEN (chon khoi) trong o nhap lieu, xoa NGUYEN CA khoi do
     *  bang mot lan bam duy nhat, thay vi im lang chi xoa 1 ky tu ngay truoc/
     *  sau vung chon (hanh vi cu, gay cam giac nut xoa "khong an gi" voi khoi
     *  van ban dai). Dung [InputConnection.getSelectedText] de kiem tra co
     *  vung chon khong: neu co (khac null va khong rong), goi commitText("")
     *  - theo dung tai lieu Android, commitText se THAY THE vung dang duoc
     *  chon bang chuoi truyen vao, nen truyen chuoi rong tuong duong voi xoa
     *  toan bo vung chon do. */
    private fun deleteChar() {
        val hadPendingSuggestion = pendingSuggestion != null
        clearAutocorrectSuggestion()
        selfInitiatedChange = true
        val ic = currentInputConnection
        val selectedText = ic?.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            ic.commitText("", 1)
            currentWord.clear()
        } else {
            ic?.deleteSurroundingText(1, 0)
            if (currentWord.isNotEmpty()) {
                currentWord.deleteCharAt(currentWord.length - 1)
            }
        }
        if (hadPendingSuggestion) redrawKeyboard()
    }

    /** LOI nguoi dung phan anh ve nut Enter (\u21b5): TRUOC DAY, moi khi o
     *  nhap co khai bao san mot "hanh dong ban phim" (IME action - vd Gui,
     *  Tim kiem, Tiep theo, Xong...), nut nay se GOI LUON hanh dong do (vd
     *  bam Gui tin nhan) thay vi xuong dong - dieu nay RAT PHIEN trong cac
     *  app nhan tin/chat: o nhap tin nhan o do THUONG co khai bao san hanh
     *  dong "Gui", nen bam Enter de xuong dong (viet tin nhan nhieu dong)
     *  lai vo tinh GUI LUON tin nhan dang go do.
     *
     *  GIO DAY: sua thanh kieu "Alt+Enter" - giong voi to hop phim Alt+Enter
     *  tren ban phim vat ly ma nhieu app chat dung de CHEN XUONG DONG THAT
     *  SU, KHONG kich hoat hanh dong Gui/Tim kiem/... cua o nhap:
     *   - Neu o nhap la loai NHIEU DONG (co co [InputType.TYPE_TEXT_FLAG_MULTI_LINE]
     *     hoac [InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE] - vd o soan tin
     *     nhan, o ghi chu, o binh luan dai...): LUON chen ky tu xuong dong
     *     that ("\n"), bo qua hanh dong ban phim du o do co khai bao gi.
     *   - Neu o nhap la loai MOT DONG (khong co hai co tren - vd o tim kiem,
     *     o dang nhap, o dien so dien thoai...): xuong dong THAT SU khong co
     *     y nghia o day (o nay khong the hien thi nhieu dong duoc), nen day
     *     la truong hop "KHONG THE Alt-Enter duoc" - quay ve dung hanh vi
     *     Enter THONG THUONG nhu truoc: goi hanh dong ban phim neu co khai
     *     bao (Gui/Tim kiem/Xong...), neu khong co gi khai bao thi moi chen
     *     xuong dong nhu mot phuong an du phong cuoi cung. */
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

    /** Duoc goi moi khi selection/con tro trong o nhap lieu thay doi. Neu
     *  thay doi nay KHONG phai do chinh ban phim gay ra (co [selfInitiatedChange]
     *  la false - vd nguoi dung vua cham vao giua doan van ban de doi vi tri
     *  go, dung phim mui ten, hoac ung dung tu thay doi noi dung), bo dem
     *  [currentWord] khong con dung voi vi tri con tro thuc te nua nen PHAI
     *  xoa - neu khong, lan go Tieng Viet tiep theo se lay nham "tu" cu lam
     *  ngu canh de bien doi Telex, gay hien tuong "chen nhac/lap lai chu vua
     *  go truoc do" (vd go "cong hau" ra "cong conghau"). */
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
            // Con tro vua doi vi tri KHONG phai do chinh ban phim gay ra (vd
            // nguoi dung cham sang cho khac) - co "viet hoa chu tiep theo"
            // (neu dang cho) khong con y nghia gi voi vi tri con tro moi,
            // huy di de tranh viet hoa nham mot cho khong lien quan.
            capitalizeNextLetter = false
            showCapitalPreview = false
        }
        selfInitiatedChange = false
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** Duoc goi khi nut [QR] duoc bam. Neu chua co quyen CAMERA, phai nho MOT
     *  Activity toi gian ([QrCameraPermissionActivity]) hien ho thoai xin
     *  quyen he thong (Service khong the tu hien hop thoai nay duoc) - sau
     *  khi nguoi dung tra loi, [notifyCameraPermissionResult] se goi lai va
     *  tu dong mo tiep khung quet neu duoc cap quyen. Neu da co quyen roi
     *  (truong hop thuong gap sau lan dau), mo thang khung quet nong ma
     *  khong can dung Activity nao ca.
     *
     *  [continuous]: xem giai thich o [buildNumbersBottomRow]. Neu khung
     *  quet DANG mo san (nguoi dung bam nut QR lan nua trong luc dang quet),
     *  chi cap nhat lai che do quet, KHONG mo lai camera tu dau. */
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

        if (qrOverlayView != null) return // camera van dang chay, chi doi che do o tren
        showQrOverlay()
    }

    /** Them View chua preview camera vao THANG cua so cua Service nay (khong
     *  qua Activity nao) bang [qrWindowManager]. Vi day van la cua so ban
     *  phim (chi la them mot lop con), he thong khong bao gio coi la mat
     *  focus - onFinishInputView() chi con bi goi dung luc nguoi dung THAT
     *  SU roi o nhap, khong con bi goi oan luc dang quet nua.
     *
     *  Chieu cao khung quet = dung chieu cao ban phim hien tai (giong logic
     *  cu), va dat gravity BOTTOM + y = chieu cao do de khung quet nam HAN
     *  o phia TREN ban phim (khong che ban phim, ban phim van hien ro va
     *  van nhan duoc cham vao cac phim con lai trong luc quet). */
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
            // Mot so ROM/dong may co the tu choi kieu cua so nay trong vai
            // tinh huong hiem - bao cho nguoi dung thay vi treo im lang.
            Toast.makeText(this, "Kh\u00f4ng m\u1edf \u0111\u01b0\u1ee3c khung qu\u00e9t", Toast.LENGTH_SHORT).show()
            return
        }
        qrOverlayView = view
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        startQrCamera()
    }

    /** Dong khung quet: ngat camera, go View khoi cua so, va dua
     *  [lifecycleRegistry] ve CREATED (khong DESTROYED, de con dung lai duoc
     *  cho lan quet ke tiep - xem giai thich o khai bao [lifecycleRegistry]). */
    private fun hideQrOverlay() {
        stopQrCamera()
        qrOverlayView?.let {
            try { qrWindowManager.removeView(it) } catch (e: Exception) { /* da bi go truoc do */ }
        }
        qrOverlayView = null
        qrPreviewView = null
        qrFlashButton = null
        qrFlashOn = false
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** Dung code de dung giao dien khung quet: lop preview camera chiem het
     *  khung, nut Huy o GOC PHAI DUOI, nut bat/tat FLASH o GOC PHAI TREN -
     *  y het bo cuc truoc day cua QrScanActivity, chi khac la gio day noi
     *  chua no la mot View thuong, khong con la mot Activity/Window rieng. */
    private fun buildQrOverlayContentView(): View {
        val root = FrameLayout(this)

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
            setOnClickListener { hideQrOverlay() }
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

        return root
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

    /** Khoi dong CameraX, bind vao [this] (Service nay tu implement
     *  LifecycleOwner - xem [lifecycle]) thay vi vao mot Activity nhu truoc. */
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

            try {
                cameraProvider.unbindAll()
                qrCamera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, imageAnalysis
                )
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
            // Bo qua neu camera provider chua kip khoi tao xong (vd nguoi
            // dung bam Huy rat nhanh ngay sau khi mo).
        }
        qrCameraExecutor?.shutdown()
        qrCameraExecutor = null
        qrCamera = null
    }

    /** Y het logic cu o QrScanActivity.processFrame(): chi nhan mot ma QR
     *  hop le (khong ky tu dac biet, khac ma vua xuat gan nhat) trong luc
     *  chua co ma nao dang cho xu ly ([qrFrameHandled]). */
    private fun processQrFrame(imageProxy: ImageProxy, scanner: BarcodeScanner) {
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

    /** Tap ky tu duoc coi la hop le (khong phai "dac biet") - y het truoc day. */
    private val qrAllowedCharacterRegex = Regex("^[\\p{L}\\p{N}\\s.,!?:;'\"()/@-]*$")

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

    /** Ma QR quet duoc: phat bip, chen thang vao o nhap dang mo (khong con
     *  can qua companion callback nua vi tat ca dien ra trong CUNG mot doi
     *  tuong Service), roi xuong dong san cho lan nhap/quet tiep theo. */
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
        }

        // Doi het thoi luong tieng bip roi moi dong khung (hoac mo lai cho
        // ma tiep theo neu dang o CHE DO QUET LIEN TUC) - tranh cat ngang am
        // thanh dang phat bat dong bo.
        mainHandler.postDelayed({
            if (qrContinuousMode) {
                qrFrameHandled.set(false)
            } else {
                hideQrOverlay()
            }
        }, (beepDurationMs + 100).toLong())
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // QUAN TRONG: chi dong khung quet khi [finishingInput] = true, tuc la
        // phien nhap THAT SU ket thuc (nguoi dung chuyen sang app khac, dong
        // han ban phim...). Mot so trang/app (dac biet WebView, hoac field co
        // validate/refresh lien tuc) khien he thong goi onFinishInputView()
        // ROI onStartInputView() lai NGAY SAU DO voi finishingInput = false -
        // day chi la tai tao lai view tam thoi, nguoi dung VAN DANG o nguyen
        // trong o nhap do, khong he roi di. TRUOC DAY ham nay dong khung quet
        // VO DIEU KIEN, nen tren cac trang loai nay, khung quet (ke ca dang o
        // CHE DO QUET LIEN TUC/dup-tap) bi tu dong tat ngay sau khi quet duoc
        // 1 ma, dung y het hien tuong nguoi dung phan anh. Gio chi dong that
        // su khi finishingInput = true.
        //
        // NHUNG: ngay ca khi finishingInput = true, VAN CHUA dong ngay - vi
        // mot so thong bao/popup thoang qua (thong bao he thong, popup xin
        // quyen trinh duyet, hop thoai khac cuop focus tam thoi...) cung
        // khien he thong bao finishingInput = true trong tich tac roi goi
        // lai onStartInputView ngay sau do, du nguoi dung khong thuc su roi
        // o nhap. Dat mot lenh dong "hoan" sau FINISH_INPUT_HIDE_DEBOUNCE_MS
        // - neu onStartInputView duoc goi lai truoc do (xem
        // [cancelPendingFinishHide]), lenh nay se bi huy, khung quet + ban
        // phim duoc giu nguyen tren cung nhu nguoi dung mong doi.
        if (finishingInput) {
            cancelPendingFinishHide()
            val hideRunnable = Runnable {
                pendingFinishHide = null
                hideQrOverlay()
            }
            pendingFinishHide = hideRunnable
            finishInputHideHandler.postDelayed(hideRunnable, FINISH_INPUT_HIDE_DEBOUNCE_MS)
        }
        hideKeyPreview()
        // Huy moi vong lap xoa-lien-tuc dang cho (phong truong hop nguoi
        // dung roi o nhap trong luc van con dang giu phim xoa).
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
    }
}

/**
 * Goi y sua loi chinh ta Tieng Viet don gian, dua tren mot tu dien co san
 * (file assets/vn_words.txt, ~6600 tu Tieng Viet thong dung, moi tu 1 dong).
 * Cach hoat dong: khi mot "tu" nguoi dung go xong KHONG co trong tu dien,
 * tim trong tu dien mot tu SAI LECH DUNG 1 KY TU (them/xoa/doi 1 ky tu) so
 * voi tu do; neu tim thay, coi la goi y sua loi hop ly. Day la mot ky thuat
 * don gian (khong phai mo hinh ngon ngu day du), nen chi bat duoc cac loi
 * go sai co ban (thieu/du/sai 1 ky tu) - khong bat duoc loi sai dau thanh
 * lam tu do van "co ve giong tu khac" trong tu dien, hay loi ngu phap/ngu
 * canh. Tu dien duoc TAI MOT LAN DUY NHAT (lazy, dung ca cho toan bo doi
 * song cua IME) va nhom theo do dai tu de tim kiem nhanh hon.
 */
private object VietnameseAutocorrect {

    private const val DICTIONARY_ASSET_PATH = "vn_words.txt"

    @Volatile
    private var dictionaryByLength: Map<Int, List<String>>? = null

    @Volatile
    private var dictionarySet: Set<String>? = null

    /** Tai tu dien tu file assets/vn_words.txt (chi 1 lan). Neu file khong
     *  ton tai (vd nguoi dung chua them file nay vao du an) hoac loi doc,
     *  tra ve tap rong - luc do tinh nang goi y coi nhu tam tat, KHONG lam
     *  crash ung dung. */
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

    /** Tra ve mot goi y thay the cho [word] (chu thuong) neu [word] KHONG co
     *  trong tu dien nhung co dung mot tu trong tu dien sai lech 1 ky tu -
     *  nguoc lai (tu dung roi, hoac khong tim thay goi y phu hop) tra null. */
    fun suggestFor(context: android.content.Context, word: String): String? {
        ensureLoaded(context)
        val set = dictionarySet ?: return null
        if (word.isEmpty() || word in set) return null

        val byLength = dictionaryByLength ?: return null
        val pool = (byLength[word.length].orEmpty()) +
            (byLength[word.length - 1].orEmpty()) +
            (byLength[word.length + 1].orEmpty())

        // Uu tien tu co ky tu DAU giong nhau, giam so luong can so sanh va
        // tang do lien quan cua goi y (tranh goi y "la" cho tu "ba" chang han).
        val firstChar = word[0]
        return pool.firstOrNull { candidate ->
            candidate.isNotEmpty() && candidate[0] == firstChar &&
                isEditDistanceAtMostOne(word, candidate)
        }
    }

    /** True neu chi can DUNG MOT phep them/xoa/doi 1 ky tu de bien [a]
     *  thanh [b] (khoang cach Levenshtein <= 1). Viet rieng (khong dung DP
     *  day du) vi chi can biet <=1 hay khong, nhanh hon nhieu so voi tinh
     *  toan bo ma tran Levenshtein cho tu dien lon. */
    private fun isEditDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        val lenA = a.length
        val lenB = b.length
        if (kotlin.math.abs(lenA - lenB) > 1) return false

        if (lenA == lenB) {
            // Cung do dai: chi cho phep DOI dung 1 ky tu.
            var diffCount = 0
            for (i in a.indices) {
                if (a[i] != b[i]) {
                    diffCount++
                    if (diffCount > 1) return false
                }
            }
            return diffCount == 1
        }

        // Do dai lech nhau dung 1: kiem tra xem co the XOA dung 1 ky tu cua
        // chuoi dai hon de duoc chuoi ngan hon khong.
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
 * thuong (khong dau) thanh chuoi co dau Tieng Viet, dua tren "tu" dang go
 * (tu luc bat dau tu den ky tu hien tai). Cac quy tac Telex duoc ho tro:
 *   - aa -> â, aw -> ă, ee -> ê, oo -> ô, ow -> ơ, uw -> ư, dd -> đ
 *   - s/f/r/x/j -> dau sac/huyen/hoi/nga/nang; z -> bo dau
 * Day la mot bo xu ly rut gon (khong xu ly het moi truong hop dac biet cua
 * chinh ta Tieng Viet), nhung dap ung tot phan lon cac tu thong dung.
 */
private object VietnameseTelex {

    // Moi nhom: ky tu goc (khong dau) + 6 bien the theo dau:
    // [khong dau, sac, huyen, hoi, nga, nang]
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

    // Cac nhom "co mu/moc" (â, ă, ê, ô, ơ, ư) duoc uu tien khi dat dau thanh
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

    /** Xu ly cac cap chuyen doi: aa->â, aw->ă, ee->ê, oo->ô, ow->ơ, uw->ư,
     *  dd->đ. TRUOC DAY chi ap dung khi ky tu ngay LIEN TRUOC do khop dung
     *  quy tac (vd phai go "tiee" thi "e" thu 2 moi bien "e" thanh "ê"), nen
     *  neu nguoi dung lo go het phu am cuoi tu roi ("tiep") moi quay lai them
     *  "e" thi se KHONG nhan (chi chen them chu "e" moi, sai chinh ta).
     *  GIO DAY: tim NGUYEN AM GOC (chua bien doi) GAN CUOI NHAT trong CA TU
     *  (khong bat buoc phai dung sat cuoi), roi bien doi ngay tai vi tri do,
     *  giu nguyen moi ky tu dung sau no (vd phu am cuoi). Nho vay go "tiep"
     *  roi go them "e" van ra "tiêp" dung nhu go "tiee" tu dau.
     *
     *  SUA THEM (2 loi nguoi dung phan anh):
     *  1) "hạu" (danh sai) + dat con tro truoc "u" + go them "a" TRUOC DAY ra
     *     "hạau" (sai) vi ham chi tim ky tu 'a' THUAN (khong dau) - trong khi
     *     ky tu thuc te ngay truoc con tro la 'ạ' (a MANG SAN dau nang), nen
     *     bi coi la "khong khop", chi chen them chu "a" moi. GIO DAY: tim
     *     theo NHOM nguyen am goc (vd nhom "a" gom ca a/á/à/ả/ã/ạ) bang
     *     [lastIndexOfGroup], roi GIU NGUYEN dau thanh da co khi doi nhom (vd
     *     'ạ' -> 'ậ', khong phai 'ạ' -> 'ạa') - ra dung "hậu" nhu mong doi.
     *  2) "được" bi go ra "đuợc": phim "w" TRUOC DAY chi doi MOT nguyen am
     *     gan cuoi nhat trong so {a,o,u} (uu tien "o" vi nam sau "u" trong tu
     *     "duo"), nen chi "o" -> "ơ", con "u" bi bo qua, giu nguyen. GIO DAY:
     *     kiem tra rieng cum "uo" LIEN TIEP (dung quy uoc Telex chuan cho cum
     *     nguyen am doi "ươ") - neu co, doi CA HAI ky tu cung luc (u -> ư VA
     *     o -> ơ), moi ra dung "được". Chi khi KHONG co cum "uo" moi quay ve
     *     logic cu (chon 1 trong 3 nguyen am gan cuoi nhat). */
    private fun applyDoubleModifier(word: String, key: Char): String? {
        if (word.isEmpty()) return null

        // Vi tri GAN CUOI NHAT trong [word] co ky tu thuoc NHOM nguyen am goc
        // [groupIdx] - BAT KY dang mang dau thanh nao (vd nhom 0 "a" khop ca
        // voi 'a','á','à','ả','ã','ạ'), khong chi ky tu thuan khong dau.
        fun lastIndexOfGroup(groupIdx: Int): Int? =
            word.indices.lastOrNull { i -> charToGroupTone[word[i]]?.first == groupIdx }

        // SUA THEM (theo yeu cau nguoi dung): ho tro "HUY bien doi" khi phim
        // modifier duoc go LAP LAI mot lan nua, giong quy uoc Unikey chuan
        // ("aaa" -> "aa" thay vi giu "â" roi chen them "a" vo nghia). Doi/HUY
        // giua NHOM GOC [fromGroupIdx] (vd "a") va NHOM DICH [toGroupIdx]
        // (vd "â") theo phim [key]:
        //  - Neu nguyen am GAN CUOI NHAT trong ca hai nhom nay dang o NHOM
        //    GOC (chua bien doi) -> bien doi BINH THUONG sang NHOM DICH, giu
        //    nguyen dau thanh da co (hanh vi cu, khong doi).
        //  - Neu nguyen am do dang o NHOM DICH (DA duoc bien doi tu truoc,
        //    vd "â" tu "aa") VA CHUA mang dau thanh nao (tone == "khong dau")
        //    -> coi day la lan go phim modifier THU BA lien tiep: HUY bien
        //    doi (dua ve lai NHOM GOC), roi CHEN THEM ky tu [key] vao CUOI
        //    CUNG cua tu (nhu mot chu cai binh thuong duoc go tiep theo, y
        //    het cach "aaa" duoc hieu la "aa"+"a" = 2 chu "a"). Vi du:
        //    "chêt" (đã có ê, chưa dấu) + "e" -> huy "ê"->"e" ("chet") roi
        //    chen "e" o cuoi -> "chete".
        //  - Neu nguyen am o NHOM DICH nhung DA CO dau thanh roi (vd "ế" -
        //    ê + sac) -> KHONG huy (dau thanh la thong tin quan trong khong
        //    the tu y bo), tra ve null de ky tu moi duoc chen BINH THUONG o
        //    cuoi tu, giu nguyen dau thanh cu. Vi du: "chết" + "e" ->
        //    "chếte" (khong phai "cheét" hay "chette").
        fun toggleGroup(fromGroupIdx: Int, toGroupIdx: Int): String? {
            val fromIdx = lastIndexOfGroup(fromGroupIdx)
            val toIdx = lastIndexOfGroup(toGroupIdx)
            val toIsNearer = toIdx != null && (fromIdx == null || toIdx > fromIdx)
            if (toIsNearer) {
                val toneIdx = charToGroupTone[word[toIdx!!]]!!.second
                if (toneIdx != 0) return null // da co dau thanh -> khong huy
                val baseChar = vowelGroups[fromGroupIdx][0]
                return word.substring(0, toIdx) + baseChar + word.substring(toIdx + 1) + key
            }
            if (fromIdx != null) {
                val toneIdx = charToGroupTone[word[fromIdx]]!!.second
                val newChar = vowelGroups[toGroupIdx][toneIdx]
                return word.substring(0, fromIdx) + newChar + word.substring(fromIdx + 1)
            }
            return null
        }

        return when (key) {
            'a' -> toggleGroup(0, 2) // a-family <-> â
            'e' -> toggleGroup(3, 4) // e-family <-> ê
            'o' -> toggleGroup(6, 7) // o-family <-> ô
            'w' -> {
                // Cum "uo" LIEN TIEP gan cuoi nhat (ky tu nhom "u" [9] ngay
                // truoc mot ky tu nhom "o" [6]) - neu co, doi CA HAI thanh
                // "ươ" cung luc (quy uoc Telex chuan cho cum nguyen am doi).
                // (Truong hop nay chi xu ly chieu BIEN DOI XUOI, khong ap
                // dung "huy" cho ca cum - qua hiem gap trong thuc te go go.)
                val uoIdx = (0 until word.length - 1).lastOrNull { i ->
                    charToGroupTone[word[i]]?.first == 9 && charToGroupTone[word[i + 1]]?.first == 6
                }
                if (uoIdx != null) {
                    val toneU = charToGroupTone[word[uoIdx]]!!.second
                    val toneO = charToGroupTone[word[uoIdx + 1]]!!.second
                    val newU = vowelGroups[10][toneU]
                    val newO = vowelGroups[8][toneO]
                    word.substring(0, uoIdx) + newU + newO + word.substring(uoIdx + 2)
                } else {
                    // Khong co cum "uo": trong 3 cap nhom co the bien doi/huy
                    // boi "w" (a<->ă, o<->ơ, u<->ư), chon cap co vi tri GAN
                    // CUOI TU NHAT (xet ca hai nhom goc/dich cua moi cap), roi
                    // ap dung toggleGroup (bien doi hoac huy) cho DUY NHAT cap
                    // do.
                    val pairs = listOf(0 to 1, 6 to 8, 9 to 10)
                    val best = pairs.mapNotNull { (fromG, toG) ->
                        val pos = maxOf(lastIndexOfGroup(fromG) ?: -1, lastIndexOfGroup(toG) ?: -1)
                        if (pos < 0) null else Triple(pos, fromG, toG)
                    }.maxByOrNull { it.first }
                    if (best == null) null else toggleGroup(best.second, best.third)
                }
            }
            'd' -> {
                // "d" <-> "đ": HUY tuong tu cac nguyen am o tren, nhung đ
                // khong mang dau thanh nen khong can kiem tra tone - cu thay
                // "đ" gan cuoi nhat (neu no gan hon "d" thuong) tro lai "d"
                // roi chen them "d" moi o cuoi tu. Vi du: "đo" + "d" -> "do"
                // + "d" = "dod" (thay vi "ddo" hay giu nguyen "đo" roi chen
                // "d" vo nghia).
                val dIdx = word.lastIndexOf('d')
                val dashIdx = word.lastIndexOf('\u0111')
                val useDash = dashIdx >= 0 && (dIdx < 0 || dashIdx > dIdx)
                when {
                    useDash -> word.substring(0, dashIdx) + 'd' + word.substring(dashIdx + 1) + 'd'
                    dIdx >= 0 -> word.substring(0, dIdx) + '\u0111' + word.substring(dIdx + 1)
                    else -> null
                }
            }
            else -> null
        }
    }

    /** Xu ly cac phim dat dau thanh: s (sac), f (huyen), r (hoi), x (nga),
     *  j (nang), z (bo dau). TRUOC DAY chi tim cum nguyen am NEU no nam dung
     *  o CUOI TU - nen neu nguoi dung da go xong phu am cuoi ("tiep") roi
     *  moi go dau thanh ("s") thi se KHONG nhan (chi chen them chu "s" moi).
     *  GIO DAY: bo qua moi phu am cuoi (khong phai nguyen am) o cuoi tu
     *  truoc, tim ra cum nguyen am GAN CUOI NHAT trong tu (du no khong con
     *  nam o vi tri cuoi cung nua), uu tien nguyen am "co mu/moc" (â, ê, ô,
     *  ơ, ư, ă) neu co. Neu khong co nguyen am "co mu/moc" nao trong cum:
     *   - Neu SAU cum nguyen am van con phu am (tu dang "dong", vd "hoan"),
     *     dau dat o nguyen am CUOI cung trong cum (ngay truoc phu am), vi
     *     day la vi tri dung duy nhat trong tieng Viet cho truong hop nay
     *     (vd "hoan" + f -> "hoàn", dau o "a").
     *   - Neu cum nguyen am nam SAT CUOI TU (tu "mo", khong co phu am theo
     *     sau, vd "bao", "hoa", "khoe", "thuy"), dau dat o nguyen am DAU
     *     TIEN trong cum (kieu chinh ta "cu": "bảo", "hòa", "khỏe", "thúy")
     *     - TRUOC DAY luon dat o nguyen am CUOI cum bat ke con hay het tu,
     *     nen "bao" + r (hoi) sai ra "baỏ" (dau tren "o") thay vi dung phai
     *     la "bảo" (dau tren "a"). Day la sua chinh cho loi nay.
     *  Neu khong tim thay nguyen am nao trong ca tu, tra ve null de ky tu
     *  duoc chen nhu binh thuong (vd "s", "r", "x" o dau tu la phu am). */
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

        // Bo qua phu am cuoi (neu co) de tim ve nguyen am gan cuoi nhat.
        var end = word.length - 1
        while (end >= 0 && !charToGroupTone.containsKey(word[end])) end--
        if (end < 0) return null

        // Cum nguyen am nam SAT CUOI TU (khong co phu am nao theo sau) khi
        // vi tri cuoi cung cua cum ("end") trung voi ky tu cuoi cung cua tu.
        val isOpenSyllable = end == word.length - 1

        val clusterIndices = mutableListOf<Int>()
        var i = end
        while (i >= 0 && charToGroupTone.containsKey(word[i])) {
            clusterIndices.add(0, i)
            i--
        }

        val preferred = clusterIndices.lastOrNull { pos ->
            charToGroupTone[word[pos]]!!.first in modifiedGroupIndices
        }

        // SUA LOI nguoi dung phan anh: "hoa"+sac TRUOC DAY ra "hóa" (dau tren
        // "o", ky tu DAU TIEN cua cum "oa") - nhung chinh ta dung phai la
        // "hoá" (dau tren "a"). Tuong tu "hoai"+huyen TRUOC DAY ra "hòai"
        // (dau tren "o") thay vi dung phai la "hoài" (dau tren "a", ky tu O
        // GIUA cum "oai"). LY DO: trong cac cum "oa", "oe", "oai", "oay",
        // chu "o" dung dau chi la BAN AM DEM (glide, phat am nhu /w/), KHONG
        // phai nguyen am chinh cua van - nguyen am chinh (noi dau thanh phai
        // dat vao) la chu ngay SAU no ("a" hoac "e"). Day la quy tac dung
        // rieng cho cum bat dau bang "o" (nhom 6) - KHONG ap dung cho cac cum
        // khac nhu "ao", "eo" (o dung SAU, dong vai tro nguyen am chinh o
        // VI TRI DAU, xem "bảo" o comment cua ham nay), nen khong lam anh
        // huong toi cac truong hop dang dung khac.
        val oGroupIdx = 6
        val startsWithOGlide = clusterIndices.isNotEmpty() &&
            charToGroupTone[word[clusterIndices.first()]]?.first == oGroupIdx
        val oGlideNucleus: Int? = when {
            !startsWithOGlide -> null
            clusterIndices.size == 3 -> clusterIndices[1] // "oai", "oay" -> dau o ky tu GIUA
            clusterIndices.size == 2 -> clusterIndices[1] // "oa", "oe" -> dau o ky tu THU HAI
            else -> null
        }

        val target = when {
            preferred != null -> preferred
            isOpenSyllable && oGlideNucleus != null -> oGlideNucleus
            isOpenSyllable && clusterIndices.size >= 2 -> clusterIndices.first()
            else -> clusterIndices.last()
        }

        val (groupIdx, currentToneIdx) = charToGroupTone[word[target]]!!

        // SUA THEM (theo yeu cau nguoi dung): neu nguyen am o vi tri [target]
        // DA MANG dung dau thanh vua duoc go lan nua (vd "chết" - ê+sac - roi
        // go tiep phim "s" mot lan nua), coi day la HUY dau thanh (quy uoc
        // giong Unikey: go lap lai cung mot phim dau thanh se "thoat" dau
        // thanh do) - dua nguyen am ve lai KHONG DAU (giu nguyen nhom, vd "ê"
        // van la "ê", chi bo dau "sac"), roi CHEN THEM ky tu [key] vao CUOI
        // CUNG cua tu nhu mot chu cai binh thuong. Vi du: "chết" + "s" (sac)
        // -> huy dau sac tren "ế" ve "ê" ("chêt"), roi chen "s" o cuoi ->
        // "chêts". Neu dau thanh moi KHAC dau thanh hien co, giu hanh vi cu:
        // THAY dau thanh binh thuong (khong chen them ky tu nao).
        if (currentToneIdx == toneIdx) {
            val revertedChar = vowelGroups[groupIdx][0]
            return word.substring(0, target) + revertedChar + word.substring(target + 1) + key
        }

        val newChar = vowelGroups[groupIdx][toneIdx]
        return word.substring(0, target) + newChar + word.substring(target + 1)
    }
}
