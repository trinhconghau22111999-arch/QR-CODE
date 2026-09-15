package com.example.qrkeyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.io.FileOutputStream
import java.io.InputStream

/**
 * THEM (theo yeu cau nguoi dung, xem anh chup man hinh tu 1 phien lam viec
 * khac dang tien hanh song song - dua theo dung ban SUA LAI CUOI CUNG cua
 * nguoi dung trong anh do): man hinh cat 1 anh do nguoi dung chon thanh
 * hinh nen cho ban phim.
 *
 * CACH DUNG (dung theo ban mo ta MOI NHAT cua nguoi dung, KHONG phai ban
 * dau - ban dau la khung co the KEO GOC de doi kich thuoc, nhung nguoi
 * dung da doi y ngay sau do sang cach nay):
 * - Khung cat CO DINH, LUON giu dung TI LE gan giong khung ban phim that
 *   (xem [FRAME_ASPECT_RATIO] - GAN DUNG, vi kich thuoc ban phim THAT SU
 *   con tuy thuoc so hang dang hien/chieu cao phim nguoi dung tu chinh,
 *   khong the biet truoc chinh xac 100%).
 * - KEO 1 ngon de DI CHUYEN anh (khong con keo goc de doi kich thuoc nua).
 * - CHUM/XOE 2 ngon de PHONG TO/THU NHO anh (giong dung thao tac cat anh
 *   dai dien tren hau het cac app).
 * - Anh LUON duoc rang buoc de PHU KIN het khung cat (khong de lo khoang
 *   trong ben trong khung do keo/zoom qua da).
 */
class BackgroundCropActivity : ComponentActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
    }

    private lateinit var cropView: CropView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriString = intent?.getStringExtra(EXTRA_IMAGE_URI)
        val uri = uriString?.let { Uri.parse(it) }
        if (uri == null) {
            Toast.makeText(this, "Kh\u00f4ng \u0111\u1ecdc \u0111\u01b0\u1EE3c \u1EA3nh \u0111\u00e3 ch\u1ecdn", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val bitmap = try {
            loadBitmapRespectingExif(uri)
        } catch (e: Exception) {
            null
        }
        if (bitmap == null) {
            Toast.makeText(this, "Kh\u00f4ng \u0111\u1ecdc \u0111\u01b0\u1EE3c \u1EA3nh \u0111\u00e3 ch\u1ecdn", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val bgColor = Color.parseColor("#0A0510")
        val accentColor = KeyboardThemePrefs.getAccentColor(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }

        // Thanh tieu de tren cung.
        root.addView(TextView(this).apply {
            text = "K\u00e9o \u0111\u1EC3 di chuy\u1EC3n \u2022 Ch\u1EE5m 2 ng\u00f3n \u0111\u1EC3 ph\u00f3ng to/thu nh\u1ecf"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(12))
        })

        // Vung cat anh - chiem toan bo khong gian con lai.
        cropView = CropView(this, bitmap)
        root.addView(cropView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Thanh nut duoi cung: Huy / Dat lam nen.
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }
        bottomBar.addView(Button(this).apply {
            text = "Hu\u1EF7"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { finish() }
        })
        bottomBar.addView(Button(this).apply {
            text = "\u2713  \u0110\u1EB7t l\u00e0m n\u1EC1n"
            isAllCaps = false
            setTextColor(Color.BLACK)
            setBackgroundColor(accentColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
            setOnClickListener { confirmCrop() }
        })
        root.addView(bottomBar)

        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Doc Bitmap tu Uri, tu dong XOAY LAI dung chieu theo du lieu EXIF cua
     *  anh goc (nhieu anh chup tu camera luu du lieu "xoay" trong EXIF thay
     *  vi xoay pixel that su - khong xu ly se bi lech 90 do). */
    private fun loadBitmapRespectingExif(uri: Uri): Bitmap? {
        val resolver = contentResolver
        val original: Bitmap = resolver.openInputStream(uri)?.use { input: InputStream ->
            android.graphics.BitmapFactory.decodeStream(input)
        } ?: return null

        val rotationDegrees = try {
            resolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
        if (rotationDegrees == 0) return original
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return try {
            Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        } catch (e: Exception) {
            original
        }
    }

    private fun confirmCrop() {
        val cropped = cropView.produceCroppedBitmap() ?: run {
            Toast.makeText(this, "C\u00f3 l\u1ed7i khi c\u1EAFt \u1EA3nh, th\u1EED l\u1EA1i nh\u00e9", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            FileOutputStream(KeyboardThemePrefs.backgroundImageFile(this)).use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            KeyboardThemePrefs.setHasBackgroundImage(this, true)
            setResult(RESULT_OK)
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Kh\u00f4ng l\u01B0u \u0111\u01B0\u1EE3c \u1EA3nh n\u1EC1n", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * View tuy chinh de CAT anh: ve Bitmap qua 1 [Matrix] (keo=dich chuyen,
 * chum 2 ngon=phong to/thu nho), phu 1 lop toi ben ngoai khung cat CO DINH
 * ti le o giua man hinh.
 */
private class CropView(context: Context, private val source: Bitmap) : View(context) {

    // GAN DUNG ti le khung ban phim that (chieu rong : chieu cao) - ban
    // phim THAT SU co the khac chut it tuy so hang dang hien/chieu cao
    // phim nguoi dung tu chinh trong Cai dat, nhung day la 1 ti le DAI
    // DIEN hop ly cho da so truong hop (ban phim 4 hang tieu chuan).
    private val frameAspectRatio = 2.3f

    private val matrix = Matrix()
    private val frameRect = RectF()
    private var minScale = 1f
    private var maxScale = 1f

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.parseColor("#B3000000") }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2f
        color = Color.WHITE
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val currentScale = currentMatrixScale()
            var factor = detector.scaleFactor
            val target = (currentScale * factor).coerceIn(minScale, maxScale)
            factor = if (currentScale == 0f) 1f else target / currentScale
            matrix.postScale(factor, factor, detector.focusX, detector.focusY)
            clampToFrame()
            invalidate()
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        computeFrameRect(w, h)
        resetMatrixToFillFrame()
        invalidate()
    }

    private fun computeFrameRect(viewW: Int, viewH: Int) {
        val maxFrameW = viewW * 0.92f
        val maxFrameH = viewH * 0.7f
        var frameW = maxFrameW
        var frameH = frameW / frameAspectRatio
        if (frameH > maxFrameH) {
            frameH = maxFrameH
            frameW = frameH * frameAspectRatio
        }
        val left = (viewW - frameW) / 2f
        val top = (viewH - frameH) / 2f
        frameRect.set(left, top, left + frameW, top + frameH)
    }

    /** Dat Bitmap sao cho PHU KIN het khung (kieu "centerCrop"), can giua -
     *  day la trang thai BAN DAU, truoc khi nguoi dung tu keo/zoom them. */
    private fun resetMatrixToFillFrame() {
        if (frameRect.width() <= 0 || frameRect.height() <= 0) return
        val scaleX = frameRect.width() / source.width
        val scaleY = frameRect.height() / source.height
        val fillScale = maxOf(scaleX, scaleY)
        minScale = fillScale
        maxScale = fillScale * 4f

        matrix.reset()
        matrix.postScale(fillScale, fillScale)
        val scaledW = source.width * fillScale
        val scaledH = source.height * fillScale
        val dx = frameRect.left + (frameRect.width() - scaledW) / 2f
        val dy = frameRect.top + (frameRect.height() - scaledH) / 2f
        matrix.postTranslate(dx, dy)
    }

    private fun currentMatrixScale(): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    /** Sau moi lan keo/zoom, dam bao anh LUON PHU KIN khung cat - khong de
     *  lo khoang trong ben trong khung (dich chuyen lai anh vao dung vi
     *  tri hop le neu bi keo qua da ra ngoai). */
    private fun clampToFrame() {
        val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        matrix.mapRect(bounds)

        var dx = 0f
        var dy = 0f
        if (bounds.width() < frameRect.width()) {
            dx = frameRect.centerX() - bounds.centerX()
        } else {
            if (bounds.left > frameRect.left) dx = frameRect.left - bounds.left
            else if (bounds.right < frameRect.right) dx = frameRect.right - bounds.right
        }
        if (bounds.height() < frameRect.height()) {
            dy = frameRect.centerY() - bounds.centerY()
        } else {
            if (bounds.top > frameRect.top) dy = frameRect.top - bounds.top
            else if (bounds.bottom < frameRect.bottom) dy = frameRect.bottom - bounds.bottom
        }
        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    matrix.postTranslate(dx, dy)
                    clampToFrame()
                    invalidate()
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawBitmap(source, matrix, bitmapPaint)
        // Phu toi TOAN BO man hinh, roi "khoet" lai vung khung cat de vung
        // do sang binh thuong - de nguoi dung thay ro dau la phan SE duoc
        // dung lam nen, dau la phan bi cat bo.
        canvas.save()
        canvas.clipOutRect(frameRect)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.restore()
        canvas.drawRect(frameRect, framePaint)
    }

    /** Tao Bitmap KET QUA CUOI CUNG dung DUNG vung anh dang nam trong
     *  khung cat, o do phan giai bang chinh kich thuoc khung tren man
     *  hinh (du dep cho muc dich lam nen ban phim, khong can qua lon). */
    fun produceCroppedBitmap(): Bitmap? {
        if (frameRect.width() <= 0 || frameRect.height() <= 0) return null
        val outW = frameRect.width().toInt().coerceAtLeast(1)
        val outH = frameRect.height().toInt().coerceAtLeast(1)
        return try {
            val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val outCanvas = Canvas(output)
            // Dich chuyen matrix hien tai sao cho goc TREN-TRAI cua khung
            // cat tro thanh goc (0,0) cua anh KET QUA.
            val shifted = Matrix(matrix)
            shifted.postTranslate(-frameRect.left, -frameRect.top)
            outCanvas.drawBitmap(source, shifted, bitmapPaint)
            output
        } catch (e: Exception) {
            null
        }
    }
}
