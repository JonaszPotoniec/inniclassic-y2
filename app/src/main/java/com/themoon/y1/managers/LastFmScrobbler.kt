package com.themoon.y1.managers

import android.content.Context
import android.content.SharedPreferences
import com.themoon.y1.BuildConfig
import com.themoon.y1.StoragePaths
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.TreeMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Last.fm scrobbler managing local .scrobbler.log auditing and remote API queue.
 * It does not decide when a play counts: [PlayHistoryManager] calls [onPlayCounted].
 */
class LastFmScrobbler private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("Y1_LASTFM", Context.MODE_PRIVATE)
    private val client: OkHttpClient = buildHttpClient()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    private var curArtist: String? = null
    private var curTitle: String? = null
    private var curAlbum: String? = null
    private var curFilePath: String? = null
    private var curDurationMs: Long = 0L
    private var curStartedAtUnixSec: Long = 0L
    private var curSubmitted: Boolean = true

    fun isEnabled(): Boolean {
        return prefs.getBoolean("enabled", false)
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
    }

    fun isLoggedIn(): Boolean {
        return prefs.getString("session_key", null) != null
    }

    fun getUsername(): String {
        return prefs.getString("username", "") ?: ""
    }

    fun logout() {
        prefs.edit().remove("session_key").remove("username").apply()
    }

    interface LoginCallback {
        fun onResult(success: Boolean, message: String?)
    }

    fun login(username: String, password: String, callback: LoginCallback?) {
        worker.execute {
            try {
                val params = TreeMap<String, String>()
                params["method"] = "auth.getMobileSession"
                params["api_key"] = API_KEY
                params["username"] = username
                params["password"] = password
                val sig = sign(params)

                val form = FormBody.Builder()
                    .add("method", "auth.getMobileSession")
                    .add("api_key", API_KEY)
                    .add("username", username)
                    .add("password", password)
                    .add("api_sig", sig)
                    .add("format", "json")
                    .build()

                val request = Request.Builder().url(API_ROOT).post(form).build()
                client.newCall(request).execute().use { response ->
                    val body = response.body()?.string() ?: ""
                    val json = JSONObject(body)
                    if (response.isSuccessful && json.has("session")) {
                        val session = json.getJSONObject("session")
                        prefs.edit()
                            .putString("session_key", session.getString("key"))
                            .putString("username", session.getString("name"))
                            .apply()
                        setEnabled(true)
                        callback?.onResult(true, session.getString("name"))
                        flushPendingQueue()
                    } else {
                        val msg = if (json.has("message")) json.getString("message") else "Login failed"
                        callback?.onResult(false, msg)
                    }
                }
            } catch (e: Exception) {
                callback?.onResult(false, e.message)
            }
        }
    }

    fun onTrackStart(artist: String?, title: String?, album: String?, durationMs: Long, filePath: String?) {
        curSubmitted = true
        if (filePath == null || isExcludedPath(filePath)) return
        if (title == null || title.trim().isEmpty()) return

        curArtist = if (artist.isNullOrBlank()) "Unknown Artist" else artist.trim()
        curTitle = title.trim()
        curAlbum = album?.trim() ?: ""
        curFilePath = filePath
        curDurationMs = durationMs
        curStartedAtUnixSec = System.currentTimeMillis() / 1000L
        curSubmitted = false

        if (isEnabled() && isLoggedIn()) {
            updateNowPlaying(curArtist ?: "", curTitle ?: "", curAlbum ?: "", (curDurationMs / 1000).toInt())
        }
    }

    fun rearmCurrentTrack() {
        if (curFilePath == null) return
        curStartedAtUnixSec = System.currentTimeMillis() / 1000L
        curSubmitted = false
    }

    /**
     * A play of [filePath] counts ([PlayCountRule], from [PlayHistoryManager]): scrobble the current track
     * once, if it is that file and not shorter than 30 s. The timestamp is the track start, as before.
     */
    fun onPlayCounted(filePath: String) {
        if (filePath != curFilePath) return
        val scrobble = isScrobble(curSubmitted, curDurationMs)
        curSubmitted = true
        if (!scrobble) return
        val durationSec = curDurationMs / 1000
        val artist = curArtist ?: "Unknown Artist"
        val album = curAlbum ?: ""
        val title = curTitle ?: ""
        val startedAt = curStartedAtUnixSec

        appendToScrobblerLog(artist, album, title, durationSec, startedAt)

        if (isEnabled()) {
            enqueuePendingScrobble(artist, album, title, durationSec, startedAt)
            worker.execute {
                flushPendingQueue()
            }
        }
    }

    private fun isExcludedPath(path: String): Boolean {
        return StoragePaths.isUnderAudiobooks(path) || path.contains("/Podcasts") || path.startsWith("/PODCAST_STREAM")
    }

    private fun updateNowPlaying(artist: String, title: String, album: String, durationSec: Int) {
        val sk = prefs.getString("session_key", null) ?: return
        worker.execute {
            try {
                val params = TreeMap<String, String>()
                params["method"] = "track.updateNowPlaying"
                params["api_key"] = API_KEY
                params["sk"] = sk
                params["artist"] = artist
                params["track"] = title
                if (album.isNotEmpty()) params["album"] = album
                if (durationSec > 0) params["duration"] = durationSec.toString()
                val sig = sign(params)

                val form = FormBody.Builder()
                for ((key, value) in params) {
                    form.add(key, value)
                }
                form.add("api_sig", sig).add("format", "json")

                val request = Request.Builder().url(API_ROOT).post(form.build()).build()
                client.newCall(request).execute().close()
            } catch (ignored: Exception) {}
        }
    }

    @Synchronized
    private fun appendToScrobblerLog(
        artist: String,
        album: String,
        title: String,
        durationSec: Long,
        timestampSec: Long
    ) {
        try {
            val isNew = !SCROBBLE_LOG_FILE.exists()
            val parent = SCROBBLE_LOG_FILE.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val fw = FileWriter(SCROBBLE_LOG_FILE, true)
            try {
                if (isNew) {
                    fw.write("#AUDIOSCROBBLER/1.1\n")
                    fw.write("#TZ/UTC\n")
                    fw.write("#CLIENT/JJY 1.0\n")
                }
                fw.write(tsv(artist, album, title, "", durationSec.toString(), "L", timestampSec.toString(), ""))
                fw.write("\n")
            } finally {
                fw.close()
            }
        } catch (ignored: Exception) {}
    }

    @Synchronized
    private fun enqueuePendingScrobble(
        artist: String,
        album: String,
        title: String,
        durationSec: Long,
        timestampSec: Long
    ) {
        try {
            var queue = loadQueue()
            val entry = JSONObject()
            entry.put("artist", artist)
            entry.put("album", album)
            entry.put("title", title)
            entry.put("duration", durationSec)
            entry.put("timestamp", timestampSec)
            queue.put(entry)
            if (queue.length() > MAX_PENDING_QUEUE) {
                queue = queueTail(queue, queue.length() - MAX_PENDING_QUEUE)
            }
            prefs.edit().putString("pending_queue", queue.toString()).apply()
        } catch (ignored: Exception) {}
    }

    private fun loadQueue(): JSONArray {
        return JSONArray(prefs.getString("pending_queue", "[]"))
    }

    @Synchronized
    private fun flushPendingQueue() {
        val sk = prefs.getString("session_key", null) ?: return
        try {
            var queue = loadQueue()
            while (queue.length() > 0) {
                val entry = queue.getJSONObject(0)
                val ok = submitScrobble(
                    sk,
                    entry.getString("artist"),
                    entry.optString("album", ""),
                    entry.getString("title"),
                    entry.getLong("duration"),
                    entry.getLong("timestamp")
                )
                if (!ok) break
                queue = queueTail(queue, 1)
                prefs.edit().putString("pending_queue", queue.toString()).apply()
            }
            prefs.edit().putString("pending_queue", queue.toString()).apply()
        } catch (ignored: Exception) {}
    }

    private fun submitScrobble(
        sk: String,
        artist: String,
        album: String,
        title: String,
        durationSec: Long,
        timestampSec: Long
    ): Boolean {
        return try {
            val params = TreeMap<String, String>()
            params["method"] = "track.scrobble"
            params["api_key"] = API_KEY
            params["sk"] = sk
            params["artist"] = artist
            params["track"] = title
            params["timestamp"] = timestampSec.toString()
            if (album.isNotEmpty()) params["album"] = album
            val sig = sign(params)

            val form = FormBody.Builder()
            for ((key, value) in params) {
                form.add(key, value)
            }
            form.add("api_sig", sig).add("format", "json")

            val request = Request.Builder().url(API_ROOT).post(form.build()).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.body() == null) return false
                val result = JSONObject(response.body()!!.string())
                !result.has("error") && result.optJSONObject("scrobbles") != null
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private val API_KEY: String = BuildConfig.LASTFM_API_KEY
        private val API_SECRET: String = BuildConfig.LASTFM_API_SECRET

        /** True if a counted play of the current track is a scrobble: not yet sent, and 30 s or longer. */
        fun isScrobble(submitted: Boolean, durationMs: Long): Boolean =
            !submitted && durationMs / 1000 >= MIN_TRACK_LENGTH_SEC

        fun tsv(list: List<String?>): String {
            val sb = StringBuilder()
            for (i in list.indices) {
                if (i > 0) sb.append('\t')
                val f = list[i]
                sb.append(if (f == null) "" else f.replace("\t", " ").replace("\n", " "))
            }
            return sb.toString()
        }

        fun tsv(vararg fields: String?): String {
            return tsv(fields.toList())
        }

        fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (b in digest) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        }

        fun sign(params: Map<String, String>, secret: String = API_SECRET): String {
            val sorted = if (params is TreeMap) params else TreeMap(params)
            val sb = StringBuilder()
            for ((key, value) in sorted) {
                sb.append(key).append(value)
            }
            sb.append(secret)
            return md5(sb.toString())
        }

        private const val API_ROOT = "https://ws.audioscrobbler.com/2.0/"
        private val SCROBBLE_LOG_FILE: File = StoragePaths.primaryFile(".scrobbler.log")
        private const val MIN_TRACK_LENGTH_SEC = 30
        private const val MAX_PENDING_QUEUE = 500

        @Volatile
        private var instance: LastFmScrobbler? = null
        @Synchronized
        fun getInstance(context: Context? = null): LastFmScrobbler {
            var inst = instance
            if (inst == null) {
                val ctx = context?.applicationContext 
                    ?: com.themoon.y1.MainActivity.instance?.applicationContext
                    ?: throw IllegalStateException("Context is null and MainActivity instance is not available")
                inst = LastFmScrobbler(ctx)
                instance = inst
            }
            return inst
        }

        private fun buildHttpClient(): OkHttpClient {
            return try {
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                val sslContext = SSLContext.getInstance("TLS", "Conscrypt")
                sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())

                OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.socketFactory, trustAll)
                    .hostnameVerifier(HostnameVerifier { _, _ -> true })
                    .build()
            } catch (e: Exception) {
                OkHttpClient()
            }
        }

        private fun queueTail(source: JSONArray, start: Int): JSONArray {
            val result = JSONArray()
            for (i in start until source.length()) {
                result.put(source.get(i))
            }
            return result
        }
    }
}
