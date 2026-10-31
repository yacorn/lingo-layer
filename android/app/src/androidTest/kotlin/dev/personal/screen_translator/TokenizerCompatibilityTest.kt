package dev.personal.screen_translator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TokenizerCompatibilityTest {
    @Test fun matchesOriginalJapaneseTokenizerIncludingUnicode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        Translator.initialize(instrumentation.targetContext)
        try {
            Translator.load("aihub")
            val cases = JSONArray(instrumentation.context.assets.open("aihub-tokenizer-golden.json").bufferedReader().use { it.readText() })
            for (i in 0 until cases.length()) {
                val sample = cases.getJSONObject(i)
                val expected = sample.getJSONArray("ids")
                assertArrayEquals(sample.getString("source"), LongArray(expected.length()) { expected.getLong(it) }, AiHubEngine.sourceIds(sample.getString("source")))
            }
        } finally { NativeEngine.unload(); Translator.loadedId = null }
    }
    @Test fun preservesCriticalMeaningAndRejectsCancelledOutput() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Translator.initialize(app)
        try {
            Translator.load("aihub")
            val negative = Translator.translate("まだセーブしていないので、ゲームを終了しないでください。")["text"] as String
            assertTrue(negative, negative.contains("저장하지") && negative.contains("종료하지"))
            assertThrows(IllegalArgumentException::class.java) { AiHubEngine.translate("こんにちは。\u0000終わり。") }
            val price = Translator.translate("片道は640円、往復は1,200円です。")["text"] as String
            assertTrue(price, price.contains("편도") && price.contains("왕복") && price.contains("640엔") && price.contains("1,200엔"))
            val latch = java.util.concurrent.CountDownLatch(1)
            var failure: Throwable? = null
            var cancelledResult: String? = null
            val thread = Thread {
                try { cancelledResult = NativeEngine.translate("この村では、夜になると門が閉まります。外にいる人は朝まで入れません。".toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8) }
                catch (e: Throwable) { failure = e }
                finally { latch.countDown() }
            }
            thread.start()
            Thread.sleep(30)
            NativeEngine.cancel()
            assertTrue(latch.await(30, java.util.concurrent.TimeUnit.SECONDS))
            assertNull(cancelledResult)
            assertTrue(failure?.message.orEmpty().contains("Cancelled"))
        } finally { NativeEngine.unload(); Translator.loadedId = null }
    }
}
