package dev.personal.screen_translator

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal object ModelUrls {
    fun checked(value: String): URI {
        val uri = URI(value)
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) && uri.fragment == null &&
            (host in setOf("huggingface.co", "github.com", "api.github.com", "raw.githubusercontent.com") ||
                host.endsWith(".hf.co") || host.endsWith(".githubusercontent.com"))) { "GitHub·Hugging Face의 HTTPS 주소만 지원합니다." }
        require(uri.path.none { it == '\\' || it.code < 32 } && uri.path.split('/').none { it == ".." || it == "." }) { "잘못된 파일 주소입니다." }
        return uri
    }
    fun repository(value: String): Pair<String, String> {
        val uri = checked(value.trim())
        val parts = uri.path.trim('/').split('/')
        require(uri.host.lowercase() in setOf("github.com", "huggingface.co") && uri.rawQuery == null && parts.size == 2 &&
            parts.all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it !in setOf(".", "..") }) {
            "저장소 첫 화면 주소를 입력해 주세요. 예: https://huggingface.co/작성자/모델"
        }
        val repo = parts.joinToString("/").removeSuffix(".git")
        return uri.host.lowercase() to repo
    }
}

internal data class DownloadFile(val name: String, val bytes: Long, val hash: String, val url: String)
internal data class ModelPack(val name: String, val license: String, val source: String, val files: List<DownloadFile>) {
    val bytes get() = files.sumOf { it.bytes }
    val id: String get() = "remote-" + sha256(toJson().toString().toByteArray()).take(24)
    fun info() = mapOf("id" to id, "name" to name, "bytes" to bytes, "source" to "ja", "target" to "ko",
        "license" to license, "origin" to source, "bundled" to false)
    fun toJson(): JSONObject = JSONObject().put("format", "lingolayer-model-v1").put("engine", "aihub-ja-ko-v1")
        .put("name", name).put("license", license).put("source", source).put("sourceLanguage", "ja").put("targetLanguage", "ko")
        .put("files", org.json.JSONArray(files.map { JSONObject().put("file", it.name).put("bytes", it.bytes).put("sha256", it.hash).put("url", it.url) }))

    companion object {
        val names = setOf("encoder_model.onnx", "decoder_model_merged.onnx", "vocab.txt", "tokenizer.json")
        fun parse(json: JSONObject): ModelPack {
            require(json.getString("format") == "lingolayer-model-v1" && json.getString("engine") == "aihub-ja-ko-v1") {
                "지원하지 않는 모델 형식입니다. 현재 AIHub JA→KO 호환 모델팩만 실행할 수 있습니다."
            }
            require(json.getString("sourceLanguage") == "ja" && json.getString("targetLanguage") == "ko") { "이 엔진은 일본어→한국어만 지원합니다." }
            val name = json.getString("name").trim(); val license = json.getString("license").trim()
            require(name.length in 1..80 && license.length in 1..200 && (name + license).none { it.code < 32 }) { "모델 이름·라이선스를 확인해 주세요." }
            val source = json.getString("source"); ModelUrls.repository(source)
            val entries = json.getJSONArray("files")
            require(entries.length() == 4) { "모델팩에는 그래프 2개와 토크나이저 2개가 필요합니다." }
            val files = (0 until entries.length()).map { i ->
                val item = entries.getJSONObject(i); val file = item.getString("file")
                val limit = when (file) { "vocab.txt" -> 16L shl 20; "tokenizer.json" -> 32L shl 20; else -> 1500L shl 20 }
                require(file in names && item.getLong("bytes") in 1..limit && item.getString("sha256").matches(Regex("[a-f0-9]{64}"))) { "파일 이름·크기·SHA-256 정보가 올바르지 않습니다." }
                val url = item.getString("url"); ModelUrls.checked(url)
                DownloadFile(file, item.getLong("bytes"), item.getString("sha256"), url)
            }.sortedBy { it.name }
            require(files.map { it.name }.toSet() == names && files.sumOf { it.bytes } <= 2L * 1024 * 1024 * 1024) { "중복 파일이 있거나 모델이 2GiB를 넘습니다." }
            return ModelPack(name, license, source, files)
        }
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

internal class ModelDownloads(private val app: Context, private val status: (String, Double?) -> Unit) {
    private val root = File(app.filesDir, "downloaded-models").apply { check(isDirectory || mkdirs()) }
    @Volatile var installed = emptyList<ModelPack>(); private set
    @Volatile var candidate: ModelPack? = null; private set
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null
    init {
        root.listFiles().orEmpty().filter { it.name.matches(Regex("remote-[a-f0-9]{24}\\.part")) }.forEach { it.deleteRecursively() }
        reload()
    }
    private fun reload() {
        installed = root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("remote-[a-f0-9]{24}")) }
            .mapNotNull { dir -> runCatching {
                val metadata = File(dir, "pack.json"); require(metadata.length() in 1..262144)
                ModelPack.parse(JSONObject(metadata.readText())).also { pack ->
                    require(pack.id == dir.name && pack.files.all { File(dir, it.name).length() == it.bytes })
                }
            }.getOrNull() }.sortedBy { it.name }
    }
    fun directory(pack: ModelPack) = File(root, pack.id)
    fun cancel() { cancelled = true; connection?.disconnect() }
    private fun active() { if (cancelled) throw CancellationException("다운로드를 취소했습니다.") }
    private fun <T> fetch(url: String, block: (InputStream) -> T): T {
        var uri = ModelUrls.checked(url)
        repeat(6) {
            active()
            val client = uri.toURL().openConnection() as HttpURLConnection
            connection = client
            try {
                client.instanceFollowRedirects = false; client.connectTimeout = 15_000; client.readTimeout = 15_000
                client.setRequestProperty("User-Agent", "LingoLayer/1.0")
                client.setRequestProperty("Accept", "application/json, application/octet-stream")
                val code = client.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    uri = ModelUrls.checked(uri.resolve(checkNotNull(client.getHeaderField("Location"))).toString())
                } else {
                    check(code == 200) { when (code) {
                        401, 403 -> "공개 저장소만 지원합니다. 접근 제한 또는 요청 한도를 확인해 주세요."
                        404 -> "모델팩을 찾지 못했습니다. 저장소에 lingolayer-model.json이 필요합니다."
                        else -> "다운로드 서버 응답 오류 ($code)"
                    } }
                    return client.inputStream.use(block)
                }
            } finally { client.disconnect(); connection = null }
        }
        error("주소 이동 횟수를 초과했습니다.")
    }
    private fun json(url: String): JSONObject = fetch(url) { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            active(); val size = input.read(buffer); if (size < 0) break
            require(output.size() + size <= 262144) { "모델 정보가 너무 큽니다." }
            output.write(buffer, 0, size)
        }
        JSONObject(output.toString("UTF-8"))
    }
    fun inspect(url: String, bundled: Model): Any {
        cancelled = false; candidate = null
        val (host, repo) = ModelUrls.repository(url)
        status("저장소에서 호환 모델을 확인하고 있습니다…", null)
        val pack = if (host == "huggingface.co" && repo == "sappho192/aihub-ja-ko-translator") {
            // This upstream has no app manifest; use the same pinned files as the bundled model.
            val manifest = JSONObject(app.assets.open("model-manifest.json").bufferedReader().use { it.readText() }).getJSONArray("models").getJSONObject(0).getJSONArray("files")
            val files = (0 until manifest.length()).map { manifest.getJSONObject(it) }.filter { it.getString("file") in ModelPack.names }.map {
                DownloadFile(it.getString("file"), it.getLong("bytes"), it.getString("sha256"), "https://huggingface.co/$repo/resolve/${it.getString("revision")}/${it.getString("path")}")
            }.sortedBy { it.name }
            ModelPack(bundled.name, "MIT / KoGPT2 CC-BY-NC-SA-4.0", "https://huggingface.co/$repo", files)
        } else {
            val revision = if (host == "github.com") json("https://api.github.com/repos/$repo/commits/HEAD").getString("sha")
                else json("https://huggingface.co/api/models/$repo").getString("sha")
            require(revision.matches(Regex("[a-f0-9]{40,64}"))) { "저장소 버전을 확인하지 못했습니다." }
            val manifest = if (host == "github.com") "https://raw.githubusercontent.com/$repo/$revision/lingolayer-model.json"
                else "https://huggingface.co/$repo/resolve/$revision/lingolayer-model.json"
            ModelPack.parse(json(manifest))
        }
        active(); candidate = pack
        status("모델 정보를 확인했습니다. 용량과 라이선스를 확인한 뒤 설치하세요.", null)
        return pack.info()
    }
    fun install(id: String): Any? {
        val pack = checkNotNull(candidate?.takeIf { it.id == id }) { "모델 주소를 먼저 확인해 주세요." }
        if (installed.any { it.id == id }) return null
        check(installed.size < 16) { "다운로드 모델은 최대 16개까지 보관할 수 있습니다." }
        check(StatFs(root.path).availableBytes > pack.bytes + (384L shl 20)) { "모델 용량과 사전 준비 공간이 부족합니다." }
        cancelled = false
        check(!directory(pack).exists() || directory(pack).deleteRecursively()) { "손상된 이전 설치 파일을 정리하지 못했습니다." }
        val stage = File(root, "$id.part")
        check(!stage.exists() || stage.deleteRecursively()) { "이전 임시 파일을 정리하지 못했습니다." }
        check(stage.mkdirs())
        var received = 0L; val started = SystemClock.elapsedRealtime()
        try {
            for (file in pack.files) {
                val digest = MessageDigest.getInstance("SHA-256"); var count = 0L; var update = 0L
                fetch(file.url) { source -> File(stage, file.name).outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        active(); check(SystemClock.elapsedRealtime() - started < 30 * 60_000) { "다운로드 시간이 초과됐습니다." }
                        val size = source.read(buffer); if (size < 0) break
                        count += size; check(count <= file.bytes) { "파일 크기가 모델 정보와 다릅니다." }
                        output.write(buffer, 0, size); digest.update(buffer, 0, size)
                        val now = SystemClock.elapsedRealtime()
                        if (now - update >= 200) { status("${pack.name} · ${file.name}", (received + count).toDouble() / pack.bytes); update = now }
                    }
                } }
                check(count == file.bytes && digest.digest().joinToString("") { "%02x".format(it) } == file.hash) { "모델 파일 검증 실패: ${file.name}" }
                received += count
            }
            active()
            File(stage, "pack.json").writeText(pack.toJson().toString())
            check(stage.renameTo(directory(pack))) { "모델 설치 폴더 저장 실패" }
            reload(); status("설치 완료 · 선택하면 실행 호환성을 검사합니다.", null)
        } catch (e: Exception) {
            if (cancelled) throw CancellationException("다운로드를 취소했습니다.")
            throw e
        } finally { stage.deleteRecursively() }
        return null
    }
    fun remove(id: String) {
        val pack = installed.firstOrNull { it.id == id } ?: error("설치된 다운로드 모델이 아닙니다.")
        check(directory(pack).deleteRecursively()) { "모델 삭제 실패" }
        reload(); if (candidate?.id == id) candidate = null
        status("다운로드 모델을 삭제했습니다.", null)
    }
    fun verify(pack: ModelPack) {
        for (file in pack.files) {
            val actual = File(directory(pack), file.name)
            check(actual.length() == file.bytes) { "모델 파일이 손상됐습니다. 삭제 후 다시 설치해 주세요." }
            val digest = MessageDigest.getInstance("SHA-256")
            actual.inputStream().use { source -> val buffer = ByteArray(1024 * 1024)
                while (true) { val size = source.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == file.hash) { "모델 파일이 손상됐습니다. 삭제 후 다시 설치해 주세요." }
        }
    }
}
