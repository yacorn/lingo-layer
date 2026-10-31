package dev.personal.screen_translator

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class ScreenTranslateService : Service() {
    companion object {
        const val STOP = "dev.personal.screen_translator.STOP"
        const val PAUSE = "dev.personal.screen_translator.PAUSE"
        const val RESUME = "dev.personal.screen_translator.RESUME"
        const val MENU = "dev.personal.screen_translator.MENU"
        @Volatile var running = false
        @Volatile var automaticRunning = false
        @Volatile var automaticPaused = false
    }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val captureThread = HandlerThread("screen-capture")
    private lateinit var captureHandler: Handler
    private lateinit var overlay: TranslateOverlay
    private var inline: InlineTranslateOverlay? = null
    private var manual = false
    private var automatic = false
    private var resumeAfterSelection = false
    @Volatile private var manualPending = false
    @Volatile private var manualStarted = 0L
    @Volatile private var captureAfterNanos = 0L
    private val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    @Volatile private var stopped = false
    @Volatile private var paused = false
    @Volatile private var sourceVisible = true
    @Volatile private var captureModeChecked = false
    @Volatile private var hideOverlaysForCapture = true
    private var lastAppFrame: Bitmap? = null
    @Volatile private var region = Region()
    private val generation = AtomicInteger()
    @Volatile private var lastHash: Long? = null
    @Volatile private var lastText = ""
    @Volatile private var lastBlocks = ""
    @Volatile private var observedHash: Long? = null
    @Volatile private var pendingHash: Long? = null
    @Volatile private var validatedGeneration = -1
    private var screenWidth = 0
    private var screenHeight = 0
    private val autoPoll = object : Runnable {
        override fun run() {
            if (stopped) return
            if (!paused && sourceVisible && inline?.interacting == false && !manualPending) requestTranslation(false)
            Translator.main.postDelayed(this, 800)
        }
    }
    private val settlePoll = Runnable {
        if (!stopped && !paused && sourceVisible && inline?.interacting == false && !manualPending) requestTranslation(false)
    }
    private val poll = object : Runnable {
        override fun run() {
            if (stopped) return
            if (manual && !manualPending) return
            if (manual && SystemClock.elapsedRealtime()-manualStarted > 5_000) {
                manualPending = false
                Translator.main.post { if (!stopped) {
                    inline?.restoreControls(false, automatic && pendingHash == null && validatedGeneration == generation.get())
                    if (!automatic) showMessage("화면을 읽지 못했습니다. 번역 버튼을 다시 눌러 주세요.")
                } }
                return
            }
            val requestedAt = manualStarted
            reader?.let { capture(it) }
            if (!manual || (manualPending && manualStarted == requestedAt)) captureHandler.postDelayed(this, if (manual) 60 else 300)
        }
    }

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        Translator.initialize(applicationContext)
        captureThread.start(); captureHandler = Handler(captureThread.looper)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action in setOf(PAUSE, RESUME, MENU)) {
            if (running && automatic) when (intent?.action) {
                PAUSE -> pauseAutomatic(true)
                RESUME -> pauseAutomatic(false)
                MENU -> inline?.showMenu()
            } else if (!running) stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        try {
            manual = intent?.getBooleanExtra("manual", false) == true
            automatic = manual && intent?.getBooleanExtra("automatic", false) == true
            automaticRunning = automatic
            check(Translator.loadedId != null) { "모델을 먼저 준비해 주세요." }
            val permission = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("permission", Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>("permission")
            checkNotNull(permission) { "화면 공유 권한이 없습니다." }
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("translation", "화면 번역", NotificationManager.IMPORTANCE_LOW))
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, notification)
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent!!.getIntExtra("resultCode", Activity.RESULT_OK), permission)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
                override fun onCapturedContentResize(width: Int, height: Int) { captureHandler.post { if (!stopped) resize(width, height) } }
                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    if (!automatic || sourceVisible == isVisible) return
                    sourceVisible = isVisible; manualPending = false; generation.incrementAndGet(); NativeEngine.cancel()
                    lastHash = null; lastBlocks = ""; observedHash = null; pendingHash = null
                    inline?.setSourceVisible(isVisible)
                    Translator.status(if (isVisible) "자동 번역 실행 중" else "번역할 앱이 보이지 않아 대기 중")
                }
            }, Translator.main)
            val metrics = resources.displayMetrics
            val bounds = if (Build.VERSION.SDK_INT >= 30) getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds else null
            screenWidth = bounds?.width() ?: metrics.widthPixels; screenHeight = bounds?.height() ?: metrics.heightPixels
            reader = newReader(screenWidth, screenHeight)
            display = projection!!.createVirtualDisplay("screen-translation", screenWidth, screenHeight, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, captureHandler)
            running = true
            if (manual) inline = InlineTranslateOverlay(this,
                onTranslate = { requestTranslation() },
                onRegion = { selected ->
                    region = selected; generation.incrementAndGet(); lastText = ""; lastHash = null; lastBlocks = ""; observedHash = null; pendingHash = null
                    if (!automatic) paused = false
                    inline?.status(if (automatic) "자동 번역 영역을 설정했습니다." else "영역을 설정했습니다. 번역 버튼을 눌러 주세요.")
                },
                onSelecting = {
                    resumeAfterSelection = !paused
                    manualPending = false; paused = true; generation.incrementAndGet(); NativeEngine.cancel()
                    lastHash = null; lastBlocks = ""; observedHash = null; pendingHash = null
                    if (automatic) { automaticPaused = true; inline?.setAutomaticPaused(true); notifications.notify(1, notification()) }
                },
                onStop = { stopSelf() }, automatic = automatic,
                onPause = {
                    pauseAutomatic(!paused)
                    paused
                },
                onSelectionFinished = {
                    if (automatic) {
                        paused = !resumeAfterSelection; automaticPaused = paused; inline?.setAutomaticPaused(paused)
                        notifications.notify(1, notification())
                    }
                })
            else overlay = TranslateOverlay(this,
                onRegion = { selected -> region = selected; generation.incrementAndGet(); lastText = ""; lastHash = null; paused = false; overlay.show("영역을 설정했습니다.") },
                onPause = { paused = !paused; generation.incrementAndGet(); if (paused) NativeEngine.cancel() else { lastText = ""; lastHash = null }; overlay.show(if (paused) "일시정지 · 재개를 누르면 계속합니다." else "화면이 바뀌면 번역합니다."); paused },
                onStop = { stopSelf() },
                onSelecting = { paused = true; generation.incrementAndGet(); NativeEngine.cancel() })
            if (manual) inline?.status(if (automatic) "자동 번역을 시작합니다. 알림에서 일시정지·메뉴·종료를 선택하세요." else "번역 버튼을 눌러 주세요. 메뉴에서 영역을 바꿀 수 있습니다.")
            else { overlay.show("화면 아래쪽을 번역합니다. ‘영역’으로 바꿀 수 있습니다."); captureHandler.post(poll) }
            Translator.status(if (automatic) "자동 번역 실행 중 · 선택 영역이 바뀌면 번역합니다." else if (manual) "버튼 번역 실행 중 · 다른 앱으로 이동해 번역을 눌러 주세요." else "화면 번역 실행 중")
            if (automatic) Translator.main.postDelayed(autoPoll, 800)
        } catch (e: Exception) { Translator.status(e.message ?: "화면 번역 시작 실패"); stopSelf() }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        fun action(name: String, label: String) = Notification.Action.Builder(null, label,
            PendingIntent.getService(this, 0, Intent(this, ScreenTranslateService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)).build()
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, "translation")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(if (automatic && paused) "일본어 화면 번역 일시정지" else "일본어 화면 번역 실행 중")
            .setContentText(if (automatic) "긴 결과는 번역 메뉴의 전체 번역 보기에서 읽을 수 있습니다." else "떠 있는 번역 버튼으로 선택 영역을 번역합니다.")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
        if (automatic) {
            builder.addAction(action(if (paused) RESUME else PAUSE, if (paused) "재개" else "일시정지"))
            builder.addAction(action(MENU, "번역 메뉴"))
        }
        return builder.addAction(action(STOP, "종료")).build()
    }
    private fun pauseAutomatic(value: Boolean) {
        if (!automatic || paused == value) return
        paused = value; automaticPaused = value; manualPending = false; generation.incrementAndGet(); NativeEngine.cancel()
        Translator.main.removeCallbacks(settlePoll)
        if (!value) { lastHash = null; lastBlocks = ""; observedHash = null; pendingHash = null }
        inline?.setAutomaticPaused(value)
        getSystemService(NotificationManager::class.java).notify(1, notification())
        Translator.status(if (value) "자동 번역 일시정지" else "자동 번역 실행 중")
    }

    private fun newReader(width: Int, height: Int): ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private fun showMessage(text: String) { if (manual) inline?.status(text) else if (::overlay.isInitialized) overlay.show(text) }
    private fun requestTranslation(force: Boolean = true) {
        if (stopped || !running || manualPending) return
        if (!force && (paused || !sourceVisible)) return
        if (!automatic && Translator.busy.get()) { inline?.notice("이전 번역을 마친 뒤 다시 눌러 주세요."); return }
        if (force) { paused = false; lastHash = null; lastBlocks = "" }
        manualPending = true; manualStarted = SystemClock.elapsedRealtime()
        val version = if (automatic) generation.get() else generation.incrementAndGet()
        captureHandler.post capture@{
            if (stopped || version != generation.get()) { manualPending = false; return@capture }
            val hide = !automatic || (captureModeChecked && hideOverlaysForCapture)
            // App capture excludes our overlays and may produce no new frame on a static page.
            if (hide) try { reader?.acquireLatestImage()?.close() } catch (_: IllegalStateException) { }
            Translator.main.post hide@{
                if (stopped || version != generation.get()) { manualPending = false; return@hide }
                if (hide) inline?.hideForCapture(region.pixels(screenWidth, screenHeight))
                captureAfterNanos = if (hide) System.nanoTime() else 0L
                captureHandler.removeCallbacks(poll)
                captureHandler.postDelayed(poll, 120)
            }
        }
    }
    private fun resize(width: Int, height: Int) {
        if (automatic) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            if (width != bounds.width() || height != bounds.height()) {
                Translator.main.post { Translator.status("앱을 전체 화면으로 열고 다시 시작해 주세요. 분할·팝업 화면은 아직 지원하지 않습니다."); stopSelf() }
                return
            }
        }
        if (width <= 0 || height <= 0 || (width == screenWidth && height == screenHeight)) return
        generation.incrementAndGet(); lastHash = null; lastText = ""; lastBlocks = ""; observedHash = null; pendingHash = null; manualPending = false
        display?.surface = null
        reader?.close(); screenWidth = width; screenHeight = height
        lastAppFrame?.recycle(); lastAppFrame = null
        reader = newReader(width, height)
        display?.resize(width, height, resources.displayMetrics.densityDpi)
        display?.surface = reader!!.surface
        Translator.main.post { if (!stopped) {
            if (manual) { inline?.clear(); inline?.restoreControls(false); inline?.ensureInBounds() }
            else if (::overlay.isInitialized) overlay.ensureInBounds()
        } }
    }
    private fun capture(source: ImageReader) {
        // Keep the latest queued frame while busy; static screens may never deliver another frame.
        if (stopped || paused || !sourceVisible || !running || (manual && !manualPending)) return
        if (!automatic && !Translator.busy.compareAndSet(false, true)) return
        val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null }
        if (image == null && (lastAppFrame == null || hideOverlaysForCapture)) { if (!automatic) Translator.busy.set(false); return }
        if (manual && image != null && image.timestamp < captureAfterNanos) {
            image.close()
            if (!automatic) Translator.busy.set(false)
            return
        }
        var bitmap: Bitmap? = null
        var ownsJob = !automatic
        var retryFrame = false
        try {
            val now = if (manual) manualStarted else SystemClock.elapsedRealtime()
            var version = generation.get()
            val insets = if (manual && Build.VERSION.SDK_INT >= 30) getSystemService(WindowManager::class.java).maximumWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                .let { intArrayOf(it.left, it.top, it.right, it.bottom) } else intArrayOf(0, 0, 0, 0)
            val width = image?.width ?: lastAppFrame!!.width; val height = image?.height ?: lastAppFrame!!.height
            val cropBounds = CaptureRules.contentCrop(region, width, height, insets)
            val full = if (image != null) {
                val plane = image.planes[0]
                check(plane.pixelStride == 4) { "지원하지 않는 화면 픽셀 형식입니다." }
                val padded = Bitmap.createBitmap(plane.rowStride/plane.pixelStride, height, Bitmap.Config.ARGB_8888)
                try {
                    val buffer = java.nio.ByteBuffer.allocate(plane.rowStride*height)
                    buffer.put(plane.buffer); buffer.rewind(); padded.copyPixelsFromBuffer(buffer)
                    val frame = Bitmap.createBitmap(padded,0,0,width,height)
                    if (frame === padded) padded.copy(Bitmap.Config.ARGB_8888,false) else frame
                } finally { padded.recycle() }
            } else lastAppFrame!!
            try {
                if (automatic && !captureModeChecked) {
                    hideOverlaysForCapture = inline!!.captureContainsIndicator(full)
                    captureModeChecked = true
                    Translator.main.post { if (!stopped) inline?.finishCaptureProbe() }
                }
                if (automatic && !hideOverlaysForCapture && image != null) {
                    lastAppFrame?.recycle(); lastAppFrame = full
                }
                val r = cropBounds
                bitmap = Bitmap.createBitmap(full, r[0], r[1], r[2], r[3])
                // createBitmap may return the original bitmap when the crop is the full image.
                if (bitmap === full) bitmap = full.copy(Bitmap.Config.ARGB_8888, false)
            } finally { if (full !== lastAppFrame) full.recycle() }
            val crop = bitmap!!
            if (manual && !automatic) {
                val pixels = IntArray(crop.width*crop.height)
                crop.getPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)
                // A secure overlay may still blank the first frame after hiding; keep this request pending.
                if (CaptureRules.isBlackFrame(pixels)) { retryFrame = true; return }
            }
            val hash = if (manual && !automatic) 0L else imageHash(crop)
            if (automatic) {
                if (observedHash != hash) {
                    // Hiding a secure overlay can briefly produce a black frame; confirm before cancelling inference.
                    if (pendingHash != hash) {
                        pendingHash = hash
                        Translator.main.post { if (!stopped && version == generation.get()) {
                            inline?.hideTranslation(true)
                            Translator.main.removeCallbacks(settlePoll)
                            Translator.main.postDelayed(settlePoll, 300)
                        } }
                        return
                    }
                    pendingHash = null; observedHash = hash
                    version = generation.incrementAndGet()
                    NativeEngine.cancel()
                    Translator.main.post { if (!stopped && version == generation.get()) inline?.hideTranslation() }
                } else {
                    pendingHash = null
                }
                // Start OCR only after two consecutive samples agree; monitor even while inference runs.
                if (lastHash == hash && validatedGeneration == version) return
                if (!Translator.busy.compareAndSet(false, true)) return
                ownsJob = true
            } else if (!manual && lastHash == hash) return
            bitmap = null; ownsJob = false
            Translator.worker.execute { process(crop, hash, version, now, cropBounds, width, height) }
        } catch (e: Exception) {
            Translator.main.post { if (!stopped) showMessage(e.message ?: "화면 읽기 실패 · 다시 눌러 주세요.") }
        } finally {
            image?.close(); bitmap?.recycle()
            if (manual && !retryFrame) manualPending = false
            if (ownsJob) Translator.busy.set(false)
            if (manual && !retryFrame) Translator.main.post { if (!stopped) inline?.restoreControls(Translator.busy.get(),
                automatic && !paused && pendingHash == null && validatedGeneration == generation.get()) }
        }
    }
    private fun imageHash(bitmap: Bitmap): Long {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var value = 1125899906842597L
        for (pixel in pixels) value = value * 31 + pixel
        return value
    }
    private fun process(bitmap: Bitmap, hash: Long, version: Int, start: Long, cropBounds: IntArray, width: Int, height: Int) {
        var recycleAfterOcr = false
        try {
            if (stopped || paused || version != generation.get()) return
            val ocrStart = SystemClock.elapsedRealtime()
            val ocrTask = recognizer.process(InputImage.fromBitmap(bitmap, 0))
            val recognized = try { Tasks.await(ocrTask, 15, TimeUnit.SECONDS) }
            catch (error: TimeoutException) {
                // await timeout does not cancel ML Kit; its input must survive until the task completes.
                recycleAfterOcr = true
                ocrTask.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { bitmap.recycle() }
                throw error
            }
            Translator.ocrMs = SystemClock.elapsedRealtime() - ocrStart
            // In manual Japanese mode, navigation icons and unrelated Latin blocks are not source text.
            // shortcut: OCR blocks approximate speech bubbles; add visual bubble detection if OCR merges distinct dialogue.
            val blocks = if (manual) recognized.textBlocks.filter { CaptureRules.hasJapanese(it.text) && it.boundingBox != null }
                .distinctBy { it.boundingBox }.sortedWith(compareBy({ it.boundingBox!!.top }, { it.boundingBox!!.left })) else recognized.textBlocks
            val text = CaptureRules.normalize(if (manual) blocks.joinToString("\n") { it.text } else recognized.text)
            if (stopped || paused || version != generation.get()) return
            lastHash = hash
            if (text.isEmpty() || !CaptureRules.hasJapanese(text)) {
                lastText = ""; lastBlocks = ""
                Translator.main.post { if (!stopped && version == generation.get()) {
                    validatedGeneration = version
                    if (automatic) inline?.clear() else showMessage("일본어를 찾지 못했습니다. 메뉴에서 번역 영역을 바꿔 주세요.")
                    inline?.setRecognizedText(recognized.text)
                } }
                return
            }
            if (!manual && text == lastText) return
            if (manual) {
                val glyphHeights = blocks.map { block -> CaptureRules.medianLineHeight(block.lines
                    .filter { CaptureRules.hasJapanese(it.text) }.mapNotNull { it.boundingBox?.height() }) }
                val signature = blocks.mapIndexed { i, block -> "${CaptureRules.normalize(block.text)}:${block.boundingBox}:${glyphHeights[i]}" }.joinToString("\u0000")
                if (automatic && signature == lastBlocks) {
                    Translator.main.post { if (!stopped && !paused && version == generation.get()) {
                        validatedGeneration = version; inline?.restoreControls(false, true)
                        inline?.setRecognizedText(recognized.text)
                    } }
                    return
                }
                val regions = blocks.map { block ->
                    val box = block.boundingBox!!
                    CaptureRules.textRegion(listOf(intArrayOf(box.left, box.top, box.right, box.bottom)), cropBounds, width, height)
                }
                val chunks = blocks.map { CaptureRules.screenChunks(it.text) }
                val parts = blocks.map { mutableListOf<String>() }
                var completed = 0
                val total = chunks.sumOf { it.size }
                var modelMs = 0L; var allCached = true; var failures = 0
                Translator.main.post { if (!stopped && !paused && version == generation.get()) {
                    inline?.clear(); validatedGeneration = version
                    inline?.setRecognizedText(recognized.text)
                } }
                chunks.forEachIndexed { index, sentences -> sentences.forEach { sentence ->
                    val active = { !stopped && !paused && version == generation.get() }
                    if (!active()) return
                    try {
                        val result = Translator.translate(sentence, active)
                        modelMs += result["ms"] as Long
                        allCached = allCached && result["cached"] == true
                        parts[index].add(result["text"] as String)
                    } catch (error: Exception) {
                        if (!active()) return
                        failures++; allCached = false
                        parts[index].add(CaptureRules.failedTranslation(sentence,error.message))
                    }
                    if (!active()) return
                    completed++
                    val snapshot = parts.mapIndexedNotNull { i, values -> if (values.isEmpty()) null else
                        InlineTranslation(values.joinToString(" "), regions[i], chunks[i].size > 1, glyphHeights[i]) }
                    val processing = completed < total
                    Translator.lastTranslation = snapshot.joinToString("\n\n") { it.text }
                    Translator.translationMs = modelMs; Translator.cachedResult = allCached
                    Translator.totalMs = SystemClock.elapsedRealtime()-start
                    Translator.status(if (processing) "문장 번역 $completed/$total" else "${snapshot.size}개 블록 처리 완료"+if (failures > 0) " · ${failures}개 문장 실패" else "")
                    Translator.main.post { if (!stopped && !paused && version == generation.get()) {
                        inline?.show(snapshot, regions, processing)
                        if (automatic && pendingHash != null) inline?.hideTranslation(true)
                    } }
                } }
                lastText = text
                // Keep failure markers stable; source changes or explicit pause/resume/manual requests retry them.
                lastBlocks = signature
                if (failures > 0) Translator.main.post {
                    if (!stopped && !paused && version == generation.get()) inline?.notice("${failures}개 문장은 원문과 실패 이유로 표시했습니다. 자동 모드는 일시정지 후 재개, 수동 모드는 다시 번역해 주세요.")
                }
                return
            }
            if (!manual) Translator.main.post { if (!stopped && version == generation.get()) overlay.show("번역 중…") }
            val result = Translator.translate(text)
            val translated = result["text"] as String
            val cacheLabel = if (result["cached"] == true) "캐시 재사용 · " else ""
            if (stopped || paused || version != generation.get()) return
            lastText = text
            Translator.totalMs = SystemClock.elapsedRealtime() - start
            Translator.publish()
            Translator.main.post { if (!stopped && version == generation.get()) {
                overlay.show(translated, "${cacheLabel}OCR ${Translator.ocrMs}ms · 번역 ${Translator.translationMs}ms · 전체 ${Translator.totalMs}ms")
            } }
        } catch (e: Exception) {
            if (!stopped && version == generation.get()) {
                paused = !manual
                Translator.status(e.message ?: "화면 번역 실패")
                Translator.main.post { if (!stopped && version == generation.get()) {
                    if (automatic && e is TimeoutException) {
                        pauseAutomatic(true)
                        inline?.status("OCR 처리 시간이 초과되어 일시정지했습니다. 영역을 줄인 뒤 알림에서 재개해 주세요.")
                        return@post
                    }
                    if (!manual) overlay.setPaused(true)
                    showMessage((e.message ?: "번역 실패")+if (manual) " · 영역을 줄이거나 다시 눌러 주세요." else " · 재개를 눌러 주세요.")
                } }
            }
        } finally {
            if (!recycleAfterOcr) bitmap.recycle()
            Translator.busy.set(false); Translator.publish()
            if (manual) Translator.main.post { if (!stopped) inline?.setBusy(false) }
        }
    }
    override fun onDestroy() {
        stopped = true; running = false; automaticRunning = false; automaticPaused = false; generation.incrementAndGet()
        captureHandler.removeCallbacks(poll)
        Translator.main.removeCallbacks(autoPoll)
        Translator.main.removeCallbacks(settlePoll)
        NativeEngine.cancel()
        inline?.close(); inline = null; manualPending = false
        if (::overlay.isInitialized) overlay.close()
        display?.release(); display = null
        projection?.stop(); projection = null
        reader?.close(); reader = null
        captureHandler.post { lastAppFrame?.recycle(); lastAppFrame = null }
        captureThread.quitSafely()
        // The OCR task completes before its recognizer is released.
        Translator.worker.execute { recognizer.close() }
        Translator.status("화면 번역 정지 · 모델은 다음 실행을 위해 유지합니다.")
        super.onDestroy()
    }
}
