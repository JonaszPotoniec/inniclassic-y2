package com.themoon.y1.sync.jellyfin

import android.util.Log
import com.themoon.y1.sync.MediaSyncProvider
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * REST API client for Jellyfin with Conscrypt TLS and low-RAM streaming support.
 */
class JellyfinClient(rawUrl: String?, deviceId: String?) {

    private val baseUrl: String = normalizeUrl(rawUrl)
    private val deviceId: String = if (!deviceId.isNullOrEmpty()) deviceId else "InnioasisY2Device"
    private val httpClient: OkHttpClient = buildOkHttpClient()

    private fun buildOkHttpClient(): OkHttpClient {
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
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Conscrypt SSL init failed, fallback to default OkHttp: " + e.message)
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    private fun getAuthHeader(token: String?): String {
        val sb = StringBuilder("MediaBrowser Client=\"InniClassic\", Device=\"Innioasis Y2\", DeviceId=\"")
            .append(deviceId).append("\", Version=\"1.0\"")
        if (!token.isNullOrEmpty()) {
            sb.append(", Token=\"").append(token).append("\"")
        }
        return sb.toString()
    }

    private fun newRequestBuilder(url: String, token: String?): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .addHeader("Authorization", getAuthHeader(token))
            .addHeader("X-Emby-Authorization", getAuthHeader(token))
        if (!token.isNullOrEmpty()) {
            builder.addHeader("X-Emby-Token", token)
        }
        return builder
    }

    class AuthResult(
        val accessToken: String,
        val userId: String,
        val serverId: String,
        val serverName: String
    )

    /**
     * Authenticate using username and password.
     */
    @Throws(Exception::class)
    fun authenticateByName(username: String?, password: String?): AuthResult {
        val bodyJson = JSONObject()
        bodyJson.put("Username", username ?: "")
        bodyJson.put("Pw", password ?: "")

        val body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString())
        val request = newRequestBuilder("$baseUrl/Users/AuthenticateByName", null)
            .post(body)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code()
            var errorMsg = ""
            try {
                val respBody = response.body()
                if (respBody != null) {
                    errorMsg = respBody.string().trim()
                }
            } catch (ignored: Exception) {}
            response.close()
            if (code == 401) {
                throw Exception("Invalid username or password")
            } else {
                val detail = if (errorMsg.isEmpty()) "" else ": $errorMsg"
                throw Exception("Authentication failed (HTTP $code$detail)")
            }
        }

        val resStr = response.body()!!.string()
        val resObj = JSONObject(resStr)
        val token = resObj.getString("AccessToken")
        val serverId = resObj.optString("ServerId", "")

        val userObj = resObj.getJSONObject("User")
        val userId = userObj.getString("Id")
        val serverName = userObj.optString("Name", "Jellyfin")

        return AuthResult(token, userId, serverId, serverName)
    }

    class QuickConnectSession(
        val code: String,
        val secret: String,
        val authenticated: Boolean
    )

    /**
     * Check if QuickConnect is enabled on the server.
     */
    fun isQuickConnectEnabled(): Boolean {
        try {
            val request = newRequestBuilder("$baseUrl/QuickConnect/Enabled", null)
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful && response.body() != null) {
                val str = response.body()!!.string().trim()
                response.close()
                return str.toBoolean()
            }
            response.close()
        } catch (ignored: Exception) {}
        return false
    }

    /**
     * Initiate a QuickConnect session.
     */
    @Throws(Exception::class)
    fun initiateQuickConnect(): QuickConnectSession {
        val emptyBody = RequestBody.create(MediaType.parse("application/x-www-form-urlencoded"), "")
        val request = newRequestBuilder("$baseUrl/QuickConnect/Initiate", null)
            .post(emptyBody)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code()
            response.close()
            throw Exception("QuickConnect initiate failed (HTTP $code)")
        }

        val resStr = response.body()!!.string()
        val obj = JSONObject(resStr)
        val code = obj.optString("Code", "")
        val secret = obj.optString("Secret", "")
        val auth = obj.optBoolean("Authenticated", false)

        return QuickConnectSession(code, secret, auth)
    }

    /**
     * Poll state of QuickConnect session.
     */
    @Throws(Exception::class)
    fun checkQuickConnect(secret: String): QuickConnectSession {
        val url = "$baseUrl/QuickConnect/Connect?secret=" + URLEncoder.encode(secret, "UTF-8")
        val request = newRequestBuilder(url, null)
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code()
            response.close()
            throw Exception("QuickConnect poll failed (HTTP $code)")
        }

        val resStr = response.body()!!.string()
        val obj = JSONObject(resStr)
        val code = obj.optString("Code", "")
        val auth = obj.optBoolean("Authenticated", false)

        return QuickConnectSession(code, secret, auth)
    }

    /**
     * Exchange authorized QuickConnect secret for an access token.
     */
    @Throws(Exception::class)
    fun authenticateWithQuickConnect(secret: String): AuthResult {
        val bodyJson = JSONObject()
        bodyJson.put("Secret", secret)

        val body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString())
        val request = newRequestBuilder("$baseUrl/Users/AuthenticateWithQuickConnect", null)
            .post(body)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code()
            var errorMsg = ""
            try {
                val respBody = response.body()
                if (respBody != null) {
                    errorMsg = respBody.string().trim()
                }
            } catch (ignored: Exception) {}
            response.close()
            val detail = if (errorMsg.isEmpty()) "" else ": $errorMsg"
            throw Exception("QuickConnect authentication failed (HTTP $code$detail)")
        }

        val resStr = response.body()!!.string()
        val resObj = JSONObject(resStr)
        val token = resObj.getString("AccessToken")
        val serverId = resObj.optString("ServerId", "")

        val userObj = resObj.getJSONObject("User")
        val userId = userObj.getString("Id")
        val serverName = userObj.optString("Name", "Jellyfin")

        return AuthResult(token, userId, serverId, serverName)
    }

    /**
     * Test direct API token authentication and retrieve userId.
     */
    @Throws(Exception::class)
    fun verifyToken(token: String): AuthResult {
        val request = newRequestBuilder("$baseUrl/System/Info", token)
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code()
            var errorMsg = ""
            try {
                val respBody = response.body()
                if (respBody != null) {
                    errorMsg = respBody.string().trim()
                }
            } catch (ignored: Exception) {}
            response.close()
            val detail = if (errorMsg.isEmpty()) "" else ": $errorMsg"
            throw Exception("Token verification failed (HTTP $code$detail)")
        }

        val resStr = response.body()!!.string()
        val infoObj = JSONObject(resStr)
        val serverName = infoObj.optString("ServerName", "Jellyfin")
        val serverId = infoObj.optString("Id", "")

        // Fetch users to discover first user id
        val usersReq = newRequestBuilder("$baseUrl/Users", token)
            .get()
            .build()

        var userId = ""
        val usersRes = httpClient.newCall(usersReq).execute()
        if (usersRes.isSuccessful) {
            val usersStr = usersRes.body()!!.string()
            val usersArr = JSONArray(usersStr)
            if (usersArr.length() > 0) {
                userId = usersArr.getJSONObject(0).getString("Id")
            }
        } else {
            usersRes.close()
        }

        return AuthResult(token, userId, serverId, serverName)
    }

    /**
     * Retrieve libraries via GET /Users/{userId}/Views.
     */
    @Throws(Exception::class)
    fun fetchLibraries(
        token: String?,
        userId: String?,
        cancelToken: MediaSyncProvider.CancellationToken?
    ): List<MediaSyncProvider.LibraryInfo> {
        if (cancelToken != null && cancelToken.isCancelled()) return ArrayList()

        val url = baseUrl + (if (!userId.isNullOrEmpty()) "/Users/$userId/Views" else "/Library/VirtualFolders")
        val request = newRequestBuilder(url, token)
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code()
            response.close()
            throw Exception("Failed to fetch libraries (HTTP $code)")
        }

        val resStr = response.body()!!.string()
        val root = JSONObject(resStr)
        val items = root.optJSONArray("Items")
        val list = ArrayList<MediaSyncProvider.LibraryInfo>()
        if (items != null) {
            for (i in 0 until items.length()) {
                if (cancelToken != null && cancelToken.isCancelled()) break
                val item = items.getJSONObject(i)
                val id = item.optString("Id", item.optString("ItemId", ""))
                val name = item.optString("Name", "Library")
                val collType = item.optString("CollectionType", "mixed").lowercase()
                list.add(MediaSyncProvider.LibraryInfo(id, name, collType))
            }
        }
        return list
    }

    class ItemsQueryResult(
        val totalRecordCount: Int,
        val items: List<JellyfinMediaItem>
    )

    /**
     * Fetch paginated media items from a library.
     */
    @Throws(Exception::class)
    fun fetchItemsPage(
        token: String?,
        userId: String,
        libraryId: String,
        collectionType: String,
        startIndex: Int,
        limit: Int,
        itemIds: List<String>?,
        cancelToken: MediaSyncProvider.CancellationToken?
    ): ItemsQueryResult {
        if (cancelToken != null && cancelToken.isCancelled()) {
            return ItemsQueryResult(0, ArrayList())
        }

        val urlBuilder = StringBuilder(baseUrl)
        urlBuilder.append("/Users/").append(userId).append("/Items")
        urlBuilder.append("?parentId=").append(URLEncoder.encode(libraryId, "UTF-8"))
        urlBuilder.append("&recursive=true")
        urlBuilder.append("&startIndex=").append(startIndex)
        urlBuilder.append("&limit=").append(limit)
        urlBuilder.append("&fields=MediaSources,Genres,MediaStreams,Composers,DateCreated")

        if ("music".equals(collectionType, ignoreCase = true)) {
            urlBuilder.append("&includeItemTypes=Audio")
            urlBuilder.append("&sortBy=AlbumArtist,Album,SortName")
        } else if ("movies".equals(collectionType, ignoreCase = true)) {
            urlBuilder.append("&includeItemTypes=Movie")
            urlBuilder.append("&sortBy=SortName")
        } else if ("tvshows".equals(collectionType, ignoreCase = true)) {
            urlBuilder.append("&includeItemTypes=Episode")
            urlBuilder.append("&sortBy=SeriesSortName,ParentIndexNumber,IndexNumber")
        }

        if (!itemIds.isNullOrEmpty()) {
            val idsStr = StringBuilder()
            for (i in itemIds.indices) {
                if (i > 0) idsStr.append(",")
                idsStr.append(itemIds[i])
            }
            urlBuilder.append("&ids=").append(URLEncoder.encode(idsStr.toString(), "UTF-8"))
        }

        val request = newRequestBuilder(urlBuilder.toString(), token)
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code()
            response.close()
            throw Exception("Failed to query items (HTTP $code)")
        }

        val resStr = response.body()!!.string()
        val root = JSONObject(resStr)
        val total = root.optInt("TotalRecordCount", 0)
        val itemsArr = root.optJSONArray("Items")

        val resultList = ArrayList<JellyfinMediaItem>()
        if (itemsArr != null) {
            for (i in 0 until itemsArr.length()) {
                if (cancelToken != null && cancelToken.isCancelled()) break
                val o = itemsArr.getJSONObject(i)
                val item = parseItem(o, libraryId, collectionType)
                resultList.add(item)
            }
        }

        return ItemsQueryResult(total, resultList)
    }

    interface ItemsFetchProgressListener {
        fun onProgress(loaded: Int, total: Int)
    }

    /**
     * Paginated fetch of all media items for a library.
     */
    @Throws(Exception::class)
    fun fetchLibraryItemsAll(
        token: String?,
        userId: String,
        libraryId: String,
        collectionType: String,
        progressListener: ItemsFetchProgressListener?
    ): List<JellyfinMediaItem> {
        return fetchLibraryItemsAll(token, userId, libraryId, collectionType, progressListener, null)
    }

    @Throws(Exception::class)
    fun fetchLibraryItemsAll(
        token: String?,
        userId: String,
        libraryId: String,
        collectionType: String,
        progressListener: ItemsFetchProgressListener?,
        cancelToken: MediaSyncProvider.CancellationToken?
    ): List<JellyfinMediaItem> {
        val result = ArrayList<JellyfinMediaItem>()
        var startIndex = 0
        val pageSize = 500
        var total = Int.MAX_VALUE

        while (startIndex < total) {
            if (cancelToken != null && cancelToken.isCancelled()) break
            val page = fetchItemsPage(
                token, userId, libraryId, collectionType,
                startIndex, pageSize, null, cancelToken
            )
            total = page.totalRecordCount
            result.addAll(page.items)
            progressListener?.onProgress(result.size, total)
            startIndex += pageSize
            if (page.items.isEmpty()) break
        }

        return result
    }

    private fun parseItem(o: JSONObject, libraryId: String, collectionType: String): JellyfinMediaItem {
        val item = JellyfinMediaItem()
        item.id = o.optString("Id", "")
        item.name = o.optString("Name", "Unknown")
        item.album = o.optString("Album", null)
        item.libraryId = libraryId
        item.collectionType = collectionType
        item.serverDateModified = o.optString("DateCreated", "")

        val artistsArr = o.optJSONArray("Artists")
        if (artistsArr != null && artistsArr.length() > 0) {
            item.artist = artistsArr.optString(0, null)
        }
        if (item.artist.isNullOrBlank()) {
            item.artist = o.optString("AlbumArtist", "Unknown Artist")
        }

        item.albumArtist = o.optString("AlbumArtist", item.artist)

        val composersArr = o.optJSONArray("Composers")
        if (composersArr != null && composersArr.length() > 0) {
            val cObj = composersArr.optJSONObject(0)
            if (cObj != null) {
                item.composer = cObj.optString("Name", "Unknown Composer")
            }
        }
        if (item.composer.isNullOrBlank()) {
            item.composer = "Unknown Composer"
        }

        val year = o.optInt("ProductionYear", 0)
        item.year = if (year > 0) year.toString() else "Unknown Year"

        val genresArr = o.optJSONArray("Genres")
        if (genresArr != null && genresArr.length() > 0) {
            item.genre = genresArr.optString(0, "Unknown Genre")
        } else {
            item.genre = "Unknown Genre"
        }

        item.indexNumber = o.optInt("IndexNumber", 0)
        item.parentIndexNumber = o.optInt("ParentIndexNumber", 0)
        item.seriesName = o.optString("SeriesName", null)
        item.seriesId = o.optString("SeriesId", null)
        item.seasonId = o.optString("SeasonId", null)
        item.seasonName = o.optString("SeasonName", null)
        if (item.seasonName.isNullOrBlank()) {
            if (item.parentIndexNumber > 0) {
                item.seasonName = "Season " + item.parentIndexNumber
            } else if (item.parentIndexNumber == 0 && "tvshows".equals(collectionType, ignoreCase = true)) {
                item.seasonName = "Specials"
            }
        }
        val currentSeriesName = item.seriesName
        if (item.seriesId.isNullOrBlank()) {
            item.seriesId = if (!currentSeriesName.isNullOrBlank()) currentSeriesName else item.id
        }
        if (item.seasonId.isNullOrBlank()) {
            item.seasonId = item.seriesId + "_S" + item.parentIndexNumber
        }

        item.albumId = o.optString("AlbumId", null)
        item.albumPrimaryImageTag = o.optString("AlbumPrimaryImageTag", null)

        item.container = o.optString("Container", "")

        val sources = o.optJSONArray("MediaSources")
        if (sources != null && sources.length() > 0) {
            val src = sources.optJSONObject(0)
            if (src != null) {
                item.fileSize = src.optLong("Size", 0)
                if (item.container.isEmpty()) {
                    item.container = src.optString("Container", "")
                }
                if (item.container.isEmpty()) {
                    val path = src.optString("Path", "")
                    val dot = path.lastIndexOf(".")
                    if (dot in 1 until path.length - 1) {
                        item.container = path.substring(dot + 1)
                    }
                }
            }
        }

        if (item.container.isEmpty()) {
            item.container = if ("music".equals(collectionType, ignoreCase = true)) "mp3" else "mp4"
        }

        return item
    }

    /**
     * Download item (audio or video) to target file with atomic write safeguards and progress listener.
     */
    @Throws(Exception::class)
    fun downloadItem(
        token: String?,
        item: JellyfinMediaItem,
        transcodeVideo: Boolean,
        destination: File,
        listener: MediaSyncProvider.DownloadProgressListener?,
        cancelToken: MediaSyncProvider.CancellationToken?
    ) {
        if (cancelToken != null && cancelToken.isCancelled()) return

        val downloadUrl = if ("movies".equals(item.collectionType, ignoreCase = true) || "tvshows".equals(item.collectionType, ignoreCase = true)) {
            if (transcodeVideo) {
                baseUrl + "/Videos/" + item.id + "/stream" +
                        "?static=false&videoCodec=h264&maxWidth=480&videoBitRate=1000000&audioCodec=aac&audioBitRate=128000&container=mp4"
            } else {
                baseUrl + "/Items/" + item.id + "/Download"
            }
        } else {
            baseUrl + "/Items/" + item.id + "/Download"
        }

        val request = newRequestBuilder(downloadUrl, token)
            .addHeader("Accept-Encoding", "identity")
            .get()
            .build()

        var response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code()
            response.close()
            // If download permission denied on audio, try static audio stream fallback
            if ("music".equals(item.collectionType, ignoreCase = true) && (code == 401 || code == 403)) {
                val fallbackUrl = baseUrl + "/Audio/" + item.id + "/stream?static=true"
                val fallbackReq = newRequestBuilder(fallbackUrl, token)
                    .addHeader("Accept-Encoding", "identity")
                    .get()
                    .build()
                response = httpClient.newCall(fallbackReq).execute()
                if (!response.isSuccessful) {
                    val fbCode = response.code()
                    response.close()
                    throw Exception("Download failed (HTTP $fbCode)")
                }
            } else {
                throw Exception("Download failed (HTTP $code)")
            }
        }

        val body = response.body() ?: run {
            response.close()
            throw Exception("Empty response body")
        }

        var contentLength = body.contentLength()
        if (contentLength <= 0 && item.fileSize > 0) {
            contentLength = item.fileSize
        }

        val parent = destination.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        val tmpFile = File(destination.absolutePath + ".tmp")
        if (tmpFile.exists()) {
            tmpFile.delete()
        }

        var inStream: InputStream? = null
        var outStream: FileOutputStream? = null
        try {
            inStream = body.byteStream()
            outStream = FileOutputStream(tmpFile)
            val buffer = ByteArray(8192)
            var read: Int
            var totalRead: Long = 0

            while (inStream.read(buffer).also { read = it } != -1) {
                if (cancelToken != null && cancelToken.isCancelled()) {
                    throw Exception("Download cancelled")
                }
                outStream.write(buffer, 0, read)
                totalRead += read.toLong()
                listener?.onProgress(totalRead, contentLength)
            }
            outStream.flush()
            outStream.close()
            outStream = null

            if (destination.exists()) {
                destination.delete()
            }
            if (!tmpFile.renameTo(destination)) {
                throw Exception("Failed to rename .tmp to " + destination.name)
            }
        } finally {
            if (outStream != null) {
                try { outStream.close() } catch (ignored: Exception) {}
            }
            if (inStream != null) {
                try { inStream.close() } catch (ignored: Exception) {}
            }
            response.close()
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    /**
     * Download album cover art to folder.jpg.
     */
    @Throws(Exception::class)
    fun downloadCover(
        token: String?,
        albumId: String?,
        itemId: String?,
        destination: File,
        cancelToken: MediaSyncProvider.CancellationToken?
    ) {
        if (cancelToken != null && cancelToken.isCancelled()) return
        if (destination.exists()) return

        val targetId = if (!albumId.isNullOrEmpty()) albumId else itemId
        if (targetId.isNullOrEmpty()) return

        val coverUrl = "$baseUrl/Items/$targetId/Images/Primary?maxWidth=320&maxHeight=320&quality=85"
        val request = newRequestBuilder(coverUrl, token)
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            return
        }

        val parent = destination.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        val tmpFile = File(destination.absolutePath + ".tmp")
        var inStream: InputStream? = null
        var outStream: FileOutputStream? = null
        try {
            inStream = response.body()!!.byteStream()
            outStream = FileOutputStream(tmpFile)
            val buffer = ByteArray(8192)
            var read: Int
            while (inStream.read(buffer).also { read = it } != -1) {
                if (cancelToken != null && cancelToken.isCancelled()) break
                outStream.write(buffer, 0, read)
            }
            outStream.flush()
            outStream.close()
            outStream = null

            if (cancelToken == null || !cancelToken.isCancelled()) {
                tmpFile.renameTo(destination)
            }
        } finally {
            if (outStream != null) {
                try { outStream.close() } catch (ignored: Exception) {}
            }
            if (inStream != null) {
                try { inStream.close() } catch (ignored: Exception) {}
            }
            response.close()
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    companion object {
        private const val TAG = "JellyfinClient"
        private val JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8")
        fun normalizeUrl(raw: String?): String {
            if (raw == null) return ""
            var url = raw.trim()
            if (url.isEmpty()) return ""
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            while (url.endsWith("/")) {
                url = url.substring(0, url.length - 1)
            }
            return url
        }
    }
}
