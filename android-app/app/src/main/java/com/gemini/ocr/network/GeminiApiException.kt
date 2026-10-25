package com.gemini.ocr.network

import java.io.IOException

/**
 * Gemini HTTP failure that keeps a machine-readable code, so callers can apply the same
 * cooldown rules as the Windows path instead of re-sending the same sentence on every frame.
 * Extends IOException: existing `catch (e: Exception)` handlers keep working unchanged.
 */
class GeminiApiException(
    val code: String,
    message: String,
    val retryAfterMs: Long? = null
) : IOException(message) {
    companion object {
        private val RETRY_DELAY = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"")

        /** Prefer the HTTP Retry-After header (integer seconds); fall back to the RetryInfo body. */
        fun parseRetryAfterMs(retryAfterHeader: String?, body: String): Long? {
            retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { return it * 1000 }
            val seconds = RETRY_DELAY.find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            return seconds?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
        }
    }
}
