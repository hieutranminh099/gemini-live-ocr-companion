# Tối ưu Độ trễ Siêu tốc & Kho Hội thoại Tiếng Trung Nạp sẵn trên Điện thoại

Nâng cấp bộ máy dịch tự động độc lập trên điện thoại để đạt tốc độ phản hồi tức thì (`< 1ms` với các câu/cụm hội thoại phổ biến được nạp sẵn và `< 150–350ms` cho câu mới), kết hợp kho dữ liệu hội thoại tiếng Trung chuyên sâu cho phim Hoa ngữ, hoạt hình (donghua), truyện tranh (manhua) và game.

## User Review & Critical Decisions

> [!IMPORTANT]
> Các quyết định thiết kế dưới đây đã được thống nhất dựa trên lựa chọn của bạn trước khi triển khai:

- **Kho hội thoại nạp sẵn tập trung chuyên sâu vào tiếng Trung (Giản thể & Phồn thể)**: Tích hợp sẵn bộ dữ liệu lớn các câu thoại, thành ngữ, xưng hô, câu cảm thán, thuật ngữ tu tiên/cổ trang/hiện đại và giao diện thường gặp trong phim Hoa ngữ, truyện tranh và game để trả kết quả ngay lập tức mà không tốn thời gian suy luận AI.
- **Đối chiếu thông minh dưới 1ms (Khớp chính xác, Chuẩn hóa bỏ dấu câu & Ghép cụm từ/Mẫu câu)**: Tự động loại bỏ dấu câu/ký tự nhiễu OCR ở hai đầu, đối chiếu toàn câu hoặc lắp ghép các vế câu ngắn và mẫu câu thông dụng (ví dụ: mẫu câu hỏi, cảm thán, xưng hô + hành động) trong bộ nhớ RAM.
- **Tối ưu hóa toàn diện đường truyền dịch tự động độc lập**:
  1. **Zero-Debounce (`0ms`) trên điện thoại**: Bỏ hoàn toàn thời gian chờ ổn định chữ đối với các câu khớp kho nạp sẵn/cache và giảm chờ xuống mức tối thiểu cho dịch cục bộ ML Kit trên máy.
  2. **Thu phóng thông minh ảnh OCR (Adaptive ROI Downscaling)**: Tự động thu gọn kích thước ảnh chụp dải phụ đề về độ phân giải tối ưu trước khi đưa vào bộ nhận diện chữ, giúp giảm 35–50% thời gian chạy OCR cục bộ mà vẫn giữ độ nét của chữ Hán.
  3. **Làm ấm trước (Pre-Warm) bộ nhận diện OCR & ML Kit ngay khi mở ứng dụng**: Khởi tạo sẵn mô hình nhận diện chữ Hán và bộ dịch trên nền để loại bỏ độ trễ khởi động ở khung hình đầu tiên.

---

## 1. Overview & Core Concept

- **What It Does**:
  - Nạp sẵn vào bộ nhớ ứng dụng hàng trăm câu hội thoại, mẫu câu giao tiếp và cụm từ tiếng Trung xuất hiện với tần suất cao nhất trong phim ảnh, truyện tranh (manhua) và game (hiện đại, cổ trang, kiếm hiệp/tu tiên, học đường, hành động).
  - Khi người dùng xem phim hoặc đọc truyện có phụ đề tiếng Trung, bộ đối chiếu siêu tốc (`< 1ms`) sẽ nhận diện ngay lập tức câu thoại hoàn chỉnh hoặc ghép các vế câu quen thuộc để hiển thị bản dịch tiếng Việt tự nhiên ngay trên khung hình đầu tiên mà không cần chờ ML Kit hay Gemini.
  - Với những câu dài và phức tạp chưa có trong kho nạp sẵn, đường truyền hình ảnh và mô hình dịch cục bộ đã được làm ấm sẵn cùng ảnh chụp thu gọn tối ưu giúp giảm tối đa thời gian từ lúc chữ xuất hiện đến lúc vẽ bản dịch.
- **Target Audience / Persona**: Người dùng xem phim Hoa ngữ, donghua, đọc manhua hoặc chơi game tiếng Trung trực tiếp trên điện thoại cần phụ đề tiếng Việt hiện ra gần như tức thời theo từng lời thoại.
- **Key Value**: Đưa độ trễ hiển thị của các câu thoại thông dụng về gần như bằng `0ms` sau khi đọc OCR, đồng thời cắt giảm đáng kể thời gian xử lý OCR và dịch máy đối với các câu thoại mới.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Khởi động & Làm ấm tự động**: Ngay khi mở ứng dụng, hệ thống tự động nạp kho hội thoại tiếng Trung vào RAM và làm ấm bộ nhận diện chữ + bộ dịch cục bộ trong nền.
  2. **Dịch tự động tức thì khi xem phim / đọc truyện**: Khi phụ đề tiếng Trung xuất hiện, nếu câu thoại khớp với kho hội thoại nạp sẵn (dù có khác biệt dấu câu như `！`, `？`, `…`, `。` hay khoảng trắng), bản dịch tiếng Việt mượt mà hiển thị ngay tức khắc (`< 1ms` tra cứu, `0ms` chờ debounce).
  3. **Quản lý & Tùy chỉnh Kho Hội thoại Nạp sẵn**: Trên màn hình chính và tab **Tùy chỉnh**, người dùng có thể bật/tắt kho hội thoại tiếng Trung nạp sẵn, xem số lượng mẫu câu đang hoạt động, xem thống kê tỷ lệ khớp tức thì và tìm kiếm/bổ sung thêm câu thoại yêu thích.
- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Giữ vững phong cách Dark Utility hiện đại, sắc nét và tập trung vào thông số độ trễ thời gian thực.
  - *Color Palette & Mood*: Nền tối sâu (`#0B1218`), bề mặt thẻ (`#14212B`), điểm nhấn xanh bạc hà (`#6EE7C7`) cho trạng thái khớp tức thì `< 1ms`, và cam hổ phách (`#F0B976`) cho cảnh báo hoặc trạng thái tải.
  - *Typography & Hierarchy*: Hiển thị rõ ràng thời gian từng chặng (`OCR ms • Kho nạp sẵn <1ms / Cục bộ ms • Tổng khung→vẽ ms`) ngay trên thẻ phiên dịch và thanh phản hồi nổi.
- **Interactive Feedback & Motion**:
  - Hiển thị huy hiệu trạng thái số lượng câu hội thoại tiếng Trung đã nạp sẵn và số lượt khớp tức thì trong phiên dịch.
  - Cho phép thử nhanh (Test thử câu tiếng Trung) hoặc tra cứu nhanh kho câu nạp sẵn ngay trong mục cài đặt.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Kiến trúc đối chiếu 3 lớp cho Kho Hội thoại Tiếng Trung (`< 1ms`)**
  - *Chosen Approach*: Kết hợp (1) **Khớp chính xác sau chuẩn hóa** (bỏ dấu câu `，。！？…—~「」『』` và khoảng trắng thừa, đồng bộ Giản thể/Phồn thể phổ biến), (2) **Ghép vế câu phân tách bằng dấu câu** (ví dụ câu gồm 2 mệnh đề ngăn bởi dấu phẩy hoặc khoảng trắng như `等一下，我马上来` sẽ ghép tức thì từ `等一下` + `我马上来`), và (3) **Khớp mẫu câu động (Template Patterns)** cho các cấu trúc câu thoại lặp lại (`别[X]了`, `你是谁`, `怎么回事`, `[X]大人`, `[X]师兄`, v.v.), đồng thời giữ lại dấu cảm thán/nghi vấn tương ứng ở đầu ra tiếng Việt.
  - *Why*: Phụ đề phim và truyện tranh thường ghép 2 câu ngắn trên cùng một dòng hoặc thay đổi dấu chấm than/dấu hỏi. Cơ chế ghép vế câu và chuẩn hóa giúp tăng tỷ lệ trúng (hit-rate) lên gấp nhiều lần so với chỉ so khớp chuỗi cứng, trong khi vẫn giữ thời gian chạy dưới `1ms`.
  - *Alternatives Considered*: Chỉ khớp chuỗi chính xác 100% (bị trượt khi OCR dính hoặc mất dấu chấm câu) hoặc thay thế từng từ rời rạc cho mọi câu dài (dễ gây dịch word-by-word thiếu tự nhiên).
- **Decision 2: Bỏ Debounce (`0ms`) cho dịch cục bộ & Thu phóng ảnh OCR thông minh**
  - *Chosen Approach*:
    - Kiểm tra kho hội thoại nạp sẵn và bộ nhớ cache **trước** bước chờ debounce. Nếu khớp toàn bộ hoặc các mệnh đề đều nằm trong kho, vẽ ngay lập tức (`0ms` debounce).
    - Khi ở chế độ Độc lập trên điện thoại kết hợp Turbo, giảm thời gian chờ ổn định chữ xuống `0ms` cho lần xuất hiện đầu tiên và chỉ giữ ngưỡng chống nháy nhỏ (`35ms`) khi chữ đang biến đổi liên tục từng khung hình.
    - Với ảnh vùng phụ đề (ROI) có chiều rộng lớn hơn `960px` (hoặc chiều cao lớn), tự động thu phóng ảnh bằng bộ lọc nhanh trước khi đưa vào ML Kit OCR và quy đổi ngược tọa độ khung chữ về kích thước gốc.
  - *Why*: Thời gian chạy ML Kit OCR tỷ lệ thuận với số điểm ảnh. Giảm kích thước vùng ảnh phụ đề quá lớn giúp cắt giảm 30–50ms mỗi khung hình mà không ảnh hưởng độ chính xác nhận diện chữ Hán cỡ phụ đề.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌────────────────────────────────────────────────────────────────────────────┐
│                   DỊCH TỰ ĐỘNG ĐỘC LẬP TRÊN ĐIỆN THOẠI                     │
└─────────────────────────────────────┬──────────────────────────────────────┘
                                      │
                                      ▼
┌────────────────────────────────────────────────────────────────────────────┐
│ 1. THU MÀN HÌNH & THU PHÓNG ẢNH OCR THÔNG MINH (Adaptive ROI Downscaling)  │
│  • Chỉ cắt đúng dải phụ đề (ROI) từ bộ đệm khung hình phần cứng            │
│  • Tự động thu gọn kích thước ảnh nếu bề ngang > 960px (giảm 35-50% ms OCR)│
│  • Bộ nhận diện chữ Hán & ML Kit được làm ấm sẵn (Pre-warmed) trong RAM    │
└─────────────────────────────────────┬──────────────────────────────────────┘
                                      │
                                      ▼
┌────────────────────────────────────────────────────────────────────────────┐
│ 2. BỘ ĐỐI CHIẾU KHO HỘI THOẠI TIẾNG TRUNG NẠP SẴN (< 1ms, 0ms Debounce)    │
│  ┌─────────────────────────┐  ┌────────────────────────┐  ┌──────────────┐ │
│  │ Lớp 1: Khớp chuẩn hóa   │→ │ Lớp 2: Ghép mệnh đề    │→ │ Lớp 3: Mẫu   │ │
│  │ (Bỏ dấu câu, Giản/Phồn) │  │ (Tách theo dấu phẩy/   │  │ câu thoại &  │ │
│  │ Phim, Truyện, Game      │  │ khoảng trắng & nối câu)│  │ cụm từ cố định│ │
│  └─────────────────────────┘  └────────────────────────┘  └──────────────┘ │
└──────────────────┬──────────────────────────────────────┬──────────────────┘
                   │ Khớp hoàn toàn (< 1ms)               │ Chưa có trong kho
                   ▼                                      ▼
┌──────────────────────────────────────┐ ┌───────────────────────────────────┐
│ HIỂN THỊ TỨC THÌ LÊN LỚP PHỦ (0ms)   │ │ 3. DỊCH CỤC BỘ ML KIT ĐÃ LÀM ẤM   │
│  • Vẽ ngay lập tức trên khung OCR    │ │  • Dịch ngay lập tức (Zero-Wait   │
│    hiện tại, không gọi AI            │ │    Initial Debounce trong Turbo)  │
│  • Lưu vào RAM LRU Cache             │ │  • Áp dụng cụm từ cố định tiếng   │
│                                      │ │    Trung & tinh chỉnh Gemini      │
└──────────────────────────────────────┘ └───────────────────────────────────┘
```

- **Data Model & State**:
  - **Kho Hội thoại Tiếng Trung Nạp sẵn (Preloaded Chinese Dialogue Corpus)**:
    - Phân nhóm theo ngữ cảnh phổ biến:
      - *Giao tiếp & Hội thoại hàng ngày trong phim*: Chào hỏi, hỏi đáp, đồng ý/từ chối, cảm xúc, thúc giục (`等一下`, `怎么回事`, `你没事吧`, `快走`, `别过来`, `我知道了`, `开玩笑的吧`, `到底怎么了`, `放开我`, `跟我们走一趟`, v.v.).
      - *Cổ trang, Kiếm hiệp & Tu tiên (Manhua / Donghua)*: Xưng hô, chiêu thức, cảnh giới, mệnh lệnh (`师兄`, `师父`, `掌门`, `陛下`, `找死`, `手下留情`, `多谢前辈`, `不知死活`, `突破境界`, `遵命`, v.v.).
      - *Truyện tranh Hiện đại, Tổng tài, Học đường & Game*: Thoại kịch tính, trạng thái hệ thống, nhiệm vụ (`不可能`, `为什么会这样`, `难以置信`, `任务完成`, `继续`, `设置`, `确认`, `取消`, v.v.).
    - Hỗ trợ đồng thời chữ Hán Giản thể và các biến thể Phồn thể thường gặp trên phim/truyện Đài Loan, Hồng Kông.
- **Interactive Component & State Mapping**:
  - **Công tắc Kho Hội thoại Tiếng Trung Nạp sẵn**: Cho phép bật/tắt kho nạp sẵn và hiển thị số lượng câu/mẫu câu đang kích hoạt.
  - **Bộ kiểm tra & tra cứu thử nhanh trong Cài đặt**: Ô nhập thử một câu thoại tiếng Trung bất kỳ để xem ngay kết quả khớp `< 1ms` từ kho hội thoại nạp sẵn hoặc từ điển cá nhân.
  - **Bộ đếm Khớp tức thì (Instant Phrase Hits)**: Hiển thị trực quan trên màn hình chính số lần phụ đề được dịch tức thì từ kho nạp sẵn và bộ nhớ đệm mà không cần chờ mô hình dịch.
