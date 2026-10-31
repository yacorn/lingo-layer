package dev.personal.screen_translator

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ManualInlineTest {
    @Test fun authoredScreenButtonFlow() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val ui = inst.uiAutomation
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val rows = JSONArray()
        val report = JSONObject().put("scope", "Own authored fixture; tap to overlay draw, compositor excluded").put("results", rows)
        var activity: MainActivity? = null
        var fixture: View? = null
        var source = "今日はいい天気ですね。"
        var multiple = false
        var touches = 0
        val accepted = AtomicBoolean(false)
        val consentError = AtomicReference<String?>()
        val waiting = AtomicReference<CountDownLatch?>()
        val output = AtomicReference<JSONObject?>()
        var tappedAt = 0L
        var firstDrawAt = 0L
        fun waitFor(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime()+10_000
            while (!condition() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50)
            check(condition()) { message }
        }
        fun find(description: String): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isVisibleToUser && node.packageName?.toString() == context.packageName && node.contentDescription?.toString() == description) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { child -> walk(child)?.let { return it } }
                return null
            }
            return ui.windows.asSequence().mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }
                .mapNotNull { walk(it) }.firstOrNull()
        }
        fun bounds(description: String): Rect {
            waitFor("Missing own control: $description") {
                find(description)?.let { node -> Rect().also { node.getBoundsInScreen(it) }.let { it.width()>0 && it.height()>0 } } == true
            }
            return Rect().also { find(description)!!.getBoundsInScreen(it) }
        }
        fun touch(x: Float, y: Float, endX: Float = x, endY: Float = y) {
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, px: Float, py: Float) {
                val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0)
                e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { check(ui.injectInputEvent(e, true)) } finally { e.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, x, y)
            if (x != endX || y != endY) { SystemClock.sleep(80); event(MotionEvent.ACTION_MOVE, endX, endY); SystemClock.sleep(80) }
            event(MotionEvent.ACTION_UP, endX, endY)
        }
        fun click(description: String) {
            val rect = bounds(description); touch(rect.exactCenterX(), rect.exactCenterY())
        }
        fun translate(id: String, expected: String, cached: Boolean) {
            waitFor("Worker did not finish") { !Translator.busy.get() }
            val latch = CountDownLatch(1); output.set(null); waiting.set(latch); firstDrawAt = 0L
            val rect = bounds("선택 영역 번역. 끌어서 버튼 이동")
            tappedAt = SystemClock.elapsedRealtime(); touch(rect.exactCenterX(), rect.exactCenterY())
            if (!latch.await(20, TimeUnit.SECONDS)) {
                val diagnostic = JSONObject().put("ocrMs", Translator.ocrMs).put("busy", Translator.busy.get())
                inst.runOnMainSync {
                    android.view.inspector.WindowInspector.getGlobalWindowViews().map { it.context }.filterIsInstance<ScreenTranslateService>().firstOrNull()?.let { service ->
                        for (field in listOf("region", "manualPending", "paused", "lastText", "screenWidth", "screenHeight")) {
                            diagnostic.put(field, ScreenTranslateService::class.java.getDeclaredField(field).apply { isAccessible = true }.get(service).toString())
                        }
                    }
                    val location = IntArray(2); fixture!!.getLocationOnScreen(location)
                    diagnostic.put("fixtureY", location[1]).put("fixtureHeight", fixture!!.height)
                }
                throw IllegalStateException("No inline result: $id / ${Translator.message} / $diagnostic")
            }
            val row = output.get()!!.put("id", id).put("source", source).put("multiple", multiple)
            assertTrue(row.getString("text"), row.getString("text").contains(expected))
            assertEquals(cached, row.getBoolean("cached"))
            rows.put(row)
        }
        try {
            check(Settings.canDrawOverlays(context))
            Translator.initialize(context); Translator.load("aihub")
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            inst.runOnMainSync {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Release Flutter accessibility callbacks before replacing its view with the fixture.
                fun detach(view: View) {
                    if (view is io.flutter.embedding.android.FlutterView) view.detachFromFlutterEngine()
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) detach(view.getChildAt(i))
                }
                detach(activity.window.decorView)
                fixture = object : View(activity) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawColor(Color.WHITE)
                        if (multiple) {
                            canvas.drawText("今日はいい天気ですね。", 32f, height*.58f, paint)
                            canvas.drawText("今日はいい天気ですね。", 72f, height*.70f, paint)
                            canvas.drawText(source, 32f, height*.82f, paint)
                        } else canvas.drawText(source, 32f, height*.60f, paint)
                    }
                    override fun onTouchEvent(event: MotionEvent): Boolean { if (event.actionMasked == MotionEvent.ACTION_UP) { touches++; performClick() }; return true }
                    override fun performClick(): Boolean { super.performClick(); return true }
                }
                activity.setContentView(fixture)
                InlineTranslateOverlay.drawObserver = { values, displayed, time, complete ->
                    if (values.isNotEmpty() && firstDrawAt == 0L) firstDrawAt = time
                    if (complete) waiting.getAndSet(null)?.let { latch ->
                        val text = values.joinToString("\n\n") { it.text }
                        fun jsonRegion(region: Region) = JSONArray(listOf(region.left, region.top, region.right, region.bottom))
                        val region = values.first().region
                        output.set(JSONObject().put("text", text).put("tapToDrawMs", time-tappedAt).put("firstDrawMs", firstDrawAt-tappedAt)
                            .put("cached", Translator.cachedResult).put("ocrMs", Translator.ocrMs).put("translationMs", Translator.translationMs)
                            .put("region", jsonRegion(region)).put("blocks", JSONArray(values.map { JSONObject().put("text", it.text).put("region", jsonRegion(it.region)) }))
                            .put("displayed", JSONArray(displayed.map { jsonRegion(it) })))
                        latch.countDown()
                    }
                }
                MainActivity::class.java.getDeclaredField("manualCapture").apply { isAccessible = true }.setBoolean(activity, true)
                MainActivity::class.java.getDeclaredMethod("startCapture", MethodChannel.Result::class.java).apply { isAccessible = true }
                    .invoke(activity, object : MethodChannel.Result {
                        override fun success(result: Any?) { accepted.set(true) }
                        override fun error(code: String, message: String?, details: Any?) { consentError.set("$code: $message") }
                        override fun notImplemented() { consentError.set("Not implemented") }
                    })
            }
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nApprove entire-screen sharing for this authored manual UI test.\n") })
            val deadline = SystemClock.elapsedRealtime()+180_000
            while ((!accepted.get() || !ScreenTranslateService.running) && consentError.get()==null && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
            check(accepted.get() && ScreenTranslateService.running) { consentError.get() ?: "Screen sharing consent timed out" }
            SystemClock.sleep(1_000)
            assertNull("Manual mode must not translate automatically", output.get())
            assertEquals("", Translator.lastTranslation)
            translate("first", "날씨", false)
            val region = output.get()!!.getJSONArray("region")
            assertTrue(region.getDouble(0) < .1 && region.getDouble(1) in .5.. .7)
            assertTrue(region.getDouble(2) > .3 && region.getDouble(3) < .8)
            val metrics = context.resources.displayMetrics
            val beforeTouches = touches
            touch((region.getDouble(0)+region.getDouble(2)).toFloat()/2*metrics.widthPixels,
                (region.getDouble(1)+region.getDouble(3)).toFloat()/2*metrics.heightPixels)
            inst.waitForIdleSync(); assertEquals(beforeTouches+1, touches)
            translate("same-source", "날씨", true)
            click("번역 메뉴"); click("마지막 번역 복사")
            inst.runOnMainSync { assertEquals(output.get()!!.getString("text"), context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
            // Android's clipboard preview is outside our overlay and can cover the authored source.
            SystemClock.sleep(4_000)
            click("번역 메뉴"); click("번역을 숨기고 원문 보기")
            assertNull(find("한국어 번역: ${output.get()!!.getString("text")}"))
            inst.runOnMainSync { source = "少し散歩しませんか？"; fixture!!.invalidate() }
            SystemClock.sleep(300)
            translate("changed-source", "산책", false)
            click("번역 메뉴"); click("번역할 영역 선택")
            val selection = bounds("번역할 영역을 드래그하세요. 짧게 누르면 취소합니다.")
            inst.waitForIdleSync()
            val frames = CountDownLatch(1)
            inst.runOnMainSync {
                val selector = android.view.inspector.WindowInspector.getGlobalWindowViews().filterIsInstance<RegionSelector>().single()
                selector.postOnAnimation { selector.postOnAnimation { frames.countDown() } }
            }
            check(frames.await(5, TimeUnit.SECONDS)) { "Region selection did not render" }
            // Start away from Android's back-gesture edges.
            touch(selection.left+selection.width()*.90f, selection.top+selection.height()*.85f,
                selection.left+selection.width()*.02f, selection.top+selection.height()*.50f)
            SystemClock.sleep(400)
            translate("selected-region", "산책", true)
            waitFor("Worker did not finish") { !Translator.busy.get() }
            // Cold block cache for the multi-bubble timing; repeated text still shares one inference.
            (Translator::class.java.getDeclaredField("cache").apply { isAccessible = true }.get(Translator) as MutableMap<*, *>).clear()
            // Three independent positions, including the same source twice, must not be merged by the cache.
            inst.runOnMainSync { multiple = true; source = "少し散歩しませんか？"; fixture!!.invalidate() }
            SystemClock.sleep(300)
            // Include all three authored bubbles inside the selected ROI ending at .85.
            translate("three-blocks", "날씨", false)
            val blockRows = output.get()!!.getJSONArray("blocks")
            assertEquals(3, blockRows.length())
            assertTrue(blockRows.getJSONObject(0).getString("text").contains("날씨"))
            assertTrue(blockRows.getJSONObject(1).getString("text").contains("날씨"))
            assertTrue(blockRows.getJSONObject(2).getString("text").contains("산책"))
            val drawn = output.get()!!.getJSONArray("displayed")
            assertEquals(3, drawn.length())
            for (i in 0 until drawn.length()) for (j in i+1 until drawn.length()) {
                val a = drawn.getJSONArray(i); val b = drawn.getJSONArray(j)
                assertTrue("Translation boxes overlap", a.getDouble(2) <= b.getDouble(0) || b.getDouble(2) <= a.getDouble(0) ||
                    a.getDouble(3) <= b.getDouble(1) || b.getDouble(3) <= a.getDouble(1))
            }
            for (i in 0 until blockRows.length()) {
                val area = blockRows.getJSONObject(i).getJSONArray("region")
                assertTrue(area.getDouble(1) < .60+i*.12 && area.getDouble(1) > .50+i*.12)
            }
            translate("three-blocks-cached", "날씨", true)
            click("번역 메뉴"); click("마지막 번역 복사")
            inst.runOnMainSync { assertEquals(output.get()!!.getString("text"), context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
            SystemClock.sleep(4_000)
            inst.runOnMainSync { source = "今日はいい天気です。"; fixture!!.invalidate() }
            SystemClock.sleep(300)
            translate("only-third-changed", "날씨", false)
            assertEquals(3, output.get()!!.getJSONArray("blocks").length())
            assertTrue(output.get()!!.getJSONArray("blocks").getJSONObject(2).getString("text").contains("날씨"))
            translate("changed-third-cached", "날씨", true)
            val old = bounds("선택 영역 번역. 끌어서 버튼 이동")
            touch(old.exactCenterX(), old.exactCenterY(), old.exactCenterX()+150, old.exactCenterY()+150)
            val moved = bounds("선택 영역 번역. 끌어서 버튼 이동")
            assertTrue(moved.left > old.left && moved.top > old.top)
            click("번역 메뉴"); click("화면 공유와 번역 종료")
            waitFor("Capture service did not stop") { !ScreenTranslateService.running }
            waitFor("Floating controls remain after stop") { find("번역 메뉴") == null }
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("error", error.toString()); throw error
        } finally {
            inst.runOnMainSync {
                context.stopService(Intent(context, ScreenTranslateService::class.java))
                InlineTranslateOverlay.drawObserver = null
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); activity?.finish()
            }
            inst.sendStatus(0, Bundle().apply { putString("manualInlineJson", report.toString()) })
        }
    }
}
