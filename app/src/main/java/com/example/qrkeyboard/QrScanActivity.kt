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
    }

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private val handled = AtomicBoolean(false)

    // ToneGenerator dung de phat tieng "bip" ngay khi quet duoc ma QR
    private val toneGenerator: ToneGenerator by lazy {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
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
        floatAboveKeyboard()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** Dung toan bo giao dien cua man hinh quet bang code: lop preview camera
     *  chiem het cua so, va nut Huy noi o GOC PHAI DUOI cua khung quet (thay
     *  vi o giua nhu truoc), de khong che khung QR o giua va de bam bang
     *  ngon tay cai khi dang cam may 1 tay. */
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

        return root
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
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
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
                    val value = barcodes.firstOrNull { it.valueType != Barcode.TYPE_UNKNOWN || it.rawValue != null }
                        ?.rawValue
                    if (!value.isNullOrEmpty() && handled.compareAndSet(false, true)) {
                        onQrFound(value)
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
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
            // Doi them mot chut (dai hon thoi luong tieng bip) roi moi dong Activity.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                finish()
            }, (beepDurationMs + 100).toLong())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        toneGenerator.release()
    }
}
