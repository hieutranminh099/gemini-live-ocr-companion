package com.gemini.ocr.network

import org.junit.Assert.*
import org.junit.Test

class GeminiApiExceptionTest {
    private val body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}"""

    @Test fun headerWinsOverBody() = assertEquals(12_000L, GeminiApiException.parseRetryAfterMs("12", body))

    @Test fun bodyRetryInfoUsedWhenNoHeader() = assertEquals(37_000L, GeminiApiException.parseRetryAfterMs(null, body))

    @Test fun fractionalRetryDelayIsParsed() =
        assertEquals(1_500L, GeminiApiException.parseRetryAfterMs(null, """{"retryDelay":"1.5s"}"""))

    @Test fun httpDateHeaderIsIgnoredAndFallsBackToBody() =
        assertEquals(37_000L, GeminiApiException.parseRetryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT", body))

    @Test fun missingZeroOrMalformedValuesGiveNull() {
        assertNull(GeminiApiException.parseRetryAfterMs(null, "{}"))
        assertNull(GeminiApiException.parseRetryAfterMs("0", """{"retryDelay":"0s"}"""))
        assertNull(GeminiApiException.parseRetryAfterMs("-5", """{"retryDelay":"abc"}"""))
    }

    @Test fun isStillAnIoException() = assertTrue(GeminiApiException("rate_limited", "x") is java.io.IOException)
}
