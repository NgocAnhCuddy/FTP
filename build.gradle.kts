// Top-level build file
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Cần cho Google Sign-In/Drive đọc đúng Client ID từ google-services.json.
    // Không đặt apply false ở app/build.gradle.kts sẽ lỗi build nếu thiếu file json,
    // nên bật/tắt tùy theo bạn đã thêm google-services.json vào app/ hay chưa.
    id("com.google.gms.google-services") version "4.5.0" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
