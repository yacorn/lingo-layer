package dev.personal.screen_translator

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
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
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AutomaticInlineTest {
    @Test fun authoredEmulatorScreenChangesWithoutTranslateTaps() {
        // This fixture replaces the activity view only on an emulator.
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val ui = inst.uiAutomation
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val rows = CopyOnWriteArrayList<JSONObject>()
        val consentError = AtomicReference<String?>()
        val report = JSONObject().put("device", "x86_64 emulator").put("realOcrAndModel", true)
        var activity: MainActivity? = null
        var fixture: View? = null
        var source = "今日はいい天気ですね。"
        var lineY = .62f
        var secondLine = false
        var decoration = false
        var changedAt = 0L
        fun waitFor(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime()+25_000
            while (!condition() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50)
            check(condition()) { "$message / ${Translator.message} / $consentError" }
        }
        fun find(text: String, packages: Set<String> = setOf(context.packageName)): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null || node.packageName?.toString() !in packages) return null
                if (node.isVisibleToUser && (node.contentDescription ?: node.text)?.toString() == text) return node
                for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                return null
            }
            return ui.windows.asSequence().mapNotNull { walk(it.root) }.firstOrNull()
        }
        fun click(text: String) {
            val action = when (text) { "자동 번역 일시정지" -> "일시정지"; "자동 번역 재개" -> "재개"; "번역 메뉴" -> "번역 메뉴"; else -> null }
            if (action != null) {
                context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.single { it.id == 1 }
                    .notification.actions.single { it.title.toString() == action }.actionIntent.send()
                inst.waitForIdleSync(); return
            }
            waitFor("Missing own control $text") { find(text)?.isEnabled == true }
            check(find(text)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun drag(x: Float, y: Float, endX: Float, endY: Float) {
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, px: Float, py: Float) {
                val value = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0)
                value.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { check(ui.injectInputEvent(value, true)) } finally { value.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, x, y)
            SystemClock.sleep(80); event(MotionEvent.ACTION_MOVE, endX, endY); SystemClock.sleep(80)
            event(MotionEvent.ACTION_UP, endX, endY)
        }
        fun update(text: String, y: Float = lineY) {
            inst.runOnMainSync { source = text; lineY = y; changedAt = SystemClock.elapsedRealtime(); fixture!!.invalidate() }
        }
        fun expectDraw(after: Int, keyword: String): JSONObject {
            waitFor("No automatic draw containing $keyword") { rows.size>after && rows.last().getString("text").contains(keyword) }
            return rows.last()
        }
        fun shown(requireVisible: Boolean = true): Boolean {
            var result = false
            inst.runOnMainSync {
                fun walk(view: View) {
                    if ((!requireVisible || view.isShown) && view.contentDescription?.startsWith("한국어 번역:") == true) result = true
                    if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
                }
                WindowInspector.getGlobalWindowViews().forEach(::walk)
            }
            return result
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
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawColor(Color.WHITE)
                        paint.textSize = width/16f
                        canvas.drawText(source, 16f, height*lineY, paint)
                        if (secondLine) canvas.drawText("少し散歩しませんか？", 16f, height*.86f, paint)
                        if (decoration) { paint.color = Color.BLUE; canvas.drawRect(0f,height*.95f,40f,height.toFloat(),paint); paint.color = Color.BLACK }
                    }
                }
                activity.setContentView(fixture)
                InlineTranslateOverlay.drawObserver = { values, regions, time, complete ->
                    if (complete && values.isNotEmpty()) rows.add(JSONObject()
                        .put("text", values.joinToString("\n\n") { it.text }).put("blocks", values.size)
                        .put("changeToDrawMs", time-changedAt).put("captureToTranslationMs", Translator.totalMs)
                        .put("ocrMs", Translator.ocrMs).put("translationMs", Translator.translationMs).put("cached", Translator.cachedResult)
                        .put("sourceTop", values.first().region.top).put("displayTop", regions.first().top))
                }
                MainActivity::class.java.getDeclaredField("manualCapture").apply { isAccessible = true }.setBoolean(activity, true)
                MainActivity::class.java.getDeclaredField("automaticCapture").apply { isAccessible = true }.setBoolean(activity, true)
                changedAt = SystemClock.elapsedRealtime()
                MainActivity::class.java.getDeclaredMethod("startCapture", MethodChannel.Result::class.java).apply { isAccessible = true }
                    .invoke(activity, object : MethodChannel.Result {
                        override fun success(result: Any?) {}
                        override fun error(code: String, message: String?, details: Any?) { consentError.set("$code: $message") }
                        override fun notImplemented() { consentError.set("Not implemented") }
                    })
            }
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nApprove full-screen sharing on the emulator for the authored automatic translation fixture.\n") })
            val consentDeadline = SystemClock.elapsedRealtime()+180_000
            while (!ScreenTranslateService.running && consentError.get()==null && SystemClock.elapsedRealtime()<consentDeadline) SystemClock.sleep(100)
            check(ScreenTranslateService.running) { "User screen-sharing approval timed out / $consentError" }
            changedAt = SystemClock.elapsedRealtime()
            val first = expectDraw(0, "날씨")
            assertFalse(first.getBoolean("cached"))
            assertTrue(first.getDouble("sourceTop") in .55.. .7)
            val stable = rows.size
            SystemClock.sleep(3_200)
            assertEquals("Static screen translated repeatedly", stable, rows.size)
            waitFor("Translation is not visible") { shown() }
            inst.runOnMainSync { decoration = true; fixture!!.invalidate() }
            SystemClock.sleep(3_200)
            assertEquals("Background-only change retriggered translation", stable, rows.size)
            update("少し散歩しませんか？")
            expectDraw(stable, "산책")
            var before = rows.size
            update("今日はいい天気ですね。")
            assertTrue(expectDraw(before, "날씨").getBoolean("cached"))
            before = rows.size
            update(source, .80f)
            assertTrue(expectDraw(before, "날씨").getDouble("sourceTop") > .7)
            click("자동 번역 일시정지")
            val paused = rows.size
            update("少し散歩しませんか？")
            SystemClock.sleep(2_400)
            assertEquals("Paused source was translated", paused, rows.size)
            click("자동 번역 재개")
            expectDraw(paused, "산책")
            update("")
            waitFor("Old translation survived empty source") { !shown(false) }
            before = rows.size
            update("今日はいい天気ですね。")
            expectDraw(before, "날씨")
            // A cancelled region selection must resume automatic monitoring.
            click("번역 메뉴"); click("번역할 영역 선택")
            waitFor("Region selector unavailable") { find("번역할 영역을 드래그하세요. 짧게 누르면 취소합니다.") != null }
            val node = find("번역할 영역을 드래그하세요. 짧게 누르면 취소합니다.")!!
            val box = Rect(); node.getBoundsInScreen(box)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("input tap ${box.centerX()} ${box.centerY()}")).use { it.readBytes() }
            waitFor("Cancelled selection did not resume") { !ScreenTranslateService.automaticPaused }
            before = rows.size
            update("少し散歩しませんか？")
            expectDraw(before, "산책")
            // Changing the ROI must clear old output and resume without a translate tap.
            click("번역 메뉴"); click("번역할 영역 선택")
            waitFor("Region selector unavailable") { find("번역할 영역을 드래그하세요. 짧게 누르면 취소합니다.") != null }
            val selected = find("번역할 영역을 드래그하세요. 짧게 누르면 취소합니다.")!!
            val selectedBox = Rect(); selected.getBoundsInScreen(selectedBox)
            inst.waitForIdleSync()
            val frames = java.util.concurrent.CountDownLatch(1)
            inst.runOnMainSync {
                val selector = WindowInspector.getGlobalWindowViews().filterIsInstance<RegionSelector>().single()
                selector.postOnAnimation { selector.postOnAnimation { frames.countDown() } }
            }
            check(frames.await(5, java.util.concurrent.TimeUnit.SECONDS))
            before = rows.size
            // Start away from Android's back and home gesture edges.
            changedAt = SystemClock.elapsedRealtime()
            drag(selectedBox.left+selectedBox.width()*.90f, selectedBox.top+selectedBox.height()*.50f,
                selectedBox.left+selectedBox.width()*.02f, selectedBox.top+selectedBox.height()*.95f)
            assertFalse("Region drag dismissed the source activity", activity.isFinishing)
            expectDraw(before, "산책")
            before = rows.size
            inst.runOnMainSync { secondLine = true }
            update("今日はいい天気ですね。", .62f)
            val multiple = expectDraw(before, "날씨")
            assertEquals(2, multiple.getInt("blocks"))
            assertTrue(multiple.getString("text").contains("산책"))
            click("번역 메뉴"); click("화면 공유와 번역 종료")
            waitFor("Capture did not stop") { !ScreenTranslateService.running }
            assertFalse(shown())
            report.put("passed", true).put("staticDedupPassed", true).put("pauseResumePassed", true)
                .put("emptySourceClearPassed", true).put("positionUpdatePassed", true).put("selectionCancelResumePassed", true).put("stopPassed", true)
                .put("backgroundOnlyDedupPassed", true).put("multiBlockPassed", true)
                .put("selectedRegionAutoResumePassed", true)
        } catch (error: Throwable) {
            report.put("error", error.toString()); throw error
        } finally {
            inst.runOnMainSync {
                context.stopService(Intent(context, ScreenTranslateService::class.java))
                InlineTranslateOverlay.drawObserver = null
                activity?.finish()
            }
            report.put("results", JSONArray(rows))
            inst.sendStatus(0, Bundle().apply { putString("automaticInlineJson", report.toString()) })
        }
    }
}
