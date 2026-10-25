package com.gemini.ocr.network

import com.gemini.ocr.TranslationBackendState
import com.gemini.ocr.capture.SubtitleDebounce
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneTranslatorTest {

    @After
    fun tearDown() {
        StandaloneTranslator.overrideOnDeviceTranslator = null
        StandaloneTranslator.overrideGeminiTranslator = null
        PhoneTranslationCache.clear(null)
    }

    @Test
    fun parseGlossaryExtractsKeyValuePairsAndIgnoresComments() {
        val raw = """
            # Nhân vật game
            旅人 = Nhà Lữ Hành
            派蒙 : Paimon
            
            原神 = Genshin Impact
        """.trimIndent()
        val parsed = PhoneTranslationCache.parseGlossary(raw)
        assertEquals(3, parsed.size)
        assertEquals("Nhà Lữ Hành", parsed["旅人"])
        assertEquals("Paimon", parsed["派蒙"])
        assertEquals("Genshin Impact", parsed["原神"])
    }

    @Test
    fun applyGlossaryReplacesTermsSortedByLength() {
        val raw = """
            星穹 = Tinh Khung
            崩坏星穹铁道 = Honkai: Star Rail
        """.trimIndent()
        val result = PhoneTranslationCache.applyGlossary("Chào mừng đến 崩坏星穹铁道 và 星穹", raw)
        assertEquals("Chào mừng đến Honkai: Star Rail và Tinh Khung", result)
    }

    @Test
    fun turboDebounceReducesInitialAndJitterSettleWindowForSubOneSecondTarget() {
        val debounce = SubtitleDebounce()
        debounce.changed(1000L, empty = false)
        assertEquals(70L, debounce.remainingMs(1020L, turbo = false))
        assertEquals(5L, debounce.remainingMs(1020L, turbo = true))
        assertEquals(0L, debounce.remainingMs(1025L, turbo = true))

        // Three rapid changes within 1000ms activate jitter settle: 180ms standard vs 70ms turbo
        debounce.changed(1050L, empty = false)
        debounce.changed(1100L, empty = false)
        assertEquals(150L, debounce.remainingMs(1130L, turbo = false))
        assertEquals(40L, debounce.remainingMs(1130L, turbo = true))
        assertEquals(0L, debounce.remainingMs(1170L, turbo = true))
    }

    @Test
    fun standaloneBackendStateFormatsExpectedBadges() {
        val hybrid = TranslationBackendState(provider = "standalone", mode = "standalone_hybrid", translationAvailable = true)
        val mlkit = TranslationBackendState(provider = "standalone", mode = "standalone_mlkit", translationAvailable = true)
        val gemini = TranslationBackendState(provider = "standalone", mode = "standalone_gemini", translationAvailable = true)

        assertTrue(hybrid.badge.contains("ML Kit (<150ms) + Gemini Flash-Lite"))
        assertTrue(mlkit.badge.contains("ML Kit On-Device (<150ms)"))
        assertTrue(gemini.badge.contains("Gemini Flash-Lite trực tiếp"))
    }

    @Test
    fun standaloneTwoStageTranslatorsApplyGlossaryAndRecordLatency() = runBlocking {
        StandaloneTranslator.overrideOnDeviceTranslator = { texts, _ ->
            texts.map { "Bản nháp $it" }
        }
        StandaloneTranslator.overrideGeminiTranslator = { texts, _, _, _, _ ->
            texts.map { "Tinh chỉnh $it" }
        }
        val stage1 = StandaloneTranslator.translateOnDevice(
            context = null,
            sourceLang = "ja",
            texts = listOf("旅人"),
            glossaryRaw = "旅人 = Nhà Lữ Hành"
        )
        assertEquals(listOf("Nhà Lữ Hành"), stage1.translations)
        assertTrue(stage1.latencyMs >= 1L)

        val stage2 = StandaloneTranslator.translateWithGemini(
            apiKey = "test-key",
            sourceLang = "ja",
            mode = "fast",
            profile = "game",
            texts = listOf("こんにちは"),
            glossaryRaw = ""
        )
        assertEquals(listOf("Tinh chỉnh こんにちは"), stage2.translations)
        assertTrue(stage2.latencyMs >= 1L)
    }

    @Test
    fun chineseDialogueBankMatchesSimplifiedAndTraditionalAndPreservesTrailingPunctuation() {
        assertTrue(ChineseDialogueBank.totalEntries >= 200)
        assertEquals("Cẩn thận!", ChineseDialogueBank.matchInstant("小心！"))
        assertEquals("Chuyện này không thể nào?", ChineseDialogueBank.matchInstant("這不可能？"))
        assertEquals("Đa tạ sư huynh!", ChineseDialogueBank.matchInstant("【多謝師兄！】"))
    }

    @Test
    fun chineseDialogueBankMatchesMultiClauseSubtitlesAndTemplates() {
        // Multi-clause composite line
        assertEquals("Sư huynh, cẩn thận!", ChineseDialogueBank.matchInstant("师兄，小心！"))
        assertEquals("Đợi một chút, tôi đến ngay", ChineseDialogueBank.matchInstant("等一下，我马上来"))
        assertEquals("Mau đi thôi, không kịp nữa rồi!", ChineseDialogueBank.matchInstant("快走，来不及了！"))

        // Template pattern matching (e.g. 别...了)
        assertEquals("Đừng nói nữa!", ChineseDialogueBank.matchInstant("别说了！"))
    }

    @Test
    fun chineseDialogueBankEnrichesCultivationAndHistoricalTermsInFallbackTranslations() {
        val enriched = ChineseDialogueBank.enrichTranslation(
            sourceText = "师尊在宗门突破元婴",
            translated = "Sư tôn ở tông môn đột phá元婴"
        )
        assertEquals("Sư tôn ở tông môn đột phá Nguyên Anh", enriched)
    }

    @Test
    fun standaloneZeroDebounceFiresImmediatelyOnFirstFrameAndReducesJitterSettle() {
        val debounce = SubtitleDebounce()
        debounce.changed(1000L, empty = false)
        // Zero initial settle in standalone turbo mode
        assertEquals(0L, debounce.remainingMs(1000L, turbo = true, standaloneZeroDebounce = true))

        // Three rapid changes within 1000ms activate 35ms jitter settle in standalone zero-debounce mode
        debounce.changed(1050L, empty = false)
        debounce.changed(1100L, empty = false)
        assertEquals(15L, debounce.remainingMs(1120L, turbo = true, standaloneZeroDebounce = true))
        assertEquals(0L, debounce.remainingMs(1135L, turbo = true, standaloneZeroDebounce = true))
    }

    @Test
    fun phoneTranslationCachePrioritizesUserGlossaryAndPreloadedChineseBank() {
        PhoneTranslationCache.put(
            context = null,
            cacheKey = "zh:fast:manga:自定义句子",
            translated = "Câu tùy chỉnh đã lưu"
        )
        // User glossary overrides preloaded bank when exact term exists
        val glossaryHit = PhoneTranslationCache.lookup(
            context = null,
            cacheKey = "zh:fast:manga:小心",
            sourceText = "小心",
            rawGlossary = "小心 = Coi chừng nhé",
            preloadedBankEnabled = true
        )
        val preloadedHit = PhoneTranslationCache.lookup(
            context = null,
            cacheKey = "zh:fast:manga:快走，来不及了！",
            sourceText = "快走，来不及了！",
            rawGlossary = "",
            preloadedBankEnabled = true
        )
        val ramCacheHit = PhoneTranslationCache.lookup(
            context = null,
            cacheKey = "zh:fast:manga:自定义句子",
            sourceText = "自定义句子",
            rawGlossary = "",
            preloadedBankEnabled = true
        )
        assertEquals("Coi chừng nhé", glossaryHit)
        assertEquals("Mau đi thôi, không kịp nữa rồi!", preloadedHit)
        assertEquals("Câu tùy chỉnh đã lưu", ramCacheHit)
    }
}
