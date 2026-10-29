package com.themoon.y1.controllers.sync

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.StatFs
import android.text.TextUtils
import android.util.TypedValue
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
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.sync.MediaSyncProvider
import com.themoon.y1.sync.SyncManager
import com.themoon.y1.sync.jellyfin.JellyfinClient
import com.themoon.y1.sync.jellyfin.JellyfinMediaItem
import com.themoon.y1.sync.jellyfin.JellyfinSyncHierarchy
import com.themoon.y1.sync.jellyfin.JellyfinSyncProvider
import com.themoon.y1.views.TablerIcons
import java.io.File
import java.text.SimpleDateFormat
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
    private val onRequestWifi: (action: Int) -> Unit,
    private val isNetworkConnected: () -> Boolean,
    private val onSyncFinished: () -> Unit,
    private val onScanVideos: () -> Unit,
    private val isFilePlaying: (File?) -> Boolean,
    private val customLibrary: MutableList<SongItem>,
    private val trackNumberMap: MutableMap<String, Int>,
    private val localize: (String) -> String,
    private val isCustomScanning: () -> Boolean = { false },
    private val onDepthChanged: ((depth: Int) -> Unit)? = null
) : ScreenController {

    companion object {
        const val PENDING_ACTION_NONE = 0
        const val PENDING_ACTION_TEST = 1
        const val PENDING_ACTION_FETCH = 2
        const val PENDING_ACTION_SYNC = 3
        const val PENDING_ACTION_QUICKCONNECT = 4
    }

    private val containerSettingsItems: LinearLayout = rootView.findViewById(R.id.container_settings_items)
    private val provider: JellyfinSyncProvider get() = JellyfinSyncProvider.getInstance(activity)
    private val syncManager: SyncManager get() = SyncManager.getInstance(activity)

    var currentDepth: Int = 1
        private set(value) {
            field = value
            onDepthChanged?.invoke(value)
        }

    var activeLibrary: MediaSyncProvider.LibraryInfo? = null
        private set
    var currentHierarchy: JellyfinSyncHierarchy? = null
        private set

    var currentSelectedSeries: JellyfinSyncHierarchy.SeriesNode? = null
        private set
    var currentSelectedSeason: JellyfinSyncHierarchy.SeasonNode? = null
        private set
    var currentSelectedArtist: JellyfinSyncHierarchy.ArtistNode? = null
        private set
    var currentSelectedAlbum: JellyfinSyncHierarchy.AlbumNode? = null
        private set
    var currentSelectedAuthor: JellyfinSyncHierarchy.AuthorNode? = null
        private set
    var currentSelectedBook: JellyfinSyncHierarchy.BookNode? = null
        private set

    var lastLongClickTime: Long = 0L
    private var isLongPressActive: Boolean = false

    var lastFocusIndex: Int = -1
    var lastLibConfigFocusIndex: Int = -1
    var lastBrowseDepth3FocusIndex: Int = -1
    var lastBrowseDepth4FocusIndex: Int = -1
    var lastBrowseDepth5FocusIndex: Int = -1

    override fun onEnter(state: ScreenState) {
        buildSettingsUI()
    }

    override fun onExit() {
        currentDepth = 1
        isLongPressActive = false
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == 23) {
            val c = activity.currentFocus
            if (c != null && currentDepth >= 3) {
                lastLongClickTime = System.currentTimeMillis()
                isLongPressActive = true
                val main = activity as? com.themoon.y1.MainActivity
                if (main != null) {
                    main.isLongPressConsumed = true
                }
                c.performLongClick()
                return true
            }
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == 23) {
            val main = activity as? com.themoon.y1.MainActivity
            if (isLongPressActive || main?.isLongPressConsumed == true || System.currentTimeMillis() - lastLongClickTime < 600) {
                isLongPressActive = false
                main?.isLongPressConsumed = false
                lastLongClickTime = System.currentTimeMillis()
                return true
            }
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        when (currentDepth) {
            5 -> {
                val lib = activeLibrary
                if (lib != null) {
                    if ("tvshows".equals(lib.collectionType, ignoreCase = true)) {
                        currentSelectedSeries?.let { buildTvSeasonsListUI(it) } ?: buildLibraryConfigUI(lib)
                    } else if ("music".equals(lib.collectionType, ignoreCase = true)) {
                        currentSelectedArtist?.let { buildMusicAlbumsListUI(it) } ?: buildLibraryConfigUI(lib)
                    } else if ("books".equals(lib.collectionType, ignoreCase = true) || "audiobooks".equals(lib.collectionType, ignoreCase = true)) {
                        currentSelectedAuthor?.let { buildBookTitlesListUI(it) } ?: buildLibraryConfigUI(lib)
                    } else {
                        buildLibraryConfigUI(lib)
                    }
                } else {
                    buildSettingsUI()
                }
                return true
            }
            4 -> {
                val lib = activeLibrary
                if (lib != null) {
                    if ("tvshows".equals(lib.collectionType, ignoreCase = true)) {
                        buildTvSeriesListUI()
                    } else if ("music".equals(lib.collectionType, ignoreCase = true)) {
                        buildMusicArtistsListUI()
                    } else if ("books".equals(lib.collectionType, ignoreCase = true) || "audiobooks".equals(lib.collectionType, ignoreCase = true)) {
                        buildBookAuthorsListUI()
                    } else {
                        buildLibraryConfigUI(lib)
                    }
                } else {
                    buildSettingsUI()
                }
                return true
            }
            3 -> {
                val lib = activeLibrary
                if (lib != null) {
                    buildLibraryConfigUI(lib)
                } else {
                    buildSettingsUI()
                }
                return true
            }
            2 -> {
                buildSettingsUI()
                return true
            }
            else -> return false
        }
    }

    private fun createWrappedSettingRow(title: String, value: String): LinearLayout {
        val row = createSettingRow(title, value)
        val origListener = row.onFocusChangeListener
        row.setOnFocusChangeListener { v, hasFocus ->
            origListener?.onFocusChange(v, hasFocus)
            if (hasFocus) {
                val idx = containerSettingsItems.indexOfChild(row)
                if (idx != -1) {
                    if (currentDepth == 1) {
                        lastFocusIndex = idx
                    } else if (currentDepth == 2) {
                        lastLibConfigFocusIndex = idx
                    }
                }
            }
        }
        return row
    }

    private fun createWrappedTablerButton(icon: Array<String>, text: String): View {
        val btn = createTablerButton(icon, text)
        val origListener = btn.onFocusChangeListener
        btn.setOnFocusChangeListener { v, hasFocus ->
            origListener?.onFocusChange(v, hasFocus)
            if (hasFocus) {
                val idx = containerSettingsItems.indexOfChild(btn)
                if (idx != -1 && currentDepth == 1) {
                    lastFocusIndex = idx
                }
            }
        }
        return btn
    }

    @JvmOverloads
    fun buildSettingsUI(preferredFocusIndex: Int = lastFocusIndex) {
        currentDepth = 1
        containerSettingsItems.removeAllViews()

        createCategoryHeader("━ " + localize("Server") + " ━")

        val rawUrl = provider.serverUrl
        val rowUrl = createWrappedSettingRow(localize("Server URL"), if (rawUrl.isEmpty()) localize("Not Configured") else rawUrl)
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

        val rowAuthMode = createWrappedSettingRow(localize("Auth Mode"), authModeLabel)
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
                val rowUser = createWrappedSettingRow(localize("Username"), userSummary)
                containerSettingsItems.addView(rowUser)
            }

            val btnQuickConnect = createWrappedTablerButton(TablerIcons.LOGIN, localize("Connect via QuickConnect"))
            btnQuickConnect.setOnClickListener {
                clickFeedback()
                startQuickConnect()
            }
            containerSettingsItems.addView(btnQuickConnect)
        } else if ("token".equals(authMode, ignoreCase = true)) {
            val token = provider.accessToken
            val rowToken = createWrappedSettingRow(localize("API Token"), if (token.isEmpty()) localize("Not Configured") else "••••••••")
            rowToken.setOnClickListener {
                clickFeedback()
                onOpenKeyboard(8, provider.accessToken)
            }
            containerSettingsItems.addView(rowToken)
        } else {
            val user = provider.username
            val rowUser = createWrappedSettingRow(localize("Username"), if (user.isEmpty()) localize("Not Configured") else user)
            rowUser.setOnClickListener {
                clickFeedback()
                onOpenKeyboard(6, provider.username)
            }
            containerSettingsItems.addView(rowUser)

            val pass = provider.password
            val rowPass = createWrappedSettingRow(localize("Password"), if (pass.isEmpty()) localize("Not Configured") else "••••••••")
            rowPass.setOnClickListener {
                clickFeedback()
                onOpenKeyboard(7, provider.password)
            }
            containerSettingsItems.addView(rowPass)
        }

        val btnTest = createWrappedTablerButton(TablerIcons.WIFI, localize("Test Connection"))
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

            if (provider.isConfigured() && isNetworkConnected()) {
                Thread {
                    try {
                        val fetched = provider.fetchLibraries(MediaSyncProvider.CancellationToken())
                        if (!fetched.isNullOrEmpty()) {
                            activity.runOnUiThread {
                                if (currentDepth == 1) {
                                    buildSettingsUI()
                                }
                            }
                        }
                    } catch (ignored: Exception) {}
                }.start()
            }
        } else {
            for (lib in cachedLibs) {
                val defaultMode = if ("music".equals(lib.collectionType, ignoreCase = true)) {
                    "all"
                } else if ("movies".equals(lib.collectionType, ignoreCase = true) || "tvshows".equals(lib.collectionType, ignoreCase = true) ||
                    "books".equals(lib.collectionType, ignoreCase = true) || "audiobooks".equals(lib.collectionType, ignoreCase = true)
                ) {
                    "manual"
                } else {
                    "off"
                }
                val curMode = ledger.getLibrarySyncMode(lib.id, defaultMode)
                val modeLabel = when {
                    "all".equals(curMode, ignoreCase = true) -> localize("Sync All")
                    "manual".equals(curMode, ignoreCase = true) -> localize("Manual")
                    else -> localize("Off")
                }

                val libIcon = if ("music".equals(lib.collectionType, ignoreCase = true)) {
                    TablerIcons.MUSIC
                } else if ("books".equals(lib.collectionType, ignoreCase = true) || "audiobooks".equals(lib.collectionType, ignoreCase = true)) {
                    TablerIcons.BOOK
                } else if ("movies".equals(lib.collectionType, ignoreCase = true)) {
                    TablerIcons.MOVIE
                } else {
                    TablerIcons.DEVICE_TV
                }

                val rowLib = createLibraryRow(libIcon, lib.name, modeLabel) {
                    clickFeedback()
                    lastLibConfigFocusIndex = -1
                    buildLibraryConfigUI(lib)
                }
                containerSettingsItems.addView(rowLib)
            }
        }

        val btnFetchLibs = createWrappedTablerButton(TablerIcons.REFRESH, localize("Fetch Libraries"))
        btnFetchLibs.setOnClickListener {
            clickFeedback()
            fetchLibraries()
        }
        containerSettingsItems.addView(btnFetchLibs)

        createCategoryHeader("━ " + localize("Sync") + " ━")

        val btnRemoveDel = createWrappedSettingRow(
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

        val btnTranscode = createWrappedSettingRow(
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

        val btnCovers = createWrappedSettingRow(
            localize("Download Covers"),
            if (provider.isDownloadCoversEnabled) localize("ON") else localize("OFF")
        )
        btnCovers.setOnClickListener {
            clickFeedback()
            val newVal = !provider.isDownloadCoversEnabled
            provider.isDownloadCoversEnabled = newVal
            (btnCovers.getChildAt(1) as? TextView)?.text = if (newVal) localize("ON") else localize("OFF")
        }
        containerSettingsItems.addView(btnCovers)

        val lastSync = syncManager.ledger.getLastSyncTime()
        var lastSyncStr = localize("Never")
        if (lastSync > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            lastSyncStr = sdf.format(java.util.Date(lastSync))
        }
        val rowLastSync = createWrappedSettingRow(localize("Last Synced"), lastSyncStr)
        rowLastSync.setOnClickListener {
            clickFeedback()
        }
        containerSettingsItems.addView(rowLastSync)

        val btnSyncNow = createWrappedTablerButton(TablerIcons.DOWNLOAD, localize("Sync Now"))
        btnSyncNow.setOnClickListener {
            clickFeedback()
            launchSync()
        }
        containerSettingsItems.addView(btnSyncNow)

        val btnLogout = createWrappedSettingRow(localize("Log Out / Reset"), "〉 ")
        btnLogout.setOnClickListener {
            clickFeedback()
            provider.clearConfiguration()
            lastFocusIndex = -1
            buildSettingsUI()
        }
        containerSettingsItems.addView(btnLogout)

        restoreFocus(1, preferredFocusIndex)
    }

    fun buildLibraryConfigUI(library: MediaSyncProvider.LibraryInfo) {
        currentDepth = 2
        if (activeLibrary == null || library.id != activeLibrary?.id) {
            currentHierarchy = null
        }
        activeLibrary = library
        containerSettingsItems.removeAllViews()

        val ledger = syncManager.ledger
        val defaultMode = if ("music".equals(library.collectionType, ignoreCase = true)) {
            "all"
        } else if ("movies".equals(library.collectionType, ignoreCase = true) || "tvshows".equals(library.collectionType, ignoreCase = true) ||
            "books".equals(library.collectionType, ignoreCase = true) || "audiobooks".equals(library.collectionType, ignoreCase = true)
        ) {
            "manual"
        } else {
            "off"
        }
        val curMode = ledger.getLibrarySyncMode(library.id, defaultMode)

        createCategoryHeader("━ ${library.name} ━")

        val rowAll = createWrappedSettingRow(localize("Sync All"), if ("all".equals(curMode, ignoreCase = true)) "●" else "○")
        rowAll.setOnClickListener {
            clickFeedback()
            ledger.setLibrarySyncMode(library.id, "all")
            ledger.save()
            lastLibConfigFocusIndex = containerSettingsItems.indexOfChild(rowAll)
            buildLibraryConfigUI(library)
        }
        containerSettingsItems.addView(rowAll)

        val rowManual = createWrappedSettingRow(localize("Manual"), if ("manual".equals(curMode, ignoreCase = true)) "●" else "○")
        rowManual.setOnClickListener {
            clickFeedback()
            ledger.setLibrarySyncMode(library.id, "manual")
            ledger.save()
            lastLibConfigFocusIndex = containerSettingsItems.indexOfChild(rowManual)
            buildLibraryConfigUI(library)
        }
        containerSettingsItems.addView(rowManual)

        val rowOff = createWrappedSettingRow(localize("Off"), if ("off".equals(curMode, ignoreCase = true)) "●" else "○")
        rowOff.setOnClickListener {
            clickFeedback()
            ledger.setLibrarySyncMode(library.id, "off")
            ledger.save()
            lastLibConfigFocusIndex = containerSettingsItems.indexOfChild(rowOff)
            buildLibraryConfigUI(library)
        }
        containerSettingsItems.addView(rowOff)

        if ("manual".equals(curMode, ignoreCase = true)) {
            createCategoryHeader("━━━━━━━━━━━━━━")
            val btnBrowse = createWrappedSettingRow(localize("Browse & Select"), "〉 ")
            btnBrowse.setOnClickListener {
                clickFeedback()
                lastBrowseDepth3FocusIndex = -1
                buildBrowseSelectUI(library)
            }
            containerSettingsItems.addView(btnBrowse)

            val sel = ledger.getLibrarySelections(library.id)
            val rowSelCount = createWrappedSettingRow(localize("Selected"), sel.size.toString())
            containerSettingsItems.addView(rowSelCount)
        }

        restoreFocus(2, lastLibConfigFocusIndex)
    }

    fun buildBrowseSelectUI(library: MediaSyncProvider.LibraryInfo) {
        currentDepth = 3
        activeLibrary = library

        val hierarchy = currentHierarchy
        if (hierarchy != null) {
            if ("tvshows".equals(library.collectionType, ignoreCase = true)) {
                buildTvSeriesListUI()
            } else if ("music".equals(library.collectionType, ignoreCase = true)) {
                buildMusicArtistsListUI()
            } else if ("books".equals(library.collectionType, ignoreCase = true) || "audiobooks".equals(library.collectionType, ignoreCase = true)) {
                buildBookAuthorsListUI()
            } else {
                buildMoviesListUI()
            }
            return
        }

        containerSettingsItems.removeAllViews()
        createCategoryHeader("━ ${library.name} ━")

        val d = activity.resources.displayMetrics.density
        val layoutLoading = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((20 * d).toInt(), (24 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
        }

        val tvLoading = TextView(activity).apply {
            text = "${localize("Counting files...")}\n${localize("Please wait.")}"
            setTextColor(ThemeManager.getTextColorSecondary())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (ThemeManager.getListTextSize() - 1f) * d)
            typeface = ThemeManager.getCustomFont()
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (14 * d).toInt())
        }
        layoutLoading.addView(tvLoading)

        val pbLoading = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (8 * d).toInt()
            )
        }
        layoutLoading.addView(pbLoading)

        containerSettingsItems.addView(layoutLoading)

        Thread {
            try {
                val validToken = provider.ensureValidAccessToken()
                val client = provider.createClient()

                val progressListener = object : JellyfinClient.ItemsFetchProgressListener {
                    override fun onProgress(loaded: Int, total: Int) {
                        activity.runOnUiThread {
                            if (currentDepth != 3) return@runOnUiThread
                            if (total > 0) {
                                pbLoading.isIndeterminate = false
                                pbLoading.max = total
                                pbLoading.progress = loaded
                                tvLoading.text = "${localize("Counting files...")}\n$loaded / $total"
                            } else {
                                tvLoading.text = "${localize("Counting files...")}\n$loaded"
                            }
                        }
                    }
                }

                val items = client.fetchLibraryItemsAll(
                    validToken,
                    provider.userId,
                    library.id,
                    library.collectionType,
                    progressListener,
                    null
                )

                activity.runOnUiThread {
                    if (currentDepth != 3) return@runOnUiThread
                    if ("tvshows".equals(library.collectionType, ignoreCase = true)) {
                        currentHierarchy = JellyfinSyncHierarchy.buildTvHierarchy(items)
                        buildTvSeriesListUI()
                    } else if ("music".equals(library.collectionType, ignoreCase = true)) {
                        currentHierarchy = JellyfinSyncHierarchy.buildMusicHierarchy(items)
                        buildMusicArtistsListUI()
                    } else if ("books".equals(library.collectionType, ignoreCase = true) || "audiobooks".equals(library.collectionType, ignoreCase = true)) {
                        currentHierarchy = JellyfinSyncHierarchy.buildBooksHierarchy(items)
                        buildBookAuthorsListUI()
                    } else {
                        currentHierarchy = JellyfinSyncHierarchy.buildMoviesHierarchy(items)
                        buildMoviesListUI()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    tvLoading.text = localize("Cannot reach server. Check URL and network.")
                    pbLoading.visibility = View.GONE
                }
            }
        }.start()
    }

    fun buildTvSeriesListUI() {
        val hierarchy = currentHierarchy ?: return
        val library = activeLibrary ?: return
        currentDepth = 3
        containerSettingsItems.removeAllViews()

        createCategoryHeader("━ ${library.name} ━")

        val ledger = syncManager.ledger
        val currentSelections = ledger.getLibrarySelections(library.id)

        var totalSize = 0L
        for (s in hierarchy.seriesList) {
            for (sn in s.seasons) {
                for (ep in sn.episodes) {
                    if (currentSelections.contains(ep.id)) {
                        totalSize += ep.fileSize
                    }
                }
            }
        }

        val totalSelected = JellyfinSyncHierarchy.countSelected(hierarchy.allItemIds, currentSelections)
        addStorageHeader(totalSize, totalSelected, hierarchy.allItemIds.size)

        val overallState = JellyfinSyncHierarchy.computeTriState(hierarchy.allItemIds, currentSelections)

        val rowToggleAll = createToggleAllRow(
            overallState,
            totalSelected,
            hierarchy.allItemIds.size
        ) {
            clickFeedback()
            if (overallState == JellyfinSyncHierarchy.TriState.CHECKED) {
                ledger.removeLibrarySelections(library.id, hierarchy.allItemIds)
            } else {
                ledger.addLibrarySelections(library.id, hierarchy.allItemIds)
            }
            ledger.save()
            buildTvSeriesListUI()
        }
        containerSettingsItems.addView(rowToggleAll)

        for (series in hierarchy.seriesList) {
            val sel = series.getSelectedCount(currentSelections)
            val tot = series.allEpisodeIds.size
            val st = series.getTriState(currentSelections)

            val row = createSelectRow(
                TablerIcons.DEVICE_TV,
                series.name,
                "$sel/$tot",
                st,
                true,
                clickListener = {
                    clickFeedback()
                    currentSelectedSeries = series
                    lastBrowseDepth4FocusIndex = -1
                    buildTvSeasonsListUI(series)
                },
                longClickListener = {
                    clickFeedback()
                    val cur = series.getTriState(ledger.getLibrarySelections(library.id))
                    if (cur == JellyfinSyncHierarchy.TriState.CHECKED) {
                        ledger.removeLibrarySelections(library.id, series.allEpisodeIds)
                    } else {
                        ledger.addLibrarySelections(library.id, series.allEpisodeIds)
                    }
                    ledger.save()
                    buildTvSeriesListUI()
                    true
                }
            )
            containerSettingsItems.addView(row)
        }

        restoreFocus(3, lastBrowseDepth3FocusIndex)
    }

    fun buildTvSeasonsListUI(series: JellyfinSyncHierarchy.SeriesNode) {
        val library = activeLibrary ?: return
        currentDepth = 4
        currentSelectedSeries = series
        containerSettingsItems.removeAllViews()

        createCategoryHeader("━ ${series.name} ━")

        val ledger = syncManager.ledger
        val currentSelections = ledger.getLibrarySelections(library.id)

        var totalSize = 0L
        for (sn in series.seasons) {
            for (ep in sn.episodes) {
                if (currentSelections.contains(ep.id)) {
                    totalSize += ep.fileSize
                }
            }
        }

        val totalSelected = series.getSelectedCount(currentSelections)
        addStorageHeader(totalSize, totalSelected, series.allEpisodeIds.size)

        val overallState = series.getTriState(currentSelections)

        val rowToggleAll = createToggleAllRow(
            overallState,
            totalSelected,
            series.allEpisodeIds.size
        ) {
            clickFeedback()
            if (overallState == JellyfinSyncHierarchy.TriState.CHECKED) {
                ledger.removeLibrarySelections(library.id, series.allEpisodeIds)
            } else {
                ledger.addLibrarySelections(library.id, series.allEpisodeIds)
            }
            ledger.save()
            buildTvSeasonsListUI(series)
        }
        containerSettingsItems.addView(rowToggleAll)

        for (season in series.seasons) {
            val sel = season.getSelectedCount(currentSelections)
            val tot = season.allEpisodeIds.size
            val st = season.getTriState(currentSelections)

            val row = createSelectRow(
                null,
                season.name,
                "$sel/$tot",
                st,
                true,
                clickListener = {
                    clickFeedback()
                    currentSelectedSeason = season
                    lastBrowseDepth5FocusIndex = -1
                    buildTvEpisodesListUI(series, season)
                },
                longClickListener = {
                    clickFeedback()
                    val cur = season.getTriState(ledger.getLibrarySelections(library.id))
                    if (cur == JellyfinSyncHierarchy.TriState.CHECKED) {
                        ledger.removeLibrarySelections(library.id, season.allEpisodeIds)
                    } else {
                        ledger.addLibrarySelections(library.id, season.allEpisodeIds)
                    }
                    ledger.save()
                    buildTvSeasonsListUI(series)
                    true
                }
            )
            containerSettingsItems.addView(row)
        }

        restoreFocus(4, lastBrowseDepth4FocusIndex)
    }

    fun buildTvEpisodesListUI(
        series: JellyfinSyncHierarchy.SeriesNode,
        season: JellyfinSyncHierarchy.SeasonNode
    ) {
        val library = activeLibrary ?: return
        currentDepth = 5
        currentSelectedSeries = series
        currentSelectedSeason = season
        containerSettingsItems.removeAllViews()

        createCategoryHeader("━ ${series.name} - ${season.name} ━")

        val ledger = syncManager.ledger
        val currentSelections = ledger.getLibrarySelections(library.id)

        var totalSize = 0L
        for (ep in season.episodes) {
            if (currentSelections.contains(ep.id)) {
                totalSize += ep.fileSize
            }
        }

        val totalSelected = season.getSelectedCount(currentSelections)
        addStorageHeader(totalSize, totalSelected, season.allEpisodeIds.size)

        val overallState = season.getTriState(currentSelections)

        val rowToggleAll = createToggleAllRow(
            overallState,
            totalSelected,
            season.allEpisodeIds.size
        ) {
            clickFeedback()
            if (overallState == JellyfinSyncHierarchy.TriState.CHECKED) {
                ledger.removeLibrarySelections(library.id, season.allEpisodeIds)
            } else {
                ledger.addLibrarySelections(library.id, season.allEpisodeIds)
            }
            ledger.save()
            buildTvEpisodesListUI(series, season)
        }
        containerSettingsItems.addView(rowToggleAll)

        for (ep in season.episodes) {
            val isSel = currentSelections.contains(ep.id)
            val st = if (isSel) JellyfinSyncHierarchy.TriState.CHECKED else JellyfinSyncHierarchy.TriState.UNCHECKED

            val epTitle = (if (ep.indexNumber > 0) "E" + (if (ep.indexNumber < 10) "0" else "") + "${ep.indexNumber} - " else "") + ep.name
            val sizeStr = if (ep.fileSize > 0) formatSize(ep.fileSize) else ""

            val row = createSelectRow(
                null,
                epTitle,
                sizeStr,
                st,
                false,
                clickListener = {
                    clickFeedback()
                    ledger.toggleLibrarySelection(library.id, ep.id)
                    ledger.save()
                    buildTvEpisodesListUI(series, season)
                },
                longClickListener = {
                    clickFeedback()
                    val cur = season.getTriState(ledger.getLibrarySelections(library.id))
                    if (cur == JellyfinSyncHierarchy.TriState.CHECKED) {
                        ledger.removeLibrarySelections(library.id, season.allEpisodeIds)
                    } else {
                        ledger.addLibrarySelections(library.id, season.allEpisodeIds)
                    }
                    ledger.save()
                    buildTvEpisodesListUI(series, season)
                    true
                }
            )
            containerSettingsItems.addView(row)
        }

        restoreFocus(5, lastBrowseDepth5FocusIndex)
    }

    fun buildMusicArtistsListUI() {
        val hierarchy = currentHierarchy ?: return
        val library = activeLibrary ?: return
        currentDepth = 3
        containerSettingsItems.removeAllViews()

        createCategoryHeader("━ ${library.name} ━")

        val ledger = syncManager.ledger
        val currentSelections = ledger.getLibrarySelections(library.id)

        var totalSize = 0L
        for (art in hierarchy.artistsList) {
            for (alb in art.albums) {
                for (s in alb.songs) {
                    if (currentSelections.contains(s.id)) {
                        totalSize += s.fileSize
                    }
                }
            }
        }

        val totalSelected = JellyfinSyncHierarchy.countSelected(hierarchy.allItemIds, currentSelections)
        addStorageHeader(totalSize, totalSelected, hierarchy.allItemIds.size)

        val overallState = JellyfinSyncHierarchy.computeTriState(hierarchy.allItemIds, currentSelections)

        val rowToggleAll = createToggleAllRow(
            overallState,
            totalSelected,
            hierarchy.allItemIds.size
        ) {
            clickFeedback()
            if (overallState == JellyfinSyncHierarchy.TriState.CHECKED) {
                ledger.removeLibrarySelections(library.id, hierarchy.allItemIds)
            } else {
                ledger.addLibrarySelections(library.id, hierarchy.allItemIds)
            }
            ledger.save()
            buildMusicArtistsListUI()
        }
        containerSettingsItems.addView(rowToggleAll)

        for (artist in hierarchy.artistsList) {
            val sel = artist.getSelectedCount(currentSelections)
            val tot = artist.allSongIds.size
            val st = artist.getTriState(currentSelections)

            val row = createSelectRow(
                TablerIcons.USER,
                artist.name,
                "$sel/$tot",
                st,
                true,
                clickListener = {
                    clickFeedback()
                    currentSelectedArtist = artist
                    lastBrowseDepth4FocusIndex = -1
                    buildMusicAlbumsListUI(artist)
                },
                longClickListener = {
                    clickFeedback()
                    val cur = artist.getTriState(ledger.getLibrarySelections(library.id))
                    if (cur == JellyfinSyncHierarchy.TriState.CHECKED) {
                        ledger.removeLibrarySelections(library.id, artist.allSongIds)
                    } else {
                        ledger.addLibrarySelections(library.id, artist.allSongIds)
                    }
                    ledger.save()
                    buildMusicArtistsListUI()
                    true
                }
            )
            containerSettingsItems.addView(row)
        }

        restoreFocus(3, lastBrowseDepth3FocusIndex)
    }

    fun buildMusicAlbumsListUI(artist: JellyfinSyncHierarchy.ArtistNode) {
        val library = activeLibrary ?: return
        currentDepth = 4
        currentSelectedArtist = artist
        containerSettingsItems.removeAllViews()

        createCategoryHeader("━ ${artist.name} ━")

        val ledger = syncManager.ledger
        val currentSelections = ledger.getLibrarySelections(library.id)

        var totalSize = 0L
        for (alb in artist.albums) {
            for (s in alb.songs) {
                if (currentSelections.contains(s.id)) {
                    totalSize += s.fileSize
                }
            }
        }

        val totalSelected = artist.getSelectedCount(currentSelections)
        addStorageHeader(totalSize, totalSelected, artist.allSongIds.size)

        val overallState = artist.getTriState(currentSelections)

        val rowToggleAll = createToggleAllRow(
            overallState,
            totalSelected,
            artist.allSongIds.size
        ) {
            clickFeedback()
            if (overallState == JellyfinSyncHierarchy.TriState.CHECKED) {
                ledger.removeLibrarySelections(library.id, artist.allSongIds)
            } else {
                ledger.addLibrarySelections(library.id, artist.allSongIds)
            }
            ledger.save()
            buildMusicAlbumsListUI(artist)
        }
        containerSettingsItems.addView(rowToggleAll)

        for (album in artist.albums) {
            val sel = album.getSelectedCount(currentSelections)
            val tot = album.allSongIds.size
            val st = album.getTriState(currentSelections)

            val row = createSelectRow(
                TablerIcons.DISC,
                album.name,
                "$sel/$tot",
                st,
                true,
                clickListener = {
                    clickFeedback()
                    currentSelectedAlbum = album
                    lastBrowseDepth5FocusIndex = -1
                    buildMusicSongsListUI(artist, album)
                },
                longClickListener = {
                    clickFeedback()
                    val cur = album.getTriState(ledger.getLibrarySelections(library.id))
                    if (cur == JellyfinSyncHierarchy.TriState.CHECKED) {
                        ledger.removeLibrarySelections(library.id, album.allSongIds)
                    } else {
                        ledger.addLibrarySelections(library.id, album.allSongIds)
                    }
                    ledger.save()
                    buildMusicAlbumsListUI(artist)
                    true
                }
            )
            containerSettingsItems.addView(row)
        }

        restoreFocus(4, lastBrowseDepth4FocusIndex)
    }

    fun buildMusicSongsListUI(
        artist: JellyfinSyncHierarchy.ArtistNode,
        album: JellyfinSyncHierarchy.AlbumNode
    ) {
        val library = activeLibrary ?: return
        currentDepth = 5
        currentSelectedArtist = artist
        currentSelectedAlbum = album
        containerSettingsItems.removeAllViews()

        createCategoryHeader("━ ${artist.name} - ${album.name} ━")

        val ledger = syncManager.ledger
        val currentSelections = ledger.getLibrarySelections(library.id)

        var totalSize = 0L
        for (s in album.songs) {
            if (currentSelections.contains(s.id)) {
                totalSize += s.fileSize
            }
        }

        val totalSelected = album.getSelectedCount(currentSelections)
        addStorageHeader(totalSize, totalSelected, album.allSongIds.size)

        val overallState = album.getTriState(currentSelections)

        val rowToggleAll = createToggleAllRow(
            overallState,
            totalSelected,
            album.allSongIds.size
        ) {
            clickFeedback()
            if (overallState == JellyfinSyncHierarchy.TriState.CHECKED) {
                ledger.removeLibrarySelections(library.id, album.allSongIds)
            } else {
                ledger.addLibrarySelections(library.id, album.allSongIds)
            }
            ledger.save()
            buildMusicSongsListUI(artist, album)
        }
        containerSettingsItems.addView(rowToggleAll)

        for (song in album.songs) {
            val isSel = currentSelections.contains(song.id)
            val st = if (isSel) JellyfinSyncHierarchy.TriState.CHECKED else JellyfinSyncHierarchy.TriState.UNCHECKED

            val songTitle = (if (song.indexNumber > 0) (if (song.indexNumber < 10) "0" else "") + "${song.indexNumber} - " else "") + song.name
            val sizeStr = if (song.fileSize > 0) formatSize(song.fileSize) else ""

            val row = createSelectRow(
                TablerIcons.MUSIC,
                songTitle,
                sizeStr,
                st,
                false,
                clickListener = {
                    clickFeedback()
                    ledger.toggleLibrarySelection(library.id, song.id)
                    ledger.save()
                    buildMusicSongsListUI(artist, album)
                },
                longClickListener = {
                    clickFeedback()
                    val cur = album.getTriState(ledger.getLibrarySelections(library.id))
                    if (cur == JellyfinSyncHierarchy.TriState.CHECKED) {
                        ledger.removeLibrarySelections(library.id, album.allSongIds)
                    } else {
                        ledger.addLibrarySelections(library.id, album.allSongIds)
                    }
                    ledger.save()
                    buildMusicSongsListUI(artist, album)
                    true
                }
            )
            containerSettingsItems.addView(row)
        }

        restoreFocus(5, lastBrowseDepth5FocusIndex)
    }

    fun buildMoviesListUI() {
        val hierarchy = currentHierarchy ?: return
        val library = activeLibrary ?: return
        currentDepth = 3
        containerSettingsItems.removeAllViews()

        createCategoryHeader("━ ${library.name} ━")

        val ledger = syncManager.ledger
        val currentSelections = ledger.getLibrarySelections(library.id)

        var totalSize = 0L
        for (m in hierarchy.moviesList) {
            if (currentSelections.contains(m.id)) {
                totalSize += m.fileSize
            }
        }

        val totalSelected = JellyfinSyncHierarchy.countSelected(hierarchy.allItemIds, currentSelections)
        addStorageHeader(totalSize, totalSelected, hierarchy.allItemIds.size)

        val overallState = JellyfinSyncHierarchy.computeTriState(hierarchy.allItemIds, currentSelections)

        val rowToggleAll = createToggleAllRow(
            overallState,
            totalSelected,
            hierarchy.allItemIds.size
        ) {
            clickFeedback()
            if (overallState == JellyfinSyncHierarchy.TriState.CHECKED) {
                ledger.removeLibrarySelections(library.id, hierarchy.allItemIds)
            } else {
                ledger.addLibrarySelections(library.id, hierarchy.allItemIds)
            }
            ledger.save()
            buildMoviesListUI()
        }
        containerSettingsItems.addView(rowToggleAll)

        for (movie in hierarchy.moviesList) {
            val isSel = currentSelections.contains(movie.id)
            val st = if (isSel) JellyfinSyncHierarchy.TriState.CHECKED else JellyfinSyncHierarchy.TriState.UNCHECKED

            val movieYear = movie.year
            val yearSuffix = if (!movieYear.isNullOrEmpty() && !movieYear.contains("Unknown")) " ($movieYear)" else ""
            val movieTitle = movie.name + yearSuffix
            val sizeStr = if (movie.fileSize > 0) formatSize(movie.fileSize) else ""

            val row = createSelectRow(
                TablerIcons.MOVIE,
                movieTitle,
                sizeStr,
                st,
                false,
                clickListener = {
                    clickFeedback()
                    ledger.toggleLibrarySelection(library.id, movie.id)
                    ledger.save()
                    buildMoviesListUI()
                },
                longClickListener = {
                    clickFeedback()
                    val cur = JellyfinSyncHierarchy.computeTriState(
                        hierarchy.allItemIds,
                        ledger.getLibrarySelections(library.id)
                    )
                    if (cur == JellyfinSyncHierarchy.TriState.CHECKED) {
                        ledger.removeLibrarySelections(library.id, hierarchy.allItemIds)
                    } else {
                        ledger.addLibrarySelections(library.id, hierarchy.allItemIds)
                    }
                    ledger.save()
                    buildMoviesListUI()
                    true
                }
            )
            containerSettingsItems.addView(row)
        }

        restoreFocus(3, lastBrowseDepth3FocusIndex)
    }

    fun buildBookAuthorsListUI() {
        val hierarchy = currentHierarchy ?: return
        val library = activeLibrary ?: return
        currentDepth = 3
        containerSettingsItems.removeAllViews()

        createCategoryHeader("━ ${library.name} ━")

        val ledger = syncManager.ledger
        val currentSelections = ledger.getLibrarySelections(library.id)

        var totalSize = 0L
        for (aut in hierarchy.authorsList) {
            for (bk in aut.books) {
                for (ch in bk.chapters) {
                    if (currentSelections.contains(ch.id)) {
                        totalSize += ch.fileSize
                    }
                }
            }
        }

        val totalSelected = JellyfinSyncHierarchy.countSelected(hierarchy.allItemIds, currentSelections)
        addStorageHeader(totalSize, totalSelected, hierarchy.allItemIds.size)

        val overallState = JellyfinSyncHierarchy.computeTriState(hierarchy.allItemIds, currentSelections)

        val rowToggleAll = createToggleAllRow(
            overallState,
            totalSelected,
            hierarchy.allItemIds.size
        ) {
            clickFeedback()
            if (overallState == JellyfinSyncHierarchy.TriState.CHECKED) {
                ledger.removeLibrarySelections(library.id, hierarchy.allItemIds)
            } else {
                ledger.addLibrarySelections(library.id, hierarchy.allItemIds)
            }
            ledger.save()
            buildBookAuthorsListUI()
        }
        containerSettingsItems.addView(rowToggleAll)

        for (author in hierarchy.authorsList) {
            val sel = author.getSelectedCount(currentSelections)
            val tot = author.allChapterIds.size
            val st = author.getTriState(currentSelections)

            val row = createSelectRow(
                TablerIcons.USER,
                author.name,
                "$sel/$tot",
                st,
                true,
                clickListener = {
                    clickFeedback()
                    currentSelectedAuthor = author
                    lastBrowseDepth4FocusIndex = -1
                    buildBookTitlesListUI(author)
                },
                longClickListener = {
                    clickFeedback()
                    val cur = author.getTriState(ledger.getLibrarySelections(library.id))
                    if (cur == JellyfinSyncHierarchy.TriState.CHECKED) {
                        ledger.removeLibrarySelections(library.id, author.allChapterIds)
                    } else {
                        ledger.addLibrarySelections(library.id, author.allChapterIds)
                    }
                    ledger.save()
                    buildBookAuthorsListUI()
                    true
                }
            )
            containerSettingsItems.addView(row)
        }

        restoreFocus(3, lastBrowseDepth3FocusIndex)
    }

    fun buildBookTitlesListUI(author: JellyfinSyncHierarchy.AuthorNode) {
        val library = activeLibrary ?: return
        currentDepth = 4
        currentSelectedAuthor = author
        containerSettingsItems.removeAllViews()

        createCategoryHeader("━ ${author.name} ━")

        val ledger = syncManager.ledger
        val currentSelections = ledger.getLibrarySelections(library.id)

        var totalSize = 0L
        for (bk in author.books) {
            for (ch in bk.chapters) {
                if (currentSelections.contains(ch.id)) {
                    totalSize += ch.fileSize
                }
            }
        }

        val totalSelected = author.getSelectedCount(currentSelections)
        addStorageHeader(totalSize, totalSelected, author.allChapterIds.size)

        val overallState = author.getTriState(currentSelections)

        val rowToggleAll = createToggleAllRow(
            overallState,
            totalSelected,
            author.allChapterIds.size
        ) {
            clickFeedback()
            if (overallState == JellyfinSyncHierarchy.TriState.CHECKED) {
                ledger.removeLibrarySelections(library.id, author.allChapterIds)
            } else {
                ledger.addLibrarySelections(library.id, author.allChapterIds)
            }
            ledger.save()
            buildBookTitlesListUI(author)
        }
        containerSettingsItems.addView(rowToggleAll)

        for (book in author.books) {
            val sel = book.getSelectedCount(currentSelections)
            val tot = book.allChapterIds.size
            val st = book.getTriState(currentSelections)

            val row = createSelectRow(
                TablerIcons.BOOK,
                book.name,
                "$sel/$tot",
                st,
                true,
                clickListener = {
                    clickFeedback()
                    currentSelectedBook = book
                    lastBrowseDepth5FocusIndex = -1
                    buildBookChaptersListUI(author, book)
                },
                longClickListener = {
                    clickFeedback()
                    val cur = book.getTriState(ledger.getLibrarySelections(library.id))
                    if (cur == JellyfinSyncHierarchy.TriState.CHECKED) {
                        ledger.removeLibrarySelections(library.id, book.allChapterIds)
                    } else {
                        ledger.addLibrarySelections(library.id, book.allChapterIds)
                    }
                    ledger.save()
                    buildBookTitlesListUI(author)
                    true
                }
            )
            containerSettingsItems.addView(row)
        }

        restoreFocus(4, lastBrowseDepth4FocusIndex)
    }

    fun buildBookChaptersListUI(
        author: JellyfinSyncHierarchy.AuthorNode,
        book: JellyfinSyncHierarchy.BookNode
    ) {
        val library = activeLibrary ?: return
        currentDepth = 5
        currentSelectedAuthor = author
        currentSelectedBook = book
        containerSettingsItems.removeAllViews()

        createCategoryHeader("━ ${author.name} - ${book.name} ━")

        val ledger = syncManager.ledger
        val currentSelections = ledger.getLibrarySelections(library.id)

        var totalSize = 0L
        for (ch in book.chapters) {
            if (currentSelections.contains(ch.id)) {
                totalSize += ch.fileSize
            }
        }

        val totalSelected = book.getSelectedCount(currentSelections)
        addStorageHeader(totalSize, totalSelected, book.allChapterIds.size)

        val overallState = book.getTriState(currentSelections)

        val rowToggleAll = createToggleAllRow(
            overallState,
            totalSelected,
            book.allChapterIds.size
        ) {
            clickFeedback()
            if (overallState == JellyfinSyncHierarchy.TriState.CHECKED) {
                ledger.removeLibrarySelections(library.id, book.allChapterIds)
            } else {
                ledger.addLibrarySelections(library.id, book.allChapterIds)
            }
            ledger.save()
            buildBookChaptersListUI(author, book)
        }
        containerSettingsItems.addView(rowToggleAll)

        for (ch in book.chapters) {
            val isSel = currentSelections.contains(ch.id)
            val st = if (isSel) JellyfinSyncHierarchy.TriState.CHECKED else JellyfinSyncHierarchy.TriState.UNCHECKED

            val chTitle = (if (ch.indexNumber > 0) (if (ch.indexNumber < 10) "0" else "") + "${ch.indexNumber} - " else "") + ch.name
            val sizeStr = if (ch.fileSize > 0) formatSize(ch.fileSize) else ""

            val row = createSelectRow(
                TablerIcons.BOOK,
                chTitle,
                sizeStr,
                st,
                false,
                clickListener = {
                    clickFeedback()
                    ledger.toggleLibrarySelection(library.id, ch.id)
                    ledger.save()
                    buildBookChaptersListUI(author, book)
                },
                longClickListener = {
                    clickFeedback()
                    val cur = book.getTriState(ledger.getLibrarySelections(library.id))
                    if (cur == JellyfinSyncHierarchy.TriState.CHECKED) {
                        ledger.removeLibrarySelections(library.id, book.allChapterIds)
                    } else {
                        ledger.addLibrarySelections(library.id, book.allChapterIds)
                    }
                    ledger.save()
                    buildBookChaptersListUI(author, book)
                    true
                }
            )
            containerSettingsItems.addView(row)
        }

        restoreFocus(5, lastBrowseDepth5FocusIndex)
    }

    private fun addStorageHeader(selectedBytes: Long, selectedCount: Int, totalCount: Int) {
        val root = SyncManager.getSyncRoot()
        var freeBytes = 0L
        try {
            val stat = StatFs(root.absolutePath)
            freeBytes = stat.availableBlocksLong * stat.blockSizeLong
        } catch (ignored: Exception) {}

        val tvHeader = TextView(activity).apply {
            typeface = ThemeManager.getCustomFontBold()
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 13f
            setPadding(10, 15, 10, 5)
            val infoHeader = "${localize("Selected")}: $selectedCount/$totalCount" +
                (if (selectedBytes > 0) " (${formatSize(selectedBytes)})" else "") +
                " | ${localize("Free")}: ${formatSize(freeBytes)}"
            text = infoHeader
        }
        containerSettingsItems.addView(tvHeader)
    }

    private fun createButtonBackground(color: Int): GradientDrawable {
        val shape = GradientDrawable()
        shape.setColor(color)
        val radius = ThemeManager.getButtonRadius() * activity.resources.displayMetrics.density
        shape.cornerRadius = radius
        return shape
    }

    private fun createFocusedButtonBackground(): GradientDrawable {
        if (ThemeManager.isClassicTheme()) {
            return GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF62B7F5.toInt(), 0xFF2588DC.toInt(), 0xFF0865C4.toInt())
            )
        }
        val base = ThemeManager.getListButtonFocusedBg()
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[2] = Math.min(1f, hsv[2] * 1.25f)
        val lighter = (base and 0xFF000000.toInt()) or (Color.HSVToColor(hsv) and 0x00FFFFFF)
        Color.colorToHSV(base, hsv)
        hsv[2] = Math.max(0f, hsv[2] * 0.65f)
        val darker = (base and 0xFF000000.toInt()) or (Color.HSVToColor(hsv) and 0x00FFFFFF)

        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighter, base, darker)
        )
    }

    private fun createLibraryRow(
        iconPaths: Array<String>?,
        name: String,
        modeLabel: String,
        clickListener: View.OnClickListener?
    ): LinearLayout {
        val d = activity.resources.displayMetrics.density
        val rowButton = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            isSoundEffectsEnabled = false
            background = createButtonBackground(ThemeManager.getListButtonNormalBg())
        }

        val padLeft = (12 * d).toInt()
        val padTopBottom = ((if (ThemeManager.isClassicTheme()) 6 else 8) * d).toInt()
        val padRight = (12 * d).toInt()
        rowButton.setPadding(padLeft, padTopBottom, padRight, padTopBottom)

        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, (3 * d).toInt(), 0, (3 * d).toInt())
        }
        rowButton.layoutParams = rowLp

        val iconSizePx = (20 * d).toInt()
        val chevronSizePx = (14 * d).toInt()

        val defaultIconColor = ThemeManager.getTextColorSecondary()
        val defaultTextColor = ThemeManager.getTextColorPrimary()
        val defaultSubtitleColor = ThemeManager.getTextColorSecondary()

        val ivIcon = if (iconPaths != null) {
            ImageView(activity).apply {
                setImageBitmap(TablerIcons.render(iconPaths, iconSizePx, defaultIconColor))
                layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                    rightMargin = (10 * d).toInt()
                }
            }.also { rowButton.addView(it) }
        } else null

        val tvTitle = TextView(activity).apply {
            text = name
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ThemeManager.getListTextSize() * d)
            setTextColor(defaultTextColor)
            typeface = ThemeManager.getCustomFontBold()
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowButton.addView(tvTitle)

        val tvMode = TextView(activity).apply {
            text = modeLabel
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (ThemeManager.getListTextSize() - 2f) * d)
            setTextColor(defaultSubtitleColor)
            typeface = ThemeManager.getCustomFont()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (8 * d).toInt()
            }
        }
        rowButton.addView(tvMode)

        val ivChevron = ImageView(activity).apply {
            setImageBitmap(TablerIcons.render(TablerIcons.CHEVRON_RIGHT, chevronSizePx, defaultSubtitleColor))
            layoutParams = LinearLayout.LayoutParams(chevronSizePx, chevronSizePx).apply {
                leftMargin = (6 * d).toInt()
            }
        }
        rowButton.addView(ivChevron)

        rowButton.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                rowButton.background = createFocusedButtonBackground()
                val focusedColor = ThemeManager.getListButtonFocusedTextColor()
                tvTitle.setTextColor(focusedColor)
                tvTitle.isSelected = true
                tvMode.setTextColor(focusedColor)
                if (ivIcon != null && iconPaths != null) {
                    ivIcon.setImageBitmap(TablerIcons.render(iconPaths, iconSizePx, focusedColor))
                }
                ivChevron.setImageBitmap(TablerIcons.render(TablerIcons.CHEVRON_RIGHT, chevronSizePx, focusedColor))
                val idx = containerSettingsItems.indexOfChild(rowButton)
                if (idx != -1 && currentDepth == 1) {
                    lastFocusIndex = idx
                }
            } else {
                rowButton.background = createButtonBackground(ThemeManager.getListButtonNormalBg())
                tvTitle.setTextColor(defaultTextColor)
                tvTitle.isSelected = false
                tvMode.setTextColor(defaultSubtitleColor)
                if (ivIcon != null && iconPaths != null) {
                    ivIcon.setImageBitmap(TablerIcons.render(iconPaths, iconSizePx, defaultIconColor))
                }
                ivChevron.setImageBitmap(TablerIcons.render(TablerIcons.CHEVRON_RIGHT, chevronSizePx, defaultSubtitleColor))
            }
        }

        if (clickListener != null) {
            rowButton.setOnClickListener(clickListener)
        }

        return rowButton
    }

    private fun createSelectRow(
        leftIconPaths: Array<String>?,
        title: String,
        subtitleText: String?,
        checkState: JellyfinSyncHierarchy.TriState,
        showChevron: Boolean,
        clickListener: View.OnClickListener?,
        longClickListener: View.OnLongClickListener?
    ): LinearLayout {
        val d = activity.resources.displayMetrics.density
        val rowButton = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            isSoundEffectsEnabled = false
            background = createButtonBackground(ThemeManager.getListButtonNormalBg())
        }

        val padLeft = (12 * d).toInt()
        val padTopBottom = ((if (ThemeManager.isClassicTheme()) 6 else 8) * d).toInt()
        val padRight = (12 * d).toInt()
        rowButton.setPadding(padLeft, padTopBottom, padRight, padTopBottom)

        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, (3 * d).toInt(), 0, (3 * d).toInt())
        }
        rowButton.layoutParams = rowLp

        val iconSizePx = (20 * d).toInt()
        val checkSizePx = (18 * d).toInt()
        val chevronSizePx = (14 * d).toInt()

        val defaultLeftIconColor = ThemeManager.getTextColorSecondary()
        val defaultTextColor = ThemeManager.getTextColorPrimary()
        val defaultSubtitleColor = ThemeManager.getTextColorSecondary()

        val ivLeftIcon = if (leftIconPaths != null) {
            ImageView(activity).apply {
                setImageBitmap(TablerIcons.render(leftIconPaths, iconSizePx, defaultLeftIconColor))
                layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                    rightMargin = (8 * d).toInt()
                }
            }.also { rowButton.addView(it) }
        } else null

        val tvTitle = TextView(activity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ThemeManager.getListTextSize() * d)
            setTextColor(defaultTextColor)
            typeface = ThemeManager.getCustomFontBold()
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowButton.addView(tvTitle)

        val tvSubtitle = if (!subtitleText.isNullOrEmpty()) {
            TextView(activity).apply {
                text = subtitleText
                setTextSize(TypedValue.COMPLEX_UNIT_PX, (ThemeManager.getListTextSize() - 2f) * d)
                setTextColor(defaultSubtitleColor)
                typeface = ThemeManager.getCustomFont()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = (8 * d).toInt()
                    rightMargin = (6 * d).toInt()
                }
            }.also { rowButton.addView(it) }
        } else null

        val checkPaths = when (checkState) {
            JellyfinSyncHierarchy.TriState.CHECKED -> TablerIcons.CHECKBOX
            JellyfinSyncHierarchy.TriState.INDETERMINATE -> TablerIcons.SQUARE_MINUS
            JellyfinSyncHierarchy.TriState.UNCHECKED -> TablerIcons.SQUARE
        }

        val checkColor = if (checkState == JellyfinSyncHierarchy.TriState.UNCHECKED) {
            defaultSubtitleColor
        } else {
            defaultTextColor
        }

        val ivCheck = ImageView(activity).apply {
            setImageBitmap(TablerIcons.render(checkPaths, checkSizePx, checkColor))
            layoutParams = LinearLayout.LayoutParams(checkSizePx, checkSizePx).apply {
                leftMargin = (4 * d).toInt()
            }
        }
        rowButton.addView(ivCheck)

        val ivChevron = if (showChevron) {
            ImageView(activity).apply {
                setImageBitmap(TablerIcons.render(TablerIcons.CHEVRON_RIGHT, chevronSizePx, defaultSubtitleColor))
                layoutParams = LinearLayout.LayoutParams(chevronSizePx, chevronSizePx).apply {
                    leftMargin = (6 * d).toInt()
                }
            }.also { rowButton.addView(it) }
        } else null

        rowButton.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                rowButton.background = createFocusedButtonBackground()
                val focusedColor = ThemeManager.getListButtonFocusedTextColor()
                tvTitle.setTextColor(focusedColor)
                tvTitle.isSelected = true
                tvSubtitle?.setTextColor(focusedColor)
                if (ivLeftIcon != null && leftIconPaths != null) {
                    ivLeftIcon.setImageBitmap(TablerIcons.render(leftIconPaths, iconSizePx, focusedColor))
                }
                ivCheck.setImageBitmap(TablerIcons.render(checkPaths, checkSizePx, focusedColor))
                ivChevron?.setImageBitmap(TablerIcons.render(TablerIcons.CHEVRON_RIGHT, chevronSizePx, focusedColor))
                val idx = containerSettingsItems.indexOfChild(rowButton)
                if (idx != -1) {
                    when (currentDepth) {
                        3 -> lastBrowseDepth3FocusIndex = idx
                        4 -> lastBrowseDepth4FocusIndex = idx
                        5 -> lastBrowseDepth5FocusIndex = idx
                    }
                }
            } else {
                rowButton.background = createButtonBackground(ThemeManager.getListButtonNormalBg())
                tvTitle.setTextColor(defaultTextColor)
                tvTitle.isSelected = false
                tvSubtitle?.setTextColor(defaultSubtitleColor)
                if (ivLeftIcon != null && leftIconPaths != null) {
                    ivLeftIcon.setImageBitmap(TablerIcons.render(leftIconPaths, iconSizePx, defaultLeftIconColor))
                }
                ivCheck.setImageBitmap(TablerIcons.render(checkPaths, checkSizePx, checkColor))
                ivChevron?.setImageBitmap(TablerIcons.render(TablerIcons.CHEVRON_RIGHT, chevronSizePx, defaultSubtitleColor))
            }
        }

        if (clickListener != null) {
            rowButton.setOnClickListener { v ->
                if (isLongPressActive || System.currentTimeMillis() - lastLongClickTime < 600) {
                    return@setOnClickListener
                }
                clickListener.onClick(v)
            }
        }

        if (longClickListener != null) {
            rowButton.setOnLongClickListener { v ->
                lastLongClickTime = System.currentTimeMillis()
                isLongPressActive = true
                val main = activity as? com.themoon.y1.MainActivity
                main?.isLongPressConsumed = true
                longClickListener.onLongClick(v)
            }
        }

        return rowButton
    }

    private fun createToggleAllRow(
        overallState: JellyfinSyncHierarchy.TriState,
        selectedCount: Int,
        totalCount: Int,
        clickListener: View.OnClickListener?
    ): View {
        val label = if (overallState == JellyfinSyncHierarchy.TriState.CHECKED) {
            localize("Deselect All")
        } else {
            localize("Select All")
        }
        val countStr = "$selectedCount/$totalCount"

        val checkPaths = when (overallState) {
            JellyfinSyncHierarchy.TriState.CHECKED -> TablerIcons.CHECKBOX
            JellyfinSyncHierarchy.TriState.INDETERMINATE -> TablerIcons.SQUARE_MINUS
            JellyfinSyncHierarchy.TriState.UNCHECKED -> TablerIcons.SQUARE
        }

        val d = activity.resources.displayMetrics.density
        val rowButton = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            isSoundEffectsEnabled = false
            background = createButtonBackground(ThemeManager.getListButtonNormalBg())
        }

        val padLeft = (12 * d).toInt()
        val padTopBottom = ((if (ThemeManager.isClassicTheme()) 6 else 8) * d).toInt()
        val padRight = (12 * d).toInt()
        rowButton.setPadding(padLeft, padTopBottom, padRight, padTopBottom)

        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, (3 * d).toInt(), 0, (3 * d).toInt())
        }
        rowButton.layoutParams = rowLp

        val iconSizePx = (20 * d).toInt()
        val defaultTextColor = ThemeManager.getTextColorPrimary()
        val defaultSubtitleColor = ThemeManager.getTextColorSecondary()

        val ivCheck = ImageView(activity).apply {
            setImageBitmap(TablerIcons.render(checkPaths, iconSizePx, defaultTextColor))
            layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                rightMargin = (10 * d).toInt()
            }
        }
        rowButton.addView(ivCheck)

        val tvLabel = TextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ThemeManager.getListTextSize() * d)
            setTextColor(defaultTextColor)
            typeface = ThemeManager.getCustomFontBold()
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowButton.addView(tvLabel)

        val tvCount = TextView(activity).apply {
            text = countStr
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (ThemeManager.getListTextSize() - 2f) * d)
            setTextColor(defaultSubtitleColor)
            typeface = ThemeManager.getCustomFont()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rowButton.addView(tvCount)

        rowButton.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                rowButton.background = createFocusedButtonBackground()
                val focusedColor = ThemeManager.getListButtonFocusedTextColor()
                tvLabel.setTextColor(focusedColor)
                tvCount.setTextColor(focusedColor)
                ivCheck.setImageBitmap(TablerIcons.render(checkPaths, iconSizePx, focusedColor))
                val idx = containerSettingsItems.indexOfChild(rowButton)
                if (idx != -1) {
                    when (currentDepth) {
                        3 -> lastBrowseDepth3FocusIndex = idx
                        4 -> lastBrowseDepth4FocusIndex = idx
                        5 -> lastBrowseDepth5FocusIndex = idx
                    }
                }
            } else {
                rowButton.background = createButtonBackground(ThemeManager.getListButtonNormalBg())
                tvLabel.setTextColor(defaultTextColor)
                tvCount.setTextColor(defaultSubtitleColor)
                ivCheck.setImageBitmap(TablerIcons.render(checkPaths, iconSizePx, defaultTextColor))
            }
        }

        if (clickListener != null) {
            rowButton.setOnClickListener { v ->
                if (isLongPressActive || System.currentTimeMillis() - lastLongClickTime < 600) {
                    return@setOnClickListener
                }
                clickListener.onClick(v)
            }
            rowButton.setOnLongClickListener { v ->
                lastLongClickTime = System.currentTimeMillis()
                isLongPressActive = true
                val main = activity as? com.themoon.y1.MainActivity
                main?.isLongPressConsumed = true
                clickListener.onClick(v)
                true
            }
        }

        return rowButton
    }

    fun testConnection() {
        if (!isNetworkConnected()) {
            onRequestWifi(PENDING_ACTION_TEST)
            return
        }

        val d = activity.resources.displayMetrics.density
        val loaderDialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val loaderLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                setColor(ThemeManager.getOverlayBackgroundColor() or 0xEE000000.toInt())
                cornerRadius = 15 * d
                setStroke((1 * d).toInt(), 0x33FFFFFF)
            }
            background = bg
            setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        }

        val progressBar = ProgressBar(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (14 * d).toInt()
            }
        }
        loaderLayout.addView(progressBar)

        val tvLoaderMsg = TextView(activity).apply {
            text = localize("Connecting...")
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 15f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
        }
        loaderLayout.addView(tvLoaderMsg)

        loaderDialog.setContentView(loaderLayout)
        loaderDialog.setCancelable(true)
        loaderDialog.setCanceledOnTouchOutside(false)
        loaderDialog.window?.setLayout((270 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        loaderDialog.show()

        provider.testConnection(object : MediaSyncProvider.ConnectionCallback {
            override fun onSuccess(serverName: String, version: String) {
                activity.runOnUiThread {
                    if (loaderDialog.isShowing) loaderDialog.dismiss()
                    Toast.makeText(
                        activity,
                        "${localize("Connected")}: $serverName",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (currentDepth == 1) {
                        buildSettingsUI()
                    }
                }
            }

            override fun onError(errorMessage: String) {
                activity.runOnUiThread {
                    if (loaderDialog.isShowing) loaderDialog.dismiss()
                    Toast.makeText(
                        activity,
                        "${localize("Cannot reach server. Check URL and network.")}\n$errorMessage",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
    }

    fun fetchLibraries() {
        if (!provider.isConfigured()) {
            Toast.makeText(activity, localize("Connect to discover libraries."), Toast.LENGTH_SHORT).show()
            return
        }

        if (!isNetworkConnected()) {
            onRequestWifi(PENDING_ACTION_FETCH)
            return
        }

        val d = activity.resources.displayMetrics.density
        val loaderDialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val loaderLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                setColor(ThemeManager.getOverlayBackgroundColor() or 0xEE000000.toInt())
                cornerRadius = 15 * d
                setStroke((1 * d).toInt(), 0x33FFFFFF)
            }
            background = bg
            setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        }

        val progressBar = ProgressBar(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (14 * d).toInt()
            }
        }
        loaderLayout.addView(progressBar)

        val tvLoaderMsg = TextView(activity).apply {
            text = localize("Fetching libraries...")
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 15f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
        }
        loaderLayout.addView(tvLoaderMsg)

        loaderDialog.setContentView(loaderLayout)
        loaderDialog.setCancelable(true)
        loaderDialog.setCanceledOnTouchOutside(false)
        loaderDialog.window?.setLayout((270 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        loaderDialog.show()

        Thread {
            try {
                provider.fetchLibraries(MediaSyncProvider.CancellationToken())
                activity.runOnUiThread {
                    if (loaderDialog.isShowing) loaderDialog.dismiss()
                    Toast.makeText(activity, localize("Libraries updated"), Toast.LENGTH_SHORT).show()
                    if (currentDepth == 1) {
                        buildSettingsUI()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (loaderDialog.isShowing) loaderDialog.dismiss()
                    Toast.makeText(
                        activity,
                        "${localize("Failed to fetch libraries")}: ${e.message ?: ""}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    fun startQuickConnect() {
        val serverUrl = provider.serverUrl
        if (serverUrl.isBlank()) {
            Toast.makeText(activity, localize("Server URL is empty"), Toast.LENGTH_SHORT).show()
            return
        }

        if (!isNetworkConnected()) {
            onRequestWifi(PENDING_ACTION_QUICKCONNECT)
            return
        }

        val d = activity.resources.displayMetrics.density
        val qcDialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                setColor(ThemeManager.getOverlayBackgroundColor() or 0xEE000000.toInt())
                cornerRadius = 15 * d
                setStroke((1 * d).toInt(), 0x33FFFFFF)
            }
            background = bg
            setPadding((22 * d).toInt(), (20 * d).toInt(), (22 * d).toInt(), (20 * d).toInt())
        }

        val tvTitle = TextView(activity).apply {
            text = localize("QuickConnect")
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 17f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
        }
        rootLayout.addView(tvTitle)

        val tvInstruction = TextView(activity).apply {
            text = localize("Enter this code on your device:")
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * d).toInt()
            }
        }
        rootLayout.addView(tvInstruction)

        val progressBar = ProgressBar(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (14 * d).toInt()
                bottomMargin = (14 * d).toInt()
            }
        }
        rootLayout.addView(progressBar)

        val tvCode = TextView(activity).apply {
            text = "------"
            setTextColor(0xFF00AAFF.toInt())
            textSize = 32f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * d).toInt()
                bottomMargin = (10 * d).toInt()
            }
        }
        rootLayout.addView(tvCode)

        val tvSubInstruction = TextView(activity).apply {
            text = localize("Go to Jellyfin Settings > Quick Connect")
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 11f
            gravity = Gravity.CENTER
        }
        rootLayout.addView(tvSubInstruction)

        val tvStatus = TextView(activity).apply {
            text = localize("Connecting...")
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * d).toInt()
                bottomMargin = (14 * d).toInt()
            }
        }
        rootLayout.addView(tvStatus)

        val btnCancel = createDialogButton(localize("Cancel")).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (36 * d).toInt()
            )
        }
        rootLayout.addView(btnCancel)

        qcDialog.setContentView(rootLayout)
        qcDialog.setCancelable(true)
        qcDialog.setCanceledOnTouchOutside(false)
        qcDialog.window?.setLayout((280 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

        val isCancelled = booleanArrayOf(false)
        btnCancel.setOnClickListener {
            isCancelled[0] = true
            clickFeedback()
            qcDialog.dismiss()
        }
        qcDialog.setOnCancelListener {
            isCancelled[0] = true
        }

        qcDialog.show()
        btnCancel.requestFocus()

        Thread {
            try {
                val client = provider.createClient()
                if (!client.isQuickConnectEnabled()) {
                    activity.runOnUiThread {
                        progressBar.visibility = View.GONE
                        tvStatus.text = localize("Server does not support QuickConnect")
                        Toast.makeText(activity, localize("Server does not support QuickConnect"), Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                val initSession = client.initiateQuickConnect()
                if (isCancelled[0]) return@Thread

                activity.runOnUiThread {
                    progressBar.visibility = View.GONE
                    tvCode.visibility = View.VISIBLE
                    tvCode.text = initSession.code
                    tvStatus.text = localize("Waiting for authorization...")
                }

                val secret = initSession.secret
                var authenticated = false
                for (i in 0 until 300) {
                    if (isCancelled[0]) return@Thread
                    Thread.sleep(2000)
                    if (isCancelled[0]) return@Thread

                    try {
                        val pollSession = client.checkQuickConnect(secret)
                        if (pollSession.authenticated) {
                            authenticated = true
                            break
                        }
                    } catch (pollEx: Exception) {
                        break
                    }
                }

                if (isCancelled[0]) return@Thread

                if (!authenticated) {
                    activity.runOnUiThread {
                        tvStatus.text = localize("QuickConnect Expired / Failed")
                    }
                    return@Thread
                }

                val auth = client.authenticateWithQuickConnect(secret)
                provider.accessToken = auth.accessToken
                provider.userId = auth.userId
                provider.serverName = auth.serverName
                if (!auth.serverName.isNullOrEmpty()) {
                    provider.username = auth.serverName
                }

                if (isCancelled[0]) return@Thread

                activity.runOnUiThread {
                    try {
                        qcDialog.dismiss()
                    } catch (ignored: Exception) {}
                    Toast.makeText(
                        activity,
                        "${localize("QuickConnect Paired!")} ${localize("Paired as ")}${auth.serverName}",
                        Toast.LENGTH_LONG
                    ).show()
                    if (currentDepth == 1) {
                        buildSettingsUI()
                    }
                }
            } catch (e: Exception) {
                if (isCancelled[0]) return@Thread
                activity.runOnUiThread {
                    progressBar.visibility = View.GONE
                    val msg = e.message ?: localize("Connection Error")
                    tvStatus.text = msg
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    fun launchSync() {
        if (isCustomScanning()) {
            Toast.makeText(activity, localize("Scanning Media..."), Toast.LENGTH_SHORT).show()
            return
        }

        if (syncManager.isSyncing) {
            Toast.makeText(activity, localize("Syncing..."), Toast.LENGTH_SHORT).show()
            return
        }

        if (!isNetworkConnected()) {
            onRequestWifi(PENDING_ACTION_SYNC)
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
                            if (currentDepth == 1) buildSettingsUI()
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

    private fun restoreFocus(targetDepth: Int, preferredIndex: Int) {
        containerSettingsItems.post {
            if (currentDepth != targetDepth) return@post
            if (preferredIndex in 0 until containerSettingsItems.childCount) {
                val target = containerSettingsItems.getChildAt(preferredIndex)
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
