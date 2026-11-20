package com.theohowie.heracles.data

import com.theohowie.heracles.bridge.KeystoreVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GitHubAuthService(private val vault: KeystoreVault) {

    data class DeviceCodeInfo(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSec: Int,
        val expiresInSec: Int,
    )

    data class GitHubUser(
        val login: String,
        val avatarUrl: String,
    )

    data class GitHubRepo(
        val fullName: String,
        val cloneUrl: String,
        val isPrivate: Boolean,
        val defaultBranch: String,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val _user = MutableStateFlow<GitHubUser?>(null)
    val user: StateFlow<GitHubUser?> = _user.asStateFlow()

    val isSignedIn: Boolean get() = vault.contains(TOKEN_KEY)

    fun getToken(): String = vault.read(TOKEN_KEY)

    fun signOut() {
        vault.delete(TOKEN_KEY)
        _user.value = null
    }

    suspend fun refreshUser(): Result<GitHubUser> = withContext(Dispatchers.IO) {
        val token = getToken()
        if (token.isBlank()) return@withContext Result.failure(IllegalStateException("غير مسجل الدخول إلى GitHub"))
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/user")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401) {
                    signOut()
                    error("انتهت صلاحية تفويض GitHub، يرجى تسجيل الدخول مجدداً")
                }
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val obj = JSONObject(resp.body?.string().orEmpty())
                val u = GitHubUser(
                    login = obj.optString("login"),
                    avatarUrl = obj.optString("avatar_url"),
                )
                _user.value = u
                u
            }
        }
    }

    suspend fun requestDeviceCode(): Result<DeviceCodeInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val form = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("scope", SCOPES)
                .build()
            val req = Request.Builder()
                .url("https://github.com/login/device/code")
                .header("Accept", "application/json")
                .post(form)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("فشل الحصول على رمز التفويض: HTTP ${resp.code}")
                val obj = JSONObject(resp.body?.string().orEmpty())
                DeviceCodeInfo(
                    deviceCode = obj.getString("device_code"),
                    userCode = obj.getString("user_code"),
                    verificationUri = obj.optString("verification_uri", VERIFICATION_URI),
                    intervalSec = obj.optInt("interval", 5),
                    expiresInSec = obj.optInt("expires_in", 900),
                )
            }
        }
    }

    suspend fun pollForAccessToken(info: DeviceCodeInfo): Result<GitHubUser> = withContext(Dispatchers.IO) {
        var waitSec = info.intervalSec.coerceAtLeast(5)
        val deadline = System.currentTimeMillis() + info.expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(waitSec * 1000L)
            val form = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("device_code", info.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            val req = Request.Builder()
                .url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .post(form)
                .build()
            val obj = runCatching {
                http.newCall(req).execute().use { resp ->
                    JSONObject(resp.body?.string().orEmpty())
                }
            }.getOrNull() ?: continue

            val token = obj.optString("access_token")
            if (token.isNotBlank()) {
                vault.write(TOKEN_KEY, token)
                return@withContext refreshUser()
            }
            when (obj.optString("error")) {
                "authorization_pending" -> continue
                "slow_down" -> {
                    waitSec += 5
                    continue
                }
                "expired_token" -> return@withContext Result.failure(IllegalStateException("انتهت صلاحية رمز التحقق، يرجى المحاولة مجدداً"))
                "access_denied" -> return@withContext Result.failure(IllegalStateException("ألغى المستخدم التفويض"))
                else -> {
                    val desc = obj.optString("error_description").ifBlank { obj.optString("error") }
                    if (desc.isNotBlank()) return@withContext Result.failure(IllegalStateException(desc))
                }
            }
        }
        Result.failure(IllegalStateException("انتهت مهلة انتظار التفويض"))
    }

    suspend fun listRepos(): Result<List<GitHubRepo>> = withContext(Dispatchers.IO) {
        val token = getToken()
        if (token.isBlank()) return@withContext Result.failure(IllegalStateException("غير مسجل الدخول إلى GitHub"))
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/user/repos?sort=updated&per_page=50")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("فشل جلب قائمة المستودعات: HTTP ${resp.code}")
                val arr = JSONArray(resp.body?.string().orEmpty())
                (0 until arr.length()).mapNotNull { idx ->
                    val o = arr.optJSONObject(idx) ?: return@mapNotNull null
                    GitHubRepo(
                        fullName = o.optString("full_name"),
                        cloneUrl = o.optString("clone_url"),
                        isPrivate = o.optBoolean("private", false),
                        defaultBranch = o.optString("default_branch", "main"),
                    )
                }
            }
        }
    }

    companion object {
        const val CLIENT_ID = "Ov23linhwuqRGaF0QYT6"
        const val SCOPES = "repo read:user"
        const val VERIFICATION_URI = "https://github.com/login/device"
        const val REVOKE_URI = "https://github.com/settings/applications"
        private const val TOKEN_KEY = "heracles.github.oauth.token"
    }
}
