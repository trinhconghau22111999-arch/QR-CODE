package com.example.qrkeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
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
            // SUA (phong ve them, gop vao nhom sua loi "tu dong dong ban
            // phim"): [onCameraPermissionResult] la callback TINH (companion
            // object) co the con giu tham chieu toi 1 instance Service DA BI
            // HUY neu he thong tai tao Service dung luc nguoi dung dang tra
            // loi hop thoai xin quyen Camera (hiem nhung khong phai khong
            // the xay ra). Boc try/catch de loi (neu co) khong lam crash
            // toan bo tien trinh.
            try {
                onCameraPermissionResult?.invoke(granted)
            } catch (e: Exception) {
                // Bo qua - Service co the da bi huy truoc khi callback chay.
            }
            onCameraPermissionResult = null
        }

        /** THEM (theo yeu cau nguoi dung, tinh nang nhap lieu bang giong noi):
         *  callback tinh, duoc [VoiceInputActivity] goi ngay sau khi he
         *  thong nhan dien giong noi xong (hoac nguoi dung huy/loi). Cung
         *  ly do can 1 Activity rieng nhu luong xin quyen Camera - Service
         *  khong the tu mo man hinh nhan dien giong noi cua he thong. [text]
         *  la null neu nguoi dung huy hoac khong nhan dien duoc gi. */
        private var onVoiceInputResult: ((text: String?) -> Unit)? = null

        fun notifyVoiceInputResult(text: String?) {
            try {
                onVoiceInputResult?.invoke(text)
            } catch (e: Exception) {
                // Bo qua - Service co the da bi huy truoc khi callback chay.
            }
            onVoiceInputResult = null
        }

        /** THEM (theo yeu cau nguoi dung "mic phải dùng mic riêng"): callback
         *  tinh, duoc [MicPermissionActivity] goi ngay sau khi nguoi dung tra
         *  loi hop thoai xin quyen RECORD_AUDIO. Cung mau [onCameraPermissionResult]. */
        private var onMicPermissionResult: ((granted: Boolean) -> Unit)? = null

        fun notifyMicPermissionResult(granted: Boolean) {
            try {
                onMicPermissionResult?.invoke(granted)
            } catch (e: Exception) {
                // Bo qua - Service co the da bi huy truoc khi callback chay.
            }
            onMicPermissionResult = null
        }

        /** Khoang cach toi thieu (dp) ngon tay phai di chuyen theo chieu
         *  ngang tren phim cach de tinh la mot cu VUOT (swipe) doi ngon ngu,
         *  thay vi mot cai CHAM (tap) chen dau cach binh thuong. */
        private const val SPACE_SWIPE_THRESHOLD_DP = 24

        /** SUA (theo yeu cau nguoi dung "tang do nhay cham phim, bam nhe
         *  cung an"): Android mac dinh HUY cu bam (click) neu ngon tay xe
         *  dich qua mot nguong rat NHO (~8dp, touchSlop cua he thong) giua
         *  luc dat xuong va nha ra - cac phim chu/⌫ TRUOC DAY tra ve false o
         *  ACTION_DOWN/MOVE, de mac cho Android tu xu ly, nen nhung cu cham
         *  NHE (ngon tay it giu chat, de rung/xe nhe hon binh thuong) rat de
         *  bi coi la "keo/vuot" thay vi "cham" va bi HUY cu bam, nguoi dung
         *  phai an that chac/that dung tam moi "an". SUA: [buildKey] tu quan
         *  ly toan bo cu cham (return true tu ACTION_DOWN), voi nguong xe
         *  dich RONG HON han (20dp thay vi ~8dp mac dinh), roi tu goi
         *  [View.performClick] khi nha tay trong pham vi nguong nay - giup
         *  nhung cu cham nhe/hoi xe van duoc tinh la mot lan bam hop le. */
        private const val KEY_TAP_MOVE_TOLERANCE_DP = 20

        /** Phim xoa (⌫): thoi gian nham giu truoc khi bat dau tu dong xoa
         *  LIEN TUC (ms), va khoang cach (ms) giua cac lan xoa lien tiep sau
         *  do. Nham giu qua [DELETE_REPEAT_INITIAL_DELAY_MS] se kich hoat
         *  xoa lap lai moi [DELETE_REPEAT_INTERVAL_MS] cho den khi tha tay,
         *  thay vi truoc day moi lan bam chi xoa dung 1 ky tu. */
        private const val DELETE_REPEAT_INITIAL_DELAY_MS = 400L
        private const val DELETE_REPEAT_INTERVAL_MS = 50L

        /** Khoang thoi gian (ms) toi da giua 2 lan cham nut Shift (⇧) de tinh
         *  la mot cu DUP-TAP (cham 2 lan lien tiep, trong khoang thoi gian
         *  nay). Xem [buildKeyboardView] (buildLettersPage), phan xu ly nut
         *  Shift trong trang chu cai. */
        private const val SHIFT_DOUBLE_TAP_MAX_INTERVAL_MS = 350L

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
    }

    /** Thoi diem (uptimeMillis) cua lan cham nut Shift (⇧) gan nhat, dung de
     *  phat hien cu dup-tap (xem [SHIFT_DOUBLE_TAP_MAX_INTERVAL_MS]). */
    private var lastShiftTapTime = 0L

    /** Handler dung rieng cho vong lap xoa lien tuc khi giu phim ⌫. */
    private val deleteRepeatHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** SUA LOI "giu xoa het sach roi buong tay van tu dong xoa tiep, go chu
     *  khong vao duoc": [deleteChar] co the tu goi [redrawKeyboard] ngay
     *  GIUA luc dang giu phim ⌫ (vi du khi o nhap vua tro thanh RONG, xem
     *  co [shouldRearmCapitalize] trong [deleteChar]) - luc do
     *  [redrawKeyboard] THAO/XAY LAI toan bo cac phim, khien chinh nut ⌫
     *  dang duoc giu bi GO KHOI cay view NGAY TRONG LUC ngon tay van con
     *  cham man hinh. Nut ⌫ CU (da bi go) se KHONG BAO GIO nhan duoc
     *  ACTION_UP/ACTION_CANCEL nua (ngon tay tha ra roi thi su kien do roi
     *  vao nut MOI thay the no, chu khong phai nut cu), nen runnable lap lai
     *  xoa (da hen gio qua [deleteRepeatHandler]) KHONG bao gio bi huy -
     *  no cu chay mai moi 50ms, xoa moi ky tu nguoi dung vua go them. Bien
     *  nay luu THAM CHIEU runnable lap lai dang hoat dong (neu co) o cap
     *  Service, de [redrawKeyboard] co the CHU DONG huy no truoc khi thao
     *  view, bat ke view nao dang giu phim. */
    private var activeDeleteRepeatRunnable: Runnable? = null

    /** SUA (theo yeu cau nguoi dung "banl phim tốc độ chạm bị khựng nhiều"):
     *  moi lan cham 1 phim ky tu don, [showKeyPreview] mo/cap nhat mot
     *  PopupWindow qua WindowManager - day la 1 lenh IPC that su (giao tiep
     *  voi tien trinh he thong), ton vai mili-giay MOI LAN, chay DONG BO
     *  tren UI thread. Khi go BINH THUONG (khong qua nhanh) thi khong dang
     *  ke, nhung khi go NHANH LIEN TUC (nhieu phim/giay), CONG DON hang
     *  chuc lenh IPC nhu vay MOI GIAY chinh la nguyen nhan pho bien gay
     *  cam giac "khung/tre" luc go nhanh. SUA: BO QUA rieng buoc hien popup
     *  xem-truoc (preview bubble) - von CHI la hieu ung tham my, KHONG anh
     *  huong toi viec ky tu co duoc chen hay khong - neu khoang cach voi
     *  lan cham phim TRUOC do duoi [FAST_TYPING_THRESHOLD_MS], coi la dang
     *  go nhanh. Van chen chu/rung/am thanh binh thuong, chi tat rieng
     *  popup xem-truoc trong luc go nhanh. */
    private var lastKeyDownTimestamp = 0L
    private val FAST_TYPING_THRESHOLD_MS = 280L  // Tăng từ 180 lên 280ms: preview ít IPC hơn khi gõ vừa

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

    /** THEM (theo yeu cau nguoi dung, thay the [isVietnameseMode] cu):
     *  2 ngon ngu DANG DUOC CHON de vuot ngang tren phim cach chuyen doi qua
     *  lai (xem [buildSpaceKey]) - doc tu [LanguagePrefs] (dong bo o
     *  [onCreate]/[onWindowShown], giong het co che mau vien/sang-toi). MAC
     *  DINH van la ("vi","en") nhu truoc gio neu nguoi dung chua tung doi
     *  trong man Cai dat. */
    private var lang1 = LanguagePrefs.DEFAULT_LANG_1
    // THEM (theo yeu cau nguoi dung "phải cho phép chỉ chọn 1 ngôn ngữ"):
    // null = nguoi dung CHi dung 1 ngon ngu duy nhat ([lang1]) - khong co
    // ngon ngu thu 2 de vuot phim cach doi qua lai.
    private var lang2: String? = LanguagePrefs.DEFAULT_LANG_2

    /** True = dang dung [lang1], false = dang dung [lang2]. GIU NGUYEN gia
     *  tri mac dinh CU (false) de KHONG doi hanh vi nguoi dung dang quen -
     *  truoc day "isVietnameseMode = false" nghia la mac dinh mo len dang o
     *  che do go thuong/Anh, tuong duong voi active = lang2 ("en") o day. */
    private var activeIsLang1 = false

    // SUA: neu [lang2] la null (che do 1 ngon ngu), LUON dung [lang1] bat ke
    // [activeIsLang1] dang la gi (khong the "active" vao 1 ngon ngu khong
    // ton tai).
    private val activeLangCode: String get() = if (activeIsLang1 || lang2 == null) lang1 else lang2!!

    /** Bat/tat go Tieng Viet kieu Telex - CHi true khi ngon ngu DANG DUOC
     *  CHON THAT SU la "vi" (Tieng Viet), bat ke no la ngon ngu 1 hay 2. */
    private val isVietnameseMode: Boolean get() = activeLangCode == "vi"

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

    /** THEM (theo yeu cau nguoi dung, tinh nang goi y emoji): bo dem RIENG,
     *  DOC LAP voi [currentWord] o tren - chua ky tu (thuong, GIU dau neu
     *  la Tieng Viet) cua "tu" dang go, dung DUY NHAT de doi chieu voi
     *  [EMOJI_TRIGGERS] va quyet dinh co hien goi y emoji hay khong. Tach
     *  RIENG (khong dung chung [currentWord]) de KHONG dung cham/anh huong
     *  toi co che Telex von da rat tinh vi (currentWord bi [insertText] tu
     *  dong xoa sau MOI lan chen van ban, khong phu hop de theo doi tu qua
     *  nhieu ky tu lien tiep o che do go THUONG/khong phai Tieng Viet). */
    private var emojiTrackWord = StringBuilder()

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

    // ─────────── THEM: popup chon ky tu co dau khi NHAN GIU 1 phim chu cai ───────────
    // (theo yeu cau nguoi dung - CHi ap dung cho 6 ngon ngu can dau phu: fr/es/de/pt/it/tr,
    // KHONG ap dung cho "vi"/"en" - xem [LanguagePrefs.ACCENT_VARIANTS]).

    private var accentPopup: PopupWindow? = null
    private var accentPopupRow: LinearLayout? = null
    private var accentPopupOptions: List<Char> = emptyList()
    private var accentPopupSelectedIndex: Int = 0
    private var accentPopupShowing: Boolean = false
    private var accentLongPressRunnable: Runnable? = null
    private val accentLongPressHandler = Handler(Looper.getMainLooper())
    private val ACCENT_LONG_PRESS_MS = 350L

    /** Goi y sua loi Tieng Viet (xem [VietnameseAutocorrect]). */
    private var pendingSuggestion: String? = null
    private var pendingSuggestionOriginalWord: String? = null

    /** THEM (theo yeu cau nguoi dung, tinh nang goi y emoji): emoji dang duoc
     *  goi y (vd go "hihi" -> goi y "\ud83d\ude02") va tu GOC da go ra no
     *  (dung de XOA DUNG SO KY TU khi nguoi dung chon emoji - xem
     *  [acceptEmojiSuggestion]). null nghia la KHONG co goi y nao dang hien. */
    private var pendingEmojiSuggestion: String? = null
    private var pendingEmojiOriginalWord: String? = null

    /** THEM (theo yeu cau nguoi dung "nút đề xuất emoji chỉ hiện tối đa 3s,
     *  không chọn là nó ẩn đi"): hen gio TU DONG AN goi y emoji sau
     *  [EMOJI_SUGGESTION_AUTO_HIDE_MS] neu nguoi dung KHONG bam chon (hoac
     *  bam "✕") trong khoang thoi gian do - tranh goi y "dinh" mai tren ban
     *  phim, chiem mat hang tren cung neu nguoi dung khong de y toi no. Chi
     *  MOT hen gio hoat dong tai 1 thoi diem (huy cai CU truoc khi dat cai
     *  MOI moi lan mot goi y MOI xuat hien - xem [checkEmojiSuggestion]).*/
    private var emojiSuggestionHideRunnable: Runnable? = null
    private val emojiSuggestionHideHandler = Handler(Looper.getMainLooper())
    private val EMOJI_SUGGESTION_AUTO_HIDE_MS = 2000L

    /** Cache tham chiếu đến hàng gợi ý (LinearLayout trên cùng trang Chữ cái) - dùng để
     *  cập nhật nội dung gợi ý TRỰC TIẾP (addView/removeView con bên trong) thay vì gọi
     *  redrawKeyboard() rebuild toàn bộ trang phím mỗi khi gợi ý thay đổi. Được gán trong
     *  buildSuggestionSlot() và đặt null khi trang bị tháo (onDestroy/redrawKeyboard). */
    private var cachedSuggestionRow: android.widget.LinearLayout? = null

    /** Cache nút Shift để updateShiftStateInPlace() highlight trực tiếp, không redrawKeyboard. */
    private var cachedShiftKey: Button? = null

    /** Cache Map ký tự → Button trang LETTERS để đổi label hoa/thường trực tiếp, không redraw. */
    private val cachedLetterKeys = mutableMapOf<Char, Button>()

    /** Huy hen gio tu-an goi y emoji dang cho (neu co) - goi truoc BAT KY
     *  thoi diem nao goi y emoji bi thay doi/xoa boi ly do KHAC (chon, bam
     *  ✕, tu bien mat vi go tiep chu khac...), tranh hen gio CU vo tinh chay
     *  va an nham mot goi y MOI xuat hien SAU do (trung hop hiem nhung van
     *  co the xay ra neu khong huy). */
    private fun cancelEmojiSuggestionAutoHide() {
        emojiSuggestionHideRunnable?.let { emojiSuggestionHideHandler.removeCallbacks(it) }
        emojiSuggestionHideRunnable = null
    }

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

    private var qrOverlayRootLayout: FrameLayout? = null
    private var qrOverlaySessionKey: String? = null

    private fun editorSessionKey(info: EditorInfo?): String {
        if (info == null) return "null"
        return "${info.packageName}:${info.fieldId}:${info.inputType}"
    }

    private val qrFrameHandled = AtomicBoolean(false)
    private var qrLastDeliveredText: String? = null
    /** THEM: dem so lan LIEN TIEP da xuat ra CUNG 1 du lieu quet duoc (tang
     *  dan khi quet trung [qrLastDeliveredText], reset ve 0 khi quet ra du
     *  lieu KHAC). Dung de so sanh voi [ScanLimitPrefs.getConsecutiveLimit]
     *  o [processQrFrame] - xem giai thich chi tiet o do. */
    private var qrConsecutiveSameCount = 0
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
     *  dung co the doi sang 1 trong cac mau co san qua man Cai dat rieng
     *  (xem SettingsActivity.kt + KeyboardThemePrefs.kt). Gia tri KHOI TAO
     *  se duoc GHI DE lai trong [onCreate]/[onWindowShown] bang mau da luu
     *  tu lan truoc (neu co). */
    private var glowColor: Int = KeyboardThemePrefs.DEFAULT_ACCENT_COLOR

    /** True = NEN toi (den), chu TRANG (mac dinh, giu nguyen giao dien cu).
     *  False = NEN sang (trang), chu DEN. Doi trong man Cai dat rieng (xem
     *  SettingsActivity.kt + KeyboardThemePrefs.kt). */
    private var isDarkTheme: Boolean = true

    // ───────────────── THEM: hieu ung "den RGB chay" tren vien phim ─────────────────
    // (theo yeu cau nguoi dung - giong bàn phim co gaming that). Mac dinh TAT,
    // doc/dong bo tu [RgbEffectPrefs] giong het co che glowColor/isDarkTheme o tren.

    private var rgbChaseEnabled: Boolean = false
    private var rgbChaseDirection: String = RgbEffectPrefs.DEFAULT_DIRECTION
    // THEM (theo yeu cau nguoi dung: "có chạy led nhiều màu nhưng lại không
    // có chạy 1 màu"): xem giai thich chi tiet trong RgbEffectPrefs.kt.
    private var rgbChaseColorMode: String = RgbEffectPrefs.DEFAULT_COLOR_MODE

    // ───────────────── THEM: 2 cong tac goi y (loai tru lan nhau) ─────────────────
    // (theo yeu cau nguoi dung - xem giai thich chi tiet o SuggestionPrefs.kt).
    // Dong bo tu SuggestionPrefs giong het co che rgbChaseEnabled o tren.

    private var autocorrectEnabled: Boolean = false
    private var emojiSuggestionEnabled: Boolean = true

    // ───────────────── THEM: Mic rieng (SpeechRecognizer) - theo yeu cau ─────────────────
    // nguoi dung "mic phải dùng mic riêng, giờ đang dùng cái của gg không tốt".
    // TRUOC DAY uy quyen HOAN TOAN cho app tro ly ao mac dinh cua may (thuong
    // la Google) qua VoiceInputActivity + Intent ACTION_RECOGNIZE_SPEECH - mo
    // 1 man hinh popup RIENG cua app do, chat luong/do on dinh phu thuoc HOAN
    // TOAN vao app do (co the la app tro ly OEM kem chat luong tren mot so
    // may, khong phai Google that). GIO DAY: tu dung [SpeechRecognizer] TRUC
    // TIEP trong CHINH Service nay - van goi toi dich vu nhan dien giong noi
    // MAC DINH cua he thong (Settings > Ngon ngu & nhap > Nhan dien giong
    // noi), nhung KHONG con phu thuoc vao app TRO LY AO mac dinh nua (2 cai
    // nay la 2 lua chon KHAC NHAU trong Android) - va KHONG con mo popup
    // rieng cua app khac, tat ca dien ra ngay ben trong app nay.

    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var isListeningForVoice: Boolean = false
    private var micButtonRef: Button? = null

    private data class ChaseEntry(val drawable: GradientDrawable, val px: Float, val py: Float)

    /** THEM (theo yeu cau nguoi dung: "sửa icon mic" - "thiết kế đơn giản
     *  đen trắng thôi", CHi sua PHAN ICON, KHONG dung vao vien/nen/kich
     *  thuoc nut): thay emoji Mic mau me (🎤) bang 1 icon TU VE, CHi 1 MAU
     *  DUY NHAT (dung mau chu hien tai, DEN hoac TRANG tuy theme), don gian
     *  giong cac app/keyboard chuyen nghiep khac. Ve theo 2 kieu: mic (dang
     *  KHONG nghe) hoac o vuong bo tron (dang DUNG - dang nghe, bam de dung
     *  som) - giong het quy uoc icon Play/Stop chuan. Ve bang Canvas/Path
     *  truc tiep nen luon giong het nhau tren MOI thiet bi, khong phu thuoc
     *  font/emoji cua tung may. */
    private class MicIconDrawable(
        private val color: Int,
        private val listening: Boolean,
        private val sizePx: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@MicIconDrawable.color
            style = Paint.Style.STROKE
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val s = minOf(b.width(), b.height()).toFloat()
            if (listening) {
                // Dang NGHE: 1 o vuong bo tron TO MAU DAC (icon Stop chuan).
                paint.style = Paint.Style.FILL
                val half = s * 0.30f
                val rect = RectF(cx - half, cy - half, cx + half, cy + half)
                canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, paint)
                return
            }
            // Dang KHONG nghe: ve 1 icon Mic toi gian - CHi net VIEN (khong
            // to mau dac), giong het phong cach vien neon cua ca ban phim.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = s * 0.09f
            paint.strokeCap = Paint.Cap.ROUND
            // 1. Than mic: 1 hinh con nhong (vien tron 2 dau) o phia TREN.
            val capsuleW = s * 0.32f
            val capsuleTop = cy - s * 0.42f
            val capsuleBottom = cy + s * 0.06f
            val capsuleRect = RectF(cx - capsuleW / 2f, capsuleTop, cx + capsuleW / 2f, capsuleBottom)
            canvas.drawRoundRect(capsuleRect, capsuleW / 2f, capsuleW / 2f, paint)
            // 2. Cung "de" (pickup) om phia duoi than mic - 1 nua vong tron.
            val standRadius = s * 0.30f
            val standRect = RectF(cx - standRadius, cy - s * 0.10f, cx + standRadius, cy + standRadius)
            canvas.drawArc(standRect, 0f, 180f, false, paint)
            // 3. Chan de (than doc) + day ngang duoi cung.
            val stemBottom = cy + standRadius + s * 0.14f
            canvas.drawLine(cx, cy + standRadius, cx, stemBottom, paint)
            val baseHalf = s * 0.16f
            canvas.drawLine(cx - baseHalf, stemBottom, cx + baseHalf, stemBottom, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx
    }

    /** THEM: danh sach phim dang ky hoat hinh, TACH RIENG theo TUNG TRANG
     *  (Letters/Numbers/Symbols/Numpad) - vi cac trang duoc CACHE rieng va
     *  KHONG PHAI luc nao cung duoc xay lai cung nhau (vd bat/tat Shift chi
     *  xay lai DUY NHAT trang Chu cai qua [redrawKeyboard], cac trang khac
     *  giu nguyen cache CU khong doi) - neu dung chung 1 danh sach, moi lan
     *  trang Chu cai duoc xay lai (rat thuong xuyen) se hoac la LAM RONG mat
     *  hoat hinh cua cac trang KHAC (neu xoa het truoc khi dang ky lai), hoac
     *  TICH LUY VO HAN cac phim CU DA BI THAY THE (neu khong xoa gi ca) - ca
     *  2 truong hop deu la loi (mat hoat hinh HOAC ri bo nho). Tach rieng
     *  theo trang giai quyet dut diem: moi ham build*Page() CHi xoa+dien lai
     *  DUNG bucket cua chinh no, khong dung cham toi cac trang khac. */
    private val rgbChaseRegistryByPage = mutableMapOf<KeyboardMode, MutableList<ChaseEntry>>()

    private var rgbChasePhaseDeg: Float = 0f
    private val rgbChaseHandler = Handler(Looper.getMainLooper())
    private var rgbChaseRunnable: Runnable? = null

    /** Khoang cach giua 2 khung hinh hoat hinh (ms) - ~15 khung/giay, du
     *  muot de mat nguoi thay "chay" lien tuc nhung khong ve lai qua nhieu
     *  lan/giay (do pin, tranh giat khi go phim nhanh cung luc). */
    private val RGB_CHASE_FRAME_MS = 66L
    // SUA (theo yeu cau nguoi dung): tang toc do doi mau THEM 15% nua so voi
    // muc truoc do (5.46 -> 6.279 do/khung hinh = 5.46 * 1.15; muc 5.46 nay
    // ban than da la ket qua tang 30% so voi goc 4.2). GIU NGUYEN tan suat
    // khung hinh (RGB_CHASE_FRAME_MS khong doi) de KHONG ton them pin, chi
    // tang do LECH mau moi khung hinh -> mau "chay" nhanh hon ma van muot,
    // khong ve lai nhieu lan/giay hon truoc.
    private val RGB_CHASE_DEG_PER_FRAME = 6.279f

    /** SUA (chong lag/nong may khi dung ban phim lien tuc lau - xem giai
     *  thich chi tiet o [applyRgbChaseFrame]): neu qua khoang thoi gian nay
     *  (mili-giay) ma KHONG co lan cham phim nao, TAM DUNG vong lap ve lai
     *  hieu ung RGB (van con "song" - se chay lai NGAY LAP TUC ngay khung
     *  hinh dau tien sau lan cham phim tiep theo, khong co do tre nao ca).
     *  8 giay du ngan de khong ai nhan ra hieu ung bi "dung hinh" trong luc
     *  gian doan binh thuong giua cac lan go (nguoi dung luc do dang doc/
     *  nghi, khong nhin ban phim), nhung du dai de KHONG lam gian doan
     *  hieu ung ngay giua luc dang go binh thuong. */
    private val RGB_CHASE_IDLE_PAUSE_MS = 8000L

    /** Bat dau vong lap hoat hinh (goi khi ban phim hien len, CHi that su
     *  chay neu [rgbChaseEnabled]). An toan khi goi nhieu lan lien tiep (tu
     *  huy vong cu truoc khi tao vong moi). */
    private fun startRgbChaseLoopIfNeeded() {
        stopRgbChaseLoop()
        if (!rgbChaseEnabled) return
        // SUA: "moi" lai moc thoi gian nhan phim GAN NHAT ngay luc ban phim
        // vua duoc mo len (truoc khi nguoi dung kip go phim nao ca) - neu
        // khong, [lastKeyDownTimestamp] van con la 0L (hoac tu lan mo ban
        // phim TRUOC do rat lau) khien [applyRgbChaseFrame] tuong nham la
        // "dang idle qua lau" va DUNG HINH hieu ung NGAY TU LUC MOI MO ban
        // phim len, du nguoi dung chua kip lam gi ca.
        lastKeyDownTimestamp = android.os.SystemClock.uptimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                // SUA (loi "vang app khi chuyen trang Chu cai <-> So"): vong
                // lap nay TRUOC DAY KHONG duoc bao ve bang try/catch - la
                // ngoai le DUY NHAT trong toan bo file so voi MOI callback
                // khac (cham phim, redrawKeyboard, switchMode... deu duoc
                // bao ve rat ky, dung ly do neu 1 loi nho xay ra giua luc no
                // dang chay se lam CRASH ca tien trinh ban phim). Vi vong
                // lap nay chay LIEN TUC moi 66ms (rat thuong xuyen, y het
                // cham phim), BAT KY loi thoang qua nao (vd doc/ghi
                // [rgbChaseRegistryByPage] dung luc [switchMode] dang xay
                // lai trang, hoac 1 View/Drawable da bi thay the) deu se
                // lam VANG CA APP ngay lap tuc, ma KHONG co co hoi tu phuc
                // hoi nhu cac noi khac. SUA: bat loi, ghi log, COI NHU
                // khung hinh nay bi bo qua (khung hinh SAU se tu dong ve
                // lai dung) thay vi de sap ca tien trinh.
                try {
                    rgbChasePhaseDeg = (rgbChasePhaseDeg + RGB_CHASE_DEG_PER_FRAME) % 360f
                    applyRgbChaseFrame()
                } catch (e: Exception) {
                    android.util.Log.e("QrKeyboardService", "Loi khi ve khung hinh RGB chase: ${e.message}", e)
                }
                rgbChaseHandler.postDelayed(this, RGB_CHASE_FRAME_MS)
            }
        }
        rgbChaseRunnable = runnable
        rgbChaseHandler.postDelayed(runnable, RGB_CHASE_FRAME_MS)
    }

    private fun stopRgbChaseLoop() {
        rgbChaseRunnable?.let { rgbChaseHandler.removeCallbacks(it) }
        rgbChaseRunnable = null
    }

    /** Tinh mau cho 1 khung hinh va ap dung cho TOAN BO cac phim da dang ky
     *  trong [rgbChaseRegistry]. Cong thuc: moi phim co 1 "do lech pha"
     *  rieng dua theo vi tri (px cho Trai->Phai, py cho Tren->Duoi, px+py
     *  cho Cheo goc), cong voi pha hien tai ([rgbChasePhaseDeg]) ra 1 goc
     *  Hue (banh xe mau HSV 360 do) - tao cam giac mau "chay" doc theo dung
     *  huong da chon, lap lai vo han (giong het kieu "Colorwave" tren cac
     *  ban phim co gaming that). */
    private fun applyRgbChaseFrame() {
        // TOI UU (sua loi "go bi cham"): TRUOC DAY vong lap nay chay qua
        // TAT CA 4 trang (rgbChaseRegistryByPage.values) MOI KHUNG HINH
        // (66ms/lan), ke ca 3 trang dang AN (KHONG hien tren man hinh luc
        // do) - vd nguoi dung dang o trang Chu cai go binh thuong, nhung
        // van phai tinh HSV->RGB + goi setStroke() cho TOAN BO phim cua ca
        // trang So/Ky hieu/Numpad dang an, hoan toan lang phi vi nguoi
        // dung KHONG he nhin thay ket qua. Voi ~40 phim/trang x 3 trang an
        // = ~120 lan goi thua MOI 66ms tren CHINH main thread - dung thread
        // dang xu ly cham/vuot ngon tay khi go phim, la nguyen nhan truc
        // tiep gay giat/cham khi go luc hieu ung RGB dang bat. SUA: CHi
        // dong bo mau cho DUNG trang dang hien thi ([mode]) - cac trang an
        // se duoc dong bo lai pha hien tai ngay khi chuyen sang (xem
        // [switchMode]/[redrawKeyboard] dang goi [registerChaseKey] moi lan
        // xay trang), nen nguoi dung se KHONG thay bat ky gian doan hoat
        // hinh nao ca.
        // THEM: chup lai (snapshot, .toList()) danh sach phim cua trang
        // hien tai TRUOC khi lap - [entries] goc la MutableList CO THE bi
        // [clearChaseRegistryForPage]/[registerChaseKey] doc/ghi lai (vd
        // ngay sau khi [switchMode] vua doi [mode] nhung truoc khi trang
        // moi kip xay xong) - lap truc tiep tren list goc dang bi sua doi
        // se nem ConcurrentModificationException (truoc day KHONG duoc bat,
        // xem try/catch moi them o [startRgbChaseLoopIfNeeded]). Snapshot
        // re (chi copy tham chieu, khong copy sau) va loai bo hoan toan rui
        // ro nay.
        // SUA (nguoi dung phan anh: "gõ bình thường thì ngon, lâu lâu lag
        // cực kì, càng dùng càng chậm dần, phải thoát app gõ mới hết"):
        // NGUYEN NHAN - vong lap nay CHi dung khi ban phim AN HAN
        // ([onFinishInputView]) - nhung theo co che InputMethodService cua
        // Android, khi nguoi dung go NHIEU O NHAP trong CUNG 1 app ma ban
        // phim KHONG dong lai lan nao (rat pho bien luc chat/nhan tin),
        // [onFinishInputView] KHONG duoc goi - nghia la vong lap nay co the
        // chay LIEN TUC KHONG NGHI suot ca phien su dung (30 phut - vai
        // tieng), lien tuc chiem CPU main thread moi 66ms du nguoi dung
        // dang go hay chi dang doc/nghi (khong dung ban phim gi ca). CPU
        // "khong duoc nghi" keo dai nhu vay tren nhieu dien thoai (dac biet
        // may tam trung/gia re) se khien may NONG DAN len, buoc he dieu
        // hanh tu dong HA XUNG NHIP CPU de giam nhiet (thermal throttling)
        // - lam CHAM DAN toan bo may (khong rieng ban phim), CANG DUNG LAU
        // CANG CHAM - dung y nguoi dung mo ta. Thoat han app dang go moi
        // thuc su kich hoat [onFinishInputView] -> dung vong lap -> CPU
        // duoc nghi, may nguoi lai. SUA: TU DONG bo qua (khong tinh mau/ve
        // lai) khung hinh nao ma da qua [RGB_CHASE_IDLE_PAUSE_MS] ke tu lan
        // cham phim GAN NHAT ([lastKeyDownTimestamp] - da co san, dung
        // chung voi co che phat hien go nhanh) - hieu ung se "dung hinh" 1
        // cach tu nhien khi khong ai dung ban phim (nguoi dung khong nhin
        // thay gi khac la vi luc do khong ai nhin ban phim ca), va CHAY LAI
        // NGAY LAP TUC (khong co do tre "khoi dong lai") ngay khung hinh
        // TIEP THEO sau khi cham phim tro lai - giam manh tong thoi gian
        // CPU phai lam viec lien tuc trong 1 phien su dung dai, ma khong
        // anh huong gi den trai nghiem go phim thuc te.
        val idleMs = android.os.SystemClock.uptimeMillis() - lastKeyDownTimestamp
        if (idleMs > RGB_CHASE_IDLE_PAUSE_MS) return

        val entries = rgbChaseRegistryByPage[mode]?.toList() ?: return
        if (entries.isEmpty()) return
        // THEM (theo yeu cau nguoi dung: "có chạy led nhiều màu nhưng lại
        // không có chạy 1 màu... Màu là màu viền đang dùng đó"): 2 CHE DO
        // mau cho hieu ung chay - [RgbEffectPrefs.COLOR_MODE_RAINBOW] (MAC
        // DINH/hanh vi CU giu nguyen o nhanh else ben duoi - vien phim doi
        // qua toan bo dai mau cau vong theo vi tri+thoi gian) va
        // [RgbEffectPrefs.COLOR_MODE_SINGLE] (MOI - vien phim VAN "chay"
        // (nhap nhay do sang theo huong da chon, tao cam giac song di
        // chuyen), nhung KHONG doi TONG MAU (Hue) - CHi dung DUNG 1 mau DUY
        // NHAT: chinh la [glowColor] (mau vien nguoi dung dang chon trong
        // Cai dat giao dien)).
        if (rgbChaseColorMode == RgbEffectPrefs.COLOR_MODE_SINGLE) {
            val baseHsv = FloatArray(3)
            Color.colorToHSV(glowColor, baseHsv)
            // Neu mau nen dang chon co do bao hoa (saturation) qua thap (vd
            // gan trang/xam), ep len toi thieu de "song chay" van con nhin
            // ra duoc ro rang, khong bi "chim" thanh mot mau xam nhat deu.
            val saturation = baseHsv[1].coerceAtLeast(0.45f)
            for (entry in entries) {
                val posFactor = when (rgbChaseDirection) {
                    RgbEffectPrefs.DIRECTION_TOP_TO_BOTTOM -> entry.py
                    RgbEffectPrefs.DIRECTION_DIAGONAL -> (entry.px + entry.py) / 2f
                    else -> entry.px // DIRECTION_LEFT_TO_RIGHT (mac dinh)
                }
                // Song hinh sin theo vi tri + pha thoi gian hien tai -> tao
                // cam giac 1 "vet sang" dang di chuyen doc theo [rgbChaseDirection],
                // dao dong do sang (Value) tu 55% (mo) len 100% (sang ro) -
                // GIONG HET cam giac "chay" cua che do nhieu mau, chi khac
                // la KHONG doi Hue (mau goc).
                val phaseRad = Math.toRadians((rgbChasePhaseDeg + posFactor * 360f).toDouble())
                val wave = ((Math.sin(phaseRad).toFloat() + 1f) / 2f)
                val value = 0.55f + wave * 0.45f
                val color = Color.HSVToColor(floatArrayOf(baseHsv[0], saturation, value))
                try {
                    entry.drawable.setStroke(dp(1), color)
                } catch (e: Exception) {
                    // Bo qua 1 phim loi (hiem gap) - khong lam hong ca khung hinh.
                }
            }
            return
        }
        val hsv = floatArrayOf(0f, 0.85f, 1f)
        for (entry in entries) {
            val posFactor = when (rgbChaseDirection) {
                RgbEffectPrefs.DIRECTION_TOP_TO_BOTTOM -> entry.py
                RgbEffectPrefs.DIRECTION_DIAGONAL -> (entry.px + entry.py) / 2f
                else -> entry.px // DIRECTION_LEFT_TO_RIGHT (mac dinh)
            }
            hsv[0] = (rgbChasePhaseDeg + posFactor * 360f) % 360f
            val color = Color.HSVToColor(hsv)
            try {
                entry.drawable.setStroke(dp(1), color)
            } catch (e: Exception) {
                // Bo qua 1 phim loi (hiem gap) - khong lam hong ca khung hinh.
            }
        }
    }

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
    private val numberRow3Symbols = "*\"':;!?"

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
        // SUA (theo yeu cau nguoi dung): "<" và ">" đã CHUYỂN xuống hàng
        // dấu cách (xem buildExtendedSymbolsBottomRow) - bổ sung "£"
        // (\u00a3) và "€" (\u20ac) vào ĐÚNG vị trí cũ của chúng (đầu hàng
        // này) thay vì để trống.
        "\u00a3\u20ac$\u00a2^\u00b0={}\\"
    )
    // SUA (theo yeu cau nguoi dung): thay "§" (\u00a7) bang "℅" (\u2105,
    // ky hieu "care of").
    private val extendedSymbolRow3 = "%\u00a9\u00ae\u2122\u2105\u00b1[]"

    /** THEM (theo yeu cau nguoi dung "bo sung tu goi y nhieu nhat co the"):
     *  bang tu khoa -> emoji GOI Y khi go TRUNG KHOP HOAN TOAN 1 tu (khong
     *  phai chuoi con) - CHi dung lai cac emoji DA CO SAN trong [emojiList]
     *  o trang Ky hieu/So (trang 2), KHONG them emoji moi nao ca, dung yeu
     *  cau "phan icon o trang 2 co dinh giu nguyen, phan nay chi truy xuat
     *  no". Ca ban KHONG dau (go nhanh, go tieng Anh) LAN CO dau (Tieng
     *  Viet chuan) deu duoc liet ke rieng de khop chinh xac voi
     *  [emojiTrackWord] (giu nguyen dau neu dang go Tieng Viet). */
    private val EMOJI_TRIGGERS: Map<String, String> = mapOf(
        // Cuoi
        "hihi" to "\ud83d\ude02", "haha" to "\ud83d\ude02", "hehe" to "\ud83d\ude04",
        "khakha" to "\ud83e\udd23", "lol" to "\ud83e\udd23",
        "cuoi" to "\ud83d\ude04", "c\u01b0\u1eddi" to "\ud83d\ude04",
        "vui" to "\ud83d\ude0a",
        // Yeu thich
        "yeu" to "\u2764\ufe0f", "y\u00eau" to "\u2764\ufe0f", "iu" to "\u2764\ufe0f",
        "thich" to "\ud83d\ude0d", "th\u00edch" to "\ud83d\ude0d",
        "tim" to "\u2764\ufe0f",
        "hon" to "\ud83d\ude18", "h\u00f4n" to "\ud83d\ude18",
        "ngau" to "\ud83d\ude0e", "ng\u1ea7u" to "\ud83d\ude0e",
        // Buon / gian / so
        "buon" to "\ud83d\ude22", "bu\u1ed3n" to "\ud83d\ude22",
        "khoc" to "\ud83d\ude2d", "kh\u00f3c" to "\ud83d\ude2d",
        "gian" to "\ud83d\ude20", "gi\u1eadn" to "\ud83d\ude20",
        "tuc" to "\ud83d\ude21", "t\u1ee9c" to "\ud83d\ude21",
        "so" to "\ud83d\ude31", "s\u1ee3" to "\ud83d\ude31",
        "soc" to "\ud83d\ude33", "s\u1ed1c" to "\ud83d\ude33",
        "ngu" to "\ud83d\ude34", "ng\u1ee7" to "\ud83d\ude34",
        // Nghi/dong y
        "nghi" to "\ud83e\udd14", "ngh\u0129" to "\ud83e\udd14",
        "ok" to "\ud83d\udc4c", "oke" to "\ud83d\udc4c", "okay" to "\ud83d\udc4c",
        "tot" to "\ud83d\udc4d", "t\u1ed1t" to "\ud83d\udc4d",
        "xau" to "\ud83d\udc4e", "x\u1ea5u" to "\ud83d\udc4e",
        "done" to "\u2705", "xong" to "\u2705",
        "sai" to "\u274c", "no" to "\u274c",
        // Chao hoi / cam on
        "chao" to "\ud83d\udc4b", "ch\u00e0o" to "\ud83d\udc4b",
        "hi" to "\ud83d\udc4b", "hello" to "\ud83d\udc4b",
        "camon" to "\ud83d\ude4f", "c\u1ea3m\u01a1n" to "\ud83d\ude4f",
        "thanks" to "\ud83d\ude4f", "thank" to "\ud83d\ude4f",
        "khoe" to "\ud83d\udcaa", "kh\u1ecfe" to "\ud83d\udcaa",
        // Thien nhien / do vat
        "lua" to "\ud83d\udd25", "l\u1eeda" to "\ud83d\udd25",
        "sao" to "\u2b50",
        "nang" to "\u2600\ufe0f", "n\u1eafng" to "\u2600\ufe0f",
        "mua" to "\ud83c\udf27\ufe0f", "m\u01b0a" to "\ud83c\udf27\ufe0f",
        "hoa" to "\ud83c\udf38",
        "tiec" to "\ud83c\udf89", "ti\u1ec7c" to "\ud83c\udf89",
        "cafe" to "\u2615", "caphe" to "\u2615", "c\u00e0ph\u00ea" to "\u2615",
        "tien" to "\ud83d\udcb0", "ti\u1ec1n" to "\ud83d\udcb0",
        "dienthoai" to "\ud83d\udcf1",
        // Dong vat
        "cho" to "\ud83d\udc36", "ch\u00f3" to "\ud83d\udc36",
        "meo" to "\ud83d\udc31", "m\u00e8o" to "\ud83d\udc31", "meocon" to "\ud83d\udc31",
        "ca" to "\ud83d\udc20", "c\u00e1" to "\ud83d\udc20",
        // Khac
        "robot" to "\ud83e\udd16",
        "gio" to "\u23f0", "gi\u1edd" to "\u23f0"
    )

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
    /** THEM (theo yeu cau nguoi dung: "bật nó là không được phép ẩn bàn
     *  phím nha" - luc dang bat Mic (dang nghe), KHONG duoc phep an ban
     *  phim di): chan phim/nut Back (KEYCODE_BACK - cach pho bien nhat de
     *  an ban phim tren Android, ca nut cung lan cu chi vuot ve) NGAY KHI
     *  dang nghe - "nuot" su kien nay (tra ve true, KHONG goi super) thay
     *  vi de he thong xu ly binh thuong (se an ban phim). Ban phim se AN
     *  DUOC LAI BINH THUONG ngay khi mic dung nghe (isListeningForVoice ve
     *  false). LUU Y: day CHi chan duoc nut/phim Back - Android KHONG cho
     *  IME chan cac hanh dong khac nhu chuyen sang app khac hoan toan hay
     *  tat man hinh (nam ngoai kha nang can thiep cua 1 ban phim). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && isListeningForVoice) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Chan luon canh "up" cua phim Back tuong ung (phong ho, dam bao khong
     *  co nhip nao lot qua du ly do gi) - cung dieu kien nhu [onKeyDown]. */
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && isListeningForVoice) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreateInputView(): View {
        // SUA (theo yeu cau nguoi dung, sua loi "tu tat ban phim, khong bat
        // lai duoc - chi con 1 hang den + icon chuyen ban phim"): BOC
        // try/catch TOAN BO than ham nay. Day la buoc DAU TIEN he thong goi
        // moi lan can hien ban phim - TRUOC DAY neu buildKeyboardContainer()
        // (xay ca 4 trang, hieu ung RGB, popup dau, goi y emoji...) nem loi
        // BAT KY o BUOC KHOI TAO nay, toan bo Service se crash NGAY LUC vua
        // mo len - giao dien "1 hang den + icon chuyen ban phim" nguoi dung
        // mo ta chinh la man hinh du phong cua he thong Android khi mot IME
        // loi luc khoi tao. SUA: neu loi, tra ve 1 ban phim TOI GIAN
        // (buildFallbackKeyboardView() - chi QWERTY co ban, KHONG co hieu
        // ung/tinh nang phu nao co the loi) thay vi de crash hoan toan - it
        // nhat nguoi dung VAN GO DUOC binh thuong, khong bi "khoa" hoan
        // toan khoi ban phim.
        return try {
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
            buildKeyboardContainer()
        } catch (e: Exception) {
            android.util.Log.e("QrKeyboardService", "Loi khi tao ban phim: ${e.message}", e)
            try {
                buildFallbackKeyboardView()
            } catch (e2: Exception) {
                // Neu ca ban phim toi gian cung loi (cuc ky hiem gap) -
                // danh chiu, tra ve 1 View rong de it nhat KHONG crash het
                // Service (nguoi dung se thay ban phim trong, nhung Service
                // van song, co the thu lai sau).
                View(this)
            }
        }
    }

    /** THEM: ban phim TOI GIAN, du phong khi [buildKeyboardContainer] gap
     *  loi - chi co 2 hang chu QWERTY co ban + hang Cach/Xoa/Enter, KHONG co
     *  Shift, hieu ung RGB, popup dau, goi y emoji, QR... (loai bo het cac
     *  tinh nang PHUC TAP moi them, chi giu lai phan LOI don gian nhat, it
     *  kha nang loi nhat) - de nguoi dung VAN GO DUOC chu co ban trong luc
     *  cho sua loi that su. */
    private fun buildFallbackKeyboardView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A0F2E"))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        fun simpleKey(label: String, weight: Float, action: () -> Unit): Button = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0A0510"))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), weight).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            setOnClickListener { action() }
        }
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        rows.forEach { r ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.forEach { c -> row.addView(simpleKey(c.toString(), 1f) { currentInputConnection?.commitText(c.toString(), 1) }) }
            root.addView(row)
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(simpleKey("\u232b", 1.5f) { currentInputConnection?.deleteSurroundingText(1, 0) })
        bottom.addView(simpleKey("\u2423", 4f) { currentInputConnection?.commitText(" ", 1) })
        bottom.addView(simpleKey("\u23ce", 1.5f) { currentInputConnection?.commitText("\n", 1) })
        root.addView(bottom)
        return root
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
        clearChaseRegistryForPage(KeyboardMode.LETTERS)
        val verticalPaddingDp = if (keyHeightDp < 48) 2 else 6
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(verticalPaddingDp), dp(4), dp(verticalPaddingDp + EXTRA_BOTTOM_LIFT_DP))
        }

        when (mode) {
            KeyboardMode.LETTERS -> {
                // SUA (nguoi dung phan anh: "gõ liên tục mà gợi ý không chịu
                // ẩn"): THEM 1 lop kiem tra "con dung" (freshness) CUOI CUNG
                // NGAY TAI DAY - phong ho truong hop (chua xac dinh chinh xac
                // duoc, co the la 1 tinh huong hiem/dua xen chua luong ra) mot
                // vai diem trong code quen goi [checkEmojiSuggestion] de tu
                // dep goi y emoji CU khi tu dang go da thay doi - neu
                // [pendingEmojiOriginalWord] (tu GOC luc goi y duoc tao ra)
                // KHONG CON KHOP voi [emojiTrackWord] HIEN TAI (tu THAT SU
                // dang go luc nay - CHi ap dung cho goi y EMOJI, vi day la
                // goi y "song" theo TUNG ky tu dang go, khac voi goi y
                // AUTOCORRECT ben duoi von duoc tao ra SAU KHI 1 tu da go
                // XONG va [emojiTrackWord] da duoc xoa rong - xem
                // [checkAutocorrectSuggestion]/[finishWordTracking], nen
                // KHONG the/KHONG can ap dung kieu kiem tra tuong tu cho
                // pendingSuggestion o day), coi goi y do la CU/khong con hop
                // le nua - don sach NGAY truoc khi quyet dinh co hien hang
                // goi y nao hay khong, thay vi tin tuong mu quang vao
                // [pendingEmojiSuggestion] co the dang "treo" sai tu 1 nhip
                // go truoc do.
                val currentTrack = emojiTrackWord.toString()
                if (pendingEmojiSuggestion != null && pendingEmojiOriginalWord != currentTrack) {
                    pendingEmojiSuggestion = null
                    pendingEmojiOriginalWord = null
                    cancelEmojiSuggestionAutoHide()
                }
                // ĐÃ REVERT (theo yêu cầu người dùng - fix "luôn thêm hàng gợi ý với chiều cao
                // cố định" ở dưới gây ra nhiều lỗi khác, phải phục hồi lại đúng cách làm CŨ):
                // chỉ addView() hàng gợi ý vào layout LÚC THẬT SỰ có gợi ý, gỡ hẳn khỏi layout
                // lúc không có - đúng như trước khi có fix lệch phím. Chấp nhận lại nhược điểm
                // gốc (hàng phím bên dưới có thể bị xê dịch nhẹ lúc gợi ý bật/tắt) để đổi lấy việc
                // bỏ các lỗi mới phát sinh từ cách làm "chiều cao cố định".
                if (pendingEmojiSuggestion != null || pendingSuggestion != null) {
                    root.addView(buildSuggestionSlot())
                }
                root.addView(buildCharRow(numberRows[0], rowPhase = 0f))
                letterRows.forEachIndexed { index, row ->
                    // SUA LOI (theo yeu cau nguoi dung): hang chu THU 2 tu
                    // tren xuong ("asdfghjkl", 9 ky tu) TRUOC DAY bi kiem
                    // tra SAI VI TRI - dieu kien dung `index == 0` (tro vao
                    // letterRows[0] = "qwertyuiop", DA DU 10 ky tu nen dieu
                    // kien do dai luon SAI, KHONG BAO GIO kich hoat) thay vi
                    // `index == 1` (letterRows[1] = "asdfghjkl", DUNG hang
                    // co 9 ky tu can co giãn). Hau qua: hang "asdfghjkl" chi
                    // dung buildCharRow() nhu moi hang khac - vi chi co 9
                    // phim thay vi 10 nhu hang tren ("qwertyuiop"), MOI PHIM
                    // BI PHONG TO len de tu lap day het chieu rong hang,
                    // khien phim hang nay TO HON han hang tren/duoi, khong
                    // thang hang/xen ke nhu ban phim vat ly that. GIO DAY
                    // (da sua index) dung buildStaggeredCharRow(): giu
                    // NGUYEN kich thuoc tung phim bang dung hang tren (10),
                    // chen 2 khoang trong nua-phim vao 2 ben de lap day phan
                    // con thieu - vua dung KICH CO tung phim, vua TU CAN
                    // GIUA ca hang (2 khoang trong 2 ben bang nhau).
                    // KHONG ap dung cho hang CUOI (letterRows.lastIndex,
                    // "zxcvbnm") vi hang do con duoc CHEN THEM phim
                    // Shift/Xoa ngay ben duoi day - neu cung stagger hang
                    // do, tong do rong se TANG THEM (13 thay vi 10), lam
                    // hang do RONG HON han cac hang khac, pha vo su can
                    // bang da co san tu truoc.
                    val rowPhase = (index + 1).toFloat() / letterRows.size
                    val rowView = if (index == 1 && row.length < numberRows[0].length)
                        buildStaggeredCharRow(row, numberRows[0].length, applyShiftCase = true, rowPhase = rowPhase)
                    else
                        buildCharRow(row, applyShiftCase = true, rowPhase = rowPhase)
                    if (index == letterRows.lastIndex) {
                        val shiftKey = buildKey(
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
                        }
                        shiftKey.tag = isShiftOn || showCapitalPreview  // khởi tạo tag đúng
                        cachedShiftKey = shiftKey
                        rowView.addView(shiftKey, 0)
                        registerChaseKey(KeyboardMode.LETTERS, shiftKey, 0.03f, rowPhase)
                        val backspaceKey = buildKey("\u232b", weight = 1.5f, onRepeat = { deleteChar() }) { deleteChar() }
                        rowView.addView(backspaceKey)
                        registerChaseKey(KeyboardMode.LETTERS, backspaceKey, 0.97f, rowPhase)
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
        clearChaseRegistryForPage(KeyboardMode.NUMBERS)
        val verticalPaddingDp = if (keyHeightDp < 48) 2 else 6
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(verticalPaddingDp), dp(4), dp(verticalPaddingDp + EXTRA_BOTTOM_LIFT_DP))
            addView(buildEmojiRow())
            numberRows.forEachIndexed { i, row -> addView(buildCharRow(row, rowPhase = i.toFloat() / (numberRows.size))) }
            addView(buildNumbersRow3())
            addView(buildNumbersBottomRow())
        }
    }

    /** Build trang ky hieu (SYMBOLS) - CHI duoc goi khi nguoi dung THAT SU
     *  chuyen toi trang nay, ket qua duoc cache lai qua [cachedSymbolsView].
     *  Dong tren cung la nut "Cai dat" (xem [buildKeyboardSettingsBar]) mo
     *  SettingsActivity - noi gio day gom ca phan chon mau sac (truoc day
     *  la 1 thanh chon mau ngay tai day, da chuyen han sang man Cai dat
     *  rieng theo yeu cau nguoi dung). */
    private fun buildSymbolsPage(): View {
        clearChaseRegistryForPage(KeyboardMode.SYMBOLS)
        val verticalPaddingDp = if (keyHeightDp < 48) 2 else 6
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(verticalPaddingDp), dp(4), dp(verticalPaddingDp + EXTRA_BOTTOM_LIFT_DP))
            addView(buildKeyboardSettingsBar())
            extendedSymbolRows.forEachIndexed { i, row -> addView(buildCharRow(row, rowPhase = i.toFloat() / (extendedSymbolRows.size))) }
            addView(buildExtendedSymbolsRow3())
            addView(buildExtendedSymbolsBottomRow())
        }
    }

    /** SUA (theo yeu cau nguoi dung): ban phim so rieng (duoc TU DONG chon
     *  khi mo mot o nhap CHI NHAN SO - xem [isNumericOnlyField]) - GIO DAY
     *  dung 4 dong, dang "ban phim bam so dien thoai" co dinh:
     *  - Dong 1: 1, 2, 3, Xoa (4 nut, weight=1 deu nhau).
     *  - Dong 2: 4, 5, 6, roi 1 khoang trong RONG DUNG BANG 1 nut (khong co
     *    phim gi ca, chi de GIU THANG COT voi nut "Xoa" o dong 1 phia tren).
     *  - Dong 3: 7, 8, 9, cung 1 khoang trong bang 1 nut y het dong 2.
     *  - Dong 4: ABC (chuyen ve chu cai), 0, roi phim Enter RONG GAP DOI (2
     *    lan) so voi 2 phim con lai truoc no trong CUNG dong nay.
     *  Tat ca 4 dong deu co TONG do rong quy doi bang 4 don vi (1+1+1+1),
     *  nen cac phim/khoang trong o CUNG mot cot deu thang hang voi nhau qua
     *  ca 4 dong (giong bo cuc ban phim bam so dien thoai that). */
    private fun buildNumpadPage(): View {
        clearChaseRegistryForPage(KeyboardMode.NUMPAD)
        val verticalPaddingDp = if (keyHeightDp < 48) 2 else 6
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(verticalPaddingDp), dp(4), dp(verticalPaddingDp + EXTRA_BOTTOM_LIFT_DP))
        }

        fun spacer(weight: Float): View = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        }

        // Dong 1: 1, 2, 3, Xoa
        val row1 = LinearLayout(this).apply {
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        listOf("1", "2", "3").forEachIndexed { idx, d ->
            val key = buildKey(d) { insertChar(d[0]) }
            row1.addView(key)
            registerChaseKey(KeyboardMode.NUMPAD, key, idx / 3f, 0f)
        }
        val delKey = buildKey("\u232b", onRepeat = { deleteChar() }) { deleteChar() }
        row1.addView(delKey)
        registerChaseKey(KeyboardMode.NUMPAD, delKey, 1f, 0f)
        root.addView(row1)

        // Dong 2: 4, 5, 6, khoang trong bang 1 nut
        val row2 = LinearLayout(this).apply {
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        listOf("4", "5", "6").forEachIndexed { idx, d ->
            val key = buildKey(d) { insertChar(d[0]) }
            row2.addView(key)
            registerChaseKey(KeyboardMode.NUMPAD, key, idx / 3f, 0.33f)
        }
        row2.addView(spacer(weight = 1f))
        root.addView(row2)

        // Dong 3: 7, 8, 9, khoang trong bang 1 nut
        val row3 = LinearLayout(this).apply {
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        listOf("7", "8", "9").forEachIndexed { idx, d ->
            val key = buildKey(d) { insertChar(d[0]) }
            row3.addView(key)
            registerChaseKey(KeyboardMode.NUMPAD, key, idx / 3f, 0.67f)
        }
        row3.addView(spacer(weight = 1f))
        root.addView(row3)

        // Dong 4: ABC, 0, Enter (rong gap doi 2 phim con lai)
        val row4 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(keyHeightDp + 2)
            ).apply { bottomMargin = dp(6) }
        }
        val nk1 = buildKey("ABC", weight = 1f, fillRowHeight = true) { switchMode(KeyboardMode.LETTERS) }
        row4.addView(nk1)
        registerChaseKey(KeyboardMode.NUMPAD, nk1, 0.125f, 1f)
        val nk2 = buildKey("0", weight = 1f, fillRowHeight = true) { insertChar('0') }
        row4.addView(nk2)
        registerChaseKey(KeyboardMode.NUMPAD, nk2, 0.375f, 1f)
        val nk3 = buildKey("\u23ce", weight = 2f, highlight = true, fillRowHeight = true) { sendEnter() }
        row4.addView(nk3)
        registerChaseKey(KeyboardMode.NUMPAD, nk3, 0.75f, 1f)
        root.addView(row4)

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
        // SUA (theo yeu cau nguoi dung, sua loi "chuyen trang la an ban phim
        // luon, khong bat lai duoc"): BOC try/catch TOAN BO than ham nay.
        // Day la ham duoc goi TRUC TIEP tu onClick cua cac nut chuyen trang
        // (?123/ABC/123 tren ban phim). TRUOC DAY neu qua trinh build trang
        // moi (Numbers/Symbols/Numpad - vd buildNumbersPage()) nem loi bat
        // ky (du la loi gi), no se KHONG duoc bat, lam CRASH toan bo tien
        // trinh ban phim NGAY LAP TUC - khop dung trieu chung nguoi dung mo
        // ta. Neu loi xay ra, co gang khoi phuc VE TRANG CHU CAI (trang on
        // dinh nhat, it code nhat) thay vi de ban phim treo/bien mat hoan
        // toan.
        try {
            mode = newMode
            val container = keyboardRootContainer
            val letters = lettersPageView
            if (container == null || letters == null) {
                setInputView(buildKeyboardContainer())
                return
            }
            when (newMode) {
                KeyboardMode.LETTERS -> { /* da co san */ }
                // SUA LOI THAT (theo yeu cau nguoi dung, tim ra qua doc ky
                // code, KHONG doan mo): TRUOC DAY chi kiem tra
                // "cachedXxxView == null" de quyet dinh co can xay/gan lai
                // trang vao [container] hay khong. NHUNG [onCreateInputView]
                // co mot buoc TOI UU: moi lan ban phim duoc tao lai (xay ra
                // THUONG XUYEN - doi o nhap, doi app...) trong khi dang o
                // trang LETTERS, no THAO (detach) cachedNumbersView/
                // cachedSymbolsView/cachedNumpadView khoi container CU de
                // tranh loi "view da co cha", nhung KHONG dat chung ve null
                // (giu lai de khoi xay lai tu dau, dung y do toi uu) - va
                // [buildKeyboardContainer] LUC DO chi gan DUY NHAT trang
                // dang trung [mode] (LETTERS) vao container MOI, bo qua cac
                // trang con lai. Hau qua: cachedNumbersView/... VAN "song"
                // va KHAC null, nhung MO COI (khong con la con cua BAT KY
                // container nao dang hien). Lan sau bam "?123", dieu kien
                // "== null" SAI (no co null dau) nen KHONG duoc gan lai vao
                // container MOI - chi set visibility=VISIBLE cho 1 View
                // khong thuoc cay giao dien nao ca, nen KHONG HIEN GI CA,
                // trong khi trang Chu cai da bi an truoc do - dung y het
                // trieu chung "an trang 1, khong bat duoc trang 2". SUA:
                // kiem tra CA truong hop View đã có san nhung dang MO COI
                // (parent != container hien tai) - neu vay, GAN LAI vao
                // container hien tai truoc, khong chi kiem tra null suong.
                KeyboardMode.NUMBERS -> {
                    val cached = cachedNumbersView
                    if (cached == null) {
                        cachedNumbersView = buildNumbersPage().also { container.addView(it) }
                    } else if (cached.parent !== container) {
                        detachFromParentIfAny(cached)
                        container.addView(cached)
                    }
                }
                KeyboardMode.SYMBOLS -> {
                    val cached = cachedSymbolsView
                    if (cached == null) {
                        cachedSymbolsView = buildSymbolsPage().also { container.addView(it) }
                    } else if (cached.parent !== container) {
                        detachFromParentIfAny(cached)
                        container.addView(cached)
                    }
                }
                KeyboardMode.NUMPAD -> {
                    val cached = cachedNumpadView
                    if (cached == null) {
                        cachedNumpadView = buildNumpadPage().also { container.addView(it) }
                    } else if (cached.parent !== container) {
                        detachFromParentIfAny(cached)
                        container.addView(cached)
                    }
                }
            }
            applyModeVisibility(container, letters, cachedNumbersView, cachedSymbolsView, cachedNumpadView)
        } catch (e: Exception) {
            android.util.Log.e("QrKeyboardService", "Loi khi chuyen sang trang $newMode: ${e.message}", e)
            try {
                mode = KeyboardMode.LETTERS
                cachedNumbersView = null
                cachedSymbolsView = null
                cachedNumpadView = null
                lettersPageView = null
                keyboardRootContainer = null
                setInputView(buildKeyboardContainer())
            } catch (e2: Exception) {
                // Neu ca buoc khoi phuc nay cung loi (rat hiem), danh chiu -
                // it nhat da co log ro rang ("Loi khi chuyen sang trang...")
                // de xem lai sau, khong con gi khac lam duoc o day.
            }
        }
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
        // Xóa cache (sắp rebuild toàn trang)
        cachedSuggestionRow = null
        cachedShiftKey = null
        cachedLetterKeys.clear()
        try {
            // SUA LOI "giu xoa het sach roi buong tay van tu dong xoa tiep":
            // ham nay co the duoc goi CHINH TU BEN TRONG vong lap xoa lien
            // tuc (deleteChar -> redrawKeyboard khi o nhap vua trong ra), se
            // THAO nut ⌫ dang duoc giu khoi cay view. Huy TRUOC bat ky
            // runnable lap lai xoa nao dang cho san trong [deleteRepeatHandler]
            // (va bao no dung tu hoi sinh qua [activeDeleteRepeatRunnable] =
            // null) de vong lap xoa KHONG con co hoi chay tiep vo han sau khi
            // nut cu bi go, du nguoi dung co buong tay hay khong.
            activeDeleteRepeatRunnable?.let { deleteRepeatHandler.removeCallbacks(it) }
            activeDeleteRepeatRunnable = null
            val container = keyboardRootContainer
            if (container != null && mode == KeyboardMode.LETTERS) {
                // SUA LOI (nguoi dung phan anh: "lau lau tat ban phim roi bat
                // lai, hoac chi an het phim con dung 1 day den phia duoi
                // cung"): TRUOC DAY xoa view CU khoi container ([removeView])
                // RUOI MOI goi [buildLettersPage] de dung view MOI - neu ham
                // nay (chay RAT thuong xuyen: bat Shift, goi y xuat hien/bien
                // mat, doi ngon ngu...) nem loi o BAT KY dau ben trong, view
                // CU da bi xoa nhung view MOI chua kip them vao, de lai
                // container HOAN TOAN TRONG (chi con nen mau [keyboardBackgroundColor],
                // KHONG con phim nao) - dung khop trieu chung nguoi dung mo
                // ta. SUA: dung view MOI TRUOC, CHi xoa view CU + gan view
                // moi vao SAU KHI da dung thanh cong - neu [buildLettersPage]
                // nem loi, container VAN CON NGUYEN view cu (dang hoat dong
                // binh thuong), khong bao gio bi trong; loi van roi xuong
                // khoi catch ben ngoai de log + thu khoi phuc them 1 lan nua
                // nhu cu, nhung nguoi dung se KHONG con thay man hinh trong
                // giua chung nua.
                val newLetters = buildLettersPage()
                lettersPageView?.let { container.removeView(it) }
                lettersPageView = newLetters
                container.addView(newLetters, 0)
                applyModeVisibility(container, newLetters, cachedNumbersView, cachedSymbolsView, cachedNumpadView)
            } else {
                setInputView(buildKeyboardContainer())
            }
        } catch (e: Exception) {
            android.util.Log.e("QrKeyboardService", "Loi khi ve lai ban phim: ${e.message}", e)
            try {
                cachedNumbersView = null
                cachedSymbolsView = null
                cachedNumpadView = null
                lettersPageView = null
                keyboardRootContainer = null
                setInputView(buildKeyboardContainer())
            } catch (e2: Exception) {
                // Danh chiu - da co log de xem lai sau.
            }
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
            activeIsLang1 = !activeIsLang1
            currentWord.clear(); emojiTrackWord.clear()
        }

        currentWord.clear(); emojiTrackWord.clear()
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
    /** THEM: dang ky 1 phim (vua duoc xay xong) vao [rgbChaseRegistry] de
     *  hoat hinh chay mau - CHi lam gi neu [rgbChaseEnabled] dang bat (do
     *  la loi kiem tra RE, tranh lang phi khi hieu ung dang tat). Lay ra
     *  drawable VIEN (lop thu 2 trong LayerDrawable tra ve tu
     *  [buildGlowKeyBackground] - xem chi tiet o do) de sau nay chi can doi
     *  MAU cua chinh drawable nay moi khung hinh, KHONG can xay lai ca
     *  Drawable/View - re hon nhieu. */
    private fun registerChaseKey(page: KeyboardMode, key: View, px: Float, py: Float) {
        if (!rgbChaseEnabled) return
        val layers = key.background as? LayerDrawable ?: return
        if (layers.numberOfLayers < 2) return
        val border = layers.getDrawable(1) as? GradientDrawable ?: return
        rgbChaseRegistryByPage.getOrPut(page) { mutableListOf() }
            .add(ChaseEntry(border, px.coerceIn(0f, 1f), py.coerceIn(0f, 1f)))
    }

    /** THEM: goi o DAU moi ham build*Page() - xoa SACH bucket cua DUNG trang
     *  do (khong dung toi cac trang khac) truoc khi dang ky lai tu dau, tranh
     *  tich luy vo han cac phim CU da bi thay the moi lan trang do duoc xay
     *  lai (vd trang Chu cai qua [redrawKeyboard], rat thuong xuyen). */
    private fun clearChaseRegistryForPage(page: KeyboardMode) {
        rgbChaseRegistryByPage[page]?.clear()
    }

    private fun buildCharRow(
        chars: String, applyShiftCase: Boolean = false, rowPhase: Float = 0.5f,
        chasePage: KeyboardMode = mode
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val total = chars.length
        chars.forEachIndexed { idx, ch ->
            val label = if (applyShiftCase && (isShiftOn || showCapitalPreview)) ch.uppercaseChar().toString() else ch.toString()
            val key = buildKey(label) { insertChar(ch) }
            if (applyShiftCase) cachedLetterKeys[ch] = key
            row.addView(key)
            registerChaseKey(chasePage, key, if (total > 1) idx.toFloat() / (total - 1) else 0.5f, rowPhase)
        }
        return row
    }

    /** THEM (theo yeu cau nguoi dung): dung cho hang co ÍT ký tự HƠN hàng
     *  tham chiếu bên trên (vd hàng "asdfghjkl" 9 ký tự so với hàng
     *  "qwertyuiop" 10 ký tự) - GIỮ NGUYÊN kích thước từng phím bằng đúng
     *  hàng tham chiếu (mỗi phím vẫn weight=1f y hệt [buildCharRow]), rồi
     *  chèn thêm 2 khoảng trống rỗng NỬA-PHÍM (weight = chênh lệch/2) vào
     *  2 bên trái/phải để LẤP ĐẦY đúng phần thiếu - tạo hiệu ứng các phím
     *  XEN KẼ/so le với hàng trên, giống hệt bố cục bàn phím vật lý thật,
     *  thay vì tự phóng to từng phím lên để lấp đầy cả hàng như trước
     *  (khiến phím hàng này to hơn hẳn hàng trên, không thẳng hàng). */
    private fun buildStaggeredCharRow(
        chars: String, referenceKeyCount: Int, applyShiftCase: Boolean = false, rowPhase: Float = 0.5f,
        chasePage: KeyboardMode = mode
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val sideWeight = (referenceKeyCount - chars.length) / 2f
        if (sideWeight > 0f) {
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, sideWeight)
            })
        }
        // THEM: vi tri X CHUAN HOA (px) tinh theo DUNG luoi cua hang tham
        // chieu (referenceKeyCount), KHONG phai theo so ky tu thuc te cua
        // hang nay - de hieu ung "chay" van thang hang DUNG cot voi hang
        // tren/duoi (vd chu "a" o hang 2 phai cung pha voi chu "q" o hang 1
        // vi ca 2 deu nam o cot dau tien), khong bi lech do hang nay it
        // phim hon.
        chars.forEachIndexed { idx, ch ->
            val label = if (applyShiftCase && (isShiftOn || showCapitalPreview)) ch.uppercaseChar().toString() else ch.toString()
            val key = buildKey(label) { insertChar(ch) }
            if (applyShiftCase) cachedLetterKeys[ch] = key  // cache để updateShiftStateInPlace() update đúng
            row.addView(key)
            val gridCol = sideWeight + idx
            val px = if (referenceKeyCount > 1) gridCol / (referenceKeyCount - 1) else 0.5f
            registerChaseKey(chasePage, key, px, rowPhase)
        }
        if (sideWeight > 0f) {
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, sideWeight)
            })
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
        // SUA LOI NGHIEM TRONG (nghi ngo chinh la nguyen nhan "bam ?123
        // khong bat trang 2"): TRUOC DAY tao DUY NHAT 1 Drawable
        // ([sharedEmojiBg]) roi GAN CHUNG cho CA ~200 nut emoji ben duoi -
        // Android TUYET DOI KHONG cho phep 1 doi tuong Drawable duoc dung
        // lam background cho NHIEU View cung luc: moi lan mot Button goi
        // setBackground(), no se "chiem" callback cua chinh Drawable do
        // (drawable.setCallback(view)) VA moi lan do bo (layout) cung GHI
        // DE truc tiep len [bounds] noi tai CUA CHINH drawable do (vi la
        // CUNG 1 doi tuong trong bo nho, khong phai 200 ban sao rieng) -
        // ket qua la 200 nut CUNG TRO VE 1 trang thai bounds/callback DUY
        // NHAT, chi nut duoc bo (layout) SAU CUNG la "thang", gay hong hinh
        // hien thi NANG (cac nut khac meo/sai kich thuoc) va tren mot so
        // dong may/phien ban Android co the nem IllegalStateException ngay
        // giua qua trinh dung hinh 200 nut nay - dung luc trang So dang
        // duoc XAY LAN DAU (khi bam "?123"), khien switchMode() nem loi,
        // BI BAT (catch) va TU DONG lui ve trang Chu cai trong im lang -
        // nguoi dung thay y het nhu "bam ?123 khong co gi xay ra".
        // SUA: dung [Drawable.constantState.newDrawable] de LAY MOT BAN
        // SAO RIENG, DOC LAP cho MOI nut - vAn tai su dung chung du lieu
        // "khong doi" (mau, do day vien...) ben trong (re, nhanh, khong
        // ton bo nho nhu build lai tu dau tu GradientDrawable), nhung MOI
        // nut co [bounds]/[callback] cua RIENG minh, dung chuan cach
        // Android quy dinh khi can dung 1 Drawable cho nhieu View.
        val sharedEmojiBg = buildGlowKeyBackground(cornerDp = 4)
        val emojiBgConstantState = sharedEmojiBg.constantState
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
                background = emojiBgConstantState?.newDrawable(resources) ?: buildGlowKeyBackground(cornerDp = 4)
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
                    emojiTrackWord.clear()
                    checkEmojiSuggestion("")
                }
            }
            inner.addView(btn)
        }
        if (inner.childCount > 0) {
            registerChaseKey(KeyboardMode.NUMBERS, inner.getChildAt(0), 0.5f, 0f)
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
    /** Hàng gợi ý (autocorrect HOẶC emoji, tuỳ cái nào đang chờ) - chỉ được addView() vào layout
     *  LÚC THẬT SỰ có gợi ý (xem chỗ gọi), gỡ hẳn khỏi layout lúc không có.
     *
     *  ĐÃ REVERT (theo yêu cầu người dùng): từng có 1 fix đổi hàng này thành LUÔN có mặt với
     *  chiều cao cố định (kể cả rỗng) để hàng phím bên dưới không bị dịch chuyển lúc gợi ý bật/
     *  tắt - nhưng fix đó lại gây ra nhiều lỗi khác, nên đã phục hồi lại đúng cách làm CŨ này.
     *  Giữ nguyên tên hàm buildSuggestionSlot()/cachedSuggestionRow và tách riêng
     *  populateAutocorrectSuggestionRow()/populateEmojiSuggestionRow() (không gộp ngược lại 2 hàm
     *  buildAutocorrectSuggestionRow()/buildEmojiSuggestionRow() như trước fix) vì các fix tối ưu
     *  hiệu năng sau đó (updateSuggestionRowInPlace()...) đã dựa trên đúng cấu trúc này - gộp
     *  ngược lại sẽ phá luôn các fix đó. */
    private fun buildSuggestionSlot(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        cachedSuggestionRow = row
        when {
            pendingEmojiSuggestion != null -> populateEmojiSuggestionRow(row)
            pendingSuggestion != null -> populateAutocorrectSuggestionRow(row)
        }
        return row
    }

    /** Cập nhật trạng thái Shift/capitalize TRỰC TIẾP trên các nút đã cache - không redrawKeyboard().
     *  Chỉ đổi text label (hoa/thường) và màu highlight nút Shift. Gọi thay vì redrawKeyboard()
     *  khi chỉ cần phản ánh thay đổi isShiftOn/showCapitalPreview/capitalizeNextLetter. */
    private fun updateShiftStateInPlace() {
        // Cập nhật nút Shift: highlight hay không
        val shiftBtn = cachedShiftKey
        if (shiftBtn == null) { redrawKeyboard(); return }
        val shouldHighlight = isShiftOn || showCapitalPreview
        // Chỉ rebuild background nút Shift nếu trạng thái highlight thực sự thay đổi
        val wasHighlight = shiftBtn.tag as? Boolean ?: false
        if (wasHighlight != shouldHighlight) {
            shiftBtn.background = buildGlowKeyBackground(borderWidthDp = if (shouldHighlight) 3 else 1)
            shiftBtn.tag = shouldHighlight
        }
        // Cập nhật label các phím chữ cái (hoa/thường)
        val toUpper = isShiftOn || showCapitalPreview
        for ((ch, btn) in cachedLetterKeys) {
            val newLabel = if (toUpper) ch.uppercaseChar().toString() else ch.toString()
            if (btn.text.toString() != newLabel) btn.text = newLabel
        }
    }

    /** Cập nhật hàng gợi ý TRỰC TIẾP (không redrawKeyboard) nếu row đang tồn tại trong cây view.
     *  Chỉ clear + repopulate children của row đó - không rebuild trang QWERTY.
     *  Gọi khi gợi ý thay đổi do gõ phím bình thường (checkEmojiSuggestion). */
    private fun updateSuggestionRowInPlace() {
        val row = cachedSuggestionRow ?: run {
            // Row chưa tồn tại (chưa build trang Letters) → redraw bình thường
            redrawKeyboard()
            return
        }
        row.removeAllViews()
        when {
            pendingEmojiSuggestion != null -> populateEmojiSuggestionRow(row)
            pendingSuggestion != null -> populateAutocorrectSuggestionRow(row)
            // Rỗng: giữ row rỗng, chiều cao cố định đã đảm bảo layout không co
        }
    }

    private fun populateAutocorrectSuggestionRow(row: LinearLayout) {
        val suggestion = pendingSuggestion ?: return

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
        // THEM: dep luon goi y emoji (2 loai goi y dung CHUNG 1 hang tren
        // cung cua trang Chu cai, chi hien 1 trong 2 tai 1 thoi diem - xem
        // [buildLettersPage]) - moi diem goi ham nay TRUOC DAY (bat dau tu
        // dong go moi, xoa het, chuyen o nhap...) deu la thoi diem HOP LY de
        // dep goi y emoji cu di.
        pendingEmojiSuggestion = null
        pendingEmojiOriginalWord = null
        cancelEmojiSuggestionAutoHide()
    }

    /** THEM (theo yeu cau nguoi dung, cong tac "Goi y sua chinh ta"): kiem
     *  tra [word] (1 tu Tieng Viet vua go XONG, chu thuong, CO the co dau)
     *  co can goi y sua khong, qua [VietnameseAutocorrect]. CHi goi o CAC
     *  DIEM RANH GIOI TU (go xong 1 tu - gap dau cach/dau cau/Enter), KHONG
     *  goi sau MOI ky tu nhu [checkEmojiSuggestion] - vi tra tu dien (du da
     *  co cache) van ton vai mili-giay, goi sau MOI ky tu se lam ban phim
     *  khung nhe luc dang go (day chinh la ly do tinh nang nay TRUOC DAY bi
     *  vo hieu hoa hoan toan - xem comment o [VietnameseAutocorrect]); goi 1
     *  LAN DUY NHAT luc tu vua go xong thi chi phi khong dang ke, khong gay
     *  giat. CHi ap dung cho Tieng Viet (tu dien chi co tu Tieng Viet). */
    private fun checkAutocorrectSuggestion(word: String) {
        if (!autocorrectEnabled || !isVietnameseMode || word.isBlank()) return
        val suggestion = try {
            VietnameseAutocorrect.suggestFor(this, word)
        } catch (e: Exception) {
            null
        }
        if (suggestion != null) {
            pendingSuggestion = suggestion
            pendingSuggestionOriginalWord = word
            // 2 loai goi y dung CHUNG 1 hang (xem [buildLettersPage]) - dam
            // bao goi y emoji CU (neu co) khong con "dinh" lai nua.
            pendingEmojiSuggestion = null
            pendingEmojiOriginalWord = null
            cancelEmojiSuggestionAutoHide()
            redrawKeyboard()
        }
    }

    /** THEM: goi CHUNG 1 cho ca 2 buoc luon di kem nhau tai MOI diem RANH
     *  GIOI TU (go xong 1 tu) trong code - (1) kiem tra goi y sua chinh ta
     *  cho tu VUA go xong (truoc khi xoa dau vet cua no), roi (2) xoa
     *  [emojiTrackWord] + kiem tra lai goi y emoji cho tu MOI (rong). Thay
     *  the cho pattern "emojiTrackWord.clear(); checkEmojiSuggestion(\"\")"
     *  lap lai nhieu noi truoc day - gom lai 1 cho de KHONG bi thieu buoc
     *  kiem tra autocorrect o bat ky diem ranh gioi tu nao. */
    private fun finishWordTracking() {
        val finishedWord = emojiTrackWord.toString()
        emojiTrackWord.clear()
        checkAutocorrectSuggestion(finishedWord)
        checkEmojiSuggestion("")
        // Reset vị trí capitalize đã áp dụng: từ hiện tại đã kết thúc,
        // từ tiếp theo KHÔNG được kế thừa vị trí hoa cũ (tránh lỗi
        // "đầu mỗi từ tự động in hoa" sau khi gõ từ đầu câu).
        if (!capitalizeNextLetter) capitalizeAppliedAtPrefixLen = null
    }

    /** THEM (theo yeu cau nguoi dung, tinh nang goi y emoji): kiem tra [word]
     *  (chu thuong) co trung khop HOAN TOAN voi 1 tu khoa trong
     *  [EMOJI_TRIGGERS] khong - neu co, hien goi y; khong thi dep goi y cu
     *  (neu co) di. Goi lai sau MOI ky tu duoc go/xoa (ca 2 luong Tieng Viet
     *  va ngon ngu khac). */
    private fun checkEmojiSuggestion(word: String) {
        // THEM (theo yeu cau nguoi dung, cong tac Cai dat): tinh nang dang
        // TAT - dep sach goi y CU (neu co - vd nguoi dung vua tat trong luc
        // dang go dang) va thoat som, KHONG tra cuu/hien goi y moi nao nua.
        if (!emojiSuggestionEnabled) {
            if (pendingEmojiSuggestion != null) {
                pendingEmojiSuggestion = null
                pendingEmojiOriginalWord = null
                cancelEmojiSuggestionAutoHide()
                redrawKeyboard()
            }
            return
        }
        val emoji = EMOJI_TRIGGERS[word.lowercase()]
        if (emoji != null) {
            if (pendingEmojiSuggestion != emoji || pendingEmojiOriginalWord != word) {
                pendingEmojiSuggestion = emoji
                pendingEmojiOriginalWord = word
                pendingSuggestion = null
                pendingSuggestionOriginalWord = null
                // THEM (tu dong an sau 3s): moi lan MOT goi y MOI xuat hien
                // (khac voi goi y dang hien, hoac lan dau xuat hien), huy hen
                // gio CU (neu co) va dat hen gio MOI dem lai tu dau - dung y
                // "hien toi da 3s" tinh tu luc goi y NAY xuat hien, khong
                // phai tinh don don theo lan go phim.
                cancelEmojiSuggestionAutoHide()
                val runnable = Runnable {
                    pendingEmojiSuggestion = null
                    pendingEmojiOriginalWord = null
                    emojiSuggestionHideRunnable = null
                    updateSuggestionRowInPlace()
                }
                emojiSuggestionHideRunnable = runnable
                emojiSuggestionHideHandler.postDelayed(runnable, EMOJI_SUGGESTION_AUTO_HIDE_MS)
                updateSuggestionRowInPlace()
            }
        } else if (pendingEmojiSuggestion != null) {
            pendingEmojiSuggestion = null
            pendingEmojiOriginalWord = null
            cancelEmojiSuggestionAutoHide()
            updateSuggestionRowInPlace()
        }
    }

    /** THEM (theo yeu cau nguoi dung, ro rang: "go hihi hien icon, bam vao
     *  thanh 'hihi 😄'"): nguoi dung bam vao emoji dang duoc goi y - GIU
     *  NGUYEN chu vua go, CHi CHEN THEM emoji vao NGAY SAU (kem 1 dau cach
     *  o truoc de tach voi chu, KHONG xoa bat ky ky tu nao ca). */
    private fun acceptEmojiSuggestion() {
        val emoji = pendingEmojiSuggestion ?: return
        cancelEmojiSuggestionAutoHide()
        val ic = currentInputConnection
        if (ic != null) {
            selfInitiatedChange = true
            ic.commitText(" $emoji ", 1)
        }
        currentWord.clear(); emojiTrackWord.clear()
        currentWordCased.clear()
        clearAutocorrectSuggestion()
        redrawKeyboard()
    }

    private fun populateEmojiSuggestionRow(row: LinearLayout) {
        val emoji = pendingEmojiSuggestion ?: return

        val bg = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(Color.parseColor("#1A0F2E"))
            setStroke(dp(1), glowColor)
        }
        val suggestionBtn = Button(this).apply {
            text = "$emoji  Th\u00eam emoji n\u00e0y?"
            isAllCaps = false
            setTextColor(Color.parseColor("#D4BBFF"))
            textSize = 15f
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
                acceptEmojiSuggestion()
            }
        }
        row.addView(suggestionBtn)
        row.addView(buildKey("\u2715", weight = 1.2f) {
            pendingEmojiSuggestion = null
            pendingEmojiOriginalWord = null
            cancelEmojiSuggestionAutoHide()
            redrawKeyboard()
        })
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

        val k1 = buildKey("?123", weight = 1.4f, fillRowHeight = true) { switchMode(KeyboardMode.NUMBERS) }
        row.addView(k1)
        registerChaseKey(KeyboardMode.LETTERS, k1, 0.078f, 1f)
        val k2 = buildKey(",", weight = 1f, fillRowHeight = true) {
            insertText(",")
            finishWordTracking()
        }
        row.addView(k2)
        registerChaseKey(KeyboardMode.LETTERS, k2, 0.211f, 1f)
        row.addView(buildSpaceKey(weight = 4.2f))
        val k3 = buildKey(".", weight = 1f, fillRowHeight = true) {
            // SUA (theo yeu cau nguoi dung): TRUOC DAY tu dong bat viet hoa
            // NGAY KHI go dau "." don (chua go dau cach) - nghia la go "."
            // xong go tiep 1 chu cai (khong qua dau cach) van bi viet hoa,
            // sai voi yeu cau "sau dau cham [DON, CHUA co dau cach] thi van
            // la chu thuong". GIO DAY: dau "." CHi duoc chen binh thuong,
            // KHONG dat co viet hoa nua - co nay gio duoc dat o dung luc go
            // PHIM CACH (xem nhanh xu ly dau cach trong insertChar), CHI KHI
            // ky tu ngay truoc dau cach do THAT SU la ".".
            insertText(".")
            finishWordTracking()
        }
        row.addView(k3)
        registerChaseKey(KeyboardMode.LETTERS, k3, 0.789f, 1f)
        val k4 = buildKey("\u23ce", weight = 1.4f, highlight = true, fillRowHeight = true) { sendEnter() }
        row.addView(k4)
        registerChaseKey(KeyboardMode.LETTERS, k4, 0.922f, 1f)

        return row
    }

    private fun buildNumbersRow3(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // SUA (theo yeu cau nguoi dung): "sửa kích thước các phím từ '*' đến
        // '?' bằng kích thước các phím của hàng ký tự phía trên" (numberRows[1],
        // 10 ký tự, mỗi phím weight=1f). 7 phím ký hiệu ("*"đến"?") đã sẵn
        // weight=1f giống hàng trên rồi - vấn đề là TỔNG trọng số cả hàng
        // này (trước đây 1.3+7+1.3=9.6) KHÁC 10 (tổng của hàng trên), nên
        // dù cùng weight=1f, mỗi phím ở đây vẫn hơi RỘNG HƠN phím hàng trên
        // (chia cho tổng nhỏ hơn). Tăng 2 nút 2 bên lên 1.5 mỗi nút
        // (1.5+7+1.5=10, khớp đúng tổng hàng trên) để TỪNG phím ký hiệu có
        // kích thước bằng CHÍNH XÁC phím hàng trên.
        val eq = buildKey("=\\<", weight = 1.5f) { switchMode(KeyboardMode.SYMBOLS) }
        row.addView(eq)
        registerChaseKey(KeyboardMode.NUMBERS, eq, 0.075f, 0.75f)
        val symTotal = numberRow3Symbols.length
        numberRow3Symbols.forEachIndexed { i, ch ->
            val symKey = buildKey(ch.toString(), weight = 1f) {
                insertText(ch.toString())
                finishWordTracking()
            }
            row.addView(symKey)
            registerChaseKey(KeyboardMode.NUMBERS, symKey, (1.5f + i + 0.5f) / (1.5f + symTotal + 1.5f), 0.75f)
        }
        val bsKey = buildKey("\u232b", weight = 1.5f, onRepeat = { deleteChar() }) { deleteChar() }
        row.addView(bsKey)
        registerChaseKey(KeyboardMode.NUMBERS, bsKey, 0.925f, 0.75f)

        return row
    }

    /** Hang duoi cung cua trang so: nut "QR" - SUA theo yeu cau nguoi dung
     *  (lan 2): TRUOC DAY co ca co che phat hien dup-tap DE TINH TOAN (bien
     *  [lastQrKeyTapTime]/[QR_DOUBLE_TAP_MAX_INTERVAL_MS]) nhung KHONG con
     *  y nghia gi nua vi CA 2 nhanh (cham 1 lan hay dup-tap) DEU chi lam
     *  DUNG MOT VIEC: mo khung quet o CHE DO QUET LIEN TUC (continuous =
     *  true) - da hop nhat tu 1 lan sua truoc do. GIO DAY: bo han co che
     *  dup-tap thua nay, CHi con cham 1 lan don gian, ap dung DUNG logic cu
     *  (continuous = true) - dung y muon "giu nguyen toan bo logic cu cua
     *  nhan 2 lan, ap dat het qua nhan 1 lan". */
    /** SUA (theo yeu cau nguoi dung): kich co CAC PHIM hang duoi cung trang
     *  nay TRUOC DAY khac voi trang Chu (page 1)/trang Ky hieu (page 3) -
     *  tong trong so 9.8 (1.6/1.2/4.2/1.2/1.6) thay vi 9 (1.4/1/4.2/1/1.4)
     *  nhu 2 trang kia. GIO DAY dung DUNG kich co 1.4/1/4.2/1/1.4 giong het
     *  [buildLettersBottomRow]/[buildExtendedSymbolsBottomRow] - CHi doi
     *  kich co, GIU NGUYEN 5 phim/hanh dong cu (ABC, QR, Cach, 123, Enter). */
    private fun buildNumbersBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(keyHeightDp + 2)
            ).apply { bottomMargin = dp(6) }
        }

        val nb1 = buildKey("ABC", weight = 1.4f, fillRowHeight = true) { switchMode(KeyboardMode.LETTERS) }
        row.addView(nb1)
        registerChaseKey(KeyboardMode.NUMBERS, nb1, 0.078f, 1f)
        val nb2 = buildKey("QR", weight = 1f, highlight = true, fillRowHeight = true) {
            openQrScanner(continuous = true)
        }
        row.addView(nb2)
        registerChaseKey(KeyboardMode.NUMBERS, nb2, 0.211f, 1f)
        row.addView(buildSpaceKey(weight = 4.2f))
        val nb3 = buildKey("123", weight = 1f, fillRowHeight = true) { switchMode(KeyboardMode.NUMPAD) }
        row.addView(nb3)
        registerChaseKey(KeyboardMode.NUMBERS, nb3, 0.789f, 1f)
        val nb4 = buildKey("\u23ce", weight = 1.4f, highlight = true, fillRowHeight = true) { sendEnter() }
        row.addView(nb4)
        registerChaseKey(KeyboardMode.NUMBERS, nb4, 0.922f, 1f)

        return row
    }

    private fun buildExtendedSymbolsRow3(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val sq1 = buildKey("?123", weight = 1.3f) { switchMode(KeyboardMode.NUMBERS) }
        row.addView(sq1)
        registerChaseKey(KeyboardMode.SYMBOLS, sq1, 0.061f, 0.75f)
        val symTotal2 = extendedSymbolRow3.length
        val totalW2 = 1.3f + symTotal2 + 1.3f
        extendedSymbolRow3.forEachIndexed { i, ch ->
            val symKey = buildKey(ch.toString(), weight = 1f) {
                insertText(ch.toString())
                finishWordTracking()
            }
            row.addView(symKey)
            registerChaseKey(KeyboardMode.SYMBOLS, symKey, (1.3f + i + 0.5f) / totalW2, 0.75f)
        }
        val sq2 = buildKey("\u232b", weight = 1.3f, onRepeat = { deleteChar() }) { deleteChar() }
        row.addView(sq2)
        registerChaseKey(KeyboardMode.SYMBOLS, sq2, 1f - 0.061f, 0.75f)

        return row
    }

    /** SUA (theo yeu cau nguoi dung): "làm cho nguyên hàng dấu cách giống
     *  hệt như trang 1 nhưng thay vì nút ',' và '.' thì thành '<' và '>'" -
     *  TRUOC DAY hang nay chi co ABC + Cach + Enter (khong co phim nao 2
     *  ben dau cach ca). GIO DAY dung dung cau truc 5-phan cua
     *  [buildLettersBottomRow] (ABC/"?123" + dau + Cach + dau + Enter, cung
     *  tong trong so = 9), nhung dung "<" va ">" thay cho ","/".". "<" và
     *  ">" TRUOC DAY nam o dau extendedSymbolRows[1] (hang 2 cua trang nay)
     *  - da CHUYEN xuong day, nhuong lai vi tri cu cho "£"/"€" (xem
     *  extendedSymbolRows). */
    private fun buildExtendedSymbolsBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(keyHeightDp + 2)
            ).apply { bottomMargin = dp(6) }
        }

        val sb1 = buildKey("ABC", weight = 1.4f, fillRowHeight = true) { switchMode(KeyboardMode.LETTERS) }
        row.addView(sb1)
        registerChaseKey(KeyboardMode.SYMBOLS, sb1, 0.078f, 1f)
        val sb2 = buildKey("<", weight = 1f, fillRowHeight = true) {
            insertText("<")
            finishWordTracking()
        }
        row.addView(sb2)
        registerChaseKey(KeyboardMode.SYMBOLS, sb2, 0.211f, 1f)
        row.addView(buildSpaceKey(weight = 4.2f))
        val sb3 = buildKey(">", weight = 1f, fillRowHeight = true) {
            insertText(">")
            finishWordTracking()
        }
        row.addView(sb3)
        registerChaseKey(KeyboardMode.SYMBOLS, sb3, 0.789f, 1f)
        val sb4 = buildKey("\u23ce", weight = 1.4f, highlight = true, fillRowHeight = true) { sendEnter() }
        row.addView(sb4)
        registerChaseKey(KeyboardMode.SYMBOLS, sb4, 0.922f, 1f)

        return row
    }

    /** "Thanh cai dat" mau vien + nen sang/toi cho toan bo ban phim - hien
     *  san tren trang Ky hieu mo rong, khong can nut bat/tat rieng.
     *
     *  SUA (theo yeu cau nguoi dung "đưa phần cài đặt màu sắc của bàn phím
     *  vào mục màu sắc trong phần cài đặt"): TRUOC DAY ham nay ve nguyen 1
     *  thanh cuon ngang gom 8 o vuong chon mau + 1 nut tron doi sang/toi NGAY
     *  TAI DAY. GIO DAY: toan bo phan chon mau da CHUYEN HAN sang man Cai dat
     *  rieng (xem SettingsActivity.kt, muc "Mau sac") - ham nay chi con ve 1
     *  NUT DUY NHAT de mo man do len (dung Intent + FLAG_ACTIVITY_NEW_TASK vi
     *  goi tu Context cua 1 Service, khong phai Activity). */
    private fun buildKeyboardSettingsBar(): View {
        val btn = Button(this).apply {
            text = "\u2699\ufe0f  C\u00e0i \u0111\u1eb7t"
            isAllCaps = false
            textSize = 14f
            setTextColor(if (isDarkTheme) Color.WHITE else Color.BLACK)
            stateListAnimator = null
            elevation = 0f
            outlineProvider = null
            background = buildGlowKeyBackground(cornerDp = 10, borderColor = glowColor, borderWidthDp = 2)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(keyHeightDp)
            ).apply { setMargins(dp(4), dp(3), dp(4), dp(6)) }
            isHapticFeedbackEnabled = true
            setOnClickListener {
                vibrateKeyPress()
                playKeyClickTone()
                try {
                    startActivity(Intent(this@QrKeyboardService, SettingsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        // THEM (theo yeu cau nguoi dung): bao cho SettingsActivity
                        // biet la dang mo TU BEN TRONG ban phim (nut "Cai dat")
                        // - KHONG duoc tu dong mo "trang chon ban phim he thong"
                        // trong truong hop nay (khac voi mo tu icon app tren man
                        // hinh chinh). Neu nguoi dung dang go duoc bang CHINH ban
                        // phim nay de bam vao nut do, ro rang no DA duoc bat/dang
                        // active roi - kiem tra lai la thua va gay phien.
                        putExtra(SettingsActivity.EXTRA_SKIP_KEYBOARD_CHECK, true)
                    })
                } catch (e: Exception) {
                    android.util.Log.w("QrKeyboardService", "Khong mo duoc SettingsActivity: ${e.message}")
                }
            }
        }
        // THEM (theo yeu cau nguoi dung, tinh nang nhap lieu bang giong noi):
        // nut hinh micro, DUNG HANG voi nut "Cai dat" o tren, canh SAT BEN
        // PHAI CUNG cua hang (dung 1 View "dem" co trong so (weight) = 1f de
        // day no ra sat le phai, xem [addView] ben duoi).
        val micBtn = Button(this).apply {
            // SUA (CHi doi PHAN NAY - icon, KHONG dung gi den padding/
            // margins/background/vien ben duoi): thay "text = emoji" bang 1
            // icon tu ve qua [MicIconDrawable], gan vao vi tri "top" cua
            // compound drawable rong (khong text) - nut van giu NGUYEN kich
            // thuoc/vien/nen nhu truoc, chi thay THU BEN TRONG hien thi.
            text = ""
            val iconSize = dp(20)
            val micIcon = MicIconDrawable(if (isDarkTheme) Color.WHITE else Color.BLACK, listening = false, sizePx = iconSize)
            micIcon.setBounds(0, 0, iconSize, iconSize)
            setCompoundDrawables(null, micIcon, null, null)
            setTextColor(if (isDarkTheme) Color.WHITE else Color.BLACK)
            stateListAnimator = null
            elevation = 0f
            outlineProvider = null
            background = buildGlowKeyBackground(cornerDp = 10, borderColor = glowColor, borderWidthDp = 2)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(keyHeightDp)
            ).apply { setMargins(dp(4), dp(3), dp(4), dp(6)) }
            isHapticFeedbackEnabled = true
            contentDescription = "Nh\u1eadp li\u1ec7u b\u1eb1ng gi\u1ecdng n\u00f3i"
            setOnClickListener {
                vibrateKeyPress()
                playKeyClickTone()
                startVoiceInput()
            }
        }
        // THEM: giu tham chieu nut Mic de co the tu cap nhat icon/mau ngay
        // luc dang nghe (dang "listening") ma KHONG can ve lai (redraw) ca
        // trang - trang Ky hieu (noi chua nut nay) duoc CACHE, hiem khi ve
        // lai, nen cap nhat truc tiep field nay la cach nhanh + on dinh
        // nhat (xem [updateMicButtonUi]).
        micButtonRef = micBtn
        registerChaseKey(KeyboardMode.SYMBOLS, btn, 0.1f, 1f)
        registerChaseKey(KeyboardMode.SYMBOLS, micBtn, 0.9f, 1f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(btn)
            addView(View(this@QrKeyboardService).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
            addView(micBtn)
        }
    }

    /** THEM (theo yeu cau nguoi dung "mic phải dùng mic riêng"): bam nut Mic.
     *  Neu dang KHONG nghe -> kiem tra quyen RECORD_AUDIO, xin neu chua co,
     *  roi bat dau nghe truc tiep bang [android.speech.SpeechRecognizer] cua
     *  CHINH app (khong con mo popup cua app khac). Neu DANG nghe (bam lai
     *  nut lan 2) -> dung nghe SOM (nguoi dung muon ket thuc ngay, khong
     *  doi du 2s im lang). */
    private fun startVoiceInput() {
        if (isListeningForVoice) {
            stopListeningForVoice(cancel = false)
            return
        }
        val hasPermission = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            onMicPermissionResult = { granted ->
                if (granted) beginListeningForVoice() else {
                    Toast.makeText(this, "Ch\u01b0a c\u1EA5p quy\u1ec1n Mic", Toast.LENGTH_SHORT).show()
                }
            }
            try {
                startActivity(Intent(this, MicPermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                onMicPermissionResult = null
                Toast.makeText(this, "Kh\u00f4ng xin \u0111\u01b0\u1EE3c quy\u1ec1n Mic", Toast.LENGTH_SHORT).show()
            }
            return
        }
        beginListeningForVoice()
    }

    /** Bat dau nghe THAT SU (da chac chan co quyen RECORD_AUDIO). Neu may
     *  KHONG co san dich vu nhan dien giong noi nao ca (hiem, vd ROM tuy
     *  bien da go het/khong cai Google), dung LAI phuong an cu (mo popup he
     *  thong qua [VoiceInputActivity]) thay vi de nguoi dung khong dung
     *  duoc gi ca - "mic riêng" la MAC DINH/uu tien, khong phai TUYET DOI
     *  duy nhat, van can 1 duong lui an toan. */
    private fun beginListeningForVoice() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            startVoiceInputViaSystemPopup()
            return
        }
        try {
            val recognizer = speechRecognizer
                ?: android.speech.SpeechRecognizer.createSpeechRecognizer(this).also { speechRecognizer = it }
            recognizer.setRecognitionListener(voiceRecognitionListener)
            val locale = VoiceInputActivity.localeForLangCode(activeLangCode)
            val recognizeIntent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, locale)
                putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                // SUA (theo yeu cau nguoi dung "dừng 1,2s thôi; 2s lâu
                // quá"): giam thoi gian im lang toi da truoc khi TU DONG coi
                // la noi xong tu 2000ms xuong 1200ms - dung nghe (va tra ket
                // qua) nhanh hon dang ke sau khi nguoi dung ngung noi.
                putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            isListeningForVoice = true
            updateMicButtonUi()
            recognizer.startListening(recognizeIntent)
        } catch (e: Exception) {
            android.util.Log.e("QrKeyboardService", "Loi khi bat dau nghe giong noi: ${e.message}", e)
            isListeningForVoice = false
            updateMicButtonUi()
            Toast.makeText(this, "Kh\u00f4ng m\u1edf \u0111\u01b0\u1EE3c Mic", Toast.LENGTH_SHORT).show()
        }
    }

    /** Dung nghe. [cancel] = true nghia la HUY hoan toan (khong lay ket qua
     *  du dang - vd loi/nguoi dung tat ban phim giua chung); false nghia la
     *  nguoi dung CHU DONG bam dung SOM, muon lay ket qua nhung gi da nghe
     *  duoc TOI THOI DIEM do (SpeechRecognizer.stopListening() se tu goi
     *  onResults voi ket qua tot nhat co duoc). */
    private fun stopListeningForVoice(cancel: Boolean) {
        try {
            if (cancel) speechRecognizer?.cancel() else speechRecognizer?.stopListening()
        } catch (e: Exception) {
            // Bo qua - hiem gap (vd recognizer da o trang thai loi san).
        }
        isListeningForVoice = false
        updateMicButtonUi()
    }

    /** Cap nhat rieng icon/mau nut Mic theo trang thai dang nghe hay khong -
     *  KHONG can ve lai (redraw) ca ban phim, chi doi truc tiep tren [micButtonRef]
     *  (an toan vi la 1 View da duoc gan vao cay - Android tu ve lai phan
     *  thay doi o lan draw pass tiep theo). */
    private fun updateMicButtonUi() {
        val btn = micButtonRef ?: return
        try {
            val iconSize = dp(20)
            if (isListeningForVoice) {
                // SUA (CHi doi PHAN ICON, dong background/vien mau do PHIA
                // DUOI giu NGUYEN nhu truoc, khong dung toi):
                val stopIcon = MicIconDrawable(Color.WHITE, listening = true, sizePx = iconSize)
                stopIcon.setBounds(0, 0, iconSize, iconSize)
                btn.setCompoundDrawables(null, stopIcon, null, null)
                btn.contentDescription = "D\u1eebng nghe (\u0111ang ghi \u00e2m)"
                btn.background = buildGlowKeyBackground(cornerDp = 10, borderColor = Color.parseColor("#FF4B4B"), borderWidthDp = 2)
            } else {
                val micIcon = MicIconDrawable(if (isDarkTheme) Color.WHITE else Color.BLACK, listening = false, sizePx = iconSize)
                micIcon.setBounds(0, 0, iconSize, iconSize)
                btn.setCompoundDrawables(null, micIcon, null, null)
                btn.contentDescription = "Nh\u1eadp li\u1ec7u b\u1eb1ng gi\u1ecdng n\u00f3i"
                btn.background = buildGlowKeyBackground(cornerDp = 10, borderColor = glowColor, borderWidthDp = 2)
            }
        } catch (e: Exception) {
            // Bo qua - hiem gap (vd View da bi thao khoi cay dung luc nay).
        }
    }

    /** Lang nghe ket qua tu [android.speech.SpeechRecognizer] - tao 1 LAN
     *  DUY NHAT, dung lai cho moi lan nghe (khong tao moi lien tuc). */
    /** THEM: goi tu [onFinishInputView] - dung nghe (kieu HUY, khong lay ket
     *  qua du dang) neu dang mo Mic luc ban phim bi an di. Khac voi
     *  [stopListeningForVoice(cancel=false)] (nguoi dung CHU DONG bam dung -
     *  van muon lay ket qua), o day ban phim dang bi an NGOAI Y MUON nguoi
     *  dung dang noi (vd bi che khuat, chuyen app) - huy la hop ly hon la
     *  co lay 1 ket qua co the sai/thieu ngu canh. */
    private fun stopVoiceInputForHide() {
        if (!isListeningForVoice) return
        stopListeningForVoice(cancel = true)
    }

    private val voiceRecognitionListener = object : android.speech.RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            isListeningForVoice = false
            updateMicButtonUi()
            // SUA: bo qua rieng ERROR_NO_MATCH (khong nhan dien duoc gi -
            // thuong do nguoi dung im lang/noi qua nho) va ERROR_SPEECH_TIMEOUT
            // - KHONG hien Toast cho 2 loi nay, vi day la tinh huong BINH
            // THUONG (nguoi dung bam Mic roi doi ma khong noi gi, hoac tu
            // huy) chu khong phai LOI THAT SU, hien Toast se gay phien.
            val code = error
            if (code == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                code == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                return
            }
            val message = when (code) {
                android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Ch\u01b0a c\u1EA5p quy\u1ec1n Mic"
                android.speech.SpeechRecognizer.ERROR_NETWORK,
                android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "L\u1ed7i m\u1EA1ng, ki\u1ec3m tra k\u1EBFt n\u1ed1i"
                android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mic \u0111ang b\u1eadn, th\u1eED l\u1EA1i sau"
                else -> "Kh\u00f4ng nh\u1eadn di\u1ec7n \u0111\u01b0\u1EE3c gi\u1ecdng n\u00f3i"
            }
            Toast.makeText(this@QrKeyboardService, message, Toast.LENGTH_SHORT).show()
        }

        override fun onResults(results: Bundle?) {
            isListeningForVoice = false
            updateMicButtonUi()
            val text = results
                ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
            if (text != null) insertRecognizedVoiceText(text)
        }
    }

    /** Duong lui: mo popup nhan dien giong noi CUA HE THONG (Google/tro ly
     *  ao mac dinh) qua [VoiceInputActivity] - CHi dung khi may KHONG co san
     *  bat ky dich vu SpeechRecognizer noi bo nao (xem [beginListeningForVoice]). */
    private fun startVoiceInputViaSystemPopup() {
        onVoiceInputResult = { text ->
            if (!text.isNullOrBlank()) insertRecognizedVoiceText(text)
        }
        try {
            val locale = VoiceInputActivity.localeForLangCode(activeLangCode)
            startActivity(Intent(this, VoiceInputActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(VoiceInputActivity.EXTRA_LOCALE, locale)
            })
        } catch (e: Exception) {
            onVoiceInputResult = null
            Toast.makeText(this, "Kh\u00f4ng m\u1edf \u0111\u01b0\u1ee3c nh\u1eadp li\u1ec7u b\u1eb1ng gi\u1ecdng n\u00f3i", Toast.LENGTH_SHORT).show()
        }
    }

    /** THEM: chen van ban da nhan dien tu giong noi vao vi tri con tro.
     *  Chen NGUYEN VAN qua commitText (KHONG di qua tung ky tu cua bo xu ly
     *  Telex nhu insertVietnameseChar) - vi day la 1 cau/cum tu DA HOAN
     *  CHINH tu he thong nhan dien (da co san dau cau tieng Viet neu co), di
     *  qua Telex tung ky tu se lam SAI/mat dau. Tu dong them 1 dau cach phia
     *  truoc neu ngay truoc con tro dang co san 1 ky tu KHONG PHAI khoang
     *  trang (tranh dinh lien vao tu truoc do). */
    private fun insertRecognizedVoiceText(text: String) {
        val ic = currentInputConnection ?: return
        currentWord.clear(); emojiTrackWord.clear()
        currentWordCased.clear()
        val before = ic.getTextBeforeCursor(1, 0)?.toString()
        val needsLeadingSpace = !before.isNullOrEmpty() && !before.last().isWhitespace()
        selfInitiatedChange = true
        ic.commitText(if (needsLeadingSpace) " $text" else text, 1)
    }

    /** Phim cach: chuc nang chinh la chen dau cach khi CHAM binh thuong.
     *  Neu ngon tay VUOT ngang qua nguong [SPACE_SWIPE_THRESHOLD_DP] truoc
     *  khi tha ra, xem la mot cu vuot doi ngon ngu thay vi mot cai cham. */
    private fun buildSpaceKey(weight: Float, chasePage: KeyboardMode = mode): View {
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

        val lang2Now = lang2
        val vLabel = TextView(this).apply {
            text = LanguagePrefs.shortLabel(lang1).take(1)
            textSize = 12f
            // SUA (che do 1 ngon ngu): neu chi dung 1 ngon ngu (lang2==null),
            // nhan ben trai LUON to sang (dai dien cho ngon ngu DUY NHAT dang
            // dung), khong phu thuoc [activeIsLang1] nua.
            setTextColor(edgeColor(activeIsLang1 || lang2Now == null))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START
            ).apply { setMargins(dp(10), 0, 0, 0) }
        }
        val eLabel = TextView(this).apply {
            text = LanguagePrefs.shortLabel(lang2Now ?: "").take(1)
            textSize = 12f
            setTextColor(edgeColor(!activeIsLang1))
            // SUA (che do 1 ngon ngu): AN HAN nhan ben phai neu khong co ngon
            // ngu thu 2 - khong con gi de hien, tranh gay hieu lam "co the
            // vuot doi ngon ngu" khi thuc ra khong the.
            visibility = if (lang2Now == null) View.INVISIBLE else View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.END
            ).apply { setMargins(0, 0, dp(10), 0) }
        }
        val centerLabel = TextView(this).apply {
            // SUA (che do 1 ngon ngu): khong can hien ten ngon ngu o giua nua
            // (chi co 1 lua chon duy nhat, khong co gi de phan biet/doi) -
            // chi hien bieu tuong dau cach don gian cho gon.
            text = if (lang2Now == null) "\u2423" else "\u2423 " + LanguagePrefs.shortLabel(activeLangCode)
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
            // SUA (cung ly do bao ve nhu trong [buildKey]): phim Cach cung
            // goi InputConnection/redrawKeyboard - bao ve tuong tu de 1 loi
            // hiem gap o day khong lam sap ca ban phim.
            try {
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
                        setLanguageMode(useLang1 = deltaX < 0)
                    } else {
                        insertChar(' ')
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
            } catch (e: Exception) {
                android.util.Log.e("QrKeyboardService", "Loi khi xu ly phim Cach: ${e.message}", e)
                true
            }
        }

        registerChaseKey(chasePage, container, 0.5f, 1f)
        return container
    }

    private fun setLanguageMode(useLang1: Boolean) {
        // SUA (che do 1 ngon ngu): khong co gi de doi qua neu [lang2] la
        // null - vuot phim cach se KHONG lam gi ca thay vi doi nham ve
        // chinh [lang1] (truong hop useLang1=false nhung lang2 null).
        if (lang2 == null) return
        if (activeIsLang1 == useLang1) return
        activeIsLang1 = useLang1
        currentWord.clear(); emojiTrackWord.clear()
        val label = LanguagePrefs.displayName(activeLangCode)
        Toast.makeText(this, "G\u00f5 $label", Toast.LENGTH_SHORT).show()
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
                // SUA (theo yeu cau nguoi dung "hình mũi tên Enter nhỏ và
                // nằm sát cạnh dưới của nút"): ky tu Enter TRUOC DAY la
                // "\u21b5" (mui ten co moc, U+21B5) - font mac dinh Android
                // (Roboto/Noto) VE ky tu nay LECH XUONG DUOI trong khung chu
                // (glyph "nam thap", nhieu khoang trong PHIA TREN no trong
                // em-box) - du gravity=CENTER can giua dung khung VAN BAN
                // (text baseline box), khung do lai KHONG khop voi phan
                // "muc" thuc su cua glyph, nen mat thuong thay ky tu bi day
                // sat xuong day nut va nho hon cam giac. GIO DAY doi sang
                // "\u23ce" (bieu tuong Enter/Return chuan, U+23CE) - glyph
                // nay duoc ve DAY VA CAN GIUA hon han trong khung chu cua
                // hau het font he thong, khac phuc dung van de nguoi dung
                // mo ta.
                label == "\u23ce" || label == "\u2b06" -> 28f
                label.length > 3 -> 11f
                label.length > 1 -> 13f
                else -> 16f
            }
            isSingleLine = true
            // SUA (theo yeu cau nguoi dung "làm nút này tương tự" - ap dung
            // cho phim Shift "⬆", U+2B06 - cung nguyen nhan y het phim Enter
            // o tren): TAT includeFontPadding cho CA 2 ky hieu mui ten don
            // le nay ("⏎" va "⬆") - deu bi khoang đệm font (danh cho
            // dau/moc cua chu co dau) day lech xuong duoi tam nut.
            includeFontPadding = label != "\u23ce" && label != "\u2b06"
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
        // THEM (tang do nhay cham phim): vi tri dat tay xuong ban dau + co
        // "da xe dich qua nguong" chua, dung de tu quan ly viec cham co tinh
        // la mot cu bam hop le hay khong (xem [KEY_TAP_MOVE_TOLERANCE_DP]).
        var downX = 0f
        var downY = 0f
        var movedTooFar = false
        val tapMoveTolerancePx = dp(KEY_TAP_MOVE_TOLERANCE_DP).toFloat()
        fun cancelPendingTimers() {
            hideKeyPreview()
            repeatRunnable?.let {
                deleteRepeatHandler.removeCallbacks(it)
                if (activeDeleteRepeatRunnable === it) activeDeleteRepeatRunnable = null
            }
            repeatRunnable = null
            accentLongPressRunnable?.let { accentLongPressHandler.removeCallbacks(it) }
            accentLongPressRunnable = null
        }
        // THEM: danh sach bien the co dau cho phim NAY (chi tinh 1 LAN luc
        // xay phim, khong doi trong luc ban phim dang hien - neu nguoi dung
        // doi ngon ngu giua chung, phim se duoc XAY LAI hoan toan qua co che
        // dong bo co san [onWindowShown] nen van luon dung).
        val accentVariants = if (label.length == 1 && label[0].isLetter()) accentVariantsFor(label[0]) else emptyList()
        button.setOnTouchListener { v, event ->
            // SUA (theo yeu cau nguoi dung "luu luu no an xuong chi con 1
            // day den phia duoi cung"): BOC try/catch TOAN BO xu ly cham
            // phim. Day la noi CHAY THUONG XUYEN NHAT trong toan bo ban
            // phim (moi lan cham/nha/keo tren BAT KY phim nao) va goi vao
            // rat nhieu ham khac (popup xem-truoc, popup chon dau, rung,
            // am thanh, InputConnection de chen/xoa chu...) - TRUOC DAY 1
            // loi bat ky (vi du InputConnection vua bi ung dung dang go
            // NGAT ket noi dung luc ngon tay cham xuong, hoac WindowManager
            // tu choi hien popup vi cua so IME dang trong qua trinh dong)
            // se KHONG duoc bat, lam CRASH ca tien trinh ban phim NGAY GIUA
            // luc go - day chinh la nguyen nhan giao dien "chi con 1 day
            // den o duoi" (man hinh du phong cua he thong khi 1 IME crash)
            // ma nguoi dung mo ta, xay ra "lau lau" vi chi trung dung luc co
            // dieu kien loi hiem gap nay. SUA: bat loi, ghi log, COI NHU
            // cu cham nay khong lam gi ca (tra ve true de van "tieu thu"
            // duoc su kien, tranh cac hanh vi bat ngo khac) thay vi de sap
            // ca ban phim.
            try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    vibrateKeyPress()
                    playKeyClickTone()
                    val now = android.os.SystemClock.uptimeMillis()
                    val isFastTyping = (now - lastKeyDownTimestamp) < FAST_TYPING_THRESHOLD_MS
                    lastKeyDownTimestamp = now
                    if (label.length == 1 && !isFastTyping) showKeyPreview(v, label)
                    repeatTriggered = false
                    movedTooFar = false
                    downX = event.rawX
                    downY = event.rawY
                    v.isPressed = true
                    if (onRepeat != null) {
                        val runnable = object : Runnable {
                            override fun run() {
                                repeatTriggered = true
                                onRepeat.invoke()
                                // SUA LOI: chi tiep tuc hen gio lan xoa ke
                                // tiep NEU runnable nay VAN con la runnable
                                // "dang hoat dong" hien tai (xem
                                // [activeDeleteRepeatRunnable]) - neu
                                // [onRepeat.invoke()] ben tren (deleteChar)
                                // da lam ban phim bi VE LAI giua chung
                                // (redrawKeyboard huy no va dat null), thi
                                // DUNG lai ngay, khong tu hoi sinh lai vong
                                // lap xoa nua.
                                if (activeDeleteRepeatRunnable === this) {
                                    deleteRepeatHandler.postDelayed(this, DELETE_REPEAT_INTERVAL_MS)
                                }
                            }
                        }
                        repeatRunnable = runnable
                        activeDeleteRepeatRunnable = runnable
                        deleteRepeatHandler.postDelayed(runnable, DELETE_REPEAT_INITIAL_DELAY_MS)
                    }
                    // THEM: neu phim nay CO bien the dau (dung ngon ngu dang
                    // active, KHONG phai "vi"/"en" - xem [accentVariantsFor]),
                    // hen gio hien popup chon dau sau [ACCENT_LONG_PRESS_MS]
                    // neu ngon tay VAN con giu (chua nha/di chuyen ra ngoai).
                    if (accentVariants.isNotEmpty()) {
                        val runnable = Runnable {
                            hideKeyPreview()
                            showAccentPopup(v, label[0], accentVariants, label[0].isUpperCase())
                        }
                        accentLongPressRunnable = runnable
                        accentLongPressHandler.postDelayed(runnable, ACCENT_LONG_PRESS_MS)
                    }
                    // SUA (tang do nhay): tu quan ly toan bo cu cham tu day
                    // (return true) thay vi de mac Android tu xu ly voi
                    // nguong xe dich rat nho mac dinh - xem ghi chu tai
                    // [KEY_TAP_MOVE_TOLERANCE_DP].
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (accentPopupShowing) {
                        updateAccentPopupSelection(event.rawX)
                        return@setOnTouchListener true
                    }
                    if (!movedTooFar) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                        if (distance > tapMoveTolerancePx) {
                            // Ngon tay DA xe dich vuot qua nguong RONG (xem
                            // [KEY_TAP_MOVE_TOLERANCE_DP]) - luc nay moi thuc
                            // su coi la mot cu keo/vuot ra khoi phim (khong
                            // phai mot cu cham hoi rung tay), nen huy cac hen
                            // gio xoa lap lai/popup dau dang cho, giong het
                            // ACTION_CANCEL.
                            movedTooFar = true
                            v.isPressed = false
                            cancelPendingTimers()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    cancelPendingTimers()
                    if (accentPopupShowing) {
                        // Nguoi dung DA giu du lau de hien popup - nha tay ra
                        // la CHOT lua chon dang to sang (KHONG chen chu cai
                        // goc qua duong onClick binh thuong nua - consume
                        // (tra ve true) de ngan onClick tu dong chay tiep,
                        // tranh chen 2 lan/sai ky tu).
                        commitAccentSelectionAndDismiss()
                        return@setOnTouchListener true
                    }
                    if (repeatTriggered || movedTooFar) {
                        return@setOnTouchListener true
                    }
                    // SUA (tang do nhay): tu goi performClick() thay vi dua
                    // vao co che click mac dinh cua Android (da bi tat qua
                    // viec return true o ACTION_DOWN/MOVE o tren) - day la
                    // duong duy nhat kich hoat [onClick] ben duoi tu gio.
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    cancelPendingTimers()
                    if (accentPopupShowing) dismissAccentPopup()
                    true
                }
                else -> false
            }
            } catch (e: Exception) {
                // Xem giai thich day du o dau setOnTouchListener: BAT loi de
                // KHONG lam sap ca tien trinh ban phim giua luc go. Don dep
                // het cac hen gio dang cho (tranh vong lap xoa/popup ket
                // dinh mai neu loi xay ra GIUA chung mot cu cham) roi coi
                // nhu cu cham nay khong lam gi ca.
                android.util.Log.e("QrKeyboardService", "Loi khi xu ly cham phim '$label': ${e.message}", e)
                try {
                    v.isPressed = false
                    cancelPendingTimers()
                } catch (e2: Exception) { /* danh chiu */ }
                true
            }
        }
        button.setOnClickListener {
            // SUA (cung ly do o setOnTouchListener o tren): [onClick] goi
            // thang vao logic chen/xoa ky tu qua InputConnection cua ung
            // dung dang go - ket noi nay co the vua bi ung dung NGAT dung
            // luc ngon tay nha ra (vd nguoi dung vua chuyen app/o nhap that
            // nhanh), gay loi neu KHONG duoc bat.
            try {
                onClick()
            } catch (e: Exception) {
                android.util.Log.e("QrKeyboardService", "Loi khi xu ly bam phim '$label': ${e.message}", e)
            }
        }
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

    /** THEM: tra ve danh sach ky tu co dau cho chu cai GOC [ch] (khong phan
     *  biet hoa/thuong khi tra bang), theo DUNG ngon ngu DANG active tren
     *  ban phim luc goi ham. Tra ve list RONG (khong co popup) neu:
     *  - Ngon ngu dang active la "vi" hoac "en" (theo dung yeu cau nguoi
     *    dung "khong ap dung no voi ban phim Anh-Viet hien tai").
     *  - Chu cai do khong co bien the dau nao trong ngon ngu dang active. */
    private fun accentVariantsFor(ch: Char): List<Char> {
        if (activeLangCode == "vi" || activeLangCode == "en") return emptyList()
        return LanguagePrefs.ACCENT_VARIANTS[activeLangCode]?.get(ch.lowercaseChar()) ?: emptyList()
    }

    /** Hien popup chon dau ngay phia tren [anchor] - hang ngang gom chu cai
     *  GOC (dau tien) roi den cac bien the co dau. [isUpper]: hien TAT CA
     *  lua chon o dang HOA neu dung dang go phim SHIFT/viet hoa dau cau, de
     *  khop voi case dang go. */
    private fun showAccentPopup(anchor: View, baseChar: Char, variants: List<Char>, isUpper: Boolean) {
        val options = (listOf(baseChar) + variants).map { if (isUpper) it.uppercaseChar() else it }
        accentPopupOptions = options
        accentPopupSelectedIndex = 0

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#3C4043"))
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        options.forEachIndexed { i, c ->
            row.addView(TextView(this).apply {
                text = c.toString()
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                if (i == 0) {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(Color.parseColor("#5F6368"))
                    }
                }
            })
        }
        accentPopupRow = row

        val popup = PopupWindow(
            row, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false
        ).apply { isClippingEnabled = false }
        accentPopup = popup

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        row.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val x = loc[0] + anchor.width / 2 - row.measuredWidth / 2
        val y = loc[1] - row.measuredHeight - dp(4)
        try {
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
            accentPopupShowing = true
        } catch (e: Exception) {
            // Bo qua neu window chua san sang de hien popup (hiem gap) -
            // coi nhu KHONG hien popup, phim se roi ve hanh vi go binh
            // thuong (chu cai goc) khi nha tay ra.
            accentPopupShowing = false
        }
    }

    /** Cap nhat lua chon dang duoc "to sang" trong popup dua theo toa do X
     *  TUYET DOI cua ngon tay tren man hinh (rawX) - goi lien tuc trong luc
     *  ngon tay con di chuyen (ACTION_MOVE) de mo phong dung kieu "vuot chon
     *  dau" quen thuoc cua Gboard/ban phim Android chuan. */
    private fun updateAccentPopupSelection(rawX: Float) {
        val row = accentPopupRow ?: return
        if (row.childCount == 0) return
        val loc = IntArray(2)
        row.getLocationOnScreen(loc)
        val relativeX = rawX - loc[0]
        val childWidth = if (row.childCount > 0) row.width.toFloat() / row.childCount else 1f
        if (childWidth <= 0f) return
        var idx = (relativeX / childWidth).toInt().coerceIn(0, row.childCount - 1)
        if (idx == accentPopupSelectedIndex) return
        accentPopupSelectedIndex = idx
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i) as? TextView ?: continue
            child.background = if (i == idx) {
                GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(Color.parseColor("#5F6368"))
                }
            } else null
        }
    }

    /** Chen ky tu dang duoc chon (to sang) trong popup vao vi tri con tro,
     *  roi dong popup lai. Goi khi nguoi dung nha tay ra ([ACTION_UP]) TRONG
     *  luc popup dang hien. */
    private fun commitAccentSelectionAndDismiss() {
        val options = accentPopupOptions
        val idx = accentPopupSelectedIndex.coerceIn(0, options.size - 1).takeIf { options.isNotEmpty() }
        dismissAccentPopup()
        if (idx != null) {
            insertChar(options[idx])
        }
    }

    private fun dismissAccentPopup() {
        try {
            accentPopup?.let { if (it.isShowing) it.dismiss() }
        } catch (e: Exception) {
            // Bo qua.
        }
        accentPopup = null
        accentPopupRow = null
        accentPopupOptions = emptyList()
        accentPopupShowing = false
    }

    private fun insertChar(ch: Char) {
        if (isVietnameseMode && ch.isLetter()) {
            insertVietnameseChar(ch)
            return
        }
        // SUA (theo yeu cau nguoi dung): tu dong viet hoa chu cai tiep theo
        // CHi khi go PHIM CACH va ky tu THAT SU ngay truoc con tro la "."
        // (tuc la nguoi dung vua go dung trinh tu ". " - dau cham roi dau
        // cach) - KHONG con bat viet hoa ngay khi go dau "." don nua (xem
        // buildLettersBottomRow). Neu giua dau "." va dau cach nguoi dung
        // go them ky tu KHAC (khong phai dau cach ngay lap tuc), ky tu ngay
        // truoc con tro se KHONG CON la "." nua, dieu kien nay tu dong
        // KHONG kich hoat - dung y muon "sau dau cham don thi van la chu
        // thuong, phai co dau cach theo sau moi tu dong viet hoa".
        if (ch == ' ') {
            val charBefore = currentInputConnection?.getTextBeforeCursor(1, 0)
            if (charBefore == "." && !capitalizeNextLetter) {
                // Dau cach ngay sau dau cham - kich hoat viet hoa cho chu tiep theo.
                capitalizeNextLetter = true
                showCapitalPreview = true
                capitalizeAppliedAtPrefixLen = null
                insertText(" ")
                finishWordTracking()
                redrawKeyboard()
                return
            }
            // SUA LOI "bam cach van con in hoa": khi go phim Cach ma DANG co
            // co viet hoa (vi du bam Shift roi bam Cach thay vi bam chu cai),
            // phai TAT co viet hoa luon - chu tiep theo sau dau cach se KHONG
            // bi viet hoa nua (nguoi dung chi muon go khoang trong, khong muon
            // viet hoa chu tiep). Ngoai tru: neu co viet hoa duoc dat do "dau
            // dau dong/o nhap trong" (capitalizeAppliedAtPrefixLen == null VAN
            // CON chua duoc ap dung) thi KHONG tat - day la viet hoa dau cau
            // tu dong, dau cach la khoang trong truoc chuc bi (vd mo o nhap
            // trong bang 1 khoang trang o dau), van muon viet hoa cho chu tiep.
            if (capitalizeNextLetter && capitalizeAppliedAtPrefixLen != null) {
                capitalizeNextLetter = false
                showCapitalPreview = false
                capitalizeAppliedAtPrefixLen = null
                insertText(" ")
                finishWordTracking()
                redrawKeyboard()
                return
            }
            // QUAN TRỌNG: dù capitalizeNextLetter đã tắt từ trước (đã dùng hết
            // lượt hoa đầu câu), capitalizeAppliedAtPrefixLen VẪN còn sót = 0.
            // Nếu không reset ở đây, từ mới sau dấu cách sẽ có commonPrefixLen=0
            // trùng với capitalizeAppliedAtPrefixLen=0 → bị nhầm là "gộp Telex
            // đè lên vị trí đã hoa" → uppercase sai chữ đầu từ mới.
            capitalizeAppliedAtPrefixLen = null
        }
        val shouldCapitalize = capitalizeNextLetter && ch.isLetter()
        if (shouldCapitalize) {
            capitalizeNextLetter = false
            showCapitalPreview = false
            capitalizeAppliedAtPrefixLen = null
        }
        val out = if (isShiftOn || shouldCapitalize) ch.uppercaseChar() else ch
        insertText(out.toString())
        // THEM (theo yeu cau nguoi dung, tinh nang goi y emoji): cap nhat tu
        // dang go RIENG (xem [emojiTrackWord]) - neu la chu cai thi noi
        // them, khong phai (dau cach/dau cau/so...) thi coi nhu HET tu, xoa
        // sach de bat dau tu MOI. Kiem tra goi y sau MOI lan cap nhat.
        if (out.isLetter()) {
            emojiTrackWord.append(out.lowercaseChar())
        } else {
            emojiTrackWord.clear()
        }
        checkEmojiSuggestion(emojiTrackWord.toString())
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
        // SUA LOI nguoi dung phan anh: chu cai DAU CAU (viet hoa tu dong)
        // khong gop duoc dau khi go lap (vd "Aa" khong ra "Â", "Ee" khong ra
        // "Ê"...). NGUYEN NHAN: dieu kien cu "capitalizeAppliedAtPrefixLen ==
        // oldWordLower.length" so sanh SAI 2 dai luong khac nhau -
        // [capitalizeAppliedAtPrefixLen] la VI TRI CO DINH (luon la 0, ghi
        // nhan luc tu con rong) cua ky tu da duoc viet hoa tu dong, trong
        // khi [oldWordLower.length] la DO DAI tu HIEN TAI (da tang len 1
        // ngay sau ky tu dau tien) - 2 gia tri nay KHONG BAO GIO bang nhau
        // ke tu ky tu THU HAI tro di, lam [keyIsUpper] luon tinh sai thanh
        // false cho lan go thu hai (dù dau cau van dang trong trang thai
        // viet hoa), khien [VietnameseTelex.applyDoubleModifier] tuong nham
        // la nguoi dung dang co y go LECH hoa/thuong (vd "Aa" muon giu
        // nguyen, khong gop) roi CHAN gop dau. SUA: CHi can [capitalizeNextLetter]
        // con dang bat (true) la coi nhu keyIsUpper=true - co gia tri nay
        // dam bao dung vi no da duoc tu dong tat ([justConsumedSingleShift])
        // ngay khi ky tu tiep theo KHONG con lien quan toi vi tri viet hoa
        // nua, nen neu no VAN con true nghia la van dang trong pham vi hop
        // le de gop/viet hoa. Truong hop nguoi dung THAT SU tu bam Shift roi
        // go chu thuong (khong lien quan viet hoa tu dong dau cau) van duoc
        // phat hien dung binh thuong qua [isShiftOn] doi rieng cho tung lan
        // go, khong bi anh huong boi thay doi nay.
        val keyIsUpper = isShiftOn || capitalizeNextLetter
        val wordAlreadyHasLiteralFOrW = oldWordLower.any { it == 'f' || it == 'w' }
        val newWordLower = if (wordAlreadyHasLiteralFOrW) {
            oldWordLower + lower
        } else {
            VietnameseTelex.processKey(oldWordLower, lower, oldWordCased, keyIsUpper, capitalizeAppliedAtPrefixLen)
        }
        currentWord = StringBuilder(newWordLower)
        emojiTrackWord = StringBuilder(newWordLower)

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
            // SUA LOI "luon hien in hoa": tat showCapitalPreview TRUOC khi goi
            // checkEmojiSuggestion (co the goi redrawKeyboard() ben trong neu
            // emoji trung khop) - tranh redraw bàn phim voi showCapitalPreview
            // = true lam phim luon hien chu HOA.
            showCapitalPreview = false
            // SUA LOI (theo yeu cau nguoi dung "bàn phím tự hiện in hoa hết,
            // gõ ra chữ thường"): BAN SUA TRUOC chi tat [showCapitalPreview]
            // (co dieu khien HIEN THI) ma QUEN tat luon [capitalizeNextLetter]
            // (co dieu khien HANH VI GO THUC TE, dung o [keyIsUpper] phia
            // tren VA o insertChar() cho ngon ngu khac Tieng Viet) - VI PHAM
            // quy tac "2 co nay LUON di doi voi nhau" ma MOI cho khac trong
            // file deu tuan thu (xem cac dong dat true/false CUNG LUC o gan
            // day). Hau qua: sau khi chu cai DAU duoc viet hoa dung, co
            // [capitalizeNextLetter] con "treo" true THEM 1 nhip go nua ma
            // KHONG co du lieu hien thi tuong ung (vi showCapitalPreview da
            // tat) - trang thai noi bo va hien thi bi LECH nhau đúng 1
            // keystroke, co the bieu hien thanh "hien thi da tat in hoa
            // nhung logic gõ ben trong van con dang coi la che do in hoa"
            // tuy thuoc ngu canh redraw. SUA: tat CA HAI cung luc, dung y
            // "da tieu thu xong luot viet hoa dau cau nay, KHONG con gi de
            // ap dung cho ky tu tiep theo nua" ca ve hien thi LAN hanh vi.
            capitalizeNextLetter = false
        }
        val justConsumedSingleShift = capitalizeNextLetter && !touchesCapitalizeTarget
        if (justConsumedSingleShift) {
            capitalizeNextLetter = false
            capitalizeAppliedAtPrefixLen = null
        }
        // Tat showCapitalPreview XONG ROI moi check emoji
        checkEmojiSuggestion(newWordLower)

        // wasCapitalizingWordStart: ký tự đầu câu (bao gồm cả khi Telex gộp như aa→â)
        // → uppercase ký tự đầu suffix. capitalizeAppliedAtPrefixLen được lưu lại để
        // các lần gộp tiếp theo (aa→â, ee→ê...) cũng uppercase đúng qua nhánh này.
        val newSuffixDisplay = when {
            wasCapitalizingWordStart || (capitalizeAppliedAtPrefixLen != null
                && commonPrefixLen == capitalizeAppliedAtPrefixLen
                && newSuffixLower.isNotEmpty()
                && !capitalizeNextLetter) -> {
                // Keystroke đầu câu HOẶC Telex gộp đè lên vị trí đã hoa (aa→Â, ee→Ê, dd→Đ)
                val restLower = newSuffixLower.drop(1)
                val rest = if (isShiftOn) restLower.uppercase() else restLower
                newSuffixLower.first().uppercaseChar() + rest
            }
            isShiftOn -> newSuffixLower.uppercase()
            else -> newSuffixLower
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
        // Cập nhật currentWordCased ngay sau commit để resync lần sau không bị
        // lag InputConnection đọc sai → isUpperAt() tính sai → Telex chặn gộp
        // (vd "Aa" không ra "Â" vì tưởng user gõ lệch hoa/thường).
        val newCasedPrefix = newWordLower.substring(0, commonPrefixLen)
            .mapIndexed { i, c ->
                currentWordCased.getOrNull(i)?.let { if (it.isUpperCase()) c.uppercaseChar() else c } ?: c
            }.joinToString("")
        currentWordCased = StringBuilder(newCasedPrefix + newSuffixDisplay)
        if (hadPendingSuggestion) updateSuggestionRowInPlace()
        if (justConsumedSingleShift || wasCapitalizingWordStart) redrawKeyboard()
    }

    private fun resyncCurrentWordFromInputConnection(ic: android.view.inputmethod.InputConnection) {
        val before = ic.getTextBeforeCursor(20, 0)?.toString() ?: return
        var i = before.length
        while (i > 0 && before[i - 1].isLetter()) i--
        val recoveredCased = before.substring(i)
        val recovered = recoveredCased.lowercase()

        // SUA LOI nguoi dung phan anh: "go 2 chu a/e/o/d ngay khi vua vao go
        // thi ra han 2 chu thay vi hop nhat thanh â/ê/ô/đ" va "go chu nao la
        // no xoa ngay chu do". NGUYEN NHAN: InputConnection cua o nhap DICH
        // co the CHUA KIP cap nhat kip thoi vao luc ham nay doc lai (dac
        // biet ngay sau keystroke truoc do, hoac go rat nhanh lien tiep) -
        // ket qua doc duoc ([recovered]) bi CU/TRE hon currentWord dang co
        // THAT SU trong bo nho (dung, da duoc [insertVietnameseChar] cap
        // nhat dung tu truoc). Neu cu ghi de currentWord bang du lieu TRE
        // nay, keystroke tiep theo se tinh sai commonPrefixLen/deleteCount
        // (tuong nham tu dang go NGAN/RONG hon that), lam applyDoubleModifier
        // KHONG hop nhat duoc (vd "aa" van la "aa" thay vi "â"), hoac lam
        // insertVietnameseChar xoa NHAM ky tu vua go. SUA: CHi ghi de
        // currentWord/currentWordCased khi du lieu doc duoc THAT SU khac biet
        // theo kieu KHONG PHAI la truong hop "tre" nay (tuc [recovered]
        // KHONG phai la mot TIEN TO ngan hon cua currentWord hien tai) - neu
        // la tien to ngan hon, coi nhu do tre, GIU NGUYEN currentWord dang co
        // (chinh xac hon ban doc duoc).
        val isStaleLag = recovered.length < currentWord.length &&
            currentWord.startsWith(recovered)
        if (isStaleLag) return

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
        // SUA LOI nguoi dung phan anh ("chon emoji khong xoa chu, hihi
        // thanh hihi 😂"): TRUOC DAY co [emojiTrackWord.clear()] o day -
        // ham nay duoc GOI CHO TUNG KY TU trong luong go thuong (qua
        // [insertChar]), nen moi lan go 1 chu cai, [emojiTrackWord] bi XOA
        // SACH ngay truoc khi [insertChar] kip noi them ky tu do vao - lam
        // [emojiTrackWord] KHONG BAO GIO tich luy qua 1 ky tu, [pendingEmojiOriginalWord]
        // vi vay LUON SAI (qua ngan/rong) - khi bam chon emoji,
        // [ic.deleteSurroundingText] xoa SAI so ky tu (qua it hoac 0), chu
        // van con nguyen tren man hinh. SUA: BO xoa [emojiTrackWord] o day -
        // de CHINH [insertChar] tu quan ly no (da co san logic dung: chu
        // cai thi noi them, khong phai thi xoa - xem [insertChar]). Cac noi
        // GOI insertText() KHAC (dau cau, ky hieu...) TU xoa [emojiTrackWord]
        // rieng ngay sau khi goi ham nay, dam bao van dung ranh gioi tu.

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
            currentWord.clear(); emojiTrackWord.clear()
            currentWordCased.clear()
        } else {
            ic?.deleteSurroundingText(1, 0)
            if (currentWord.isNotEmpty()) {
                currentWord.deleteCharAt(currentWord.length - 1)
            }
            if (currentWordCased.isNotEmpty()) {
                currentWordCased.deleteCharAt(currentWordCased.length - 1)
            }
            if (emojiTrackWord.isNotEmpty()) {
                emojiTrackWord.deleteCharAt(emojiTrackWord.length - 1)
            }
        }
        checkEmojiSuggestion(emojiTrackWord.toString())

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
        if (hadPendingSuggestion) updateSuggestionRowInPlace()
        if (shouldRearmCapitalize) redrawKeyboard()
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        currentWord.clear(); emojiTrackWord.clear()
        clearAutocorrectSuggestion()
        selfInitiatedChange = true
        val inputType = currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL
        val isMultiLine = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
            (inputType and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0
        if (isMultiLine) {
            ic.commitText("\n", 1)
            // THEM (theo yeu cau nguoi dung): tu dong viet hoa chu cai DAU
            // TIEN cua dong MOI - giong het co che tu dong viet hoa sau dau
            // "." + dau cach (xem [insertVietnameseChar]/[insertChar] o
            // tren). [capitalizeAppliedAtPrefixLen] = null de bao hieu "vi
            // tri MOI, chua ap dung o dau ca" - dung y het cach cac diem
            // dat co khac trong file nay lam.
            capitalizeNextLetter = true
            showCapitalPreview = true
            capitalizeAppliedAtPrefixLen = null
            redrawKeyboard()
            return
        }
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
            // THEM: tuong tu nhanh multi-line o tren - o nhap 1 dong nhung
            // KHONG co action rieng (Next/Done/Tim kiem...) van chi la chen
            // ky tu xuong dong thuong, ap dung dung quy tac tu dong viet hoa
            // giong het nhau.
            capitalizeNextLetter = true
            showCapitalPreview = true
            capitalizeAppliedAtPrefixLen = null
            redrawKeyboard()
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
            currentWord.clear(); emojiTrackWord.clear()
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
        // qua KeyboardThemePrefs (dung CHUNG voi SettingsActivity, xem file
        // do). Neu chua tung doi gi ca, dung gia tri mac dinh (tim neon, nen toi).
        glowColor = KeyboardThemePrefs.getAccentColor(this)
        isDarkTheme = KeyboardThemePrefs.isDarkTheme(this)
        val (l1, l2) = LanguagePrefs.getSelectedLanguages(this)
        lang1 = l1
        lang2 = l2
        rgbChaseEnabled = RgbEffectPrefs.isEnabled(this)
        rgbChaseDirection = RgbEffectPrefs.getDirection(this)
        rgbChaseColorMode = RgbEffectPrefs.getColorMode(this)
        autocorrectEnabled = SuggestionPrefs.isAutocorrectEnabled(this)
        emojiSuggestionEnabled = SuggestionPrefs.isEmojiSuggestionEnabled(this)
        if (autocorrectEnabled) VietnameseAutocorrect.preload(this)
    }

    /** THEM: man Cai dat (SettingsActivity) gio la noi DUY NHAT nguoi dung
     *  doi mau vien/che do sang-toi (da chuyen khoi thanh chon mau tren
     *  trang Ky hieu cua ban phim - xem buildKeyboardSettingsBar). Ham
     *  callback nay cua InputMethodService duoc goi MOI LAN cua so ban phim
     *  TRO LAI HIEN THI (ke ca sau khi bi che boi 1 Activity khac, dung y
     *  luc nguoi dung tu man Cai dat quay lai) - doc lai gia tri moi nhat va
     *  ve lai ban phim ngay, khong can nguoi dung tu dong/mo lai ban phim
     *  moi thay mau vua doi. */
    override fun onWindowShown() {
        super.onWindowShown()
        val newColor = KeyboardThemePrefs.getAccentColor(this)
        val newDark = KeyboardThemePrefs.isDarkTheme(this)
        val (newLang1, newLang2) = LanguagePrefs.getSelectedLanguages(this)
        val newRgbEnabled = RgbEffectPrefs.isEnabled(this)
        val newRgbDirection = RgbEffectPrefs.getDirection(this)
        val newRgbColorMode = RgbEffectPrefs.getColorMode(this)
        // THEM: dong bo 2 cong tac goi y (xem SuggestionPrefs.kt) - KHONG can
        // gop vao [needsFullRebuild] ben duoi (khac voi mau/nen/RGB, 2 cong
        // tac nay KHONG anh huong toi CACH VE cac phim, chi anh huong toi
        // hang goi y tren cung se hien hay khong lan go tiep theo - chi can
        // cap nhat co, KHONG can xay lai toan bo ban phim). Neu 1 goi y
        // dang HIEN ma nguoi dung vua TAT tinh nang do di, dep sach NGAY
        // (khong doi den lan go tiep theo) de tranh hang goi y "mo coi" con
        // luu tren man hinh du tinh nang da bi tat.
        val newAutocorrectEnabled = SuggestionPrefs.isAutocorrectEnabled(this)
        val newEmojiEnabled = SuggestionPrefs.isEmojiSuggestionEnabled(this)
        if (newAutocorrectEnabled != autocorrectEnabled || newEmojiEnabled != emojiSuggestionEnabled) {
            autocorrectEnabled = newAutocorrectEnabled
            emojiSuggestionEnabled = newEmojiEnabled
            if (autocorrectEnabled) VietnameseAutocorrect.preload(this)
            // SUA: [clearAutocorrectSuggestion] dep sach CA HAI loai goi y
            // cung luc (xem giai thich chi tiet o chinh ham do) - don gian
            // hon la tach rieng tung loai, va van dam bao dung yeu cau "dep
            // NGAY hang goi y mo coi neu tinh nang vua bi tat di".
            if ((!autocorrectEnabled && pendingSuggestion != null) ||
                (!emojiSuggestionEnabled && pendingEmojiSuggestion != null)
            ) {
                clearAutocorrectSuggestion()
                redrawKeyboard()
            }
        }
        val needsFullRebuild = newColor != glowColor || newDark != isDarkTheme ||
            newLang1 != lang1 || newLang2 != lang2 || newRgbEnabled != rgbChaseEnabled
        if (needsFullRebuild) {
            glowColor = newColor
            isDarkTheme = newDark
            // THEM: neu 2 ngon ngu vua doi trong man Cai dat KHONG con chua
            // ngon ngu DANG active hien tai (vd dang o "en" nhung nguoi dung
            // vua doi bo "en" ra khoi 2 lua chon) - ve lai ngon ngu 1 cho an
            // toan, tranh active "treo" vao 1 ma ngon ngu khong con duoc
            // chon nua.
            val stillValid = activeLangCode == newLang1 || activeLangCode == newLang2
            lang1 = newLang1
            lang2 = newLang2
            if (!stillValid) activeIsLang1 = true
            // THEM: bat/tat hieu ung RGB chay - can XAY LAI TOAN BO ban phim
            // (giong het ly do doi mau/nen o duoi) vi cac phim CHI duoc
            // DANG KY vao [rgbChaseRegistryByPage] NGAY LUC xay dung (xem
            // [registerChaseKey]) - bat hieu ung len ma khong xay lai thi
            // se KHONG CO phim nao duoc dang ky ca (danh sach rong, hoat
            // hinh khong chay duoc), tat di ma khong xay lai thi cac phim
            // van con "dinh" mau hoat hinh cuoi cung thay vi tro ve mau tinh
            // binh thuong.
            rgbChaseEnabled = newRgbEnabled
            rgbChaseDirection = newRgbDirection
            rgbChaseColorMode = newRgbColorMode

            // SUA LOI nguoi dung phan anh ("doi mau gio no chi ap dung cho
            // man 1", "doi nen phai ap dung ca ben trong phim luon"):
            // TRUOC DAY goi [redrawKeyboard()] - ham nay CHi ve lai DUY NHAT
            // trang Chu cai (toi uu de tranh giat khi go phim binh thuong -
            // xem giai thich o [redrawKeyboard]), 3 trang con lai
            // (Numbers/Symbols/Numpad) VAN GIU NGUYEN cache CU voi mau/nen
            // "nuong san" tu truoc do - dan den hien tuong doi mau/nen
            // CHi thay doi tren trang dang xem luc do, cac trang khac (va ca
            // mau nen TUNG PHIM ben trong, vi keyFillColor() chi duoc tinh
            // lai khi PHIM do THAT SU duoc XAY LAI) van giu mau cu cho toi
            // khi bi buoc build lai vi ly do khac.
            //
            // SUA: xoa SACH toan bo cache (ca 4 trang + container goc) roi
            // XAY LAI HOAN TOAN qua setInputView(buildKeyboardContainer())
            // - day la truong hop nguoi dung CHU DONG doi mau/nen trong man
            // Cai dat (RAT HIEM khi xay ra, khac han voi moi lan go phim
            // binh thuong), nen chi phi xay lai toan bo ca 4 trang cung 1
            // luc la HOAN TOAN chap nhan duoc, dam bao MOI thu (nen ngoai
            // LAN mau nen tung phim ben trong) deu dung mau/nen MOI NHAT
            // tren CA 4 trang ngay lap tuc.
            cachedNumbersView = null
            cachedSymbolsView = null
            cachedNumpadView = null
            lettersPageView = null
            keyboardRootContainer = null
            setInputView(buildKeyboardContainer())
        } else if (rgbChaseDirection != newRgbDirection || rgbChaseColorMode != newRgbColorMode) {
            // Doi HUONG chay va/hoac CHE DO MAU (nhieu mau/1 mau) nhung van
            // BAT (khong can dang ky lai phim, chi can nho gia tri moi -
            // khung hinh KE TIEP se tu ap dung dung).
            rgbChaseDirection = newRgbDirection
            rgbChaseColorMode = newRgbColorMode
        }
        // THEM: dam bao vong lap hoat hinh dang CHAY DUNG trang thai bat/tat
        // moi nhat - goi lai moi lan ban phim hien len (an toan, tu huy vong
        // cu truoc khi tao vong moi neu co).
        startRgbChaseLoopIfNeeded()
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
            // SUA (gop vao nhom sua loi "app tu tat khi chuyen man"): TRUOC
            // DAY startActivity() o day KHONG duoc bao ve - neu nem loi (vd
            // ActivityNotFoundException/SecurityException trong truong hop
            // hiem gap) se lam crash toan bo tien trinh ban phim NGAY LUC
            // dang chuyen sang man xin quyen Camera.
            try {
                startActivity(intent)
            } catch (e: Exception) {
                onCameraPermissionResult = null
                Toast.makeText(this, "Kh\u00f4ng m\u1edf \u0111\u01b0\u1ee3c m\u00e0n xin quy\u1ec1n Camera", Toast.LENGTH_SHORT).show()
            }
            return
        }

        qrContinuousMode = continuous
        qrLastDeliveredText = null
        qrConsecutiveSameCount = 0
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
        qrOverlaySessionKey = null
        qrPreviewView = null
        qrFlashButton = null
        qrFlashOn = false
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

    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startQrCamera() {
        val preview = qrPreviewView ?: return
        qrCameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            // SUA (rat quan trong): BOC try/catch quanh TOAN BO than cua
            // listener nay - TRUOC DAY chi bindToLifecycle() duoc bao ve,
            // nhung cameraProviderFuture.get() VA cac buoc dung use-case o
            // duoi CUNG co the nem loi (vd camera dang bi app khac chiem
            // dung, thiet bi khong ho tro use-case nao do...). Loi nem ra
            // TRONG mot Runnable chay tren main executor SE KHONG duoc
            // JVM/Android tu dong bat - lam CRASH toan bo tien trinh ban
            // phim (nguyen nhan gay "ban phim tu dong dong, khong bat lai
            // duoc" nguoi dung bao cao).
            try {
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

                cameraProvider.unbindAll()
                qrCamera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase, imageAnalysis
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
            // Bo qua neu camera provider chua kip khoi tao xong.
        }
        qrCameraExecutor?.shutdown()
        qrCameraExecutor = null
        qrCamera = null
    }

    private fun processQrFrame(imageProxy: ImageProxy, scanner: BarcodeScanner) {
        // SUA (rat quan trong, sua loi "tu dong dong ban phim, khong bat lai
        // duoc"): BOC try/catch toan bo than ham nay. Ham nay chay LIEN TUC
        // (~30 lan/giay) tren 1 luong nen rieng (executor). TRUOC DAY neu
        // InputImage.fromMediaImage()/scanner.process() nem loi (vd khung
        // hinh bi hong do rung/dong camera, dinh dang anh khong ho tro...) -
        // loi do KHONG duoc bat, se lam CRASH toan bo tien trinh ban phim.
        // Dam bao imageProxy LUON duoc close() (ke ca khi loi) - khong thi
        // camera se bi "tac", ngung gui khung hinh moi, giong het trieu
        // chung "khong bat len duoc" ma nguoi dung mo ta.
        try {
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
                        // SUA (theo yeu cau nguoi dung): TRUOC DAY chi cho xuat DUNG 1
                        // LAN cho moi du lieu quet duoc, quet trung y het lan nua se bi
                        // CHAN HAN (value != qrLastDeliveredText). GIO DAY: cho phep
                        // toi da [ScanLimitPrefs.getConsecutiveLimit] lan LIEN TIEP
                        // giong het nhau (mac dinh 2, nguoi dung tu chinh trong man
                        // Cai dat). RIENG ban Google Play (BuildConfig kem theo flavor
                        // "ggplay"): KHONG GIOI HAN so lan lien tiep - luon cho qua.
                        val isSameAsLast = !value.isNullOrEmpty() && value == qrLastDeliveredText
                        val underLimit = BuildConfig.UNLIMITED_CONSECUTIVE_SCAN ||
                            qrConsecutiveSameCount < ScanLimitPrefs.getConsecutiveLimit(this)
                        val allowedToDeliver = !isSameAsLast || underLimit
                        if (!value.isNullOrEmpty() && !containsQrSpecialCharacter(value) &&
                            allowedToDeliver &&
                            qrFrameHandled.compareAndSet(false, true)
                        ) {
                            qrConsecutiveSameCount = if (isSameAsLast) qrConsecutiveSameCount + 1 else 1
                            qrLastDeliveredText = value
                            ScanHistoryStore.addEntry(this, value)
                            onQrFound(value)
                        }
                    }
                }
                .addOnFailureListener { /* Bo qua 1 khung loi - se co khung tiep theo */ }
                .addOnCompleteListener { imageProxy.close() }
        } catch (e: Exception) {
            try { imageProxy.close() } catch (ignored: Exception) { }
        }
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
            currentWord.clear(); emojiTrackWord.clear()
            val hadPendingSuggestion = pendingSuggestion != null
            clearAutocorrectSuggestion()
            if (hadPendingSuggestion) redrawKeyboard()
            Toast.makeText(this, "\u0110\u00e3 qu\u00e9t: $text", Toast.LENGTH_SHORT).show()
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

    /** Ghi log khi bàn phím bị ẩn (dù do user hay hệ thống) vào SharedPreferences.
     *  Mở app → tab "Lỗi bàn phím" để xem log. */
    private fun logKeyboardHide(reason: String) {
        try {
            val prefs = getSharedPreferences("kb_hide_log", android.content.Context.MODE_PRIVATE)
            val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date())
            val entry = "[$time] $reason | shift=$isShiftOn cap=$capitalizeNextLetter " +
                "capApplied=$capitalizeAppliedAtPrefixLen showCap=$showCapitalPreview " +
                "isViet=$isVietnameseMode word=${currentWord.toString().take(10)}"
            val old = prefs.getString("log", "") ?: ""
            val lines = old.lines().takeLast(49)  // giữ 50 dòng gần nhất
            prefs.edit().putString("log", (lines + entry).joinToString(System.lineSeparator())).apply()
        } catch (_: Exception) {}
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        logKeyboardHide("onFinishInputView(finishing=$finishingInput)")
        super.onFinishInputView(finishingInput)
        // THEM: dep sach popup chon dau (neu dang hien) + huy timer nhan giu
        // dang cho (neu co) - ban phim sap an, khong de popup "mo coi" tren
        // man hinh hoac timer chay ngam vo ich.
        accentLongPressRunnable?.let { accentLongPressHandler.removeCallbacks(it) }
        accentLongPressRunnable = null
        // THEM: huy luon hen gio tu-an goi y emoji (xem [cancelEmojiSuggestionAutoHide])
        // - ban phim sap an, khong can hen gio nay chay ngam vo ich (se tu
        // kiem tra lai tu dau qua [checkEmojiSuggestion] khi go tiep sau khi
        // mo lai).
        cancelEmojiSuggestionAutoHide()
        dismissAccentPopup()
        // THEM (theo yeu cau nguoi dung "mic phải dùng mic riêng"): dung
        // nghe/giai phong SpeechRecognizer neu dang mo - ban phim sap an,
        // KHONG de mic tiep tuc ghi am ngam khi nguoi dung khong con nhin
        // thay ban phim nua (rui ro rieng tu + ton pin vo ich).
        stopVoiceInputForHide()
        // THEM: dung NGAY vong lap hoat hinh RGB (khong can cho debounce -
        // View ban phim da AN ngay lap tuc roi, ve lai mau lien tuc luc
        // khong ai nhin thay la lang phi pin thuan tuy). Se tu khoi dong lai
        // trong [onWindowShown] khi ban phim hien len lan sau (neu van dang
        // bat trong Cai dat).
        stopRgbChaseLoop()
        // SUA LOI nguoi dung phan anh ("dang go binh thuong, dong ban phim
        // xuong roi bat lai khong tu ve trang Chu cai"): TRUOC DAY toan bo
        // logic duoi day CHi chay khi [finishingInput] = true - nhung theo
        // co che InputMethodService cua Android, hanh dong "dong ban phim
        // xuong" THONG THUONG nhat (bam mui ten xuong / Back / vuot xuong)
        // MA VAN O NGUYEN trong CUNG mot o nhap (khong chuyen app/o nhap
        // khac) lai goi onFinishInputView VOI finishingInput = FALSE (chi
        // "an" ban phim, KHONG ket thuc phien nhap lieu) - nen toan bo logic
        // (bao gom viec dat co reset ve trang Chu cai) CHUA BAO GIO duoc
        // chay cho dung tinh huong pho bien nay. SUA: bo dieu kien
        // if(finishingInput), luon chay logic ben duoi moi khi ham nay duoc
        // goi (bat ke true/false) - co che debounce co san (cho
        // [FINISH_INPUT_HIDE_DEBOUNCE_MS], huy neu ban phim mo lai nhanh
        // qua [cancelPendingFinishHide] trong onStartInputView) da tu loc
        // dung truong hop gian doan tam thoi (vd chuyen app nhanh roi quay
        // lai ngay) roi nen an toan khi bo dieu kien nay.
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
                // Khung quet QR KHONG mo luc nay - day la luc ban phim
                // THAT SU bi "dong" (da qua debounce, khong phai gian
                // doan tam thoi) - danh dau de lan MO LAI ke tiep
                // ([onStartInputView]) tu dong quay ve trang Chu cai,
                // bat ke la cung mot o nhap cu hay o nhap moi.
                shouldResetModeToLettersOnNextStart = true
            }
            hideQrOverlay()
        }
        pendingFinishHide = hideRunnable
        finishInputHideHandler.postDelayed(hideRunnable, FINISH_INPUT_HIDE_DEBOUNCE_MS)
        hideKeyPreview()
        deleteRepeatHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        logKeyboardHide("onDestroy - service bị kill")
        super.onDestroy()
        cancelPendingFinishHide()
        hideQrOverlay()
        qrToneGenerator.release()
        stopRgbChaseLoop()
        // THEM (theo yeu cau nguoi dung "mic phải dùng mic riêng"): giai
        // phong han SpeechRecognizer (goi .destroy(), khong chi .cancel()) -
        // Service sap bi huy hoan toan, giu lai se ro ri tai nguyen he thong
        // (native resource cua SpeechRecognizer KHONG tu duoc GC don).
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            // Bo qua - hiem gap.
        }
        speechRecognizer = null
        rgbChaseRegistryByPage.clear()
        accentLongPressRunnable?.let { accentLongPressHandler.removeCallbacks(it) }
        accentLongPressRunnable = null
        // THEM (don dep triet de): huy not vong lap xoa lien tuc neu VAN con
        // dang cho san (hiem gap - Service thuong khong bi huy giua luc dang
        // giu phim ⌫, nhung phong ve van hon).
        activeDeleteRepeatRunnable?.let { deleteRepeatHandler.removeCallbacks(it) }
        activeDeleteRepeatRunnable = null
        cancelEmojiSuggestionAutoHide()
        dismissAccentPopup()
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

    /** THEM: "lam nong" (nap san) tu dien vao bo nho o 1 THREAD NEN, goi 1
     *  LAN luc ban phim vua mo len NEU tinh nang dang bat (xem [onCreate]) -
     *  de tranh lan GIAT NHE DAU TIEN khi nguoi dung go xong tu DAU TIEN
     *  (luc do [ensureLoaded] moi phai doc + parse file tu dien tu assets,
     *  ~6.600 dong, neu KHONG lam nong truoc se chay dong bo tren main
     *  thread dung luc do). Cac lan goi [suggestFor] SAU DO deu dùng lai
     *  cache (Volatile field), khong doc file lai nua du goi tu thread nao. */
    fun preload(context: android.content.Context) {
        Thread {
            try {
                ensureLoaded(context)
            } catch (e: Exception) {
                // Bo qua - hiem gap, lan goi [suggestFor] tiep theo se tu thu lai.
            }
        }.start()
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

    /** THEM (theo yeu cau nguoi dung, lam ro lan 2 va lan 3): danh sach cac
     *  PHU AM GHEP CHi HOP LE LAM AM DAU (onset) cua 1 am tiet, KHONG BAO
     *  GIO la phu am CUOI (coda) hop le trong Tieng Viet - neu TU dang go
     *  (TINH TOI THOI DIEM NGAY TRUOC keystroke dau thanh) hien dang KET
     *  THUC bang MOT trong cac cum nay, thi phim dau thanh (s/f/r/x/j) se
     *  KHONG duoc ap dung nua - xem [applyTone]. Da BO "ch" va "ng" ra khoi
     *  danh sach nay (theo yeu cau moi nhat "tru ng, nh, ch") vi ca 2 deu
     *  la PHU AM CUOI (coda) HOP LE RAT PHO BIEN trong Tieng Viet (vd
     *  "sach"+s phai ra dung "sách", "lang"+huyen phai ra dung "làng") -
     *  chan dau thanh cho 2 cum nay se lam hong hang loat tu thong dung kiet
     *  thuc bang "ch"/"ng". "nh" cung la coda hop le tuong tu nen da duoc
     *  tru ra tu truoc (xem "nhanh"+s -> "nhánh" o vi du ben duoi). */
    private val toneBlockingEndClusters = listOf(
        "ngh", "tr", "th", "ph", "gh", "kh", "qu"
    )

    private val charToGroupTone: Map<Char, Pair<Int, Int>> by lazy {
        val map = HashMap<Char, Pair<Int, Int>>()
        vowelGroups.forEachIndexed { groupIdx, tones ->
            tones.forEachIndexed { toneIdx, c -> map[c] = groupIdx to toneIdx }
        }
        map
    }

    /** THEM (theo yeu cau nguoi dung, sua loi go "rever" -> "rểv"): dem so
     *  CUM NGUYEN AM rieng biet trong [word] - 1 "cum" la 1 day lien tiep cac
     *  ky tu nguyen am (vd "oa","uu"...). Mot am tiet tieng Viet hop le CHi
     *  co DUNG 1 cum nguyen am (phu am chi dung truoc/sau, khong xen giua 2
     *  nguyen am khac nhom). Neu tu dang go da co >= 2 cum (vd "rev" + go
     *  them 'e' -> "reve" co 2 cum: "e" va "e" cach nhau boi "v") thi KHONG
     *  con la 1 am tiet tieng Viet nua (nhieu kha nang la tu tieng Anh/nuoc
     *  ngoai go lien, vd "rever") - se dung [processKey] o duoi de TAT HAN
     *  viec bo dau/gop chu cho phan con lai cua tu do. */
    private fun vowelGroupCount(word: String): Int {
        var count = 0
        var inGroup = false
        for (c in word) {
            val isVowel = charToGroupTone.containsKey(c)
            if (isVowel && !inGroup) count++
            inGroup = isVowel
        }
        return count
    }

    /** THEM (sua loi go "rever"): tim VI TRI BAT DAU cua "am tiet cuoi cung"
     *  dang go trong [word] - la vi tri NGAY SAU cum nguyen am GAN NHAT ma
     *  cum do co phu am theo SAU no (tuc mot cum nguyen am da bi "dong" lai
     *  boi 1 phu am, bao hieu am tiet MOI se bat dau tu phu am do). Dung de
     *  [applyDoubleModifier]/[applyTone] o duoi CHi duoc phep gop/bo dau
     *  trong PHAM VI am tiet cuoi cung nay - KHONG duoc "voi" qua ranh gioi
     *  phu am de gop voi 1 nguyen am o am tiet TRUOC DO (vd "rev" + go 'e'
     *  KHONG duoc gop voi 'e' dau tien - ca 2 da bi ngan cach boi 'v' - phai
     *  giu nguyen thanh "reve", khong duoc ra "rêv"). Neu khong tim thay
     *  ranh gioi nao, tra ve 0 (toan bo [word] la 1 am tiet dang go, xu ly
     *  binh thuong nhu truoc gio). */
    private fun lastSyllableStart(word: String): Int {
        var boundary = 0
        var i = 0
        while (i < word.length) {
            if (charToGroupTone.containsKey(word[i])) {
                var j = i
                while (j < word.length && charToGroupTone.containsKey(word[j])) j++
                if (j < word.length) boundary = j
                i = j
            } else {
                i++
            }
        }
        return boundary
    }

    /** [wordCased]: ban GIU NGUYEN hoa/thuong THAT SU cua [word] (cung do
     *  dai, tung vi tri khop voi [word]) - dung DUY NHAT de kiem tra "lech
     *  hoa/thuong" trong [applyDoubleModifier] (xem giai thich chi tiet o
     *  do). [keyIsUpper]: case THAT SU (hoa hay thuong) cua CHINH phim vua
     *  go (truoc khi ha thanh [keyLower]). [autoCapPos]: VI TRI (thuong la
     *  0) trong [word] ma ky tu o do dang HOA CHi VI tinh nang TU DONG VIET
     *  HOA DAU CAU ([capitalizeAppliedAtPrefixLen] ben Service) - KHONG
     *  PHAI do nguoi dung THAT SU bam Shift. null neu khong co vi tri nao
     *  nhu vay. Xem giai thich day du o [applyDoubleModifier]. */
    fun processKey(word: String, keyLower: Char, wordCased: String, keyIsUpper: Boolean, autoCapPos: Int? = null): String {
        // THEM (sua loi go "rever" -> "rểv"): tu da co >= 2 cum nguyen am
        // rieng biet (khong con la 1 am tiet tieng Viet hop le, vd dang go
        // lien 1 tu tieng Anh nhu "rever") -> TAT HAN bo dau/gop chu cho
        // PHAN CON LAI cua tu nay, chi go y nguyen tu day tro di.
        if (vowelGroupCount(word) >= 2) return word + keyLower
        applyDoubleModifier(word, keyLower, wordCased, keyIsUpper, autoCapPos)?.let { return it }
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
     *  [keyIsUpper] o [processKey].
     *
     *  SUA LOI (nguoi dung phan anh: chu cai DAU CAU tu dong viet hoa
     *  khong gop duoc dau, vd "Aa" (A tu dong viet hoa dau cau + a thuong
     *  go binh thuong) khong ra "Â"): quy uoc "lech hoa/thuong de thoat"
     *  o tren CHi dung khi ca 2 ky tu la NGUOI DUNG THAT SU chu y go lech
     *  (bam Shift that). Neu ky tu GOC (o [fromIdx]) dang hoa CHi VI tu
     *  dong viet hoa dau cau ([autoCapPos] == fromIdx, khong phai nguoi
     *  dung bam Shift) thi KHONG duoc tinh la "lech" - van phai gop binh
     *  thuong thanh "Â"/"Ê"/"Đ" nhu the ca 2 ky tu cung thuong. */
    private fun applyDoubleModifier(word: String, key: Char, wordCased: String, keyIsUpper: Boolean, autoCapPos: Int? = null): String? {
        if (word.isEmpty()) return null

        // THEM (sua loi go "rever"): CHI tim trong pham vi am tiet CUOI CUNG
        // dang go - khong "voi" qua ranh gioi phu am de gop voi nguyen am o
        // am tiet TRUOC do (xem giai thich chi tiet o [lastSyllableStart]).
        // CHi ap dung cho truong hop go LAP LAI 1 nguyen am (a/e/o) - vi day
        // la truong hop THAT SU dang go THEM 1 nguyen am moi, co the la khoi
        // dau 1 am tiet MOI.
        val syllableStart = lastSyllableStart(word)
        fun lastIndexOfGroup(groupIdx: Int): Int? =
            word.indices.lastOrNull { i -> i >= syllableStart && charToGroupTone[word[i]]?.first == groupIdx }

        // SUA LOI nguoi dung phan anh: "van" + "w" khong ra "văn" ma giu
        // nguyen "vanw". NGUYEN NHAN: phim 'w' KHONG PHAI dang go THEM 1
        // nguyen am moi - no la 1 phim "BIEN DOI" nguyen am GAN NHAT co san
        // trong tu (a->ă, o->ơ, u->ư), tuong tu dau thanh (s/f/r/x/j) chu
        // KHONG giong truong hop go LAP LAI mot nguyen am (a/e/o) o tren. Vi
        // vay 'w' (va tuong tu, dau thanh trong [applyTone]) PHAI duoc tim
        // KHONG GIOI HAN pham vi am tiet - "van" (v-a-n) la 1 am tiet BINH
        // THUONG, "n" chi la phu am CUOI (coda) bthg, hoan toan hop le, KHONG
        // phai la ranh gioi sang 1 am tiet moi (vi chua co nguyen am nao
        // khac go THEM sau no ca). Dung ham nay (khong bi chan boi
        // syllableStart) rieng cho nhanh 'w' o duoi.
        fun lastIndexOfGroupAny(groupIdx: Int): Int? =
            word.indices.lastOrNull { i -> charToGroupTone[word[i]]?.first == groupIdx }

        // Case (hoa/thuong) THAT SU cua ky tu dang o vi tri [pos] trong tu,
        // dua vao [wordCased] - false (coi nhu thuong) neu vi tri khong hop
        // le (hiem gap, phong ve).
        fun isUpperAt(pos: Int): Boolean = wordCased.getOrNull(pos)?.isUpperCase() ?: false

        fun toggleGroup(fromGroupIdx: Int, toGroupIdx: Int, unrestricted: Boolean = false): String? {
            val lookup = if (unrestricted) ::lastIndexOfGroupAny else ::lastIndexOfGroup
            val fromIdx = lookup(fromGroupIdx)
            val toIdx = lookup(toGroupIdx)
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
                // TRU KHI ky tu GOC dang hoa CHi vi tu dong viet hoa dau cau
                // (fromIdx == autoCapPos) - luc do KHONG tinh la "lech", van
                // gop binh thuong (xem giai thich day du o dau ham nay).
                if (isUpperAt(fromIdx) != keyIsUpper && fromIdx != autoCapPos) return null
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
                        val pos = maxOf(lastIndexOfGroupAny(fromG) ?: -1, lastIndexOfGroupAny(toG) ?: -1)
                        if (pos < 0) null else Triple(pos, fromG, toG)
                    }.maxByOrNull { it.first }
                    if (best == null) null else toggleGroup(best.second, best.third, unrestricted = true)
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
                    // TRU KHI 'D' dang hoa CHi vi tu dong viet hoa dau cau
                    // (dIdx == autoCapPos) - luc do van gop binh thuong.
                    dIdx >= 0 && (isUpperAt(dIdx) == keyIsUpper || dIdx == autoCapPos) ->
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
        // SUA LOI nguoi dung phan anh: chu "gi" KHONG THE them dau thanh
        // duoc (vd go "gi" + "f" mong muon ra "gì" nhung khong co gi xay
        // ra). NGUYEN NHAN: dieu kien duoi day TRUOC DAY chan CA "qu" LAN
        // "gi" nhu nhau khi chi moi co DUNG 1 nguyen am ("u"/"i") ngay sau
        // "q"/"g" - nhung 2 truong hop nay KHONG GIONG NHAU: "qu" MOT MINH
        // (chua co nguyen am nao khac) THUC SU khong phai la 1 am tiet hop
        // le (Tieng Viet KHONG co tu nao chi la "qu" + dau thanh - luon can
        // it nhat 1 nguyen am khac phía sau, vd "quá", "quý"), nhung "gi"
        // THI CO THE la 1 am tiet HOAN CHINH mot minh - "gì" (nghia la "cai
        // gi") la mot tu Tieng Viet rat pho bien, cung nhu "gỉ" (ri set).
        // SUA: CHi con chan rieng cho "qu" ("q" + nguyen am nhom "u"),
        // KHONG con chan cho "gi" nua - "i" ngay sau "g" duoc bo dau thanh
        // BINH THUONG nhu moi nguyen am khac khi la nguyen am DUY NHAT cua
        // tu (vd "gi" + "f" -> "gì" dung nhu mong doi).
        val isPrematureQuGlide = clusterIndices.size == 1 &&
            clusterIndices[0] > 0 &&
            word[clusterIndices[0] - 1] == 'q' &&
            charToGroupTone[word[clusterIndices[0]]]?.first == 9 // "qu"
        if (isPrematureQuGlide) return null

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


