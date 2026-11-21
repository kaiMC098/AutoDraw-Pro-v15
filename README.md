# AutoDraw Pro (MyAutoDraw Color Pro)

Ứng dụng Android hỗ trợ vẽ tự động bằng dịch vụ Trợ năng (Accessibility) và lớp phủ nổi (Overlay).

## Xuất APK trên GitHub
1. Đẩy toàn bộ thư mục này lên nhánh `main` (giữ nguyên cấu trúc, gồm cả `.github/workflows/build-apk.yml`).
2. Vào tab **Actions** → workflow **Build Android APK** (hoặc bấm **Run workflow**).
3. Chờ chạy xong (dấu ✓ xanh). APK có ở 2 nơi: mục **Releases** của repo (tải thẳng file `.apk`), hoặc mục **Artifacts** trong lần chạy (tải .zip rồi giải nén).
4. Nếu bị đỏ: mở bước đỏ, copy phần báo lỗi để được hỗ trợ.

## Cấu trúc
- `app/src/main/java/com/myautodraw/` – mã Java
- `app/src/main/res/` – giao diện, màu, chuỗi
- `.github/workflows/build-apk.yml` – quy trình build (không còn tự sinh lại mã nguồn)

## Cách dùng trong Builda
1. Mở app, chọn ảnh, cấp quyền Overlay và bật dịch vụ Trợ năng.
2. Mở Builda, đặt **khung xanh** lên vùng canvas, bấm **Vẽ**.
3. Chế độ **Màu:Tay** (mặc định): mỗi màu app sẽ dừng, hiện ô màu + mã HSV cần chọn. Bạn chọn màu trong Builda, đóng bảng màu, bấm **Đã chọn màu - Tiếp** để vẽ tiếp.
4. Chế độ **Màu:Tự động**: đặt khung cam khít 3 thanh H-S-V, chấm "Màu" lên nút Màu và chấm "Bút" lên nút Bút. Núm trượt trong Builda phải đang ở 0 khi bắt đầu.
