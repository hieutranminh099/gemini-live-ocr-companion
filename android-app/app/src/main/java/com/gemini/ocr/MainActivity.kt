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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.gemini.ocr.network.DiscoveredPc
import com.gemini.ocr.network.LanScanner
import com.gemini.ocr.network.PhoneTranslationCache
import com.gemini.ocr.network.SecureConnection
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.ocr.OcrProcessor
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val Mint = Color(0xFF6EE7C7)
private val Background = Color(0xFF0B1218)
private val Panel = Color(0xFF14212B)
private val Muted = Color(0xFF98ACB9)

class MainActivity : ComponentActivity() {
    private lateinit var settings: AppSettings
    private val incoming = MutableStateFlow("")
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startForegroundService(Intent(this, ScreenCaptureService::class.java).putExtra("data", result.data))
        } else SessionState.status.value = "Bạn chưa cấp quyền thu màn hình."
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { beginProjection() }
    private val overlays = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) startTranslation(forceStandalone = settings.connectionMode != "pc")
        else SessionState.status.value = "Cần quyền hiển thị trên ứng dụng khác để hiện bản dịch."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        intent.data?.let { incoming.value = it.toString() }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Background, surface = Panel,
                onPrimary = Background, onSurface = Color(0xFFE8F0F4), secondary = Color(0xFFF0B976),
                secondaryContainer = Color(0xFF25443A), onSecondaryContainer = Mint, outline = Color(0xFF536974))) {
                Surface(Modifier.fillMaxSize(), color = Background) { App() }
            }
        }
    }
    override fun onResume() { super.onResume(); SessionState.activityVisible.value = true }
    override fun onPause() { SessionState.activityVisible.value = false; super.onPause() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); intent.data?.let { incoming.value = it.toString() } }
    private fun startTranslation(forceStandalone: Boolean = false) {
        if (forceStandalone && settings.connectionMode == "pc") {
            settings.connectionMode = "auto"
        }
        val canStandalone = forceStandalone || settings.connectionMode != "pc"
        if (settings.token.isBlank() && !canStandalone) {
            SessionState.status.value = "Hãy ghép cặp máy tính hoặc chuyển sang chế độ Độc lập trên điện thoại."
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            overlays.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else beginProjection()
    }
    private fun beginProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val request = if (Build.VERSION.SDK_INT >= 34 && settings.fullScreen)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        capture.launch(request)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun App() {
        var tab by remember { mutableIntStateOf(0) }
        BackHandler(enabled = tab != 0) { tab = 0 }
        val scope = rememberCoroutineScope()
        val running by SessionState.running.collectAsState()
        val connected by SessionState.connected.collectAsState()
        val standaloneActive by SessionState.standaloneActive.collectAsState()
        val offlineModelStatus by SessionState.offlineModelStatus.collectAsState()
        val backend by SessionState.backend.collectAsState()
        val status by SessionState.status.collectAsState()
        val latency by SessionState.latency.collectAsState()
        val instantMs by SessionState.instantMs.collectAsState()
        val ocr by SessionState.ocrMs.collectAsState()
        val count by SessionState.blocks.collectAsState()
        val cached by SessionState.cacheHits.collectAsState()
        val preloadedHits by SessionState.preloadedHits.collectAsState()
        val fuzzyHits by SessionState.fuzzyHits.collectAsState()
        val savedApiCalls by SessionState.savedApiCalls.collectAsState()
        val noiseFiltered by SessionState.noiseFiltered.collectAsState()
        val activeModelName by SessionState.activeModelName.collectAsState()
        val ocrFrames by SessionState.ocrFrames.collectAsState()
        val ocrArea by SessionState.ocrArea.collectAsState()
        val lastOcr by SessionState.lastOcr.collectAsState()
        val lastTranslation by SessionState.lastTranslation.collectAsState()
        val timings by SessionState.timings.collectAsState()
        val networkRtt by SessionState.networkRtt.collectAsState()
        val frameToDraw by SessionState.frameToDraw.collectAsState()
        var subtitlePreset by remember { mutableStateOf(settings.subtitleFocus) }
        var autoPreset by remember { mutableStateOf(settings.subtitleFocus && settings.subtitleCapture == "auto") }
        var connectionMode by remember { mutableStateOf(settings.connectionMode) }
        var standaloneEngine by remember { mutableStateOf(settings.standaloneEngine) }
        var turboLatency by remember { mutableStateOf(settings.turboLatency) }
        var preloadedDialogueEnabled by remember { mutableStateOf(settings.preloadedDialogueEnabled) }
        var fastOcrDownscale by remember { mutableStateOf(settings.fastOcrDownscale) }
        var costSavingMode by remember { mutableStateOf(settings.costSavingMode) }
        var stabilizeBeforeCloud by remember { mutableStateOf(settings.stabilizeBeforeCloud) }
        var preparingModel by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            launch(Dispatchers.Default) {
                OcrProcessor.preWarm(settings.language)
                StandaloneTranslator.preWarm(this@MainActivity, settings.language)
            }
        }
        LaunchedEffect(tab, running) {
            subtitlePreset = settings.subtitleFocus
            autoPreset = settings.subtitleFocus && settings.subtitleCapture == "auto"
            connectionMode = settings.connectionMode
            standaloneEngine = settings.standaloneEngine
            turboLatency = settings.turboLatency
            preloadedDialogueEnabled = settings.preloadedDialogueEnabled
            fastOcrDownscale = settings.fastOcrDownscale
            costSavingMode = settings.costSavingMode
            stabilizeBeforeCloud = settings.stabilizeBeforeCloud
        }
        var paired by remember { mutableStateOf(settings.token.isNotBlank()) }
        LaunchedEffect(running, status) { paired = settings.token.isNotBlank() }
        val link by incoming.collectAsState()
        LaunchedEffect(link) { if (link.isNotBlank()) tab = 1 }
        Scaffold(containerColor = Background, bottomBar = {
            NavigationBar(containerColor = Background) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Outlined.Translate, null) }, label = { Text("Dịch trực tiếp") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Outlined.Devices, null) }, label = { Text("Kết nối") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Outlined.Tune, null) }, label = { Text("Tùy chỉnh") })
            }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.DocumentScanner, null, tint = Mint, modifier = Modifier.size(32.dp))
                    Column { Text("GEMINI OCR", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 16.sp); Text("Độc lập trên điện thoại ≤ 1s  ·  Companion PC", color = Muted, fontSize = 11.sp) }
                }
                when (tab) {
                    0 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("Hiểu mọi\nkhung hình.", fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
                            Text("Dịch trực tiếp ngay trên điện thoại với độ trễ ≤ 1 giây, hoặc đồng bộ cùng máy tính.", color = Muted, fontSize = 15.sp)
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
                            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(
                                        imageVector = if (connected && connectionMode != "standalone") Icons.Outlined.Wifi else Icons.Outlined.Smartphone,
                                        contentDescription = null,
                                        tint = if (connected || connectionMode != "pc" || standaloneActive) Mint else Muted
                                    )
                                    Text(
                                        text = if (running) "PHIÊN DỊCH ĐANG CHẠY" else "BẮT ĐẦU MỘT PHIÊN DỊCH",
                                        fontSize = 12.sp,
                                        color = Mint,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Text(status, fontSize = 17.sp)
                                Text(
                                    text = if (connected && connectionMode != "standalone") backend.badge else settings.standaloneBadge,
                                    color = Mint,
                                    fontSize = 12.sp
                                )
                                Button(onClick = {
                                    if (running) startService(Intent(this@MainActivity, ScreenCaptureService::class.java).setAction("STOP"))
                                    else if (connectionMode == "standalone") startTranslation(forceStandalone = true)
                                    else if (!paired) tab = 1 else startTranslation()
                                }, modifier = Modifier.fillMaxWidth().height(54.dp).testTag("primary_session_button"), shape = RoundedCornerShape(16.dp)) {
                                    Icon(if (running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (running) "Kết thúc phiên dịch"
                                        else if (connectionMode == "standalone") "Bật dịch độc lập (≤ 1s)"
                                        else if (paired) "Bật dịch màn hình"
                                        else "Kết nối máy tính",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (!running && connectionMode != "standalone") {
                                    OutlinedButton(
                                        onClick = { startTranslation(forceStandalone = true) },
                                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("standalone_start_button"),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Icon(Icons.Outlined.Bolt, contentDescription = null, tint = Mint)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Dịch độc lập ngay trên điện thoại (≤ 1s)", color = Mint, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                if (running) {
                                    OutlinedButton(
                                        onClick = {
                                            startService(Intent(this@MainActivity, ScreenCaptureService::class.java).setAction("RETRANSLATE_PRO"))
                                        },
                                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("retranslate_high_precision_button"),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Color(0xFFF0B976))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Dịch lại với chế độ chính xác cao (Gemini Pro)", color = Color(0xFFF0B976), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                }
                                Text(if (Build.VERSION.SDK_INT >= 34 && !settings.fullScreen) "Chọn “Một ứng dụng” trong hộp thoại Android, rồi chọn game hoặc ứng dụng cần dịch." else "Cho phép thu toàn màn hình, rồi chuyển sang ứng dụng cần dịch.", color = Muted, fontSize = 12.sp)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Metric(if (connected && connectionMode != "standalone") "Máy chủ" else "Độ trễ dịch", "$latency ms", Modifier.weight(1f))
                            Metric("OCR cục bộ", "$ocr ms", Modifier.weight(1f))
                            Metric("Tiết kiệm API", "$savedApiCalls lượt", Modifier.weight(1f))
                        }
                        if (running && (preloadedHits > 0 || fuzzyHits > 0 || noiseFiltered > 0)) {
                            Text(
                                "Kho thoại/UI (<1ms): $preloadedHits • Khớp gần đúng (Fuzzy 90%+): $fuzzyHits • Lọc rác OCR: $noiseFiltered",
                                color = Mint,
                                fontSize = 12.sp
                            )
                        }
                        if (running && instantMs > 0) Text("Bản dịch tức thì trên máy (Kho/ML Kit): $instantMs ms • Model đám mây: $activeModelName", color = Mint, fontSize = 12.sp)
                        if (running) Text("Đã đọc $ocrFrames khung • OCR $ocrArea% diện tích màn hình", color = Muted, fontSize = 12.sp)
                        if (running && timings.isNotEmpty()) Text(timings, color = Muted, fontSize = 12.sp)
                        if (running && networkRtt > 0) Text("Khứ hồi Android ↔ Windows: $networkRtt ms", color = Muted, fontSize = 12.sp)
                        if (running && frameToDraw > 0) Text("Từ khung OCR đến lúc vẽ: $frameToDraw ms. Chưa gồm thời gian chữ xuất hiện trước lần lấy khung.", color = Muted, fontSize = 12.sp)
                        if (running && lastOcr.isNotBlank()) Section("Khung vừa đọc") {
                            Text("OCR: $lastOcr", color = Muted, fontSize = 14.sp)
                            Text(if (lastTranslation.isBlank()) "Chưa có kết quả • Xem trạng thái ở trên" else lastTranslation, color = Mint, fontSize = 15.sp)
                        }
                        Section("Chế độ Độc lập & Tối ưu 5 Tầng (≤ 1s · Tiết kiệm Token)") {
                            Text("Kiến trúc 5 tầng: (1) Định tuyến Flash/Pro thông minh, (2) Cache + Fuzzy Match 90–95% + Từ điển UI Offline, (3) Lọc rác OCR & Gom cụm đánh số, (4) Rút gọn Prompt + Khóa 100–250 token + Nén 1–2 câu ngữ cảnh, (5) Hybrid ML Kit Offline + Gemini khi gặp câu phức tạp.", color = Muted, fontSize = 13.sp)
                            Choice(
                                label = "Chế độ hoạt động",
                                values = linkedMapOf("auto" to "Tự động (Độc lập ⇄ PC)", "standalone" to "Độc lập trên điện thoại", "pc" to "Chỉ kết nối PC"),
                                initial = connectionMode,
                                enabled = !running
                            ) {
                                connectionMode = it
                                settings.connectionMode = it
                            }
                            Choice(
                                label = "Bộ máy dịch trên điện thoại",
                                values = linkedMapOf(
                                    "hybrid" to "Hybrid ≤ 1s (Kho + ML Kit + Gemini)",
                                    "mlkit" to "Offline siêu tốc (Kho + ML Kit)",
                                    "gemini" to "Gemini Flash-Lite trực tiếp"
                                ),
                                initial = standaloneEngine,
                                enabled = !running
                            ) {
                                standaloneEngine = it
                                settings.standaloneEngine = it
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Tối ưu chi phí Gemini 5 tầng (Smart Cost Saver)", fontWeight = FontWeight.Medium)
                                    Text("Tự động dùng ML Kit Offline / Fuzzy Match 90%+ cho câu ngắn; chỉ gọi Gemini Flash/Pro khi gặp câu dài, cổ văn/tiên hiệp hoặc bấm nút PRO.", color = Muted, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = costSavingMode,
                                    onCheckedChange = { costSavingMode = it; settings.costSavingMode = it },
                                    enabled = !running,
                                    modifier = Modifier.testTag("cost_saving_mode_switch")
                                )
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Kiểm tra ổn định 2 khung hình trước khi gọi Cloud", fontWeight = FontWeight.Medium)
                                    Text("Tránh gửi request lên đám mây khi màn hình đang cuộn hoặc mờ; bản dịch Offline vẫn phản hồi tức thì.", color = Muted, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = stabilizeBeforeCloud,
                                    onCheckedChange = { stabilizeBeforeCloud = it; settings.stabilizeBeforeCloud = it },
                                    enabled = !running,
                                    modifier = Modifier.testTag("stabilize_before_cloud_switch")
                                )
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Phản hồi tức thì 0ms Debounce (Turbo)", fontWeight = FontWeight.Medium)
                                    Text("Bỏ độ trễ chờ ổn định chữ ban đầu (0ms), dịch và hiển thị ngay từ khung OCR đầu tiên.", color = Muted, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = turboLatency,
                                    onCheckedChange = { turboLatency = it; settings.turboLatency = it },
                                    enabled = !running,
                                    modifier = Modifier.testTag("turbo_latency_switch")
                                )
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Kho hội thoại Tiếng Trung nạp sẵn (${ChineseDialogueBank.totalEntries} mục · <1ms)", fontWeight = FontWeight.Medium)
                                    Text("Khớp tức thì câu thoại phim, hoạt hình, truyện tranh (tu tiên, kiếm hiệp, cổ trang, hiện đại), chuẩn hóa Phồn/Giản và ghép cụm thông minh.", color = Muted, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = preloadedDialogueEnabled,
                                    onCheckedChange = { preloadedDialogueEnabled = it; settings.preloadedDialogueEnabled = it },
                                    enabled = !running,
                                    modifier = Modifier.testTag("preloaded_dialogue_switch")
                                )
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Tối ưu kích thước ảnh OCR (Fast Downscale)", fontWeight = FontWeight.Medium)
                                    Text("Thu gọn ảnh chụp vùng lớn xuống chuẩn 960px trước khi chạy ML Kit OCR, giảm 35–50% thời gian đọc chữ.", color = Muted, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = fastOcrDownscale,
                                    onCheckedChange = { fastOcrDownscale = it; settings.fastOcrDownscale = it },
                                    enabled = !running,
                                    modifier = Modifier.testTag("fast_ocr_downscale_switch")
                                )
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
                        Section("Phụ đề video") {
                            Text("Chữ Việt căn giữa, nền tối dễ đọc. Tự động thu toàn màn hình đặt bản dịch sát ngoài vùng chữ gốc để không nháy; 1 KHUNG giữ kiểu phủ chữ gốc.", color = Muted, fontSize = 13.sp)
                            Button(onClick = { settings.useAutoSubtitlePreset(); subtitlePreset = true; autoPreset = true }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                                Text(if (autoPreset) "Đã chọn · Tự động tăng tốc" else "Tự động tăng tốc · Gemini")
                            }
                            OutlinedButton(onClick = { settings.useSubtitlePreset(); subtitlePreset = true; autoPreset = false }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                                Text(if (subtitlePreset && !autoPreset) "Đã chọn · Phụ đề 1 khung" else "Dùng kiểu phụ đề 1 khung")
                            }
                            if (subtitlePreset) {
                                Choice("Cách lấy phụ đề", linkedMapOf("frame" to "Chạm dịch 1 khung", "auto" to "Tự động"), settings.subtitleCapture, !running) { settings.subtitleCapture = it; autoPreset = it == "auto" }
                                Text(if (autoPreset) "Bật dịch → chuyển sang video → VÙNG để khoanh chữ gốc, chừa khoảng trống phía trên. Bản dịch và thanh nút luôn hiện ổn định khi quét; câu mới sẽ tự gửi đến Gemini." else "Bật dịch → chuyển sang video → VÙNG để khoanh chữ → dừng video rồi chạm 1 KHUNG. Thanh nổi hiện chữ OCR và số giây chờ; có thể chạm HỦY.", color = Mint, fontSize = 12.sp)
                                Text("Trên Windows, dùng Gemini API hoặc tự bật chế độ CLI cá nhân thử nghiệm. Google không hỗ trợ CLI làm backend ứng dụng này; chế độ thử nghiệm có thể bị gián đoạn và không bảo đảm độ trễ 1 giây. Prototype local chưa bật Hybrid.", color = Muted, fontSize = 12.sp)
                            }
                        }
                        Section("Cấu hình phiên dịch") {
                            Choice("Ngôn ngữ gốc", linkedMapOf("ja" to "Nhật", "en" to "Anh", "zh" to "Trung", "ko" to "Hàn"), settings.language, !running) { settings.language = it }
                            Choice("Chế độ AI", linkedMapOf("fast" to "Fast", "smart" to "Smart", "quality" to "Quality"), settings.mode, !running) { settings.mode = it }
                            Choice("Nội dung", linkedMapOf("game" to "Game", "manga" to "Manga", "document" to "Tài liệu", "web" to "Web", "technical" to "Kỹ thuật"), settings.profile, !running) { settings.profile = it }
                        }
                        Text("OCR xử lý ngay trên điện thoại. Ở chế độ Độc lập, bản dịch chạy trực tiếp trên thiết bị (ML Kit Offline + Gemini API), không cần mở máy tính.", color = Muted, fontSize = 12.sp)
                    }
                    1 -> PairingScreen(link, running, paired) { paired = it }
                    2 -> SettingsScreen(running)
                }
                Text("GEMINI LIVE OCR TRANSLATOR  ·  ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            }
        }
    }
    @Composable private fun PairingScreen(link: String, running: Boolean, paired: Boolean, onPaired: (Boolean) -> Unit) {
        var address by remember { mutableStateOf(settings.host) }
        var fingerprint by remember { mutableStateOf(settings.pin) }
        var code by remember { mutableStateOf("") }
        var pasted by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var scanning by remember { mutableStateOf(false) }
        var scanMessage by remember { mutableStateOf("") }
        var discoveredPcs by remember { mutableStateOf<List<DiscoveredPc>>(emptyList()) }
        val scope = rememberCoroutineScope()
        fun connectToDiscoveredPc(pc: DiscoveredPc) {
            busy = true
            scanMessage = "Đang kết nối với ${pc.name} (${pc.host})…"
            scope.launch {
                try {
                    SecureConnection.connectDiscovered(settings, pc, "${Build.MANUFACTURER} ${Build.MODEL}")
                    address = settings.host
                    fingerprint = settings.pin
                    discoveredPcs = emptyList()
                    onPaired(true)
                    scanMessage = "Đã tự động kết nối với ${pc.name} (${pc.host}). Bạn có thể bật dịch."
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    scanMessage = error.message ?: "Không thể kết nối tự động với ${pc.name}."
                } finally {
                    busy = false
                }
            }
        }
        fun importLink(value: String) {
            try {
                val uri = Uri.parse(value.trim())
                require(uri.scheme == "gemocr" && uri.host == "pair")
                address = uri.getQueryParameter("host") ?: error("host")
                fingerprint = uri.getQueryParameter("pin") ?: error("pin")
                code = uri.getQueryParameter("code") ?: error("code")
                message = "Đã đọc thông tin. Xác nhận đúng máy tính rồi nhấn Ghép cặp."
            } catch (_: Exception) { message = "Liên kết không hợp lệ. Hãy tạo mã ghép cặp trên Windows." }
        }
        LaunchedEffect(link) { if (link.isNotBlank()) importLink(link) }
        Text("Máy tính của bạn", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Tùy chọn đồng bộ với Windows Companion hoặc dùng độc lập trên điện thoại.", color = Muted)
        Section("Quét tự động trong mạng LAN") {
            Text("Tự động lắng nghe tín hiệu UDP từ máy tính đang mở Gemini OCR trong cùng mạng LAN và kết nối ngay.", color = Muted, fontSize = 14.sp)
            Button(
                onClick = {
                    scanning = true
                    discoveredPcs = emptyList()
                    scanMessage = "Đang quét thiết bị trong mạng LAN…"
                    scope.launch {
                        try {
                            val found = LanScanner.scan()
                            when {
                                found.isEmpty() -> {
                                    scanMessage = "Không tìm thấy máy tính trong mạng LAN. Bạn vẫn có thể bật dịch độc lập ngay trên điện thoại ở tab Dịch trực tiếp."
                                }
                                found.size == 1 -> {
                                    val pc = found.first()
                                    scanning = false
                                    connectToDiscoveredPc(pc)
                                }
                                else -> {
                                    discoveredPcs = found
                                    scanMessage = "Tìm thấy ${found.size} máy tính trong mạng LAN. Chọn thiết bị để kết nối:"
                                }
                            }
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            scanMessage = error.message ?: "Quét mạng LAN gặp lỗi. Hãy thử lại."
                        } finally {
                            scanning = false
                        }
                    }
                },
                enabled = !scanning && !busy && !running,
                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("auto_scan_devices_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Background)
                    Spacer(Modifier.width(8.dp))
                    Text("Đang quét mạng LAN…", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Outlined.WifiFind, contentDescription = "Tự động quét thiết bị")
                    Spacer(Modifier.width(8.dp))
                    Text("Tự động quét thiết bị", fontWeight = FontWeight.Bold)
                }
            }
            if (scanMessage.isNotBlank()) {
                Text(scanMessage, color = Mint, fontSize = 13.sp, modifier = Modifier.testTag("auto_scan_status"))
            }
            if (discoveredPcs.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    discoveredPcs.forEach { pc ->
                        OutlinedButton(
                            onClick = { connectToDiscoveredPc(pc) },
                            enabled = !busy && !running,
                            modifier = Modifier.fillMaxWidth().testTag("discovered_pc_${pc.host}"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text(pc.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("${pc.host} • SHA-256: ${pc.pin.take(12)}…", color = Muted, fontSize = 12.sp)
                            }
                            Text("Kết nối", color = Mint, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        Section("Bộ máy dịch trên Windows") {
            Text("Trong Cài đặt AI trên Windows, chọn Gemini API hoặc tự bật CLI cá nhân thử nghiệm nếu muốn dùng tài khoản đã đăng nhập. API có hạn mức riêng. Google không hỗ trợ dùng CLI làm backend ứng dụng này; thử nghiệm có thể ngừng hoạt động và không bảo đảm tốc độ 1 giây.", color = Muted, fontSize = 14.sp)
            Text("Ghép cặp bên dưới và giữ máy tính mở trong suốt phiên. Cấu hình và dữ liệu đăng nhập cũ được giữ; ứng dụng không tự chuyển phương thức xác thực.", color = Muted, fontSize = 14.sp)
        }
        if (paired) {
            Section("Đã ghép cặp") {
                Text(settings.host, color = Mint, fontSize = 22.sp)
                Text("Máy tính cần mở chương trình Gemini OCR trong suốt phiên dịch.", color = Muted)
                OutlinedButton(onClick = { settings.unpair(); onPaired(false) }, enabled = !running) { Text("Hủy ghép cặp trên điện thoại") }
            }
        }
        Section("Ghép cặp bằng QR hoặc liên kết") {
            Text("1. Mở Gemini OCR trên Windows và chọn Kết nối thiết bị.\n2. Quét QR bằng camera điện thoại hoặc dán liên kết bên dưới.\n3. Nhấn Ghép cặp để lưu kết nối.", color = Muted, fontSize = 14.sp)
            OutlinedTextField(value = pasted, onValueChange = { pasted = it }, label = { Text("Liên kết gemocr://pair…") }, modifier = Modifier.fillMaxWidth(), enabled = !running)
            TextButton(onClick = { importLink(pasted) }, enabled = !running && pasted.isNotBlank()) { Text("Đọc liên kết") }
        }
        Section("Thông tin kết nối") {
            OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Địa chỉ máy tính · IP:cổng") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !running)
            OutlinedTextField(value = fingerprint, onValueChange = { fingerprint = it }, label = { Text("SHA-256 chứng chỉ · 64 ký tự") }, modifier = Modifier.fillMaxWidth(), enabled = !running)
            OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text("Mã 6 số · hiệu lực 2 phút") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !running)
            Button(onClick = {
                busy = true
                scope.launch {
                    try {
                        SecureConnection.pair(settings, address, fingerprint, code, "${Build.MANUFACTURER} ${Build.MODEL}")
                        onPaired(true); message = "Ghép cặp thành công. Bạn có thể bật dịch."
                    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (error: Exception) { message = error.message ?: "Không thể kết nối. Kiểm tra Wi-Fi, địa chỉ và tường lửa Windows." }
                    finally { busy = false }
                }
            }, enabled = !busy && !running && code.length == 6 && fingerprint.isNotBlank() && address.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("manual_pair_button")) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Ghép cặp an toàn")
            }
            if (message.isNotBlank()) Text(message, color = Mint, fontSize = 13.sp)
        }
    }
    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun SettingsScreen(running: Boolean) {
        var apiKeyInput by remember { mutableStateOf(settings.standaloneApiKey) }
        var localGlossary by remember { mutableStateOf(settings.localGlossary) }
        var cacheClearedMessage by remember { mutableStateOf("") }
        var testInput by remember { mutableStateOf("师兄，小心！") }
        val previewMatch = remember(testInput) { ChineseDialogueBank.matchInstant(testInput) }
        Text("Theo cách của bạn", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(if (running) "Kết thúc phiên dịch để thay đổi cấu hình." else "Tinh chỉnh lớp dịch, AI độc lập trên máy và quyền riêng tư.", color = Muted)
        Section("Kho hội thoại Tiếng Trung nạp sẵn (<1ms)") {
            Text("Đã nạp sẵn ${ChineseDialogueBank.totalEntries} câu thoại và thuật ngữ phim, hoạt hình, truyện tranh (Tu tiên, Kiếm hiệp, Cổ trang, Hiện đại). Hỗ trợ chuẩn hóa Phồn thể → Giản thể, bỏ dấu câu và ghép vế tự động dưới 1ms.", color = Muted, fontSize = 12.sp)
            OutlinedTextField(
                value = testInput,
                onValueChange = { testInput = it },
                label = { Text("Thử câu thoại Tiếng Trung (Giản thể / Phồn thể)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("dialogue_bank_test_input")
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("师兄，小心！", "等一下，我马上来", "這不可能！", "快走，来不及了！", "找死！敢尔").forEach { sample ->
                    FilterChip(
                        selected = testInput == sample,
                        onClick = { testInput = sample },
                        label = { Text(sample, fontSize = 12.sp) }
                    )
                }
            }
            Text(
                text = if (previewMatch != null) "Khớp tức thì (<1ms): $previewMatch" else "Câu tự do chưa có trong kho cố định • Sẽ dịch bằng ML Kit / Gemini và hậu xử lý thuật ngữ Hán-Việt.",
                color = if (previewMatch != null) Mint else Muted,
                fontSize = 13.sp,
                fontWeight = if (previewMatch != null) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.testTag("dialogue_bank_test_result")
            )
        }
        Section("AI Độc lập & Từ điển trên điện thoại") {
            Text("Nhập Gemini API Key cá nhân (được mã hóa bằng AndroidKeyStore trên thiết bị) để dùng chế độ Hybrid hoặc Gemini trực tiếp trên điện thoại mà không cần máy tính.", color = Muted, fontSize = 12.sp)
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it; settings.standaloneApiKey = it.trim() },
                label = { Text("Gemini API Key trên điện thoại (tùy chọn)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth().testTag("standalone_api_key_input")
            )
            Text(
                text = if (settings.effectiveStandaloneApiKey().isNotBlank()) "Đã cấu hình khóa Gemini API trên điện thoại (Flash-Lite minimal thinking)." else "Chưa nhập khóa Gemini API • Ứng dụng vẫn dịch độc lập bằng Kho hội thoại (<1ms) + ML Kit Offline (<150ms).",
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
                    cacheClearedMessage = "Đã xóa bộ nhớ dịch lưu trên điện thoại."
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().testTag("clear_phone_cache_button")
            ) {
                Text("Xóa bộ nhớ dịch trên điện thoại")
            }
            if (cacheClearedMessage.isNotBlank()) {
                Text(cacheClearedMessage, color = Mint, fontSize = 12.sp)
            }
        }
        Section("Thiết bị và quyền ứng dụng") {
            Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", color = Mint, fontSize = 13.sp)
            Text("Quyền hiển thị nổi: ${if (Settings.canDrawOverlays(this@MainActivity)) "Đã cấp" else "Chưa cấp"}", color = Muted, fontSize = 12.sp)
            Text("Nếu MIUI/HyperOS dừng app khi chuyển sang game, kiểm tra giới hạn pin và quyền cửa sổ nổi trong phần thông tin ứng dụng. Tên mục tùy phiên bản hệ thống. Luôn cấp quyền thu màn hình cho mỗi phiên.", color = Muted, fontSize = 12.sp)
            TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) { Text("Mở thông tin ứng dụng") }
        }
        Section("Bản dịch nổi") {
            Choice("Kiểu hiển thị", linkedMapOf("subtitle" to "Phụ đề rời", "replace" to "Phủ từng đoạn", "cover" to "Đè phụ đề gốc"), settings.overlay, !running) { settings.overlay = it; if (it == "cover") settings.region = "subtitle" }
            Text("Đè phụ đề gốc dùng nền kín, chữ đậm căn giữa. Khi tự động thu toàn màn hình, dải Việt nằm sát ngoài vùng OCR để không nháy. Chỉnh vùng trực tiếp bằng nút VÙNG.", color = Muted, fontSize = 12.sp)
            Choice("Màu chữ", linkedMapOf("-1" to "Trắng", "-9508921" to "Xanh bạc hà", "-11930" to "Vàng"), settings.textColor.toString(), !running) { settings.textColor = it.toInt() }
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
            Choice("Vùng nhận diện", linkedMapOf("full" to "Toàn màn hình", "dialogue" to "Nửa dưới", "center" to "Vùng giữa", "subtitle" to "Dải phụ đề"), settings.region, !running) {
                settings.region = it
                if (it != "subtitle" && settings.overlay == "cover") settings.overlay = "subtitle"
            }
            Choice("Lấy khung trong dải phụ đề", linkedMapOf("frame" to "Chạm dịch 1 khung", "auto" to "Tự động"), settings.subtitleCapture, !running) { settings.subtitleCapture = it }
            var tracking by remember { mutableStateOf(settings.trackSourceRow) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Theo dõi dòng chữ trong VÙNG", Modifier.weight(1f))
                Switch(checked = tracking, onCheckedChange = { tracking = it; settings.trackSourceRow = it }, enabled = !running)
            }
            Text("Thử nghiệm: thu hẹp theo chiều dọc sau hai lần thấy dòng nguồn, luôn nằm trong vùng bạn chọn. Quét lại toàn vùng mỗi 750 ms hoặc khi mất chữ. Chưa bật mặc định.", color = Muted, fontSize = 12.sp)
            Text("Chọn Nửa dưới cho hội thoại game để tránh đồng hồ, điểm số và thanh trạng thái. Các vùng khác vẫn giữ nguyên trên màn hình.", color = Muted, fontSize = 12.sp)
            var interval by remember { mutableFloatStateOf(settings.interval.toFloat()) }
            Text("Chu kỳ OCR · ${interval.toInt()} ms", color = Muted)
            Slider(value = interval, onValueChange = { interval = it; settings.interval = it.toLong() }, valueRange = 75f..2000f, enabled = !running)
            var fullScreen by remember { mutableStateOf(settings.fullScreen) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Thu toàn màn hình", Modifier.weight(1f))
                Switch(checked = fullScreen, onCheckedChange = { fullScreen = it; settings.fullScreen = it }, enabled = !running && Build.VERSION.SDK_INT >= 34)
            }
            Text("Tự động trong dải phụ đề không ẩn lớp dịch khi quét toàn màn hình: bản Việt đặt ngoài VÙNG. Android 14+ chia sẻ Một ứng dụng cho phép giữ kiểu phủ chữ gốc. Các kiểu phủ khác vẫn có thể ẩn ngắn khi chụp.", color = Muted, fontSize = 12.sp)
        }
        Section("Ứng dụng bỏ qua") {
            var exclusions by remember { mutableStateOf(settings.excludedApps) }
            Text("Mỗi dòng một tên package (ví dụ com.example.bank). Cần quyền Truy cập dữ liệu sử dụng để xác định ứng dụng đang mở. Khi thiếu quyền, quá trình gửi văn bản sẽ tạm dừng.", color = Muted, fontSize = 12.sp)
            OutlinedTextField(value = exclusions, onValueChange = { exclusions = it; settings.excludedApps = it }, label = { Text("Package cần bỏ qua") }, modifier = Modifier.fillMaxWidth(), enabled = !running, minLines = 3)
            TextButton(onClick = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }, enabled = !running) { Text("Cấp quyền dữ liệu sử dụng") }
            Text("Khi mở ngân hàng, mật khẩu hoặc nội dung riêng tư, chạm nút nổi để tạm dừng ngay. Giữ nút nổi để kết thúc thu màn hình.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 10.sp, color = Muted); Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Mint)
    }
}
@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Choice(label: String, values: Map<String, String>, initial: String, enabled: Boolean, onChange: (String) -> Unit) {
    var selected by remember(initial) { mutableStateOf(initial) }
    Text(label, fontSize = 12.sp, color = Muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { (key, value) -> FilterChip(selected = selected == key, onClick = { selected = key; onChange(key) }, label = { Text(value, fontSize = 12.sp) }, enabled = enabled) }
    }
}
