# Tái Cấu Trúc Ứng Dụng Dịch Màn Hình Độc Lập Trên Android (Standalone On-Device & Kho Thoại Tự Học SQLite)

Tái cấu trúc toàn diện ứng dụng thành hệ thống dịch màn hình hoạt động độc lập 100% trên thiết bị Android (không phụ thuộc máy tính Windows), tối giản còn 2 chế độ dịch thông minh, chỉ sử dụng mô hình siêu nhẹ `gemini-3.1-flash-lite-preview`, giải phóng tức thì bộ nhớ hình ảnh (`Bitmap.recycle()`) và tích hợp Kho thoại tự học SQLite (Self-Learning Cache) kèm bộ lọc tự sửa lỗi OCR.

## User Review & Critical Decisions

> [!IMPORTANT]
> Các quyết định thiết kế dưới đây đã được xác nhận và ưu tiên áp dụng cho kiến trúc độc lập:

- **Quyết định đã xác nhận 1 (Thanh điều hướng & Quản lý Kho thoại)**: Loại bỏ hoàn toàn tab Kết nối PC và thay thế bằng tab **Kho thoại tự học** ở thanh điều hướng dưới để người dùng tra cứu, lọc theo thể loại (`UI`, `Danh xưng`, `Câu thoại thường`), chỉnh sửa bản dịch và quản lý quy tắc sửa lỗi OCR ngay trên điện thoại.
- **Quyết định đã xác nhận 2 (Xử lý cụm từ ngắn trong Chế độ 2 - Kho kết hợp Gemini)**: Đặt ngưỡng độ dài ký tự linh hoạt (mặc định `≤ 15` ký tự, có thanh trượt điều chỉnh). Nếu câu nằm trong Kho thoại SQLite hoặc từ điển nạp sẵn thì trả về tức thì (`< 1ms`). Nếu câu ngắn chưa có trong Kho, hệ thống gọi Gemini Flash-Lite 1 lần duy nhất để tự học và lưu vĩnh viễn vào SQLite (hoặc dùng ML Kit Offline ngay nếu không có mạng/API Key).
- **Quyết định đã xác nhận 3 (Bộ lọc tiền xử lý tự sửa lỗi OCR - OCR Pre-correction)**: Khởi tạo sẵn bộ cặp ký tự OCR dễ nhận diện nhầm (ví dụ chữ Hán tương tự hình dáng, ký tự viền đồ họa dính chữ) trong SQLite, đồng thời tự động học quy tắc sửa lỗi và cho phép người dùng thêm/sửa thủ công.

---

## 1. Overview & Core Concept

- **What It Does**: Ứng dụng dịch màn hình trực tiếp trên Android bằng dịch vụ cửa sổ nổi (`Floating Overlay Service`) và `MediaProjection API`. Toàn bộ quy trình chụp vùng màn hình, thu gọn ảnh (`Fast OCR Downscale`), đọc chữ bằng ML Kit OCR, tiền xử lý sửa lỗi OCR, tra cứu Kho thoại SQLite (`< 1ms`) và gọi dịch đám mây (`gemini-3.1-flash-lite-preview`) đều diễn ra ngay trên điện thoại mà không cần kết nối tới máy tính Windows.
- **Target Audience / Persona**: Người dùng xem phim, đọc truyện tranh (manhua/manga), chơi game trên điện thoại cần tốc độ phản hồi dưới 1 giây, tiết kiệm pin/RAM tối đa (không nóng máy, không giật lag) và chi phí token thấp nhất.
- **Key Value**:
  - **Độc lập 100%**: Loại bỏ hoàn toàn các luồng nền WebSocket/LAN kết nối PC trong dịch vụ chụp màn hình.
  - **Tối giản 2 chế độ dịch**: Dễ hiểu, chuyển đổi 1 chạm giữa *Chế độ 1: Dịch thuần từ Kho (Offline)* và *Chế độ 2: Kho kết hợp Gemini (Hybrid thông minh)*.
  - **Kho thoại SQLite tự học (Self-Learning Cache)**: Càng dùng càng nhanh và tiết kiệm — mọi câu Gemini dịch thành công đều tự động phân loại và lưu vào cơ sở dữ liệu SQLite cục bộ.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Tab 1 — Dịch trực tiếp**:
     - Hiển thị bộ chọn **2 Chế độ dịch chính** dạng thẻ trực quan:
       - **Chế độ 1: Dịch thuần từ Kho (Kho thoại Offline)**: Chỉ khớp Kho thoại SQLite + ML Kit Offline (`0 token`, không gọi API ngoài).
       - **Chế độ 2: Kho kết hợp Gemini (Hybrid thông minh)**: Tra cứu Kho thoại SQLite `< 1ms` cho các cụm từ đã biết hoặc ngắn `≤ N` ký tự; tự động gọi `gemini-3.1-flash-lite-preview` khi câu vượt ngưỡng ký tự hoặc chưa có trong Kho rồi tự lưu ngược vào SQLite.
     - Nút **Bật dịch màn hình** kích hoạt ngay cửa sổ nổi và `MediaProjection` độc lập.
     - Thẻ thống kê hiệu năng thời gian thực: Độ trễ (`ms`), số câu trong Kho SQLite, số lượt khớp Kho (`< 1ms`), số lỗi OCR đã tự sửa và số lượt gọi API tiết kiệm được.
  2. **Tab 2 — Kho thoại tự học (Thay thế tab Kết nối PC)**:
     - Thanh tìm kiếm tức thì và bộ lọc theo thể loại: `Tất cả`, `UI (Giao diện)`, `Danh xưng`, `Câu thoại thường`.
     - Danh sách các bản ghi trong SQLite hiển thị: Câu nguồn, Bản dịch tiếng Việt, Nhãn thể loại, Cặp lỗi OCR hay gặp (`lỗi_OCR_hay_gặp`), và Tần suất sử dụng (`tần_suất_sử_dụng`).
     - Hỗ trợ thêm mới câu thoại/quy tắc sửa lỗi OCR, chỉnh sửa bản dịch hoặc xóa bản ghi.
  3. **Tab 3 — Tùy chỉnh**:
     - Thanh trượt điều chỉnh **Ngưỡng độ dài ký tự Hybrid** (mặc định `15` ký tự, phạm vi `5..60` ký tự) cho Chế độ 2.
     - Cấu hình ngôn ngữ nguồn (Trung, Nhật, Hàn, Anh), vùng chụp phụ đề, kích thước chữ nổi, bật/tắt **Fast OCR Downscale** và khóa API Gemini Flash-Lite.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Giao diện tối tinh gọn (Precision Dark Utility), độ tương phản cao, giảm thiểu hiệu ứng thừa để tiết kiệm GPU khi chạy cùng game/video.
  - *Color Palette & Mood*: Nền chính `#0B1218`, bề mặt thẻ `#14212B`, điểm nhấn xanh bạc hà `#6EE7C7` cho trạng thái Kho thoại `< 1ms` và vàng hổ phách `#F0B976` cho nhãn phân loại thể loại.
  - *Component Styling & Layout*: Thiết kế theo chuẩn Material Design 3, vùng chạm tối thiểu `48dp`, thanh nổi (`TranslationOverlay`) tối giản đã loại bỏ nút `PRO` rườm rà.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Loại bỏ hoàn toàn Gemini Pro, chỉ giữ duy nhất Gemini Flash-Lite (`gemini-3.1-flash-lite-preview`)**
  - *Chosen Approach*: Cố định engine đám mây vào `gemini-3.1-flash-lite-preview` với `thinkingLevel = MINIMAL` và khóa `maxOutputTokens` (`100..250`), xóa bỏ mọi tùy chọn và nút gọi Gemini Pro.
  - *Why*: Giảm tối đa độ trễ phản hồi xuống dưới 1 giây, loại bỏ nguy cơ tốn chi phí cao đột biến và giữ giao diện cửa sổ nổi gọn nhẹ.
- **Decision 2: Cơ sở dữ liệu SQLite cục bộ (Self-Learning Cache) kết hợp bộ đệm RAM `LruCache`**
  - *Chosen Approach*: Sử dụng SQLite cục bộ (`SQLiteOpenHelper` chuẩn của Android kết hợp lớp đệm RAM `LruCache` đồng bộ) lưu bảng `translation_bank` (`source_text`, `translated_text`, `category`, `common_ocr_errors`, `usage_frequency`, `updated_at`) và bảng/quy tắc sửa lỗi OCR.
  - *Why*: Tốc độ truy vấn `< 1ms` trên luồng OCR mà không làm nặng APK, bền vững qua các lần khởi động lại máy, tự động tăng `usage_frequency` mỗi khi câu thoại xuất hiện lại.
- **Decision 3: Giải phóng bộ nhớ Bitmap tức thì (`Bitmap.recycle()`) & Fast OCR Downscale**
  - *Chosen Approach*: Chỉ trích xuất đúng vùng chữ (ROI) từ `ImageReader`, thu gọn kích thước ảnh (`Fast OCR Downscale`) trước khi đưa vào ML Kit OCR và gọi `bitmap.recycle()` ngay trong khối `finally` sau khi nhận diện xong; đồng thời ngắt toàn bộ tiến trình kết nối PC trong dịch vụ chụp màn hình.
  - *Why*: Ngăn chặn hiện tượng tràn bộ nhớ đồ họa (OOM), giảm tải CPU/GPU và giữ máy luôn mát khi dịch liên tục.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌───────────────────────────────────────────────────────────────────────────┐
│                  STANDALONE ANDROID SCREEN TRANSLATOR                     │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  [MediaProjection + ImageReader]                                          │
│         │ (Chỉ cắt vùng ROI + Fast OCR Downscale)                         │
│         ▼                                                                 │
│  [ML Kit On-Device OCR] ──(finally)──► [Bitmap.recycle() giải phóng ngay] │
│         │                                                                 │
│         ▼                                                                 │
│  [Bộ lọc Tiền xử lý & Tự sửa lỗi OCR (OCR Pre-correction)]                │
│   • Lọc ký tự rác đồ họa (| / \ _ : ;)                                    │
│   • Áp dụng cặp ký tự sửa lỗi từ Kho SQLite (common_ocr_errors)           │
│         │                                                                 │
│         ▼                                                                 │
│  [Kho Thoại Tự Học SQLite (SelfLearningDatabase + RAM LruCache)]          │
│   • Bảng: câu_nguồn | bản_dịch | thể_loại | lỗi_OCR_hay_gặp | tần_suất    │
│   • Nạp sẵn 320+ câu thoại phim/truyện/game + Từ điển UI Offline          │
│         │                                                                 │
│         ├──► Khớp Kho (< 1ms) ──► Tăng tần_suất_sử_dụng ──► Hiển thị ngay │
│         │                                                                 │
│         └──► Chưa có trong Kho:                                           │
│               ├─► [Chế độ 1: Dịch thuần từ Kho]                           │
│               │     └─► Dịch bằng ML Kit Offline (0 API call)             │
│               │                                                           │
│               └─► [Chế độ 2: Kho kết hợp Gemini (Ngưỡng ≤ N ký tự)]       │
│                     ├─► Gọi Gemini Flash-Lite (thinkingLevel = MINIMAL)   │
│                     ├─► Tự động phân loại thể_loại (UI/Danh xưng/Thoại)   │
│                     └─► Tự động lưu ngược vào Kho SQLite cho lần sau      │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State (`SelfLearningDatabase`)

1. **Bảng `translation_bank` (Kho bản dịch động)**:
   - `source_text` (`TEXT PRIMARY KEY`): Câu nguồn đã chuẩn hóa.
   - `translated_text` (`TEXT NOT NULL`): Bản dịch tiếng Việt.
   - `category` (`TEXT NOT NULL`): Thể loại tự động hoặc người dùng gán:
     - `UI`: Giao diện, nút bấm, thông báo hệ thống (`Start`, `Level Up`, `Nhận vật phẩm`, v.v.).
     - `PRONOUN`: Danh xưng, tên gọi, chức danh (`Sư phụ`, `Tiểu sư muội`, `Bệ hạ`, `Đạo hữu`, v.v.).
     - `DIALOGUE`: Câu thoại thường trong phim, truyện, game.
   - `common_ocr_errors` (`TEXT NOT NULL`): Các biến thể/ký tự OCR thường quét sai của câu này hoặc quy tắc thay thế (phân tách bằng dấu `,` hoặc mẫu `sai->đúng`).
   - `usage_frequency` (`INTEGER NOT NULL DEFAULT 1`): Tần suất xuất hiện, tự động cộng thêm `1` mỗi lần khớp thành công.
   - `updated_at` (`LONG`): Thời điểm cập nhật gần nhất.

2. **Bộ lọc tiền xử lý tự sửa lỗi OCR (`preprocessAndCorrectOcr`)**:
   - Trước khi tra cứu Kho hoặc gửi đi dịch, chuỗi văn bản thô từ ML Kit OCR đi qua:
     1. Chuẩn hóa dấu câu và khoảng trắng thừa giữa các ký tự CJK.
     2. Đối chiếu danh sách `common_ocr_errors` nạp sẵn (ví dụ các ký tự Hán/Latin bị ML Kit nhận nhầm do viền phụ đề như `I` $\rightarrow$ `l`, `0` $\rightarrow$ `O` trong từ khóa, hoặc các cặp chữ Hán dễ nhầm trong thoại phim/truyện như `己/已`, `人/入`, `大/太` trong cụm cố định) và các biến thể lỗi đã lưu trong `translation_bank`.
     3. Nếu chuỗi OCR khớp với một biến thể nằm trong trường `common_ocr_errors` của bản ghi trong Kho, hệ thống tự động sửa về `source_text` chuẩn và trả về ngay `translated_text` trong `< 1ms`.

### Interactive Component & State Mapping

- **Chọn Chế độ dịch (Tab Dịch trực tiếp & Tùy chỉnh)**:
  - Chỉ hiển thị 2 chế độ: ` MODE_OFFLINE_BANK ("bank_only")` và `MODE_HYBRID_GEMINI ("hybrid_gemini")`.
  - Khi người dùng đổi chế độ hoặc kéo thanh trượt ngưỡng ký tự (`hybridCharThreshold`, mặc định `15`), `AppSettings` lưu tức thì và `ScreenCaptureService` áp dụng ngay ở khung hình kế tiếp.
- **Quản lý Kho thoại tự học (Tab Kho thoại)**:
  - Tìm kiếm theo từ khóa nguồn/bản dịch, lọc theo `UI` / `Danh xưng` / `Câu thoại thường`, sắp xếp theo `Tần suất sử dụng` giảm dần.
  - Hộp thoại thêm/chỉnh sửa bản ghi cho phép sửa `Bản dịch`, đổi `Thể loại`, và nhập thêm `Lỗi OCR hay gặp` (`common_ocr_errors`) để bộ lọc tiền xử lý tự động nhận diện.
- **Quản lý vòng đời Bitmap & Dịch vụ**:
  - Trong `ScreenCaptureService`, mỗi `CapturedFrame` sau khi hoàn tất `ocr.process(...)` đều được gọi `frame.bitmap.recycle()` trong khối `finally`. Không khởi tạo `TranslationClient` hay bất kỳ kết nối mạng LAN/PC nào trong dịch vụ.
