package com.example.qrkeyboard

import android.Manifest
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

        // Dat cua so quet thanh mot khung noi, nam ngang, cao 1/5 man hinh,
        // dinh ngay phia tren ban phim ao - KHONG cuop focus cua o nhap lieu
        // dang mo, de ban phim (QrKeyboardService) van tiep tuc hien thi
        // binh thuong phia duoi trong luc quet.
        floatAboveKeyboard()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
*  - Chieu cao = 1/5 chieu cao man hinh, chieu rong = het man hinh
     *    (khung nam ngang).
     *  - Gravity.BOTTOM + offset y = chieu cao ban phim (nhan tu Intent extra,
     *    do QrKeyboardService do va gui kem) -> khung quet duoc "dat" ngay
     *    sat phia tren ban phim, khong de bi ban phim che mat.
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
        val windowHeightPx = metrics.heightPixels / 5
        val keyboardHeightPx = intent.getIntExtra(EXTRA_KEYBOARD_HEIGHT_PX, 0)

        // Tru them mot khoang nho (overlapPx) khoi offset, de canh duoi cua
        // khung quet KHONG chi cham dung diem tren cung cua ban phim ma con
        // lan xuong phu them mot chut - dam bao khong bao gio ho mot khe ho
        // (do sai so lam tron px) va che luon phan "dong dang nhap" (o nhap
        // lieu/thanh soan tin) thuong nam sat ngay phia tren ban phim trong
        // hau het cac app (Zalo, Messenger, form web...).
        val overlapPx = dp(24)
        val yOffset = (keyboardHeightPx - overlapPx).coerceAtLeast(0)

        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, windowHeightPx)

        val params = window.attributes
        params.y = yOffset
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
        // Phat
