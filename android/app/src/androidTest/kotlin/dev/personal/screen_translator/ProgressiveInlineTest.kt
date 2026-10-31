package dev.personal.screen_translator

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ProgressiveInlineTest {
    @Test fun firstSentenceStableBoxAndCancelMovedSource() {
        val arguments = InstrumentationRegistry.getArguments()
        check(Build.HARDWARE in setOf("ranchu", "goldfish") || arguments.getString("allow-physical-device") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val rows = CopyOnWriteArrayList<JSONObject>()
        val report = JSONObject().put("scope", "Authored fixture; actual OCR/model; onDraw excludes compositor")
            .put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
        var activity: MainActivity? = null
        var layoutOverlay: InlineTranslateOverlay? = null
        var fixture: View? = null
        var source = "少し散歩しませんか？"
        var stage = "warm"
        var changedAt = 0L
        val paragraph = "今日は朝から雨が降っています。午後には近くの公園へ行って、友達とゆっくり話をする予定です。"+
            "公園の入り口では、昨日買った新しい本を読んで待っています。帰りには駅の前にあるお店で、夕食に使う新鮮な野菜と果物を買います。また明日、ここで会いましょう。"
        fun waitFor(message: String, timeout: Long = 60_000, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime()+timeout
            while (!condition() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(50)
            check(condition()) { "$message / ${Translator.message}" }
        }
        fun outputPresent(): Boolean {
            var present = false
            inst.runOnMainSync {
                fun walk(view: View) {
                    if (view.contentDescription?.startsWith("한국어 번역:") == true) present = true
                    if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
                }
                WindowInspector.getGlobalWindowViews().forEach(::walk)
            }
            return present
        }
        fun generationAndVisible(): Pair<Int, Boolean> {
            var generation = -1
            var visible = false
            inst.runOnMainSync {
                fun walk(view: View) {
                    if (view.isShown && view.contentDescription?.startsWith("한국어 번역:") == true) visible = true
                    if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
                }
                WindowInspector.getGlobalWindowViews().forEach { root ->
                    if (root.context is ScreenTranslateService) {
                        generation = (ScreenTranslateService::class.java.getDeclaredField("generation").apply { isAccessible = true }
                            .get(root.context) as java.util.concurrent.atomic.AtomicInteger).get()
                    }
                    walk(root)
                }
            }
            return generation to visible
        }
        fun update(id: String, text: String) {
            inst.runOnMainSync { stage = id; source = text; changedAt = SystemClock.elapsedRealtime(); fixture!!.invalidate() }
        }
        try {
            check(Settings.canDrawOverlays(context))
            Translator.initialize(context); Translator.load("aihub")
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            inst.runOnMainSync {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                fun detach(view: View) {
                    if (view is io.flutter.embedding.android.FlutterView) view.detachFromFlutterEngine()
                    if (view is ViewGroup) for (i in 0 until view.childCount) detach(view.getChildAt(i))
                }
                detach(activity.window.decorView)
                fixture = object : View(activity) {
                    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawColor(Color.WHITE); paint.textSize = width/36f
                        val layout = StaticLayout.Builder.obtain(source, 0, source.length, paint, width-48)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0f, 1f).build()
                        canvas.save(); canvas.translate(24f, height*.60f); layout.draw(canvas); canvas.restore()
                    }
                }
                activity.setContentView(fixture)
                InlineTranslateOverlay.drawObserver = { values, displayed, time, complete ->
                    rows.add(JSONObject().put("stage", stage).put("elapsedMs", time-changedAt).put("complete", complete)
                        .put("text", values.joinToString("\n\n") { it.text }).put("blocks", values.size)
                        .put("progressive", values.any { it.progressive }).put("ocrMs", Translator.ocrMs)
                        .put("modelMs", Translator.translationMs).put("cached", Translator.cachedResult)
                        .put("box", displayed.firstOrNull()?.let { JSONArray(listOf(it.left,it.top,it.right,it.bottom)) }))
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
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nApprove full-screen sharing for the authored sentence-progress and cancellation test.\n") })
            waitFor("Human approval timed out", 180_000) { ScreenTranslateService.running }
            changedAt = SystemClock.elapsedRealtime()
            waitFor("Warm translation unavailable") { rows.any { it.optBoolean("complete") && it.optString("text").contains("산책") } }
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nProgressive: warm translation drawn.\n") })
            update("blank", "")
            waitFor("Blank source did not clear") { !outputPresent() }
            update("paragraph", paragraph)
            waitFor("No completed paragraph", 150_000) { rows.any { it.optString("stage")=="paragraph" && it.optBoolean("complete") } }
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nProgressive: paragraph complete.\n") })
            val paragraphRows = rows.filter { it.optString("stage")=="paragraph" && it.optInt("blocks")>0 }
            assertTrue("First sentence was not displayed before completion", paragraphRows.any { !it.getBoolean("complete") })
            val box = paragraphRows.first().getJSONArray("box").toString()
            assertTrue("Paragraph box moved while appending", paragraphRows.all { it.getJSONArray("box").toString()==box })
            report.put("firstPartialDrawMs", paragraphRows.first().getLong("elapsedMs"))
                .put("firstLongSentenceDrawMs", paragraphRows.firstOrNull { it.optBoolean("progressive") }?.optLong("elapsedMs"))
                .put("paragraphCompleteDrawMs", paragraphRows.last().getLong("elapsedMs"))
                .put("firstOcrBlockBoxStable", true).put("paragraphDisplayedBlocks", paragraphRows.last().getInt("blocks"))
            if (arguments.getString("measure-visibility") == "true") {
                val visibility = JSONObject().put("scope", "Own translation View visibility, sampled every 16ms; not panel video")
                val intervals = JSONArray()
                val finished = java.util.concurrent.CountDownLatch(1)
                val started = SystemClock.elapsedRealtime()
                var hiddenAt = -1L
                var hiddenMs = 0L
                var samples = 0
                var visibleSamples = 0
                var transitions = 0
                val probe = object : Runnable {
                    override fun run() {
                        val now = SystemClock.elapsedRealtime()
                        fun shown(view: View): Boolean {
                            if (!view.isShown) return false
                            if (view.contentDescription?.startsWith("한국어 번역:") == true) return true
                            return view is ViewGroup && (0 until view.childCount).any { shown(view.getChildAt(it)) }
                        }
                        val visible = WindowInspector.getGlobalWindowViews().filter { it.context is ScreenTranslateService }.any(::shown)
                        samples++
                        if (visible) visibleSamples++
                        if (!visible && hiddenAt < 0) { hiddenAt = now; transitions++ }
                        if (visible && hiddenAt >= 0) {
                            val duration = now-hiddenAt
                            hiddenMs += duration; intervals.put(duration); hiddenAt = -1
                        }
                        if (now-started >= 6_000) {
                            if (hiddenAt >= 0) { hiddenMs += now-hiddenAt; intervals.put(now-hiddenAt) }
                            visibility.put("durationMs", now-started).put("samples", samples).put("visibleSamples", visibleSamples)
                                .put("hiddenTransitions", transitions).put("hiddenMs", hiddenMs).put("hiddenIntervalsMs", intervals)
                                .put("uninterruptedDisplayPassed", transitions == 0 && visibleSamples == samples)
                            finished.countDown()
                        } else Translator.main.postDelayed(this, 16)
                    }
                }
                Translator.main.post(probe)
                try { check(finished.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Visibility probe timed out" } }
                finally { Translator.main.removeCallbacks(probe) }
                report.put("steadyScreenVisibility", visibility)
            }
            update("blank-again", "")
            waitFor("Blank source did not clear") { !outputPresent() }
            update("cached-paragraph", paragraph)
            waitFor("Cached paragraph unavailable") { rows.any { it.optString("stage")=="cached-paragraph" && it.optBoolean("complete") && it.optBoolean("cached") } }
            val second = "昨日は友達と図書館に行きました。そこで、日本の歴史について書かれた本を何冊も探しました。"+
                "図書館の窓からは大きな川と新しい橋が見えていました。帰る前に、受付で本の返却日を確認しました。来週は弟と一緒に、別の図書館にも行く予定です。"
            update("cancelled-paragraph", second)
            waitFor("No partial result to cancel", 90_000) { rows.any { it.optString("stage")=="cancelled-paragraph" && !it.optBoolean("complete") && it.optInt("blocks")>0 } }
            assertTrue("Cancellation did not occur during inference", Translator.busy.get())
            val oldGeneration = generationAndVisible().first
            update("after-move", "少し散歩しませんか？")
            waitFor("Changed source did not hide the old overlay", 30_000) {
                val (generation, visible) = generationAndVisible()
                generation>oldGeneration && !visible
            }
            report.put("oldOverlayHiddenMs", SystemClock.elapsedRealtime()-changedAt)
            waitFor("Latest source did not replace old paragraph", 60_000) {
                rows.any { it.optString("stage")=="after-move" && it.optBoolean("complete") && it.optString("text").contains("산책") }
            }
            assertFalse("Old paragraph completed after screen moved", rows.any {
                it.optString("stage")=="after-move" && it.optLong("elapsedMs")>report.getLong("oldOverlayHiddenMs") && it.optBoolean("complete") && !it.optString("text").contains("산책")
            })
            report.put("cancelAndLatestSourcePassed", true).put("sentenceCachePassed", true)
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nProgressive: cancellation and latest source passed.\n") })
            inst.runOnMainSync { context.stopService(Intent(context, ScreenTranslateService::class.java)) }
            waitFor("Service did not stop", 10_000) { !ScreenTranslateService.running }
            inst.runOnMainSync {
                stage = "layout-only"; changedAt = SystemClock.elapsedRealtime()
                layoutOverlay = InlineTranslateOverlay(activity!!, {}, {}, {}, {})
                layoutOverlay!!.show(listOf(InlineTranslation("첫 문장입니다.", Region(.05f,.60f,.95f,.80f), true)), listOf(Region(.05f,.60f,.95f,.80f)), true)
            }
            waitFor("Partial fixed-box render unavailable", 5_000) { rows.any { it.optString("stage")=="layout-only" } }
            val layoutBox = rows.last().getJSONArray("box").toString()
            inst.runOnMainSync {
                layoutOverlay!!.show(listOf(InlineTranslation("첫 문장입니다. "+"뒤 문장을 같은 상자에 추가합니다. ".repeat(12), Region(.05f,.60f,.95f,.80f), true)), listOf(Region(.05f,.60f,.95f,.80f)), false)
            }
            waitFor("Complete fixed-box render unavailable", 5_000) { rows.last().optString("stage")=="layout-only" && rows.last().optBoolean("complete") }
            val initialBox = JSONArray(layoutBox)
            val finalBox = rows.last().getJSONArray("box")
            assertEquals(initialBox.getDouble(0),finalBox.getDouble(0),.001)
            assertEquals(initialBox.getDouble(1),finalBox.getDouble(1),.001)
            assertTrue(finalBox.getDouble(2)>=initialBox.getDouble(2) && finalBox.getDouble(3)>=initialBox.getDouble(3))
            report.put("progressiveAnchorRenderPassed", true).put("anchorScope", "Overlay render with supplied Korean text; grows down/right with a fixed source anchor")
            report.put("functionalPassed", true).put("passed", true)
            if (arguments.getString("require-steady-overlay") == "true") {
                assertTrue("Own translation repeatedly hidden on unchanged source", report.getJSONObject("steadyScreenVisibility").getBoolean("uninterruptedDisplayPassed"))
            }
        } catch (error: Throwable) {
            report.put("passed", false).put("error", error.toString()); throw error
        } finally {
            inst.runOnMainSync {
                context.stopService(Intent(context, ScreenTranslateService::class.java))
                layoutOverlay?.close()
                InlineTranslateOverlay.drawObserver = null; activity?.finish()
            }
            report.put("draws", JSONArray(rows))
            inst.sendStatus(0, Bundle().apply { putString("progressiveInlineJson", report.toString()) })
        }
    }
}
