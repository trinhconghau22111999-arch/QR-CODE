# QR Keyboard (Android)

Bàn phím Android tuỳ chỉnh có sẵn nút **[QR] Quét mã**. Khi bấm vào, camera sẽ mở lên
(ở dạng lớp phủ trong suốt phía trên bàn phím), quét mã QR bằng ML Kit, sau đó tự động
**chèn nội dung mã vào đúng ô nhập liệu** đang gõ dở — hoạt động trong bất kỳ ứng dụng nào
(Zalo, Messenger, trình duyệt, ghi chú...), vì đây là bàn phím hệ thống thật (Input Method Service),
không phải một app riêng lẻ.

## Cấu trúc dự án
```
QRKeyboard/
├── app/
│   ├── src/main/java/com/example/qrkeyboard/
│   │   ├── MainActivity.kt        # Màn hình hướng dẫn bật bàn phím
│   │   ├── QrKeyboardService.kt   # Input Method Service - vẽ bàn phím, xử lý phím bấm
│   │   └── QrScanActivity.kt      # Màn hình quét QR bằng CameraX + ML Kit
│   ├── src/main/res/...           # Layout, string, theme, icon
│   └── build.gradle
├── build.gradle
└── settings.gradle
```

## Cách mở & chạy dự án
1. Cài **Android Studio** (bản mới nhất, Koala trở lên khuyến nghị).
2. Chọn **Open** rồi trỏ tới thư mục `QRKeyboard` này.
3. Android Studio sẽ tự tải Gradle Wrapper và các thư viện (CameraX, ML Kit) — cần
   máy tính có kết nối internet ở bước này.
4. Cắm điện thoại Android (bật USB debugging) hoặc dùng máy ảo có Camera, rồi bấm **Run ▶**.

> Dự án chưa kèm file `gradle-wrapper.jar` (file nhị phân). Nếu Android Studio báo thiếu wrapper,
> chỉ cần mở Terminal trong Android Studio và chạy: `gradle wrapper` (yêu cầu máy đã cài Gradle),
> hoặc dùng **File → Sync Project with Gradle Files**, Android Studio sẽ tự sinh phần còn thiếu.

## Cách bật và dùng bàn phím trên điện thoại
1. Mở app **QR Keyboard** vừa cài, bấm nút **"Mở cài đặt bàn phím"**.
2. Trong **Cài đặt hệ thống → Ngôn ngữ & bàn phím → Quản lý bàn phím**, bật **QR Keyboard**.
3. Vào bất kỳ ô nhập văn bản nào (tin nhắn, ghi chú...), chạm giữ biểu tượng
   bàn phím / quả địa cầu ở thanh gợi ý, chọn **QR Keyboard** để chuyển sang.
4. Bấm nút **[QR] Quét mã** trên bàn phím → cấp quyền Camera lần đầu → đưa mã QR
   vào khung hình → nội dung sẽ tự động được gõ vào ô nhập liệu.

## Ghi chú kỹ thuật
- Dùng `InputMethodService` để vẽ bàn phím thật (không phải overlay giả).
- Quét mã bằng `CameraX` + `ML Kit Barcode Scanning` — chạy **offline hoàn toàn trên máy**,
  không gửi hình ảnh lên máy chủ nào.
- Bàn phím có QWERTY cơ bản, Shift, chuyển 123/ABC, khoảng cách, Enter, xoá — đủ dùng để gõ
  nhanh, có thể mở rộng thêm (tiếng Việt có dấu, biểu tượng cảm xúc, gợi ý từ...) tuỳ nhu cầu.
- Khi người dùng lần đầu dùng nút QR, hệ thống sẽ hỏi quyền Camera qua `QrScanActivity`.

## Có thể mở rộng thêm
- Thêm gõ tiếng Việt có dấu (Telex/VNI) bằng thư viện xử lý bộ gõ.
- Thêm rung/haptic feedback khi bấm phím.
- Cho phép chọn quét QR từ ảnh có sẵn trong thư viện thay vì chỉ camera trực tiếp.
- Lưu lịch sử các mã đã quét.
