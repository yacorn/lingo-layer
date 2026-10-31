package dev.personal.screen_translator

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

@RunWith(AndroidJUnit4::class)
class InlineOverflowTest {
    @Test fun paragraphUsesAvailableSpaceWithoutMovingItsAnchor() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val report = JSONObject().put("scope", "Own white fixture and supplied Korean paragraph; real renderer, no OCR/model")
        var activity: MainActivity? = null
        var overlay: InlineTranslateOverlay? = null
        var opened = 0; var resumed = 0
        fun find(description: String): View? {
            fun walk(view: View): View? {
                if (view.contentDescription?.toString() == description) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))?.let { return it }
                return null
            }
            return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { walk(it) }
        }
        try {
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            var focused = false
            val focusDeadline = SystemClock.elapsedRealtime()+15_000
            while (!focused && SystemClock.elapsedRealtime()<focusDeadline) {
                inst.runOnMainSync { focused = activity!!.window.decorView.hasWindowFocus() }
                if (!focused) SystemClock.sleep(50)
            }
            check(focused) { "Authored activity never gained focus before fixture setup" }
            inst.runOnMainSync {
                fun detach(view: View) {
                    if (view is io.flutter.embedding.android.FlutterView) view.detachFromFlutterEngine()
                    if (view is ViewGroup) for (i in 0 until view.childCount) detach(view.getChildAt(i))
                }
                detach(activity!!.window.decorView)
                activity!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity!!.setContentView(object : View(activity) { override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.WHITE) } })
                overlay = InlineTranslateOverlay(context, {}, {}, { opened++ }, {}, onSelectionFinished = { resumed++ })
            }
            val metrics = context.resources.displayMetrics
            report.put("width",metrics.widthPixels).put("height",metrics.heightPixels).put("densityDpi",metrics.densityDpi)
            val padding = (6*metrics.density).toInt()
            val source = Region(.05f,.25f,.95f,.28f)
            val sourcePixels = source.pixels(metrics.widthPixels, metrics.heightPixels)
            val ink = TextPaint().apply { textSize = 14*metrics.scaledDensity }
            val phrase = "긴 문단도 여유 공간을 이용하면 위치를 바꾸지 않고 끝까지 읽을 수 있습니다. "
            var text = phrase
            fun layout() = StaticLayout.Builder.obtain(text,0,text.length,ink,sourcePixels[2])
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing((3*metrics.density).toInt().toFloat(),1f).build()
            while (layout().height+2*padding < 145*metrics.density) text += phrase
            val expectedHeight = layout().height+2*padding
            check(expectedHeight < 240*metrics.density)
            val drawn = CountDownLatch(1)
            var box: Region? = null
            inst.runOnMainSync {
                InlineTranslateOverlay.drawObserver = { _, regions, _, _ -> box = regions.singleOrNull(); drawn.countDown() }
                overlay!!.show(listOf(InlineTranslation(text,source,true)),listOf(source),false)
            }
            check(drawn.await(5,TimeUnit.SECONDS)) { "No overlay draw" }
            val actual = checkNotNull(box).pixels(metrics.widthPixels,metrics.heightPixels)
            report.put("requiredHeightPx",expectedHeight).put("actualHeightPx",actual[3])
            check(actual[3]+2 >= expectedHeight) { "Paragraph clipped despite available space: ${actual[3]} < $expectedHeight" }
            check(kotlin.math.abs(actual[0]-sourcePixels[0]) <= 12*metrics.density)
            check(kotlin.math.abs(actual[1]-sourcePixels[1]) <= 12*metrics.density)
            val full = phrase.repeat(35)+"마지막 문장까지 보존합니다."
            val bottom = Region(.05f,.99f,.95f,1f)
            val skipped = CountDownLatch(1)
            inst.runOnMainSync {
                InlineTranslateOverlay.drawObserver = { values, _, _, _ ->
                    report.put("bottomInlineBlocks",values.size); skipped.countDown()
                }
                overlay!!.show(listOf(InlineTranslation(full,bottom,true)),listOf(bottom),false)
            }
            check(skipped.await(5,TimeUnit.SECONDS))
            check(report.getInt("bottomInlineBlocks") == 0) { "Do not draw an unreadable sliver at the system bar" }
            var scroll: ScrollView? = null
            inst.runOnMainSync {
                overlay!!.showMenu()
                check((find("전체 번역 보기") as Button).performClick())
                check(opened == 1 && overlay!!.interacting)
                val panel = checkNotNull(find("전체 번역 읽기 창")) as ViewGroup
                scroll = (0 until panel.childCount).map { panel.getChildAt(it) }.filterIsInstance<ScrollView>().single()
                val body = scroll!!.getChildAt(0) as TextView
                check(body.text.toString() == full && body.ellipsize == null)
            }
            inst.waitForIdleSync()
            fun renderReader(name: String) {
                inst.runOnMainSync {
                    val panel = checkNotNull(find("전체 번역 읽기 창"))
                    val bitmap = Bitmap.createBitmap(panel.width,panel.height,Bitmap.Config.ARGB_8888)
                    try { panel.draw(Canvas(bitmap)); File(context.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
                    finally { bitmap.recycle() }
                }
            }
            renderReader("overflow-reader-top.png")
            inst.runOnMainSync { scroll!!.fullScroll(View.FOCUS_DOWN) }
            SystemClock.sleep(400)
            renderReader("overflow-reader-bottom.png")
            inst.runOnMainSync {
                val panel = find("전체 번역 읽기 창") as ViewGroup
                val close = checkNotNull(find("전체 번역 닫고 화면으로 돌아가기"))
                val rect = android.graphics.Rect().also { close.getGlobalVisibleRect(it) }
                report.put("panelScrollY",panel.scrollY).put("readerScrollY",scroll!!.scrollY)
                    .put("closeButtonVisible",!rect.isEmpty)
            }
            val frames = CountDownLatch(1)
            inst.runOnMainSync {
                val panel = checkNotNull(find("전체 번역 읽기 창"))
                val params = panel.layoutParams as WindowManager.LayoutParams
                // Only the authored test reader is exposed for visual QA; production keeps FLAG_SECURE.
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                context.getSystemService(WindowManager::class.java).updateViewLayout(panel,params)
                panel.postOnAnimation { panel.postOnAnimation { frames.countDown() } }
            }
            check(frames.await(5,TimeUnit.SECONDS))
            SystemClock.sleep(150)
            try {
                val screen = checkNotNull(inst.uiAutomation.takeScreenshot())
                try { File(context.getExternalFilesDir(null),"overflow-reader-screen-bottom.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG,100,it) } }
                finally { screen.recycle() }
            } finally { inst.runOnMainSync {
                val panel = checkNotNull(find("전체 번역 읽기 창"))
                val params = panel.layoutParams as WindowManager.LayoutParams
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_SECURE
                context.getSystemService(WindowManager::class.java).updateViewLayout(panel,params)
            } }
            inst.runOnMainSync {
                check(scroll!!.scrollY > 0) { "Full translation must scroll" }
                check((find("전체 번역 복사") as Button).performClick())
                check(context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString() == full)
                check((find("전체 번역 닫고 화면으로 돌아가기") as Button).performClick())
                check(resumed == 1 && !overlay!!.interacting) { "Reader did not restore prior interaction state" }
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                check(find("전체 번역 읽기 창") == null) { "Reader window remained after close" }
                overlay!!.showMenu(); (find("전체 번역 보기") as Button).performClick()
                overlay!!.close()
                check(resumed == 1) { "Stopping must not resume capture" }
            }
            inst.waitForIdleSync()
            inst.runOnMainSync { check(find("전체 번역 읽기 창") == null) { "Reader window remained after stop" } }
            report.put("fullTextPreservedScrolledCopied",true).put("readerPauseResumeAndCleanup",true)
            report.put("passed",true)
        } catch (error: Throwable) { report.put("passed",false).put("error",error.toString()); throw error }
        finally {
            inst.runOnMainSync { overlay?.close(); InlineTranslateOverlay.drawObserver = null; activity?.finish() }
            inst.sendStatus(0,Bundle().apply { putString("inlineOverflowJson",report.toString()) })
        }
    }
}
