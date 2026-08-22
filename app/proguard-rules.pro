# Apache MINA FtpServer / Commons Net dùng reflection nội bộ
-keep class org.apache.ftpserver.** { *; }
-keep class org.apache.mina.** { *; }
-keep class org.apache.commons.net.** { *; }
-dontwarn org.apache.ftpserver.**
-dontwarn org.apache.mina.**
-dontwarn org.apache.commons.net.**

# Apache MINA SSHD (SFTP server) dùng reflection để tự dò và đăng ký các security provider
# ngay trong static initializer của SecurityUtils, và tự build danh sách NamedFactory (kênh,
# subsystem...) bên trong ServerBuilder cũng bằng phản chiếu/factory pattern nội bộ.
#
# QUAN TRỌNG: gradle.properties bật android.enableR8.fullMode=true — full mode tối ưu SÂU HƠN
# nhiều so với compat mode và có thể xoá/đổi tên/tối ưu bên trong các class factory như
# ServerBuilder ngay cả khi có "-keep", NẾU rule đó dùng allowobfuscation/allowshrinking (rule
# trước đây có 2 cờ này, mâu thuẫn trực tiếp với ý định "giữ nguyên" -> R8 vẫn coi là được phép
# xoá/đổi tên -> NoClassDefFoundError: org.apache.sshd.server.ServerBuilder khi start SFTP dù
# rule -keep vẫn có mặt trong file). Không dùng 2 cờ đó cho package này nữa.
-keep class org.apache.sshd.** { *; }
-keepclassmembers class org.apache.sshd.** { *; }
-keep interface org.apache.sshd.** { *; }
-dontwarn org.apache.sshd.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }

# Không tối ưu hoá/inline/merge BẤT KỲ phần nào bên trong toàn bộ package org.apache.sshd —
# chỉ -keep tên class là chưa đủ an toàn với code dùng ServiceLoader/reflection nặng như SSHD
# khi R8 full mode được bật; full mode vẫn có thể re-arrange logic BÊN TRONG method dù giữ được
# chữ ký/tên class, phá vỡ cách SSHD tự build danh sách factory lúc runtime.
# Dùng -keepclassmembers KHÔNG kèm allowshrinking/allowobfuscation (khác bản trước) để loại bỏ
# hoàn toàn khả năng R8 tự quyết "class này thật ra không cần thiết" — đây chính là nguyên nhân
# NoClassDefFoundError dù đã có -keep ở trên.
-keepclassmembers class org.apache.sshd.server.ServerBuilder { *; }

# ServerBuilder.build() (gọi ngầm trong SshServer.setUpDefaultServer()) tự dựng danh sách
# NamedFactory mặc định (cipher/mac/kex/signature/compression) từ các hằng số enum tĩnh —
# ví dụ BuiltinCiphers.aes128ctr — tức là các enum này implement NamedFactory qua
# phương thức trừu tượng override RIÊNG cho từng hằng số (constant-specific class body),
# một dạng anonymous inner class ẩn danh mà R8 fullMode có thể coi là "không truy cập được
# từ đâu" nếu chỉ thấy tham chiếu gián tiếp qua danh sách builder — dẫn tới bị merge/xoá dù
# class enum cha đã có "-keep". Cấm R8 tối ưu hoá enum + merge class trong toàn bộ sshd-common
# để giữ nguyên từng hằng số enum kèm class thân của nó.
-keepclassmembers enum org.apache.sshd.** { *; }
-keep,allowshrinking class org.apache.sshd.common.cipher.BuiltinCiphers { *; }
-keep,allowshrinking class org.apache.sshd.common.mac.BuiltinMacs { *; }
-keep,allowshrinking class org.apache.sshd.common.kex.BuiltinDHFactories { *; }
-keep,allowshrinking class org.apache.sshd.common.signature.BuiltinSignatures { *; }
-keep,allowshrinking class org.apache.sshd.common.compression.BuiltinCompressions { *; }
-keepattributes InnerClasses,EnclosingMethod

# Không cho R8 merge/inline bất kỳ class nào trong sshd-common và sshd-core — ServerBuilder
# build danh sách factory bằng cách duyệt qua nhiều class riêng lẻ lúc runtime, merge class sẽ
# phá cấu trúc mà nó cần.
-keep,allowobfuscation class org.apache.sshd.common.** { *; }
-keep,allowobfuscation class org.apache.sshd.server.** { *; }

# BUILD FAILED (R8 fullMode): "Missing class" cho javax.el.* và org.ietf.jgss.* — đây KHÔNG
# phải thiếu dependency thật, mà là 2 API chỉ tồn tại trên JDK desktop, không có trên Android
# runtime:
#  - javax.el.* (Expression Language) bị net.engio.mbassy (MBassador, dependency gián tiếp
#    của sshd-core) tham chiếu trong 1 nhánh tính năng tùy chọn (EL-based message filtering)
#    mà app này không dùng tới.
#  - org.ietf.jgss.* (GSSAPI/Kerberos) bị smbj (SMB client) tham chiếu trong nhánh xác thực
#    Kerberos tùy chọn — app chỉ dùng NTLM/user-password nên nhánh này không chạy tới.
# Compile-time các class thật SỰ không tồn tại trong classpath Android (đúng như thiết kế),
# nên -keep là vô nghĩa ở đây (không có gì để giữ) — phải dùng -dontwarn để R8 fullMode
# không coi đây là lỗi cứng và dừng build, vì các nhánh code này không bao giờ được gọi tới
# lúc runtime trên Android.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn net.engio.mbassy.**

# Gson (dùng để lưu JSON trong SecurePrefs)
-keepattributes Signature
-keepattributes *Annotation*
# Thư viện Gson gốc (com.google.gson) cũng dùng reflection tạo instance các class @Key/@Expose —
# GsonFactory của Google API Client (bên trên) build trên nền thư viện Gson thật này, nên cần giữ
# nguyên cả 2 lớp để tránh đúng lỗi "cannot be instantiated ... no accessible default constructor"
# xảy ra ở tầng Gson thay vì tầng Google API Client.
-keep class com.google.gson.** { *; }
-keepclassmembers class com.google.gson.** { *; }
-dontwarn com.google.gson.**
# QUAN TRỌNG: package app đã đổi từ com.example.ftpmanager -> com.myfile.ui từ lâu, nhưng rule
# này vẫn trỏ tên cũ nên KHÔNG bảo vệ được field name của các data class Gson hiện tại
# (DropboxListFolderRequest, DropboxEntry, BoxEntry...). Ở bản release (isMinifyEnabled=true),
# R8 đổi tên field -> Gson serialize sai key JSON (vd "path" bị đổi thành "a") -> Dropbox trả
# "required Path is missing", Box cũng lỗi tương tự do response bị parse sai.
-keep class com.myfile.ui.model.** { *; }
-keep class com.myfile.ui.cloud.** { *; }

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keepattributes Exceptions

# Google API Client — KHÔNG chỉ -dontwarn, phải -keep toàn bộ package com.google.api.client vì
# thư viện này dùng reflection nặng để tự new instance các class nội bộ lúc runtime (GsonFactory
# tạo JsonObjectParser, AbstractGoogleJsonClient tạo request/response type qua Class.newInstance,
# v.v.). Rule cũ chỉ có "-dontwarn com.google.api.client.**" (chỉ tắt cảnh báo build, KHÔNG giữ gì
# cả) + "-keep class com.google.api.services.drive.** " (chỉ giữ model Drive, không giữ phần lõi
# client) — R8 fullMode vẫn tự do đổi tên/xoá constructor mặc định của các class lõi trong
# com.google.api.client.**, dẫn đúng lỗi đang gặp: "IllegalArgumentException: key error" bọc
# "InstantiationException: java.lang.Class<v4.a> cannot be instantiated ... because it is
# abstract and because it has no accessible default constructor" — v4.a chính là 1 class nội bộ
# của thư viện đã bị R8 đổi tên/xoá mất constructor, KHÔNG liên quan gì tới cấu hình API key/OAuth
# Client (đã đúng từ trước) — bug này thuần túy là thiếu ProGuard rule cho bản release.
-keep class com.google.api.client.** { *; }
-keepclassmembers class com.google.api.client.** { *; }
-keep interface com.google.api.client.** { *; }
-dontwarn com.google.api.client.**
-keep class com.google.api.services.drive.** { *; }
-keepclassmembers class com.google.api.services.drive.** { *; }

# AppAuth
-keep class net.openid.appauth.** { *; }
