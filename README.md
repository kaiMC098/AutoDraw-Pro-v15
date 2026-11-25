# 🎨 MyAutoDraw Color Pro (AutoDraw Pro)

> **Ứng dụng Android hỗ trợ tự động vẽ tranh đủ màu sắc nét ngay trên màn hình** bằng dịch vụ **Trợ năng (Accessibility)** và **Lớp phủ nổi (Overlay)** — tối ưu cho **Builda** và các ứng dụng vẽ trên điện thoại.
>
> Phát triển bởi **@ellenjoe023** • Mã nguồn mở

---

## ✨ Tính năng nổi bật

* **Tự động làm sắc nét ảnh:** Tự đo độ mờ và tương phản của ảnh tải lên để làm rõ đường viền, ánh mắt và chi tiết nhỏ mà không tạo viền đen giả.
* **Vẽ tỉ mỉ từng hàng 1 pixel:** Quét và vẽ lần lượt từ trên xuống dưới theo từng hàng `1px`, giữ đúng tỷ lệ và bố cục ảnh gốc.
* **Tách màu thông minh (2 – 24 màu):** Tự động sắp xếp thứ tự vẽ chuẩn họa sĩ (*mảng nền lớn → chi tiết trung gian → nét viền tối → đốm sáng trắng nhỏ vẽ cuối cùng*).
* **3 chế độ vẽ linh hoạt:**
  * **Đủ màu:** Tô đầy đủ các lớp màu tách từ ảnh gốc.
  * **Nét viền:** Chỉ trích xuất và đi nét phác thảo.
  * **Mảng đậm:** Tô các mảng tối/sáng theo ngưỡng tùy chỉnh.
* **Kiểm soát bảng màu H-S-V:** Chạm vào từng ô màu để chỉnh thông số **H - S - V**, nhấn giữ để bật/tắt riêng từng màu, hoặc tự động loại bỏ nền trắng bên ngoài mà vẫn giữ trọn tròng trắng mắt và điểm sáng.
* **Menu nổi gọn nhẹ:** Thanh điều khiển kéo thả tự do ngay trên màn hình game/app vẽ, hỗ trợ tạm dừng, bỏ qua màu hoặc chỉnh tốc độ vẽ (`ms`).

---

## 📱 Những điện thoại nào cài được?

Ứng dụng hỗ trợ **Android 7.0 (API 24) trở lên**, không giới hạn cấu hình chip (tương thích cả máy thật lẫn giả lập / Cloud Phone).

| Thiết bị / Hệ điều hành | Khả năng cài đặt | Lưu ý khi cài & cấp quyền |
| :--- | :---: | :--- |
| **Android 7.0 – Android 12**<br>*(Samsung, Oppo, Vivo, Realme, Xiaomi, Vsmart...)* | ✅ Cài trực tiếp | Bật **Cài đặt ứng dụng không xác định** cho trình quản lý tệp. Nếu từng cài bản cũ khác chữ ký, hãy gỡ bản cũ trước khi cài bản mới. |
| **Android 13 – Android 15**<br>*(Samsung One UI 5–7, Pixel, Android gốc...)* | ✅ Cài trực tiếp | Trước khi bật Trợ năng, vào **Thông tin ứng dụng → dấu 3 chấm (⋮) góc trên → Cho phép cài đặt bị hạn chế**. |
| **Xiaomi / Redmi / POCO**<br>*(MIUI 13–14, HyperOS)* | ✅ Cài trực tiếp | Ngoài bước mở *Cài đặt bị hạn chế*, vào **Thông tin ứng dụng → Quyền khác** và bật xanh **Hiển thị cửa sổ pop-up khi chạy trong nền** để menu nổi luôn hiện trên Builda. |
| **Giả lập Android & Cloud Phone**<br>*(LDPlayer, BlueStacks, Nox, XCloudPhone...)* | ✅ Cài trực tiếp | Kéo thả tệp `.apk` vào màn hình giả lập và bật Trợ năng trong Cài đặt. |
| **iPhone / iPad (iOS / iPadOS)** | ❌ Không hỗ trợ | Tệp cài đặt định dạng `.apk` chỉ chạy trên hệ điều hành Android. |

---

## 📖 Hướng dẫn sử dụng chi tiết

### Bước 1: Chuẩn bị ảnh & cấp quyền trong app
1. Mở **MyAutoDraw Color Pro**, bấm **1. Chọn ảnh màu từ thư viện** và chọn bức ảnh muốn vẽ.
2. Chọn chế độ **Đủ màu** (nên để **16 – 20 màu**, bật **Bỏ qua nền trắng / trong suốt**).
3. Bấm **2. Cấp quyền hiển thị nổi (Overlay)** và gạt cho phép.
4. Bấm **3. Bật dịch vụ AutoDraw (Trợ năng)** → tìm **MyAutoDraw Pro** trong mục *Ứng dụng đã tải xuống* và bật công tắc để hiện thanh công cụ nổi.

### Bước 2: Căn khung & vẽ trên Builda
1. Mở **Builda**, tạo khung vẽ mới, chọn **bút nét cứng** và kéo thanh **cỡ bút xuống mức nhỏ nhất (2–4px)** để nét vẽ mịn, không bị bệt.
2. Kéo **KHUNG VẼ** viền xanh nằm gọn vào trong vùng giấy vẽ caro của Builda.
3. Chọn 1 trong 2 cách đổi màu trên thanh menu nổi rồi bấm **Vẽ**:

* **Cách 1 — Thủ công (`Màu:Tay` — Khuyên dùng để chuẩn màu nhất):**
  * App sẽ vẽ xong từng lớp màu rồi tự động tạm dừng, hiển thị **ô màu mẫu** kèm **mã Hex và chỉ số `H / S / V`**.
  * Bạn chọn đúng màu đó trong Builda, đóng bảng màu lại rồi bấm **`Vẽ tiếp ▶`** (hoặc bấm **`Bỏ qua`** nếu không muốn vẽ màu đó).
* **Cách 2 — Tự động (`Màu:Tự động`):**
  * Mở bảng chỉnh màu **H-S-V** trong Builda, kéo **khung cam H-S-V** đặt khít lên 3 thanh trượt màu của Builda.
  * Kéo chấm hồng **`Màu`** đặt đè lên nút mở bảng màu, và chấm xanh **`Bút`** đặt đè lên biểu tượng bút vẽ ở thanh công cụ dưới.
  * Đưa 3 núm trượt H-S-V của Builda về sát bên trái (`0`) rồi bấm **`Vẽ`**.

> 💡 **Mẹo vẽ đẹp:** Nếu điện thoại xử lý cảm ứng chậm làm đứt nét, hãy tăng thanh **Thời gian vẽ mỗi nét** lên khoảng `28ms – 35ms` trước khi bấm **Vẽ**.

---

## ⚙️ Tự xuất tệp APK trên GitHub

1. Đẩy toàn bộ mã nguồn lên nhánh `main` (giữ nguyên thư mục `.github/workflows/build-apk.yml`).
2. Vào tab **Actions** → chọn workflow **Build Android APK** (hoặc bấm **Run workflow**).
3. Chờ hoàn tất (dấu tích xanh **✓**) và tải tệp **`MyAutoDraw-Pro-v15.apk`** tại mục **Releases** hoặc **Artifacts**.
4. 
