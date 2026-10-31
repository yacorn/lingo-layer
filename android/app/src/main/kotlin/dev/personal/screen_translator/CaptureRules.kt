package dev.personal.screen_translator

import kotlin.math.max
import kotlin.math.min

data class Region(val left: Float = 0f, val top: Float = .55f, val right: Float = 1f, val bottom: Float = 1f) {
    fun pixels(width: Int, height: Int): IntArray {
        require(width > 0 && height > 0)
        val x1 = (min(left, right).coerceIn(0f, 1f) * width).toInt().coerceAtMost(width - 1)
        val y1 = (min(top, bottom).coerceIn(0f, 1f) * height).toInt().coerceAtMost(height - 1)
        val x2 = (max(left, right).coerceIn(0f, 1f) * width).toInt().coerceIn(x1 + 1, width)
        val y2 = (max(top, bottom).coerceIn(0f, 1f) * height).toInt().coerceIn(y1 + 1, height)
        return intArrayOf(x1, y1, x2 - x1, y2 - y1)
    }
}
data class InlineTranslation(val text: String, val region: Region, val progressive: Boolean = false, val sourceGlyphHeightPx: Float = 0f)
object CaptureRules {
    fun medianLineHeight(heights: List<Int>): Float {
        val valid = heights.filter { it > 0 }.sorted()
        if (valid.isEmpty()) return 0f
        return (valid[(valid.size-1)/2].toFloat()+valid[valid.size/2])/2f
    }
    fun screenChunks(text: String): List<String> = sentenceChunks(text).flatMap { sentence ->
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < sentence.length) {
            var end = min(start+160,sentence.length)
            if (end < sentence.length && Character.isHighSurrogate(sentence[end-1]) && Character.isLowSurrogate(sentence[end])) end--
            val piece = sentence.substring(start,end)
            if (piece.isNotBlank()) pieces.add(piece)
            start = end
        }
        pieces
    }
    fun isBlackFrame(pixels: IntArray) = pixels.all { it and 0x00ffffff == 0 }
    fun inlineAnchor(box: IntArray, cell: IntArray, padding: Int): IntArray? {
        require(box.size == 4 && cell.size == 4 && padding >= 0)
        if (cell[2] <= 0 || cell[3] <= 0 || box[0] < cell[0] || box[1] < cell[1] ||
            box[0] >= cell[0]+cell[2] || box[1] >= cell[1]+cell[3]) return null
        // Reduce decoration padding instead of moving the OCR text origin into a neighbouring cell.
        val fittedPadding = min(padding,min(box[0]-cell[0],box[1]-cell[1]))
        return intArrayOf(box[0]-fittedPadding,box[1]-fittedPadding,fittedPadding)
    }
    fun failedTranslation(source: String, message: String?): String {
        val detail = message.orEmpty().lowercase()
        val reason = when {
            "too long" in detail || "700자" in detail -> "입력 길이 초과"
            "output limit" in detail -> "출력 길이 초과"
            "빈 번역 결과" in detail -> "번역 결과 없음"
            else -> "번역 처리 오류"
        }
        return "[$reason · 원문] $source"
    }
    // Rectangular cells keep expanded translations apart while retaining their source anchors.
    fun displayCells(regions: List<Region>, width: Int, height: Int, insets: IntArray = intArrayOf(0,0,0,0)): List<IntArray> {
        require(insets.size == 4 && insets.all { it >= 0 } && insets[0]+insets[2]<width && insets[1]+insets[3]<height)
        val boxes = regions.map { it.pixels(width, height) }
        return boxes.mapIndexed { index, box ->
            var left = insets[0]; var top = insets[1]; var right = width-insets[2]; var bottom = height-insets[3]
            val x = box[0]+box[2]/2f; val y = box[1]+box[3]/2f
            boxes.forEachIndexed { otherIndex, other ->
                if (index != otherIndex) {
                    val ox = other[0]+other[2]/2f; val oy = other[1]+other[3]/2f
                    val gapX = max(box[0], other[0])-min(box[0]+box[2], other[0]+other[2])
                    val gapY = max(box[1], other[1])-min(box[1]+box[3], other[1]+other[3])
                    val horizontal = if (gapX > 0 || gapY > 0) gapX > gapY else kotlin.math.abs(x-ox) > kotlin.math.abs(y-oy)
                    if (horizontal) {
                        val split = if (gapX >= 0) (max(box[0], other[0])+min(box[0]+box[2], other[0]+other[2]))/2 else ((x+ox)/2).toInt()
                        if (x < ox || (x == ox && index < otherIndex)) right = min(right, split) else left = max(left, split)
                    } else {
                        val split = if (gapY >= 0) (max(box[1], other[1])+min(box[1]+box[3], other[1]+other[3]))/2 else ((y+oy)/2).toInt()
                        if (y < oy || (y == oy && index < otherIndex)) bottom = min(bottom, split) else top = max(top, split)
                    }
                }
            }
            intArrayOf(left, top, max(0, right-left), max(0, bottom-top))
        }
    }
    fun contentCrop(region: Region, width: Int, height: Int, insets: IntArray): IntArray {
        require(insets.size == 4 && insets.all { it >= 0 })
        val crop = region.pixels(width, height)
        val left = max(crop[0], insets[0])
        val top = max(crop[1], insets[1])
        val right = min(crop[0]+crop[2], width-insets[2])
        val bottom = min(crop[1]+crop[3], height-insets[3])
        require(right > left && bottom > top) { "선택 영역에 앱 화면이 없습니다. 상태바와 하단 버튼을 제외해 주세요." }
        return intArrayOf(left, top, right-left, bottom-top)
    }
    fun normalize(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    fun sentenceChunks(text: String): List<String> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()
        if (normalized.length <= 80) return listOf(normalized)
        // OCR line wraps are not sentence boundaries; retain spaces between Latin words.
        val paragraph = normalized.replace(Regex("([^\\n])\\n(?=[^\\n])")) { match ->
            val before = match.value.first()
            val after = normalized[match.range.last+1]
            "$before"+if (before.isLetterOrDigit() && before.code<128 && after.isLetterOrDigit() && after.code<128) " " else ""
        }
        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        val closing = mutableListOf<Char>()
        val pairs = mapOf('「' to '」', '『' to '』', '（' to '）', '(' to ')', '“' to '”')
        for (character in paragraph) {
            current.append(character)
            pairs[character]?.let { closing.add(it) }
            if (closing.lastOrNull() == character) closing.removeAt(closing.lastIndex)
            if (character in "。！？!?" && closing.isEmpty()) {
                sentences.add(current.toString().trim()); current.clear()
            }
        }
        if (current.isNotBlank()) sentences.add(current.toString().trim())
        // shortcut: punctuation-free blocks stay intact; add semantic segmentation if these dominate real screens.
        if (sentences.size <= 1) return listOf(paragraph)
        val chunks = mutableListOf(sentences.first())
        var next = 1
        while (next < sentences.size) {
            var chunk = sentences[next++]
            if (next < sentences.size && chunk.length+sentences[next].length <= 80) chunk += sentences[next++]
            chunks.add(chunk)
        }
        return chunks
    }
    fun hasJapanese(text: String) = text.any { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' || it in '\uff66'..'\uff9f' }
    fun textRegion(boxes: List<IntArray>, crop: IntArray, width: Int, height: Int): Region {
        require(crop.size == 4 && width > 0 && height > 0 && crop[2] > 0 && crop[3] > 0)
        val valid = boxes.filter { it.size == 4 && it[2] > it[0] && it[3] > it[1] }
        val left = valid.minOfOrNull { it[0] }?.coerceIn(0, crop[2]) ?: 0
        val top = valid.minOfOrNull { it[1] }?.coerceIn(0, crop[3]) ?: 0
        val right = valid.maxOfOrNull { it[2] }?.coerceIn(left, crop[2]) ?: crop[2]
        val bottom = valid.maxOfOrNull { it[3] }?.coerceIn(top, crop[3]) ?: crop[3]
        return Region((crop[0]+left).toFloat()/width, (crop[1]+top).toFloat()/height,
            (crop[0]+right).toFloat()/width, (crop[1]+bottom).toFloat()/height)
    }
}
