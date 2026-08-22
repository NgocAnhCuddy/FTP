package com.myfile.ui.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import com.myfile.ui.BuildConfig
import com.myfile.ui.model.CloudProvider
import com.myfile.ui.util.LogBus
import com.myfile.ui.util.SecurePrefs
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.ClientSecretPost
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Quản lý luồng OAuth2 (Authorization Code + PKCE) cho các dịch vụ dùng chuẩn OAuth2 thường:
 * Dropbox, Box.
 *
 * Google Drive dùng Google Sign-In SDK riêng (xem GoogleDriveService), không qua lớp này.
 *
 * Redirect URI dùng chung: com.myfile.ui.oauth://callback
 * (đã khai báo RedirectUriReceiverActivity trong AndroidManifest.xml)
 */
class OAuthManager(private val context: Context) {

    private val redirectUri = android.net.Uri.parse("com.myfile.ui.oauth://callback")
    private val authService = AuthorizationService(context)
    private val prefs = SecurePrefs.getInstance(context)

    private fun configFor(provider: CloudProvider): AuthorizationServiceConfiguration = when (provider) {
        CloudProvider.DROPBOX -> AuthorizationServiceConfiguration(
            android.net.Uri.parse("https://www.dropbox.com/oauth2/authorize"),
            android.net.Uri.parse("https://api.dropboxapi.com/oauth2/token")
        )
        CloudProvider.BOX -> AuthorizationServiceConfiguration(
            android.net.Uri.parse("https://account.box.com/api/oauth2/authorize"),
            android.net.Uri.parse("https://api.box.com/oauth2/token")
        )
        CloudProvider.GOOGLE_DRIVE -> throw IllegalArgumentException("Google Drive dùng Google Sign-In riêng")
    }

    private fun clientIdFor(provider: CloudProvider): String = when (provider) {
        CloudProvider.DROPBOX -> BuildConfig.DROPBOX_APP_KEY
        CloudProvider.BOX -> BuildConfig.BOX_CLIENT_ID
        CloudProvider.GOOGLE_DRIVE -> ""
    }

    /**
     * Box đăng ký app dạng "confidential client": bước đổi authorization code lấy access token
     * BẮT BUỘC phải kèm client_secret, nếu không Box trả lỗi "client credentials are invalid"
     * (đây chính là lỗi đang gặp). Dropbox dùng PKCE cho public client nên KHÔNG cần/không nên
     * gửi client_secret (app di động không thể giữ bí mật secret an toàn).
     */
    private fun clientAuthFor(provider: CloudProvider): ClientAuthentication? = when (provider) {
        CloudProvider.BOX -> ClientSecretPost(BuildConfig.BOX_CLIENT_SECRET)
        CloudProvider.DROPBOX -> null
        CloudProvider.GOOGLE_DRIVE -> null
    }

    private fun scopesFor(provider: CloudProvider): String = when (provider) {
        CloudProvider.DROPBOX -> "" // Dropbox quản lý scope qua App Console, không cần truyền
        CloudProvider.BOX -> ""     // Box mặc định trả full scope theo app config
        CloudProvider.GOOGLE_DRIVE -> ""
    }

    /** Tạo Intent để mở màn hình đăng nhập OAuth trong trình duyệt/CustomTabs. */
    fun buildAuthIntent(provider: CloudProvider): Intent {
        val builder = AuthorizationRequest.Builder(
            configFor(provider),
            clientIdFor(provider),
            ResponseTypeValues.CODE,
            redirectUri
        )
        val scope = scopesFor(provider)
        if (scope.isNotBlank()) builder.setScope(scope)

        val request = builder.build()
        return authService.getAuthorizationRequestIntent(request)
    }

    /** Gọi trong onActivityResult / ActivityResultCallback sau khi user đăng nhập xong. */
    suspend fun handleAuthResponse(intent: Intent, provider: CloudProvider): Result<Unit> {
        val response = AuthorizationResponse.fromIntent(intent)
        val error = AuthorizationException.fromIntent(intent)

        if (response == null) {
            val msg = error?.errorDescription ?: "Người dùng đã hủy đăng nhập"
            LogBus.warning("Liên kết $provider thất bại: $msg")
            return Result.failure(Exception(msg))
        }

        val tokenRequest = response.createTokenExchangeRequest()
        val clientAuth = clientAuthFor(provider)

        return suspendCoroutine { cont ->
            val callback = { tokenResponse: TokenResponse?, ex: AuthorizationException? ->
                if (tokenResponse != null) {
                    val expiresAt = tokenResponse.accessTokenExpirationTime ?: 0L
                    prefs.saveCloudToken(
                        provider,
                        tokenResponse.accessToken ?: "",
                        tokenResponse.refreshToken,
                        expiresAt
                    )
                    LogBus.success("Đã liên kết tài khoản $provider")
                    cont.resume(Result.success(Unit))
                } else {
                    LogBus.error("Lỗi đổi token $provider: ${ex?.errorDescription}")
                    cont.resume(Result.failure(Exception(ex?.errorDescription ?: "Lỗi xác thực")))
                }
            }
            if (clientAuth != null) {
                authService.performTokenRequest(tokenRequest, clientAuth, callback)
            } else {
                authService.performTokenRequest(tokenRequest, callback)
            }
        }
    }

    fun dispose() {
        authService.dispose()
    }

    companion object {
        /**
         * Làm mới access token bằng refresh token đã lưu — gọi TRỰC TIẾP tới token endpoint
         * bằng OkHttp thường (KHÔNG qua AuthorizationService của AppAuth). Lý do: hàm này được
         * gọi từ CloudTokenAuthenticator (xem RetrofitFactory.kt), và theo đúng hợp đồng của
         * okhttp3.Authenticator, authenticate() BẮT BUỘC là hàm đồng bộ/blocking — trong khi
         * AuthorizationService.performTokenRequest() của AppAuth chỉ có bản callback bất đồng
         * bộ, muốn dùng lại sẽ phải bọc thêm runBlocking/suspendCancellableCoroutine phức tạp
         * và có nguy cơ deadlock nếu lỡ chạy trên main thread. Trong khi đó refresh token
         * (grant_type=refresh_token) chỉ là 1 POST request OAuth2 thuần, không cần mở trình
         * duyệt/CustomTabs như bước đăng nhập ban đầu, nên gọi thẳng bằng OkHttp đồng bộ ở đây
         * vừa an toàn vừa đơn giản hơn nhiều.
         *
         * BUG ĐANG SỬA: đây chính là nguyên nhân lỗi "liên kết Cloud được vài tiếng là báo lỗi,
         * phải liên kết lại". accessToken của Dropbox/Box có thời hạn (Box ~1 giờ, Dropbox ~4
         * giờ theo mặc định), nhưng trước đây app chỉ LƯU refreshToken lúc đăng nhập lần đầu
         * (xem handleAuthResponse ở trên) mà KHÔNG BAO GIỜ dùng nó để xin access token mới —
         * RetrofitFactory chỉ gắn access token cũ vào mọi request bất kể đã hết hạn hay chưa.
         * Khi hết hạn, provider trả 401 cho mọi lệnh gọi (listFiles/upload/download/...), người
         * dùng phải tự vào mục Cloud bấm hủy liên kết rồi liên kết lại từ đầu (mở lại trình
         * duyệt đăng nhập) mới có access token mới — đúng triệu chứng đang gặp.
         *
         * @return access token mới nếu thành công, null nếu refresh thất bại. Nếu lỗi xác nhận
         * refresh token không còn hợp lệ (400/401 — đã bị thu hồi hoặc hết hạn), token đã lưu sẽ
         * bị xoá để isLinked() phản ánh đúng "chưa liên kết" thay vì tiếp tục hiện lầm "đã liên
         * kết" trong khi mọi thao tác đều âm thầm lỗi.
         */
        fun refreshAccessTokenBlocking(context: Context, provider: CloudProvider): String? {
            val prefs = SecurePrefs.getInstance(context)
            val refreshToken = prefs.getCloudRefreshToken(provider)
            if (refreshToken.isNullOrEmpty()) return null

            val tokenUrl = when (provider) {
                CloudProvider.DROPBOX -> "https://api.dropboxapi.com/oauth2/token"
                CloudProvider.BOX -> "https://api.box.com/oauth2/token"
                // Google Drive tự làm mới token ngầm qua GoogleAccountCredential/Play Services,
                // không đi qua luồng AppAuth này nên không có gì để refresh ở đây.
                CloudProvider.GOOGLE_DRIVE -> return null
            }
            val clientId = when (provider) {
                CloudProvider.DROPBOX -> BuildConfig.DROPBOX_APP_KEY
                CloudProvider.BOX -> BuildConfig.BOX_CLIENT_ID
                CloudProvider.GOOGLE_DRIVE -> ""
            }

            val formBuilder = okhttp3.FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
            // Box là confidential client (giống lúc đổi authorization code lấy token ban đầu ở
            // handleAuthResponse) nên bước refresh cũng BẮT BUỘC kèm client_secret. Dropbox dùng
            // PKCE cho public client nên không gửi/không cần client_secret.
            if (provider == CloudProvider.BOX) {
                formBuilder.add("client_secret", BuildConfig.BOX_CLIENT_SECRET)
            }

            val request = okhttp3.Request.Builder().url(tokenUrl).post(formBuilder.build()).build()

            return try {
                okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string()
                    if (!response.isSuccessful || bodyStr.isNullOrEmpty()) {
                        LogBus.warning(
                            "Làm mới token $provider thất bại (${response.code}) — refresh token có thể đã bị thu hồi/hết hạn, cần liên kết lại",
                            "OAUTH_REFRESH"
                        )
                        if (response.code == 400 || response.code == 401) {
                            prefs.clearCloudToken(provider)
                        }
                        return null
                    }
                    val json = com.google.gson.JsonParser.parseString(bodyStr).asJsonObject
                    val newAccessToken = json.get("access_token")?.takeIf { !it.isJsonNull }?.asString
                    if (newAccessToken.isNullOrEmpty()) return null
                    // Dropbox thường KHÔNG trả refresh_token mới ở bước refresh (refresh token
                    // gốc còn hiệu lực vô thời hạn tới khi bị thu hồi) — giữ token cũ nếu response
                    // không có cái mới, tránh vô tình xoá mất refresh token đang dùng tốt.
                    val newRefreshToken = json.get("refresh_token")?.takeIf { !it.isJsonNull }?.asString
                        ?: refreshToken
                    val expiresIn = json.get("expires_in")?.takeIf { !it.isJsonNull }?.asLong ?: 3600L
                    val expiresAt = System.currentTimeMillis() + expiresIn * 1000
                    prefs.saveCloudToken(provider, newAccessToken, newRefreshToken, expiresAt)
                    LogBus.success("Đã tự động làm mới token $provider", "OAUTH_REFRESH")
                    newAccessToken
                }
            } catch (e: Exception) {
                LogBus.error("Lỗi mạng khi làm mới token $provider", "OAUTH_REFRESH", e)
                null
            }
        }
    }
}
