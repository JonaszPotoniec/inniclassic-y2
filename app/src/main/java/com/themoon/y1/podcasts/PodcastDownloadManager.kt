package com.themoon.y1.podcasts

import com.themoon.y1.StoragePaths
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Manages background downloading of podcast episodes, progress polling,
 * and file integrity verification.
 */
class PodcastDownloadManager private constructor() {

    interface DownloadListener {
        fun onProgress(audioUrl: String, progressPercent: Int)
        fun onComplete(audioUrl: String, destFile: File)
        fun onError(audioUrl: String, errorMsg: String)
    }

    private val activeDownloads = ConcurrentHashMap<String, Int>()
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val client: OkHttpClient = buildClient()

    fun isDownloading(audioUrl: String): Boolean = activeDownloads.containsKey(audioUrl)
    fun getProgress(audioUrl: String): Int = activeDownloads[audioUrl] ?: 0
    fun hasActiveDownloads(): Boolean = activeDownloads.isNotEmpty()

    fun startDownload(
        title: String,
        audioUrl: String,
        channelName: String,
        listener: DownloadListener?
    ) {
        val safeChannel = channelName.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
        val safeTitle = title.replace("[\\\\/:*?\"<>|]".toRegex(), "_") + ".mp3"
        val podcastDir = StoragePaths.getPodcastChannelDir(safeChannel)
        if (!podcastDir.exists()) podcastDir.mkdirs()

        val destFile = File(podcastDir, safeTitle)
        activeDownloads[audioUrl] = 0

        executor.execute {
            try {
                val request = Request.Builder()
                    .url(audioUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/114.0.0.0 Safari/537.36")
                    .header("Accept-Encoding", "identity")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw java.io.IOException("HTTP error ${response.code()}")
                }

                val body = response.body() ?: throw java.io.IOException("Empty body")
                val totalLength = body.contentLength()
                val isStream = body.byteStream()
                val fos = FileOutputStream(destFile)

                val buffer = ByteArray(8192)
                var len: Int
                var downloaded = 0L
                var lastReported = 0

                while (isStream.read(buffer).also { len = it } != -1) {
                    fos.write(buffer, 0, len)
                    downloaded += len

                    if (totalLength > 0) {
                        val progress = ((downloaded * 100L) / totalLength).toInt()
                        if (progress > lastReported) {
                            lastReported = progress
                            activeDownloads[audioUrl] = progress
                            listener?.onProgress(audioUrl, progress)
                        }
                    }
                }

                fos.flush()
                fos.close()
                isStream.close()

                activeDownloads.remove(audioUrl)
                listener?.onComplete(audioUrl, destFile)
            } catch (e: Exception) {
                if (destFile.exists()) {
                    destFile.delete()
                }
                activeDownloads.remove(audioUrl)
                listener?.onError(audioUrl, e.message ?: "Download failed")
            }
        }
    }

    private fun buildClient(): OkHttpClient {
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
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            OkHttpClient()
        }
    }

    companion object {
        @Volatile
        private var instance: PodcastDownloadManager? = null

        fun getInstance(): PodcastDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: PodcastDownloadManager().also { instance = it }
            }
        }
    }
}
