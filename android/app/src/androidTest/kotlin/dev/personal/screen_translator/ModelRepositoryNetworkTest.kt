package dev.personal.screen_translator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ModelRepositoryNetworkTest {
    @Test fun publicRepositoryMetadataAndPinnedVocabularyAreReachable() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ModelDownloads(app) { _, _ -> }
        val readJson = store.javaClass.getDeclaredMethod("json", String::class.java).apply { isAccessible = true }
        val hf = readJson.invoke(store, "https://huggingface.co/api/models/sappho192/aihub-ja-ko-translator") as JSONObject
        assertTrue(hf.getString("sha").matches(Regex("[a-f0-9]{40}")))
        val github = readJson.invoke(store, "https://api.github.com/repos/SKT-AI/KoGPT2/commits/HEAD") as JSONObject
        assertTrue(github.getString("sha").matches(Regex("[a-f0-9]{40}")))
        val model = JSONObject(app.assets.open("model-manifest.json").bufferedReader().use { it.readText() }).getJSONArray("models").getJSONObject(0)
        val files = model.getJSONArray("files")
        val file = (0 until files.length()).map { files.getJSONObject(it) }.first { it.getString("file") == "vocab.txt" }
        val url = "https://huggingface.co/${file.getString("repo")}/resolve/${file.getString("revision")}/${file.getString("path")}"
        val fetch = store.javaClass.getDeclaredMethod("fetch", String::class.java, kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
        val consume: (InputStream) -> String = { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192); var count = 0L
            while (true) { val size = input.read(buffer); if (size < 0) break
                count += size; check(count <= file.getLong("bytes")); digest.update(buffer, 0, size)
            }
            assertEquals(file.getLong("bytes"), count)
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        assertEquals(file.getString("sha256"), fetch.invoke(store, url, consume))
    }
}
