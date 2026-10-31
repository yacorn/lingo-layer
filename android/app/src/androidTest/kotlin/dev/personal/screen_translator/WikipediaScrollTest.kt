package dev.personal.screen_translator

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class WikipediaScrollTest {
    @Test fun publicWikipediaScroll() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val ui = inst.uiAutomation
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val url = "https://ja.wikipedia.org/wiki/%E7%8C%AB#top"
        val rows = CopyOnWriteArrayList<JSONObject>()
        val stages = JSONArray()
        val report = JSONObject().put("url", url).put("build", "x86_64 Debug")
            .put("scope", "Public Chrome page, actual OCR/model; onDraw excludes compositor")
        var activity: MainActivity? = null
        var stage = "launch"
        var changedAt = 0L
        fun node(text: String): AccessibilityNodeInfo? {
            fun walk(value: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (value == null || value.packageName?.toString() != context.packageName) return null
                if (value.isVisibleToUser && (value.contentDescription ?: value.text)?.toString() == text) return value
                for (i in 0 until value.childCount) walk(value.getChild(i))?.let { return it }
                return null
            }
            return ui.windows.asSequence().mapNotNull { walk(it.root) }.firstOrNull()
        }
        fun waitFor(timeout: Long, condition: () -> Boolean): Boolean {
            val end = SystemClock.elapsedRealtime()+timeout
            while (!condition() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(100)
            return condition()
        }
        fun click(text: String) {
            val action = when (text) { "자동 번역 일시정지" -> "일시정지"; "번역 메뉴" -> "번역 메뉴"; else -> null }
            if (action != null) {
                context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.single { it.id == 1 }
                    .notification.actions.single { it.title.toString() == action }.actionIntent.send()
                inst.waitForIdleSync(); return
            }
            check(waitFor(10_000) { node(text)?.isEnabled == true }) { "Missing own control: $text" }
            check(node(text)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun source(): String {
            var text = ""
            inst.runOnMainSync {
                val service = WindowInspector.getGlobalWindowViews().map { it.context }.filterIsInstance<ScreenTranslateService>().firstOrNull()
                if (service != null) text = ScreenTranslateService::class.java.getDeclaredField("lastText").apply { isAccessible = true }.get(service).toString()
            }
            return text
        }
        fun browserText(): String {
            val values = linkedSetOf<String>()
            fun walk(value: AccessibilityNodeInfo?) {
                if (value == null || value.packageName?.toString() != "com.android.chrome") return
                val bounds = Rect(); value.getBoundsInScreen(bounds)
                val text = value.text?.toString().orEmpty()
                if (value.isVisibleToUser && bounds.bottom>704 && bounds.top<1232 && CaptureRules.hasJapanese(text)) values.add(text)
                for (i in 0 until value.childCount) walk(value.getChild(i))
            }
            ui.windows.forEach { walk(it.root) }
            return values.joinToString("\n")
        }
        fun visibleHeadings(): Set<String> {
            val values = linkedSetOf<String>()
            fun walk(value: AccessibilityNodeInfo?) {
                if (value == null || value.packageName?.toString() != "com.android.chrome") return
                val bounds = Rect(); value.getBoundsInScreen(bounds)
                val text = value.text?.toString().orEmpty()
                if (value.isVisibleToUser && bounds.top>=704 && bounds.bottom<=1232 && bounds.height() in 20..150 &&
                    bounds.width()>100 && text.length in 2..40 && CaptureRules.hasJapanese(text)) values.add(text)
                for (i in 0 until value.childCount) walk(value.getChild(i))
            }
            ui.windows.forEach { walk(it.root) }
            return values
        }
        fun swipe(duration: Int) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("input swipe 360 1090 360 570 $duration")).use { it.readBytes() }
        }
        fun inspect(id: String, count: Int, duration: Int) {
            stage = id
            val before = rows.size
            val oldSource = source()
            val oldBrowser = browserText()
            changedAt = SystemClock.elapsedRealtime()
            repeat(count) { swipe(duration) }
            val ended = SystemClock.elapsedRealtime()
            val result = JSONObject().put("stage", id).put("swipes", count).put("gestureDurationMs", duration)
                .put("scrollExecutionMs", ended-changedAt).put("previousSource", oldSource)
                .put("oldTranslationStillPresentAtScrollEnd", Translator.lastTranslation)
                .put("completedDrawsAtScrollEnd", rows.size-before)
            val refreshed = waitFor(60_000) { rows.size>before && rows.last().optBoolean("complete") && source()!=oldSource && !Translator.busy.get() }
            result.put("refreshed", refreshed).put("currentSource", source()).put("status", Translator.message)
                .put("browserVisibleTextChanged", browserText()!=oldBrowser)
            if (refreshed) result.put("scrollEndToCompleteMs", rows.last().getLong("drawAt")-ended)
            stages.put(result)
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nWikipedia stage $id: refreshed=$refreshed, status=${Translator.message}\n") })
        }
        try {
            check(Settings.canDrawOverlays(context))
            Translator.initialize(context); Translator.load("aihub")
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            inst.runOnMainSync {
                InlineTranslateOverlay.drawObserver = { values, displayed, time, complete ->
                    rows.add(JSONObject().put("stage", stage).put("drawAt", time).put("sinceStageStartMs", time-changedAt)
                        .put("complete", complete).put("progressive", values.any { it.progressive }).put("text", values.joinToString("\n\n") { it.text })
                        .put("blocks", values.size).put("sourceTop", JSONArray(values.map { it.region.top }))
                        .put("displayTop", JSONArray(displayed.map { it.top })).put("ocrMs", Translator.ocrMs)
                        .put("modelMs", Translator.translationMs).put("cached", Translator.cachedResult))
                }
                MainActivity::class.java.getDeclaredField("manualCapture").apply { isAccessible = true }.setBoolean(activity, true)
                MainActivity::class.java.getDeclaredField("automaticCapture").apply { isAccessible = true }.setBoolean(activity, true)
                MainActivity::class.java.getDeclaredMethod("startCapture", MethodChannel.Result::class.java).apply { isAccessible = true }
                    .invoke(activity, object : MethodChannel.Result {
                        override fun success(result: Any?) {}
                        override fun error(code: String, message: String?, details: Any?) { report.put("consentError", "$code: $message") }
                        override fun notImplemented() {}
                    })
            }
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nApprove entire-screen sharing; only the public Japanese Wikipedia page will be tested.\n") })
            check(waitFor(180_000) { ScreenTranslateService.running }) { "Human screen-sharing approval timed out" }
            changedAt = SystemClock.elapsedRealtime()
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.android.chrome")
                .putExtra("create_new_tab", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            check(waitFor(30_000) { browserText().isNotBlank() }) { "Public Japanese page did not become visible" }
            stage = "initial"; changedAt = SystemClock.elapsedRealtime()
            check(waitFor(60_000) {
                rows.any { it.optString("stage")=="initial" && it.optBoolean("complete") && it.optInt("blocks")>0 } &&
                    source().replace(Regex("\\s+"), "").contains("ネコ")
            }) { "Initial Wikipedia translation unavailable: ${Translator.message}" }
            inspect("slow-1", 1, 650)
            inspect("slow-2", 1, 650)
            inspect("fast-3", 3, 160)
            // A completed batch can describe a frame captured during the fling.
            val expected = visibleHeadings()
            check(expected.size>=2) { "No independently visible headings for final-screen validation" }
            stage = "settled-final"
            val normalize = { text: String -> text.replace(Regex("\\s+"), "") }
            val matched = { expected.filter { normalize(source()).contains(normalize(it)) } }
            val caughtUp = waitFor(60_000) { matched().size>=minOf(3, expected.size) && !Translator.busy.get() }
            report.put("finalScreenMatched", caughtUp).put("expectedVisibleHeadings", JSONArray(expected.toList()))
                .put("matchedVisibleHeadings", JSONArray(matched())).put("finalSource", source())
                .put("fastStartToFinalScreenMatchMs", SystemClock.elapsedRealtime()-changedAt)
            click("자동 번역 일시정지")
            val files = context.getExternalFilesDir(null)!!
            // Save the native overlay render and unmodified public browser screenshot separately.
            val hidden = mutableListOf<Pair<View, Int>>()
            inst.runOnMainSync {
                fun render(view: View) {
                    if (view.contentDescription?.startsWith("한국어 번역:") == true && view.width>0) {
                        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        File(files, "wiki-overlay.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    if (view is ViewGroup) for (i in 0 until view.childCount) render(view.getChildAt(i))
                }
                WindowInspector.getGlobalWindowViews().filter { it.context is ScreenTranslateService }.forEach {
                    render(it); hidden.add(it to it.visibility); it.visibility = View.INVISIBLE
                }
            }
            SystemClock.sleep(200)
            ui.takeScreenshot()?.let { bitmap ->
                File(files, "wiki-source.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            inst.runOnMainSync { hidden.forEach { (view, visibility) -> view.visibility = visibility } }
            click("번역 메뉴"); click("화면 공유와 번역 종료")
            check(waitFor(10_000) { !ScreenTranslateService.running })
            report.put("completed", true).put("stages", stages)
        } catch (error: Throwable) {
            report.put("error", error.toString()).put("stages", stages); throw error
        } finally {
            inst.runOnMainSync {
                context.stopService(Intent(context, ScreenTranslateService::class.java))
                InlineTranslateOverlay.drawObserver = null
                activity?.finish()
            }
            report.put("draws", JSONArray(rows))
            inst.sendStatus(0, Bundle().apply { putString("wikipediaScrollJson", report.toString()) })
        }
    }
}
