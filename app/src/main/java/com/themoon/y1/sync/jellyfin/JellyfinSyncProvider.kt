package com.themoon.y1.sync.jellyfin

import android.content.Context
import android.content.SharedPreferences
import com.themoon.y1.sync.MediaSyncProvider
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Jellyfin implementation of MediaSyncProvider.
 */
class JellyfinSyncProvider private constructor(context: Context) : MediaSyncProvider {

    private val context: Context = context.applicationContext
    private val prefs: SharedPreferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getId(): String = "jellyfin"

    override fun getDisplayName(): String = "Jellyfin"

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(url) {
            prefs.edit().putString("server_url", JellyfinClient.normalizeUrl(url)).apply()
        }

    var authMode: String
        get() = prefs.getString("auth_mode", "quickconnect") ?: "quickconnect"
        set(mode) {
            prefs.edit().putString("auth_mode", mode).apply()
        }

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(username) {
            prefs.edit().putString("username", username).apply()
        }

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(password) {
            prefs.edit().putString("password", password).apply()
        }

    var accessToken: String
        get() = prefs.getString("access_token", "") ?: ""
        set(token) {
            prefs.edit().putString("access_token", token).apply()
        }

    var userId: String
        get() = prefs.getString("user_id", "") ?: ""
        set(userId) {
            prefs.edit().putString("user_id", userId).apply()
        }

    var serverName: String
        get() = prefs.getString("server_name", "") ?: ""
        set(serverName) {
            prefs.edit().putString("server_name", serverName).apply()
        }

    var isDownloadCoversEnabled: Boolean
        get() = prefs.getBoolean("download_covers", true)
        set(enabled) {
            prefs.edit().putBoolean("download_covers", enabled).apply()
        }

    var isRemoveDeletedEnabled: Boolean
        get() = prefs.getBoolean("remove_deleted", false)
        set(enabled) {
            prefs.edit().putBoolean("remove_deleted", enabled).apply()
        }

    var isTranscodeVideoEnabled: Boolean
        get() = prefs.getBoolean("transcode_video", false)
        set(enabled) {
            prefs.edit().putBoolean("transcode_video", enabled).apply()
        }

    val deviceId: String
        get() {
            var id = prefs.getString("device_id", "") ?: ""
            if (id.isEmpty()) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString("device_id", id).apply()
            }
            return id
        }

    override fun isConfigured(): Boolean {
        val url = serverUrl
        if (url.isEmpty()) return false
        return if ("token".equals(authMode, ignoreCase = true) || "quickconnect".equals(authMode, ignoreCase = true)) {
            accessToken.isNotEmpty()
        } else {
            username.isNotEmpty() && (password.isNotEmpty() || accessToken.isNotEmpty())
        }
    }

    override fun getStatusSummary(): String {
        if (!isConfigured()) {
            return "Not Configured"
        }
        val user = username
        if (user.isNotEmpty()) {
            return "@$user"
        }
        return "Connected"
    }

    fun createClient(): JellyfinClient {
        return JellyfinClient(serverUrl, deviceId)
    }

    override fun testConnection(callback: MediaSyncProvider.ConnectionCallback) {
        Thread {
            try {
                val url = serverUrl
                if (url.isEmpty()) {
                    callback.onError("Server URL is empty")
                    return@Thread
                }

                val client = createClient()
                val auth: JellyfinClient.AuthResult
                if ("token".equals(authMode, ignoreCase = true) || "quickconnect".equals(authMode, ignoreCase = true)) {
                    if (accessToken.isEmpty()) {
                        callback.onError("Not paired via QuickConnect yet")
                        return@Thread
                    }
                    auth = client.verifyToken(accessToken)
                } else {
                    auth = client.authenticateByName(username, password)
                    accessToken = auth.accessToken
                }

                userId = auth.userId
                serverName = auth.serverName
                try {
                    val libs = client.fetchLibraries(accessToken, auth.userId, null)
                    if (libs.isNotEmpty()) {
                        setCachedLibraries(libs)
                    }
                } catch (ignored: Exception) {}
                callback.onSuccess(auth.serverName, "OK")
            } catch (e: Exception) {
                callback.onError(e.message ?: "Connection failed")
            }
        }.start()
    }

    @Synchronized
    @Throws(Exception::class)
    fun ensureValidAccessToken(): String {
        val token = accessToken
        if ("credentials".equals(authMode, ignoreCase = true)) {
            val client = createClient()
            try {
                if (token.isNotEmpty()) {
                    client.verifyToken(token)
                    return token
                }
            } catch (ignored: Exception) {
                // Token invalid or expired, re-authenticate using saved username and password
            }
            if (username.isNotEmpty() && password.isNotEmpty()) {
                val auth = client.authenticateByName(username, password)
                accessToken = auth.accessToken
                userId = auth.userId
                serverName = auth.serverName
                return auth.accessToken
            }
        } else {
            if (userId.isEmpty() && token.isNotEmpty()) {
                val client = createClient()
                try {
                    val auth = client.verifyToken(token)
                    if (auth.userId.isNotEmpty()) {
                        userId = auth.userId
                    }
                    if (auth.serverName.isNotEmpty()) {
                        serverName = auth.serverName
                    }
                } catch (ignored: Exception) {}
            }
        }
        return token
    }

    @Synchronized
    fun getCachedLibraries(): List<MediaSyncProvider.LibraryInfo> {
        val jsonStr = prefs.getString("cached_libraries", "")
        val list = ArrayList<MediaSyncProvider.LibraryInfo>()
        if (jsonStr.isNullOrEmpty()) return list
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    MediaSyncProvider.LibraryInfo(
                        obj.optString("id", ""),
                        obj.optString("name", ""),
                        obj.optString("collectionType", "")
                    )
                )
            }
        } catch (ignored: Exception) {}
        return list
    }

    @Synchronized
    fun setCachedLibraries(libraries: List<MediaSyncProvider.LibraryInfo>?) {
        if (libraries == null) return
        try {
            val arr = JSONArray()
            for (lib in libraries) {
                val obj = JSONObject()
                obj.put("id", lib.id)
                obj.put("name", lib.name)
                obj.put("collectionType", lib.collectionType)
                arr.put(obj)
            }
            prefs.edit().putString("cached_libraries", arr.toString()).apply()
        } catch (ignored: Exception) {}
    }

    @Throws(Exception::class)
    override fun fetchLibraries(token: MediaSyncProvider.CancellationToken?): List<MediaSyncProvider.LibraryInfo> {
        val validToken = ensureValidAccessToken()
        val client = createClient()
        val libs = client.fetchLibraries(validToken, userId, token)
        if (libs.isNotEmpty()) {
            setCachedLibraries(libs)
        }
        return libs
    }

    override fun clearConfiguration() {
        prefs.edit()
            .remove("server_url")
            .remove("auth_mode")
            .remove("username")
            .remove("password")
            .remove("access_token")
            .remove("user_id")
            .remove("server_name")
            .remove("cached_libraries")
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "Y1_JELLYFIN"

        @Volatile
        private var instance: JellyfinSyncProvider? = null
        @Synchronized
        fun getInstance(context: Context): JellyfinSyncProvider {
            var inst = instance
            if (inst == null) {
                inst = JellyfinSyncProvider(context)
                instance = inst
            }
            return inst
        }
    }
}
