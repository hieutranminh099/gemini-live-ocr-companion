package com.gemini.ocr.network

import kotlin.math.max
import kotlin.math.min

/**
 * Implements the 5-Tier Gemini Real-Time OCR Cost & Token Optimization Architecture:
 *
 * 1. Smart Model Routing (Flash / Flash-Lite by default; escalates to Gemini Pro only for >200 words,
 *    complex classical/technical passages, or explicit "High Precision" user re-translation).
 * 2. Translation Memory & Local Cache with Exact Match, Fuzzy Match (90–95%+ similarity & number/entity
 *    template substitution), and Offline UI Terminology Dictionary.
 * 3. OCR Noise Regex Pre-filtering (strips graphic artifacts | / \ _ : ;, numbers-only, and <3 char noise)
 *    + 2-Frame Stabilization before cloud calls + Numbered Multi-line Batching.
 * 4. Compact System Instruction, tight max_output_tokens cap (100–250 tokens for normal dialogue),
 *    and Context Window Compression (1–2 preceding lines).
 * 5. Smart Hybrid On-Device + Cloud Routing (handles short everyday lines 100% offline with ML Kit;
 *    escalates to Gemini API only for long/multi-clause or Sino-Vietnamese/Xianxia/Sci-Fi/Technical lines).
 */
object CostOptimizationEngine {

    const val FLASH_LITE_MODEL = "gemini-3.1-flash-lite-preview"
    const val FLASH_MODEL = "gemini-3.5-flash"
    const val PRO_MODEL = "gemini-3.1-pro-preview"

    const val COMPACT_SYSTEM_INSTRUCTION =
        "Bạn là bộ dịch OCR. Dịch trực tiếp sang tiếng Việt tự nhiên, giữ nguyên cấu trúc dòng, " +
        "tự sửa lỗi chính tả OCR nếu có. Không giải thích, chỉ trả về bản dịch. " +
        "Trả về đúng định dạng JSON {\"translations\":[\"...\"]} khớp thứ tự từng dòng."

    data class ModelRouteDecision(
        val model: String,
        val reason: String,
        val isProEscalated: Boolean
    )

    data class FuzzyMatchResult(
        val translation: String,
        val similarity: Double,
        val templateMatched: Boolean
    )

    // Universal Offline UI & Game Terminology Dictionary (0 tokens across EN, JA, KO, ZH)
    private val offlineUiDictionary: Map<String, String> = mapOf(
        // English UI terms
        "start" to "Bắt đầu",
        "start game" to "Bắt đầu trò chơi",
        "new game" to "Trò chơi mới",
        "continue" to "Tiếp tục",
        "load game" to "Tải trò chơi",
        "save game" to "Lưu trò chơi",
        "save" to "Lưu",
        "load" to "Tải",
        "settings" to "Cài đặt",
        "options" to "Tùy chọn",
        "inventory" to "Túi đồ",
        "backpack" to "Balo",
        "equipment" to "Trang bị",
        "level up" to "Tăng cấp",
        "level" to "Cấp độ",
        "skill" to "Kỹ năng",
        "skills" to "Kỹ năng",
        "quest" to "Nhiệm vụ",
        "quests" to "Nhiệm vụ",
        "main quest" to "Nhiệm vụ chính",
        "side quest" to "Nhiệm vụ phụ",
        "mission complete" to "Hoàn thành nhiệm vụ",
        "mission failed" to "Nhiệm vụ thất bại",
        "victory" to "Chiến thắng",
        "defeat" to "Thất bại",
        "game over" to "Kết thúc trò chơi",
        "confirm" to "Xác nhận",
        "cancel" to "Hủy bỏ",
        "accept" to "Chấp nhận",
        "decline" to "Từ chối",
        "skip" to "Bỏ qua",
        "pause" to "Tạm dừng",
        "resume" to "Tiếp tục",
        "retry" to "Thử lại",
        "exit" to "Thoát",
        "quit" to "Thoát",
        "back" to "Quay lại",
        "next" to "Tiếp theo",
        "shop" to "Cửa hàng",
        "store" to "Cửa hàng",
        "map" to "Bản đồ",
        "status" to "Trạng thái",
        "character" to "Nhân vật",
        "party" to "Đội hình",
        "guild" to "Bang hội",
        "friends" to "Bạn bè",
        "mail" to "Hộp thư",
        "claim" to "Nhận thưởng",
        "claim all" to "Nhận tất cả",
        "reward" to "Phần thưởng",
        "rewards" to "Phần thưởng",
        "loading" to "Đang tải...",
        "connecting" to "Đang kết nối...",
        // Japanese UI terms
        "スタート" to "Bắt đầu",
        "ゲーム開始" to "Bắt đầu trò chơi",
        "つづきから" to "Tiếp tục",
        "設定" to "Cài đặt",
        "オプション" to "Tùy chọn",
        "インベントリ" to "Túi đồ",
        "持ち物" to "Túi đồ",
        "装備" to "Trang bị",
        "レベルアップ" to "Tăng cấp",
        "スキル" to "Kỹ năng",
        "クエスト" to "Nhiệm vụ",
        "確認" to "Xác nhận",
        "キャンセル" to "Hủy bỏ",
        "スキップ" to "Bỏ qua",
        "戻る" to "Quay lại",
        "次へ" to "Tiếp theo",
        // Korean UI terms
        "시작" to "Bắt đầu",
        "게임 시작" to "Bắt đầu trò chơi",
        "계속하기" to "Tiếp tục",
        "설정" to "Cài đặt",
        "인벤토리" to "Túi đồ",
        "가방" to "Túi đồ",
        "장비" to "Trang bị",
        "레벨업" to "Tăng cấp",
        "스킬" to "Kỹ năng",
        "퀘스트" to "Nhiệm vụ",
        "확인" to "Xác nhận",
        "취소" to "Hủy bỏ",
        "건너뛰기" to "Bỏ qua",
        "뒤로" to "Quay lại"
    )

    private val whitelistedShortWords = setOf(
        "ok", "no", "go", "up", "on", "hp", "mp", "lv", "xp", "sp", "ap", "ai", "pc", "ui", "id"
    )

    // Graphic / border characters commonly misread by screen OCR
    private val graphicNoiseChars = setOf(
        '|', '/', '\\', '_', ':', ';', '-', '=', '+', '*', '~', '^', '·', '•',
        '<', '>', '[', ']', '(', ')', '{', '}', '!', '?', '.', ',', '\'', '"',
        '`', '´', '¦', '¬', '°', '±', '×', '÷', '—', '–', '…', '「', '」', '『', '』', '【', '】'
    )

    private val numberRegex = Regex("\\d+(?:[.,:/]\\d+)*")
    private val cjkScriptRegex = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]")

    // Classical Chinese, Xianxia, Wuxia, Sci-Fi & Technical markers that benefit from Gemini context
    private val complexDomainMarkers = listOf(
        // Xianxia / Cultivation / Wuxia
        "元婴", "化神", "渡劫", "大乘", "筑基", "金丹", "天道", "法则", "神识", "本命",
        "心魔", "乾坤", "阴阳", "阵法", "封印", "血脉", "灵根", "丹田", "经脉", "剑意",
        "领域", "苍穹", "轮回", "涅槃", "神魂", "妖兽", "魔族", "仙界", "秘境", "传承",
        // Classical / Historical literary markers
        "寡人", "陛下", "微臣", "圣旨", "钦此", "岂敢", "何以", "未尝", "天命", "社稷",
        // Sci-Fi / Technical markers
        "量子", "跃迁", "协议", "矩阵", "曲率", "引力", "星际", "神经元", "演算法", "防火墙",
        "quantum", "protocol", "hyperspace", "anomaly", "neural", "encryption", "algorithm", "calibration"
    )

    /**
     * Ultra-lightweight Cloud Model Routing:
     * Gemini Pro is completely removed to prevent lag, latency spikes, and high token costs.
     * Always routes to the fastest, most cost-efficient Flash-Lite engine (`gemini-3.1-flash-lite-preview`).
     */
    fun routeModel(
        @Suppress("UNUSED_PARAMETER") texts: List<String>,
        @Suppress("UNUSED_PARAMETER") sourceLang: String,
        @Suppress("UNUSED_PARAMETER") profile: String,
        @Suppress("UNUSED_PARAMETER") mode: String,
        @Suppress("UNUSED_PARAMETER") forceHighPrecision: Boolean = false
    ): ModelRouteDecision {
        return ModelRouteDecision(
            model = FLASH_LITE_MODEL,
            reason = "flash_lite_exclusive_engine",
            isProEscalated = false
        )
    }

    fun estimateWordCount(text: String, sourceLang: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        val cjkCount = trimmed.count { ch ->
            Character.UnicodeScript.of(ch.code) in setOf(
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL
            )
        }
        val nonCjkWords = trimmed
            .replace(cjkScriptRegex, " ")
            .trim()
            .split(Regex("\\s+"))
            .count { it.isNotBlank() }
        // In Chinese/Japanese, ~1.5 CJK characters correspond to 1 English word (>300 CJK chars ~= >200 words)
        val cjkWordEquivalent = if (sourceLang in setOf("zh", "ja")) (cjkCount * 2) / 3 else cjkCount / 2
        return nonCjkWords + cjkWordEquivalent
    }

    private fun containsClassicalOrComplexStructure(text: String): Boolean {
        val classicalParticles = listOf("之", "乎", "者", "也", "焉", "哉", "乃", "吾", "汝", "寡人", "诏曰")
        val hits = classicalParticles.count { text.contains(it) }
        return hits >= 2 || complexDomainMarkers.count { text.contains(it, ignoreCase = true) } >= 2
    }

    /**
     * Tier 2A: Offline UI Terminology Dictionary lookup (< 1ms, 0 tokens).
     */
    fun matchOfflineUiTerm(sourceText: String): String? {
        val cleaned = sourceText.trim()
            .trim { it in graphicNoiseChars || it.isWhitespace() }
            .lowercase()
        if (cleaned.isEmpty()) return null
        return offlineUiDictionary[cleaned]
    }

    /**
     * Tier 2B: Fuzzy Match (90–95%+ similarity) & Number/Entity Template Substitution.
     * Reuses existing translations when strings differ only by numbers (e.g. "Level 5" -> "Level 12",
     * "获得 100 金币" -> "获得 250 金币") or have >= 90% character similarity with same number count.
     */
    fun findFuzzyMatch(
        sourceText: String,
        candidates: Map<String, String>,
        minSimilarity: Double = 0.90
    ): FuzzyMatchResult? {
        val normalizedSource = normalizeForFuzzy(sourceText)
        if (normalizedSource.length < 3 || candidates.isEmpty()) return null

        val sourceNumbers = extractNumbers(sourceText)
        val sourceTemplate = toNumberTemplate(normalizedSource)

        var bestTranslation: String? = null
        var bestSimilarity = 0.0
        var bestIsTemplate = false

        for ((rawKey, cachedTranslation) in candidates) {
            val candidateSource = extractSourceFromCacheKey(rawKey)
            if (candidateSource.isEmpty() || candidateSource == sourceText) continue
            val normalizedCandidate = normalizeForFuzzy(candidateSource)
            if (normalizedCandidate.length < 3) continue

            val candidateNumbers = extractNumbers(candidateSource)
            val candidateTemplate = toNumberTemplate(normalizedCandidate)

            // Case 1: Exact template match where only numbers changed (e.g. "Nhận 50 vàng" -> "Nhận 200 vàng")
            if (sourceNumbers.isNotEmpty() &&
                sourceNumbers.size == candidateNumbers.size &&
                sourceTemplate == candidateTemplate &&
                sourceTemplate.length >= 4
            ) {
                val substituted = substituteNumbers(cachedTranslation, candidateNumbers, sourceNumbers)
                if (substituted != null) {
                    return FuzzyMatchResult(
                        translation = substituted,
                        similarity = 0.99,
                        templateMatched = true
                    )
                }
            }

            // Case 2: High character similarity (90-95%+)
            val lengthDiff = kotlin.math.abs(normalizedSource.length - normalizedCandidate.length)
            val maxLen = max(normalizedSource.length, normalizedCandidate.length)
            if (lengthDiff.toDouble() / maxLen > (1.0 - minSimilarity)) continue

            val sim = stringSimilarity(normalizedSource, normalizedCandidate)
            if (sim >= minSimilarity && sim > bestSimilarity) {
                val resolved = if (sourceNumbers.isNotEmpty() && sourceNumbers.size == candidateNumbers.size) {
                    substituteNumbers(cachedTranslation, candidateNumbers, sourceNumbers) ?: cachedTranslation
                } else if (sourceNumbers.isEmpty() && candidateNumbers.isEmpty()) {
                    cachedTranslation
                } else {
                    null
                }
                if (resolved != null) {
                    bestSimilarity = sim
                    bestTranslation = resolved
                    bestIsTemplate = false
                }
            }
        }

        return bestTranslation?.let {
            FuzzyMatchResult(
                translation = it,
                similarity = bestSimilarity,
                templateMatched = bestIsTemplate
            )
        }
    }

    private fun extractSourceFromCacheKey(cacheKey: String): String {
        // Cache keys have format "lang|profile|mode|sourceText" or "lang:mode:profile:sourceText"
        val pipeCount = cacheKey.count { it == '|' }
        if (pipeCount >= 3) {
            var idx = -1
            repeat(3) { idx = cacheKey.indexOf('|', idx + 1) }
            if (idx >= 0 && idx + 1 < cacheKey.length) return cacheKey.substring(idx + 1)
        }
        val colonCount = cacheKey.count { it == ':' }
        if (colonCount >= 3) {
            var idx = -1
            repeat(3) { idx = cacheKey.indexOf(':', idx + 1) }
            if (idx >= 0 && idx + 1 < cacheKey.length) return cacheKey.substring(idx + 1)
        }
        return cacheKey
    }

    private fun normalizeForFuzzy(text: String): String =
        ChineseDialogueBank.normalizeTraditional(text.trim()).lowercase().replace(Regex("\\s+"), " ")

    fun extractNumbers(text: String): List<String> =
        numberRegex.findAll(text).map { it.value }.toList()

    private fun toNumberTemplate(text: String): String {
        var idx = 0
        return numberRegex.replace(text) { "{#${idx++}}" }
    }

    private fun substituteNumbers(
        cachedTranslation: String,
        oldNumbers: List<String>,
        newNumbers: List<String>
    ): String? {
        if (oldNumbers.size != newNumbers.size) return null
        var result = cachedTranslation
        for (i in oldNumbers.indices) {
            val oldNum = oldNumbers[i]
            val newNum = newNumbers[i]
            if (oldNum == newNum) continue
            if (!result.contains(oldNum)) return null
            result = result.replaceFirst(oldNum, newNum)
        }
        return result
    }

    /**
     * Normalized Levenshtein similarity in [0.0, 1.0].
     */
    fun stringSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        val dist = levenshteinDistance(a, b)
        return (1.0 - dist.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val n = lhs.length
        val m = rhs.length
        if (n == 0) return m
        if (m == 0) return n
        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            val lhsChar = lhs[i - 1]
            for (j in 1..m) {
                val cost = if (lhsChar == rhs[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[m]
    }

    /**
     * Tier 3A: OCR Noise Pre-filtering.
     * Rejects:
     * - Empty or overly short strings (< 3 chars for non-CJK Latin scripts unless whitelisted like OK/HP/MP/LV)
     * - Strings containing only digits, punctuation, or graphic border characters (| / \ _ : ;)
     * - Strings where > 65% of characters are graphic noise artifacts.
     */
    fun isOcrNoise(
        text: String,
        language: String = "en",
        retainSourceFragments: Boolean = false
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true

        if (retainSourceFragments &&
            trimmed.any { it.isDigit() || it == '_' } &&
            trimmed.all { it.isDigit() || it == '_' || it == '.' || it == '-' || it.isWhitespace() }
        ) {
            return false
        }

        // Reject if no letters at all (numbers + symbols only)
        val letterCount = trimmed.count { it.isLetter() }
        if (letterCount == 0) return true

        // Count graphic noise characters (| / \ _ : ; etc.)
        val nonSpaceLength = trimmed.count { !it.isWhitespace() }
        val noiseCount = trimmed.count { it in graphicNoiseChars }
        if (nonSpaceLength > 0 && noiseCount.toDouble() / nonSpaceLength.toDouble() > 0.65) {
            return true
        }

        val hasCjk = trimmed.any { ch ->
            Character.UnicodeScript.of(ch.code) in setOf(
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL
            )
        }

        if (!hasCjk && language !in setOf("zh", "ja", "ko")) {
            val cleanAlphaNum = trimmed.filter { it.isLetterOrDigit() }
            if (cleanAlphaNum.length < 3 && cleanAlphaNum.lowercase() !in whitelistedShortWords) {
                return true
            }
        } else if (!hasCjk) {
            // Even when source language is CJK, if OCR read random Latin noise < 3 chars, filter it out
            val cleanAlphaNum = trimmed.filter { it.isLetterOrDigit() }
            if (cleanAlphaNum.length < 3 && cleanAlphaNum.lowercase() !in whitelistedShortWords) {
                return true
            }
        }

        return false
    }

    /**
     * Tier 3C: Numbered Batching for multiple OCR blocks on a single frame.
     * Formats ["Câu A", "Câu B"] -> "1. Câu A\n2. Câu B"
     */
    fun formatNumberedBatch(texts: List<String>): String =
        texts.mapIndexed { idx, line -> "${idx + 1}. ${line.replace("\n", " ").trim()}" }
            .joinToString("\n")

    /**
     * Parses a numbered response ("1. Dịch A\n2. Dịch B") if returned as plain text.
     */
    fun parseNumberedLines(rawText: String, expectedCount: Int): List<String>? {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.replace(Regex("^\\d+\\s*[.):\\-]\\s*"), "").trim() }
            .filter { it.isNotEmpty() }
        return if (lines.size == expectedCount) lines else null
    }

    /**
     * Tier 4B: Locks max_output_tokens tightly (100–250 tokens for normal dialogue)
     * to prevent hallucination loops and token waste.
     */
    fun computeMaxOutputTokens(texts: List<String>, isProEscalated: Boolean = false): Int {
        val totalChars = texts.sumOf { it.length }
        if (!isProEscalated && totalChars <= 160 && texts.size <= 6) {
            return (totalChars * 2 + texts.size * 24).coerceIn(100, 250)
        }
        return (totalChars * 3 + texts.size * 32).coerceIn(250, 1024)
    }

    /**
     * Tier 4C: Context Window Compression — keeps only the last 1–2 dialogue lines
     * to preserve personal pronouns without bloating input tokens.
     */
    fun compressContextWindow(
        history: List<Pair<String, String>>,
        maxItems: Int = 2
    ): List<Pair<String, String>> = history.takeLast(maxItems.coerceIn(1, 2))

    /**
     * Mode 2 (Kho kết hợp Gemini) Smart Hybrid Escalation:
     * - Phrases that match the local SQLite Self-Learning Bank, Preloaded Chinese Dialogue Bank,
     *   or Offline UI Dictionary are served in < 1ms with 0 cloud tokens (`false`).
     * - Phrases that exceed [charThreshold] (default 15 chars), contain complex domain markers,
     *   or are missing from the local bank (when [learnMissingShortPhrases] is enabled) escalate to
     *   Gemini Flash-Lite once so they can be automatically learned into SQLite.
     */
    fun shouldEscalateToGemini(
        texts: List<String>,
        sourceLang: String,
        profile: String,
        costSavingMode: Boolean = true,
        forceHighPrecision: Boolean = false,
        charThreshold: Int = 15,
        learnMissingShortPhrases: Boolean = false
    ): Boolean {
        if (forceHighPrecision) return true
        if (!costSavingMode) return true
        if (profile == "technical") return true

        val effectiveThreshold = charThreshold.coerceIn(5, 60)

        for (raw in texts) {
            val corrected = SelfLearningDatabase.preprocessAndCorrectOcr(raw)
            if (corrected.isEmpty()) continue

            // Check if already in SQLite Self-Learning Bank, ChineseDialogueBank, or Offline UI Dictionary
            val inBank = SelfLearningDatabase.lookup(null, corrected, incrementFrequency = false) != null ||
                ChineseDialogueBank.matchInstant(corrected) != null ||
                matchOfflineUiTerm(corrected) != null

            // If phrase exceeds the flexible character threshold, escalate to Gemini Flash-Lite
            if (corrected.length > effectiveThreshold) {
                return true
            }

            // If phrase is <= threshold and already in the bank, serve directly from bank (< 1ms, 0 tokens)
            if (inBank) {
                continue
            }

            // If missing from bank and user chose to call Gemini once to auto-learn into SQLite
            if (learnMissingShortPhrases) {
                return true
            }

            val simplified = ChineseDialogueBank.normalizeTraditional(corrected)
            if (complexDomainMarkers.any { simplified.contains(it, ignoreCase = true) }) {
                return true
            }
            val clauseCount = simplified.split(Regex("[，,、；;。.！!？?…]+")).count { it.isNotBlank() }
            if (clauseCount >= 3) return true

            val cjkChars = simplified.count { ch ->
                Character.UnicodeScript.of(ch.code) in setOf(
                    Character.UnicodeScript.HAN,
                    Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA,
                    Character.UnicodeScript.HANGUL
                )
            }
            if (cjkChars > effectiveThreshold) return true
            val wordCount = estimateWordCount(simplified, sourceLang)
            if (wordCount > 12) return true
        }
        return false
    }
}
