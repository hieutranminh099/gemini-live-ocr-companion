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
import com.gemini.ocr.network.ChineseDialogueBank
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
        SelfLearningDatabase.ensureInitialized(this)
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
        val instantMs by SessionState.instantMs.collectAsState()
        val ocr by SessionState.ocrMs.collectAsState()
        val preloadedHits by SessionState.preloadedHits.collectAsState()
        val fuzzyHits by SessionState.fuzzyHits.collectAsState()
        val savedApiCalls by SessionState.savedApiCalls.collectAsState()
        val noiseFiltered by SessionState.noiseFiltered.collectAsState()
        val ocrCorrectedCount by SessionState.ocrCorrectedCount.collectAsState()
        val bankSize by SessionState.bankSize.collectAsState()
        val ocrFrames by SessionState.ocrFrames.collectAsState()
        val ocrArea by SessionState.ocrArea.collectAsState()
        val lastOcr by SessionState.lastOcr.collectAsState()
        val lastTranslation by SessionState.lastTranslation.collectAsState()
        val timings by SessionState.timings.collectAsState()
        val frameToDraw by SessionState.frameToDraw.collectAsState()

        var subtitlePreset by remember { mutableStateOf(settings.subtitleFocus) }
        var autoPreset by remember { mutableStateOf(settings.subtitleFocus && settings.subtitleCapture == "auto") }
        var translationMode by remember { mutableStateOf(settings.translationMode) }
        var hybridCharThreshold by remember { mutableFloatStateOf(settings.hybridCharThreshold.toFloat()) }
        var ocrPreCorrection by remember { mutableStateOf(settings.ocrPreCorrection) }
        var fastOcrDownscale by remember { mutableStateOf(settings.fastOcrDownscale) }
        var stabilizeBeforeCloud by remember { mutableStateOf(settings.stabilizeBeforeCloud) }
        var preparingModel by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            launch(Dispatchers.Default) {
                SelfLearningDatabase.ensureInitialized(this@MainActivity)
                OcrProcessor.preWarm(settings.language)
                StandaloneTranslator.preWarm(this@MainActivity, settings.language)
            }
        }

        LaunchedEffect(tab, running) {
            subtitlePreset = settings.subtitleFocus
            autoPreset = settings.subtitleFocus && settings.subtitleCapture == "auto"
            translationMode = settings.translationMode
            hybridCharThreshold = settings.hybridCharThreshold.toFloat()
            ocrPreCorrection = settings.ocrPreCorrection
            fastOcrDownscale = settings.fastOcrDownscale
            stabilizeBeforeCloud = settings.stabilizeBeforeCloud
            SelfLearningDatabase.size(this@MainActivity)
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
                            "GEMINI OCR STANDALONE",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            fontSize = 16.sp
                        )
                        Text(
                            "Độc lập 100% trên Android · Kho thoại SQLite <1ms · Flash-Lite",
                            color = Muted,
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
                                "Hoạt động độc lập hoàn toàn trên điện thoại — không cần máy tính, tự động học vào Kho SQLite và giải phóng bộ nhớ tức thì.",
                                color = Muted,
                                fontSize = 15.sp
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
                                        text = if (running) "PHIÊN DỊCH ĐỘC LẬP ĐANG CHẠY" else "DỊCH MÀN HÌNH ĐỘC LẬP TRÊN ANDROID",
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
                                    if (Build.VERSION.SDK_INT >= 34 && !settings.fullScreen) {
                                        "Chọn “Một ứng dụng” trong hộp thoại Android, rồi chọn game, truyện hoặc video cần dịch."
                                    } else {
                                        "Cho phép thu màn hình để cửa sổ nổi tự chụp vùng chỉ định, chạy ML Kit OCR và hiển thị bản dịch đè lên nội dung gốc."
                                    },
                                    color = Muted,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Metric("Độ trễ dịch", "$latency ms", Modifier.weight(1f))
                            Metric("OCR cục bộ", "$ocr ms", Modifier.weight(1f))
                            Metric("Tiết kiệm API", "$savedApiCalls lượt", Modifier.weight(1f))
                        }

                        Text(
                            "Kho SQLite: $bankSize mục • Khớp Kho (<1ms): $preloadedHits • Sửa lỗi OCR: $ocrCorrectedCount • Lọc rác: $noiseFiltered",
                            color = Mint,
                            fontSize = 12.sp
                        )

                        if (running && instantMs > 0) {
                            Text(
                                "Bản dịch tức thì trên máy: $instantMs ms • Engine Cloud duy nhất: gemini-3.1-flash-lite-preview",
                                color = Mint,
                                fontSize = 12.sp
                            )
                        }
                        if (running) {
                            Text(
                                "Đã đọc $ocrFrames khung • OCR $ocrArea% diện tích màn hình • Tự giải phóng Bitmap sau mỗi khung",
                                color = Muted,
                                fontSize = 12.sp
                            )
                        }
                        if (running && timings.isNotEmpty()) Text(timings, color = Muted, fontSize = 12.sp)
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

                        // 1. Simplified 2 Translation Modes Section
                        Section("2 Chế độ dịch tinh gọn (Standalone On-Device)") {
                            Text(
                                "Đã loại bỏ hoàn toàn các chế độ rườm rà và sự phụ thuộc vào PC. Chỉ giữ lại 2 chế độ tối ưu:",
                                color = Muted,
                                fontSize = 13.sp
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedCard(
                                    onClick = {
                                        if (!running) {
                                            translationMode = "bank_only"
                                            settings.translationMode = "bank_only"
                                        }
                                    },
                                    enabled = !running,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = if (translationMode == "bank_only") Color(0xFF1B332E) else Color(0xFF0E1820)
                                    ),
                                    modifier = Modifier.fillMaxWidth().testTag("mode_bank_only_card")
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            RadioButton(
                                                selected = translationMode == "bank_only",
                                                onClick = null
                                            )
                                            Text(
                                                "Chế độ 1: Dịch thuần từ Kho (Kho thoại Offline)",
                                                fontWeight = FontWeight.Bold,
                                                color = if (translationMode == "bank_only") Mint else Color.White,
                                                fontSize = 14.sp
                                            )
                                        }
                                        Text(
                                            "Chỉ khớp và lấy kết quả từ cơ sở dữ liệu cục bộ (SQLite Kho thoại / ML Kit Offline), không gọi bất kỳ API nào ra bên ngoài (0 token).",
                                            color = Muted,
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                OutlinedCard(
                                    onClick = {
                                        if (!running) {
                                            translationMode = "hybrid_gemini"
                                            settings.translationMode = "hybrid_gemini"
                                        }
                                    },
                                    enabled = !running,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = if (translationMode == "hybrid_gemini") Color(0xFF1B332E) else Color(0xFF0E1820)
                                    ),
                                    modifier = Modifier.fillMaxWidth().testTag("mode_hybrid_gemini_card")
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            RadioButton(
                                                selected = translationMode == "hybrid_gemini",
                                                onClick = null
                                            )
                                            Text(
                                                "Chế độ 2: Kho kết hợp Gemini (Hybrid thông minh)",
                                                fontWeight = FontWeight.Bold,
                                                color = if (translationMode == "hybrid_gemini") Mint else Color.White,
                                                fontSize = 14.sp
                                            )
                                        }
                                        Text(
                                            "Cụm từ ngắn ≤ ${hybridCharThreshold.toInt()} ký tự hoặc khớp từ điển UI/thoại lấy trực tiếp từ Kho (<1ms). Câu vượt ngưỡng hoặc chưa có trong Kho mới gọi Gemini Flash-Lite và tự lưu ngược vào SQLite.",
                                            color = Muted,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }

                            if (translationMode == "hybrid_gemini") {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "Ngưỡng độ dài ký tự (Chế độ 2): ≤ ${hybridCharThreshold.toInt()} ký tự ưu tiên Kho thoại (<1ms)",
                                        color = Mint,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Slider(
                                        value = hybridCharThreshold,
                                        onValueChange = {
                                            hybridCharThreshold = it
                                            settings.hybridCharThreshold = it.toInt()
                                        },
                                        valueRange = 5f..60f,
                                        enabled = !running,
                                        modifier = Modifier.testTag("hybrid_char_threshold_slider")
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    preparingModel = true
                                    scope.launch {
                                        try {
                                            StandaloneTranslator.prepareOfflineModel(this@MainActivity, settings.language) { msg ->
                                                SessionState.offlineModelStatus.value = msg
                                            }
                                        } catch (error: Exception) {
                                            SessionState.offlineModelStatus.value = error.message ?: "Không thể tải gói dịch ngoại tuyến."
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
                                    Text("Đang chuẩn bị gói ngôn ngữ…", color = Mint)
                                } else {
                                    Icon(Icons.Outlined.Download, contentDescription = null, tint = Mint)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Tải sẵn gói dịch Offline (${settings.language.uppercase()} → VI)", color = Mint)
                                }
                            }
                            if (offlineModelStatus.isNotBlank()) {
                                Text(offlineModelStatus, color = Mint, fontSize = 12.sp, modifier = Modifier.testTag("offline_model_status"))
                            }
                        }

                        // 2. Performance & Memory Optimization Section
                        Section("Tối ưu Hiệu năng & Bộ nhớ (Không nóng máy · Không giật lag)") {
                            Text(
                                "• Đã xóa bỏ hoàn toàn Gemini Pro: Chỉ dùng duy nhất mô hình siêu nhẹ Gemini Flash-Lite.\n• Tự động giải phóng bộ nhớ ảnh (Bitmap.recycle()) ngay sau mỗi lần quét OCR.\n• Không chạy tiến trình nền kết nối PC.",
                                color = Muted,
                                fontSize = 12.sp
                            )
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Fast OCR Downscale (Thu gọn ảnh vùng chụp)", fontWeight = FontWeight.Medium)
                                    Text("Thu gọn vùng chụp màn hình trước khi nạp vào ML Kit OCR để giảm 35–50% tải CPU/RAM.", color = Muted, fontSize = 12.sp)
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
                                    Text("Bộ lọc tiền xử lý tự sửa lỗi OCR (OCR Pre-correction)", fontWeight = FontWeight.Medium)
                                    Text("Dùng dữ liệu 'lỗi OCR hay gặp' trong Kho SQLite để tự sửa lỗi chính tả OCR trước khi dịch.", color = Muted, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = ocrPreCorrection,
                                    onCheckedChange = { ocrPreCorrection = it; settings.ocrPreCorrection = it },
                                    enabled = !running,
                                    modifier = Modifier.testTag("ocr_pre_correction_switch")
                                )
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Kiểm tra ổn định 2 khung hình trước khi gọi Cloud", fontWeight = FontWeight.Medium)
                                    Text("Tránh gửi request lên đám mây khi màn hình đang cuộn; bản dịch Kho vẫn hiện tức thì.", color = Muted, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = stabilizeBeforeCloud,
                                    onCheckedChange = { stabilizeBeforeCloud = it; settings.stabilizeBeforeCloud = it },
                                    enabled = !running,
                                    modifier = Modifier.testTag("stabilize_before_cloud_switch")
                                )
                            }
                        }

                        Section("Phụ đề video & Khu vực quét") {
                            Text(
                                "Chữ Việt căn giữa, nền tối dễ đọc. Tự động thu màn hình đặt bản dịch sát ngoài vùng chữ gốc để không nháy; 1 KHUNG giữ kiểu phủ chữ gốc.",
                                color = Muted,
                                fontSize = 13.sp
                            )
                            Button(
                                onClick = { settings.useAutoSubtitlePreset(); subtitlePreset = true; autoPreset = true },
                                enabled = !running,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (autoPreset) "Đã chọn · Tự động tăng tốc" else "Tự động tăng tốc · Gemini")
                            }
                            OutlinedButton(
                                onClick = { settings.useSubtitlePreset(); subtitlePreset = true; autoPreset = false },
                                enabled = !running,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (subtitlePreset && !autoPreset) "Đã chọn · Phụ đề 1 khung" else "Dùng kiểu phụ đề 1 khung")
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
                                }
                            }
                        }

                        Section("Ngôn ngữ & Bối cảnh") {
                            Choice(
                                "Ngôn ngữ gốc",
                                linkedMapOf("zh" to "Trung", "ja" to "Nhật", "en" to "Anh", "ko" to "Hàn"),
                                settings.language,
                                !running
                            ) { settings.language = it }
                            Choice(
                                "Nội dung",
                                linkedMapOf("game" to "Game", "manga" to "Manga/Manhua", "document" to "Tài liệu", "web" to "Web"),
                                settings.profile,
                                !running
                            ) { settings.profile = it }
                        }
                    }
                    1 -> TranslationBankScreen(running)
                    2 -> SettingsScreen(running)
                }

                Text(
                    "GEMINI LIVE OCR STANDALONE  ·  ${BuildConfig.VERSION_NAME}",
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
            else "Tinh chỉnh AI độc lập trên máy, giao diện cửa sổ nổi và quyền riêng tư.",
            color = Muted
        )

        Section("Khóa Gemini Flash-Lite & Từ điển cá nhân") {
            Text(
                "Chỉ sử dụng duy nhất mô hình siêu nhẹ Gemini Flash-Lite (gemini-3.1-flash-lite-preview) cho Chế độ 2 để tiết kiệm tối đa chi phí và giữ độ trễ ≤ 1s.",
                color = Muted,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it; settings.standaloneApiKey = it.trim() },
                label = { Text("Gemini API Key trên điện thoại (cho Chế độ 2)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth().testTag("standalone_api_key_input")
            )
            Text(
                text = if (settings.effectiveStandaloneApiKey().isNotBlank()) {
                    "Đã cấu hình khóa Gemini API trên điện thoại (Engine: Flash-Lite)."
                } else {
                    "Chưa nhập khóa Gemini API • Ứng dụng vẫn hoạt động 100% độc lập với Kho thoại SQLite (<1ms) + ML Kit Offline."
                },
                color = Mint,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = localGlossary,
                onValueChange = { localGlossary = it; settings.localGlossary = it },
                label = { Text("Từ điển cá nhân trên điện thoại (nguồn = đích, mỗi dòng 1 mục)") },
                enabled = !running,
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("local_glossary_input")
            )
            OutlinedButton(
                onClick = {
                    PhoneTranslationCache.clear(this@MainActivity)
                    cacheClearedMessage = "Đã đặt lại bộ nhớ đệm về Kho mặc định."
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().testTag("clear_phone_cache_button")
            ) {
                Text("Đặt lại bộ nhớ đệm trên điện thoại")
            }
            if (cacheClearedMessage.isNotBlank()) {
                Text(cacheClearedMessage, color = Mint, fontSize = 12.sp)
            }
        }

        Section("Thiết bị và quyền ứng dụng") {
            Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", color = Mint, fontSize = 13.sp)
            Text(
                "Quyền hiển thị nổi: ${if (Settings.canDrawOverlays(this@MainActivity)) "Đã cấp" else "Chưa cấp"}",
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

        Section("Bản dịch nổi") {
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

        Section("Nhận diện màn hình") {
            Choice(
                "Vùng nhận diện",
                linkedMapOf("full" to "Toàn màn hình", "dialogue" to "Nửa dưới", "center" to "Vùng giữa", "subtitle" to "Dải phụ đề"),
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
            Text("Chu kỳ OCR · ${interval.toInt()} ms", color = Muted)
            Slider(value = interval, onValueChange = { interval = it; settings.interval = it.toLong() }, valueRange = 75f..2000f, enabled = !running)
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

        Section("Ứng dụng bỏ qua") {
            var exclusions by remember { mutableStateOf(settings.excludedApps) }
            OutlinedTextField(
                value = exclusions,
                onValueChange = { exclusions = it; settings.excludedApps = it },
                label = { Text("Package cần bỏ qua") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !running,
                minLines = 3
            )
            TextButton(
                onClick = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                enabled = !running
            ) {
                Text("Cấp quyền dữ liệu sử dụng")
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
