package dev.personal.screen_translator

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class InlinePlacementTest {
    @Test fun longBoxesStayAtTheirSourceAndInsideContent() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val report = JSONObject().put("scope", "Own source and supplied Korean text; actual overlay renderer, no OCR/model")
        val rows = JSONArray()
        val errors = mutableListOf<String>()
        val cases = listOf(
            "short-prefix" to Region(.05f,.15f,.95f,.60f),
            "short-result" to Region(.05f,.15f,.95f,.60f),
            "large-paragraph" to Region(.05f,.15f,.95f,.60f),
            "bottom-paragraph" to Region(.05f,.90f,.80f,.97f),
            "right-column" to Region(.85f,.55f,.98f,.62f))
        var overlay: InlineTranslateOverlay? = null
        var activity: MainActivity? = null
        try {
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels; val height = metrics.heightPixels
            val density = metrics.density
            inst.runOnMainSync {
                fun detach(view: View) {
                    if (view is io.flutter.embedding.android.FlutterView) view.detachFromFlutterEngine()
                    if (view is ViewGroup) for (i in 0 until view.childCount) detach(view.getChildAt(i))
                }
                detach(activity!!.window.decorView)
                activity!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity!!.setContentView(object : View(activity) {
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawColor(Color.WHITE)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 30f }
                        canvas.drawText("日本語の長い文章と画面の端の表示を確認します。", 24f, height*.65f, paint)
                    }
                })
                overlay = InlineTranslateOverlay(activity!!, {}, {}, {}, {})
            }
            for ((id, source) in cases) {
                val ready = CountDownLatch(1)
                var box: Region? = null
                inst.runOnMainSync {
                    InlineTranslateOverlay.drawObserver = { _, displayed, _, _ -> box = displayed.singleOrNull(); ready.countDown() }
                    val text = if (id.startsWith("short")) "첫 문장입니다." else "긴 문장의 번역을 원문의 위치에 표시합니다. ".repeat(40)
                    overlay!!.show(listOf(InlineTranslation(text, source, id != "short-result")), listOf(source), false)
                }
                check(ready.await(5, TimeUnit.SECONDS)) { "Overlay did not draw: $id" }
                val drawn = checkNotNull(box) { "Overlay dropped: $id" }
                val actual = drawn.pixels(width,height); val anchor = source.pixels(width,height)
                val row = JSONObject().put("id",id).put("source",JSONArray(listOf(source.left,source.top,source.right,source.bottom)))
                    .put("drawn",JSONArray(listOf(drawn.left,drawn.top,drawn.right,drawn.bottom)))
                    .put("widthPx",actual[2]).put("heightPx",actual[3]).put("anchorShiftX",actual[0]-anchor[0]).put("anchorShiftY",actual[1]-anchor[1])
                if (actual[3] > minOf((240*density).toInt(),(height*.35f).toInt())+2) errors.add("$id: oversized height")
                if (id.startsWith("short") && (actual[3]>64*density || actual[2]>160*density)) errors.add("$id: excessive empty space")
                if (id == "right-column" && actual[3]>maxOf(anchor[3]*1.5f+12*density,96*density)+2) errors.add("$id: narrow box stretched vertically")
                if (kotlin.math.abs(actual[0]-anchor[0])>12*density) errors.add("$id: horizontal anchor moved")
                if (kotlin.math.abs(actual[1]-anchor[1])>12*density) errors.add("$id: vertical anchor moved")
                inst.runOnMainSync {
                    val insets = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.windowInsets
                        .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    if (actual[0]<insets.left || actual[1]<insets.top || actual[0]+actual[2]>width-insets.right || actual[1]+actual[3]>height-insets.bottom)
                        errors.add("$id: overlaps system bars")
                    val view = WindowInspector.getGlobalWindowViews().first { it.contentDescription?.startsWith("한국어 번역:")==true }
                    val bitmap = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                    try { view.draw(Canvas(bitmap)); File(context.getExternalFilesDir(null),"placement-$id.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
                    finally { bitmap.recycle() }
                }
                rows.put(row)
            }
            val dense = (0 until 12).map { index ->
                val y = (height*.2f).toInt()+index*84
                Region(.05f,y.toFloat()/height,.95f,(y+80).toFloat()/height)
            }
            val denseReady = CountDownLatch(1)
            var denseDrawn = emptyList<Region>()
            inst.runOnMainSync {
                InlineTranslateOverlay.drawObserver = { _, displayed, _, _ -> denseDrawn = displayed; denseReady.countDown() }
                overlay!!.show(dense.mapIndexed { index, source -> InlineTranslation("문단 ${index+1}",source) },dense,false)
            }
            check(denseReady.await(5,TimeUnit.SECONDS))
            check(denseDrawn.size == dense.size) { "Dense paragraphs dropped: ${denseDrawn.size}/${dense.size}" }
            val insets = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            val cells = CaptureRules.displayCells(dense,width,height,intArrayOf(insets.left,insets.top,insets.right,insets.bottom))
            var maxError = 0
            dense.forEachIndexed { index, source ->
                val bounds = source.pixels(width,height)
                val padding = checkNotNull(CaptureRules.inlineAnchor(bounds,cells[index],(6*density).toInt()))[2]
                val box = denseDrawn[index].pixels(width,height)
                maxError = maxOf(maxError,kotlin.math.abs(box[0]+padding-bounds[0]),kotlin.math.abs(box[1]+padding-bounds[1]))
            }
            if (maxError > 2) errors.add("dense paragraphs: text origin moved $maxError px")
            rows.put(JSONObject().put("id","dense-paragraphs").put("blocks",denseDrawn.size).put("maxTextOriginErrorPx",maxError))
            report.put("errors",JSONArray(errors)).put("passed",errors.isEmpty())
            check(errors.isEmpty()) { errors.joinToString("; ") }
        } catch (error: Throwable) { report.put("passed",false).put("error",error.toString()); throw error }
        finally {
            inst.runOnMainSync { overlay?.close(); InlineTranslateOverlay.drawObserver=null; activity?.finish() }
            report.put("cases",rows)
            inst.sendStatus(0,Bundle().apply { putString("placementJson",report.toString()) })
        }
    }
}
