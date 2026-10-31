package dev.personal.screen_translator

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object NativeEngine {
    init { System.loadLibrary("screen_translate") }
    external fun prepareTokenizer(path: ByteArray)
    external fun segment(text: ByteArray): Array<ByteArray>
    external fun unloadTokenizer()
    fun load(path: ByteArray) = AiHubEngine.load(File(path.toString(Charsets.UTF_8)))
    fun translate(text: ByteArray, active: () -> Boolean = { true }): ByteArray = AiHubEngine.translate(text.toString(Charsets.UTF_8), 2, active).toByteArray(Charsets.UTF_8)
    fun cancel() { AiHubEngine.cancelled = true }
    fun unload() = AiHubEngine.unload()
}
data class ModelFile(val file: String, val bytes: Long, val hash: String)
data class Model(val id: String, val label: String, val name: String, val directory: String, val bytes: Long, val files: List<ModelFile>, val note: String)
object Translator {
    lateinit var app: Context
    lateinit var models: List<Model>
    internal lateinit var downloads: ModelDownloads
    @Volatile var selectedId = "aihub"
    val worker = Executors.newSingleThreadExecutor()
    val busy = AtomicBoolean(false)
    val main = Handler(Looper.getMainLooper())
    var listener: ((Map<String, Any?>) -> Unit)? = null
    @Volatile var loadedId: String? = null
    @Volatile var message = "번역 모델을 선택해 준비해 주세요."
    @Volatile var progress: Double? = null
    @Volatile var lastTranslation = ""
    @Volatile var translationMs = 0L
    @Volatile var ocrMs = 0L
    @Volatile var totalMs = 0L
    @Volatile var cachedResult = false
    private val cache = object : LinkedHashMap<String, String>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }
    fun initialize(context: Context) {
        if (::app.isInitialized) return
        app = context
        val json = JSONObject(app.assets.open("model-manifest.json").bufferedReader().use { it.readText() }).getJSONArray("models")
        models = (0 until json.length()).map { i ->
            val m = json.getJSONObject(i)
            val entries = m.getJSONArray("files")
            val files = (0 until entries.length()).map { j ->
                val entry = entries.getJSONObject(j)
                ModelFile(entry.getString("file"), entry.getLong("bytes"), entry.getString("sha256"))
            }
            require(files.map { it.file }.toSet() == setOf("encoder_model.onnx", "decoder_model_merged.onnx", "vocab.txt", "tokenizer.json", "sys.dic", "unk.dic", "char.bin", "matrix.bin", "dicrc", "mecabrc"))
            require(files.size == 10 && files.all { it.bytes > 0 && it.hash.matches(Regex("[a-f0-9]{64}")) })
            require(files.sumOf { it.bytes } == m.getLong("bytes"))
            Model(m.getString("id"), m.getString("label"), m.getString("name"), m.getString("directory"), m.getLong("bytes"), files, m.getString("note"))
        }
        downloads = ModelDownloads(app) { text, value -> status(text, value) }
        selectedId = app.getSharedPreferences("translation", 0).getString("model", "aihub") ?: "aihub"
        if (models.none { it.id == selectedId } && downloads.installed.none { it.id == selectedId }) selectedId = "aihub"
    }
    fun snapshot(): Map<String, Any?> = mapOf(
        "models" to (models.map { mapOf("id" to it.id, "label" to it.label, "name" to it.name, "bytes" to it.bytes, "note" to it.note,
            "source" to "ja", "target" to "ko", "license" to "MIT / KoGPT2 CC-BY-NC-SA-4.0", "bundled" to true) } + downloads.installed.map { it.info() }),
        "selectedId" to selectedId, "candidate" to downloads.candidate?.info(),
        "loadedId" to loadedId, "busy" to busy.get(), "running" to ScreenTranslateService.running,
        "automatic" to ScreenTranslateService.automaticRunning,
        "paused" to ScreenTranslateService.automaticPaused,
        "overlayAllowed" to Settings.canDrawOverlays(app),
        "message" to message, "progress" to progress, "translation" to lastTranslation,
        "translationMs" to translationMs, "ocrMs" to ocrMs, "totalMs" to totalMs, "cached" to cachedResult
    )
    fun publish() { main.post { listener?.invoke(snapshot()) } }
    fun status(text: String, value: Double? = null) { message = text; progress = value; publish() }
    fun run(result: MethodChannel.Result, block: () -> Any?) {
        if (!busy.compareAndSet(false, true)) { result.error("BUSY", "이미 처리 중입니다.", null); return }
        publish()
        worker.execute {
            try { val value = block(); main.post { result.success(value) } }
            catch (e: Exception) { status(e.message ?: "처리 실패"); main.post { result.error("ENGINE", e.message, null) } }
            finally { busy.set(false); publish() }
        }
    }
    fun load(id: String): Any? {
        if (loadedId == id) return null
        val model = models.firstOrNull { it.id == id }
        val pack = downloads.installed.firstOrNull { it.id == id }
        require(model != null || pack != null) { "설치된 모델을 선택해 주세요." }
        NativeEngine.unload(); loadedId = null; cache.clear()
        lastTranslation = ""; translationMs = 0; ocrMs = 0; totalMs = 0; cachedResult = false
        try {
            if (model != null) {
                val dir = prepareFiles(model)
                status("${model.name} 메모리에 올리는 중…")
                NativeEngine.load(dir.absolutePath.toByteArray(Charsets.UTF_8))
            } else {
                val remote = checkNotNull(pack)
                status("다운로드 모델을 검증하고 있습니다…")
                downloads.verify(remote)
                val dictionary = prepareFiles(models.first(), models.first().files.filter { it.file !in ModelPack.names })
                AiHubEngine.load(downloads.directory(remote), dictionary = dictionary)
                // Metadata compatibility alone cannot validate a graph/tokenizer pair.
                AiHubEngine.translate("これは翻訳テストです。", 2)
            }
            loadedId = id; selectedId = id
            app.getSharedPreferences("translation", 0).edit().putString("model", id).apply()
            status("${model?.name ?: pack!!.name} 준비 완료 · 기기에서 번역합니다.")
        } catch (e: Exception) { NativeEngine.unload(); throw e }
        return null
    }
    fun removeModel(id: String): Any? {
        require(downloads.installed.any { it.id == id }) { "설치된 다운로드 모델이 아닙니다." }
        if (loadedId == id) { NativeEngine.unload(); loadedId = null; cache.clear(); lastTranslation = "" }
        downloads.remove(id)
        if (selectedId == id) {
            selectedId = "aihub"
            app.getSharedPreferences("translation", 0).edit().putString("model", selectedId).apply()
        }
        return null
    }
    private fun prepareFiles(model: Model, files: List<ModelFile> = model.files): File {
            require(model.directory.matches(Regex("[\\w-]+")))
            val dir = File(app.filesDir, "models/${model.directory}").apply { check(isDirectory || mkdirs()) { "모델 폴더 생성 실패" } }
            val missing = files.filter { !File(dir, it.file).exists() }.sumOf { it.bytes }
            if (missing > 0) check(StatFs(dir.path).availableBytes > missing + 128L * 1024 * 1024) { "모델을 준비할 저장 공간이 부족합니다." }
            var prepared = 0L
            for (entry in files) {
                val file = File(dir, entry.file)
                if (!file.exists()) {
                    status("설치된 모델을 준비하고 있습니다…", 0.0)
                    val temp = File(dir, entry.file + ".part")
                    val digest = MessageDigest.getInstance("SHA-256")
                    try {
                        app.assets.open("models/${model.directory}/${entry.file}").use { source -> temp.outputStream().use { out ->
                            val buffer = ByteArray(1024 * 1024)
                            var count = 0L; var lastUpdate = 0L
                            while (true) {
                                val size = source.read(buffer); if (size < 0) break
                                out.write(buffer, 0, size); digest.update(buffer, 0, size); count += size
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastUpdate >= 200) { status("설치된 모델을 준비하고 있습니다…", (prepared + count).toDouble() / model.bytes); lastUpdate = now }
                            }
                            check(count == entry.bytes && digest.digest().joinToString("") { "%02x".format(it) } == entry.hash) { "모델 파일 검증 실패" }
                        } }
                        check(temp.renameTo(file)) { "모델 파일 저장 실패" }
                    } finally { temp.delete() }
                } else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { source ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) { val size = source.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                    }
                    check(digest.digest().joinToString("") { "%02x".format(it) } == entry.hash) { "모델 파일이 손상됐습니다. 앱 데이터를 초기화해 주세요." }
                }
                check(file.length() == entry.bytes) { "모델 파일 크기 검증 실패" }
                prepared += entry.bytes
            }
            return dir
    }
    fun translate(text: String, active: () -> Boolean = { true }): Map<String, Any> {
        check(active()) { "Cancelled" }
        val id = checkNotNull(loadedId) { "모델을 먼저 준비해 주세요." }
        require(text.isNotBlank() && text.length <= 700) { "700자 이내로 영역을 줄여 주세요." }
        val start = SystemClock.elapsedRealtime()
        val key = "$id\u0000$text"
        val cached = cache[key]
        val translated = cached ?: NativeEngine.translate(text.toByteArray(Charsets.UTF_8), active).toString(Charsets.UTF_8).trim()
        check(translated.isNotBlank()) { "빈 번역 결과입니다. 다시 시도해 주세요." }
        cache[key] = translated
        cachedResult = cached != null
        translationMs = SystemClock.elapsedRealtime() - start
        lastTranslation = translated
        if (!ScreenTranslateService.running) { totalMs = translationMs; ocrMs = 0 }
        status(if (cached != null) "이전 번역 재사용" else "번역 완료")
        return mapOf("text" to translated, "ms" to translationMs, "cached" to (cached != null))
    }
}
