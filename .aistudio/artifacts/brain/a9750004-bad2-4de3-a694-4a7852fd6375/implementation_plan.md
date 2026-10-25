# Dịch Độc Lập Phụ Đề Tiếng Trung (ZH → VI) Siêu Tốc ≤ 1000ms Trên Android & BlueStacks

Giải pháp dịch phụ đề trực tiếp độc lập hoàn toàn trên thiết bị Android và trình giả lập **BlueStacks** (không cần kết nối PC), chuyên biệt hóa 100% cho cặp ngôn ngữ **Tiếng Trung (Giản thể & Phồn thể) → Tiếng Việt**, chống tràn RAM tuyệt đối bằng **SQLite B-Tree Index + LruCache** và ép độ trễ toàn trình (End-to-End Latency) xuống **≤ 1000ms**.

## User Review & Critical Decisions

> [!IMPORTANT]
> Các quyết định kiến trúc và trải nghiệm người dùng đã được xác nhận và khóa chặt theo yêu cầu kỹ thuật:

- **Confirmed Decision 1 (Giao diện & Phạm vi hoạt động)**: Chuyển đổi hoàn toàn giao diện chính sang **Dịch độc lập trực tiếp trên Android / BlueStacks** với 2 chế độ dịch rõ ràng (**Chế độ 1: Kho Phụ Đề + Gemini Hybrid** và **Chế độ 2: Chỉ dùng Gemini Direct**), loại bỏ sự phụ thuộc vào máy tính.
- **Confirmed Decision 2 (Tối giản ngôn ngữ ZH → VI)**: Loại bỏ toàn bộ thư viện ML Kit OCR ngoại ngữ dư thừa (Anh, Nhật, Hàn) và bộ dịch ML Kit On-Device nặng nề trong `build.gradle.kts`, chỉ giữ lại duy nhất **`com.google.mlkit:text-recognition-chinese`** (hỗ trợ cả chữ Hán Giản thể và Phồn thể) để giảm tối đa dung lượng APK và mức tiêu thụ RAM.
- **Confirmed Decision 3 (Kiến trúc Kho phụ đề SQLite Anti-OOM + Self-Learning)**: Lưu trữ kho phụ đề Tiếng Trung (phim ảnh, cổ trang, tu tiên, kiếm hiệp, hiện đại) trong tệp tài nguyên nén nhẹ (`assets/zh_vi_subtitles.tsv`) và nạp vào cơ sở dữ liệu **SQLite (`subtitle_bank`)** trên `Dispatchers.IO` với chỉ mục B-Tree **`CREATE INDEX idx_zh_hash ON subtitle_bank(normalized_hash)`**. Trên RAM chỉ giữ **`android.util.LruCache` tối đa 300 câu** (< 2MB RAM), tự động học câu mới từ Gemini (**Self-Learning Cache**) và cho phép người dùng xem thống kê / tra cứu / xóa kho.

---

## 1. Overview & Core Concept

- **What It Does**: Ứng dụng bắt khung hình vùng phụ đề trực tiếp trên điện thoại Android hoặc giả lập BlueStacks, nhận diện chữ Hán (Giản thể / Phồn thể) trong 40–80ms và dịch sang tiếng Việt tự nhiên trong < 1000ms bằng sự kết hợp giữa **Kho phụ đề SQLite B-Tree (< 5ms)** và **Gemini API tốc độ cao (`thinkingBudget = 0`)**.
- **Target Audience / Persona**: Người xem phim Trung Quốc, hoạt hình 3D (donghua), đọc truyện tranh (manhua tu tiên, kiếm hiệp, cổ trang, hiện đại) hoặc chơi game tiếng Trung ngay trên điện thoại và BlueStacks cần bản dịch tiếng Việt mượt, đúng xưng hô Hán-Việt và không bao giờ gây đơ cảm ứng hay tràn bộ nhớ.
- **Key Value**:
  - **Độ trễ toàn trình ≤ 1000ms**: Tối ưu từ khâu đọc bộ đệm `ImageReader` (chỉ cắt ROI phụ đề, chuẩn `rowStride`/`pixelStride`, thu nhỏ chiều cao về `160px–240px`, so khớp pixel Frame Diffing 300ms) đến khâu tra kho SQLite B-Tree (`< 5ms`) và gọi Gemini (`thinkingBudget = 0`, HTTP/2 Keep-Alive, System Prompt < 40 tokens).
  - **Chống tràn RAM & Chống đơ cảm ứng trên BlueStacks**: Không giữ `HashMap` khổng lồ trên RAM; cửa sổ phụ đề hoàn toàn trong suốt (`Color.TRANSPARENT`) và cho phép bấm xuyên qua (`FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`).

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Màn hình chính (Dịch Độc Lập ZH → VI)**:
     - Hiển thị trạng thái sẵn sàng của bộ nhận diện chữ Hán (`ML Kit Chinese OCR`) và số lượng câu trong Kho phụ đề SQLite (câu gốc nạp sẵn + câu tự học Self-Learning).
     - Bộ chọn **2 Chế độ dịch độc lập**:
       - **Chế độ 1: Kho Phụ Đề + Gemini (Hybrid Siêu Tốc)**: Tra cứu SQLite + LruCache (< 5ms, khớp nguyên câu hoặc tách ghép vế thông minh); nếu chưa có mới gọi Gemini và tự động lưu ngầm vào SQLite (`Self-Learning Cache`).
       - **Chế độ 2: Chỉ dùng Gemini (Gemini Direct)**: Bỏ qua kho mẫu, gửi câu chữ Hán kèm 1 câu ngữ cảnh liền trước lên Gemini để dịch sát mạch phim nhất, giữ `LruCache(100)` trên RAM để không gọi lại API khi dòng phụ đề đang đứng yên.
     - Thanh đo độ trễ trực tiếp theo thời gian thực: **`OCR: 52ms | Dịch: 4ms (Kho) / 310ms (Gemini) | Tổng: 56ms / 362ms`**.
  2. **Màn hình Kho Phụ Đề SQLite & Kiểm Thử Tốc Độ**:
     - Cho phép tra cứu thử tức thì (< 5ms) câu thoại Giản thể / Phồn thể từ SQLite, xem số câu đã tự học (Self-Learning), thêm câu mới hoặc xóa các câu tự học / làm mới bộ nhớ đệm.
     - Nút chạy nhanh **Benchmark Độ Trễ & RAM** ngay trên giao diện để kiểm chứng tốc độ tra kho (< 10ms) và luồng dịch.
  3. **Lớp phủ Phụ đề (Overlay) & Thanh công cụ nổi**:
     - Cửa sổ hiển thị phụ đề tiếng Việt có nền khung trong suốt (`Color.TRANSPARENT`), gắn cứng cờ `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` để mọi thao tác chạm trong game/video trên điện thoại và BlueStacks đều xuyên qua 100%.
     - Thanh công cụ nhỏ gọn hiển thị thông số **`OCR: XXms | Dịch: YYms | Tổng: ZZms`** cùng nút chọn **VÙNG** phụ đề (mặc định ở 1/3 dưới màn hình) và nút Tạm dừng / Dừng.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Utilitarian Cyber-Cinema Dark — tối giản, sắc nét, ưu tiên khả năng đọc nhanh và theo dõi thông số kỹ thuật (telemetry) thời gian thực.
  - *Color Palette & Mood*: Nền tối sâu (`#0B1218`), bề mặt thẻ (`#14212B`), điểm nhấn Xanh Ngọc Bạc Hà (`#6EE7C7`) cho trạng thái tốc độ cao ≤ 1s, và Vàng Hổ Phách (`#F0B976`) cho chỉ số cảnh báo.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Lưu trữ Kho Phụ Đề trong SQLite có chỉ mục B-Tree + `LruCache(300)` thay vì `Map` trên RAM**
  - *Chosen Approach*: Đóng gói kho câu thoại Tiếng Trung trong tệp `assets/zh_vi_subtitles.tsv`, khởi tạo bảng SQLite `subtitle_bank` với chỉ mục `CREATE INDEX idx_zh_hash ON subtitle_bank(normalized_hash)` trên `Dispatchers.IO`, kết hợp `LruCache<String, String>(300)` trên RAM.
  - *Why*: Ngăn chặn hoàn toàn lỗi tràn bộ nhớ (`OutOfMemoryError`) khi kho phụ đề mở rộng hàng nghìn câu thông qua cơ chế tự học (`Self-Learning Cache`), giữ mức sử dụng RAM của bộ nhớ đệm luôn dưới 2MB mà vẫn đạt tốc độ truy vấn < 5ms.
- **Decision 2: Cắt đúng vùng chọn phụ đề (ROI Cropping) + Chuẩn hóa `rowStride`/`pixelStride` + Downscale chiều cao `160px–240px` + Frame Diffing**
  - *Chosen Approach*: Trích xuất trực tiếp vùng chữ nhật phụ đề (mặc định 1/3 dưới màn hình) từ `Image.Plane` bằng công thức `offset = y * rowStride + x * pixelStride`, so sánh độ lệch pixel (Frame Diffing) với khung hình trước đó trong 300ms, và thu nhỏ chiều cao bitmap vùng cắt về `160px–240px` trước khi đưa vào `ChineseTextRecognizer`.
  - *Why*: Trên trình giả lập BlueStacks, `rowStride` thường chứa phần đệm (padding) lớn hơn `width * pixelStride`. Xử lý chính xác `rowStride` giúp ảnh không bị méo/đen hình, đồng thời chiều cao `160px–240px` giảm thời gian ML Kit Chinese OCR xuống chỉ còn **40ms–80ms**.
- **Decision 3: Tinh gọn cuộc gọi Gemini API (< 600ms) với `thinkingBudget = 0` và ngữ cảnh 1 câu liền trước**
  - *Chosen Approach*: Gọi trực tiếp REST API với cấu hình `thinkingBudget = 0`, giữ ấm kết nối bằng `OkHttpClient` HTTP/2 Connection Pool (Keep-Alive 5 phút), dùng System Prompt siêu gọn (< 40 tokens) và chỉ gửi kèm tối đa 1 câu thoại liền trước.
  - *Why*: Loại bỏ hoàn toàn thời gian trễ sinh token suy nghĩ ẩn (thinking tokens) và giảm kích thước payload, đảm bảo tổng thời gian `OCR (50ms) + Gemini (350–550ms)` luôn nằm dưới ngưỡng **1000ms**.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Pipeline Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                 ScreenCaptureService (HandlerThread "OCRFrames")             │
│  ImageReader (maxImages = 2, RGBA_8888, AtomicBoolean isProcessing)          │
│       │                                                                      │
│       ▼                                                                      │
│  1. ROI Cropping (1/3 dưới màn hình, tính chuẩn rowStride & pixelStride)     │
│       │                                                                      │
│       ▼                                                                      │
│  2. Frame Diffing (Độ lệch pixel < ngưỡng trong 300ms ──► Bỏ qua OCR ngay)   │
│       │ (Có thay đổi)                                                        │
│       ▼                                                                      │
│  3. ROI Downscaling (Thu nhỏ chiều cao vùng phụ đề về 160px–240px)           │
│       │                                                                      │
│       ▼                                                                      │
│  4. ML Kit Chinese OCR (Giản thể & Phồn thể · 40ms–80ms)                     │
└───────┬──────────────────────────────────────────────────────────────────────┘
        │ Văn bản chữ Hán (ZH)
        ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                   StandaloneTranslator (2 Chế Độ Độc Lập)                    │
│                                                                              │
│  ┌────────────────────────────────────┐  ┌────────────────────────────────┐  │
│  │ CHẾ ĐỘ 1: KHO PHỤ ĐỀ + GEMINI      │  │ CHẾ ĐỘ 2: CHỈ DÙNG GEMINI      │  │
│  │ 1. LruCache(300) (< 0.1ms)         │  │ 1. LruCache(100) câu đang hiện │  │
│  │ 2. SQLite B-Tree (idx_zh_hash <5ms)│  │ 2. Gửi câu ZH + 1 câu ngữ cảnh │  │
│  │    (Exact Match + Ghép cụm từ)     │  │    liền trước lên Gemini API   │  │
│  │ 3. Nếu chưa có ──► Gọi Gemini API  │  │    (thinkingBudget = 0)        │  │
│  │    & tự lưu ngầm vào SQLite        │  │                                │  │
│  │    (Self-Learning Cache)           │  │                                │  │
│  └─────────────────┬──────────────────┘  └───────────────┬────────────────┘  │
└────────────────────┼─────────────────────────────────────┼───────────────────┘
                     └──────────────────┬──────────────────┘
                                        ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                        TranslationOverlay (BlueStacks/Android)               │
│  • Cửa sổ Phụ đề: Color.TRANSPARENT + FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE│
│  • Thanh trạng thái: "OCR: 52ms | Dịch: 310ms | Tổng: 362ms"                 │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Data Model & SQLite Anti-OOM Schema

- **Bảng `subtitle_bank` (SQLite trên `Dispatchers.IO`)**:
  - `id INTEGER PRIMARY KEY AUTOINCREMENT`
  - `normalized_hash INTEGER NOT NULL` (Mã băm 64-bit FNV-1a / CRC của chuỗi chữ Hán đã chuẩn hóa Phồn → Giản và lược bỏ dấu câu)
  - `normalized_zh TEXT NOT NULL UNIQUE` (Chuỗi chữ Hán chuẩn hóa để kiểm tra chống đụng độ mã băm)
  - `vi_text TEXT NOT NULL` (Bản dịch tiếng Việt chuẩn ngữ cảnh)
  - `category TEXT NOT NULL` (`preloaded` hoặc `learned` cho các câu tự học từ Gemini)
  - `updated_at INTEGER NOT NULL`
  - **Chỉ mục B-Tree bắt buộc**:
    - `CREATE INDEX idx_zh_hash ON subtitle_bank(normalized_hash)`
- **Bộ đệm RAM**:
  - Chế độ 1 (Hybrid): `android.util.LruCache<String, String>(300)` lưu tối đa 300 câu vừa tra cứu.
  - Chế độ 2 (Gemini Direct): `android.util.LruCache<String, String>(100)` lưu 100 câu gần nhất để chống gọi lặp API khi phụ đề đứng yên.

### Verification & Automated Benchmark Suite

- Bộ kiểm thử tự động (`ChineseSubtitleBenchmarkTest`) xác minh trực tiếp trước khi hoàn thành:
  1. **Tốc độ tra kho SQLite (`idx_zh_hash`) + `LruCache(300)`**: Đo thời gian tra cứu chính xác và ghép cụm từ trên kho phụ đề Tiếng Trung đạt **< 10ms** và mức tăng bộ nhớ RAM **< 5MB**.
  2. **Tốc độ & Ngữ cảnh 10 câu thoại phim Tiếng Trung đa dạng** (cổ trang, tu tiên, kiếm hiệp, hiện đại, câu ngắn, câu dài có ngữ cảnh nối tiếp): Xác nhận bản dịch tiếng Việt tự nhiên, đúng xưng hô (`Sư tôn`, `Sư huynh`, `Bệ hạ`, `Cô nương`...), tự động lưu vào `Self-Learning Cache` ở Chế độ 1, và tổng thời gian xử lý trung bình (`OCR + Tra kho / Gemini`) đạt **≤ 1000ms**.
