package dev.personal.screen_translator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler
import java.util.concurrent.CancellationException

/** Isolated instrumentation process: HTTPS fixtures never access the network or inference engine. */
@RunWith(AndroidJUnit4::class)
class ModelDownloadsTest {
    @Test fun repositoriesDownloadsValidationCancellationAndPersistence() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Translator.initialize(app)
        val body = "fixture".toByteArray()
        val repo = "https://huggingface.co/fixture/model"
        val revision = "a".repeat(40)
        fun manifest(badHash: Boolean = false) = JSONObject().put("format", "lingolayer-model-v1").put("engine", "aihub-ja-ko-v1")
            .put("sourceLanguage", "ja").put("targetLanguage", "ko").put("name", if (badHash) "Bad fixture" else "Download fixture")
            .put("license", "Test fixture only").put("source", repo).put("files", JSONArray(ModelPack.names.map { name ->
                JSONObject().put("file", name).put("bytes", body.size).put("sha256", if (badHash) "0".repeat(64) else ModelPack.sha256(body))
                    .put("url", "$repo/resolve/$revision/$name")
            }))
        var current = manifest()
        var interrupt = false
        var store: ModelDownloads? = null
        val redirects = mutableMapOf<String, String>()
        val statuses = mutableMapOf<String, Int>()
        val requests = mutableListOf<String>()
        URL.setURLStreamHandlerFactory { protocol -> if (protocol != "https") null else object : URLStreamHandler() {
            override fun openConnection(url: URL) = object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode(): Int { requests.add(url.toString()); return statuses[url.toString()] ?: if (url.toString() in redirects) 302 else 200 }
                override fun getHeaderField(name: String) = if (name == "Location") redirects[url.toString()] else null
                override fun getInputStream(): java.io.InputStream {
                    val value = when {
                        url.path.endsWith("lingolayer-model.json") -> current.toString().toByteArray()
                        url.path.endsWith("/commits/HEAD") || url.path == "/api/models/fixture/model" -> JSONObject().put("sha", revision).toString().toByteArray()
                        else -> { if (interrupt) store!!.cancel(); body }
                    }
                    return ByteArrayInputStream(value)
                }
            }
        } }
        val progress = mutableListOf<Double>()
        store = ModelDownloads(app) { _, value -> if (value != null) progress.add(value) }
        var installedId: String? = null
        try {
            store.inspect(repo, Translator.models.first())
            val pack = checkNotNull(store.candidate)
            assertEquals(pack.id, ModelPack.parse(pack.toJson()).id)
            assertEquals(4, pack.files.size)
            store.install(pack.id); installedId = pack.id
            assertTrue(progress.all { it in 0.0..1.0 })
            val restored = ModelDownloads(app) { _, _ -> }
            assertTrue(restored.installed.any { it.id == pack.id })
            restored.verify(pack)
            store.remove(pack.id); installedId = null
            assertFalse(restored.directory(pack).exists())
            current = manifest(true)
            store.inspect("https://github.com/fixture/model", Translator.models.first())
            assertThrows(IllegalStateException::class.java) { store.install(store.candidate!!.id) }
            assertFalse(store.directory(store.candidate!!).exists())
            current = manifest(); interrupt = true
            store.inspect(repo, Translator.models.first())
            assertThrows(CancellationException::class.java) { store.install(store.candidate!!.id) }
            assertFalse(store.directory(store.candidate!!).exists())
            interrupt = false
            val metadata = "https://huggingface.co/api/models/fixture/model"
            redirects[metadata] = "http://huggingface.co/insecure"
            assertThrows(IllegalArgumentException::class.java) { store.inspect(repo, Translator.models.first()) }
            redirects.clear(); statuses[metadata] = 403
            assertThrows(IllegalStateException::class.java) { store.inspect(repo, Translator.models.first()) }
            for (invalid in listOf(manifest().put("engine", "llama"), manifest().put("targetLanguage", "en"), manifest().put("files", JSONArray())))
                assertThrows(IllegalArgumentException::class.java) { ModelPack.parse(invalid) }
            assertTrue(requests.any { it.contains("api.github.com") })
        } finally {
            installedId?.let { id -> runCatching { ModelDownloads(app) { _, _ -> }.remove(id) } }
        }
    }
}
