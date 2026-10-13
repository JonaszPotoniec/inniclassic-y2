package com.themoon.y1.controllers.sync

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.sync.MediaSyncProvider
import com.themoon.y1.sync.SyncManager
import com.themoon.y1.sync.jellyfin.JellyfinSyncHierarchy
import com.themoon.y1.sync.jellyfin.JellyfinSyncProvider
import com.themoon.y1.views.TablerIcons
import java.io.File
import java.util.Locale

/**
 * Controller for Jellyfin sync configuration, library browsing,
 * item selection, QuickConnect pairing, and sync progress modal.
 */
class JellyfinUiController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val createSettingRow: (title: String, value: String) -> LinearLayout,
    private val createCategoryHeader: (title: String) -> Unit,
    private val createTablerButton: (icon: Array<String>, text: String) -> View,
    private val createDialogButton: (text: String) -> View,
    private val clickFeedback: () -> Unit,
    private val onOpenKeyboard: (mode: Int, currentVal: String) -> Unit,
    private val onRequestWifi: () -> Unit,
    private val isNetworkConnected: () -> Unit,
    private val onSyncFinished: () -> Unit,
    private val onScanVideos: () -> Unit,
    private val isFilePlaying: (File?) -> Boolean,
    private val customLibrary: MutableList<SongItem>,
    private val trackNumberMap: MutableMap<String, Int>,
    private val localize: (String) -> String
) : ScreenController {

    private val containerSettingsItems: LinearLayout = rootView.findViewById(R.id.container_settings_items)
    private val provider: JellyfinSyncProvider get() = JellyfinSyncProvider.getInstance(activity)
    private val syncManager: SyncManager get() = SyncManager.getInstance(activity)

    var currentDepth: Int = 1
        private set
    var activeLibrary: MediaSyncProvider.LibraryInfo? = null
        private set
    var currentHierarchy: JellyfinSyncHierarchy? = null
        private set

    private var lastFocusIndex: Int = -1
    private var lastLibConfigFocusIndex: Int = -1

    override fun onEnter(state: ScreenState) {
        buildSettingsUI()
    }

    override fun onExit() {
        currentDepth = 1
    }

    fun buildSettingsUI(preferredFocusIndex: Int = lastFocusIndex) {
        currentDepth = 1
        containerSettingsItems.removeAllViews()

        createCategoryHeader("━ " + localize("Server") + " ━")

        val rawUrl = provider.serverUrl
        val rowUrl = createSettingRow(localize("Server URL"), if (rawUrl.isEmpty()) localize("Not Configured") else rawUrl)
        rowUrl.setOnClickListener {
            clickFeedback()
            onOpenKeyboard(5, provider.serverUrl)
        }
        containerSettingsItems.addView(rowUrl)

        val authMode = provider.authMode
        val authModeLabel = when {
            "credentials".equals(authMode, ignoreCase = true) -> localize("Credentials")
            "token".equals(authMode, ignoreCase = true) -> localize("Token")
            else -> localize("QuickConnect")
        }

        val rowAuthMode = createSettingRow(localize("Auth Mode"), authModeLabel)
        rowAuthMode.setOnClickListener {
            clickFeedback()
            val cur = provider.authMode
            val next = when {
                "quickconnect".equals(cur, ignoreCase = true) -> "credentials"
                "credentials".equals(cur, ignoreCase = true) -> "token"
                else -> "quickconnect"
            }
            provider.authMode = next
            buildSettingsUI(containerSettingsItems.indexOfChild(rowAuthMode))
        }
        containerSettingsItems.addView(rowAuthMode)

        if ("quickconnect".equals(authMode, ignoreCase = true)) {
            val paired = provider.accessToken.isNotEmpty()
            val userSummary = if (provider.username.isEmpty()) localize("Not Configured") else "@" + provider.username
            if (paired) {
                val rowUser = createSettingRow(localize("Username"), userSummary)
                containerSettingsItems.addView(rowUser)
            }

            val btnQuickConnect = createTablerButton(TablerIcons.LOGIN, localize("Connect via QuickConnect"))
            btnQuickConnect.setOnClickListener {
                clickFeedback()
                startQuickConnect()
            }
            containerSettingsItems.addView(btnQuickConnect)
        } else if ("token".equals(authMode, ignoreCase = true)) {
            val token = provider.accessToken
            val rowToken = createSettingRow(localize("API Token"), if (token.isEmpty()) localize("Not Configured") else "••••••••")
            rowToken.setOnClickListener {
                clickFeedback()
                onOpenKeyboard(8, provider.accessToken)
            }
            containerSettingsItems.addView(rowToken)
        } else {
            val user = provider.username
            val rowUser = createSettingRow(localize("Username"), if (user.isEmpty()) localize("Not Configured") else user)
            rowUser.setOnClickListener {
                clickFeedback()
                onOpenKeyboard(6, provider.username)
            }
            containerSettingsItems.addView(rowUser)

            val pass = provider.password
            val rowPass = createSettingRow(localize("Password"), if (pass.isEmpty()) localize("Not Configured") else "••••••••")
            rowPass.setOnClickListener {
                clickFeedback()
                onOpenKeyboard(7, provider.password)
            }
            containerSettingsItems.addView(rowPass)
        }

        val btnTest = createTablerButton(TablerIcons.WIFI, localize("Test Connection"))
        btnTest.setOnClickListener {
            clickFeedback()
            testConnection()
        }
        containerSettingsItems.addView(btnTest)

        createCategoryHeader("━ " + localize("Libraries") + " ━")

        val ledger = syncManager.ledger
        val cachedLibs = provider.getCachedLibraries()

        if (cachedLibs.isEmpty()) {
            val tvHint = TextView(activity).apply {
                text = localize("Connect to discover libraries.")
                setTextColor(ThemeManager.getTextColorSecondary())
                textSize = 14f
                setPadding(20, 15, 20, 15)
            }
            containerSettingsItems.addView(tvHint)
        } else {
            for (lib in cachedLibs) {
                val defaultMode = if ("music".equals(lib.collectionType, ignoreCase = true)) "all" else "manual"
                val curMode = ledger.getLibrarySyncMode(lib.id, defaultMode)
                val modeLabel = when {
                    "all".equals(curMode, ignoreCase = true) -> localize("Sync All")
                    "manual".equals(curMode, ignoreCase = true) -> localize("Manual")
                    else -> localize("Off")
                }

                val rowLib = createSettingRow(lib.name, "$modeLabel 〉")
                rowLib.setOnClickListener {
                    clickFeedback()
                    lastLibConfigFocusIndex = -1
                    buildLibraryConfigUI(lib)
                }
                containerSettingsItems.addView(rowLib)
            }
        }

        val btnFetchLibs = createTablerButton(TablerIcons.REFRESH, localize("Fetch Libraries"))
        btnFetchLibs.setOnClickListener {
            clickFeedback()
            fetchLibraries()
        }
        containerSettingsItems.addView(btnFetchLibs)

        createCategoryHeader("━ " + localize("Sync") + " ━")

        val btnRemoveDel = createSettingRow(
            localize("Remove Deleted"),
            if (provider.isRemoveDeletedEnabled) localize("ON") else localize("OFF")
        )
        btnRemoveDel.setOnClickListener {
            clickFeedback()
            val newVal = !provider.isRemoveDeletedEnabled
            provider.isRemoveDeletedEnabled = newVal
            (btnRemoveDel.getChildAt(1) as? TextView)?.text = if (newVal) localize("ON") else localize("OFF")
        }
        containerSettingsItems.addView(btnRemoveDel)

        val btnTranscode = createSettingRow(
            localize("Transcode Video"),
            if (provider.isTranscodeVideoEnabled) localize("ON") else localize("OFF")
        )
        btnTranscode.setOnClickListener {
            clickFeedback()
            val newVal = !provider.isTranscodeVideoEnabled
            provider.isTranscodeVideoEnabled = newVal
            (btnTranscode.getChildAt(1) as? TextView)?.text = if (newVal) localize("ON") else localize("OFF")
        }
        containerSettingsItems.addView(btnTranscode)

        val btnSyncNow = createTablerButton(TablerIcons.REFRESH, localize("Sync Now"))
        btnSyncNow.setOnClickListener {
            clickFeedback()
            launchSync()
        }
        containerSettingsItems.addView(btnSyncNow)

        restoreFocus(preferredFocusIndex)
    }

    fun buildLibraryConfigUI(library: MediaSyncProvider.LibraryInfo) {
        currentDepth = 2
        activeLibrary = library
        containerSettingsItems.removeAllViews()

        val ledger = syncManager.ledger
        val defaultMode = if ("music".equals(library.collectionType, ignoreCase = true)) "all" else "manual"
        val curMode = ledger.getLibrarySyncMode(library.id, defaultMode)

        createCategoryHeader("━ ${library.name} ━")

        val rowAll = createSettingRow(localize("Sync All"), if ("all".equals(curMode, ignoreCase = true)) "●" else "○")
        rowAll.setOnClickListener {
            clickFeedback()
            ledger.setLibrarySyncMode(library.id, "all")
            ledger.save()
            lastLibConfigFocusIndex = containerSettingsItems.indexOfChild(rowAll)
            buildLibraryConfigUI(library)
        }
        containerSettingsItems.addView(rowAll)

        val rowManual = createSettingRow(localize("Manual"), if ("manual".equals(curMode, ignoreCase = true)) "●" else "○")
        rowManual.setOnClickListener {
            clickFeedback()
            ledger.setLibrarySyncMode(library.id, "manual")
            ledger.save()
            lastLibConfigFocusIndex = containerSettingsItems.indexOfChild(rowManual)
            buildLibraryConfigUI(library)
        }
        containerSettingsItems.addView(rowManual)

        val rowOff = createSettingRow(localize("Off"), if ("off".equals(curMode, ignoreCase = true)) "●" else "○")
        rowOff.setOnClickListener {
            clickFeedback()
            ledger.setLibrarySyncMode(library.id, "off")
            ledger.save()
            lastLibConfigFocusIndex = containerSettingsItems.indexOfChild(rowOff)
            buildLibraryConfigUI(library)
        }
        containerSettingsItems.addView(rowOff)

        val btnBack = createSettingRow(localize("Back to Libraries"), "〈 ")
        btnBack.setOnClickListener {
            clickFeedback()
            buildSettingsUI()
        }
        containerSettingsItems.addView(btnBack)

        restoreFocus(lastLibConfigFocusIndex)
    }

    private fun testConnection() {
        provider.testConnection(object : MediaSyncProvider.ConnectionCallback {
            override fun onSuccess(serverName: String, version: String) {
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        localize("Connected successfully!") + " ($serverName v$version)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onError(error: String) {
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        localize("Connection failed: ") + error,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
    }

    private fun fetchLibraries() {
        Thread {
            try {
                val libs = provider.fetchLibraries(MediaSyncProvider.CancellationToken())
                activity.runOnUiThread {
                    Toast.makeText(activity, "${localize("Fetched")} ${libs.size} ${localize("libraries")}", Toast.LENGTH_SHORT).show()
                    buildSettingsUI()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "${localize("Error:")} ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun startQuickConnect() {
        Thread {
            try {
                val client = provider.createClient()
                val session = client.initiateQuickConnect()
                activity.runOnUiThread {
                    showQuickConnectDialog(session.code, session.secret)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "${localize("QuickConnect error:")} ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showQuickConnectDialog(code: String, secret: String) {
        val d = activity.resources.displayMetrics.density
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val bg = GradientDrawable().apply {
                setColor(ThemeManager.getOverlayBackgroundColor() or 0xEE000000.toInt())
                cornerRadius = 15 * d
                setStroke((1 * d).toInt(), 0x33FFFFFF)
            }
            background = bg
            setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (18 * d).toInt())
        }

        val tvTitle = TextView(activity).apply {
            text = localize("QuickConnect Code")
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 16f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
        }
        root.addView(tvTitle)

        val tvCode = TextView(activity).apply {
            text = code
            setTextColor(0xFF00FFFF.toInt())
            textSize = 26f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
        }
        root.addView(tvCode)

        val btnClose = createDialogButton(localize("Close")).apply {
            setOnClickListener {
                clickFeedback()
                dialog.dismiss()
            }
        }
        root.addView(btnClose)

        dialog.setContentView(root)
        dialog.window?.setLayout((290 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    fun launchSync() {
        if (syncManager.isSyncing) {
            Toast.makeText(activity, localize("Syncing..."), Toast.LENGTH_SHORT).show()
            return
        }

        showSyncModal()

        syncManager.batchUpdateCallback = object : SyncManager.LibraryBatchUpdateCallback {
            override fun onMusicBatchReady(
                newSongs: List<SongItem>,
                updatedSongs: List<SongItem>,
                trackNumbers: Map<String, Int>,
                isDirty: Boolean
            ) {
                activity.runOnUiThread {
                    synchronized(customLibrary) {
                        for ((key, value) in trackNumbers) {
                            trackNumberMap[key] = value
                        }
                    }
                }
            }

            override fun onVideosBatchReady() {
                activity.runOnUiThread {
                    onScanVideos()
                }
            }
        }

        syncManager.playbackCheckCallback = object : SyncManager.PlaybackCheckCallback {
            override fun isFileCurrentlyPlaying(file: File): Boolean {
                return isFilePlaying(file)
            }
        }

        syncManager.startSync(customLibrary, trackNumberMap)
    }

    private fun showSyncModal() {
        val d = activity.resources.displayMetrics.density
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val bg = GradientDrawable().apply {
                setColor(ThemeManager.getOverlayBackgroundColor() or 0xEE000000.toInt())
                cornerRadius = 15 * d
                setStroke((1 * d).toInt(), 0x33FFFFFF)
            }
            background = bg
            setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (18 * d).toInt())
        }

        val tvTitle = TextView(activity).apply {
            text = localize("Jellyfin Sync")
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 16f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
        }
        root.addView(tvTitle)

        val tvStatus = TextView(activity).apply {
            text = localize("Syncing...")
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, (8 * d).toInt())
        }
        root.addView(tvStatus)

        val pBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            val pLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (8 * d).toInt()).apply {
                bottomMargin = (8 * d).toInt()
            }
            layoutParams = pLp
        }
        root.addView(pBar)

        val tvDetail = TextView(activity).apply {
            text = ""
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * d).toInt())
        }
        root.addView(tvDetail)

        val btnCancel = createDialogButton(localize("Cancel")).apply {
            setOnClickListener {
                clickFeedback()
                syncManager.cancelSync()
                dialog.dismiss()
            }
        }
        root.addView(btnCancel)

        dialog.setContentView(root)
        dialog.window?.setLayout((290 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

        syncManager.syncListener = object : SyncManager.SyncListener {
            override fun onStateChanged(state: SyncManager.SyncState, message: String?) {
                activity.runOnUiThread {
                    tvStatus.text = message ?: ""
                    when (state) {
                        SyncManager.SyncState.COMPLETE -> {
                            Toast.makeText(activity, localize("Sync Complete"), Toast.LENGTH_SHORT).show()
                            if (dialog.isShowing) dialog.dismiss()
                            buildSettingsUI()
                            onSyncFinished()
                        }
                        SyncManager.SyncState.ERROR -> {
                            Toast.makeText(activity, message ?: "Error", Toast.LENGTH_LONG).show()
                            if (dialog.isShowing) dialog.dismiss()
                        }
                        SyncManager.SyncState.CANCELLED -> {
                            if (dialog.isShowing) dialog.dismiss()
                        }
                        else -> {}
                    }
                }
            }

            override fun onProgress(
                libraryName: String,
                currentItem: Int,
                totalItems: Int,
                currentTitle: String,
                bytesRead: Long,
                bytesTotal: Long
            ) {
                activity.runOnUiThread {
                    val pct = if (totalItems > 0) currentItem * 100 / totalItems else 0
                    pBar.progress = pct
                    tvStatus.text = "$libraryName ($currentItem/$totalItems)"
                    val sizeStr = if (bytesTotal > 0) " - ${formatSize(bytesRead)} / ${formatSize(bytesTotal)}" else ""
                    tvDetail.text = "$currentTitle$sizeStr"
                }
            }
        }

        dialog.show()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1.0) {
            return String.format(Locale.US, "%.1f GB", gb)
        }
        val mb = bytes / (1024 * 1024)
        return "$mb MB"
    }

    private fun restoreFocus(targetIndex: Int) {
        containerSettingsItems.post {
            if (targetIndex in 0 until containerSettingsItems.childCount) {
                val target = containerSettingsItems.getChildAt(targetIndex)
                if (target != null && target.isFocusable && target.visibility == View.VISIBLE) {
                    target.requestFocus()
                    return@post
                }
            }
            for (i in 0 until containerSettingsItems.childCount) {
                val child = containerSettingsItems.getChildAt(i)
                if (child != null && child.isFocusable && child.visibility == View.VISIBLE) {
                    child.requestFocus()
                    break
                }
            }
        }
    }
}
