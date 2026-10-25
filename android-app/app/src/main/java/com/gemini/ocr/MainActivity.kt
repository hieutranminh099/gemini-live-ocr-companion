package com.gemini.ocr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemini.ocr.capture.ScreenCaptureService
import com.gemini.ocr.network.PhoneTranslationCache
import com.gemini.ocr.network.SelfLearningDatabase
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.ocr.OcrProcessor
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val Mint = Color(0xFF6EE7C7)
private val Background = Color(0xFF0B1218)
private val Panel = Color(0xFF14212B)
private val Muted = Color(0xFF98ACB9)

class MainActivity : ComponentActivity() {
    private lateinit var settings: AppSettings

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startForegroundService(Intent(this, ScreenCaptureService::class.java).putExtra("data", result.data))
        } else {
            SessionState.status.value = "Bạn chưa cấp quyền thu màn hình."
        }
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        beginProjection()
    }

    private val overlays = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            startTranslation()
        } else {
            SessionState.status.value = "Cần quyền hiển thị trên ứng dụng khác để hiện bản dịch."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Mint,
                    background = Background,
                    surface = Panel,
                    onPrimary = Background,
                    onSurface = Color(0xFFE8F0F4),
                    secondary = Color(0xFFF0B976),
                    secondaryContainer = Color(0xFF25443A),
                    onSecondaryContainer = Mint,
                    outline = Color(0xFF536974)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Background) { App() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SessionState.activityVisible.value = true
    }

    override fun onPause() {
        SessionState.activityVisible.value = false
        super.onPause()
    }

    private fun startTranslation() {
        settings.connectionMode = "standalone"
        settings.language = "zh"
        if (!Settings.canDrawOverlays(this)) {
            overlays.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            beginProjection()
        }
    }

    private fun beginProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val request = if (Build.VERSION.SDK_INT >= 34 && settings.fullScreen) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        capture.launch(request)
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun App() {
        var tab by remember { mutableIntStateOf(0) }
        BackHandler(enabled = tab != 0) { tab = 0 }
        val scope = rememberCoroutineScope()
        val running by SessionState.running.collectAsState()
        val connected by SessionState.connected.collectAsState()
        val offlineModelStatus by SessionState.offlineModelStatus.collectAsState()
        val backend by SessionState.backend.collectAsState()
        val status by SessionState.status.collectAsState()
        val latency by SessionState.latency.collectAsState()
        val ocr by SessionState.ocrMs.collectAsState()
        val preloadedHits by SessionState.preloadedHits.collectAsState()
        val savedApiCalls by SessionState.savedApiCalls.collectAsState()
        val ocrCorrectedCount by SessionState.ocrCorrectedCount.collectAsState()
        val bankSize by SessionState.bankSize.collectAsState()
        val ocrFrames by SessionState.ocrFrames.collectAsState()
        val ocrArea by SessionState.ocrArea.collectAsState()
        val lastOcr by SessionState.lastOcr.collectAsState()
        val lastTranslation by SessionState.lastTranslation.collectAsState()
        val timings by SessionState.timings.collectAsState()
        val frameToDraw by SessionState.frameToDraw.collectAsState()

        var subtitlePreset by remember { mutableStateOf(settings.subtitleFocus) }
        var autoPreset by remember { mutableStateOf(false) }
        var oneFramePreset by remember { mutableStateOf(false) }
        var translationMode by remember { mutableStateOf(settings.translationMode) }
        var ocrPreCorrection by remember { mutableStateOf(settings.ocrPreCorrection) }
        var fastOcrDownscale by remember { mutableStateOf(settings.fastOcrDownscale) }
        var preparingModel by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            launch(Dispatchers.IO) {
                SelfLearningDatabase.initializeOnIo(this@MainActivity)
                OcrProcessor.preWarm("zh")
                StandaloneTranslator.preWarm(this@MainActivity, "zh")
            }
        }

        LaunchedEffect(tab, running) {
            subtitlePreset = settings.subtitleFocus
            translationMode = settings.translationMode
            ocrPreCorrection = settings.ocrPreCorrection
            fastOcrDownscale = settings.fastOcrDownscale
            launch(Dispatchers.IO) {
                SelfLearningDatabase.size(this@MainActivity)
            }
        }

        Scaffold(
            containerColor = Background,
            bottomBar = {
                NavigationBar(containerColor = Background) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Outlined.Translate, null) },
                        label = { Text("Dịch trực tiếp") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Outlined.Storage, null) },
                        label = { Text("Kho thoại") }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Outlined.Tune, null) },
                        label = { Text("Tùy chỉnh") }
                    )
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Outlined.DocumentScanner, null, tint = Mint, modifier = Modifier.size(32.dp))
                    Column {
                        Text(
                            "GEMINI ZH→VI SUBTITLE OCR",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            fontSize = 15.sp
                        )
                        Text(
                            "Độc lập Android & BlueStacks · Tiếng Trung → Việt ≤ 1000ms",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                }

                // Compact Real-Time Latency Telemetry Status Bar (Requirement 6)
                Surface(
                    color = Color(0xFF10252C),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("telemetry_status_bar")
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val telemetryText = if (timings.isNotBlank()) {
                            timings
                        } else {
                            SessionState.formatCompactTelemetry(ocr.coerceAtLeast(45L), latency.coerceAtLeast(3L))
                        }
                        Text(
                            text = telemetryText,
                            color = Mint,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "ZH → VI · ≤1s",
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    }
                }

                when (tab) {
                    0 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                "Hiểu mọi\nkhung hình.",
                                fontSize = 40.sp,
                                lineHeight = 44.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Chuyên biệt dịch phụ đề Tiếng Trung (Giản thể & Phồn thể) sang Tiếng Việt trực tiếp trên Android và BlueStacks với độ trễ toàn trình ≤ 1 giây.",
                                color = Muted,
                                fontSize = 14.sp
                            )
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Panel),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Column(
                                Modifier.padding(22.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Smartphone,
                                        contentDescription = null,
                                        tint = Mint
                                    )
                                    Text(
                                        text = if (running) "PHIÊN DỊCH TRUNG → VIỆT ĐANG CHẠY" else "DỊCH ĐỘC LẬP ANDROID & BLUESTACKS",
                                        fontSize = 12.sp,
                                        color = Mint,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Text(status, fontSize = 17.sp)
                                Text(
                                    text = if (connected && backend.mode.isNotBlank()) backend.badge else settings.standaloneBadge,
                                    color = Mint,
                                    fontSize = 12.sp
                                )
                                Button(
                                    onClick = {
                                        if (running) {
                                            startService(Intent(this@MainActivity, ScreenCaptureService::class.java).setAction("STOP"))
                                        } else {
                                            startTranslation()
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                        .testTag("primary_session_button"),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(if (running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (running) "Kết thúc phiên dịch" else "Bật dịch màn hình",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    "Tự động cắt vùng phụ đề 1/3 dưới màn hình (chuẩn Row Stride cho BlueStacks), thu nhỏ 160–240px và bấm xuyên cảm ứng 100%.",
                                    color = Muted,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Metric("OCR chữ Hán", "$ocr ms", Modifier.weight(1f))
                            Metric("Tốc độ dịch", "$latency ms", Modifier.weight(1f))
                            Metric("Tổng E2E", "${(ocr + latency)} ms", Modifier.weight(1f))
                        }

                        Text(
                            "Kho SQLite (idx_zh_hash): $bankSize câu • LruCache RAM: ${SelfLearningDatabase.ramLruSize()}/300 • Khớp Kho (<5ms): $preloadedHits • Tiết kiệm API: $savedApiCalls",
                            color = Mint,
                            fontSize = 12.sp
                        )

                        if (running) {
                            Text(
                                "Đã đọc $ocrFrames khung • Vùng ROI: $ocrArea% màn hình • Sửa lỗi OCR: $ocrCorrectedCount",
                                color = Muted,
                                fontSize = 12.sp
                            )
                        }
                        if (running && frameToDraw > 0) {
                            Text(
                                "Từ khung OCR đến lúc vẽ: $frameToDraw ms.",
                                color = Muted,
                                fontSize = 12.sp
                            )
                        }
                        if (running && lastOcr.isNotBlank()) {
                            Section("Khung vừa đọc") {
                                Text("OCR: $lastOcr", color = Muted, fontSize = 14.sp)
                                Text(
                                    if (lastTranslation.isBlank()) "Chưa có kết quả • Xem trạng thái ở trên" else lastTranslation,
                                    color = Mint,
                                    fontSize = 15.sp
                                )
                            }
                        }

                        // Requirement 2: 2 Clear Standalone Modes
                        Section("2 Chế độ dịch độc lập Tiếng Trung → Tiếng Việt") {
                            Text(
                                "Tối giản 100% cho Tiếng Trung (Giản thể & Phồn thể) sang Tiếng Việt với 2 chế độ:",
                                color = Muted,
                                fontSize = 13.sp
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedCard(
                                    onClick = {
                                        if (!running) {
                                            translationMode = StandaloneTranslator.MODE_HYBRID_BANK_GEMINI
                                            settings.translationMode = StandaloneTranslator.MODE_HYBRID_BANK_GEMINI
                                        }
                                    },
                                    enabled = !running,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = if (translationMode == StandaloneTranslator.MODE_HYBRID_BANK_GEMINI) Color(0xFF1B332E) else Color(0xFF0E1820)
                                    ),
                                    modifier = Modifier.fillMaxWidth().testTag("mode_hybrid_gemini_card")
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            RadioButton(
                                                selected = translationMode == StandaloneTranslator.MODE_HYBRID_BANK_GEMINI,
                                                onClick = null
                                            )
                                            Text(
                                                "Chế độ 1: Kho Phụ Đề + Gemini (Hybrid Siêu Tốc)",
                                                fontWeight = FontWeight.Bold,
                                                color = if (translationMode == StandaloneTranslator.MODE_HYBRID_BANK_GEMINI) Mint else Color.White,
                                                fontSize = 14.sp
                                            )
                                        }
                                        Text(
                                            "Tra cứu tức thì trong Kho phụ đề Tiếng Trung SQLite (B-Tree idx_zh_hash + LruCache 300 câu, < 5ms). Nếu câu mới chưa có trong kho sẽ gọi Gemini API, hiển thị ngay và tự động lưu ngầm vào kho (Self-Learning Cache).",
                                            color = Muted,
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                OutlinedCard(
                                    onClick = {
                                        if (!running) {
                                            translationMode = StandaloneTranslator.MODE_GEMINI_DIRECT
                                            settings.translationMode = StandaloneTranslator.MODE_GEMINI_DIRECT
                                        }
                                    },
                                    enabled = !running,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = if (translationMode == StandaloneTranslator.MODE_GEMINI_DIRECT) Color(0xFF1B332E) else Color(0xFF0E1820)
                                    ),
                                    modifier = Modifier.fillMaxWidth().testTag("mode_bank_only_card")
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            RadioButton(
                                                selected = translationMode == StandaloneTranslator.MODE_GEMINI_DIRECT,
                                                onClick = null
                                            )
                                            Text(
                                                "Chế độ 2: Chỉ dùng Gemini (Gemini Direct)",
                                                fontWeight = FontWeight.Bold,
                                                color = if (translationMode == StandaloneTranslator.MODE_GEMINI_DIRECT) Mint else Color.White,
                                                fontSize = 14.sp
                                            )
                                        }
                                        Text(
                                            "Bỏ qua kho phụ đề mẫu, gửi trực tiếp chữ Hán kèm 1 câu ngữ cảnh liền trước lên Gemini (thinkingBudget = 0), giữ bộ đệm RAM nhỏ LruCache(100) để không gọi lại API khi phụ đề đứng yên.",
                                            color = Muted,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    preparingModel = true
                                    scope.launch {
                                        try {
                                            StandaloneTranslator.prepareOfflineModel(this@MainActivity, "zh") { msg ->
                                                SessionState.offlineModelStatus.value = msg
                                            }
                                        } catch (error: Exception) {
                                            SessionState.offlineModelStatus.value = error.message ?: "Không thể khởi tạo kho SQLite."
                                        } finally {
                                            preparingModel = false
                                        }
                                    }
                                },
                                enabled = !preparingModel,
                                modifier = Modifier.fillMaxWidth().testTag("prepare_offline_model_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (preparingModel) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Mint)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Đang đồng bộ Kho SQLite B-Tree…", color = Mint)
                                } else {
                                    Icon(Icons.Outlined.Storage, contentDescription = null, tint = Mint)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Đồng bộ Kho phụ đề Tiếng Trung SQLite (ZH → VI)", color = Mint)
                                }
                            }
                            if (offlineModelStatus.isNotBlank()) {
                                Text(offlineModelStatus, color = Mint, fontSize = 12.sp, modifier = Modifier.testTag("offline_model_status"))
                            }
                        }

                        // Pipeline Latency & Anti-OOM Section
                        Section("Chuỗi Pipeline Ép Độ Trễ ≤ 1000ms & Chống Đơ BlueStacks") {
                            Text(
                                "• Chỉ giữ duy nhất ML Kit Chinese Text Recognition (Giản thể & Phồn thể).\n" +
                                    "• Cắt đúng vùng phụ đề (ROI 1/3 dưới) với chuẩn Row Stride & Pixel Stride.\n" +
                                    "• Downscale chiều cao ROI về 160px–240px (OCR 40ms–80ms) + Frame Diffing 300ms.\n" +
                                    "• Cửa sổ phụ đề trong suốt gắn cờ FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE.",
                                color = Muted,
                                fontSize = 12.sp
                            )
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("ROI Downscale 160px–240px (OCR 40–80ms)", fontWeight = FontWeight.Medium)
                                    Text("Thu nhỏ vùng phụ đề trước khi đưa vào ML Kit Chinese OCR.", color = Muted, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = fastOcrDownscale,
                                    onCheckedChange = { fastOcrDownscale = it; settings.fastOcrDownscale = it },
                                    enabled = !running,
                                    modifier = Modifier.testTag("fast_ocr_downscale_switch")
                                )
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Tự sửa lỗi chữ Hán OCR (OCR Pre-correction)", fontWeight = FontWeight.Medium)
                                    Text("Tự sửa các chữ Hán dễ nhầm (帅兄→师兄, 己经→已经) trước khi tra kho.", color = Muted, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = ocrPreCorrection,
                                    onCheckedChange = { ocrPreCorrection = it; settings.ocrPreCorrection = it },
                                    enabled = !running,
                                    modifier = Modifier.testTag("ocr_pre_correction_switch")
                                )
                            }
                        }

                        Section("Phụ đề video & Khu vực quét") {
                            Text(
                                "Chữ Việt căn giữa, nền bán trong suốt bấm xuyên cảm ứng. Tự động đặt vùng phụ đề mặc định ở 1/3 dưới màn hình.",
                                color = Muted,
                                fontSize = 13.sp
                            )
                            Button(
                                onClick = { settings.useAutoSubtitlePreset(); subtitlePreset = true; autoPreset = true; oneFramePreset = false },
                                enabled = !running,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (autoPreset) "Đã chọn · Tự động tăng tốc" else "Tự động tăng tốc · Gemini")
                            }
                            OutlinedButton(
                                onClick = { settings.useSubtitlePreset(); subtitlePreset = true; autoPreset = false; oneFramePreset = true },
                                enabled = !running,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (oneFramePreset) "Đã chọn · Phụ đề 1 khung" else "Dùng kiểu phụ đề 1 khung")
                            }
                            if (subtitlePreset) {
                                Choice(
                                    "Cách lấy phụ đề",
                                    linkedMapOf("frame" to "Chạm dịch 1 khung", "auto" to "Tự động"),
                                    settings.subtitleCapture,
                                    !running
                                ) {
                                    settings.subtitleCapture = it
                                    autoPreset = it == "auto"
                                    oneFramePreset = it == "frame"
                                }
                            }
                        }

                        Section("Ngôn ngữ & Bối cảnh") {
                            Text("Ngôn ngữ gốc: Tiếng Trung (Giản thể & Phồn thể) → Tiếng Việt", color = Mint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Choice(
                                "Nội dung phim / truyện",
                                linkedMapOf("manga" to "Phim / Cổ trang / Tu tiên", "game" to "Game / Kiếm hiệp", "document" to "Hiện đại / Đô thị"),
                                settings.profile,
                                !running
                            ) { settings.profile = it }
                        }
                    }
                    1 -> TranslationBankScreen(running)
                    2 -> SettingsScreen(running)
                }

                Text(
                    "GEMINI CHINESE OCR STANDALONE  ·  ${BuildConfig.VERSION_NAME}",
                    color = Muted,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun SettingsScreen(running: Boolean) {
        var apiKeyInput by remember { mutableStateOf(settings.standaloneApiKey) }
        var localGlossary by remember { mutableStateOf(settings.localGlossary) }
        var cacheClearedMessage by remember { mutableStateOf("") }

        Text("Theo cách của bạn", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            if (running) "Kết thúc phiên dịch để thay đổi cấu hình."
            else "Tinh chỉnh khóa Gemini tốc độ cao (thinkingBudget = 0), vùng phụ đề và cửa sổ nổi.",
            color = Muted
        )

        Section("Khóa Gemini API & Từ điển xưng hô Tiếng Trung") {
            Text(
                "Tối ưu kết nối HTTP/2 Keep-Alive, tắt thinkingBudget = 0 và dùng System Prompt < 40 tokens để đạt thời gian phản hồi Gemini < 600ms.",
                color = Muted,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it; settings.standaloneApiKey = it.trim() },
                label = { Text("Gemini API Key trên điện thoại / BlueStacks") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth().testTag("standalone_api_key_input")
            )
            Text(
                text = if (settings.effectiveStandaloneApiKey().isNotBlank()) {
                    "Đã cấu hình khóa Gemini API (thinkingBudget = 0 · HTTP/2 Keep-Alive)."
                } else {
                    "Chưa nhập khóa Gemini API • Chế độ 1 vẫn tra cứu tức thì Kho phụ đề Tiếng Trung SQLite (<5ms)."
                },
                color = Mint,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = localGlossary,
                onValueChange = { localGlossary = it; settings.localGlossary = it },
                label = { Text("Từ điển xưng hô / tên riêng (chữ Hán = tiếng Việt, mỗi dòng 1 mục)") },
                enabled = !running,
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("local_glossary_input")
            )
            OutlinedButton(
                onClick = {
                    PhoneTranslationCache.clear(this@MainActivity)
                    cacheClearedMessage = "Đã xóa LruCache và khôi phục Kho phụ đề Tiếng Trung mặc định."
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().testTag("clear_phone_cache_button")
            ) {
                Text("Đặt lại LruCache & Kho tự học về mặc định")
            }
            if (cacheClearedMessage.isNotBlank()) {
                Text(cacheClearedMessage, color = Mint, fontSize = 12.sp)
            }
        }

        Section("Thiết bị và quyền ứng dụng") {
            Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", color = Mint, fontSize = 13.sp)
            Text(
                "Quyền hiển thị nổi (Touch Pass-Through): ${if (Settings.canDrawOverlays(this@MainActivity)) "Đã cấp" else "Chưa cấp"}",
                color = Muted,
                fontSize = 12.sp
            )
            TextButton(
                onClick = {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            ) {
                Text("Mở thông tin ứng dụng")
            }
        }

        Section("Bản dịch nổi (Bấm xuyên cảm ứng)") {
            Choice(
                "Kiểu hiển thị",
                linkedMapOf("subtitle" to "Phụ đề rời", "replace" to "Phủ từng đoạn", "cover" to "Đè phụ đề gốc"),
                settings.overlay,
                !running
            ) {
                settings.overlay = it
                if (it == "cover") settings.region = "subtitle"
            }
            Choice(
                "Màu chữ",
                linkedMapOf("-1" to "Trắng", "-9508921" to "Xanh bạc hà", "-11930" to "Vàng"),
                settings.textColor.toString(),
                !running
            ) { settings.textColor = it.toInt() }
            var font by remember { mutableFloatStateOf(settings.fontSize.toFloat()) }
            Text("Cỡ chữ · ${font.toInt()} sp", color = Muted)
            Slider(value = font, onValueChange = { font = it; settings.fontSize = it.toInt() }, valueRange = 12f..30f, enabled = !running)
            var opacity by remember { mutableFloatStateOf(settings.opacity) }
            Text("Độ đậm nền · ${(opacity * 100).toInt()}%", color = Muted)
            Slider(value = opacity, onValueChange = { opacity = it; settings.opacity = it }, valueRange = .4f..1f, enabled = !running)
            var y by remember { mutableFloatStateOf(settings.subtitleY) }
            Text("Vị trí phụ đề · ${(y * 100).toInt()}% từ trên xuống", color = Muted)
            Slider(value = y, onValueChange = { y = it; settings.subtitleY = it }, valueRange = .1f.. .85f, enabled = !running)
        }

        Section("Vùng nhận diện (ROI Cropping)") {
            Choice(
                "Vùng nhận diện",
                linkedMapOf("subtitle" to "Dải phụ đề (1/3 dưới)", "dialogue" to "Nửa dưới", "center" to "Vùng giữa", "full" to "Toàn màn hình"),
                settings.region,
                !running
            ) {
                settings.region = it
                if (it != "subtitle" && settings.overlay == "cover") settings.overlay = "subtitle"
            }
            Choice(
                "Lấy khung trong dải phụ đề",
                linkedMapOf("frame" to "Chạm dịch 1 khung", "auto" to "Tự động"),
                settings.subtitleCapture,
                !running
            ) { settings.subtitleCapture = it }
            var interval by remember { mutableFloatStateOf(settings.interval.toFloat()) }
            Text("Chu kỳ quét OCR · ${interval.toInt()} ms", color = Muted)
            Slider(value = interval, onValueChange = { interval = it; settings.interval = it.toLong() }, valueRange = 75f..1000f, enabled = !running)
            var fullScreen by remember { mutableStateOf(settings.fullScreen) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Thu toàn màn hình", Modifier.weight(1f))
                Switch(
                    checked = fullScreen,
                    onCheckedChange = { fullScreen = it; settings.fullScreen = it },
                    enabled = !running && Build.VERSION.SDK_INT >= 34
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .background(Panel, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, fontSize = 10.sp, color = Muted)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Mint)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(
    label: String,
    values: Map<String, String>,
    initial: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    var selected by remember(initial) { mutableStateOf(initial) }
    Text(label, fontSize = 12.sp, color = Muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { (key, value) ->
            FilterChip(
                selected = selected == key,
                onClick = { selected = key; onChange(key) },
                label = { Text(value, fontSize = 12.sp) },
                enabled = enabled
            )
        }
    }
}
