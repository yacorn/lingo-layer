package dev.personal.screen_translator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ModelSmokeTest {
    @Test fun bundledJapaneseOcrWorksOffline() {
        val bitmap = android.graphics.Bitmap.createBitmap(800, 200, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawText("今日はいい天気です。", 20f, 110f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 48f })
        val reader = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions.Builder().build())
        try {
            val text = com.google.android.gms.tasks.Tasks.await(reader.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)), 30, java.util.concurrent.TimeUnit.SECONDS).text
            assertTrue("OCR: $text", text.contains("天気"))
            assertTrue(CaptureRules.hasJapanese(text))
        } finally { reader.close(); bitmap.recycle() }
    }
    @Test fun allBundledModelsTranslateJapaneseToKorean() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Translator.initialize(app)
        val metrics = mutableListOf<String>()
        try {
            for (model in Translator.models) {
                Translator.load(model.id)
                val result = Translator.translate("今日はいい天気ですね。少し散歩しませんか？")
                val text = result["text"] as String
                assertTrue("${model.id}: $text", text.count { it in '\uac00'..'\ud7a3' } >= 5)
                assertFalse(text.contains("<think>"))
                assertFalse(text.contains("<|im_"))
                metrics += "${model.id}\t${result["ms"]}ms\t$text"
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "\n${metrics.last()}\n") })
                assertEquals(true, Translator.translate("今日はいい天気ですね。少し散歩しませんか？")["cached"])
                val condition = Translator.translate("鍵を持っているなら、この扉を開けられます。")["text"] as String
                assertTrue("Target-language conditioning: $condition", condition.contains("열쇠") && condition.contains("문"))
                val overflow = assertThrows(IllegalStateException::class.java) {
                    NativeEngine.translate("a ".repeat(1024).toByteArray(Charsets.UTF_8))
                }
                assertTrue(overflow.message.orEmpty().contains("too long"))
            }
            File(app.filesDir, "smoke-results.tsv").writeText(metrics.joinToString("\n"))
        } finally { NativeEngine.unload(); Translator.loadedId = null }
    }
}
