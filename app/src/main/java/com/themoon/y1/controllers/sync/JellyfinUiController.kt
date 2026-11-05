package com.themoon.y1.controllers.sync

import android.app.Activity
import android.os.StatFs
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.JellyfinLevel
import com.themoon.y1.navigation.KeyboardTarget
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.sync.MediaSyncProvider
import com.themoon.y1.sync.SyncManager
import com.themoon.y1.sync.jellyfin.JellyfinClient
import com.themoon.y1.sync.jellyfin.JellyfinMediaItem
import com.themoon.y1.sync.jellyfin.JellyfinSyncHierarchy
import com.themoon.y1.sync.jellyfin.JellyfinSyncHierarchy.TriState
import com.themoon.y1.sync.jellyfin.JellyfinSyncProvider
import com.themoon.y1.views.Notice
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Jellyfin sync: the settings (server, login, libraries, sync options) and the lists to select the items of a
 * library, on the list model. Each level is a screen state ([JellyfinLevel]) on the back stack, so Back and the
 * row that opened the deeper list work as on all other lists. Also QuickConnect pairing and the sync progress.
 */
class JellyfinUiController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val clickFeedback: () -> Unit,
    private val onOpenKeyboard: (target: KeyboardTarget) -> Unit,
    private val onRequestWifi: (action: Int) -> Unit,
    private val isNetworkConnected: () -> Boolean,
    private val onSyncFinished: () -> Unit,
    private val onScanVideos: () -> Unit,
    private val isFilePlaying: (File?) -> Boolean,
    private val customLibrary: MutableList<SongItem>,
    private val trackNumberMap: MutableMap<String, Int>,
    private val localize: (String) -> String,
    private val isCustomScanning: () -> Boolean = { false }
) : ScreenController {

    companion object {
        const val PENDING_ACTION_NONE = 0
        const val PENDING_ACTION_TEST = 1
        const val PENDING_ACTION_FETCH = 2
        const val PENDING_ACTION_SYNC = 3
        const val PENDING_ACTION_QUICKCONNECT = 4
    }

    private val screen = ListScreen(rootView.findViewById(R.id.container_settings_items), null, clickFeedback)
    private val provider: JellyfinSyncProvider get() = JellyfinSyncProvider.getInstance(activity)
    private val syncManager: SyncManager get() = SyncManager.getInstance(activity)

    /** The library of the open library levels, and its items (they stay while its levels are on the back stack). */
    var activeLibrary: MediaSyncProvider.LibraryInfo? = null
        private set
    var currentHierarchy: JellyfinSyncHierarchy? = null
        private set

    /** The library whose items load now, and the view that shows the progress. */
    private var loadingLibraryId: String? = null
    private var loadingView: LoadingView? = null

    override fun onEnter(state: ScreenState) {
        screen.show(spec((state as? ScreenState.Jellyfin)?.level ?: JellyfinLevel.Root))
    }

    override fun onExit() {
        screen.clear()
    }

    override fun onThemeChanged() {
        NavigationManager.getInstance().currentState?.let { onEnter(it) }
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean = screen.wheel(delta)

    override fun title(): String? = screen.title

    /** The Jellyfin level that shows now, or null when another screen shows. */
    private val shownLevel: JellyfinLevel?
        get() = (NavigationManager.getInstance().currentState as? ScreenState.Jellyfin)?.level

    /** Opens a deeper level: a new back-stack entry, so Back comes back to the row that opened it. */
    private fun open(level: JellyfinLevel) {
        (activity as? com.themoon.y1.MainActivity)?.changeScreen(ScreenState.Jellyfin(level))
    }

    /** Makes the rows of the level that shows again (its content changed); the selected row stays selected. */
    private fun refresh() {
        shownLevel?.let { screen.update(spec(it)) }
    }

    /** Makes the settings again if they show (after a connection test, a library fetch, a pairing or a sync). */
    private fun refreshRoot() {
        if (shownLevel == JellyfinLevel.Root) refresh()
    }

    private fun library(id: String): MediaSyncProvider.LibraryInfo? =
        activeLibrary?.takeIf { it.id == id } ?: provider.getCachedLibraries().firstOrNull { it.id == id }

    /** The items of [lib] when they are loaded. */
    private fun hierarchyOf(lib: MediaSyncProvider.LibraryInfo): JellyfinSyncHierarchy? =
        currentHierarchy?.takeIf { activeLibrary?.id == lib.id }

    /** The list of [level]. A level whose library is gone shows the settings; one whose items are gone, the library list. */
    private fun spec(level: JellyfinLevel): ListSpec {
        if (level is JellyfinLevel.Root) return rootSpec()
        val lib = when (level) {
            is JellyfinLevel.Library -> library(level.libraryId)
            is JellyfinLevel.Browse -> library(level.libraryId)
            is JellyfinLevel.Group -> library(level.libraryId)
            is JellyfinLevel.Items -> library(level.libraryId)
            JellyfinLevel.Root -> null
        } ?: return rootSpec()
        if (level is JellyfinLevel.Library) return librarySpec(lib)
        val h = hierarchyOf(lib) ?: return loadingSpec(lib)
        val type = JellyfinLabels.kind(lib.collectionType)
        return when (level) {
            is JellyfinLevel.Group -> when (type) {
                JellyfinLabels.Kind.TV -> h.seriesList.getOrNull(level.group)?.let { s ->
                    groupSpec(lib, s.name, s.allEpisodeIds, s.seasons.map { Node(it.name, it.allEpisodeIds) }, level.group)
                }
                JellyfinLabels.Kind.MUSIC -> h.artistsList.getOrNull(level.group)?.let { a ->
                    groupSpec(lib, a.name, a.allSongIds, a.albums.map { Node(it.name, it.allSongIds) }, level.group)
                }
                JellyfinLabels.Kind.BOOKS -> h.authorsList.getOrNull(level.group)?.let { a ->
                    groupSpec(lib, a.name, a.allChapterIds, a.books.map { Node(it.name, it.allChapterIds) }, level.group)
                }
                JellyfinLabels.Kind.MOVIES -> null
            }
            is JellyfinLevel.Items -> when (type) {
                JellyfinLabels.Kind.TV -> h.seriesList.getOrNull(level.group)?.let { s ->
                    s.seasons.getOrNull(level.item)?.let { itemsSpec(lib, "${s.name} - ${it.name}", it.allEpisodeIds, it.episodes, JellyfinLabels::episode) }
                }
                JellyfinLabels.Kind.MUSIC -> h.artistsList.getOrNull(level.group)?.let { a ->
                    a.albums.getOrNull(level.item)?.let { itemsSpec(lib, "${a.name} - ${it.name}", it.allSongIds, it.songs, JellyfinLabels::track) }
                }
                JellyfinLabels.Kind.BOOKS -> h.authorsList.getOrNull(level.group)?.let { a ->
                    a.books.getOrNull(level.item)?.let { itemsSpec(lib, "${a.name} - ${it.name}", it.allChapterIds, it.chapters, JellyfinLabels::track) }
                }
                JellyfinLabels.Kind.MOVIES -> null
            }
            else -> null
        } ?: browseSpec(lib, h)
    }

    // ---- Rows ----

    /** A header between the row groups (a HEADER look of the list model later). */
    private fun header(title: String) = RowSpec(title, RowLook.HEADER, key = "\u0000header $title")

    /** A grey line that the wheel skips. */
    private fun info(text: String, key: String = "\u0000info $text") = RowSpec(text, RowLook.INFO, key = key)

    private fun value(label: String, value: String, onClick: (() -> Unit)? = null) =
        RowSpec(label, RowLook.VALUE, value = value, onClick = onClick)

    private fun action(label: String, run: () -> Unit) = RowSpec(label, RowLook.MENU, onClick = run)

    // ---- Settings (root) ----

    private fun rootSpec(): ListSpec {
        val rows = ArrayList<RowSpec>()
        rows += header("━ " + localize("Server") + " ━")
        val rawUrl = provider.serverUrl
        rows += value(localize("Server URL"), rawUrl.ifEmpty { localize("Not Configured") }) {
            onOpenKeyboard(KeyboardTarget.JellyfinServerUrl)
        }
        val authMode = provider.authMode
        rows += value(localize("Auth Mode"), localize(JellyfinLabels.authModeLabel(authMode))) {
            provider.authMode = JellyfinLabels.nextAuthMode(provider.authMode)
            refresh()
        }
        when {
            "quickconnect".equals(authMode, ignoreCase = true) -> {
                if (provider.accessToken.isNotEmpty()) {
                    rows += value(localize("Username"), if (provider.username.isEmpty()) localize("Not Configured") else "@" + provider.username)
                }
                rows += action(localize("Connect via QuickConnect")) { startQuickConnect() }
            }
            "token".equals(authMode, ignoreCase = true) -> {
                rows += value(localize("API Token"), if (provider.accessToken.isEmpty()) localize("Not Configured") else "••••••••") {
                    onOpenKeyboard(KeyboardTarget.JellyfinToken)
                }
            }
            else -> {
                rows += value(localize("Username"), provider.username.ifEmpty { localize("Not Configured") }) {
                    onOpenKeyboard(KeyboardTarget.JellyfinUsername)
                }
                rows += value(localize("Password"), if (provider.password.isEmpty()) localize("Not Configured") else "••••••••") {
                    onOpenKeyboard(KeyboardTarget.JellyfinPassword(provider.username))
                }
            }
        }
        rows += action(localize("Test Connection")) { testConnection() }

        rows += header("━ " + localize("Libraries") + " ━")
        val ledger = syncManager.ledger
        val cachedLibs = provider.getCachedLibraries()
        if (cachedLibs.isEmpty()) {
            rows += info(localize("Connect to discover libraries."))
            if (provider.isConfigured() && isNetworkConnected()) {
                Thread {
                    try {
                        val fetched = provider.fetchLibraries(MediaSyncProvider.CancellationToken())
                        if (!fetched.isNullOrEmpty()) activity.runOnUiThread { refreshRoot() }
                    } catch (ignored: Exception) {}
                }.start()
            }
        } else {
            for (lib in cachedLibs) {
                val mode = ledger.getLibrarySyncMode(lib.id, JellyfinLabels.defaultMode(lib.collectionType))
                rows += RowSpec(lib.name, RowLook.VALUE, value = localize(JellyfinLabels.modeLabel(mode)) + " 〉", key = "\u0000library ${lib.id}",
                    onClick = { open(JellyfinLevel.Library(lib.id)) })
            }
        }
        rows += action(localize("Fetch Libraries")) { fetchLibraries() }

        rows += header("━ " + localize("Sync") + " ━")
        rows += value(localize("Remove Deleted"), onOff(provider.isRemoveDeletedEnabled, localize)) {
            provider.isRemoveDeletedEnabled = !provider.isRemoveDeletedEnabled
            refresh()
        }
        rows += value(localize("Transcode Video"), onOff(provider.isTranscodeVideoEnabled, localize)) {
            provider.isTranscodeVideoEnabled = !provider.isTranscodeVideoEnabled
            refresh()
        }
        rows += value(localize("Download Covers"), onOff(provider.isDownloadCoversEnabled, localize)) {
            provider.isDownloadCoversEnabled = !provider.isDownloadCoversEnabled
            refresh()
        }
        val lastSync = ledger.getLastSyncTime()
        rows += value(localize("Last Synced"),
            if (lastSync > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(lastSync)) else localize("Never"))
        rows += action(localize("Sync Now")) { launchSync() }
        rows += value(localize("Log Out / Reset"), "〉 ") {
            val server = listOf(provider.username.takeIf { it.isNotEmpty() }?.let { "@$it" }, provider.serverUrl.takeIf { it.isNotEmpty() })
            ActionScreens.open(ActionScreens.Spec(
                localize("Jellyfin Sync"),
                server.filterNotNull(),
                listOf(ActionScreens.Action(localize("Log Out / Reset")) {
                    provider.clearConfiguration()
                    refreshRoot()
                })
            ))
        }
        return ListSpec(localize("Jellyfin"), rows)
    }

    // ---- A library: its sync mode ----

    private fun librarySpec(library: MediaSyncProvider.LibraryInfo): ListSpec {
        if (activeLibrary?.id != library.id) currentHierarchy = null
        activeLibrary = library
        val ledger = syncManager.ledger
        val mode = ledger.getLibrarySyncMode(library.id, JellyfinLabels.defaultMode(library.collectionType))
        val rows = ArrayList<RowSpec>()
        for (m in JellyfinLabels.MODES) {
            rows += RowSpec(localize(JellyfinLabels.modeLabel(m)), RowLook.CHOICE, checked = m.equals(mode, ignoreCase = true), onClick = {
                ledger.setLibrarySyncMode(library.id, m)
                ledger.save()
                refresh()
            })
        }
        if ("manual".equals(mode, ignoreCase = true)) {
            rows += header("━━━━━━━━━━━━━━")
            rows += value(localize("Browse & Select"), "〉 ") { open(JellyfinLevel.Browse(library.id)) }
            rows += value(localize("Selected"), ledger.getLibrarySelections(library.id).size.toString())
        }
        return ListSpec(library.name, rows)
    }

    // ---- The items of a library ----

    /** A series, artist, author, season, album or book: a name and the ids of the items in it. */
    private class Node(val name: String, val ids: Collection<String>)

    /** The items of [lib] load from the server; the list shows when they are in. */
    private fun loadingSpec(lib: MediaSyncProvider.LibraryInfo): ListSpec {
        activeLibrary = lib
        val view = LoadingView(localize("Counting files...") + "\n" + localize("Please wait."))
        loadingView = view
        if (loadingLibraryId != lib.id) loadItems(lib)
        return ListSpec(lib.name, listOf(RowSpec(lib.name, RowLook.CUSTOM, view = view.root, key = "\u0000loading")))
    }

    private fun loadItems(library: MediaSyncProvider.LibraryInfo) {
        loadingLibraryId = library.id
        fun showsLoading() = shownLevel == JellyfinLevel.Browse(library.id)
        Thread {
            try {
                val validToken = provider.ensureValidAccessToken()
                val client = provider.createClient()
                val progressListener = object : JellyfinClient.ItemsFetchProgressListener {
                    override fun onProgress(loaded: Int, total: Int) {
                        activity.runOnUiThread {
                            if (showsLoading()) loadingView?.progress(localize("Counting files..."), loaded, total)
                        }
                    }
                }
                val items = client.fetchLibraryItemsAll(validToken, provider.userId, library.id, library.collectionType, progressListener, null)
                activity.runOnUiThread {
                    loadingLibraryId = null
                    // Keep the items also when the user went away: the next visit shows them at once
                    if (activeLibrary?.id != library.id) return@runOnUiThread
                    currentHierarchy = when (JellyfinLabels.kind(library.collectionType)) {
                        JellyfinLabels.Kind.TV -> JellyfinSyncHierarchy.buildTvHierarchy(items)
                        JellyfinLabels.Kind.MUSIC -> JellyfinSyncHierarchy.buildMusicHierarchy(items)
                        JellyfinLabels.Kind.BOOKS -> JellyfinSyncHierarchy.buildBooksHierarchy(items)
                        JellyfinLabels.Kind.MOVIES -> JellyfinSyncHierarchy.buildMoviesHierarchy(items)
                    }
                    if (showsLoading()) screen.show(spec(JellyfinLevel.Browse(library.id)))
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    loadingLibraryId = null
                    if (showsLoading()) loadingView?.error(localize("Cannot reach server. Check URL and network."))
                }
            }
        }.start()
    }

    /** The first list of a library: its series, artists or authors (they open), or its movies (ticks). */
    private fun browseSpec(lib: MediaSyncProvider.LibraryInfo, h: JellyfinSyncHierarchy): ListSpec {
        val selections = syncManager.ledger.getLibrarySelections(lib.id)
        val rows = ArrayList<RowSpec>()
        when (JellyfinLabels.kind(lib.collectionType)) {
            JellyfinLabels.Kind.TV ->
                h.seriesList.forEachIndexed { i, s -> rows += groupRow(lib, Node(s.name, s.allEpisodeIds), selections, JellyfinLevel.Group(lib.id, i)) }
            JellyfinLabels.Kind.MUSIC ->
                h.artistsList.forEachIndexed { i, a -> rows += groupRow(lib, Node(a.name, a.allSongIds), selections, JellyfinLevel.Group(lib.id, i)) }
            JellyfinLabels.Kind.BOOKS ->
                h.authorsList.forEachIndexed { i, a -> rows += groupRow(lib, Node(a.name, a.allChapterIds), selections, JellyfinLevel.Group(lib.id, i)) }
            JellyfinLabels.Kind.MOVIES ->
                for (m in h.moviesList) rows += itemRow(lib, JellyfinLabels.movie(m.name, m.year), m.id, h.allItemIds, selections)
        }
        return selectSpec(lib, lib.name, h.allItemIds, sizeOf(h.allItemIds, h, selections), selections, rows)
    }

    /** A series, artist or author: its seasons, albums or books (they open). */
    private fun groupSpec(lib: MediaSyncProvider.LibraryInfo, title: String, ids: Collection<String>, children: List<Node>, group: Int): ListSpec {
        val selections = syncManager.ledger.getLibrarySelections(lib.id)
        val rows = children.mapIndexed { i, child -> groupRow(lib, child, selections, JellyfinLevel.Items(lib.id, group, i)) }
        return selectSpec(lib, title, ids, sizeOf(ids, hierarchyOf(lib), selections), selections, rows)
    }

    /** A season, album or book: its episodes, songs or chapters (ticks). */
    private fun itemsSpec(
        lib: MediaSyncProvider.LibraryInfo,
        title: String,
        ids: Collection<String>,
        items: List<JellyfinMediaItem>,
        label: (Int, String) -> String
    ): ListSpec {
        val selections = syncManager.ledger.getLibrarySelections(lib.id)
        val rows = items.map { itemRow(lib, label(it.indexNumber, it.name), it.id, ids, selections) }
        return selectSpec(lib, title, ids, sizeOf(ids, hierarchyOf(lib), selections), selections, rows)
    }

    /** The rows above the items of a selection list: the storage line and Select All / Deselect All. */
    private fun selectSpec(
        lib: MediaSyncProvider.LibraryInfo,
        title: String,
        ids: Collection<String>,
        selectedBytes: Long,
        selections: Set<String>,
        items: List<RowSpec>
    ): ListSpec {
        val selected = JellyfinSyncHierarchy.countSelected(ids, selections)
        val all = JellyfinSyncHierarchy.computeTriState(ids, selections) == TriState.CHECKED
        val rows = ArrayList<RowSpec>()
        rows += info(JellyfinLabels.storageLine(localize("Selected"), localize("Free"), selected, ids.size, selectedBytes, freeBytes()), key = "\u0000storage")
        rows += RowSpec(localize(if (all) "Deselect All" else "Select All"), RowLook.VALUE, value = "$selected/${ids.size}", key = "\u0000all",
            onClick = { toggle(lib, ids) }, onHold = { toggle(lib, ids) })
        rows += items
        return ListSpec(title, rows)
    }

    /** A row that opens a deeper list: the selected count; a hold selects all its items, or none. */
    private fun groupRow(lib: MediaSyncProvider.LibraryInfo, node: Node, selections: Set<String>, level: JellyfinLevel): RowSpec {
        val count = "${JellyfinSyncHierarchy.countSelected(node.ids, selections)}/${node.ids.size}"
        return RowSpec(node.name, RowLook.VALUE, value = "$count 〉", key = level.toString(),
            onClick = { open(level) }, onHold = { toggle(lib, node.ids) })
    }

    /** An item: the tick shows when it is selected; a hold selects all items of the list, or none. */
    private fun itemRow(lib: MediaSyncProvider.LibraryInfo, label: String, id: String, all: Collection<String>, selections: Set<String>) =
        RowSpec(label, RowLook.CHOICE, checked = id in selections, key = id,
            onClick = {
                val ledger = syncManager.ledger
                ledger.toggleLibrarySelection(lib.id, id)
                ledger.save()
                refresh()
            },
            onHold = { toggle(lib, all) })

    /** Selects all of [ids], or none when all are selected. */
    private fun toggle(lib: MediaSyncProvider.LibraryInfo, ids: Collection<String>) {
        val ledger = syncManager.ledger
        if (JellyfinSyncHierarchy.computeTriState(ids, ledger.getLibrarySelections(lib.id)) == TriState.CHECKED) {
            ledger.removeLibrarySelections(lib.id, ids)
        } else {
            ledger.addLibrarySelections(lib.id, ids)
        }
        ledger.save()
        refresh()
    }

    /** The size of the selected items among [ids]. */
    private fun sizeOf(ids: Collection<String>, h: JellyfinSyncHierarchy?, selections: Set<String>): Long {
        if (h == null) return 0L
        val wanted = ids.filter { it in selections }.toHashSet()
        if (wanted.isEmpty()) return 0L
        var total = 0L
        for (m in h.moviesList) if (m.id in wanted) total += m.fileSize
        for (s in h.seriesList) for (sn in s.seasons) for (ep in sn.episodes) if (ep.id in wanted) total += ep.fileSize
        for (a in h.artistsList) for (al in a.albums) for (song in al.songs) if (song.id in wanted) total += song.fileSize
        for (a in h.authorsList) for (b in a.books) for (ch in b.chapters) if (ch.id in wanted) total += ch.fileSize
        return total
    }

    private fun freeBytes(): Long = try {
        val stat = StatFs(SyncManager.getSyncRoot().absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (ignored: Exception) {
        0L
    }

    /** The text and the progress bar while the items of a library load (a LOADING look of the list model later). */
    private inner class LoadingView(text: String) {
        private val d = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((20 * d).toInt(), (24 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
        }
        private val label = TextView(activity).apply {
            this.text = text
            setTextColor(ThemeManager.getTextColorSecondary())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (ThemeManager.getListTextSize() - 1f) * d)
            typeface = ThemeManager.getCustomFont()
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (14 * d).toInt())
        }
        private val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (8 * d).toInt())
        }

        init {
            root.addView(label)
            root.addView(bar)
        }

        fun progress(title: String, loaded: Int, total: Int) {
            if (total > 0) {
                bar.isIndeterminate = false
                bar.max = total
                bar.progress = loaded
                label.text = "$title\n$loaded / $total"
            } else {
                label.text = "$title\n$loaded"
            }
        }

        fun error(text: String) {
            label.text = text
            bar.visibility = View.GONE
        }
    }

    fun testConnection() {
        if (!isNetworkConnected()) {
            onRequestWifi(PENDING_ACTION_TEST)
            return
        }

        val key = ActionScreens.open(ActionScreens.Spec(localize("Test Connection"), listOf(localize("Connecting..."))))

        provider.testConnection(object : MediaSyncProvider.ConnectionCallback {
            override fun onSuccess(serverName: String, version: String) {
                activity.runOnUiThread {
                    ActionScreens.close(key)
                    Notice.show(
                        activity,
                        "${localize("Connected")}: $serverName"
                    )
                    refreshRoot()
                }
            }

            override fun onError(errorMessage: String) {
                activity.runOnUiThread {
                    ActionScreens.close(key)
                    Notice.error(
                        activity,
                        "${localize("Cannot reach server. Check URL and network.")}\n$errorMessage"
                    )
                }
            }
        })
    }

    fun fetchLibraries() {
        if (!provider.isConfigured()) {
            Notice.show(activity, localize("Connect to discover libraries."))
            return
        }

        if (!isNetworkConnected()) {
            onRequestWifi(PENDING_ACTION_FETCH)
            return
        }

        val key = ActionScreens.open(ActionScreens.Spec(localize("Fetch Libraries"), listOf(localize("Fetching libraries..."))))

        Thread {
            try {
                provider.fetchLibraries(MediaSyncProvider.CancellationToken())
                activity.runOnUiThread {
                    ActionScreens.close(key)
                    Notice.show(activity, localize("Libraries updated"))
                    refreshRoot()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    ActionScreens.close(key)
                    Notice.error(
                        activity,
                        "${localize("Failed to fetch libraries")}: ${e.message ?: ""}"
                    )
                }
            }
        }.start()
    }

    fun startQuickConnect() {
        val serverUrl = provider.serverUrl
        if (serverUrl.isBlank()) {
            Notice.error(activity, localize("Server URL is empty"))
            return
        }

        if (!isNetworkConnected()) {
            onRequestWifi(PENDING_ACTION_QUICKCONNECT)
            return
        }

        // The code and the state on a screen; Back cancels
        val isCancelled = booleanArrayOf(false)
        fun info(code: String?, status: String) = listOfNotNull(
            localize("Enter this code on your device:"), code, localize("Go to Jellyfin Settings > Quick Connect"), status
        )
        val key = ActionScreens.open(ActionScreens.Spec(
            localize("QuickConnect"),
            info(null, localize("Connecting...")),
            onCancel = { isCancelled[0] = true }
        ))

        Thread {
            try {
                val client = provider.createClient()
                if (!client.isQuickConnectEnabled()) {
                    activity.runOnUiThread {
                        ActionScreens.update(key, info(null, localize("Server does not support QuickConnect")))
                        Notice.error(activity, localize("Server does not support QuickConnect"))
                    }
                    return@Thread
                }

                val initSession = client.initiateQuickConnect()
                if (isCancelled[0]) return@Thread

                ActionScreens.update(key, info(initSession.code, localize("Waiting for authorization...")))

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
                    ActionScreens.update(key, info(initSession.code, localize("QuickConnect Expired / Failed")))
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
                    ActionScreens.close(key)
                    Notice.show(
                        activity,
                        "${localize("QuickConnect Paired!")} ${localize("Paired as ")}${auth.serverName}",
                        long = true
                    )
                    refreshRoot()
                }
            } catch (e: Exception) {
                if (isCancelled[0]) return@Thread
                activity.runOnUiThread {
                    val msg = e.message ?: localize("Connection Error")
                    ActionScreens.update(key, info(null, msg))
                    Notice.error(activity, msg)
                }
            }
        }.start()
    }

    fun launchSync() {
        if (isCustomScanning()) {
            Notice.show(activity, localize("Scanning Media..."))
            return
        }

        if (syncManager.isSyncing) {
            Notice.show(activity, localize("Syncing..."))
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

    /** The sync state on a progress screen. Back stops the sync. */
    private fun showSyncModal() {
        val key = ActionScreens.open(ActionScreens.Spec(
            localize("Jellyfin Sync"),
            listOf(localize("Syncing...")),
            onCancel = { syncManager.cancelSync() }
        ))
        var lastUpdate = 0L

        syncManager.syncListener = object : SyncManager.SyncListener {
            override fun onStateChanged(state: SyncManager.SyncState, message: String?) {
                activity.runOnUiThread {
                    ActionScreens.update(key, listOf(message ?: ""))
                    when (state) {
                        SyncManager.SyncState.COMPLETE -> {
                            Notice.show(activity, localize("Sync Complete"))
                            ActionScreens.close(key)
                            refreshRoot()
                            onSyncFinished()
                        }
                        SyncManager.SyncState.ERROR -> {
                            Notice.error(activity, message ?: "Error")
                            ActionScreens.close(key)
                        }
                        SyncManager.SyncState.CANCELLED -> {
                            ActionScreens.close(key)
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
                // At most 4 new builds of the screen a second
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastUpdate < 250 && currentItem < totalItems) return
                lastUpdate = now
                val pct = if (totalItems > 0) currentItem * 100 / totalItems else 0
                val sizeStr = if (bytesTotal > 0) " - ${JellyfinLabels.formatSize(bytesRead)} / ${JellyfinLabels.formatSize(bytesTotal)}" else ""
                ActionScreens.update(key, listOf("$libraryName ($currentItem/$totalItems)", "$pct %", "$currentTitle$sizeStr"))
            }
        }
    }
}
