package dev.personal.screen_translator

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import kotlin.math.max
import kotlin.math.min

class TranslateOverlay(
    private val context: Context,
    private val onRegion: (Region) -> Unit,
    private val onPause: () -> Boolean,
    private val onStop: () -> Unit,
    private val onSelecting: () -> Unit
) {
    companion object {
        // Test observer measures the draw callback; compositor presentation is outside this timestamp.
        @Volatile var drawObserver: ((String, String, Long) -> Unit)? = null
    }
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { setColor(Color.rgb(23, 30, 36)); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.rgb(86, 106, 119)) }
        elevation = dp(8).toFloat()
    }
    private val result = TextView(context).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(0, dp(6), 0, dp(4)) }
    private val timing = TextView(context).apply { setTextColor(Color.rgb(168, 193, 203)); textSize = 11f }
    private val pause = Button(context).apply { text = "일시정지"; textSize = 11f; contentDescription = "화면 번역 일시정지 또는 재개" }
    private var selector: RegionSelector? = null
    private var closed = false
    private var pendingDraw = false
    private val layout = WindowManager.LayoutParams(
        min(dp(340), context.resources.displayMetrics.widthPixels - dp(16)), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(48) }

    init {
        val header = TextView(context).apply { text = "화면 번역  •  여기서 이동"; setTextColor(Color.rgb(155, 222, 205)); textSize = 12f; minHeight = dp(30); contentDescription = "번역창 이동 손잡이" }
        var startX = 0f; var startY = 0f; var baseX = 0; var baseY = 0
        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; baseX = layout.x; baseY = layout.y }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = (baseX + event.rawX - startX).toInt().coerceIn(0, max(0, context.resources.displayMetrics.widthPixels - panel.width))
                    layout.y = (baseY + event.rawY - startY).toInt().coerceIn(0, max(0, context.resources.displayMetrics.heightPixels - panel.height))
                    if (!closed) windows.updateViewLayout(panel, layout)
                }
                MotionEvent.ACTION_UP -> header.performClick()
            }
            true
        }
        panel.addView(header)
        val scroll = ScrollView(context).apply { addView(result) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(140)))
        panel.addView(timing)
        val buttons = LinearLayout(context)
        val area = Button(context).apply { text = "영역"; textSize = 11f; contentDescription = "번역할 화면 영역 선택"; setOnClickListener { selectRegion() } }
        pause.setOnClickListener { setPaused(onPause()) }
        val close = Button(context).apply { text = "정지"; textSize = 11f; setOnClickListener { onStop() } }
        listOf(area, pause, close).forEach { buttons.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f)) }
        panel.addView(buttons)
        windows.addView(panel, layout)
        panel.viewTreeObserver.addOnDrawListener {
            if (pendingDraw) {
                pendingDraw = false
                drawObserver?.invoke(result.text.toString(), timing.text.toString(), android.os.SystemClock.elapsedRealtime())
            }
        }
    }
    fun setPaused(value: Boolean) { pause.text = if (value) "재개" else "일시정지" }
    fun ensureInBounds() {
        if (closed) return
        val metrics = context.resources.displayMetrics
        layout.x = layout.x.coerceIn(0, max(0, metrics.widthPixels - panel.width))
        layout.y = layout.y.coerceIn(0, max(0, metrics.heightPixels - panel.height))
        windows.updateViewLayout(panel, layout)
    }
    fun show(text: String, detail: String = "") { if (!closed) { pendingDraw = detail.isNotEmpty(); result.text = text; timing.text = detail } }
    private fun selectRegion() {
        if (selector != null || closed) return
        onSelecting(); setPaused(true)
        panel.visibility = View.GONE
        selector = RegionSelector(context) { selected ->
            selector = null; panel.visibility = View.VISIBLE
            if (selected != null) { onRegion(selected); setPaused(false) }
        }.also { it.open() }
    }

    fun close() {
        if (closed) return
        closed = true
        selector?.close(); selector = null
        windows.removeView(panel)
    }
}
