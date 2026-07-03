package com.example.qrkeyboard

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
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

    private lateinit var cameraExecutor: ExecutorService
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
        setContentView(R.layout.activity_qr_scan)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Thu nho cua so quet: chi cao bang 1/5 chieu cao man hinh, rong het
        // chieu ngang (nam ngang), va ghim xuong day man hinh - dung vao vung
        // ma ban phim ao van thuong chiem giu. Phai goi SAU setContentView() de
        // decor view cua Activity da duoc tao, neu khong window.setLayout() se
        // khong co tac dung.
        resizeScanWindow()

        findViewById<android.widget.Button>(R.id.btnCancel).setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Dat kich thuoc + vi tri cho cua so trong suot cua Activity nay:
     *  - Chieu cao = 1/5 chieu cao man hinh (khung nam ngang, thap va dai).
     *  - Chieu rong = het chieu ngang man hinh.
     *  - Gravity.BOTTOM: ghim khung quet xuong day man hinh, dung vao vi tri
     *    ma ban phim ao (QrKeyboardService) van thuong nam, de nguoi dung co
     *    cam giac khung quet "thay the" ban phim tam thoi thay vi chiem het
     *    man hinh nhu truoc.
     *
     *  Luu y: activity_qr_scan.xml can co PreviewView/nut Huy dung
     *  match_parent cho chieu rong/cao de tu dong co giau theo kich thuoc
     *  cua so moi nay; neu layout dang dung gia tri co dinh (fixed dp/height)
     *  thi can sua lai layout do cho khop. */
    private fun resizeScanWindow() {
        val metrics = resources.displayMetrics
        val windowHeightPx = metrics.heightPixels / 5

        window.setGravity(Gravity.BOTTOM)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, windowHeightPx)
    }

    @OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCamera() {
        val previewView = findViewById<PreviewView>(R.id.previewView)
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
