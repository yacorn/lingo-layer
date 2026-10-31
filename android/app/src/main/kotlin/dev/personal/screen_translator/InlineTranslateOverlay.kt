package dev.personal.screen_translator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.*
import android.widget.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class InlineTranslateOverlay(
    private val context: Context,
    private val onTranslate: () -> Unit,
    private val onRegion: (Region) -> Unit,
    private val onSelecting: () -> Unit,
    private val onStop: () -> Unit,
    private val automatic: Boolean = false,
    private val onPause: () -> Boolean = { false },
    private val onSelectionFinished: () -> Unit = {}
) {
    companion object {
        @Volatile var drawObserver: ((List<InlineTranslation>, List<Region>, Long, Boolean) -> Unit)? = null
    }
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private fun contentInsets() = if (android.os.Build.VERSION.SDK_INT >= 30) windows.maximumWindowMetrics.windowInsets
        .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        .let { intArrayOf(it.left,it.top,it.right,it.bottom) } else intArrayOf(0,0,0,0)
    private var closed = false
    private var selector: RegionSelector? = null
    private var fullTextPanel: LinearLayout? = null
    private var results = emptyList<InlineTranslation>()
    private var sourceRegions = emptyList<Region>()
    private var translated = ""
    private var recognizedText = ""
    private var pendingDraw = false
    private var complete = false
    private var capturing = false
    private var automaticPaused = false
    private var sourceVisible = true
    private var edgeBrightness = 0f
    private var edgePulse: android.animation.ValueAnimator? = null
    private val edge = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat()
                shader = SweepGradient(width/2f, height/2f, intArrayOf(Color.rgb(45,213,182),Color.rgb(114,143,248),Color.rgb(160,114,220),Color.rgb(45,213,182)), null)
                alpha = if (automaticPaused || !sourceVisible) 65 else (145+edgeBrightness*100).toInt()
            }
            val gap = dp(2).toFloat()
            canvas.drawRoundRect(gap,gap,width-gap,height-gap,dp(26).toFloat(),dp(26).toFloat(),paint)
        }
    }.apply { contentDescription = "자동 번역 준비 중"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
    private val captureMarker = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            val paint = Paint()
            intArrayOf(0xff29dbc9.toInt(),0xff8174ff.toInt(),0xffffc866.toInt()).forEachIndexed { index, color ->
                paint.color = color
                canvas.drawRect((index*dp(16)).toFloat(),0f,(index*dp(16)+dp(8)).toFloat(),height.toFloat(),paint)
            }
        }
    }
    val interacting get() = selector != null || fullTextPanel != null || menu.visibility == View.VISIBLE
    private var toast: Toast? = null
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 27, 31) }
    private val inline = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            if (results.isEmpty()) return
            val insets = contentInsets()
            val cells = CaptureRules.displayCells(sourceRegions, width, height, insets)
            val drawn = mutableListOf<InlineTranslation>()
            val displayed = mutableListOf<Region>()
            var truncated = false
            results.forEach { result ->
                val cell = cells[sourceRegions.indexOf(result.region)]
                val bounds = result.region.pixels(width, height)
                val margin = dp(4)
                val anchor = CaptureRules.inlineAnchor(bounds,cell,dp(6)) ?: return@forEach
                val x = anchor[0]; val y = anchor[1]; val padding = anchor[2]
                val availableWidth = cell[2]-2*margin
                val availableHeight = cell[3]-2*margin
                if (availableWidth <= 2*padding || availableHeight <= 2*padding) return@forEach
                val layoutWidth = max(bounds[2]+2*padding, dp(96)).coerceAtMost(cell[0]+cell[2]-margin-x)
                if (layoutWidth <= 2*padding) return@forEach
                val availableBoxHeight = min(cell[1]+cell[3]-margin-y, min(dp(240), (height*.35f).toInt()))
                var maxBoxHeight = min(cell[1]+cell[3]-margin-y,
                    min(max((bounds[3]*1.5f).toInt()+2*padding, dp(96)), min(dp(240), (height*.35f).toInt())))
                if (maxBoxHeight <= 2*padding) return@forEach
                var size = if (result.progressive) 14f else 18f
                val matched = result.sourceGlyphHeightPx.isFinite() && result.sourceGlyphHeightPx > 0
                val matchedSize = if (matched) {
                    val reference = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,16f,resources.displayMetrics)
                    ink.textSize = reference
                    val glyph = Rect(); ink.getTextBounds("가나다라마바사",0,7,glyph)
                    (reference*result.sourceGlyphHeightPx/max(1,glyph.height())).coerceIn(
                        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,10f,resources.displayMetrics),
                        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,32f,resources.displayMetrics))
                } else 0f
                fun textLayout(limit: Boolean = false): StaticLayout {
                    ink.textSize = if (matched) matchedSize else android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics)
                    val builder = StaticLayout.Builder.obtain(result.text, 0, result.text.length, ink, layoutWidth-2*padding)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(dp(3).toFloat(), 1f).build()
                    if (!limit) return builder
                    val maximum = max(1, ((maxBoxHeight-2*padding)/(ink.fontSpacing+dp(3))).toInt())
                    return StaticLayout.Builder.obtain(result.text, 0, result.text.length, ink, layoutWidth-2*padding)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(dp(3).toFloat(), 1f)
                        .setMaxLines(maximum).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
                }
                var lines = textLayout()
                fun useAvailableSpace() {
                    if (lines.height+2*padding <= availableBoxHeight) maxBoxHeight = max(maxBoxHeight, lines.height+2*padding)
                }
                useAvailableSpace()
                if (!matched && !result.progressive) while (lines.height+2*padding > maxBoxHeight && size > 12f) {
                    size -= 1f; lines = textLayout(); useAvailableSpace()
                }
                if (lines.height+2*padding > maxBoxHeight) { truncated = true; lines = textLayout(true) }
                if (lines.getLineBottom(0)+2*padding > maxBoxHeight) return@forEach
                val boxHeight = (lines.height+2*padding).coerceAtMost(maxBoxHeight)
                val boxWidth = (kotlin.math.ceil((0 until lines.lineCount).maxOf { lines.getLineRight(it) }.toDouble()).toInt()+2*padding)
                    .coerceIn(2*padding+1, layoutWidth)
                canvas.drawRoundRect(x.toFloat(), y.toFloat(), (x+boxWidth).toFloat(), (y+boxHeight).toFloat(), dp(8).toFloat(), dp(8).toFloat(), backdrop)
                canvas.save(); canvas.translate((x+padding).toFloat(), (y+padding).toFloat())
                canvas.clipRect(0, 0, boxWidth-2*padding, boxHeight-2*padding); lines.draw(canvas); canvas.restore()
                drawn.add(result)
                displayed.add(Region(x.toFloat()/width, y.toFloat()/height, (x+boxWidth).toFloat()/width, (y+boxHeight).toFloat()/height))
            }
            if (pendingDraw) {
                pendingDraw = false
                drawObserver?.invoke(drawn.toList(), displayed.toList(), SystemClock.elapsedRealtime(), complete)
                readAll.text = if (truncated || drawn.size < results.size) "전체 번역 보기 · …" else "전체 번역 보기"
            }
        }
    }.apply { visibility = View.INVISIBLE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
    private val bubble = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply { setColor(Color.rgb(20, 71, 61)); cornerRadius = dp(28).toFloat() }
        elevation = dp(6).toFloat()
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    private fun button(label: String, description: String, click: () -> Unit) = Button(context).apply {
        text = label; textSize = 13f; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
        minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(8), 0, dp(8), 0); contentDescription = description; setOnClickListener { click() }
    }
    private val translate = button("번역", "선택 영역 번역. 끌어서 버튼 이동") {
        if (automatic) setAutomaticPaused(onPause()) else onTranslate()
    }
    private val more = button("…", "번역 메뉴", ::toggleMenu)
    private val menu = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Color.rgb(23, 30, 36)); cornerRadius = dp(16).toFloat() }
        elevation = dp(6).toFloat(); visibility = View.GONE
    }
    private val original = button("원문 보기", "번역을 숨기고 원문 보기") {
        onSelecting(); hideTranslation(); menu.visibility = View.GONE
        if (automatic) setAutomaticPaused(true)
    }
    private val readAll = button("전체 번역 보기", "전체 번역 보기") { showText(false) }.apply { isEnabled = false }
    private val readSource = button("인식한 원문 보기", "인식한 원문 보기") { showText(true) }.apply { isEnabled = false }
    private val bubbleLayout = parameters(dp(128), dp(56)).apply { x = dp(12); y = dp(120) }
    private val menuLayout = parameters(dp(200), -2)

    private fun parameters(width: Int, height: Int) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            if (android.os.Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

    init {
        // Android 12+ permits touches through an untrusted overlay only within its opacity limit.
        val opacity = if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(android.hardware.input.InputManager::class.java).maximumObscuringOpacityForTouch else .8f
        val textOpacity = min(if (automatic) .7f else .8f, opacity)
        windows.addView(inline, parameters(-1, -1).apply { flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; alpha = textOpacity })
        if (automatic) windows.addView(edge, parameters(-1,-1).apply {
            flags = (flags and WindowManager.LayoutParams.FLAG_SECURE.inv()) or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // Two windows from our UID must stay within Android's combined touch-through opacity.
            alpha = max(0f,(opacity-textOpacity)/(1f-textOpacity)-.001f)
        })
        if (automatic) windows.addView(captureMarker, parameters(dp(40),dp(8)).apply {
            flags = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            x = context.resources.displayMetrics.widthPixels/2-dp(16); y = context.resources.displayMetrics.heightPixels/2
        })
        bubble.addView(translate, LinearLayout.LayoutParams(0, -1, 1f))
        bubble.addView(more, LinearLayout.LayoutParams(dp(48), -1))
        menu.addView(button("영역 선택", "번역할 영역 선택", ::selectRegion))
        menu.addView(original)
        menu.addView(readAll)
        menu.addView(readSource)
        menu.addView(button("번역 복사", "마지막 번역 복사") {
            if (translated.isNotEmpty()) {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("번역", translated))
                menu.visibility = View.GONE
            }
        })
        menu.addView(button("번역 종료", "화면 공유와 번역 종료", onStop))
        try {
            windows.addView(menu, menuLayout)
            windows.addView(bubble, bubbleLayout)
        } catch (error: Exception) {
            if (bubble.isAttachedToWindow) windows.removeView(bubble)
            if (menu.isAttachedToWindow) windows.removeView(menu)
            if (edge.isAttachedToWindow) windows.removeView(edge)
            if (captureMarker.isAttachedToWindow) windows.removeView(captureMarker)
            windows.removeView(inline)
            throw error
        }
        var startX = 0f; var startY = 0f; var baseX = 0; var baseY = 0; var dragging = false
        translate.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; baseX = bubbleLayout.x; baseY = bubbleLayout.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX-startX)+abs(event.rawY-startY) > dp(8)) dragging = true
                    if (dragging) { bubbleLayout.x = (baseX+event.rawX-startX).toInt(); bubbleLayout.y = (baseY+event.rawY-startY).toInt(); ensureInBounds() }
                }
                MotionEvent.ACTION_UP -> if (!dragging) view.performClick()
            }
            true
        }
        setBusy(false)
    }
    private fun toggleMenu() {
        if (closed || fullTextPanel != null) return
        menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        ensureInBounds()
    }
    fun showMenu() {
        if (closed || fullTextPanel != null) return
        menu.visibility = View.VISIBLE
        ensureInBounds()
    }
    fun ensureInBounds() {
        if (closed) return
        val metrics = context.resources.displayMetrics
        bubbleLayout.x = bubbleLayout.x.coerceIn(0, max(0, metrics.widthPixels-dp(128)))
        bubbleLayout.y = bubbleLayout.y.coerceIn(dp(36), max(dp(36), metrics.heightPixels-dp(104)))
        windows.updateViewLayout(bubble, bubbleLayout)
        menuLayout.x = bubbleLayout.x.coerceAtMost(max(0, metrics.widthPixels-dp(200)))
        menuLayout.y = min(bubbleLayout.y+dp(60), max(dp(36), metrics.heightPixels-dp(320)))
        windows.updateViewLayout(menu, menuLayout)
    }
    fun hideForCapture(area: IntArray) {
        if (closed) return
        toast?.cancel(); capturing = true; inline.visibility = View.INVISIBLE; menu.visibility = View.GONE
        val overlaps = bubbleLayout.x < area[0]+area[2] && bubbleLayout.x+dp(128) > area[0] &&
            bubbleLayout.y < area[1]+area[3] && bubbleLayout.y+dp(56) > area[1]
        bubble.visibility = if (automatic) View.GONE else if (overlaps) View.INVISIBLE else View.VISIBLE
        if (automatic) edge.visibility = View.INVISIBLE
        // A redraw supplies a fresh capture frame even when the source and visible controls are static.
        bubble.invalidate()
    }
    fun restoreControls(busy: Boolean, restoreTranslation: Boolean = false) {
        capturing = false
        if (automatic) edge.visibility = View.VISIBLE
        if (restoreTranslation && translated.isNotEmpty()) inline.visibility = View.VISIBLE
        setBusy(busy)
    }
    fun setAutomaticPaused(value: Boolean) { automaticPaused = value; updateEdge(); setBusy(false) }
    fun setSourceVisible(value: Boolean) {
        sourceVisible = value
        if (!value) hideTranslation()
        updateEdge()
    }
    private fun updateEdge() {
        edge.contentDescription = if (automaticPaused) "자동 번역 일시정지" else if (!sourceVisible) "번역할 앱이 보이지 않아 대기 중" else "자동 번역 실행 중"
        edge.invalidate()
    }
    fun finishCaptureProbe() {
        if (captureMarker.isAttachedToWindow) windows.removeView(captureMarker)
        updateEdge()
    }
    fun captureContainsIndicator(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val step = dp(8)*2
        // All three colored marks must be captured before using the full-screen fallback.
        for (y in bitmap.height/2-dp(12)..min(bitmap.height-1,bitmap.height/2+dp(12))) {
            for (x in max(0,bitmap.width/2-step-dp(12)) until min(bitmap.width-step*2,bitmap.width/2+dp(12))) {
                val index = y*bitmap.width+x
                if (pixels[index] and 0xffffff == 0x29dbc9 && pixels[index+step] and 0xffffff == 0x8174ff && pixels[index+step*2] and 0xffffff == 0xffc866) return true
            }
        }
        return false
    }
    fun setBusy(value: Boolean) {
        if (closed) return
        if (automatic) bubble.visibility = View.GONE
        else if (!capturing && selector == null && fullTextPanel == null) bubble.visibility = View.VISIBLE
        translate.isEnabled = automatic || !value
        translate.text = if (automatic) { if (automaticPaused) "재개" else "자동" } else if (value) "처리 중" else "번역"
        translate.contentDescription = if (automatic) {
            if (automaticPaused) "자동 번역 재개" else "자동 번역 일시정지"
        } else if (value) "화면 번역 처리 중" else "선택 영역 번역. 끌어서 버튼 이동"
    }
    fun show(values: List<InlineTranslation>, regions: List<Region>, busy: Boolean) {
        if (closed) return
        results = values; sourceRegions = regions; translated = values.joinToString("\n\n") { it.text }
        readAll.isEnabled = translated.isNotEmpty()
        complete = !busy; pendingDraw = true
        inline.contentDescription = "한국어 번역: $translated"
        inline.visibility = if (capturing) View.INVISIBLE else View.VISIBLE
        inline.invalidate(); setBusy(busy)
        if (automatic && !busy) {
            edgePulse?.cancel()
            edgePulse = android.animation.ValueAnimator.ofFloat(1f,0f).apply {
                duration = 450
                addUpdateListener { edgeBrightness = it.animatedValue as Float; edge.invalidate() }
                start()
            }
        }
    }
    fun hideTranslation(preserveDraw: Boolean = false) { if (!closed) { inline.visibility = View.INVISIBLE; if (!preserveDraw) pendingDraw = false } }
    fun setRecognizedText(text: String) { if (!closed) { recognizedText = text; readSource.isEnabled = text.isNotBlank() } }
    fun clear() { hideTranslation(); inline.contentDescription = null; translated = ""; results = emptyList(); sourceRegions = emptyList(); readAll.isEnabled = false; readAll.text = "전체 번역 보기"; setRecognizedText("") }
    fun status(message: String) {
        if (closed) return
        clear(); restoreControls(false); notice(message)
    }
    fun notice(message: String) {
        if (closed) return
        toast?.cancel(); toast = Toast.makeText(context, message, Toast.LENGTH_LONG).also { it.show() }
    }
    private fun selectRegion() {
        if (closed || selector != null || fullTextPanel != null) return
        onSelecting(); clear(); bubble.visibility = View.INVISIBLE; menu.visibility = View.GONE
        selector = RegionSelector(context) { selected ->
            selector = null; setBusy(false)
            if (selected != null) onRegion(selected)
            onSelectionFinished()
        }.also { it.open() }
    }
    private fun showText(source: Boolean) {
        val snapshot = if (source) recognizedText else translated
        if (closed || fullTextPanel != null || snapshot.isEmpty()) return
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(Color.rgb(23,30,36)); cornerRadius = dp(16).toFloat() }
            contentDescription = if (source) "인식한 원문 읽기 창" else "전체 번역 읽기 창"
        }
        panel.addView(TextView(context).apply {
            text = if (source) "최근 인식한 원문 · 선택 영역" else if (complete) "전체 번역" else "현재까지 번역된 내용"
            setTextColor(Color.rgb(155,222,205)); textSize = 16f; setPadding(0,0,0,dp(8))
        })
        val body = TextView(context).apply {
            text = snapshot; textSize = 16f; setTextColor(Color.WHITE); setTextIsSelectable(true)
            setLineSpacing(dp(4).toFloat(),1f)
        }
        panel.addView(ScrollView(context).apply { addView(body) }, LinearLayout.LayoutParams(-1,0,1f))
        panel.addView(LinearLayout(context).apply {
            addView(button("복사", if (source) "인식한 원문 복사" else "전체 번역 복사") {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(if (source) "인식한 원문" else "번역", snapshot))
            }, LinearLayout.LayoutParams(0,dp(48),1f))
            addView(button("닫기", if (source) "인식한 원문 닫기" else "전체 번역 닫고 화면으로 돌아가기") {
                windows.removeView(panel); fullTextPanel = null
                onSelectionFinished(); setBusy(false)
                if (!automatic && translated.isNotEmpty() && !capturing) inline.visibility = View.VISIBLE
            }, LinearLayout.LayoutParams(0,dp(48),1f))
        })
        val metrics = context.resources.displayMetrics
        val insets = contentInsets()
        val availableHeight = metrics.heightPixels-insets[1]-insets[3]-dp(24)
        val panelWidth = min(dp(420),metrics.widthPixels-insets[0]-insets[2]-dp(24))
        val panelHeight = min(dp(520),(availableHeight*.8f).toInt())
        onSelecting()
        try { windows.addView(panel, parameters(panelWidth,panelHeight).apply {
            x = insets[0]+(metrics.widthPixels-insets[0]-insets[2]-panelWidth)/2
            y = insets[1]+dp(12)+(availableHeight-panelHeight)/2
        }) } catch (_: Exception) {
            onSelectionFinished(); notice("읽기 창을 열지 못했습니다. 다른 앱 위에 표시 권한을 확인해 주세요."); return
        }
        fullTextPanel = panel; menu.visibility = View.GONE; bubble.visibility = View.INVISIBLE; hideTranslation()
    }
    fun close() {
        if (closed) return
        closed = true; toast?.cancel(); selector?.close(); selector = null
        fullTextPanel?.let { windows.removeView(it) }; fullTextPanel = null
        edgePulse?.cancel()
        if (automatic && edge.isAttachedToWindow) windows.removeView(edge)
        if (captureMarker.isAttachedToWindow) windows.removeView(captureMarker)
        windows.removeView(inline); windows.removeView(menu); windows.removeView(bubble)
    }
}
