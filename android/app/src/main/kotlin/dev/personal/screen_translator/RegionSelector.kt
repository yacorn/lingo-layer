package dev.personal.screen_translator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RegionSelector(context: Context, private val onDismiss: (Region?) -> Unit) : View(context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = resources.displayMetrics.density
    private val shade = Paint().apply { color = Color.argb(95, 0, 0, 0) }
    private val outline = Paint().apply { color = Color.rgb(83, 225, 181); style = Paint.Style.STROKE; strokeWidth = 3 * density }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics) }
    private var x1 = 0f; private var y1 = 0f; private var x2 = 0f; private var y2 = 0f
    private var attached = false

    fun open() {
        windows.addView(this, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
                if (android.os.Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            })
        attached = true
        contentDescription = "번역할 영역을 드래그하세요. 짧게 누르면 취소합니다."
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        canvas.drawText("번역할 대사·자막을 드래그하세요", 18 * density, 88 * density, label)
        canvas.drawText("짧게 누르면 취소합니다", 18 * density, 116 * density, label)
        canvas.drawRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2), outline)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x1 = event.x; y1 = event.y; x2 = x1; y2 = y1 }
            MotionEvent.ACTION_MOVE -> { x2 = event.x; y2 = event.y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                x2 = event.x; y2 = event.y
                val selected = if (event.actionMasked == MotionEvent.ACTION_UP && abs(x1-x2) >= 24*density && abs(y1-y2) >= 24*density)
                    Region(x1/width, y1/height, x2/width, y2/height) else null
                close(); onDismiss(selected); performClick()
            }
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun close() { if (attached) { windows.removeView(this); attached = false } }
}
