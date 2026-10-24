package com.gemini.ocr.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gemini.ocr.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var host: String
        get() = prefs.getString("host", "")!!
        set(value) { prefs.edit().putString("host", value).apply() }
    var pin: String
        get() = prefs.getString("pin", "")!!
        set(value) { prefs.edit().putString("pin", value).apply() }
    var language: String
        get() = prefs.getString("language", "ja")!!
        set(value) { prefs.edit().putString("language", value).apply() }
    var mode: String
        get() = prefs.getString("mode", "smart")!!
        set(value) { prefs.edit().putString("mode", value).apply() }
    var profile: String
        get() = prefs.getString("profile", "game")!!
        set(value) { prefs.edit().putString("profile", value).apply() }
    var region: String
        get() = prefs.getString("region", "full")!!
        set(value) { prefs.edit().putString("region", value).apply() }
    val subtitleFocus: Boolean get() = region == "subtitle"
    var trackSourceRow: Boolean
        get() = prefs.getBoolean("trackSourceRow", false)
        set(value) { prefs.edit().putBoolean("trackSourceRow", value).apply() }
    // A full-display projection also captures our windows. Keep automatic subtitles
    // outside the OCR strip so they can remain visible while new source text is read.
    val adjacentSubtitles: Boolean get() = subtitleFocus && subtitleCapture == "auto" &&
        (android.os.Build.VERSION.SDK_INT < 34 || fullScreen)
    var subtitleCapture: String
        get() = prefs.getString("subtitleCapture", "frame")!!
        set(value) { prefs.edit().putString("subtitleCapture", value).apply() }
    var subtitleRegion: android.graphics.RectF
        get() = android.graphics.RectF(prefs.getFloat("roiLeft", .08f), prefs.getFloat("roiTop", .78f),
            prefs.getFloat("roiRight", .92f), prefs.getFloat("roiBottom", .96f))
        set(value) { prefs.edit().putFloat("roiLeft", value.left).putFloat("roiTop", value.top)
            .putFloat("roiRight", value.right).putFloat("roiBottom", value.bottom).apply() }

    /** Standalone on-device mode by default ("standalone"). */
    var connectionMode: String
        get() = prefs.getString("connectionMode", "standalone")!!
        set(value) { prefs.edit().putString("connectionMode", value).apply() }

    /**
     * Simplified 2 Translation Modes:
     * - "bank_only": Chế độ 1 — Dịch thuần từ Kho (Kho thoại SQLite Offline + ML Kit Offline, 0 API calls)
     * - "hybrid_gemini": Chế độ 2 — Kho kết hợp Gemini (Hybrid thông minh với ngưỡng ký tự linh hoạt)
     */
    var translationMode: String
        get() {
            val raw = prefs.getString("translationMode", null)
            if (raw == "bank_only" || raw == "hybrid_gemini") return raw
            return if (prefs.getString("standaloneEngine", "hybrid") == "mlkit") "bank_only" else "hybrid_gemini"
        }
        set(value) {
            val normalized = if (value == "bank_only" || value == "mlkit") "bank_only" else "hybrid_gemini"
            val engine = if (normalized == "bank_only") "mlkit" else "hybrid"
            prefs.edit()
                .putString("translationMode", normalized)
                .putString("standaloneEngine", engine)
                .apply()
        }

    /** Synchronized with [translationMode]: "mlkit" for Mode 1 (Bank Offline) or "hybrid" for Mode 2 (Bank + Gemini Flash-Lite). */
    var standaloneEngine: String
        get() = if (translationMode == "bank_only") "mlkit" else "hybrid"
        set(value) {
            translationMode = if (value == "mlkit" || value == "bank_only") "bank_only" else "hybrid_gemini"
        }

    /** Flexible character length threshold for Mode 2 (Kho kết hợp Gemini), default 15 characters. */
    var hybridCharThreshold: Int
        get() = prefs.getInt("hybridCharThreshold", 15).coerceIn(5, 60)
        set(value) { prefs.edit().putInt("hybridCharThreshold", value.coerceIn(5, 60)).apply() }

    /** Enables OCR Pre-correction using common OCR error rules in the SQLite Self-Learning Bank. */
    var ocrPreCorrection: Boolean
        get() = prefs.getBoolean("ocrPreCorrection", true)
        set(value) { prefs.edit().putBoolean("ocrPreCorrection", value).apply() }

    /** Sub-1s pipeline optimizations: 0ms/25ms settle debounce + immediate on-device draw without extra frame round-trip. */
    var turboLatency: Boolean
        get() = prefs.getBoolean("turboLatency", true)
        set(value) { prefs.edit().putBoolean("turboLatency", value).apply() }

    /** Enables < 1ms instant lookup against the preloaded Chinese movie, drama, manhua & game dialogue corpus. */
    var preloadedDialogueEnabled: Boolean
        get() = prefs.getBoolean("preloadedDialogueEnabled", true)
        set(value) { prefs.edit().putBoolean("preloadedDialogueEnabled", value).apply() }

    /** Downscales oversized subtitle ROI bitmaps before ML Kit OCR to cut OCR latency by 35-50%. */
    var fastOcrDownscale: Boolean
        get() = prefs.getBoolean("fastOcrDownscale", true)
        set(value) { prefs.edit().putBoolean("fastOcrDownscale", value).apply() }

    /** 5-Tier Gemini Cost Optimization: Fuzzy Match 90-95%, Smart Hybrid gating, compact prompt, 100-250 token cap. */
    var costSavingMode: Boolean
        get() = prefs.getBoolean("costSavingMode", true)
        set(value) { prefs.edit().putBoolean("costSavingMode", value).apply() }

    /** Requires 2-frame OCR stabilization before calling cloud Gemini API (while still allowing instant offline preview). */
    var stabilizeBeforeCloud: Boolean
        get() = prefs.getBoolean("stabilizeBeforeCloud", true)
        set(value) { prefs.edit().putBoolean("stabilizeBeforeCloud", value).apply() }

    /** Local personal glossary stored on the phone, formatted as "source = target" per line. */
    var localGlossary: String
        get() = prefs.getString("localGlossary", "")!!
        set(value) { prefs.edit().putString("localGlossary", value).apply() }

    val standaloneBadge: String
        get() = when (translationMode) {
            "bank_only" -> "Chế độ 1: Dịch thuần từ Kho (SQLite <1ms + ML Kit Offline · 0 token)"
            else -> "Chế độ 2: Kho kết hợp Gemini Flash-Lite (Ngưỡng ≤ ${hybridCharThreshold} ký tự)"
        }

    fun useSubtitlePreset() {
        region = "subtitle"; overlay = "cover"; subtitleCapture = "frame"
        mode = "fast"; fontSize = 22; opacity = 1f; textColor = android.graphics.Color.WHITE
        interval = 500
    }
    fun useAutoSubtitlePreset() {
        useSubtitlePreset()
        subtitleCapture = "auto"; interval = 150
    }
    var overlay: String
        get() = prefs.getString("overlay", "subtitle")!!
        set(value) { prefs.edit().putString("overlay", value).apply() }
    var fontSize: Int
        get() = prefs.getInt("font", 18)
        set(value) { prefs.edit().putInt("font", value).apply() }
    var opacity: Float
        get() = prefs.getFloat("opacity", .9f)
        set(value) { prefs.edit().putFloat("opacity", value).apply() }
    var textColor: Int
        get() = prefs.getInt("textColor", android.graphics.Color.WHITE)
        set(value) { prefs.edit().putInt("textColor", value).apply() }
    var interval: Long
        get() = prefs.getLong("interval", 900)
        set(value) { prefs.edit().putLong("interval", value).apply() }
    var subtitleY: Float
        get() = prefs.getFloat("subtitleY", .78f)
        set(value) { prefs.edit().putFloat("subtitleY", value).apply() }
    var fullScreen: Boolean
        get() = prefs.getBoolean("fullScreen", android.os.Build.VERSION.SDK_INT < 34)
        set(value) { prefs.edit().putBoolean("fullScreen", value).apply() }
    var excludedApps: String
        get() = prefs.getString("excluded", "")!!
        set(value) { prefs.edit().putString("excluded", value).apply() }
    var token: String
        get() = decryptPref("token")
        set(value) = encryptPref("token", value)

    var standaloneApiKey: String
        get() = decryptPref("standalone_api_key")
        set(value) = encryptPref("standalone_api_key", value.trim())

    fun effectiveStandaloneApiKey(): String =
        standaloneApiKey.ifBlank { BuildConfig.GEMINI_API_KEY.trim() }

    private fun decryptPref(prefKey: String): String {
        val stored = prefs.getString(prefKey, null) ?: return ""
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            prefs.getString("${prefKey}_plain_fallback", "") ?: ""
        }
    }

    private fun encryptPref(prefKey: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit().remove(prefKey).remove("${prefKey}_plain_fallback").apply()
            return
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(prefKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .remove("${prefKey}_plain_fallback").apply()
        } catch (_: Exception) {
            prefs.edit().putString("${prefKey}_plain_fallback", value).apply()
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("GeminiOCRDevice", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("GeminiOCRDevice", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun savePairing(hostValue: String, pinValue: String, tokenValue: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(tokenValue.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("host", hostValue).putString("pin", pinValue)
            .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "Không lưu được kết nối." }
    }
    fun unpair() { prefs.edit().remove("token").remove("host").remove("pin").apply() }
}
