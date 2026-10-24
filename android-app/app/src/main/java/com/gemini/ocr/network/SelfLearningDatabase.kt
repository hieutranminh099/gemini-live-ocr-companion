package com.gemini.ocr.network

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.gemini.ocr.SessionState

/**
 * Category classification for entries in the Self-Learning SQLite Translation Bank.
 */
object BankCategory {
    const val UI = "UI"
    const val PRONOUN = "PRONOUN"
    const val DIALOGUE = "DIALOGUE"

    fun displayName(category: String): String = when (category.uppercase()) {
        UI -> "UI (Giao diện)"
        PRONOUN -> "Danh xưng"
        else -> "Câu thoại thường"
    }
}

/**
 * Represents a single record in the local SQLite Self-Learning Translation Bank (`translation_bank`).
 *
 * Fields required by specification:
 * - [sourceText]: câu_nguồn
 * - [translatedText]: bản_dịch
 * - [category]: thể_loại (UI, PRONOUN / danh xưng, DIALOGUE / câu thoại thường)
 * - [commonOcrErrors]: lỗi_OCR_hay_gặp (các ký tự/biến thể OCR thường quét sai)
 * - [usageFrequency]: tần_suất_sử_dụng
 */
data class TranslationBankRecord(
    val sourceText: String,
    val translatedText: String,
    val category: String,
    val commonOcrErrors: String = "",
    val usageFrequency: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Local SQLite + In-Memory LruCache Self-Learning Translation Bank & OCR Pre-correction Engine.
 *
 * Responsibilities:
 * 1. Stores dynamic translations in SQLite (`translation_bank` table) and mirrors active entries in RAM
 *    for < 1ms lookup on the real-time OCR thread.
 * 2. Pre-corrects common OCR recognition errors (`preprocessAndCorrectOcr`) using preloaded confusion pairs
 *    and user/auto-learned `common_ocr_errors` rules before matching or translating.
 * 3. Automatically classifies and saves every successful Gemini Flash-Lite cloud translation back into SQLite
 *    so subsequent scans are served locally with 0 tokens.
 */
object SelfLearningDatabase {
    private const val DB_NAME = "gemini_ocr_self_learning.db"
    private const val DB_VERSION = 1
    private const val TABLE_BANK = "translation_bank"

    private const val MAX_RAM_RECORDS = 2500
    private val ramRecords = object : LinkedHashMap<String, TranslationBankRecord>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranslationBankRecord>?): Boolean =
            size > MAX_RAM_RECORDS
    }

    // Maps an OCR-misread variant directly to its canonical source_text
    private val variantToCanonical = HashMap<String, String>(512)

    // Substring replacement pairs (wrongOcrSubstring -> correctedSubstring)
    private val confusionSubstringRules = LinkedHashMap<String, String>()

    @Volatile
    private var dbHelper: BankSqliteHelper? = null

    @Volatile
    private var initialized = false

    private class BankSqliteHelper(context: Context) :
        SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_BANK (
                    source_text TEXT PRIMARY KEY,
                    translated_text TEXT NOT NULL,
                    category TEXT NOT NULL,
                    common_ocr_errors TEXT NOT NULL DEFAULT '',
                    usage_frequency INTEGER NOT NULL DEFAULT 1,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_bank_freq ON $TABLE_BANK(usage_frequency DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_bank_cat ON $TABLE_BANK(category)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            onCreate(db)
        }
    }

    // Preloaded OCR confusion pairs commonly encountered in screen/game/manhua OCR
    private val defaultConfusionPairs: List<Pair<String, String>> = listOf(
        "己经" to "已经",
        "自已" to "自己",
        "进人" to "进入",
        "大好了" to "太好了",
        "大慢了" to "太慢了",
        "大强了" to "太强了",
        "木有" to "没有",
        "来不乃" to "来不及",
        "不叮能" to "不可能",
        "师兄I" to "师兄！",
        "小心I" to "小心！",
        "找死I" to "找死！",
        "快走I" to "快走！",
        "住手I" to "住手！",
        "L0ADING" to "LOADING",
        "LeveI Up" to "Level Up",
        "LEVEI UP" to "LEVEL UP",
        "S7ART" to "START",
        "C0NFIRM" to "CONFIRM",
        "INVENT0RY" to "INVENTORY"
    )

    // Seed entries stored in SQLite on first launch so the bank is immediately rich and inspectable
    private val seedRecords: List<TranslationBankRecord> = listOf(
        TranslationBankRecord(
            sourceText = "师兄，小心！",
            translatedText = "Sư huynh, cẩn thận!",
            category = BankCategory.DIALOGUE,
            commonOcrErrors = "师兄,小心I|帅兄，小心！|师兄，小心!",
            usageFrequency = 15
        ),
        TranslationBankRecord(
            sourceText = "快走，来不及了！",
            translatedText = "Mau đi thôi, không kịp nữa rồi!",
            category = BankCategory.DIALOGUE,
            commonOcrErrors = "快走，来不乃了！|快走,来不及了I",
            usageFrequency = 12
        ),
        TranslationBankRecord(
            sourceText = "这不可能！",
            translatedText = "Chuyện này không thể nào!",
            category = BankCategory.DIALOGUE,
            commonOcrErrors = "這不可能！|这不叮能！|这不可能I",
            usageFrequency = 14
        ),
        TranslationBankRecord(
            sourceText = "等一下，我马上来",
            translatedText = "Đợi một chút, tôi đến ngay",
            category = BankCategory.DIALOGUE,
            commonOcrErrors = "等ー下，我马上来|等一下,我马上来",
            usageFrequency = 9
        ),
        TranslationBankRecord(
            sourceText = "师尊",
            translatedText = "Sư tôn",
            category = BankCategory.PRONOUN,
            commonOcrErrors = "帅尊|师樽",
            usageFrequency = 20
        ),
        TranslationBankRecord(
            sourceText = "小师妹",
            translatedText = "Tiểu sư muội",
            category = BankCategory.PRONOUN,
            commonOcrErrors = "小帅妹",
            usageFrequency = 11
        ),
        TranslationBankRecord(
            sourceText = "陛下",
            translatedText = "Bệ hạ",
            category = BankCategory.PRONOUN,
            commonOcrErrors = "陛卞",
            usageFrequency = 10
        ),
        TranslationBankRecord(
            sourceText = "道友请留步",
            translatedText = "Đạo hữu xin dừng bước",
            category = BankCategory.DIALOGUE,
            commonOcrErrors = "道友請留步|道友请留歩",
            usageFrequency = 8
        ),
        TranslationBankRecord(
            sourceText = "Level Up",
            translatedText = "Tăng cấp",
            category = BankCategory.UI,
            commonOcrErrors = "LeveI Up|LEVEI UP|Level up!",
            usageFrequency = 18
        ),
        TranslationBankRecord(
            sourceText = "Inventory",
            translatedText = "Túi đồ",
            category = BankCategory.UI,
            commonOcrErrors = "INVENT0RY|lnventory",
            usageFrequency = 16
        ),
        TranslationBankRecord(
            sourceText = "Settings",
            translatedText = "Cài đặt",
            category = BankCategory.UI,
            commonOcrErrors = "Sett1ngs|[Settings]",
            usageFrequency = 13
        ),
        TranslationBankRecord(
            sourceText = "任务完成",
            translatedText = "Hoàn thành nhiệm vụ",
            category = BankCategory.UI,
            commonOcrErrors = "任務完成|任劳完成",
            usageFrequency = 14
        )
    )

    private val pronounMarkers = setOf(
        "师兄", "师姐", "师父", "师尊", "师妹", "师弟", "小师妹", "大师兄",
        "陛下", "殿下", "前辈", "道友", "宗主", "长老", "公子", "小姐",
        "夫人", "少爷", "姑娘", "大人", "老祖", "仙子", "阁下", "朕", "寡人", "本座", "本王"
    )

    private val uiKeywords = setOf(
        "start", "settings", "options", "inventory", "equipment", "level up", "level",
        "skill", "quest", "confirm", "cancel", "reward", "claim", "shop", "map", "status",
        "loading", "开始", "设置", "背包", "装备", "升级", "技能", "任务", "确认", "取消", "奖励", "商店"
    )

    @Synchronized
    fun ensureInitialized(context: Context? = null) {
        if (!initialized) {
            confusionSubstringRules.clear()
            for ((wrong, right) in defaultConfusionPairs) {
                confusionSubstringRules[wrong] = right
            }
            for (seed in seedRecords) {
                putInRam(seed)
            }
            initialized = true
        }
        if (context != null && dbHelper == null) {
            try {
                val helper = BankSqliteHelper(context)
                dbHelper = helper
                val db = helper.writableDatabase
                loadFromSqlite(db)
                if (querySqliteCount(db) == 0) {
                    for (seed in seedRecords) {
                        upsertSqlite(db, seed)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    private fun loadFromSqlite(db: SQLiteDatabase) {
        try {
            db.rawQuery(
                "SELECT source_text, translated_text, category, common_ocr_errors, usage_frequency, updated_at FROM $TABLE_BANK ORDER BY usage_frequency DESC LIMIT $MAX_RAM_RECORDS",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val record = TranslationBankRecord(
                        sourceText = cursor.getString(0),
                        translatedText = cursor.getString(1),
                        category = cursor.getString(2),
                        commonOcrErrors = cursor.getString(3) ?: "",
                        usageFrequency = cursor.getInt(4),
                        updatedAt = cursor.getLong(5)
                    )
                    putInRam(record)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun querySqliteCount(db: SQLiteDatabase): Int {
        return try {
            db.rawQuery("SELECT COUNT(*) FROM $TABLE_BANK", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun upsertSqlite(db: SQLiteDatabase, record: TranslationBankRecord) {
        try {
            val values = ContentValues().apply {
                put("source_text", record.sourceText)
                put("translated_text", record.translatedText)
                put("category", record.category)
                put("common_ocr_errors", record.commonOcrErrors)
                put("usage_frequency", record.usageFrequency)
                put("updated_at", record.updatedAt)
            }
            db.insertWithOnConflict(TABLE_BANK, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun putInRam(record: TranslationBankRecord) {
        val key = normalizeKey(record.sourceText)
        if (key.isEmpty()) return
        ramRecords[key] = record
        indexOcrErrors(key, record.commonOcrErrors)
    }

    @Synchronized
    private fun indexOcrErrors(canonicalKey: String, rawErrors: String) {
        if (rawErrors.isBlank()) return
        val tokens = rawErrors.split(Regex("[|,;\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
        for (token in tokens) {
            val arrowIdx = token.indexOf("->").takeIf { it > 0 } ?: token.indexOf("=>").takeIf { it > 0 }
            if (arrowIdx != null) {
                val wrong = token.substring(0, arrowIdx).trim()
                val right = token.substring(arrowIdx + 2).trim()
                if (wrong.isNotEmpty() && right.isNotEmpty()) {
                    confusionSubstringRules[wrong] = right
                }
            } else {
                val variantKey = normalizeKey(token)
                if (variantKey.isNotEmpty()) {
                    variantToCanonical[variantKey] = canonicalKey
                }
            }
        }
    }

    fun normalizeKey(text: String): String =
        text.trim()
            .trim { it == '|' || it == '/' || it == '\\' || it == '_' || it == '`' }
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Pre-processes and auto-corrects common OCR mistakes before lookup or translation.
     * Uses both substring confusion rules (`己经` -> `已经`) and exact variant mappings from the bank.
     */
    @Synchronized
    fun preprocessAndCorrectOcr(rawText: String, context: Context? = null): String {
        ensureInitialized(context)
        var text = rawText.trim()
        if (text.isEmpty()) return ""

        // Strip leading/trailing isolated OCR border artifacts (| / \ _) while keeping valid brackets
        text = text.trim { it == '|' || it == '/' || it == '\\' || it == '_' || it == '`' }.trim()
        if (text.isEmpty()) return ""

        // Apply known OCR confusion substring rules
        var corrected = text
        for ((wrong, right) in confusionSubstringRules) {
            if (corrected.contains(wrong)) {
                corrected = corrected.replace(wrong, right)
            }
        }

        // Check if the entire string matches a known OCR error variant in `translation_bank`
        val key = normalizeKey(corrected)
        val canonicalKey = variantToCanonical[key]
        if (canonicalKey != null) {
            ramRecords[canonicalKey]?.let { canonicalRecord ->
                corrected = canonicalRecord.sourceText
            }
        }

        if (corrected != rawText.trim()) {
            SessionState.ocrCorrectedCount.value++
        }
        return corrected
    }

    /**
     * Automatically classifies a phrase into UI, PRONOUN (Danh xưng), or DIALOGUE (Câu thoại thường).
     */
    fun classifyCategory(sourceText: String, translatedText: String = ""): String {
        val cleaned = normalizeKey(sourceText)
        val lower = cleaned.lowercase()
        if (CostOptimizationEngine.matchOfflineUiTerm(cleaned) != null ||
            uiKeywords.any { lower == it || lower.startsWith("$it ") || cleaned.contains(it, ignoreCase = true) && cleaned.length <= 12 }
        ) {
            return BankCategory.UI
        }
        val simplified = ChineseDialogueBank.normalizeTraditional(cleaned)
        if (cleaned.length <= 8 && pronounMarkers.any { simplified.contains(it) }) {
            return BankCategory.PRONOUN
        }
        val lowerVi = translatedText.lowercase()
        if (cleaned.length <= 8 && listOf("sư huynh", "sư tỷ", "sư phụ", "sư tôn", "tiểu sư muội", "bệ hạ", "điện hạ", "tiền bối", "đạo hữu", "công tử", "tiểu thư").any { lowerVi == it }) {
            return BankCategory.PRONOUN
        }
        return BankCategory.DIALOGUE
    }

    /**
     * Generates default OCR error variants for newly learned sentences (e.g., Traditional/Simplified
     * variants or trailing exclamation confusion `!`/`I`).
     */
    fun inferDefaultOcrErrors(sourceText: String, rawOcrBeforeCorrection: String = ""): String {
        val variants = linkedSetOf<String>()
        val cleaned = normalizeKey(sourceText)
        if (rawOcrBeforeCorrection.isNotBlank() && normalizeKey(rawOcrBeforeCorrection) != cleaned) {
            variants.add(normalizeKey(rawOcrBeforeCorrection))
        }
        val simplified = ChineseDialogueBank.normalizeTraditional(cleaned)
        if (simplified != cleaned) {
            variants.add(simplified)
        }
        if (cleaned.endsWith("！") || cleaned.endsWith("!")) {
            variants.add(cleaned.dropLast(1) + "I")
        }
        return variants.joinToString("|")
    }

    /**
     * Looks up a phrase in the Self-Learning SQLite Bank (< 1ms via RAM mirror).
     * Automatically increments `usage_frequency` on hit.
     */
    @Synchronized
    fun lookup(
        context: Context?,
        rawSourceText: String,
        incrementFrequency: Boolean = true
    ): TranslationBankRecord? {
        ensureInitialized(context)
        val corrected = preprocessAndCorrectOcr(rawSourceText, context)
        val key = normalizeKey(corrected)
        if (key.isEmpty()) return null

        val simplifiedKey = normalizeKey(ChineseDialogueBank.normalizeTraditional(corrected))
        val matchedKey = when {
            ramRecords.containsKey(key) -> key
            ramRecords.containsKey(simplifiedKey) -> simplifiedKey
            variantToCanonical.containsKey(key) -> variantToCanonical[key]
            variantToCanonical.containsKey(simplifiedKey) -> variantToCanonical[simplifiedKey]
            else -> null
        } ?: return null

        val existing = ramRecords[matchedKey] ?: return null
        if (!incrementFrequency) return existing

        val updated = existing.copy(
            usageFrequency = existing.usageFrequency + 1,
            updatedAt = System.currentTimeMillis()
        )
        ramRecords[matchedKey] = updated
        dbHelper?.let { helper ->
            try {
                upsertSqlite(helper.writableDatabase, updated)
            } catch (_: Exception) {
            }
        }
        SessionState.bankSize.value = ramRecords.size
        return updated
    }

    /**
     * Saves or updates a translation learned from Gemini Flash-Lite or added/edited by the user.
     */
    @Synchronized
    fun learnOrUpsert(
        context: Context?,
        sourceText: String,
        translatedText: String,
        category: String? = null,
        commonOcrErrors: String? = null,
        rawOcrBeforeCorrection: String = ""
    ): TranslationBankRecord {
        ensureInitialized(context)
        val cleanSource = normalizeKey(sourceText)
        val cleanTarget = translatedText.trim()
        val existing = ramRecords[cleanSource]

        val resolvedCategory = category?.takeIf { it.isNotBlank() }
            ?: existing?.category
            ?: classifyCategory(cleanSource, cleanTarget)

        val inferredErrors = commonOcrErrors
            ?: existing?.commonOcrErrors?.takeIf { it.isNotBlank() }
            ?: inferDefaultOcrErrors(cleanSource, rawOcrBeforeCorrection)

        val record = TranslationBankRecord(
            sourceText = cleanSource,
            translatedText = cleanTarget,
            category = resolvedCategory,
            commonOcrErrors = inferredErrors,
            usageFrequency = (existing?.usageFrequency ?: 0) + 1,
            updatedAt = System.currentTimeMillis()
        )
        putInRam(record)
        dbHelper?.let { helper ->
            try {
                upsertSqlite(helper.writableDatabase, record)
            } catch (_: Exception) {
            }
        }
        SessionState.bankSize.value = ramRecords.size
        return record
    }

    @Synchronized
    fun delete(context: Context?, sourceText: String) {
        ensureInitialized(context)
        val key = normalizeKey(sourceText)
        val removed = ramRecords.remove(key)
        if (removed != null) {
            variantToCanonical.entries.removeIf { it.value == key }
        }
        dbHelper?.let { helper ->
            try {
                helper.writableDatabase.delete(TABLE_BANK, "source_text = ?", arrayOf(key))
            } catch (_: Exception) {
            }
        }
        SessionState.bankSize.value = ramRecords.size
    }

    @Synchronized
    fun getAllRecords(
        context: Context? = null,
        categoryFilter: String = "ALL",
        searchQuery: String = ""
    ): List<TranslationBankRecord> {
        ensureInitialized(context)
        val query = searchQuery.trim().lowercase()
        return ramRecords.values
            .filter { record ->
                (categoryFilter == "ALL" || record.category.equals(categoryFilter, ignoreCase = true)) &&
                    (query.isEmpty() ||
                        record.sourceText.lowercase().contains(query) ||
                        record.translatedText.lowercase().contains(query) ||
                        record.commonOcrErrors.lowercase().contains(query))
            }
            .sortedWith(compareByDescending<TranslationBankRecord> { it.usageFrequency }.thenByDescending { it.updatedAt })
    }

    @Synchronized
    fun getSnapshotMap(context: Context? = null): Map<String, String> {
        ensureInitialized(context)
        val map = LinkedHashMap<String, String>(ramRecords.size)
        for ((k, v) in ramRecords) {
            map[k] = v.translatedText
        }
        return map
    }

    @Synchronized
    fun size(context: Context? = null): Int {
        ensureInitialized(context)
        SessionState.bankSize.value = ramRecords.size
        return ramRecords.size
    }

    @Synchronized
    fun resetForTest() {
        ramRecords.clear()
        variantToCanonical.clear()
        confusionSubstringRules.clear()
        initialized = false
        ensureInitialized(null)
    }
}
