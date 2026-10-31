package dev.personal.screen_translator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln
import ai.onnxruntime.providers.NNAPIFlags
import java.util.EnumSet

object AiHubEngine {
    private val environment = OrtEnvironment.getEnvironment()
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var vocabulary = emptyMap<String, Long>()
    private var targetTokens = emptyArray<String>()
    private var targetSpecials = emptySet<Int>()
    @Volatile var cancelled = false

    fun load(directory: File, nnapi: Boolean = false, dictionary: File = directory) {
        unload()
        try {
            vocabulary = File(directory, "vocab.txt").readLines().mapIndexed { id, token -> token to id.toLong() }.toMap()
            require(vocabulary.size in 5..100_000) { "지원하지 않는 원문 어휘 크기입니다." }
            check(vocabulary["[CLS]"] == 2L && vocabulary["[SEP]"] == 3L && vocabulary["[UNK]"] == 1L)
            val tokenizer = JSONObject(File(directory, "tokenizer.json").readText())
            val tokens = tokenizer.getJSONObject("model").getJSONObject("vocab")
            require(tokens.length() in 2..100_000 && tokens.keys().asSequence().map { tokens.getInt(it) }.toSet() == (0 until tokens.length()).toSet()) { "지원하지 않는 번역 어휘입니다." }
            targetTokens = Array(tokens.length()) { "" }
            for (token in tokens.keys()) targetTokens[tokens.getInt(token)] = token
            val added = tokenizer.getJSONArray("added_tokens")
            targetSpecials = (0 until added.length()).map { added.getJSONObject(it) }.filter { it.getBoolean("special") }.map { it.getInt("id") }.toSet()
            check(targetTokens[1] == "</s>")
            NativeEngine.prepareTokenizer(dictionary.absolutePath.toByteArray(Charsets.UTF_8))
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(4); options.setInterOpNumThreads(1)
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                if (nnapi) {
                    options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
                    options.enableProfiling(File(directory, "nnapi-encoder-profile").absolutePath)
                }
                encoder = environment.createSession(File(directory, "encoder_model.onnx").absolutePath, options)
                if (nnapi) options.enableProfiling(File(directory, "nnapi-decoder-profile").absolutePath)
                decoder = environment.createSession(File(directory, "decoder_model_merged.onnx").absolutePath, options)
            }
            check(encoder!!.inputNames == setOf("input_ids", "attention_mask") &&
                decoder!!.inputNames == (pastNames + listOf("input_ids", "encoder_hidden_states", "use_cache_branch")).toSet() &&
                decoder!!.outputNames.size == pastNames.size + 1) { "AIHub 엔진과 호환되지 않는 ONNX 입출력입니다." }
        } catch (e: Exception) { unload(); throw e }
    }

    fun unload() {
        decoder?.close(); decoder = null
        encoder?.close(); encoder = null
        NativeEngine.unloadTokenizer()
        vocabulary = emptyMap(); targetTokens = emptyArray(); targetSpecials = emptySet()
    }

    fun sourceIds(text: String): LongArray {
        check(encoder != null) { "Load a model first" }
        val ids = mutableListOf(2L)
        // HF splits special tokens before MeCab and normalizes ordinary segments with NFKC.
        val special = Regex("\\[PAD]|\\[UNK]|\\[CLS]|\\[SEP]|\\[MASK]")
        var offset = 0
        fun appendOrdinary(segment: String) {
            val normalized = Normalizer.normalize(segment, Normalizer.Form.NFKC)
            for (word in NativeEngine.segment(normalized.toByteArray(Charsets.UTF_8))) {
                val characters = word.toString(Charsets.UTF_8).codePoints().toArray().map { String(Character.toChars(it)) }
                val pieces = mutableListOf<Long>()
                var start = 0
                if (characters.size > 100) { ids.add(1L); continue }
                while (start < characters.size) {
                    var end = characters.size
                    var token: Long? = null
                    while (end > start) {
                        val piece = (if (start == 0) "" else "##") + characters.subList(start, end).joinToString("")
                        token = vocabulary[piece]
                        if (token != null) break
                        end--
                    }
                    if (token == null) { pieces.clear(); pieces.add(1L); break }
                    pieces.add(token); start = end
                }
                ids.addAll(pieces)
            }
        }
        for (match in special.findAll(text)) {
            appendOrdinary(text.substring(offset, match.range.first))
            ids.add(vocabulary.getValue(match.value)); offset = match.range.last + 1
        }
        appendOrdinary(text.substring(offset)); ids.add(3L)
        check(ids.size <= 512) { "Text is too long; select fewer sentences" }
        return ids.toLongArray()
    }

    private val pastNames = (0 until 12).flatMap { listOf("past_key_values.$it.key", "past_key_values.$it.value") }
    private fun tensor(ids: LongArray) = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))

    fun translate(text: String, beamSize: Int = 1, active: () -> Boolean = { true }): String {
        require(beamSize in 1..3)
        require('\u0000' !in text) { "Text contains an embedded NUL" }
        val sourceSession = checkNotNull(encoder) { "Load a model first" }
        val targetSession = checkNotNull(decoder) { "Load a model first" }
        cancelled = false
        val source = sourceIds(text)
        tensor(source).use { ids -> tensor(LongArray(source.size) { 1L }).use { mask ->
            check(!cancelled && active()) { "Cancelled" }
            sourceSession.run(mapOf("input_ids" to ids, "attention_mask" to mask)).use { encoded ->
                if (beamSize > 1) return translateBeams(targetSession, encoded[0] as OnnxTensor, beamSize, active)
                val emptyPast = pastNames.associateWith {
                    OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(1, 12, 0, 64))
                }
                var previous: OrtSession.Result? = null
                val generated = mutableListOf<Int>()
                var token = 1L
                try {
                    for (step in 0 until 256) {
                        check(!cancelled && active()) { "Cancelled" }
                        tensor(longArrayOf(token)).use { next ->
                            OnnxTensor.createTensor(environment, booleanArrayOf(step > 0)).use { branch ->
                                val inputs = mutableMapOf("input_ids" to next, "encoder_hidden_states" to (encoded[0] as OnnxTensor), "use_cache_branch" to branch)
                                for ((index, name) in pastNames.withIndex()) inputs[name] = previous?.get(index + 1) as OnnxTensor? ?: emptyPast.getValue(name)
                                val result = targetSession.run(inputs)
                                previous?.close(); previous = result
                                val logits = (result[0] as OnnxTensor).floatBuffer
                                var best = 0
                                var maximum = Float.NEGATIVE_INFINITY
                                for (index in 0 until logits.remaining()) {
                                    val score = logits.get(index)
                                    if (score > maximum) { maximum = score; best = index }
                                }
                                check(maximum.isFinite()) { "Invalid model output" }
                                token = best.toLong()
                            }
                        }
                        if (token == 1L) {
                            check(!cancelled && active()) { "Cancelled" }
                            return generated.filter { it !in targetSpecials }.joinToString("") { targetTokens[it] }.replace('▁', ' ').trim()
                        }
                        check(token >= 0 && token < targetTokens.size) { "Invalid output token" }
                        generated.add(token.toInt())
                    }
                    error("Translation exceeded the output limit; select fewer sentences")
                } finally { previous?.close(); emptyPast.values.forEach { it.close() } }
            }
        } }
    }

    private fun translateBeams(session: OrtSession, hidden: OnnxTensor, width: Int, active: () -> Boolean): String {
        data class Hypothesis(val score: Double, val tokens: List<Int>, val past: OrtSession.Result?)
        val empty = pastNames.associateWith { OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(1, 12, 0, 64)) }
        var active = listOf(Hypothesis(0.0, emptyList(), null))
        var owned = mutableListOf<OrtSession.Result>()
        val finished = mutableListOf<Pair<Double, List<Int>>>()
        var firstCompletedStep = -1
        try {
            for (step in 0 until 256) {
                check(!cancelled && active()) { "Cancelled" }
                val candidates = mutableListOf<Hypothesis>()
                val previous = owned
                owned = mutableListOf()
                try {
                    for (hypothesis in active) {
                        check(!cancelled && active()) { "Cancelled" }
                        tensor(longArrayOf(hypothesis.tokens.lastOrNull()?.toLong() ?: 1L)).use { next ->
                            OnnxTensor.createTensor(environment, booleanArrayOf(step > 0)).use { branch ->
                                val inputs = mutableMapOf("input_ids" to next, "encoder_hidden_states" to hidden, "use_cache_branch" to branch)
                                for ((index, name) in pastNames.withIndex()) inputs[name] = hypothesis.past?.get(index + 1) as OnnxTensor? ?: empty.getValue(name)
                                val result = session.run(inputs).also { owned.add(it) }
                                val logits = (result[0] as OnnxTensor).floatBuffer
                                val top = mutableListOf<Pair<Float, Int>>()
                                for (index in 0 until logits.remaining()) {
                                    val score = logits.get(index)
                                    check(score.isFinite()) { "Invalid model output" }
                                    val position = top.indexOfFirst { score > it.first }.let { if (it < 0) top.size else it }
                                    if (position < 2 * width) { top.add(position, score to index); if (top.size > 2 * width) top.removeAt(top.lastIndex) }
                                }
                                val maximum = top.first().first.toDouble()
                                var sum = 0.0
                                for (index in 0 until logits.remaining()) sum += exp(logits.get(index).toDouble() - maximum)
                                val normalizer = maximum + ln(sum)
                                for ((score, token) in top) candidates.add(Hypothesis(hypothesis.score + score - normalizer, hypothesis.tokens + token, result))
                            }
                        }
                    }
                } finally { previous.forEach { it.close() } }
                candidates.sortByDescending { it.score }
                val next = mutableListOf<Hypothesis>()
                for ((rank, hypothesis) in candidates.take(2 * width).withIndex()) {
                    if (hypothesis.tokens.last() == 1) {
                        if (rank < width) finished.add(hypothesis.score / hypothesis.tokens.size to hypothesis.tokens.dropLast(1))
                    } else if (next.size < width) next.add(hypothesis)
                }
                finished.sortByDescending { it.first }
                while (finished.size > width) finished.removeAt(finished.lastIndex)
                active = next
                if (finished.isNotEmpty() && firstCompletedStep < 0) firstCompletedStep = step
                // shortcut: allow 8 more steps after the first EOS; recheck later winners when retraining.
                if (active.isEmpty() || (finished.size == width && finished.last().first >= candidates.first().score / (step + 1)) ||
                    (firstCompletedStep >= 0 && step - firstCompletedStep >= 8)) break
            }
            check(!cancelled && active()) { "Cancelled" }
            check(finished.isNotEmpty()) { "Translation exceeded the output limit; select fewer sentences" }
            return finished.first().second.filter { it !in targetSpecials }.joinToString("") { targetTokens[it] }.replace('▁', ' ').trim()
        } finally { owned.forEach { it.close() }; empty.values.forEach { it.close() } }
    }
}
