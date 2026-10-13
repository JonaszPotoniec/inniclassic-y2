package com.themoon.y1.podcasts

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Searches Apple iTunes Podcast directory and returns discovered podcast channels.
 */
class PodcastSearchClient private constructor() {

    data class PodcastSearchResult(
        val name: String,
        val author: String,
        val feedUrl: String,
        val artworkUrl: String
    )

    private val client: OkHttpClient = buildClient()

    fun search(keyword: String, limit: Int = 200): List<PodcastSearchResult> {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encoded&entity=podcast&limit=$limit"

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw java.io.IOException("iTunes Search API returned ${response.code()}")
        }

        val jsonStr = response.body()?.string() ?: return emptyList()
        val root = JSONObject(jsonStr)
        val resultsArray = root.optJSONArray("results") ?: return emptyList()

        val results = ArrayList<PodcastSearchResult>()
        for (i in 0 until resultsArray.length()) {
            val item = resultsArray.optJSONObject(i) ?: continue
            if (item.has("feedUrl") && item.has("collectionName")) {
                val name = item.getString("collectionName")
                val author = item.optString("artistName", "Unknown")
                val feedUrl = item.getString("feedUrl")
                val artwork = item.optString("artworkUrl100", "")
                results.add(PodcastSearchResult(name, author, feedUrl, artwork))
            }
        }
        return results
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
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }

    companion object {
        @Volatile
        private var instance: PodcastSearchClient? = null

        @JvmStatic
        fun getInstance(): PodcastSearchClient {
            return instance ?: synchronized(this) {
                instance ?: PodcastSearchClient().also { instance = it }
            }
        }
    }
}
