package com.gemini.ocr

import kotlinx.coroutines.flow.MutableStateFlow

/** Server-advertised or standalone on-device mode; null availability keeps older companions explicitly unknown. */
data class TranslationBackendState(
    val provider: String = "",
    val mode: String = "",
    val translationAvailable: Boolean? = null,
    val experimentalCliEnabled: Boolean = false,
) {
    val badge: String get() = when {
        mode == "bank_only" -> "Chế độ 1: Dịch thuần từ Kho (Kho thoại SQLite + ML Kit Offline)"
        mode == "hybrid_gemini" -> "Chế độ 2: Kho kết hợp Gemini Flash-Lite (Hybrid thông minh)"
        mode == "standalone_hybrid" -> "Độc lập trên điện thoại • ML Kit (<150ms) + Gemini Flash-Lite"
        mode == "standalone_mlkit" -> "Độc lập ngoại tuyến • ML Kit On-Device (<150ms)"
        mode == "standalone_gemini" -> "Độc lập trên điện thoại • Gemini Flash-Lite trực tiếp"
        mode == "cache_only" -> "Chỉ dùng cache / từ điển • Chưa bật dịch câu mới"
        mode == "cli_experimental" && experimentalCliEnabled -> when (translationAvailable) {
            true -> "CLI cá nhân thử nghiệm • Đã bật dịch câu mới"
            false -> "CLI cá nhân thử nghiệm • Bộ dịch chưa sẵn sàng"
            null -> "CLI cá nhân thử nghiệm • Chưa rõ trạng thái bộ dịch"
        }
        mode == "api" -> when (translationAvailable) {
            true -> "Gemini API • Khóa trên Windows"
            false -> "Gemini API • Bộ dịch chưa sẵn sàng"
            null -> "Gemini API • Chưa rõ trạng thái bộ dịch"
        }
        provider == "cli" -> "CLI trên Windows • Chưa rõ chế độ và khả năng dịch"
        provider == "api" -> "Gemini API • Chưa rõ chế độ và khả năng dịch"
        else -> "Windows đã kết nối • Chưa rõ bộ máy dịch"
    }
}

object SessionState {
    val running = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    val status = MutableStateFlow("Sẵn sàng dịch độc lập trên điện thoại")
    val connected = MutableStateFlow(false)
    val standaloneActive = MutableStateFlow(true)
    val offlineModelStatus = MutableStateFlow("Gói ngoại tuyến tự động tải khi cần")
    val provider = MutableStateFlow("standalone")
    val backend = MutableStateFlow(TranslationBackendState())
    val latency = MutableStateFlow(0L)
    val instantMs = MutableStateFlow(0L)
    val blocks = MutableStateFlow(0)
    val cacheHits = MutableStateFlow(0)
    val preloadedHits = MutableStateFlow(0)
    val fuzzyHits = MutableStateFlow(0)
    val savedApiCalls = MutableStateFlow(0)
    val noiseFiltered = MutableStateFlow(0)
    val ocrCorrectedCount = MutableStateFlow(0)
    val bankSize = MutableStateFlow(0)
    val activeModelName = MutableStateFlow("gemini-3.1-flash-lite-preview")
    val ocrMs = MutableStateFlow(0L)
    val ocrFrames = MutableStateFlow(0)
    val ocrArea = MutableStateFlow(100)
    val activityVisible = MutableStateFlow(false)
    val overlayVisible = MutableStateFlow(false)
    val lastOcr = MutableStateFlow("")
    val lastTranslation = MutableStateFlow("")
    val timings = MutableStateFlow("")
    val networkRtt = MutableStateFlow(0L)
    val frameToDraw = MutableStateFlow(0L)
}
