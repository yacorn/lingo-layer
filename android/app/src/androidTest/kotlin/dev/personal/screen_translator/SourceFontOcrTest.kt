package dev.personal.screen_translator

import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.text.TextPaint
import android.view.*
import android.view.inspector.WindowInspector
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SourceFontOcrTest {
    @Test fun recognizedGlyphSizeAndRawTextReachTheReader() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val context=inst.targetContext
        val report=JSONObject().put("scope","Own short Japanese bitmap with real bundled OCR; long recognized-text replay and supplied Korean overlay, no model inference")
        val recognizer=TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        val image=Bitmap.createBitmap(1080,400,Bitmap.Config.ARGB_8888)
        var activity: MainActivity?=null
        var overlay: InlineTranslateOverlay?=null
        fun find(description: String): View? {
            fun walk(view: View): View? {
                if(view.contentDescription?.toString()==description) return view
                if(view is ViewGroup) for(i in 0 until view.childCount) walk(view.getChildAt(i))?.let { return it }
                return null
            }
            return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { walk(it) }
        }
        try {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=48f }
            Canvas(image).apply {
                drawColor(Color.WHITE)
                drawText("今日はいい天気ですね。",40f,100f,paint)
                drawText("少し散歩しませんか？",40f,210f,paint)
            }
            val started=SystemClock.elapsedRealtime()
            val recognized=Tasks.await(recognizer.process(InputImage.fromBitmap(image,0)),15,TimeUnit.SECONDS)
            report.put("ocrMs",SystemClock.elapsedRealtime()-started).put("recognized",recognized.text)
            check(recognized.text.contains("天気") && recognized.text.contains("散歩")) { "Authored Japanese OCR omitted expected words" }
            val replay=recognized.text.repeat(40)
            check(replay.length>700)
            val chunks=CaptureRules.screenChunks(replay)
            check(chunks.all { it.length<=160 })
            report.put("recognizedCharacters",recognized.text.length).put("replayedCharacters",replay.length).put("screenChunks",chunks.size)
            val glyph=CaptureRules.medianLineHeight(recognized.textBlocks.flatMap { it.lines }.mapNotNull { it.boundingBox?.height() })
            check(glyph>0)
            activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            var focused=false;val deadline=SystemClock.elapsedRealtime()+15_000
            while(!focused && SystemClock.elapsedRealtime()<deadline) {
                inst.runOnMainSync { focused=activity!!.window.decorView.hasWindowFocus() }
                if(!focused) SystemClock.sleep(50)
            }
            check(focused) { "Authored activity has no focus" }
            inst.runOnMainSync {
                fun detach(view: View) {
                    if(view is io.flutter.embedding.android.FlutterView) view.detachFromFlutterEngine()
                    if(view is ViewGroup) for(i in 0 until view.childCount) detach(view.getChildAt(i))
                }
                detach(activity!!.window.decorView)
                activity!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity!!.setContentView(object: View(activity) { override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.WHITE) } })
                overlay=InlineTranslateOverlay(context,{},{},{},{})
            }
            val source=Region(.05f,.25f,.95f,.4f)
            fun draw(text: String): Float {
                val drawn=CountDownLatch(1)
                inst.runOnMainSync {
                    InlineTranslateOverlay.drawObserver={ _,_,_,_ -> drawn.countDown() }
                    overlay!!.show(listOf(InlineTranslation(text,source,true,glyph)),listOf(source),false)
                }
                check(drawn.await(5,TimeUnit.SECONDS))
                var size=0f
                inst.runOnMainSync {
                    val ink=InlineTranslateOverlay::class.java.getDeclaredField("ink").apply { isAccessible=true }.get(overlay) as TextPaint
                    size=ink.textSize
                    val bounds=Rect();ink.getTextBounds("가나다라마바사",0,7,bounds)
                    check(kotlin.math.abs(bounds.height()-glyph)<=3f) { "Matched glyph height differs: ${bounds.height()} / $glyph" }
                }
                return size
            }
            val firstSize=draw("첫 문장입니다.")
            val longSize=draw("긴 번역이 추가되어도 원문의 글자 크기를 유지합니다. ".repeat(30))
            check(firstSize==longSize) { "Long result changed the matched font size" }
            val raw="$replay\nTEST_NAME 123"
            inst.runOnMainSync {
                overlay!!.setRecognizedText(raw);overlay!!.showMenu()
                check((find("인식한 원문 보기") as Button).performClick())
                val panel=checkNotNull(find("인식한 원문 읽기 창")) as ViewGroup
                val scroll=(0 until panel.childCount).map { panel.getChildAt(it) }.filterIsInstance<ScrollView>().single()
                check((scroll.getChildAt(0) as TextView).text.toString()==raw)
                overlay!!.setRecognizedText("更新後の原文です。")
                check((find("인식한 원문 복사") as Button).performClick())
                check(context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()==raw)
                check((find("인식한 원문 닫기") as Button).performClick())
            }
            inst.waitForIdleSync()
            inst.runOnMainSync { check(find("인식한 원문 읽기 창")==null && !overlay!!.interacting) }
            report.put("sourceGlyphHeightPx",glyph).put("matchedTextSizePx",firstSize)
                .put("longResultKeptFontSize",true).put("rawSnapshotAndCopyPassed",true).put("passed",true)
        } catch(error: Throwable) { report.put("passed",false).put("error",error.toString());throw error }
        finally {
            image.recycle();recognizer.close()
            inst.runOnMainSync { overlay?.close();InlineTranslateOverlay.drawObserver=null;activity?.finish() }
            inst.sendStatus(0,Bundle().apply { putString("sourceFontOcrJson",report.toString()) })
        }
    }
}
