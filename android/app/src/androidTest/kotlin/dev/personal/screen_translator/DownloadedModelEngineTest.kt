package dev.personal.screen_translator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadedModelEngineTest {
    @Test fun installedCompatibleGraphsCanBeSelectedTranslatedAndRemoved() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Translator.initialize(app)
        val entries = JSONObject(app.assets.open("model-manifest.json").bufferedReader().use { it.readText() })
            .getJSONArray("models").getJSONObject(0).getJSONArray("files")
        val files = (0 until entries.length()).map { entries.getJSONObject(it) }.filter { it.getString("file") in ModelPack.names }.map {
            DownloadFile(it.getString("file"), it.getLong("bytes"), it.getString("sha256"),
                "https://huggingface.co/${it.getString("repo")}/resolve/${it.getString("revision")}/${it.getString("path")}")
        }.sortedBy { it.name }
        val pack = ModelPack("Compatible graph fixture", "Fixture / original model license", "https://huggingface.co/sappho192/aihub-ja-ko-translator", files)
        val dir = Translator.downloads.directory(pack)
        check(!dir.exists() && dir.mkdirs())
        try {
            for (file in files) app.assets.open("models/aihub-ja-ko-int8/${file.name}").use { input ->
                File(dir, file.name).outputStream().use { output -> input.copyTo(output) }
            }
            File(dir, "pack.json").writeText(pack.toJson().toString())
            Translator.downloads = ModelDownloads(app) { text, value -> Translator.status(text, value) }
            Translator.load(pack.id)
            assertEquals(pack.id, Translator.loadedId)
            assertEquals(pack.id, app.getSharedPreferences("translation", 0).getString("model", null))
            val result = Translator.translate("今日はいい天気ですね。")
            assertTrue((result["text"] as String).any { it in '\uac00'..'\ud7a3' })
            assertEquals(false, result["cached"])
            assertEquals(true, Translator.translate("今日はいい天気ですね。")["cached"])
            Translator.removeModel(pack.id)
            assertEquals("aihub", Translator.selectedId)
            assertNull(Translator.loadedId)
            assertFalse(dir.exists())
        } finally {
            NativeEngine.unload(); Translator.loadedId = null
            dir.deleteRecursively()
            app.getSharedPreferences("translation", 0).edit().putString("model", "aihub").commit()
        }
    }
}
