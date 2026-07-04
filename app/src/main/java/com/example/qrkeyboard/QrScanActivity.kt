package com.example.qrkeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Activity trong suot, duoc QrKeyboardService mo len khi nguoi dung
 * nham nut [QR] tren ban phim. Sau khi quet duoc ma, ket qua duoc
 * gui thang ve QrKeyboardService (qua callback tinh) de chen vao
 * o nhap lieu dang mo, roi Activity tu dong dong lai.
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        /** Key cua Intent extra chua chieu cao (px) cua ban phim dang hien thi,
         *  do QrKeyboardService gui qua khi mo Activity nay, dung de dat khung
         *  quet nam dung ngay phia tren ban phim. */
        const val EXTRA_KEYBOARD_HEIGHT_PX = "extra_keyboard_height_px"

        /** Key cua Intent extra (Boolean) bao Activity nay dang duoc mo o CHE
         *  DO QUET LIEN TUC (nguoi dung dup-tap nut QR tren ban phim) hay CHAM
         *  1 LAN binh thuong. Xem [continuousMode] va [onQrFound]. */
        const val EXTRA_CONTINUOUS_MODE = "extra_continuous_mode"
    }

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private val handled = AtomicBoolean(false)

    /** True neu Activity duoc mo bang DUP-TAP nut QR: sau khi quet duoc 1 ma,
     *  KHONG tu dong dong man hinh nua, ma tiep tuc quet ma tiep theo, cho
     *  den khi nguoi dung tu bam nut "Huy". False (mac dinh, cham 1 lan) giu
     *  hanh vi cu: quet duoc 1 ma la tu dong dong ngay. */
    private var continuousMode = false

    /** Tham chieu Camera dang duoc gan (tra ve tu bindToLifecycle), dung de
     *  dieu khien den flash/pin (torch) qua [toggleFlash]. Null cho toi khi
     *  camera duoc khoi dong xong trong [startCamera]. */
    private var camera: androidx.camera.core.Camera? = null

    /** Trang thai dang bat/tat cua den flash, dung de cap nhat giao dien nut
     *  bat/tat flash ([updateFlashButtonAppearance]) va truyen vao
     *  [androidx.camera.core.CameraControl.enableTorch]. */
    private var flashOn = false

    /** Nut bat/tat flash o goc phai tren man hinh quet - giu tham chieu de
     *  co the doi mau/nhan khi trang thai flash thay doi. */
    private var flashButton: Button? = null

    // ToneGenerator dung de phat tieng "bip" ngay khi quet duoc ma QR.
    // Muc am luong dat TOI DA (100/100 - thang do cua ToneGenerator) theo
    // yeu cau: nghe "bip" thiet lon, ro rang, khong con bi nho nhu truoc.
    private val toneGenerator: ToneGenerator by lazy {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "C\u1ea7n quy\u1ec1n camera \u0111\u1ec3 qu\u00e9t QR", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        continuousMode = intent.getBooleanExtra(EXTRA_CONTINUOUS_MODE, false)
        // Khong con dung setContentView(R.layout.activity_qr_scan) nua: layout XML
        // do khong co san trong du an duoc chia se, nen dung code de dung toan bo
        // giao dien (giong phong cach QrKeyboardService), giup kiem soat chinh xac
        // vi tri tung phan tu (vd nut Huy o goc phai duoi) ma khong phu thuoc file
        // XML nao khac.
        setContentView(buildScanContentView())
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Dat cua so quet thanh mot khung noi, nam ngang, cao BANG DUNG chieu cao
        // ban phim ao ben duoi (khong con cong them phan du) - KHONG cuop focus
        // cua o nhap lieu dang mo, ban phim (QrKeyboardService) van "song" binh
        // thuong o duoi, chi la bi khung quet nay che mat trong luc quet.
        floatAboveKeyboard()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Vi Activity nay khai bao launchMode="singleTop" trong Manifest, neu
     *  nguoi dung bam nut [QR] lan nua trong luc instance cu chua kip huy
     *  het, he thong se TAI SU DUNG instance cu va goi onNewIntent() thay
     *  vi onCreate(). Neu khong xu ly o day, hai van de se xay ra:
     *   1) `handled` van con true tu lan quet truoc -> processFrame() se
     *      bo qua moi ma QR moi, xem nhu "quet khong duoc" cho lan mo lai.
     *   2) Vi tri/kich thuoc khung quet (dua theo EXTRA_KEYBOARD_HEIGHT_PX)
     *      khong duoc cap nhat lai theo Intent moi.
     *  Ham nay reset lai ca hai de moi lan mo deu hoat dong dung nhu mo moi. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handled.set(false)
        continuousMode = intent.getBooleanExtra(EXTRA_CONTINUOUS_MODE, false)
        floatAboveKeyboard()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** Dung toan bo giao dien cua man hinh quet bang code: lop preview camera
     *  chiem het cua so, nut Huy noi o GOC PHAI DUOI cua khung quet (thay
     *  vi o giua nhu truoc), de khong che khung QR o giua va de bam bang
     *  ngon tay cai khi dang cam may 1 tay, va nut bat/tat DEN FLASH o GOC
     *  PHAI TREN (xem [toggleFlash]) de quet duoc ma QR trong dieu kien thieu
     *  sang ma khong can roi khoi man hinh quet. */
    private fun buildScanContentView(): View {
        val root = FrameLayout(this)

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(previewView)

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
            ).apply {
                setMargins(0, 0, dp(12), dp(12))
            }
            setOnClickListener { finish() }
        }
        root.addView(cancelBtn)

        val flashBtn = Button(this).apply {
            text = "\u26a1"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 18f
            includeFontPadding = true
            background = buildFlashButtonBackground(active = false)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                setMargins(0, dp(12), dp(12), 0)
            }
            setOnClickListener { toggleFlash() }
        }
        flashButton = flashBtn
        root.addView(flashBtn)

        return root
    }

    /** Nen tron cho nut flash - doi mau khi BAT (xanh, giong mau highlight
     *  cua nut QR tren ban phim) de nguoi dung biet ngay flash dang mo, hay
     *  TAT (xam trong suot, dong bo mau voi nut Huy). */
    private fun buildFlashButtonBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(if (active) Color.parseColor("#1A73E8") else Color.parseColor("#CC202124"))
    }

    /** Bat/tat den flash (torch) cua camera dang quet. Chi hoat dong khi
     *  [camera] da san sang (sau khi [startCamera] bind xong) VA thiet bi co
     *  don vi flash (hasFlashUnit()) - neu khong, bao cho nguoi dung biet
     *  bang Toast thay vi im lang khong lam gi. */
    private fun toggleFlash() {
        val cam = camera
        if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "Thi\u1ebft b\u1ecb kh\u00f4ng c\u00f3 \u0111\u00e8n flash", Toast.LENGTH_SHORT).show()
            return
        }
        flashOn = !flashOn
        cam.cameraControl.enableTorch(flashOn)
        updateFlashButtonAppearance()
    }

    private fun updateFlashButtonAppearance() {
        flashButton?.background = buildFlashButtonBackground(active = flashOn)
    }

    /** Bien cua so cua Activity nay thanh mot khung noi (khong chiem toan man
     *  hinh) va KHONG cuop focus/ban phim cua o nhap lieu goc:
     *
     *  - FLAG_NOT_FOCUSABLE: cua so nay se khong bao gio nhan key/IME focus.
     *    Theo tai lieu Android, dat co nay dong nghia cua so duoc coi la
     *    "doc lap voi ban phim ao dang hien" (tuong duong tu dong co them
     *    FLAG_ALT_FOCUSABLE_IM) -> o nhap lieu o cua so ben duoi VAN GIU
     *    focus, nen he thong KHONG tu dong an ban phim ao khi Activity nay
     *    mo len. Cac nut/camera preview ben trong Activity nay van nhan
     *    duoc cham (touch) binh thuong, chi rieng key/IME focus la khong co.
     *  - Chieu rong = het man hinh (khung nam ngang). Chieu cao = BANG DUNG
     *    chieu cao ban phim (nhan tu Intent extra, do QrKeyboardService do va
     *    gui kem).
     *  - Gravity.BOTTOM + y = keyboardHeightPx: day la thay doi quan trong -
     *    truoc day y = 0 khien khung quet nam DE LEN dung vi tri cua ban
     *    phim (che mat ban phim). Gio day, dat y BANG chieu cao ban phim se
     *    NHAC khung quet len cao hon dung MOT khoang bang chieu cao ban
     *    phim, tuc la khung quet nam HAN o phia TREN ban phim, KHONG con
     *    de/che len ban phim nua (ban phim van hien ro o duoi, tuy khong
     *    nhan duoc touch trong luc dang quet vi la lop duoi).
     *
     *  Luu y: day la ky thuat khong chinh thong (Activity thuong khong dung
     *  de lam overlay), nen hanh vi co the khac nhau giua cac dong may/ban
     *  Android (mot so ROM nhu MIUI, One UI co the van tu an ban phim trong
     *  vai truong hop). Neu can do tin cay cao hon o moi thiet bi, phuong an
     *  chac chan hon la dung mot cua so he thong that (WindowManager +
     *  quyen SYSTEM_ALERT_WINDOW) thay vi Activity.
     */
    private fun floatAboveKeyboard() {
        val metrics = resources.displayMetrics
        val keyboardHeightPx = intent.getIntExtra(EXTRA_KEYBOARD_HEIGHT_PX, 0)

        // Phong khi keyboardHeightPx = 0 (vd do doc chua kip xong luc gui
        // Intent), dat mot muc san toi thieu = 1/3 man hinh de khung quet
        // khong bi qua nho.
        val minHeightPx = metrics.heightPixels / 3
        val sizePx = if (keyboardHeightPx > 0) keyboardHeightPx else minHeightPx

        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.setGravity(Gravity.BOTTOM)
        // Chieu cao khung quet = dung chieu cao ban phim.
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, sizePx)

        val params = window.attributes
        // Nhac khung quet len KHOI vi tri ban phim mot khoang dung bang chieu
        // cao ban phim, de no nam han o phia tren, khong con de len ban phim.
        params.y = sizePx
        window.attributes = params
    }

    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val scanner = BarcodeScanning.getClient()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrame(imageProxy, scanner)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Kh\u00f4ng m\u1edf \u0111\u01b0\u1ee3c camera: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processFrame(imageProxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (!handled.get()) {
                    val barcode = barcodes.firstOrNull {
                        it.valueType != Barcode.TYPE_UNKNOWN || it.rawValue != null || it.rawBytes != null
                    }
                    val value = barcode?.let { extractBarcodeText(it) }
                    // YEU CAU: neu noi dung ma QR co CHUA KY TU DAC BIET, KHONG
                    // xuat ket qua ra o nhap (xem [containsSpecialCharacter]) -
                    // tiep tuc quet frame ke tiep nhu chua tim thay gi ca, thay
                    // vi chen no vao ban phim.
                    if (!value.isNullOrEmpty() && !containsSpecialCharacter(value) &&
                        handled.compareAndSet(false, true)
                    ) {
                        onQrFound(value)
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /** Tap ky tu duoc COI LA HOP LE (khong phai "dac biet"): chu cai (co dau
     *  Tieng Viet), chu so, khoang trang, va mot so dau cau/ky hieu co ban
     *  thuong gap trong van ban thong thuong. BAT KY ky tu nao KHONG nam
     *  trong tap nay (vd @#$%^&*+={}[]|\~`<>, emoji, ky tu dieu khien...)
     *  se khien ca ket qua bi coi la "chua ky tu dac biet" va bi TU CHOI,
     *  khong xuat ra o nhap - dung theo yeu cau: khong muon ma QR chua ky
     *  tu dac biet duoc chen vao van ban dang go. */
    private val allowedCharacterRegex = Regex(
        "^[\\p{L}\\p{N}\\s.,!?:;'\"()/@-]*$"
    )

    private fun containsSpecialCharacter(text: String): Boolean =
        !allowedCharacterRegex.matches(text)

    /** Lay noi dung van ban tu ma QR, uu tien [Barcode.rawValue] (thuong dung
     *  nhat, du dieu kien cho hau het ma QR), roi [displayValue], roi cuoi
     *  cung tu giai ma [rawBytes] (UTF-8, roi Latin-1 neu UTF-8 khong hop
     *  le) - de LAY DUNG duoc noi dung thuc te cua ma QR (bao gom ca ma co
     *  dau/Tieng Viet) truoc khi dem qua buoc kiem tra [containsSpecialCharacter]
     *  ben tren. Lay dung noi dung truoc, roi moi quyet dinh co xuat ra hay
     *  khong, chinh xac hon la doan bua khi con thieu du lieu. */
    private fun extractBarcodeText(barcode: Barcode): String? {
        barcode.rawValue?.let { if (it.isNotEmpty()) return it }
        barcode.displayValue?.let { if (it.isNotEmpty()) return it }
        val bytes = barcode.rawBytes ?: return null
        if (bytes.isEmpty()) return null
        return try {
            val utf8 = String(bytes, Charsets.UTF_8)
            // Neu giai ma UTF-8 sinh ra ky tu thay the "\uFFFD" (dau hieu
            // byte khong hop le voi UTF-8), coi nhu giai ma sai bang ma,
            // chuyen sang Latin-1 (luon hop le, khong bao gio ra "\uFFFD").
            if (utf8.contains('\uFFFD')) String(bytes, Charsets.ISO_8859_1) else utf8
        } catch (e: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun onQrFound(text: String) {
        // Phat tieng "bip" ngay lap tuc (co the goi tu bat ky thread nao),
        // truoc khi chen du lieu vao o nhap
        val beepDurationMs = 150
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, beepDurationMs)

        runOnUiThread {
            // Luu ket qua lai, ban phim se tu dien vao o nhap lieu khi ket noi lai
            QrKeyboardService.deliverScanResult(text)
            Toast.makeText(this, "\u0110\u00e3 qu\u00e9t: $text", Toast.LENGTH_SHORT).show()

            // QUAN TRONG: khong goi finish() ngay lap tuc. startTone() phat am thanh
            // BAT DONG BO trong nen; neu Activity dong ngay, onDestroy() se goi
            // toneGenerator.release() va cat ngang tieng bip truoc khi no kip phat het.
            // Doi them mot chut (dai hon thoi luong tieng bip) roi moi dong Activity
            // (hoac, neu dang o CHE DO QUET LIEN TUC, chi mo lai co [handled] de
            // tiep tuc quet ma tiep theo, KHONG dong Activity).
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (continuousMode) {
                    // Che do quet lien tuc (mo bang dup-tap nut QR): khong dong
                    // man hinh, chi cho phep quet tiep ma QR ke tiep. Man hinh
                    // chi dong khi nguoi dung tu bam nut "Huy".
                    handled.set(false)
                } else {
                    finish()
                }
            }, (beepDurationMs + 100).toLong())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        toneGenerator.release()
    }
}
