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

private val BankMint = Color(0xFF6EE7C7)
private val BankAmber = Color(0xFFF0B976)
private val BankPanel = Color(0xFF14212B)
private val BankCardInner = Color(0xFF0E1820)
private val BankMuted = Color(0xFF98ACB9)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslationBankScreen(running: Boolean) {
    val context = LocalContext.current
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
    val correctedPreview = remember(ocrTestInput, refreshTrigger) {
        SelfLearningDatabase.preprocessAndCorrectOcr(ocrTestInput, context)
    }
    val bankMatchPreview = remember(correctedPreview, refreshTrigger) {
        SelfLearningDatabase.lookup(context, correctedPreview, incrementFrequency = false)
    }
    val preloadedFallbackPreview = remember(correctedPreview) {
        ChineseDialogueBank.matchInstant(correctedPreview)
    }

    val records = remember(searchQuery, selectedCategory, refreshTrigger) {
        SelfLearningDatabase.getAllRecords(
            context = context,
            categoryFilter = selectedCategory,
            searchQuery = searchQuery
        )
    }
    val ocrCorrectedCount by SessionState.ocrCorrectedCount.collectAsState()
    val preloadedHits by SessionState.preloadedHits.collectAsState()

    Text("Kho thoại tự học", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Text(
        "Cơ sở dữ liệu SQLite cục bộ lưu trữ các câu thoại, thuật ngữ UI, danh xưng và quy tắc tự sửa lỗi OCR. Mọi câu được Gemini Flash-Lite dịch trên Cloud sẽ tự động lưu ngược vào đây.",
        color = BankMuted,
        fontSize = 14.sp
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BankStatCard("Bản ghi SQLite", "${records.size} mục", Modifier.weight(1f))
        BankStatCard("Kho nạp sẵn", "${ChineseDialogueBank.totalEntries}+ câu", Modifier.weight(1f))
        BankStatCard("Đã sửa lỗi OCR", "$ocrCorrectedCount lần", Modifier.weight(1f))
    }

    // OCR Pre-correction & <1ms Bank Lookup Tester
    Card(colors = CardDefaults.cardColors(containerColor = BankPanel), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, tint = BankMint)
                Text("Bộ lọc Tiền xử lý & Tự sửa lỗi OCR (<1ms)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Tự động sửa các ký tự OCR thường nhận diện nhầm (ví dụ: 帅兄 → 师兄, I → ！, 己经 → 已经, LeveI Up → Level Up) trước khi tra cứu Kho thoại.",
                color = BankMuted,
                fontSize = 12.sp
            )
            OutlinedTextField(
                value = ocrTestInput,
                onValueChange = { ocrTestInput = it },
                label = { Text("Nhập chuỗi OCR bị lỗi hoặc câu cần kiểm tra") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("ocr_precorrection_test_input")
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("帅兄，小心I", "LeveI Up", "这不叮能！", "道友請留步", "INVENT0RY").forEach { sample ->
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
                    val catText = bankMatchPreview?.let { BankCategory.displayName(it.category) } ?: "Kho nạp sẵn"
                    val freqText = bankMatchPreview?.usageFrequency ?: 1
                    Text(
                        "Khớp Kho tức thì (<1ms): $matchedText",
                        color = BankMint,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("dialogue_bank_test_result")
                    )
                    Text("Thể loại: $catText • Tần suất sử dụng: $freqText lần", color = BankAmber, fontSize = 12.sp)
                } else {
                    Text(
                        "Chưa có trong Kho • Chế độ 1 sẽ dịch bằng ML Kit Offline; Chế độ 2 sẽ gọi Gemini Flash-Lite 1 lần rồi tự động lưu vào SQLite.",
                        color = BankMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("dialogue_bank_test_result")
                    )
                }
            }
        }
    }

    // Search, Filter & Add Record Controls
    Card(colors = CardDefaults.cardColors(containerColor = BankPanel), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Quản lý Kho bản dịch động (SQLite)", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
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
                    Spacer(Modifier.width(6.dp))
                    Text(if (showEditor) "Đóng" else "Thêm vào Kho", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                    Text("Thêm / Cập nhật bản ghi trong Kho SQLite", color = BankMint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    OutlinedTextField(
                        value = editingSource,
                        onValueChange = { editingSource = it },
                        label = { Text("Câu nguồn chuẩn (câu_nguồn)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("bank_editor_source_input")
                    )
                    OutlinedTextField(
                        value = editingTranslation,
                        onValueChange = { editingTranslation = it },
                        label = { Text("Bản dịch tiếng Việt (bản_dịch)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("bank_editor_translation_input")
                    )
                    Text("Thể loại (thể_loại):", color = BankMuted, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            BankCategory.UI to "UI (Giao diện)",
                            BankCategory.PRONOUN to "Danh xưng",
                            BankCategory.DIALOGUE to "Câu thoại thường"
                        ).forEach { (catKey, catLabel) ->
                            FilterChip(
                                selected = editingCategory == catKey,
                                onClick = { editingCategory = catKey },
                                label = { Text(catLabel, fontSize = 12.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = editingOcrErrors,
                        onValueChange = { editingOcrErrors = it },
                        label = { Text("Lỗi OCR hay gặp (ngăn cách bằng | hoặc sai->đúng)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("bank_editor_ocr_errors_input")
                    )
                    Button(
                        onClick = {
                            if (editingSource.isNotBlank() && editingTranslation.isNotBlank()) {
                                SelfLearningDatabase.learnOrUpsert(
                                    context = context,
                                    sourceText = editingSource,
                                    translatedText = editingTranslation,
                                    category = editingCategory,
                                    commonOcrErrors = editingOcrErrors.trim()
                                )
                                statusBanner = "Đã lưu '${editingSource.trim()}' vào Kho thoại SQLite."
                                showEditor = false
                                refreshTrigger++
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
                label = { Text("Tìm kiếm câu nguồn, bản dịch hoặc lỗi OCR…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bank_search_input")
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "ALL" to "Tất cả",
                    BankCategory.UI to "UI (Giao diện)",
                    BankCategory.PRONOUN to "Danh xưng",
                    BankCategory.DIALOGUE to "Câu thoại thường"
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
                            SelfLearningDatabase.delete(context, record.sourceText)
                            statusBanner = "Đã xóa '${record.sourceText}' khỏi Kho thoại."
                            refreshTrigger++
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
