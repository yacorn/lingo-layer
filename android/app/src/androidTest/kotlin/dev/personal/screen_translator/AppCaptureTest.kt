package dev.personal.screen_translator

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ImageReader
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AppCaptureTest {
    @Test fun selectedAppExcludesVisibleOverlays() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        var activity: MainActivity? = null
        var marker: View? = null
        var overlay: InlineTranslateOverlay? = null
        var fixture: View? = null
        var source = "今日はいい天気ですね。"
        val expectedFull = InstrumentationRegistry.getArguments().getString("expected-capture") == "full"
        val report = JSONObject().put("scope", "Authored Japanese fixture only; pixel counts and OCR, no screen image saved")
        val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        fun field(service: ScreenTranslateService, name: String) = ScreenTranslateService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
        fun waitFor(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime()+30_000
            while (!condition() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50)
            check(condition()) { "$message / ${Translator.message}" }
        }
        fun notificationAction(label: String) {
            context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.single { it.id == 1 }
                .notification.actions.single { it.title.toString() == label }.actionIntent.send()
            inst.waitForIdleSync()
        }
        fun sample(reader: ImageReader): Bitmap {
            var result: Bitmap? = null
            val after = System.nanoTime()
            val deadline = SystemClock.elapsedRealtime()+5_000
            while (result == null && SystemClock.elapsedRealtime()<deadline) {
                inst.runOnMainSync { fixture!!.invalidate() }
                val image = reader.acquireLatestImage()
                if (image != null) {
                    if (image.timestamp < after) { image.close(); SystemClock.sleep(50); continue }
                    try {
                        val plane = image.planes[0]
                        val padded = Bitmap.createBitmap(plane.rowStride/plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                        try {
                            val bytes = java.nio.ByteBuffer.allocate(plane.rowStride*image.height)
                            bytes.put(plane.buffer); bytes.rewind(); padded.copyPixelsFromBuffer(bytes)
                            result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height).copy(Bitmap.Config.ARGB_8888, false)
                        } finally { padded.recycle() }
                    } finally { image.close() }
                } else SystemClock.sleep(50)
            }
            return checkNotNull(result) { "No authored capture frame" }
        }
        try {
            Translator.initialize(context); Translator.load("aihub")
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            inst.runOnMainSync {
                activity!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                fun detach(view: View) {
                    if (view is io.flutter.embedding.android.FlutterView) view.detachFromFlutterEngine()
                    if (view is ViewGroup) for (i in 0 until view.childCount) detach(view.getChildAt(i))
                }
                detach(activity!!.window.decorView)
                fixture = object : View(activity) {
                    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawColor(Color.WHITE)
                        canvas.drawText(source, 40f, height*.65f, ink)
                    }
                }
                activity!!.setContentView(fixture)
                MainActivity::class.java.getDeclaredField("manualCapture").apply { isAccessible = true }.setBoolean(activity, true)
                MainActivity::class.java.getDeclaredField("automaticCapture").apply { isAccessible = true }.setBoolean(activity, true)
                MainActivity::class.java.getDeclaredMethod("startCapture", MethodChannel.Result::class.java).apply { isAccessible = true }
                    .invoke(activity, object : MethodChannel.Result {
                        override fun success(result: Any?) {}
                        override fun error(code: String, message: String?, details: Any?) { report.put("consentError", "$code: $message") }
                        override fun notImplemented() {}
                    })
            }
            inst.sendStatus(0, Bundle().apply { putString("stream", if (expectedFull) "\nChoose entire screen for fallback test.\n" else "\nChoose SINGLE APP, then the authored 화면 번역 app. Do not choose entire screen.\n") })
            val deadline = SystemClock.elapsedRealtime()+180_000
            while (!ScreenTranslateService.running && !report.has("consentError") && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
            check(ScreenTranslateService.running) { "Share consent unavailable: $report" }
            lateinit var service: ScreenTranslateService
            inst.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() ?: activity
                fun detach(view: View) {
                    if (view is io.flutter.embedding.android.FlutterView) view.detachFromFlutterEngine()
                    if (view is ViewGroup) for (i in 0 until view.childCount) detach(view.getChildAt(i))
                }
                detach(activity!!.window.decorView)
                activity!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                fixture = object : View(activity) {
                    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawColor(Color.WHITE)
                        canvas.drawText(source, 40f, height*.65f, ink)
                    }
                }
                activity!!.setContentView(fixture)
                service = WindowInspector.getGlobalWindowViews().map { it.context }.filterIsInstance<ScreenTranslateService>().first()
                overlay = field(service, "inline") as InlineTranslateOverlay
            }
            waitFor("No actual first translation") { Translator.lastTranslation.contains("날씨") && !Translator.busy.get() }
            check(field(service,"captureModeChecked") == true)
            check(field(service,"hideOverlaysForCapture") == expectedFull) { "Wrong capture mode detected" }
            val resultView = InlineTranslateOverlay::class.java.getDeclaredField("inline").apply { isAccessible = true }.get(overlay) as View
            var hidden = 0; var samples = 0
            val steadyEnd = SystemClock.elapsedRealtime()+6_000
            while (SystemClock.elapsedRealtime()<steadyEnd) {
                inst.runOnMainSync { if (!resultView.isShown) hidden++; samples++ }
                SystemClock.sleep(16)
            }
            report.put("steadySamples",samples).put("hiddenSamples",hidden).put("fullScreenFallback",expectedFull)
            check(if (expectedFull) hidden > 0 else hidden == 0) { "Unexpected stationary overlay visibility" }
            notificationAction("일시정지")
            waitFor("Notification pause failed") { ScreenTranslateService.automaticPaused && !Translator.busy.get() }
            inst.runOnMainSync {
                val bubble = InlineTranslateOverlay::class.java.getDeclaredField("bubble").apply { isAccessible = true }.get(overlay) as View
                check(!bubble.isShown) { "Automatic floating button remains" }
                if (expectedFull) overlay!!.hideTranslation()
                marker = View(activity).apply { setBackgroundColor(Color.MAGENTA) }
                val params = WindowManager.LayoutParams(320, 280, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT; x = 180; y = 450 }
                activity!!.getSystemService(WindowManager::class.java).addView(marker, params)
                if (!expectedFull) overlay!!.show(listOf(InlineTranslation("한국어 번역 표시 검사", Region(.04f,.60f,.8f,.72f))), listOf(Region(.04f,.60f,.8f,.72f)), false)
            }
            SystemClock.sleep(700)
            val bitmap = sample(field(service, "reader") as ImageReader)
            try {
                val pixels = IntArray(bitmap.width*bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val magenta = pixels.count { Color.red(it)>220 && Color.blue(it)>220 && Color.green(it)<50 }
                val white = pixels.count { Color.red(it)>220 && Color.green(it)>220 && Color.blue(it)>220 }
                val ocr = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 15, TimeUnit.SECONDS).text
                val location = IntArray(2)
                inst.runOnMainSync { activity!!.window.decorView.findViewById<View>(android.R.id.content).getLocationOnScreen(location) }
                report.put("width", bitmap.width).put("height", bitmap.height).put("contentX",location[0]).put("contentY",location[1])
                    .put("magentaPixels",magenta).put("whitePixels",white).put("ocr",ocr)
                    .put("markerCenterArgb",Integer.toHexString(bitmap.getPixel(340,590)))
                check(if (expectedFull) magenta > 0 else magenta == 0) { "Unexpected colored overlay capture" }
                check(white > pixels.size*.8) { "Captured source blanked by secure overlay" }
                check(ocr.contains("天気")) { "Japanese source missing under translation: $ocr" }
                check(!ocr.contains("한국어")) { "Translation entered capture: $ocr" }
            } finally { bitmap.recycle() }
            inst.runOnMainSync { activity!!.getSystemService(WindowManager::class.java).removeView(marker) }
            notificationAction("재개")
            waitFor("Notification resume/cache failed") { !ScreenTranslateService.automaticPaused && Translator.lastTranslation.contains("날씨") && Translator.cachedResult && !Translator.busy.get() }
            inst.runOnMainSync { source = "少し散歩しませんか？"; fixture!!.invalidate() }
            waitFor("Changed source did not translate") { Translator.lastTranslation.contains("산책") && !Translator.busy.get() }
            notificationAction("번역 메뉴")
            inst.runOnMainSync {
                val menu = InlineTranslateOverlay::class.java.getDeclaredField("menu").apply { isAccessible = true }.get(overlay) as ViewGroup
                check(menu.isShown)
                val copy = (0 until menu.childCount).map { menu.getChildAt(it) }.single { it.contentDescription == "마지막 번역 복사" }
                copy.performClick()
                check(context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString().contains("산책"))
            }
            notificationAction("종료")
            waitFor("Notification stop failed") { !ScreenTranslateService.running }
            report.put("notificationPauseResumeMenuCopyStopPassed",true).put("changedSourceAndCachePassed",true).put("passed",true)
        } catch (error: Throwable) { report.put("passed",false).put("error",error.toString()); throw error }
        finally {
            inst.runOnMainSync {
                if (marker?.isAttachedToWindow == true) activity!!.getSystemService(WindowManager::class.java).removeView(marker)
                context.stopService(Intent(context, ScreenTranslateService::class.java)); activity?.finish()
            }
            recognizer.close()
            inst.sendStatus(0, Bundle().apply { putString("appCaptureJson", report.toString()) })
        }
    }
}
