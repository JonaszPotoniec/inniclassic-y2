package com.themoon.y1.managers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.themoon.y1.StoragePaths
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.views.Notice
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Manages GitHub OTA release checking, version comparison, download progress,
 * and silent root installation for the Innioasis Y2.
 */
class OtaUpdateManager(
    private val activity: Activity,
    private val clickFeedback: () -> Unit,
    private val onRequireWifi: () -> Unit,
    private val localize: (String) -> String
) {

    companion object {
        /** True from the start of a download to its install or its end: one at a time, as all write the same file. */
        private val downloadRunning = java.util.concurrent.atomic.AtomicBoolean(false)

        const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/JonaszPotoniec/inniclassic-y2/main/app/version.json"

        fun extractBuildNumber(suffix: String?): Int {
            if (suffix.isNullOrEmpty()) return 0
            val m = Pattern.compile("(\\d+)").matcher(suffix)
            if (m.find()) {
                return try {
                    m.group(1).toInt()
                } catch (_: Exception) {
                    0
                }
            }
            return 0
        }

        fun compareVersionStrings(s1: String?, s2: String?): Int {
            var v1 = s1?.trim() ?: ""
            var v2 = s2?.trim() ?: ""
            if (v1.startsWith("v", ignoreCase = true)) v1 = v1.substring(1)
            if (v2.startsWith("v", ignoreCase = true)) v2 = v2.substring(1)
            if (v1 == v2) return 0

            val datePattern = Pattern.compile("^(\\d{8})(.*)$")
            val m1 = datePattern.matcher(v1)
            val m2 = datePattern.matcher(v2)

            if (m1.matches() && m2.matches()) {
                val d1 = m1.group(1).toLong()
                val d2 = m2.group(1).toLong()
                if (d1 != d2) return if (d1 > d2) 1 else -1

                val b1 = extractBuildNumber(m1.group(2))
                val b2 = extractBuildNumber(m2.group(2))
                if (b1 != b2) return if (b1 > b2) 1 else -1
                return 0
            }

            if (m1.matches() && !m2.matches()) return 1
            if (!m1.matches() && m2.matches()) return -1

            val parts1 = v1.split("[.\\-_]".toRegex())
            val parts2 = v2.split("[.\\-_]".toRegex())
            val len = minOf(parts1.size, parts2.size)

            for (i in 0 until len) {
                val p1 = parts1[i]
                val p2 = parts2[i]
                val isNum1 = p1.matches("\\d+".toRegex())
                val isNum2 = p2.matches("\\d+".toRegex())

                if (isNum1 && isNum2) {
                    val n1 = p1.toLong()
                    val n2 = p2.toLong()
                    if (n1 != n2) return if (n1 > n2) 1 else -1
                } else {
                    val cmp = p1.compareTo(p2, ignoreCase = true)
                    if (cmp != 0) return if (cmp > 0) 1 else -1
                }
            }
            return parts1.size.compareTo(parts2.size)
        }

        fun isServerVersionNewer(serverCode: Int, serverName: String, myCode: Int, myName: String): Boolean {
            val nameCompare = compareVersionStrings(serverName, myName)
            if (nameCompare > 0) return true
            if (nameCompare < 0) return false

            var normServer = serverCode.toLong()
            var normMy = myCode.toLong()
            if (normServer > 1000000000L && normMy < 100000000L) {
                normServer /= 100
            } else if (normMy > 1000000000L && normServer < 100000000L) {
                normMy /= 100
            }
            return normServer > normMy
        }

        private fun getUpdateHttpClient(): OkHttpClient {
            return try {
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(certs: Array<X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(certs: Array<X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val sslContext = SSLContext.getInstance("TLS", "Conscrypt")
                sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())

                OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.socketFactory, trustAll)
                    .hostnameVerifier { _, _ -> true }
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
            } catch (_: Exception) {
                OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
            }
        }
    }

    private fun isNetworkConnected(): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val net = cm.activeNetworkInfo ?: return false
        return net.isConnected
    }

    /** System Update → Check for Updates: a progress screen, then the result screen. Back cancels. */
    fun checkForUpdates() {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val key = ActionScreens.open(ActionScreens.Spec(
            localize("System Update"),
            listOf(localize("Connecting to Wi-Fi...")),
            onCancel = { cancelled.set(true) }
        ))

        Thread {
            try {
                val wm = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                var connected = isNetworkConnected()

                if (!connected) {
                    if (wm != null && !wm.isWifiEnabled) {
                        wm.isWifiEnabled = true
                    }

                    var waitCount = 0
                    while (!connected && waitCount < 20) {
                        if (cancelled.get()) return@Thread
                        try {
                            Thread.sleep(500)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        waitCount++
                        connected = isNetworkConnected()
                    }
                }

                if (cancelled.get()) return@Thread

                if (!connected) {
                    activity.runOnUiThread {
                        ActionScreens.close(key)
                        Notice.error(
                            activity,
                            localize("Wi-Fi connection required. Please connect.")
                        )
                        onRequireWifi()
                    }
                    return@Thread
                }

                ActionScreens.update(key, listOf(localize("Checking for updates...")))

                val client = getUpdateHttpClient()
                val request = Request.Builder()
                    .url(UPDATE_JSON_URL)
                    .header("User-Agent", "InniClassic-OTA")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code()}")
                }

                val body = response.body()?.string() ?: ""
                val root = JSONObject(body)

                val serverVersionCode = root.optInt("versionCode", 0)
                val serverVersionName = root.optString("versionName", "")
                var apkUrl = root.optString("apkUrl", "")
                if (apkUrl.isEmpty()) {
                    val apkName = root.optString("apkName", root.optString("outputFile", "InniClassic.apk"))
                    apkUrl = "https://github.com/JonaszPotoniec/inniclassic-y2/releases/latest/download/$apkName"
                }

                var myVersionName = "1.0"
                var myVersionCode = 1
                try {
                    val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                    myVersionName = pInfo.versionName ?: "1.0"
                    myVersionCode = pInfo.versionCode
                } catch (_: Exception) {}

                val finalApkUrl = apkUrl
                val hasUpdate = isServerVersionNewer(serverVersionCode, serverVersionName, myVersionCode, myVersionName)

                activity.runOnUiThread {
                    if (cancelled.get()) return@runOnUiThread
                    ActionScreens.close(key)
                    if (hasUpdate) {
                        showNewVersionDialog(serverVersionName, myVersionName, finalApkUrl)
                    } else {
                        showUpToDateDialog(serverVersionName, myVersionName, finalApkUrl)
                    }
                }
            } catch (e: Throwable) {
                activity.runOnUiThread {
                    if (cancelled.get()) return@runOnUiThread
                    ActionScreens.close(key)
                    showUpdateErrorDialog(e.message ?: "Network error")
                }
            }
        }.start()
    }

    private fun showNewVersionDialog(serverVersion: String, currentVersion: String, apkUrl: String) {
        ActionScreens.open(ActionScreens.Spec(
            localize("New Version Available"),
            listOf("${localize("New version")}: $serverVersion", "(${localize("Current")}: $currentVersion)"),
            listOf(ActionScreens.Action(localize("Update Now")) { downloadAndInstallApk(apkUrl) })
        ))
    }

    private fun showUpToDateDialog(serverVersion: String, currentVersion: String, apkUrl: String) {
        val curVerStr = if (currentVersion.startsWith("v")) currentVersion else "v$currentVersion"
        // No row selected at the start (before, OK had the focus): one centre push does not download again
        ActionScreens.open(ActionScreens.Spec(
            localize("Up to Date"),
            listOf(curVerStr, localize("You are using the latest version.")),
            listOf(ActionScreens.Action(localize("Reinstall")) { downloadAndInstallApk(apkUrl) }),
            selectAction = false
        ))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun showUpdateErrorDialog(errorMsg: String) {
        ActionScreens.open(ActionScreens.Spec(
            localize("Update Check Failed"),
            listOf(localize("Unable to check for updates. Check internet connection."))
        ))
    }

    /** Downloads the update on a progress screen, then installs it. Back stops the download. */
    fun downloadAndInstallApk(apkUrl: String) {
        // A download that Back stopped can still wait for its server (read timeout 60 s)
        if (!downloadRunning.compareAndSet(false, true)) {
            Notice.show(activity, localize("Please wait a moment..."))
            return
        }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val call = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>(null)
        val title = localize("Downloading Update")
        val key = ActionScreens.open(ActionScreens.Spec(
            title,
            listOf(localize("Connecting to server...")),
            onCancel = {
                cancelled.set(true)
                // Also a read that waits for the server stops now
                call.get()?.cancel()
            }
        ))

        Thread {
            var updateFile: File? = null
            var installs = false
            try {
                val client = getUpdateHttpClient()
                val request = Request.Builder()
                    .url(apkUrl)
                    .header("User-Agent", "InniClassic-OTA")
                    .header("Accept-Encoding", "identity")
                    .build()

                val newCall = client.newCall(request)
                call.set(newCall)
                if (cancelled.get()) newCall.cancel()
                val response = newCall.execute()
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code()}")
                }

                val responseBody = response.body() ?: throw Exception("Empty response body")
                val fileLength = responseBody.contentLength()

                val dir = activity.getDir("update", Context.MODE_PRIVATE)
                val file = File(dir, "InniClassic_Update.apk")
                updateFile = file

                val fos = FileOutputStream(file)
                val isStream = responseBody.byteStream()
                val buffer = ByteArray(8192)
                var len: Int
                var total: Long = 0
                var shownProgress = -1

                try {
                    while (isStream.read(buffer).also { len = it } != -1) {
                        if (cancelled.get()) throw InterruptedException("cancelled")
                        total += len.toLong()
                        fos.write(buffer, 0, len)

                        if (fileLength > 0) {
                            val progress = (total * 100 / fileLength).toInt()
                            if (progress != shownProgress) {
                                shownProgress = progress
                                val downloadedMB = total / (1024 * 1024)
                                val totalMB = fileLength / (1024 * 1024)
                                ActionScreens.update(key, listOf("$progress%   (${downloadedMB}MB / ${totalMB}MB)"))
                            }
                        }
                    }
                } finally {
                    fos.close()
                    isStream.close()
                }

                if (fileLength > 0 && total != fileLength) {
                    throw Exception("Incomplete download")
                }

                ActionScreens.update(key, listOf(localize("Download Finished! Installing...")))
                installs = true
                Handler(Looper.getMainLooper()).postDelayed({
                    ActionScreens.close(key)
                    // Back in this last second also stops the update: no install and no restart
                    if (cancelled.get()) {
                        file.delete()
                        downloadRunning.set(false)
                        return@postDelayed
                    }
                    Thread {
                        installApk(file)
                        downloadRunning.set(false)
                    }.start()
                }, 1000)
            } catch (_: Exception) {
                updateFile?.let { if (it.exists()) it.delete() }
                activity.runOnUiThread {
                    ActionScreens.close(key)
                    if (!cancelled.get()) {
                        Notice.error(
                            activity,
                            localize("Download failed. Check your internet connection.")
                        )
                    }
                }
            } finally {
                if (!installs) downloadRunning.set(false)
            }
        }.start()
    }

    fun installApk(apkFile: File) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = java.io.DataOutputStream(process.outputStream)

            val apkPath = apkFile.absolutePath
            val logPath = StoragePaths.primaryFile("y1_update_log.txt").absolutePath

            os.writeBytes("chmod 777 " + apkFile.parentFile.absolutePath + "\n")
            os.writeBytes("chmod 777 $apkPath\n")
            os.writeBytes("mount -o remount,rw /system 2>/dev/null\n")
            os.writeBytes("if [ -f /system/app/com.themoon.y1.apk ]; then\n")
            os.writeBytes("  cp $apkPath /system/app/com.themoon.y1.apk >> $logPath 2>&1\n")
            os.writeBytes("  chmod 644 /system/app/com.themoon.y1.apk\n")
            os.writeBytes("  rm -f /data/dalvik-cache/*com.themoon.y1* 2>/dev/null\n")
            os.writeBytes("  rm -f /data/app/com.themoon.y1* 2>/dev/null\n")
            os.writeBytes("fi\n")
            os.writeBytes("pm install -r -d $apkPath >> $logPath 2>&1\n")
            os.writeBytes("sync\n")
            os.writeBytes("reboot\n")
            os.writeBytes("sleep 3\n")
            os.writeBytes("am start -n " + activity.packageName + "/.MainActivity\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()

            process.waitFor()
            return
        } catch (_: Exception) {}

        // Fallback for non-root systems
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val apkUri = Uri.parse("file://" + apkFile.absolutePath)
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            activity.startActivity(intent)
        } catch (_: Exception) {
            activity.runOnUiThread {
                Notice.error(activity, localize("Install Failed."))
            }
        }
    }
}
