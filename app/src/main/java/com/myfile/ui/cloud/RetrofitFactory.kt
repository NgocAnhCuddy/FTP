package com.myfile.ui.cloud

import com.myfile.ui.model.CloudProvider
import com.myfile.ui.util.SecurePrefs
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import android.content.Context

/** Tạo Retrofit client có tự động gắn header Authorization: Bearer <token> cho từng provider. */
object RetrofitFactory {

    private fun authInterceptor(context: Context, provider: CloudProvider): Interceptor = Interceptor { chain ->
        val token = SecurePrefs.getInstance(context).getCloudAccessToken(provider)
        val request = chain.request().newBuilder().apply {
            if (!token.isNullOrEmpty()) {
                addHeader("Authorization", "Bearer $token")
            }
            // Dropbox API v2 bắt buộc Content-Type: application/json trên các endpoint RPC dạng
            // POST (list_folder, create_folder_v2, delete_v2, get_space_usage...) — Retrofit +
            // GsonConverterFactory không tự set header này khi @Body là data class thường, dẫn
            // tới lỗi "Lỗi Dropbox: 400" đang gặp. Box API không cần header này nên chỉ set khi
            // có body và endpoint là Dropbox.
            if (provider == CloudProvider.DROPBOX && chain.request().body != null) {
                addHeader("Content-Type", "application/json")
            }
        }.build()
        chain.proceed(request) as Response
    }

    /**
     * Tự động làm mới access token khi request bị Dropbox/Box từ chối với 401 (token hết hạn),
     * rồi TỰ ĐỘNG GỬI LẠI request đó với token mới — người dùng không hề hay biết, không phải
     * vào Cloud bấm hủy liên kết rồi liên kết lại. Đây là mảnh còn thiếu khiến trước đây
     * accessToken hết hạn sau vài tiếng là mọi thao tác Cloud báo lỗi (xem chú thích chi tiết ở
     * OAuthManager.refreshAccessTokenBlocking).
     *
     * Đồng bộ hoá (synchronized) theo TỪNG provider: nếu nhiều request cùng lúc đều dính 401 (ví
     * dụ đang tải song song vài file), CHỈ luồng đầu tiên thực sự gọi endpoint làm mới token —
     * các luồng sau chờ, rồi dùng luôn token mới vừa lưu thay vì mỗi luồng tự gọi làm mới riêng.
     * Quan trọng vì refresh token ở một số provider chỉ dùng được 1 lần (rotate) — gọi làm mới
     * song song nhiều lần có thể khiến các lần gọi sau nhận refresh token đã bị vô hiệu bởi lần
     * gọi trước, làm hỏng phiên đăng nhập thay vì sửa lỗi.
     */
    private class CloudTokenAuthenticator(
        private val context: Context,
        private val provider: CloudProvider
    ) : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            // Đã thử làm mới rồi mà vẫn 401 -> refresh token cũng không dùng được nữa (đã bị
            // OAuthManager.refreshAccessTokenBlocking xoá khỏi SecurePrefs), dừng lại để lỗi 401
            // nổi lên bình thường thay vì lặp vô hạn.
            if (responseCount(response) >= 2) return null

            val prefs = SecurePrefs.getInstance(context)
            val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

            return synchronized(lockFor(provider)) {
                val currentToken = prefs.getCloudAccessToken(provider)
                val tokenToUse = if (!currentToken.isNullOrEmpty() && currentToken != failedToken) {
                    // Luồng khác vừa làm mới xong trong lúc luồng này chờ lock -> dùng luôn.
                    currentToken
                } else {
                    OAuthManager.refreshAccessTokenBlocking(context, provider)
                }
                tokenToUse?.let {
                    response.request.newBuilder().header("Authorization", "Bearer $it").build()
                }
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    // Khởi tạo sẵn lock cho cả 3 provider ngay từ đầu (thay vì tạo "lười" lúc cần) — vì
    // CloudProvider chỉ có 3 giá trị cố định nên không tốn kém gì, và tránh hẳn 1 race condition
    // hiếm gặp: nếu tạo lười bằng getOrPut, 2 luồng cùng lúc gọi lần ĐẦU TIÊN cho cùng 1 provider
    // có thể mỗi luồng tạo 1 Any() khác nhau trước khi kịp ghi vào map, khiến 1 trong 2 luồng
    // đồng bộ hoá nhầm trên lock "mồ côi" không ai khác dùng, làm mất tác dụng chống refresh
    // song song đúng lúc cần nhất (lần đầu token hết hạn).
    private val providerLocks: Map<CloudProvider, Any> = CloudProvider.values().associateWith { Any() }
    private fun lockFor(provider: CloudProvider): Any = providerLocks.getValue(provider)

    private fun client(context: Context, provider: CloudProvider): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val builder = OkHttpClient.Builder()
            .addInterceptor(authInterceptor(context, provider))
            .addInterceptor(logging)
        // Google Drive không đi qua client này trong thực tế (GoogleDriveService tự dùng Drive
        // SDK riêng), nhưng vẫn chặn tường minh ở đây: chỉ gắn Authenticator cho Dropbox/Box.
        if (provider != CloudProvider.GOOGLE_DRIVE) {
            builder.authenticator(CloudTokenAuthenticator(context, provider))
        }
        return builder.build()
    }

    fun dropbox(context: Context): DropboxApi = Retrofit.Builder()
        .baseUrl("https://api.dropboxapi.com/")
        .client(client(context, CloudProvider.DROPBOX))
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(DropboxApi::class.java)

    fun box(context: Context): BoxApi = Retrofit.Builder()
        .baseUrl("https://api.box.com/")
        .client(client(context, CloudProvider.BOX))
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(BoxApi::class.java)

    fun okHttpFor(context: Context, provider: CloudProvider): OkHttpClient = client(context, provider)
}
