package com.gemini.ocr.network

import android.content.Context
import com.gemini.ocr.SessionState
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Persistent + RAM LRU cache and local glossary lookup on the Android device.
 * Strictly capped at 300 entries in RAM to prevent OOM on phones and BlueStacks.
 */
object PhoneTranslationCache {
    private const val PREFS_NAME = "phone_translation_cache"
    private const val MAX_ENTRIES = 300
    private val ramCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_ENTRIES
    }
    @Volatile
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(context: Context?) {
        if (loaded || context == null) return
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            for ((k, v) in prefs.all) {
                if (v is String && v.isNotBlank() && ramCache.size < MAX_ENTRIES) {
                    ramCache[k] = v
                }
            }
            loaded = true
        } catch (_: Exception) {
        }
    }

    fun parseGlossary(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val delimiterIndex = trimmed.indexOf('=').takeIf { it > 0 }
                ?: trimmed.indexOf("->").takeIf { it > 0 }
                ?: trimmed.indexOf(':').takeIf { it > 0 }
                ?: continue
            val sepLen = if (trimmed.startsWith("->", delimiterIndex)) 2 else 1
            val source = normalizeText(trimmed.substring(0, delimiterIndex))
            val target = trimmed.substring(delimiterIndex + sepLen).trim()
            if (source.isNotEmpty() && target.isNotEmpty()) {
                result[source] = target
            }
        }
        return result
    }

    fun applyGlossary(text: String, rawGlossary: String): String {
        val glossary = parseGlossary(rawGlossary)
        if (glossary.isEmpty() || text.isBlank()) return text
        val normalized = normalizeText(text)
        glossary[normalized]?.let { return it }
        var result = text
        for ((term, replacement) in glossary.entries.sortedByDescending { it.key.length }) {
            if (result.contains(term, ignoreCase = true)) {
                result = result.replace(term, replacement, ignoreCase = true)
            }
        }
        return result
    }

    fun normalizeText(text: String): String =
        text.trim().split(Regex("\\s+")).joinToString(" ")

    @Synchronized
    fun lookup(
        context: Context?,
        cacheKey: String,
        sourceText: String,
        rawGlossary: String = "",
        preloadedBankEnabled: Boolean = true,
        fuzzyEnabled: Boolean = true
    ): String? {
        val rawNormalized = normalizeText(sourceText)
        if (rawNormalized.isEmpty()) return null
        val correctedSource = SelfLearningDatabase.preprocessAndCorrectOcr(rawNormalized, context)
        val normalizedSource = normalizeText(correctedSource)

        val glossary = parseGlossary(rawGlossary)
        glossary[normalizedSource]?.let {
            SessionState.savedApiCalls.value++
            return it
        }
        glossary[rawNormalized]?.let {
            SessionState.savedApiCalls.value++
            return it
        }

        // Mode 2 (Gemini Direct) sets preloadedBankEnabled = false to bypass the preloaded bank,
        // while Mode 1 (Hybrid Siêu Tốc) checks the SQLite Bank + ChineseDialogueBank (< 5ms).
        if (preloadedBankEnabled) {
            SelfLearningDatabase.lookup(context, normalizedSource, incrementFrequency = true)?.let { record ->
                SessionState.preloadedHits.value++
                SessionState.savedApiCalls.value++
                return record.translatedText
            }
            CostOptimizationEngine.matchOfflineUiTerm(normalizedSource)?.let { uiMatched ->
                SessionState.preloadedHits.value++
                SessionState.savedApiCalls.value++
                SelfLearningDatabase.learnOrUpsert(
                    context = context,
                    sourceText = normalizedSource,
                    translatedText = uiMatched,
                    category = BankCategory.UI,
                    rawOcrBeforeCorrection = rawNormalized
                )
                return uiMatched
            }
            ChineseDialogueBank.matchInstant(normalizedSource, context)?.let { matched ->
                SessionState.preloadedHits.value++
                SessionState.savedApiCalls.value++
                SelfLearningDatabase.learnOrUpsert(
                    context = context,
                    sourceText = normalizedSource,
                    translatedText = matched,
                    rawOcrBeforeCorrection = rawNormalized
                )
                return matched
            }
        }

        ensureLoaded(context)
        ramCache[cacheKey]?.let { exact ->
            SessionState.savedApiCalls.value++
            return exact
        }

        if (preloadedBankEnabled && fuzzyEnabled && (ramCache.isNotEmpty() || SelfLearningDatabase.ramLruSize() > 0)) {
            val prefix = cacheKey.substringBeforeLast('|', cacheKey.substringBeforeLast(':', ""))
            val sameNamespaceCandidates = LinkedHashMap<String, String>()
            if (prefix.isNotEmpty()) {
                sameNamespaceCandidates.putAll(ramCache.filterKeys { it.startsWith(prefix) })
            } else {
                sameNamespaceCandidates.putAll(ramCache)
            }
            sameNamespaceCandidates.putAll(SelfLearningDatabase.getSnapshotMap(context))
            val fuzzy = CostOptimizationEngine.findFuzzyMatch(normalizedSource, sameNamespaceCandidates)
            if (fuzzy != null) {
                ramCache[cacheKey] = fuzzy.translation
                SessionState.fuzzyHits.value++
                SessionState.savedApiCalls.value++
                return fuzzy.translation
            }
        }
        return null
    }

    @Synchronized
    fun put(
        context: Context?,
        cacheKey: String,
        translated: String,
        sourceText: String = "",
        saveToBank: Boolean = true
    ) {
        if (translated.isBlank()) return
        ensureLoaded(context)
        ramCache[cacheKey] = translated
        val extractedSource = sourceText.ifBlank {
            val pipeIdx = cacheKey.lastIndexOf('|')
            val colonIdx = cacheKey.lastIndexOf(':')
            val splitAt = maxOf(pipeIdx, colonIdx)
            if (splitAt >= 0 && splitAt + 1 < cacheKey.length) cacheKey.substring(splitAt + 1) else ""
        }
        if (saveToBank && extractedSource.isNotBlank()) {
            SelfLearningDatabase.learnOrUpsert(
                context = context,
                sourceText = extractedSource,
                translatedText = translated
            )
        }
        if (context != null) {
            try {
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit().putString(cacheKey, translated)
                if (ramCache.size > MAX_ENTRIES) {
                    val keepKeys = ramCache.keys.toSet()
                    for (existingKey in prefs.all.keys) {
                        if (existingKey !in keepKeys) editor.remove(existingKey)
                    }
                }
                editor.apply()
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun clear(context: Context?) {
        ramCache.clear()
        StandaloneTranslator.clearDirectCache()
        SelfLearningDatabase.resetForTest()
        if (context != null) {
            try {
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().clear().apply()
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun size(context: Context? = null): Int {
        ensureLoaded(context)
        return ramCache.size + SelfLearningDatabase.size(context)
    }
}

/**
 * Standalone Chinese-to-Vietnamese Subtitle Translator for Android & BlueStacks (<= 1000ms E2E).
 *
 * Strictly implements the 2 Standalone Modes:
 * - Mode 1 (`MODE_HYBRID_BANK_GEMINI` = "hybrid_gemini"):
 *   **Chế độ 1: Kho Phụ Đề + Gemini (Hybrid Siêu Tốc)**
 *   Looks up Chinese subtitles in the SQLite B-Tree Bank + 300-entry RAM LRU (< 5ms).
 *   If matched -> displays immediately.
 *   If missing -> calls Gemini API directly (`thinkingBudget = 0`), displays translation,
 *   and auto-saves the new pair into the SQLite bank (`Self-Learning Cache`) on `Dispatchers.IO`.
 *
 * - Mode 2 (`MODE_GEMINI_DIRECT` = "gemini_direct"):
 *   **Chế độ 2: Chỉ dùng Gemini (Gemini Direct)**
 *   Bypasses the preloaded subtitle bank, sends the Chinese subtitle + at most 1 preceding line
 *   of context directly to Gemini API, while keeping a small 100-entry RAM LRU (`directLru100`)
 *   so stationary subtitles on screen never re-trigger API calls.
 */
object StandaloneTranslator {
    const val MODE_HYBRID_BANK_GEMINI = "hybrid_gemini"
    const val MODE_GEMINI_DIRECT = "gemini_direct"

    const val FAST_MODEL = CostOptimizationEngine.FLASH_LITE_MODEL
    const val SMART_MODEL = CostOptimizationEngine.FLASH_MODEL
    const val PRO_MODEL = CostOptimizationEngine.PRO_MODEL

    private const val DIRECT_LRU_CAPACITY = 100

    // Mode 2 small RAM LRU cache (100 recent sentences) for stationary subtitles
    private val directLru100 = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > DIRECT_LRU_CAPACITY
    }

    data class TranslationOutcome(
        val translations: List<String>,
        val elapsedMs: Long,
        val engineLabel: String,
        val isFinal: Boolean,
        val modelUsed: String = "",
        val fromBank: Boolean = false
    ) {
        val latencyMs: Long get() = elapsedMs
        val provider: String get() = engineLabel
    }

    @Volatile
    var overrideOnDeviceTranslator: (suspend (List<String>, String) -> List<String>?)? = null

    @Volatile
    var overrideGeminiTranslator: (suspend (List<String>, String, String, String, Map<String, String>) -> List<String>)? = null

    private val recentContext = ArrayDeque<Pair<String, String>>()

    // HTTP/2 Connection Pooling & Keep-Alive to keep Gemini connection warm (< 600ms)
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    fun hasApiKey(settings: AppSettings): Boolean =
        settings.effectiveStandaloneApiKey().isNotBlank()

    @Synchronized
    fun lookupDirectLru(sourceText: String): String? {
        val key = PhoneTranslationCache.normalizeText(sourceText)
        if (key.isEmpty()) return null
        return directLru100[key]
    }

    @Synchronized
    fun putDirectLru(sourceText: String, translatedVi: String) {
        val key = PhoneTranslationCache.normalizeText(sourceText)
        if (key.isEmpty() || translatedVi.isBlank()) return
        directLru100[key] = translatedVi
    }

    @Synchronized
    fun directLruSize(): Int = directLru100.size

    @Synchronized
    fun clearDirectCache() {
        directLru100.clear()
        recentContext.clear()
    }

    @Synchronized
    fun getRecentContextSnapshot(maxLines: Int = 1): List<Pair<String, String>> =
        CostOptimizationEngine.compressContextWindow(recentContext.toList(), maxItems = maxLines)

    @Synchronized
    fun recordContextPair(sourceZh: String, translatedVi: String) {
        if (sourceZh.isBlank() || translatedVi.isBlank()) return
        recentContext.addLast(sourceZh.trim() to translatedVi.trim())
        while (recentContext.size > 2) {
            recentContext.removeFirst()
        }
    }

    /**
     * Pre-warms the SQLite Chinese subtitle bank on `Dispatchers.IO` so the first frame has zero cold-start delay.
     */
    suspend fun preWarm(context: Context?, @Suppress("UNUSED_PARAMETER") sourceLang: String = "zh") =
        withContext(Dispatchers.IO) {
            try {
                SelfLearningDatabase.initializeOnIo(context)
                SessionState.offlineModelStatus.value =
                    "Kho phụ đề Tiếng Trung SQLite (idx_zh_hash) + LruCache(300) đã sẵn sàng (< 5ms)"
            } catch (_: Exception) {
            }
        }

    suspend fun prepareOfflineModel(
        context: Context? = null,
        @Suppress("UNUSED_PARAMETER") sourceLang: String = "zh",
        onStatus: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val started = monotonicMs()
        SelfLearningDatabase.initializeOnIo(context)
        val elapsed = (monotonicMs() - started).coerceAtLeast(1L)
        val count = SelfLearningDatabase.size(context).coerceAtLeast(ChineseDialogueBank.totalEntries)
        val msg = "Kho phụ đề Tiếng Trung SQLite ($count câu · B-Tree idx_zh_hash) sẵn sàng (${elapsed}ms)"
        SessionState.offlineModelStatus.value = msg
        onStatus?.invoke(msg)
        msg
    }

    /**
     * Instant Chinese Subtitle Bank lookup (< 5ms) for Mode 1 (or custom test override).
     */
    suspend fun translateOnDevice(
        context: Context? = null,
        sourceLang: String = "zh",
        texts: List<String>,
        glossaryRaw: String = ""
    ): TranslationOutcome =
        translateOnDevice(texts = texts, sourceLang = sourceLang, rawGlossary = glossaryRaw, context = context)
            ?: throw IOException("Câu thoại mới chưa có trong Kho phụ đề Tiếng Trung.")

    suspend fun translateOnDevice(
        texts: List<String>,
        sourceLang: String = "zh",
        rawGlossary: String = "",
        context: Context? = null
    ): TranslationOutcome? = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext null
        val started = monotonicMs()
        overrideOnDeviceTranslator?.let { custom ->
            val out = custom(texts, sourceLang) ?: return@withContext null
            return@withContext TranslationOutcome(
                translations = applyGlossaryPostProcess(texts, out, rawGlossary),
                elapsedMs = (monotonicMs() - started).coerceAtLeast(1L),
                engineLabel = "Kho Phụ Đề (<5ms)",
                isFinal = false,
                fromBank = true
            )
        }
        val glossary = PhoneTranslationCache.parseGlossary(rawGlossary)
        val matchedList = ArrayList<String>(texts.size)
        for (raw in texts) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                matchedList.add("")
                continue
            }
            val corrected = SelfLearningDatabase.preprocessAndCorrectOcr(trimmed, context)
            val matched = glossary[PhoneTranslationCache.normalizeText(corrected)]
                ?: glossary[PhoneTranslationCache.normalizeText(trimmed)]
                ?: SelfLearningDatabase.lookup(context, corrected, incrementFrequency = true)?.translatedText
                ?: CostOptimizationEngine.matchOfflineUiTerm(corrected)
                ?: ChineseDialogueBank.matchInstant(corrected, context)
                ?: return@withContext null
            matchedList.add(matched)
        }
        val processed = applyGlossaryPostProcess(texts, matchedList, rawGlossary)
        val elapsed = (monotonicMs() - started).coerceAtLeast(1L)
        TranslationOutcome(
            translations = processed,
            elapsedMs = elapsed,
            engineLabel = "Kho Phụ Đề (<5ms)",
            isFinal = true,
            fromBank = true
        )
    }

    /**
     * Unified Standalone Pipeline executing Mode 1 (`hybrid_gemini`) or Mode 2 (`gemini_direct`).
     */
    suspend fun translateStandaloneMode(
        context: Context?,
        texts: List<String>,
        translationMode: String,
        apiKey: String,
        glossaryRaw: String = "",
        profile: String = "manga"
    ): TranslationOutcome = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) {
            return@withContext TranslationOutcome(emptyList(), 0L, "Idle", isFinal = true)
        }
        val started = monotonicMs()
        val isDirectMode = translationMode == MODE_GEMINI_DIRECT

        if (!isDirectMode) {
            // Mode 1: Kho Phụ Đề + Gemini (Hybrid Siêu Tốc)
            // Step 1: Instant lookup (< 5ms) in SQLite Bank + 300-entry RAM LRU + multi-clause matcher
            val bankOutcome = translateOnDevice(
                texts = texts,
                sourceLang = "zh",
                rawGlossary = glossaryRaw,
                context = context
            )
            if (bankOutcome != null) {
                SessionState.preloadedHits.value += texts.size
                SessionState.savedApiCalls.value++
                texts.zip(bankOutcome.translations).forEach { (src, vi) ->
                    recordContextPair(src, vi)
                }
                return@withContext bankOutcome
            }
        } else {
            // Mode 2: Chỉ dùng Gemini (Gemini Direct)
            // Check 100-entry RAM LRU (`directLru100`) so stationary subtitles don't re-trigger API calls
            val cachedDirect = ArrayList<String>(texts.size)
            var allInDirectLru = true
            for (line in texts) {
                val hit = lookupDirectLru(line)
                if (hit != null) {
                    cachedDirect.add(hit)
                } else {
                    allInDirectLru = false
                    break
                }
            }
            if (allInDirectLru && cachedDirect.size == texts.size) {
                SessionState.cacheHits.value += texts.size
                SessionState.savedApiCalls.value++
                return@withContext TranslationOutcome(
                    translations = applyGlossaryPostProcess(texts, cachedDirect, glossaryRaw, enrichWithBank = false),
                    elapsedMs = (monotonicMs() - started).coerceAtLeast(1L),
                    engineLabel = "Gemini Direct (LruCache 100)",
                    isFinal = true,
                    modelUsed = FAST_MODEL,
                    fromBank = false
                )
            }
        }

        // Step 2: Call Gemini API (< 600ms target with thinkingBudget = 0 and 1-line context)
        val geminiOutcome = translateWithGemini(
            apiKey = apiKey,
            sourceLang = "zh",
            mode = "fast",
            profile = profile,
            texts = texts,
            glossaryRaw = glossaryRaw,
            forceHighPrecision = false,
            saveToSelfLearningBank = !isDirectMode,
            context = context
        )
        texts.zip(geminiOutcome.translations).forEach { (src, vi) ->
            putDirectLru(src, vi)
        }
        geminiOutcome.copy(
            engineLabel = if (isDirectMode) "Gemini Direct" else "Kho + Gemini (Tự học)"
        )
    }

    fun applyGlossaryPostProcess(
        sources: List<String>,
        translations: List<String>,
        rawGlossary: String,
        enrichWithBank: Boolean = true
    ): List<String> {
        val glossary = PhoneTranslationCache.parseGlossary(rawGlossary)
        return sources.zip(translations) { src, tr ->
            val exact = glossary[PhoneTranslationCache.normalizeText(src)]
            if (exact != null) return@zip exact
            var result = if (enrichWithBank) ChineseDialogueBank.enrichTranslation(src, tr) else tr
            for ((term, replacement) in glossary) {
                if (src.contains(term, ignoreCase = true) && !result.contains(replacement, ignoreCase = true)) {
                    result = result.replace(term, replacement, ignoreCase = true)
                }
            }
            result
        }
    }

    /**
     * Requirement 4.3:
     * - Ultra-short System Prompt (< 40 tokens):
     *   "Dịch phụ đề phim tiếng Trung sau sang tiếng Việt tự nhiên, đúng ngữ cảnh và xưng hô. Chỉ trả về câu dịch, không giải thích."
     * - `thinkingBudget = 0` to receive the first token immediately.
     * - At most 1 preceding subtitle line for context.
     */
    fun buildGeminiRequestJson(
        texts: List<String>,
        sourceLang: String = "zh",
        profile: String = "manga",
        @Suppress("UNUSED_PARAMETER") mode: String = "fast",
        glossary: Map<String, String> = emptyMap(),
        contextItems: List<Pair<String, String>> = emptyList(),
        isProEscalated: Boolean = false
    ): String {
        val systemPrompt = CostOptimizationEngine.ULTRA_SHORT_SYSTEM_PROMPT
        val compressedContext = CostOptimizationEngine.compressContextWindow(contextItems, maxItems = 1)

        val userPromptBuilder = StringBuilder()
        if (compressedContext.isNotEmpty()) {
            val (prevZh, prevVi) = compressedContext.last()
            userPromptBuilder.append("Ngữ cảnh câu trước: ").append(prevZh).append(" -> ").append(prevVi).append("\n")
        }
        if (glossary.isNotEmpty()) {
            val matchingTerms = glossary.entries.filter { (s, _) ->
                texts.any { it.contains(s, ignoreCase = true) }
            }.take(8)
            if (matchingTerms.isNotEmpty()) {
                userPromptBuilder.append("Từ điển: ")
                userPromptBuilder.append(matchingTerms.joinToString(", ") { "${it.key}=${it.value}" })
                userPromptBuilder.append("\n")
            }
        }
        if (texts.size == 1) {
            userPromptBuilder.append(texts[0].trim())
        } else {
            userPromptBuilder.append(CostOptimizationEngine.formatNumberedBatch(texts))
        }

        val maxOutputTokens = CostOptimizationEngine.computeMaxOutputTokens(texts, isProEscalated)
        val schema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject().put(
                    "translations",
                    JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING"))
                )
            )
            .put("required", JSONArray().put("translations"))

        val generationConfig = JSONObject()
            .put("responseMimeType", "application/json")
            .put("responseSchema", schema)
            .put("maxOutputTokens", maxOutputTokens)
            .put("temperature", 0.1)
            .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))

        return JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userPromptBuilder.toString())))
                )
            )
            .put("generationConfig", generationConfig)
            .toString()
    }

    fun parseGeminiResponseJson(body: String, expectedCount: Int): List<String> {
        val root = JSONObject(body)
        val candidates = root.getJSONArray("candidates")
        if (candidates.length() == 0) throw IOException("Gemini không trả về kết quả.")
        val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val textBuilder = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (!part.optBoolean("thought", false)) {
                textBuilder.append(part.optString("text", ""))
            }
        }
        val rawText = textBuilder.toString().trim()
        return try {
            val parsed = JSONObject(rawText)
            val arr = parsed.getJSONArray("translations")
            if (arr.length() != expectedCount) {
                throw IOException("Số câu dịch trả về không khớp (${arr.length()}/$expectedCount).")
            }
            List(arr.length()) { idx ->
                arr.getString(idx).trim()
                    .replace(Regex("^\\d+\\s*[.):\\-]\\s*"), "")
                    .trim()
                    .also { require(it.isNotEmpty()) }
            }
        } catch (e: Exception) {
            CostOptimizationEngine.parseNumberedLines(rawText, expectedCount)
                ?: if (expectedCount == 1 && rawText.isNotBlank() && !rawText.startsWith("{")) {
                    listOf(rawText.lines().first().trim())
                } else {
                    throw IOException(e.message ?: "Không đọc được phản hồi từ Gemini.")
                }
        }
    }

    suspend fun translateWithGemini(
        settings: AppSettings,
        texts: List<String>,
        forceHighPrecision: Boolean = false
    ): TranslationOutcome = translateWithGemini(
        apiKey = settings.effectiveStandaloneApiKey(),
        sourceLang = "zh",
        mode = "fast",
        profile = settings.profile,
        texts = texts,
        glossaryRaw = settings.localGlossary,
        forceHighPrecision = forceHighPrecision,
        saveToSelfLearningBank = settings.translationMode != MODE_GEMINI_DIRECT
    )

    suspend fun translateWithGemini(
        apiKey: String,
        sourceLang: String = "zh",
        mode: String = "fast",
        profile: String = "manga",
        texts: List<String>,
        glossaryRaw: String = "",
        forceHighPrecision: Boolean = false,
        saveToSelfLearningBank: Boolean = true,
        context: Context? = null
    ): TranslationOutcome = withContext(Dispatchers.IO) {
        val started = monotonicMs()
        val glossary = PhoneTranslationCache.parseGlossary(glossaryRaw)
        val route = CostOptimizationEngine.routeModel(
            texts = texts,
            sourceLang = sourceLang,
            profile = profile,
            mode = mode,
            forceHighPrecision = forceHighPrecision
        )
        SessionState.activeModelName.value = route.model

        val correctedTexts = texts.map { SelfLearningDatabase.preprocessAndCorrectOcr(it, context) }
        overrideGeminiTranslator?.let { custom ->
            val out = custom(correctedTexts, sourceLang, profile, mode, glossary)
            val processed = applyGlossaryPostProcess(correctedTexts, out, glossaryRaw, enrichWithBank = saveToSelfLearningBank)
            correctedTexts.zip(processed).forEach { (src, vi) ->
                recordContextPair(src, vi)
                putDirectLru(src, vi)
                if (saveToSelfLearningBank) {
                    SelfLearningDatabase.learnOrUpsert(
                        context = context,
                        sourceText = src,
                        translatedText = vi
                    )
                }
            }
            return@withContext TranslationOutcome(
                translations = processed,
                elapsedMs = (monotonicMs() - started).coerceAtLeast(1L),
                engineLabel = "Gemini Flash-Lite",
                isFinal = true,
                modelUsed = FAST_MODEL
            )
        }
        if (apiKey.isBlank()) {
            throw IOException("Chưa có khóa Gemini API. Hãy nhập Gemini API Key ở tab Tùy chỉnh để dịch câu mới.")
        }
        val model = FAST_MODEL
        val contextSnapshot = getRecentContextSnapshot(maxLines = 1)
        val payload = buildGeminiRequestJson(
            texts = correctedTexts,
            sourceLang = "zh",
            profile = profile,
            mode = "fast",
            glossary = glossary,
            contextItems = contextSnapshot,
            isProEscalated = false
        )
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                when (response.code) {
                    429 -> throw IOException("Đạt giới hạn lượt gọi Gemini API (429).")
                    400, 401, 403 -> throw IOException("Khóa Gemini API hoặc mô hình không hợp lệ (${response.code}).")
                    else -> throw IOException("Máy chủ Gemini tạm thời không phản hồi (${response.code}).")
                }
            }
            val rawTranslations = parseGeminiResponseJson(rawBody, correctedTexts.size)
            val translations = applyGlossaryPostProcess(correctedTexts, rawTranslations, glossaryRaw, enrichWithBank = saveToSelfLearningBank)
            texts.indices.forEach { idx ->
                val rawSrc = texts[idx]
                val correctedSrc = correctedTexts[idx]
                val vi = translations.getOrNull(idx).orEmpty()
                if (vi.isNotBlank()) {
                    recordContextPair(correctedSrc, vi)
                    putDirectLru(rawSrc, vi)
                    putDirectLru(correctedSrc, vi)
                    if (saveToSelfLearningBank) {
                        SelfLearningDatabase.learnOrUpsert(
                            context = context,
                            sourceText = correctedSrc,
                            translatedText = vi,
                            rawOcrBeforeCorrection = rawSrc
                        )
                    }
                }
            }
            val elapsed = (monotonicMs() - started).coerceAtLeast(1L)
            TranslationOutcome(
                translations = translations,
                elapsedMs = elapsed,
                engineLabel = "Gemini Flash-Lite",
                isFinal = true,
                modelUsed = model
            )
        }
    }
}
