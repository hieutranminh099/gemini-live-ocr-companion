package com.gemini.ocr.network

import android.content.Context
import com.gemini.ocr.SessionState
import com.gemini.ocr.settings.AppSettings
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Persistent + RAM LRU cache and local glossary lookup on the Android device.
 * Enables < 5ms instant lookup for repeated game UI, HUD labels, and subtitles across sessions.
 */
object PhoneTranslationCache {
    private const val PREFS_NAME = "phone_translation_cache"
    private const val MAX_ENTRIES = 1500
    private val ramCache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
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
                if (v is String && v.isNotBlank()) {
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
        // Check Self-Learning SQLite Bank (< 1ms via RAM mirror + increments usage_frequency)
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
        if (preloadedBankEnabled) {
            ChineseDialogueBank.matchInstant(normalizedSource)?.let { matched ->
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
        if (fuzzyEnabled && (ramCache.isNotEmpty() || SelfLearningDatabase.size(context) > 0)) {
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
    fun put(context: Context?, cacheKey: String, translated: String, sourceText: String = "") {
        if (translated.isBlank()) return
        ensureLoaded(context)
        ramCache[cacheKey] = translated
        val extractedSource = sourceText.ifBlank {
            val pipeIdx = cacheKey.lastIndexOf('|')
            val colonIdx = cacheKey.lastIndexOf(':')
            val splitAt = maxOf(pipeIdx, colonIdx)
            if (splitAt >= 0 && splitAt + 1 < cacheKey.length) cacheKey.substring(splitAt + 1) else ""
        }
        if (extractedSource.isNotBlank()) {
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
 * Standalone On-Device & Direct Gemini Translator for Android with 5-Tier Cost Optimization.
 *
 * Tier 0: Preloaded Chinese Movie/Manhua/Game Dialogue Bank + Universal Offline UI Dictionary (< 1ms).
 * Tier 1: ML Kit On-Device Translation (~40–140ms, works 100% offline after language pack download).
 * Tier 2: Smart Model Routing (`gemini-3.1-flash-lite-preview` / `gemini-3.5-flash` by default;
 *         escalates to `gemini-3.1-pro-preview` only for >200 words, complex classical/technical text,
 *         or explicit High Precision user re-translation).
 */
object StandaloneTranslator {
    const val FAST_MODEL = CostOptimizationEngine.FLASH_LITE_MODEL
    const val SMART_MODEL = CostOptimizationEngine.FLASH_MODEL
    const val PRO_MODEL = CostOptimizationEngine.PRO_MODEL

    data class TranslationOutcome(
        val translations: List<String>,
        val elapsedMs: Long,
        val engineLabel: String,
        val isFinal: Boolean,
        val modelUsed: String = ""
    ) {
        val latencyMs: Long get() = elapsedMs
        val provider: String get() = engineLabel
    }

    @Volatile
    var overrideOnDeviceTranslator: (suspend (List<String>, String) -> List<String>?)? = null

    @Volatile
    var overrideGeminiTranslator: (suspend (List<String>, String, String, String, Map<String, String>) -> List<String>)? = null

    private val translators = ConcurrentHashMap<String, Translator>()
    private val readyModels = ConcurrentHashMap.newKeySet<String>()
    private val recentContext = ArrayDeque<Pair<String, String>>()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    fun hasApiKey(settings: AppSettings): Boolean =
        settings.effectiveStandaloneApiKey().isNotBlank()

    fun mlKitLanguageCode(sourceLang: String): String = when (sourceLang.lowercase()) {
        "ja" -> TranslateLanguage.JAPANESE
        "zh" -> TranslateLanguage.CHINESE
        "ko" -> TranslateLanguage.KOREAN
        else -> TranslateLanguage.ENGLISH
    }

    private fun getOrCreateTranslator(sourceLang: String): Translator {
        val code = mlKitLanguageCode(sourceLang)
        return translators.getOrPut(code) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(code)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build()
            Translation.getClient(options)
        }
    }

    /**
     * Pre-warms the preloaded Chinese dialogue bank, persistent cache, and ML Kit Translator client
     * in the background so the first captured subtitle frame experiences zero cold-start delay.
     */
    suspend fun preWarm(context: Context?, sourceLang: String) = withContext(Dispatchers.Default) {
        try {
            SelfLearningDatabase.ensureInitialized(context)
            ChineseDialogueBank.totalEntries
            PhoneTranslationCache.size(context)
            val translator = getOrCreateTranslator(sourceLang)
            if (sourceLang.lowercase() in readyModels) {
                translator.translate("你好").await()
            }
        } catch (_: Exception) {
        }
    }

    suspend fun prepareOfflineModel(
        @Suppress("UNUSED_PARAMETER") context: Context? = null,
        sourceLang: String,
        onStatus: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val started = monotonicMs()
        try {
            val loadingMsg = "Đang tải / kiểm tra gói ngôn ngữ ($sourceLang → vi)…"
            SessionState.offlineModelStatus.value = loadingMsg
            onStatus?.invoke(loadingMsg)
            val translator = getOrCreateTranslator(sourceLang)
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions).await()
            // Warm up native translation pipeline with a single token so subsequent calls are instant
            try { translator.translate("你好").await() } catch (_: Exception) {}
            readyModels.add(sourceLang.lowercase())
            val elapsed = monotonicMs() - started
            val msg = "Gói dịch ngoại tuyến ($sourceLang → vi) đã sẵn sàng (${elapsed} ms)"
            SessionState.offlineModelStatus.value = msg
            onStatus?.invoke(msg)
            msg
        } catch (e: Exception) {
            val msg = "Chưa tải được gói ngoại tuyến ($sourceLang): ${e.message ?: "Kiểm tra kết nối mạng"}"
            SessionState.offlineModelStatus.value = msg
            onStatus?.invoke(msg)
            throw e
        }
    }

    suspend fun translateOnDevice(
        @Suppress("UNUSED_PARAMETER") context: Context? = null,
        sourceLang: String,
        texts: List<String>,
        glossaryRaw: String = ""
    ): TranslationOutcome =
        translateOnDevice(texts = texts, sourceLang = sourceLang, rawGlossary = glossaryRaw)
            ?: throw IOException("Chưa tải xong gói dịch ngoại tuyến ($sourceLang → vi).")

    suspend fun translateOnDevice(
        texts: List<String>,
        sourceLang: String,
        rawGlossary: String = ""
    ): TranslationOutcome? = withContext(Dispatchers.Default) {
        if (texts.isEmpty()) return@withContext null
        val started = monotonicMs()
        overrideOnDeviceTranslator?.let { custom ->
            val out = custom(texts, sourceLang) ?: return@withContext null
            return@withContext TranslationOutcome(
                translations = applyGlossaryPostProcess(texts, out, rawGlossary),
                elapsedMs = (monotonicMs() - started).coerceAtLeast(1L),
                engineLabel = "ML Kit On-Device",
                isFinal = false
            )
        }
        try {
            val translator = getOrCreateTranslator(sourceLang)
            if (sourceLang.lowercase() !in readyModels) {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                readyModels.add(sourceLang.lowercase())
                SessionState.offlineModelStatus.value = "Gói dịch ngoại tuyến ($sourceLang → vi) đã sẵn sàng"
            }
            val translated = coroutineScope {
                texts.map { text ->
                    async {
                        val trimmed = text.trim()
                        if (trimmed.isEmpty()) {
                            ""
                        } else {
                            CostOptimizationEngine.matchOfflineUiTerm(trimmed)
                                ?: ChineseDialogueBank.matchInstant(trimmed)
                                ?: translator.translate(trimmed).await()
                        }
                    }
                }.awaitAll()
            }
            if (translated.size != texts.size || translated.any { it.isBlank() }) {
                return@withContext null
            }
            val processed = applyGlossaryPostProcess(texts, translated, rawGlossary)
            val elapsed = (monotonicMs() - started).coerceAtLeast(1L)
            TranslationOutcome(
                translations = processed,
                elapsedMs = elapsed,
                engineLabel = "ML Kit On-Device",
                isFinal = false
            )
        } catch (_: Exception) {
            null
        }
    }

    fun applyGlossaryPostProcess(
        sources: List<String>,
        translations: List<String>,
        rawGlossary: String
    ): List<String> {
        val glossary = PhoneTranslationCache.parseGlossary(rawGlossary)
        return sources.zip(translations) { src, tr ->
            val exact = glossary[PhoneTranslationCache.normalizeText(src)]
            if (exact != null) return@zip exact
            var result = ChineseDialogueBank.enrichTranslation(src, tr)
            for ((term, replacement) in glossary) {
                if (src.contains(term, ignoreCase = true) && !result.contains(replacement, ignoreCase = true)) {
                    result = result.replace(term, replacement, ignoreCase = true)
                }
            }
            result
        }
    }

    fun buildGeminiRequestJson(
        texts: List<String>,
        sourceLang: String,
        profile: String,
        mode: String,
        glossary: Map<String, String>,
        contextItems: List<Pair<String, String>>,
        isProEscalated: Boolean = false
    ): String {
        val systemPrompt = CostOptimizationEngine.COMPACT_SYSTEM_INSTRUCTION

        val numberedBatch = CostOptimizationEngine.formatNumberedBatch(texts)
        val userPayload = JSONObject()
            .put("source_language", sourceLang)
            .put("target_language", "vi")
            .put("profile", profile)
            .put("numbered_lines", numberedBatch)
            .put("items", JSONArray().apply { texts.forEach { put(it) } })
        if (glossary.isNotEmpty()) {
            val gArr = JSONArray()
            for ((s, t) in glossary.entries.take(20)) {
                if (texts.any { it.contains(s, ignoreCase = true) }) {
                    gArr.put(JSONObject().put("source", s).put("target", t))
                }
            }
            if (gArr.length() > 0) userPayload.put("glossary", gArr)
        }
        // Tier 4C: Compress context window to 1-2 lines immediately preceding current frame
        if (contextItems.isNotEmpty()) {
            val compressedContext = CostOptimizationEngine.compressContextWindow(contextItems, maxItems = 2)
            val cArr = JSONArray()
            for ((s, v) in compressedContext) {
                cArr.put(JSONObject().put("source", s).put("vi", v))
            }
            userPayload.put("context", cArr)
        }

        // Tier 4B: Lock maxOutputTokens tightly (100-250 tokens for normal dialogue)
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
            .put("temperature", 0.2)
            .put("thinkingConfig", JSONObject().put("thinkingLevel", if (mode == "fast" && !isProEscalated) "minimal" else "low"))

        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userPayload.toString())))
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
                ?: throw IOException(e.message ?: "Không đọc được phản hồi từ Gemini.")
        }
    }

    suspend fun translateWithGemini(
        settings: AppSettings,
        texts: List<String>,
        forceHighPrecision: Boolean = false
    ): TranslationOutcome = translateWithGemini(
        apiKey = settings.effectiveStandaloneApiKey(),
        sourceLang = settings.language,
        mode = if (settings.turboLatency && !forceHighPrecision) "fast" else settings.mode,
        profile = settings.profile,
        texts = texts,
        glossaryRaw = settings.localGlossary,
        forceHighPrecision = forceHighPrecision
    )

    suspend fun translateWithGemini(
        apiKey: String,
        sourceLang: String,
        mode: String,
        profile: String,
        texts: List<String>,
        glossaryRaw: String = "",
        forceHighPrecision: Boolean = false
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

        val correctedTexts = texts.map { SelfLearningDatabase.preprocessAndCorrectOcr(it) }
        overrideGeminiTranslator?.let { custom ->
            val out = custom(correctedTexts, sourceLang, profile, mode, glossary)
            correctedTexts.zip(out).forEach { (src, vi) ->
                SelfLearningDatabase.learnOrUpsert(
                    context = null,
                    sourceText = src,
                    translatedText = vi
                )
            }
            return@withContext TranslationOutcome(
                translations = out,
                elapsedMs = (monotonicMs() - started).coerceAtLeast(1L),
                engineLabel = "Gemini Flash-Lite",
                isFinal = true,
                modelUsed = FAST_MODEL
            )
        }
        if (apiKey.isBlank()) {
            throw IOException("Chưa có khóa Gemini API trên điện thoại. Hãy nhập khóa hoặc dùng Chế độ 1: Dịch thuần từ Kho.")
        }
        val model = FAST_MODEL
        val contextSnapshot = synchronized(recentContext) { recentContext.toList() }
        val payload = buildGeminiRequestJson(
            texts = correctedTexts,
            sourceLang = sourceLang,
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
            val translations = applyGlossaryPostProcess(correctedTexts, rawTranslations, glossaryRaw)
            synchronized(recentContext) {
                correctedTexts.zip(translations).forEach { (src, vi) ->
                    recentContext.addLast(src to vi)
                    while (recentContext.size > 4) recentContext.removeFirst()
                }
            }
            texts.indices.forEach { idx ->
                val rawSrc = texts[idx]
                val correctedSrc = correctedTexts[idx]
                val vi = translations.getOrNull(idx).orEmpty()
                if (vi.isNotBlank()) {
                    SelfLearningDatabase.learnOrUpsert(
                        context = null,
                        sourceText = correctedSrc,
                        translatedText = vi,
                        rawOcrBeforeCorrection = rawSrc
                    )
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
