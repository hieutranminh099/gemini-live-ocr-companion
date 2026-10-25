package com.gemini.ocr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemini.ocr.network.BankCategory
import com.gemini.ocr.network.ChineseDialogueBank
import com.gemini.ocr.network.SelfLearningDatabase
import com.gemini.ocr.network.TranslationBankRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BankMint = Color(0xFF6EE7C7)
private val BankAmber = Color(0xFFF0B976)
private val BankPanel = Color(0xFF14212B)
private val BankCardInner = Color(0xFF0E1820)
private val BankMuted = Color(0xFF98ACB9)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslationBankScreen(running: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    var showEditor by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf("") }
    var editingTranslation by remember { mutableStateOf("") }
    var editingCategory by remember { mutableStateOf(BankCategory.DIALOGUE) }
    var editingOcrErrors by remember { mutableStateOf("") }
    var statusBanner by remember { mutableStateOf("") }

    var ocrTestInput by remember { mutableStateOf("帅兄，小心I") }
    var correctedPreview by remember { mutableStateOf("") }
    var bankMatchPreview by remember { mutableStateOf<TranslationBankRecord?>(null) }
    var preloadedFallbackPreview by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<List<TranslationBankRecord>>(emptyList()) }

    LaunchedEffect(ocrTestInput, refreshTrigger) {
        withContext(Dispatchers.IO) {
            val corrected = SelfLearningDatabase.preprocessAndCorrectOcr(ocrTestInput, context)
            val record = SelfLearningDatabase.lookup(context, corrected, incrementFrequency = false)
            val fallback = ChineseDialogueBank.matchInstant(corrected, context)
            correctedPreview = corrected
            bankMatchPreview = record
            preloadedFallbackPreview = fallback
        }
    }

    LaunchedEffect(searchQuery, selectedCategory, refreshTrigger) {
        withContext(Dispatchers.IO) {
            records = SelfLearningDatabase.getAllRecords(
                context = context,
                categoryFilter = selectedCategory,
                searchQuery = searchQuery
            )
        }
    }

    val ocrCorrectedCount by SessionState.ocrCorrectedCount.collectAsState()
    val bankSize by SessionState.bankSize.collectAsState()

    Text("Kho thoại tự học", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Text(
        "Cơ sở dữ liệu SQLite Tiếng Trung → Tiếng Việt có chỉ mục B-Tree (idx_zh_hash) kết hợp LruCache giới hạn tối đa 300 câu trên RAM (<2MB) chống tràn bộ nhớ.",
        color = BankMuted,
        fontSize = 14.sp
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BankStatCard("SQLite B-Tree", "${bankSize.coerceAtLeast(ChineseDialogueBank.totalEntries)} câu", Modifier.weight(1f))
        BankStatCard("RAM LruCache", "${SelfLearningDatabase.ramLruSize()}/300 câu", Modifier.weight(1f))
        BankStatCard("Đã sửa lỗi OCR", "$ocrCorrectedCount lần", Modifier.weight(1f))
    }

    // OCR Pre-correction & <5ms Bank Lookup Tester
    Card(colors = CardDefaults.cardColors(containerColor = BankPanel), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, tint = BankMint)
                Text("Tra cứu Kho Phụ Đề Tiếng Trung (<5ms)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Tự động chuẩn hóa Giản thể/Phồn thể, bỏ dấu câu và ghép mệnh đề thông minh từ SQLite idx_zh_hash trên Dispatchers.IO.",
                color = BankMuted,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = ocrTestInput,
                onValueChange = { ocrTestInput = it },
                label = { Text("Nhập câu chữ Hán cần tra thử trong Kho") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("ocr_precorrection_test_input")
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("帅兄，小心I", "这不叮能！", "道友請留步", "奉天承运，皇帝诏曰", "渡劫期老怪").forEach { sample ->
                    FilterChip(
                        selected = ocrTestInput == sample,
                        onClick = { ocrTestInput = sample },
                        label = { Text(sample, fontSize = 12.sp) }
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(BankCardInner, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Sau tiền xử lý sửa lỗi OCR: $correctedPreview", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                val matchedText = bankMatchPreview?.translatedText ?: preloadedFallbackPreview
                if (matchedText != null) {
                    val catText = bankMatchPreview?.let { BankCategory.displayName(it.category) } ?: "Kho phụ đề Tiếng Trung"
                    val freqText = bankMatchPreview?.usageFrequency ?: 1
                    Text(
                        "Khớp Kho tức thì (<5ms): $matchedText",
                        color = BankMint,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("dialogue_bank_test_result")
                    )
                    Text("Thể loại: $catText • Tần suất: $freqText lần", color = BankAmber, fontSize = 12.sp)
                } else {
                    Text(
                        "Câu mới chưa có trong Kho • Chế độ 1 sẽ gọi Gemini API và tự động lưu ngầm vào SQLite (Self-Learning Cache).",
                        color = BankMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("dialogue_bank_test_result")
                    )
                }
            }
        }
    }

    // Search, Filter, Clear & Add Record Controls
    Card(colors = CardDefaults.cardColors(containerColor = BankPanel), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Quản lý Kho SQLite (idx_zh_hash)", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                SelfLearningDatabase.clearLearnedAndRestoreDefaults(context)
                                statusBanner = "Đã xóa các câu tự học và nạp lại kho phụ đề mặc định từ asset."
                                refreshTrigger++
                            }
                        },
                        enabled = !running,
                        modifier = Modifier.testTag("reset_bank_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Làm mới Kho", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            editingSource = ""
                            editingTranslation = ""
                            editingCategory = BankCategory.DIALOGUE
                            editingOcrErrors = ""
                            showEditor = !showEditor
                        },
                        modifier = Modifier.testTag("add_bank_record_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(if (showEditor) Icons.Outlined.Close else Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (showEditor) "Đóng" else "Thêm câu", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            if (showEditor) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(BankCardInner, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Thêm / Cập nhật câu phụ đề Tiếng Trung", color = BankMint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    OutlinedTextField(
                        value = editingSource,
                        onValueChange = { editingSource = it },
                        label = { Text("Câu chữ Hán (Giản thể hoặc Phồn thể)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("bank_editor_source_input")
                    )
                    OutlinedTextField(
                        value = editingTranslation,
                        onValueChange = { editingTranslation = it },
                        label = { Text("Bản dịch tiếng Việt") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("bank_editor_translation_input")
                    )
                    OutlinedTextField(
                        value = editingOcrErrors,
                        onValueChange = { editingOcrErrors = it },
                        label = { Text("Biến thể lỗi OCR (tùy chọn, ngăn cách bằng |)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("bank_editor_ocr_errors_input")
                    )
                    Button(
                        onClick = {
                            if (editingSource.isNotBlank() && editingTranslation.isNotBlank()) {
                                val src = editingSource
                                val tr = editingTranslation
                                val cat = editingCategory
                                val errs = editingOcrErrors.trim()
                                scope.launch(Dispatchers.IO) {
                                    SelfLearningDatabase.learnOrUpsert(
                                        context = context,
                                        sourceText = src,
                                        translatedText = tr,
                                        category = cat,
                                        commonOcrErrors = errs
                                    )
                                    statusBanner = "Đã lưu '${src.trim()}' vào Kho SQLite (idx_zh_hash)."
                                    showEditor = false
                                    refreshTrigger++
                                }
                            }
                        },
                        enabled = editingSource.isNotBlank() && editingTranslation.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("bank_editor_save_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Lưu vào Kho SQLite", fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (statusBanner.isNotBlank()) {
                Text(statusBanner, color = BankMint, fontSize = 12.sp, modifier = Modifier.testTag("bank_status_banner"))
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Tìm câu chữ Hán hoặc nghĩa tiếng Việt trong Kho…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bank_search_input")
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "ALL" to "Tất cả",
                    BankCategory.DIALOGUE to "Câu thoại phim / truyện",
                    BankCategory.PRONOUN to "Danh xưng",
                    BankCategory.UI to "UI / Thuật ngữ"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = selectedCategory == key,
                        onClick = { selectedCategory = key },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                records.take(35).forEach { record ->
                    BankRecordItem(
                        record = record,
                        enabled = !running,
                        onEdit = {
                            editingSource = record.sourceText
                            editingTranslation = record.translatedText
                            editingCategory = record.category
                            editingOcrErrors = record.commonOcrErrors
                            showEditor = true
                        },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                SelfLearningDatabase.delete(context, record.sourceText)
                                statusBanner = "Đã xóa '${record.sourceText}' khỏi Kho SQLite."
                                refreshTrigger++
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BankRecordItem(
    record: TranslationBankRecord,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(BankCardInner, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Color(0xFF25443A),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = BankCategory.displayName(record.category),
                        color = BankMint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = "Tần suất: ${record.usageFrequency} lần",
                    color = BankAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Row {
                IconButton(onClick = onEdit, enabled = enabled, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Chỉnh sửa bản ghi", tint = BankMint, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Xóa bản ghi", tint = BankMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
        Text(record.sourceText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(record.translatedText, color = BankMint, fontSize = 14.sp)
        if (record.commonOcrErrors.isNotBlank()) {
            Text(
                "Lỗi OCR tự sửa: ${record.commonOcrErrors}",
                color = BankMuted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun BankStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(BankPanel, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, fontSize = 10.sp, color = BankMuted)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = BankMint)
    }
}
