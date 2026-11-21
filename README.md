# AutoDraw Pro (MyAutoDraw Color Pro)

Ứng dụng Android hỗ trợ vẽ tự động bằng dịch vụ Trợ năng (Accessibility) và lớp phủ nổi (Overlay).

## Xuất APK trên GitHub
1. Đẩy toàn bộ thư mục này lên nhánh `main` (giữ nguyên cấu trúc, gồm cả `.github/workflows/build-apk.yml`).
2. Vào tab **Actions** → workflow **Build Android APK** (hoặc bấm **Run workflow**).
3. Chờ chạy xong, mở lần chạy đó → mục **Artifacts** → tải `MyAutoDraw-Color-Pro-APK` (giải nén .zip để lấy `app-debug.apk`).

## Cấu trúc
- `app/src/main/java/com/myautodraw/` – mã Java
- `app/src/main/res/` – giao diện, màu, chuỗi
- `.github/workflows/build-apk.yml` – quy trình build (không còn tự sinh lại mã nguồn)
