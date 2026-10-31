package dev.personal.screen_translator

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EdgeOverlayTest {
    @Test fun edgeAndTranslationAllowSourceTouches() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val report = JSONObject().put("scope", "Own fixture and supplied Korean overlay; actual injected tap, no capture/model")
        var activity: MainActivity? = null
        var overlay: InlineTranslateOverlay? = null
        var touches = 0
        try {
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            var focused = false
            val focusDeadline = SystemClock.elapsedRealtime()+15_000
            while (!focused && SystemClock.elapsedRealtime()<focusDeadline) {
                inst.runOnMainSync { focused = activity.window.decorView.hasWindowFocus() }
                if (!focused) SystemClock.sleep(50)
            }
            check(focused) { "Authored activity never gained focus before fixture setup" }
            inst.runOnMainSync {
                fun detach(view: View) {
                    if (view is io.flutter.embedding.android.FlutterView) view.detachFromFlutterEngine()
                    if (view is ViewGroup) for (i in 0 until view.childCount) detach(view.getChildAt(i))
                }
                detach(activity.window.decorView)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContentView(object : View(activity) {
                    override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.WHITE) }
                    override fun onTouchEvent(event: MotionEvent): Boolean {
                        if (event.actionMasked == MotionEvent.ACTION_UP) { touches++; performClick() }
                        return true
                    }
                    override fun performClick(): Boolean { super.performClick(); return true }
                })
                overlay = InlineTranslateOverlay(activity, {}, {}, {}, {}, automatic = true)
                overlay!!.finishCaptureProbe()
                overlay!!.show(listOf(InlineTranslation("한국어 번역 표시 검사", Region(.2f,.6f,.8f,.75f))),listOf(Region(.2f,.6f,.8f,.75f)),false)
            }
            SystemClock.sleep(700)
            val width = context.resources.displayMetrics.widthPixels
            val height = context.resources.displayMetrics.heightPixels
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,width*.3f,height*.62f,0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { check(inst.uiAutomation.injectInputEvent(event,true)) } finally { event.recycle() }
            }
            inst.waitForIdleSync()
            check(touches == 1) { "Combined overlay windows blocked own source tap" }
            inst.runOnMainSync {
                fun view(name: String) = InlineTranslateOverlay::class.java.getDeclaredField(name).apply { isAccessible = true }.get(overlay) as View
                check(!view("bubble").isShown)
                check(view("edge").isShown)
                overlay!!.setAutomaticPaused(true)
                check(view("edge").contentDescription == "자동 번역 일시정지")
                overlay!!.setAutomaticPaused(false); overlay!!.setSourceVisible(false)
                check(!view("inline").isShown)
                check(view("edge").contentDescription == "번역할 앱이 보이지 않아 대기 중")
            }
            report.put("sourceTapPassed",true).put("edgeStateAndNoFloatingButtonPassed",true).put("passed",true)
        } catch (error: Throwable) { report.put("passed",false).put("error",error.toString()); throw error }
        finally {
            inst.runOnMainSync { overlay?.close(); activity?.finish() }
            inst.sendStatus(0, Bundle().apply { putString("edgeOverlayJson",report.toString()) })
        }
    }
}
