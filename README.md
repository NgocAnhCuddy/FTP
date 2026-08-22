# MyFile Manager (Android / Kotlin)

App Android viết bằng Kotlin, gồm 2 chức năng chính:

1. **Máy chủ FTP** ngay trên điện thoại (dùng Apache MINA FtpServer), chạy nền ổn định qua Foreground Service, hỗ trợ nhiều người dùng, phân quyền đọc/ghi, log hoạt động realtime.
2. **Client FTP + đám mây**: kết nối tới máy chủ FTP khác để quản lý file, và liên kết tài khoản **Google Drive, OneDrive, Dropbox, Box** để duyệt/tải lên/tải xuống file.

Giao diện gồm 2 lớp:
- **Màn hình chính (`HomeActivity`)**: phỏng theo giao diện "My Files" của Samsung One UI — file gần đây, lưới thể loại (Ảnh/Video/Âm thanh/Tài liệu...), danh sách lưu trữ (Bộ nhớ trong, **Lưu trữ mạng** dẫn vào app), tự đổi Sáng/Tối theo hệ thống.
- **Màn hình quản lý (`MainActivity`)**: 4 tab — Máy chủ FTP / Kết nối FTP / Đám mây / Cài đặt, theme trắng viền xanh dương pastel.

---

## 1. Build project

### Build tự động qua GitHub Actions (khuyến nghị)

Project đã có sẵn `.github/workflows/build.yml`. Chỉ cần:

1. Tạo repo GitHub mới, đẩy toàn bộ project lên:
   ```bash
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/<ten-ban>/<ten-repo>.git
   git push -u origin main
   ```
2. Vào tab **Actions** trên GitHub, workflow "Build APK" sẽ tự chạy.
3. Sau khi build xong (~5–8 phút), vào job vừa chạy → mục **Artifacts** → tải `app-debug` về, giải nén sẽ có file `app-debug.apk`.
4. Cài file `.apk` này vào điện thoại Android (cần bật "Cài đặt từ nguồn không xác định").

### Build local bằng Android Studio

1. Mở Android Studio → Open → chọn thư mục `myfile-manager`.
2. Android Studio sẽ tự tạo Gradle Wrapper (`gradlew`) nếu project chưa có — chờ Sync xong.
3. Chạy nút ▶ Run để cài trực tiếp vào máy ảo/điện thoại qua USB debugging.

### Build local bằng dòng lệnh (không cần Android Studio)

Cần cài sẵn Gradle 8.7+ và JDK 17:
```bash
cd myfile-manager
gradle wrapper --gradle-version 8.7   # tạo gradlew lần đầu
./gradlew assembleDebug
```
File APK nằm ở `app/build/outputs/apk/debug/app-debug.apk`.

---

## 2. Đăng ký Client ID cho các dịch vụ đám mây

App cần Client ID/App Key riêng cho từng dịch vụ để tính năng liên kết tài khoản hoạt động. Mặc định trong `app/build.gradle.kts` là placeholder (`YOUR_..._CLIENT_ID`) — **bạn cần thay bằng ID thật của mình** thì nút "Liên kết tài khoản" mới chạy được.

### a) Google Drive

1. Vào [Google Cloud Console](https://console.cloud.google.com/) → tạo project mới (hoặc chọn project có sẵn).
2. Vào **APIs & Services → Library**, tìm và bật **Google Drive API**.
3. Vào **APIs & Services → Credentials → Create Credentials → OAuth client ID**.
4. Chọn loại **Android**.
5. Điền:
   - **Package name**: `com.myfile.ui` (hoặc package bạn đã đổi)
   - **SHA-1 certificate fingerprint**: lấy bằng lệnh:
     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
     ```
     (dùng keystore release thật nếu build bản release)
6. Tạo xong, Google sẽ cấp Client ID dạng `xxxx.apps.googleusercontent.com`. **Lưu ý:** Google Sign-In trên Android chỉ cần khai báo Client ID này trên Console (gắn với package+SHA1), **không cần** dán vào code — app đã dùng `GoogleSignInOptions.DEFAULT_SIGN_IN`, tự động khớp qua `google-services.json` hoặc qua cấu hình Console. Nếu muốn chắc chắn, có thể tải file `google-services.json` từ Firebase Console (liên kết cùng project) và đặt vào thư mục `app/`.

### b) OneDrive (Microsoft)

1. Vào [Azure Portal → App registrations](https://portal.azure.com/#blade/Microsoft_AAD_RegisteredApps/ApplicationsListBlade) → **New registration**.
2. Đặt tên app, chọn **Accounts in any organizational directory and personal Microsoft accounts**.
3. Ở **Redirect URI**, chọn platform **Public client/native (mobile & desktop)**, điền:
   ```
   com.myfile.ui.oauth://callback
   ```
4. Sau khi tạo, vào **Overview**, copy **Application (client) ID**.
5. Vào **Authentication**, đảm bảo mục "Allow public client flows" = **Yes**.
6. Vào **API permissions → Add a permission → Microsoft Graph → Delegated permissions**, thêm:
   - `Files.ReadWrite`
   - `offline_access`
   - `User.Read`
7. Dán Client ID vào `app/build.gradle.kts`:
   ```kotlin
   buildConfigField("String", "ONEDRIVE_CLIENT_ID", "\"<client-id-cua-ban>\"")
   ```

### c) Dropbox

1. Vào [Dropbox App Console](https://www.dropbox.com/developers/apps) → **Create app**.
2. Chọn **Scoped access**, quyền truy cập **App folder** (an toàn hơn) hoặc **Full Dropbox**.
3. Sau khi tạo, vào tab **Settings**, copy **App key**.
4. Ở mục **OAuth 2 → Redirect URIs**, thêm:
   ```
   com.myfile.ui.oauth://callback
   ```
5. Vào tab **Permissions**, bật các quyền:
   - `files.metadata.read`
   - `files.content.read`
   - `files.content.write`
6. Dán App key vào `app/build.gradle.kts`:
   ```kotlin
   buildConfigField("String", "DROPBOX_APP_KEY", "\"<app-key-cua-ban>\"")
   ```

### d) Box

1. Vào [Box Developer Console](https://app.box.com/developers/console) → **Create New App**.
2. Chọn **Custom App → User Authentication (OAuth 2.0)**.
3. Sau khi tạo, vào tab **Configuration**:
   - Copy **Client ID** và **Client Secret**.
   - Ở mục **OAuth 2.0 Redirect URI**, thêm:
     ```
     com.myfile.ui.oauth://callback
     ```
   - Ở mục **Application Scopes**, bật: Read all files and folders, Write all files and folders.
4. Dán vào `app/build.gradle.kts`:
   ```kotlin
   buildConfigField("String", "BOX_CLIENT_ID", "\"<client-id-cua-ban>\"")
   buildConfigField("String", "BOX_CLIENT_SECRET", "\"<client-secret-cua-ban>\"")
   ```

> **Lưu ý bảo mật:** Client Secret (Box) về nguyên tắc không nên nhúng cứng trong app di động (có thể bị dịch ngược lấy ra). Với ứng dụng thật, nên có bước đổi token qua backend server riêng của bạn. Bản này nhúng trực tiếp để đơn giản hóa cho việc học/dùng cá nhân.

---

## 3. Cách dùng app

### Máy chủ FTP (tab "Máy chủ FTP")
1. Bấm **Bật máy chủ**. App sẽ xin quyền truy cập bộ nhớ (cần cấp quyền "Truy cập toàn bộ file" ở Android 11+).
2. Địa chỉ kết nối (`ftp://<ip>:<port>`) hiển thị ngay khi server chạy — dùng địa chỉ này trên máy tính (Windows Explorer, FileZilla...) để kết nối.
3. Thêm người dùng ở mục "Người dùng" (mặc định có sẵn `admin` / `admin123` — **nên đổi mật khẩu này**).
4. Máy chủ tiếp tục chạy nền nhờ Foreground Service (có thông báo trên thanh trạng thái).

### Kết nối FTP (tab "Kết nối FTP")
- Bấm **Kết nối**, nhập địa chỉ máy chủ FTP khác, tên đăng nhập, mật khẩu → duyệt/tải lên/tải xuống/xóa/đổi tên file.

### Đám mây (tab "Đám mây")
- Bấm vào từng dịch vụ để liên kết tài khoản (cần đã cấu hình Client ID ở bước 2).
- Sau khi liên kết, bấm vào card để duyệt file.

---

## 4. Cấu trúc project

```
app/src/main/java/com/myfile/ui/
├── server/       # FtpServerManager (Apache MINA), FtpServerService (Foreground Service)
├── client/       # FtpClientManager (Apache Commons Net)
├── cloud/        # CloudFileService + implementation cho 4 dịch vụ, OAuthManager, Retrofit APIs
├── model/        # Data class dùng chung
├── util/         # SecurePrefs (mã hóa), LogBus, NetworkUtils
└── ui/
    ├── HomeActivity          # Màn hình chính kiểu Samsung My Files
    ├── MainActivity          # 4 tab quản lý
    ├── fragments/            # ServerFragment, ClientFragment, CloudFragment, SettingsFragment
    ├── FtpConnectionActivity # Form kết nối FTP
    ├── FileBrowserActivity   # Duyệt file trên FTP server khác
    ├── CloudBrowserActivity  # Duyệt file trên cloud
    ├── LogActivity           # Nhật ký hoạt động server
    └── adapters/             # RecyclerView adapters
```

## 5. Quyền cần thiết

| Quyền | Mục đích |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | Cho máy chủ FTP đọc/ghi toàn bộ bộ nhớ máy |
| `FOREGROUND_SERVICE` | Giữ máy chủ FTP chạy nền ổn định |
| `POST_NOTIFICATIONS` | Hiển thị thông báo trạng thái máy chủ |
| `INTERNET` | Giao tiếp FTP client + API đám mây |

---

## 6. Ghi chú kỹ thuật

- Máy chủ FTP mặc định chạy cổng **2121** (không phải 21, vì cổng <1024 cần quyền root trên Android). Có thể đổi trong tab "Máy chủ FTP".
- Toàn bộ mật khẩu người dùng FTP và token OAuth được lưu qua `EncryptedSharedPreferences` (mã hóa bằng Android Keystore).
- FTP thường (không mã hóa TLS) — chỉ nên dùng trong mạng LAN tin cậy, không public ra Internet nếu chưa có FTPS/VPN.
