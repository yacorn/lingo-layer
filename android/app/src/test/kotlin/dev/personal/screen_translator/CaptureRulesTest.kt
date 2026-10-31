package dev.personal.screen_translator

import org.junit.Assert.*
import org.junit.Test

class CaptureRulesTest {
    @Test fun screenChunksAcceptLongPagesWithoutDroppingTextOrSplittingSurrogates() {
        val page="今日は朝からとてもいい天気ですね。少し散歩しませんか？".repeat(40)
        assertTrue(page.length>700)
        val chunks=CaptureRules.screenChunks(page)
        assertEquals(page,chunks.joinToString(""))
        assertTrue(chunks.all { it.length in 1..160 })
        val unpunctuated="あ".repeat(159)+"😀"+"い".repeat(700)
        val pieces=CaptureRules.screenChunks(unpunctuated)
        assertEquals(unpunctuated,pieces.joinToString(""))
        assertTrue(pieces.all { it.length<=160 && !Character.isHighSurrogate(it.last()) && !Character.isLowSurrogate(it.first()) })
        assertEquals(emptyList<String>(),CaptureRules.screenChunks(" \n "))
    }
    @Test fun sourceLineHeightIgnoresMissingBoxesAndSmallOutliers() {
        assertEquals(30f,CaptureRules.medianLineHeight(listOf(0,-1,12,30,30,31)),0f)
        assertEquals(31f,CaptureRules.medianLineHeight(listOf(30,32)),0f)
        assertEquals(0f,CaptureRules.medianLineHeight(emptyList()),0f)
    }
    @Test fun denseParagraphPaddingDoesNotMoveSourceText() {
        val regions = (0 until 8).map { Region(.1f,(100+it*35)/2000f,.9f,(130+it*35)/2000f) }
        val cells = CaptureRules.displayCells(regions,1000,2000)
        regions.forEachIndexed { index, source ->
            val box = source.pixels(1000,2000)
            val anchor = checkNotNull(CaptureRules.inlineAnchor(box,cells[index],6))
            assertEquals("Block $index text x moved",box[0],anchor[0]+anchor[2])
            assertEquals("Block $index text y moved",box[1],anchor[1]+anchor[2])
        }
    }
    @Test fun impossibleCellDoesNotRelocateItsSourceIntoAnotherParagraph() {
        assertNull(CaptureRules.inlineAnchor(intArrayOf(100,400,800,300),intArrayOf(0,600,1000,400),6))
    }
    @Test fun anchorsAtInsetsAndAcrossDensitiesKeepTheExactTextOrigin() {
        for (scale in 1..3) {
            val source=intArrayOf(10*scale,30*scale,80*scale,30*scale)
            val cell=intArrayOf(10*scale,30*scale,100*scale,50*scale)
            assertArrayEquals(intArrayOf(10*scale,30*scale,0),CaptureRules.inlineAnchor(source,cell,6*scale))
        }
        assertNull(CaptureRules.inlineAnchor(intArrayOf(100,100,10,10),intArrayOf(0,0,0,0),6))
    }
    @Test fun failedSentencesKeepTheirSourceAndReadableReason() {
        val source="少し散歩しませんか？"
        assertEquals("[입력 길이 초과 · 원문] $source",CaptureRules.failedTranslation(source,"Text is too long; select fewer sentences"))
        assertEquals("[출력 길이 초과 · 원문] $source",CaptureRules.failedTranslation(source,"Translation exceeded the output limit; select fewer sentences"))
        assertEquals("[번역 결과 없음 · 원문] $source",CaptureRules.failedTranslation(source,"빈 번역 결과입니다."))
        assertEquals("[번역 처리 오류 · 원문] $source",CaptureRules.failedTranslation(source,"Native error with /private/path"))
        assertEquals("[번역 처리 오류 · 원문] $source",CaptureRules.failedTranslation(source,null))
    }
    @Test fun translationCellsExcludeSystemBars() {
        val cells = CaptureRules.displayCells(listOf(Region(.05f,.15f,.95f,.4f),Region(.05f,.8f,.95f,.97f)),1080,2340,intArrayOf(0,96,0,72))
        cells.forEach { assertTrue(it[0]>=0 && it[1]>=96 && it[0]+it[2]<=1080 && it[1]+it[3]<=2268) }
        assertTrue(cells[0][1]+cells[0][3]<=cells[1][1])
        assertThrows(IllegalArgumentException::class.java) { CaptureRules.displayCells(emptyList(),100,200,intArrayOf(0,100,0,100)) }
    }
    @Test fun onlyCompletelyBlackPixelsAreRejected() {
        assertTrue(CaptureRules.isBlackFrame(intArrayOf(0, 0xff000000.toInt())))
        assertFalse(CaptureRules.isBlackFrame(intArrayOf(0xff000000.toInt(), 0xff000001.toInt())))
        assertFalse(CaptureRules.isBlackFrame(intArrayOf(0xffffffff.toInt())))
    }
    @Test fun shortDialogueKeepsContextWhileLongParagraphStartsWithOneSentence() {
        assertEquals(listOf("今日は晴れです。散歩しませんか？"), CaptureRules.sentenceChunks("今日は晴れです。散歩しませんか？"))
        val first = "今日は朝からとてもいい天気なので、近くの公園までゆっくり散歩したいと思っています。"
        val second = "公園では、木の下で休みながら、友達と昨日の出来事について話す予定です。"
        val third = "帰りには、お店で新鮮な野菜を買います。"
        val chunks = CaptureRules.sentenceChunks(first+second+third)
        assertEquals(first, chunks.first())
        assertEquals(first+second+third, chunks.joinToString(""))
        assertTrue(chunks.size >= 2)
    }
    @Test fun ocrWrapsDoNotBreakJapaneseWordsOrLatinNamesAndQuotesStayTogether() {
        val original = "「今日は晴れですか？」と聞きました。"+"リビ\nアヤマネコの学名は Felis\nsilvestris です。"+"動物の名前を間違えないように、図鑑を見ながらゆっくり説明します。"
        val chunks = CaptureRules.sentenceChunks(original)
        assertEquals("「今日は晴れですか？」と聞きました。", chunks.first())
        assertTrue(chunks.joinToString("").contains("リビアヤマネコ"))
        assertTrue(chunks.joinToString("").contains("Felis silvestris"))
        assertFalse(chunks.any { '\n' in it })
        assertEquals(emptyList<String>(), CaptureRules.sentenceChunks(" \n "))
    }
    @Test fun translationCellsKeepColumnsAndStackedBubblesApart() {
        val regions = listOf(Region(.05f,.1f,.45f,.25f), Region(.55f,.1f,.95f,.25f),
            Region(.05f,.35f,.45f,.5f), Region(.05f,.7f,.95f,.9f))
        val cells = CaptureRules.displayCells(regions, 1000, 2000)
        cells.forEachIndexed { index, cell ->
            val source = regions[index].pixels(1000,2000)
            assertTrue(cell[0] <= source[0] && cell[1] <= source[1])
            assertTrue(cell[0]+cell[2] >= source[0]+source[2] && cell[1]+cell[3] >= source[1]+source[3])
            assertTrue(cell[0] >= 0 && cell[1] >= 0 && cell[0]+cell[2] <= 1000 && cell[1]+cell[3] <= 2000)
            cells.drop(index+1).forEach { other ->
                assertTrue(cell[0]+cell[2] <= other[0] || other[0]+other[2] <= cell[0] ||
                    cell[1]+cell[3] <= other[1] || other[1]+other[3] <= cell[1])
            }
        }
        assertArrayEquals(intArrayOf(0,0,1000,2000), CaptureRules.displayCells(listOf(regions.first()),1000,2000).single())
        assertTrue(CaptureRules.displayCells(emptyList(),1000,2000).isEmpty())
    }
    @Test fun overlappingOcrBoxesStillReceiveSeparateDisplayCells() {
        val cells = CaptureRules.displayCells(listOf(Region(.1f,.2f,.6f,.4f), Region(.3f,.3f,.8f,.5f)),1000,2000)
        val a = cells[0]; val b = cells[1]
        assertTrue(a[0]+a[2] <= b[0] || b[0]+b[2] <= a[0] || a[1]+a[3] <= b[1] || b[1]+b[3] <= a[1])
    }
    @Test fun screenCropExcludesSystemBarsAndRejectsEmptySelection() {
        assertArrayEquals(intArrayOf(0, 110, 100, 70), CaptureRules.contentCrop(Region(), 100, 200, intArrayOf(0, 10, 0, 20)))
        assertArrayEquals(intArrayOf(8, 10, 85, 170), CaptureRules.contentCrop(Region(0f,0f,1f,1f), 100, 200, intArrayOf(8,10,7,20)))
        assertThrows(IllegalArgumentException::class.java) { CaptureRules.contentCrop(Region(0f,.95f,1f,1f), 100,200,intArrayOf(0,10,0,20)) }
    }
    @Test fun cropHandlesReverseDragsAndBounds() {
        assertArrayEquals(intArrayOf(20, 40, 60, 120), Region(.8f, .8f, .2f, .2f).pixels(100, 200))
        assertArrayEquals(intArrayOf(0, 0, 100, 200), Region(-1f, -1f, 2f, 2f).pixels(100, 200))
        assertArrayEquals(intArrayOf(99, 199, 1, 1), Region(1f, 1f, 1f, 1f).pixels(100, 200))
    }
    @Test fun ocrNormalizationKeepsPunctuationAndDetectsKanjiOnlyJapanese() {
        assertEquals("こんにちは。\n次へ！", CaptureRules.normalize(" こんにちは。 \n\n 次へ！ "))
        assertTrue(CaptureRules.hasJapanese("入口"))
        assertTrue(CaptureRules.hasJapanese("ｺﾝﾆﾁﾊ"))
        assertFalse(CaptureRules.hasJapanese("번역 시작 123"))
    }
    @Test fun inlinePositionUsesCropOffsetAndAllOcrBlocks() {
        val area = CaptureRules.textRegion(listOf(intArrayOf(10, 15, 60, 35), intArrayOf(5, 40, 80, 90)),
            intArrayOf(20, 100, 100, 200), 200, 400)
        assertEquals(Region(.125f, .2875f, .5f, .475f), area)
        assertEquals(Region(.1f, .25f, .6f, .75f), CaptureRules.textRegion(emptyList(), intArrayOf(20,100,100,200), 200,400))
        assertEquals(Region(.1f, .25f, .6f, .75f), CaptureRules.textRegion(listOf(intArrayOf(-10,-10,500,500)), intArrayOf(20,100,100,200), 200,400))
    }
}
