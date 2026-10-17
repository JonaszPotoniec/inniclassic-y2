package com.themoon.y1.managers

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.views.TablerIcons
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

    fun checkForUpdates() {
        val d = activity.resources.displayMetrics.density
        val loaderDialog = Dialog(activity)
        loaderDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        loaderDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val loaderLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(ThemeManager.getOverlayBackgroundColor() or 0xEE000000.toInt())
                cornerRadius = 15f * d
                setStroke((1f * d).toInt(), 0x33FFFFFF)
            }
            setPadding((24f * d).toInt(), (24f * d).toInt(), (24f * d).toInt(), (24f * d).toInt())
        }

        val progressBar = ProgressBar(activity)
        val pbLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = (14f * d).toInt()
        }
        loaderLayout.addView(progressBar, pbLp)

        val tvLoaderMsg = TextView(activity).apply {
            text = localize("Connecting to Wi-Fi...")
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 15f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
        }
        loaderLayout.addView(tvLoaderMsg)

        loaderDialog.setContentView(loaderLayout)
        loaderDialog.setCancelable(true)
        loaderDialog.setCanceledOnTouchOutside(false)
        loaderDialog.window?.setLayout((270f * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        loaderDialog.show()

        var isCancelled = false
        loaderDialog.setOnCancelListener { isCancelled = true }
        loaderDialog.setOnDismissListener { isCancelled = true }

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
                        if (isCancelled) return@Thread
                        try {
                            Thread.sleep(500)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        waitCount++
                        connected = isNetworkConnected()
                    }
                }

                if (isCancelled) return@Thread

                if (!connected) {
                    activity.runOnUiThread {
                        if (loaderDialog.isShowing) loaderDialog.dismiss()
                        Toast.makeText(
                            activity,
                            localize("Wi-Fi connection required. Please connect."),
                            Toast.LENGTH_LONG
                        ).show()
                        onRequireWifi()
                    }
                    return@Thread
                }

                activity.runOnUiThread {
                    if (loaderDialog.isShowing) {
                        tvLoaderMsg.text = localize("Checking for updates...")
                    }
                }

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
                    if (loaderDialog.isShowing) loaderDialog.dismiss()
                    if (hasUpdate) {
                        showNewVersionDialog(serverVersionName, myVersionName, finalApkUrl)
                    } else {
                        showUpToDateDialog(serverVersionName, myVersionName, finalApkUrl)
                    }
                }
            } catch (e: Throwable) {
                activity.runOnUiThread {
                    if (loaderDialog.isShowing) loaderDialog.dismiss()
                    showUpdateErrorDialog(e.message ?: "Network error")
                }
            }
        }.start()
    }

    private fun showNewVersionDialog(serverVersion: String, currentVersion: String, apkUrl: String) {
        val d = activity.resources.displayMetrics.density
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ThemeManager.getOverlayBackgroundColor() or 0xEE000000.toInt())
                cornerRadius = 15f * d
                setStroke((1f * d).toInt(), 0x33FFFFFF)
            }
            setPadding((18f * d).toInt(), (18f * d).toInt(), (18f * d).toInt(), (14f * d).toInt())
        }

        val ivIcon = ImageView(activity).apply {
            val size = (36f * d).toInt()
            setImageBitmap(TablerIcons.render(TablerIcons.DOWNLOAD, size, ThemeManager.getTextColorPrimary()))
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (8f * d).toInt()
            }
        }
        rootLayout.addView(ivIcon)

        val tvTitle = TextView(activity).apply {
            text = localize("New Version Available")
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 17f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (6f * d).toInt())
        }
        rootLayout.addView(tvTitle)

        val tvMsg = TextView(activity).apply {
            text = "${localize("New version")}: $serverVersion\n(${localize("Current")}: $currentVersion)"
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (14f * d).toInt())
        }
        rootLayout.addView(tvMsg)

        val btnContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val btnUpdate = createDialogButton(localize("Update Now"), isFocused = true)
        btnUpdate.setOnClickListener {
            clickFeedback()
            dialog.dismiss()
            downloadAndInstallApk(apkUrl)
        }
        btnContainer.addView(btnUpdate)

        val btnCancel = createDialogButton(localize("Later"), isFocused = false)
        btnCancel.setOnClickListener {
            clickFeedback()
            dialog.dismiss()
        }
        btnContainer.addView(btnCancel)

        rootLayout.addView(btnContainer)
        dialog.setContentView(rootLayout)
        dialog.window?.setLayout((290f * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()

        btnUpdate.post { btnUpdate.requestFocus() }
    }

    private fun showUpToDateDialog(serverVersion: String, currentVersion: String, apkUrl: String) {
        val d = activity.resources.displayMetrics.density
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ThemeManager.getOverlayBackgroundColor() or 0xEE000000.toInt())
                cornerRadius = 15f * d
                setStroke((1f * d).toInt(), 0x33FFFFFF)
            }
            setPadding((18f * d).toInt(), (18f * d).toInt(), (18f * d).toInt(), (14f * d).toInt())
        }

        val ivIcon = ImageView(activity).apply {
            val size = (36f * d).toInt()
            setImageBitmap(TablerIcons.render(TablerIcons.CHECK, size, ThemeManager.getTextColorPrimary()))
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (8f * d).toInt()
            }
        }
        rootLayout.addView(ivIcon)

        val tvTitle = TextView(activity).apply {
            text = localize("Up to Date")
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 17f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (6f * d).toInt())
        }
        rootLayout.addView(tvTitle)

        val curVerStr = if (currentVersion.startsWith("v")) currentVersion else "v$currentVersion"
        val tvMsg = TextView(activity).apply {
            text = "$curVerStr\n${localize("You are using the latest version.")}"
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (14f * d).toInt())
        }
        rootLayout.addView(tvMsg)

        val btnContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val btnOk = createDialogButton(localize("OK"), isFocused = true)
        btnOk.setOnClickListener {
            clickFeedback()
            dialog.dismiss()
        }
        btnContainer.addView(btnOk)

        val btnReinstall = createDialogButton(localize("Reinstall"), isFocused = false)
        btnReinstall.setOnClickListener {
            clickFeedback()
            dialog.dismiss()
            downloadAndInstallApk(apkUrl)
        }
        btnContainer.addView(btnReinstall)

        rootLayout.addView(btnContainer)
        dialog.setContentView(rootLayout)
        dialog.window?.setLayout((290f * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()

        btnOk.post { btnOk.requestFocus() }
    }

    private fun showUpdateErrorDialog(errorMsg: String) {
        val d = activity.resources.displayMetrics.density
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ThemeManager.getOverlayBackgroundColor() or 0xEE000000.toInt())
                cornerRadius = 15f * d
                setStroke((1f * d).toInt(), 0x33FFFFFF)
            }
            setPadding((18f * d).toInt(), (18f * d).toInt(), (18f * d).toInt(), (14f * d).toInt())
        }

        val tvTitle = TextView(activity).apply {
            text = localize("Update Check Failed")
            setTextColor(0xFFFF4444.toInt())
            textSize = 17f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (6f * d).toInt())
        }
        rootLayout.addView(tvTitle)

        val tvMsg = TextView(activity).apply {
            text = localize("Unable to check for updates. Check internet connection.")
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (14f * d).toInt())
        }
        rootLayout.addView(tvMsg)

        val btnOk = createDialogButton(localize("OK"), isFocused = true)
        btnOk.setOnClickListener {
            clickFeedback()
            dialog.dismiss()
        }
        rootLayout.addView(btnOk)

        dialog.setContentView(rootLayout)
        dialog.window?.setLayout((290f * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()

        btnOk.post { btnOk.requestFocus() }
    }

    private fun createDialogButton(label: String, isFocused: Boolean): View {
        val d = activity.resources.displayMetrics.density
        val btn = TextView(activity).apply {
            text = label
            textSize = 15f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            val padH = (12f * d).toInt()
            val padV = (10f * d).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (4f * d).toInt(), 0, (4f * d).toInt())
            }
        }

        fun updateBackground(hasF: Boolean) {
            val bg = GradientDrawable().apply {
                cornerRadius = 8f * d
                if (hasF) {
                    setColor(ThemeManager.getListButtonFocusedBg())
                    btn.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                } else {
                    setColor(ThemeManager.getListButtonNormalBg())
                    btn.setTextColor(ThemeManager.getTextColorPrimary())
                }
            }
            btn.background = bg
        }

        updateBackground(isFocused)
        btn.setOnFocusChangeListener { _, hasFocus ->
            updateBackground(hasFocus)
        }
        return btn
    }

    fun downloadAndInstallApk(apkUrl: String) {
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        val tvProgress = TextView(activity).apply {
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 0)
            textSize = 16f
            text = localize("Connecting to server...")
        }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            addView(progressBar)
            addView(tvProgress)
        }

        val progressDialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(localize("Downloading Update"))
            .setView(layout)
            .setCancelable(false)
            .create()

        progressDialog.show()

        Thread {
            try {
                val client = getUpdateHttpClient()
                val request = Request.Builder()
                    .url(apkUrl)
                    .header("User-Agent", "InniClassic-OTA")
                    .header("Accept-Encoding", "identity")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code()}")
                }

                val responseBody = response.body() ?: throw Exception("Empty response body")
                val fileLength = responseBody.contentLength()

                val dir = activity.getDir("update", Context.MODE_PRIVATE)
                val updateFile = File(dir, "InniClassic_Update.apk")

                val fos = FileOutputStream(updateFile)
                val isStream = responseBody.byteStream()
                val buffer = ByteArray(8192)
                var len: Int
                var total: Long = 0

                while (isStream.read(buffer).also { len = it } != -1) {
                    total += len.toLong()
                    fos.write(buffer, 0, len)

                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        val downloadedMB = total / (1024 * 1024)
                        val totalMB = fileLength / (1024 * 1024)

                        activity.runOnUiThread {
                            progressBar.progress = progress
                            tvProgress.text = "$progress%   (${downloadedMB}MB / ${totalMB}MB)"
                        }
                    }
                }
                fos.close()
                isStream.close()

                if (fileLength > 0 && total != fileLength) {
                    if (updateFile.exists()) updateFile.delete()
                    throw Exception("Incomplete download")
                }

                activity.runOnUiThread {
                    progressBar.progress = 100
                    tvProgress.text = localize("Download Finished! Installing...")

                    Handler(Looper.getMainLooper()).postDelayed({
                        progressDialog.dismiss()
                        Thread {
                            installApk(updateFile)
                        }.start()
                    }, 1000)
                }
            } catch (_: Exception) {
                activity.runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(
                        activity,
                        localize("Download failed. Check your internet connection."),
                        Toast.LENGTH_LONG
                    ).show()
                }
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
                Toast.makeText(activity, localize("Install Failed."), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
