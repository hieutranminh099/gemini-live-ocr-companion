# Tự Động Quét & Kết Nối Thiết Bị Qua Mạng LAN (UDP Broadcast)

Bổ sung cơ chế phát tín hiệu và khám phá thiết bị tự động trong mạng nội bộ (LAN) bằng giao thức UDP Broadcast kết hợp phản hồi trực tiếp (Unicast Probe Reply), giúp ứng dụng Android tự động tìm thấy máy tính Windows, tự động ghép cặp bảo mật qua TLS và kết nối ngay chỉ với một chạm vào nút **"Tự động quét thiết bị"**.

## User Review & Critical Decisions

> [!IMPORTANT]
> Các quyết định sản phẩm và kỹ thuật dưới đây đã được thống nhất dựa trên lựa chọn của bạn:

- **Giao thức khám phá mạng LAN**: Sử dụng **UDP Broadcast kết hợp phản hồi Unicast trực tiếp** (cổng UDP `8767`) bằng thư viện chuẩn của Python và Android (`socket` / `DatagramSocket`), không phụ thuộc thư viện ngoài.
- **Tự động ghép cặp & kết nối ngay**: Khi tìm thấy máy tính trong mạng LAN, Android tự động xác minh chứng chỉ TLS (`SHA-256 pin`), tự động hoàn tất ghép cặp thông qua vé xác thực nội bộ (`lan_ticket`) nếu chưa ghép cặp (hoặc cập nhật IP mới nếu đã ghép cặp trước đó) và kết nối ngay.
- **Hành vi khi quét thấy một hoặc nhiều PC**: Nếu chỉ có 1 máy tính phản hồi trong mạng LAN, ứng dụng tự động ghép cặp và kết nối ngay lập tức; nếu phát hiện từ 2 máy tính trở lên, ứng dụng hiển thị danh sách máy tính (tên máy, IP:Port) để người dùng chạm chọn.

---

## 1. Overview & Core Concept

- **What It Does**:
  - **Trên Windows Server**: Khi chương trình khởi chạy, một dịch vụ UDP Discovery chạy ngầm sẽ định kỳ phát tín hiệu thông báo sự hiện diện của máy chủ (`IP`, `Port`, `Tên máy tính`, `Dấu vân tay chứng chỉ TLS SHA-256`, `Vé ghép cặp LAN tạm thời`) trong mạng LAN, đồng thời lắng nghe yêu cầu quét chủ động từ điện thoại để phản hồi ngay lập tức.
  - **Trên Android**: Cung cấp nút **"Tự động quét thiết bị"** nổi bật ở cả màn hình chính và tab **Kết nối**. Khi người dùng nhấn nút, điện thoại phát gói tin dò tìm UDP và lắng nghe tín hiệu từ PC. Ngay khi tìm thấy PC, ứng dụng tự động ghép cặp qua TLS và sẵn sàng dịch màn hình mà không cần nhập IP, chứng chỉ hay mã 6 số thủ công.
- **Target Audience / Persona**: Người dùng sử dụng Gemini Live OCR Translator trên điện thoại Android và máy tính Windows trong cùng mạng Wi-Fi/LAN muốn kết nối nhanh chóng mỗi khi mở ứng dụng hoặc khi địa chỉ IP của máy tính thay đổi do DHCP.
- **Key Value**: Loại bỏ hoàn toàn thao tác nhập IP:Port, dấu vân tay SHA-256 hoặc quét QR thủ công, đồng thời tự động khắc phục tình trạng mất kết nối khi router đổi IP nội bộ của máy tính.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Quét và tự động kết nối (1 máy tính trong LAN)**:
     - Người dùng mở ứng dụng Android, nhấn nút **"Tự động quét thiết bị"** tại tab **Kết nối** (hoặc ngay trên thẻ trạng thái ở tab **Dịch trực tiếp** khi chưa ghép cặp).
     - Nút chuyển sang trạng thái đang quét kèm biểu tượng radar/vòng xoay tiến trình: *"Đang tìm máy tính trong mạng LAN…"*.
     - Khi phát hiện duy nhất 1 máy tính, ứng dụng tự động xác thực chứng chỉ TLS, lưu thông tin ghép cặp (hoặc cập nhật IP mới), kích hoạt kiểm tra kết nối và hiển thị thông báo: *"Đã tự động kết nối với <Tên PC> (<IP:Port>)"*.
  2. **Quét và chọn máy tính (Nhiều máy tính trong LAN)**:
     - Nếu trong thời gian quét phát hiện từ 2 máy tính trở lên cùng chạy Gemini OCR, giao diện hiển thị danh sách các thẻ máy tính tìm thấy (gồm tên máy, địa chỉ `IP:Port`, và 8 ký tự đầu của chứng chỉ SHA-256).
     - Người dùng chạm vào máy tính mong muốn để tự động ghép cặp và kết nối ngay.
  3. **Không tìm thấy máy tính (Fallback an toàn)**:
     - Nếu hết thời gian quét (~2.5 giây) mà không nhận được phản hồi UDP, ứng dụng hiển thị hướng dẫn rõ ràng về kiểm tra Wi-Fi/Firewall và giữ nguyên phần ghép cặp bằng QR / liên kết / mã 6 số truyền thống ở bên dưới.
- **Visual Identity & Theme**:
  - **Aesthetic Direction**: Giữ vững phong cách **Dark Utilitarian Precision** hiện có của ứng dụng với nền tối sâu và điểm nhấn xanh bạc hà sắc nét.
  - **Color Palette & Mood**:
    - Background chính: `#0B1218` (`--background`)
    - Bề mặt thẻ (Panel): `#14212B` (`--surface`)
    - Điểm nhấn chính (Primary Accent): `#6EE7C7` (`--primary` - Mint)
    - Điểm nhấn phụ (Secondary): `#F0B976` (`--secondary` - Warm Amber)
    - Văn bản phụ (Muted): `#98ACB9` (`--muted`)
  - **Component Styling & Layout**:
    - Thẻ **"Tự động quét thiết bị"** đặt ở vị trí ưu tiên cao nhất trong tab **Kết nối** với bo góc `22.dp`, nút bấm chính cao `52.dp` bo góc `16.dp`, biểu tượng `Icons.Outlined.Radar` / `Icons.Outlined.WifiFind`, đảm bảo vùng chạm tối thiểu `48.dp`.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Kết hợp UDP Broadcast định kỳ (Beacon) và UDP Probe phản hồi trực tiếp (Unicast Reply)**
  - *Chosen Approach*: Máy chủ Windows vừa phát broadcast định kỳ mỗi 2 giây tới cổng UDP `8767`, vừa lắng nghe gói `gemocr_discover` trên cổng `8767` để trả lời trực tiếp (unicast) về đúng địa chỉ `(ip, port)` của điện thoại.
  - *Why*: Một số router Wi-Fi hạn chế hoặc làm trễ gói broadcast thụ động một chiều. Khi điện thoại chủ động gửi gói probe và máy chủ trả lời unicast trực tiếp về địa chỉ nguồn của gói tin, thời gian tìm thấy máy tính giảm xuống dưới `150ms` và máy chủ xác định chính xác địa chỉ IP LAN cùng dải mạng với điện thoại.
  - *Alternatives Considered*: Chỉ dùng mDNS (`zeroconf`) — đòi hỏi cài thêm thư viện ngoài trên Windows và đôi khi bị chặn multicast trên mạng Wi-Fi khách.
- **Decision 2: Ghép cặp LAN không chạm (Zero-Click LAN Pairing) vẫn giữ nguyên bảo mật TLS Certificate Pinning**
  - *Chosen Approach*: Gói tin UDP Beacon từ máy chủ chứa dấu vân tay chứng chỉ TLS (`pin` SHA-256 64 ký tự) và một mã vé ghép cặp LAN tự xoay vòng (`lan_ticket`) do máy chủ ký/quản lý trong bộ nhớ. Khi Android kết nối tới `https://<IP>:<Port>/v1/pair`, kết nối TLS vẫn được kiểm tra nghiêm ngặt bằng `SecureConnection.client(pin)` trước khi gửi `lan_ticket` để nhận `token` phiên 256-bit.
  - *Why*: Đáp ứng trải nghiệm "tự động kết nối ngay khi tìm thấy PC" mà không làm suy giảm lớp mã hóa TLS hay làm hỏng cơ chế ghép cặp bằng mã 6 số / QR hiện có.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        WINDOWS COMPANION SERVER                         │
│                                                                         │
│  ┌──────────────────────────┐        ┌───────────────────────────────┐  │
│  │  LAN Discovery Service   │        │   FastAPI Device TLS Server   │  │
│  │  (UDP Socket :8767)      │        │   (HTTPS / WSS :8765)         │  │
│  │  • Periodic LAN Beacon   │        │   • POST /v1/pair             │  │
│  │  • Unicast Probe Responder│       │     (6-digit code OR ticket)  │  │
│  └────────────┬─────────────┘        │   • GET  /health              │  │
│               │                      │   • WSS  /v1/stream           │  │
└───────────────┼──────────────────────┴───────────────┬───────────────┘
                │ UDP Broadcast / Unicast (:8767)      │ TLS 1.2+ (Pinned SHA-256)
                ▼                                      ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          ANDROID APPLICATION                            │
│                                                                         │
│  ┌──────────────────────────┐        ┌───────────────────────────────┐  │
│  │   LanScanner (UDP :8767) │───────▶│      SecureConnection         │  │
│  │  • Send gemocr_discover  │        │  • Verify TLS SHA-256 Pin     │  │
│  │  • Collect Beacons       │        │  • Reuse token if pin matches │  │
│  │  • Deduplicate by Pin    │        │  • Auto-pair via lan_ticket   │  │
│  └────────────┬─────────────┘        └───────────────┬───────────────┘  │
│               │                                      │                  │
│               ▼                                      ▼                  │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                MainActivity / PairingScreen UI                    │  │
│  │  • "Tự động quét thiết bị" Button (Home & Pairing Tabs)           │  │
│  │  • Auto-connect on single PC / Selection Cards on multiple PCs    │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### Data Model & Protocol Payloads

1. **Gói dò tìm từ Android (`gemocr_discover`)**:
   ```json
   {
     "type": "gemocr_discover",
     "protocol": 1
   }
   ```
2. **Gói phản hồi / phát sóng từ Windows (`gemocr_beacon`)**:
   ```json
   {
     "type": "gemocr_beacon",
     "protocol": 1,
     "name": "DESKTOP-PC",
     "host": "192.168.1.10:8765",
     "addresses": ["192.168.1.10"],
     "port": 8765,
     "pin": "<64-hex-sha256-certificate-fingerprint>",
     "lan_ticket": "<ephemeral-lan-pairing-ticket>"
   }
   ```
3. **Mở rộng yêu cầu ghép cặp (`POST /v1/pair`)**:
   - Chấp nhận `code` 6 số truyền thống **hoặc** `lan_ticket` hợp lệ đang hoạt động trên `Runtime.pairing`.
   - Nếu thiết bị Android đã có sẵn `token` hợp lệ với cùng chứng chỉ `pin`, Android chỉ cần cập nhật `settings.host` sang địa chỉ `IP:Port` mới tìm thấy và kiểm tra kết nối mà không cần tạo lại thiết bị trùng lặp trên máy chủ.

### Interactive Component & State Mapping

- **Nút `"Tự động quét thiết bị"` (`testTag = "auto_scan_devices_button"`)**:
  - Khi nhấn: Chuyển trạng thái `scanning = true`, xóa danh sách máy tính tạm thời, gọi `LanScanner.discover()` để gửi gói UDP broadcast và lắng nghe phản hồi.
  - Nếu tìm thấy **1 máy tính**: Gọi `SecureConnection.connectOrPairDiscovered(...)`, lưu `host`, `pin`, `token` vào `AppSettings`, cập nhật `paired = true`, `SessionState.status.value = "Đã tự động kết nối với <Tên PC>"`, và hiển thị thông báo thành công.
  - Nếu tìm thấy **nhiều máy tính**: Hiển thị danh sách thẻ máy tính (`testTag = "discovered_pc_item_<host>"`); khi người dùng chọn 1 thẻ, thực hiện ghép cặp & kết nối ngay với máy tính đó.
  - Nếu **không tìm thấy**: Đặt `scanning = false` và hiển thị thông báo hướng dẫn kiểm tra mạng LAN / tường lửa.
