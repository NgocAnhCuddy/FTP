plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chỉ áp dụng plugin Google Services nếu đã có app/google-services.json — tránh lỗi build
// cứng "File google-services.json is missing" cho người chưa cấu hình xong Google Drive,
// trong khi Dropbox/Box vẫn build và chạy bình thường không cần file này.
val hasGoogleServicesJson = file("google-services.json").exists()
if (hasGoogleServicesJson) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.myfile.ui"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.myfile.ui"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Placeholder client IDs cho các dịch vụ cloud - thay bằng ID thật của bạn
        // LƯU Ý: Google Sign-In SDK (dùng cho Google Drive) KHÔNG đọc 2 giá trị dưới đây lúc
        // runtime — nó tự nhận diện app dựa trên cặp (package name + SHA-1 chữ ký APK) đã đăng
        // ký sẵn trên Google Cloud Console, khớp ngầm lúc gọi GoogleSignIn.getClient(). Giữ lại
        // 2 dòng này chỉ để tham chiếu/đối chiếu cấu hình, không ảnh hưởng hành vi đăng nhập.
        manifestPlaceholders["googleClientId"] = "664326481029-5nl3olg60oiqvm14gt316orododf06aq.apps.googleusercontent.com"
        // Scheme redirect dùng chung cho luồng OAuth2 của AppAuth (OneDrive/Dropbox/Box)
        manifestPlaceholders["appAuthRedirectScheme"] = "com.myfile.ui.oauth"
        buildConfigField("String", "GOOGLE_DRIVE_CLIENT_ID", "\"664326481029-5nl3olg60oiqvm14gt316orododf06aq.apps.googleusercontent.com\"")
        buildConfigField("String", "DROPBOX_APP_KEY", "\"2tznxguscwvir9n\"")
        buildConfigField("String", "BOX_CLIENT_ID", "\"tq8ju2pw919xsm8tg790v8bb3bu7601l\"")
        buildConfigField("String", "BOX_CLIENT_SECRET", "\"tFuhtDHbSNvEcFlcJwCKoi3K26xSVwQN\"")
    }

    signingConfigs {
        create("release") {
            // Dùng debug keystore mặc định nếu không cấu hình signing riêng qua CI secrets
            val storeFilePath = System.getenv("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val storeFilePath = System.getenv("RELEASE_STORE_FILE")
            signingConfig = if (storeFilePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Bắt buộc cho SFTP server (org.apache.sshd) dùng java.nio.file.Path/Paths/Files —
        // các API này chỉ có sẵn từ Android API 26 trở lên; minSdk của app là 24, nên thiếu
        // desugaring sẽ khiến app CRASH NGAY (NoClassDefFoundError/NoSuchMethodError) trên
        // Android 7.0/7.1 dù build thành công bình thường.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    // Core AndroidX + Material 3
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.gridlayout:gridlayout:1.0.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // FTP Server (Apache MINA FTPServer)
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0")

    // HTTP Stream Server (NanoHTTPD) — phát file qua LAN cho TV/điện thoại khác
    // (mở bằng trình duyệt/VLC) và làm nền tảng để DLNA cast trỏ TV về lấy file.
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Media3 ExoPlayer + MediaSession — player audio/video chạy nền kiểu VLC,
    // có notification điều khiển, tiếp tục phát khi khóa màn hình.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // FTP Client (Apache Commons Net)
    implementation("commons-net:commons-net:3.11.1")

    // SFTP Client (SSHJ - hỗ trợ SFTP qua SSH)
    implementation("com.hierynomus:sshj:0.38.0")

    // SMB2/SMB3 Client (smbj - duyệt/tải lên/xuống chia sẻ mạng Windows/NAS)
    implementation("com.hierynomus:smbj:0.13.0")

    // SFTP Server (Apache MINA sshd - dùng chung engine với ftpserver-core)
    // sshd-sftp là artifact RIÊNG kể từ sshd 2.0 trở đi, chứa SftpSubsystemFactory —
    // thiếu nó sẽ không có class này dù đã có sshd-core.
    implementation("org.apache.sshd:sshd-core:2.13.2")
    implementation("org.apache.sshd:sshd-common:2.13.2")
    implementation("org.apache.sshd:sshd-sftp:2.13.2")

    // Networking cho cloud APIs (Google Drive, OneDrive, Box, Dropbox)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // AppAuth cho OAuth2 (OneDrive, Box, Dropbox - authorization code flow)
    implementation("net.openid:appauth:0.11.1")

    // Google Sign-In + Drive API
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.api-client:google-api-client-android:2.7.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20240914-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.http-client:google-http-client-gson:1.45.0") {
        exclude(group = "org.apache.httpcomponents")
    }

    // QR code hiển thị địa chỉ FTP để kết nối nhanh
    implementation("com.google.zxing:core:3.5.3")

    // Quét mã QR bằng camera để kết nối nhanh (CameraX + ML Kit Barcode Scanning)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Nén/giải nén ZIP và 7Z (dùng chung cho Bộ nhớ trong, FTP client, Cloud)
    implementation("org.apache.commons:commons-compress:1.26.2")
    // androidx.biometric: khoá app bằng vân tay/khuôn mặt (BiometricPrompt chuẩn hệ thống,
    // tự động fallback sang màn hình khoá thiết bị PIN/mẫu hình/mật khẩu nếu máy không có cảm
    // biến sinh trắc học hoặc người dùng chưa đăng ký vân tay nào).
    implementation("androidx.biometric:biometric:1.1.0")
    // junrar: đọc/giải nén file .rar (hỗ trợ RAR lên tới v7, kể cả file có mật khẩu và archive
    // nhiều phần .partN.rar). Thư viện CHỈ giải nén, không thể TẠO file .rar mới — tạo RAR đòi
    // hỏi giấy phép thương mại từ RARLAB, không thư viện mã nguồn mở nào được phép làm việc đó.
    // BẮT BUỘC dùng >= 7.5.10: các bản 7.5.7 trở xuống có 2 lỗ hổng path-traversal nghiêm
    // trọng trong LocalFolderExtractor (CVE-2026-28208, CVE-2026-41245) — 1 file .rar độc hại
    // có thể ghi đè file TÙY Ý ngoài thư mục đích khi giải nén (kể cả trên máy đã áp dụng
    // safeDestFile() chống Zip Slip trong ArchiveUtils.kt, vì lỗ hổng nằm bên TRONG code giải
    // nén của chính thư viện, xảy ra trước khi code của app kiểm tra được đường dẫn).
    implementation("com.github.junrar:junrar:7.5.10")
    // zip4j: đọc VÀ TẠO file .zip có mật khẩu (AES-256 hoặc ZipCrypto chuẩn cũ) — thư viện
    // java.util.zip có sẵn trong JDK KHÔNG hỗ trợ zip mã hoá dưới bất kỳ hình thức nào, nên cần
    // thư viện riêng cho cả 2 chiều: giải nén file .zip có pass tải từ mạng về, và tạo file
    // .zip có pass khi người dùng muốn bảo vệ dữ liệu trước khi chia sẻ. 100% Java (không code
    // native), hoạt động ổn định trên Android. Bản >= 2.10.0 đã vá CVE-2018-1002202 (Zip Slip)
    // và CVE-2022-24615 (uncaught exception khi parse zip cố tình lỗi định dạng).
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("org.tukaani:xz:1.9") // cần cho giải nén 7z dùng LZMA2

    // WebView mở rộng: an toàn hơn khi nạp file HTML local (WebViewAssetLoader)
    implementation("androidx.webkit:webkit:1.11.0")

    // Image loading cho thumbnail file
    implementation("io.coil-kt:coil:2.7.0")
    // coil-gif: cung cấp GifDecoder VÀ ImageDecoderDecoder (dùng cho HEIC/HEIF trên API 28+).
    // MyFileApp.kt import cả hai class này từ module coil-gif, không phải từ coil lõi —
    // thiếu dòng này gây lỗi "Unresolved reference" khi build.
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // PhotoView: pinch-to-zoom + double-tap zoom + kéo ảnh cho MediaViewerActivity. Lấy qua
    // jitpack.io (đã khai ở settings.gradle.kts) vì đây là thư viện nhỏ, ổn định, không còn
    // maintain bản mới trên Maven Central nhưng vẫn hoạt động tốt, được rất nhiều app dùng.
    implementation("com.github.chrisbanes:PhotoView:2.3.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // Bắt buộc để dùng java.nio.file.* (Path, Paths, Files) trên minSdk 24-25 — thư viện
    // SFTP server (sshd-sftp) dùng các API này trực tiếp, không có desugaring sẽ crash
    // ngay khi gọi tới trên các máy Android 7.0/7.1.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
