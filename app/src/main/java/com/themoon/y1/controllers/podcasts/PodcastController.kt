package com.themoon.y1.controllers.podcasts

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.controllers.settings.confirmScreen
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.models.PodcastChannel
import com.themoon.y1.models.PodcastEpisode
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.podcasts.PodcastDownloadManager
import com.themoon.y1.podcasts.PodcastRepository
import com.themoon.y1.views.Notice
import com.themoon.y1.views.TablerIcons
import java.io.File
import java.util.ArrayList

/**
 * Screen controller for Podcast feeds, episode browsing, download actions,
 * streaming, and subscription management.
 */
class PodcastController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val containerBrowserItems: LinearLayout,
    private val listVirtualSongs: ListView,
    private val tvBrowserPath: TextView?,
    private val repository: PodcastRepository,
    private val downloadManager: PodcastDownloadManager,
    private val onRequestSearch: () -> Unit,
    private val onNavigateToPlayer: () -> Unit,
    private val createListButton: (icon: String, text: String, color: Int?) -> View,
    private val createTablerButton: (icon: Array<String>, text: String, textColor: Int, iconColor: Int) -> View,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String,
    private val showLoadingPopup: () -> Unit = {},
    private val onHideLoadingPopup: () -> Unit = {},
    private val onExitPodcasts: () -> Unit = {}
) : ScreenController {

    private val scrollViewBrowser: View? = containerBrowserItems.parent as? View
    private var currentMode: BrowserMode = BrowserMode.PodcastChannels
    private var lastFocusText: String? = null

    val isChannelsMode: Boolean
        get() = currentMode is BrowserMode.PodcastChannels

    val isEpisodesMode: Boolean
        get() = currentMode is BrowserMode.PodcastEpisodes

    val isManageMode: Boolean
        get() = currentMode is BrowserMode.PodcastManage

    override fun onBackPressed(): Boolean {
        return when (currentMode) {
            is BrowserMode.PodcastEpisodes, is BrowserMode.PodcastManage -> {
                buildChannelsUI()
                true
            }
            is BrowserMode.PodcastChannels -> {
                onExitPodcasts()
                true
            }
            else -> false
        }
    }

    override fun onEnter(state: ScreenState) {
        when (state) {
            is ScreenState.Podcasts -> buildChannelsUI()
            is ScreenState.PodcastEpisodes -> buildEpisodesUI(state.channelTitle, state.rssUrl)
            is ScreenState.PodcastManage -> buildManageUI()
            is ScreenState.Browser -> {
                currentMode = state.mode
                when (val mode = state.mode) {
                    is BrowserMode.PodcastChannels -> buildChannelsUI()
                    is BrowserMode.PodcastEpisodes -> buildEpisodesUI(mode.channelTitle, mode.rssUrl)
                    is BrowserMode.PodcastManage -> buildManageUI()
                    else -> buildChannelsUI()
                }
            }
            else -> buildChannelsUI()
        }
    }

    override fun onExit() {
    }

    override fun onThemeChanged() {
        when (currentMode) {
            // The episode list comes from the network: make only its rows again
            is BrowserMode.PodcastEpisodes -> listVirtualSongs.adapter = listVirtualSongs.adapter
            is BrowserMode.PodcastManage -> buildManageUI()
            else -> buildChannelsUI()
        }
    }

    fun buildChannelsUI() {
        currentMode = BrowserMode.PodcastChannels
        scrollViewBrowser?.visibility = View.VISIBLE
        listVirtualSongs.visibility = View.GONE
        containerBrowserItems.removeAllViews()
        tvBrowserPath?.text = localize("Podcasts")

        val btnSearch = createListButton("\uE8B6", localize("Search New Podcasts"), 0xFF00FFFF.toInt())
        btnSearch.setOnClickListener {
            clickFeedback()
            onRequestSearch()
        }
        containerBrowserItems.addView(btnSearch)

        val btnManage = createListButton("\uE872", localize("Manage Subscriptions"), 0xFFFF5555.toInt())
        btnManage.setOnClickListener {
            clickFeedback()
            buildManageUI()
        }
        containerBrowserItems.addView(btnManage)

        val channels = repository.getSubscriptions()
        if (channels.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = "⚠️ " + localize("No subscriptions found.") + "\n\n" +
                        localize("Create a 'subscriptions.txt' file in the Podcasts folder.") + "\n" +
                        localize("Format: Channel Name|RSS_URL")
                setTextColor(0xFF888888.toInt())
                gravity = Gravity.CENTER
                setPadding(20, 100, 20, 50)
                setLineSpacing(15f, 1.2f)
            }
            containerBrowserItems.addView(tvEmpty)
        } else {
            for (ch in channels) {
                val btnCh = createListButton("\uE03E", ch.title, null)
                btnCh.tag = ch.title
                btnCh.setOnClickListener {
                    clickFeedback()
                    lastFocusText = ch.title
                    buildEpisodesUI(ch.title, ch.rssUrl)
                    Notice.show(activity, localize("Connecting to server: ") + ch.title)
                }
                containerBrowserItems.addView(btnCh)
            }
        }

        if (containerBrowserItems.childCount > 0) {
            var focused = false
            val target = lastFocusText
            if (!target.isNullOrEmpty()) {
                for (i in 0 until containerBrowserItems.childCount) {
                    val child = containerBrowserItems.getChildAt(i)
                    if (target == child.tag) {
                        child.requestFocus()
                        focused = true
                        break
                    }
                }
            }
            if (!focused) {
                containerBrowserItems.getChildAt(0).requestFocus()
            }
        }
    }

    fun buildEpisodesUI(channelName: String, rssUrl: String) {
        currentMode = BrowserMode.PodcastEpisodes(channelName, rssUrl)
        scrollViewBrowser?.visibility = View.GONE
        listVirtualSongs.visibility = View.VISIBLE
        tvBrowserPath?.text = "${localize("Podcast")}: $channelName"

        Thread {
            try {
                val episodes = repository.fetchEpisodes(rssUrl, channelName)
                activity.runOnUiThread {
                    displayEpisodesList(channelName, episodes)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Notice.error(
                        activity,
                        localize("Failed to fetch episodes: ") + (e.message ?: "Unknown error")
                    )
                }
            }
        }.start()
    }

    private fun displayEpisodesList(channelName: String, episodes: List<SongItem>) {
        val adapter = com.themoon.y1.adapters.SongListAdapter(episodes)
        listVirtualSongs.adapter = adapter
        listVirtualSongs.setOnItemClickListener { _, _, position, _ ->
            clickFeedback()
            if (position in episodes.indices) {
                val ep = episodes[position]
                showActionDialog(ep.title, ep.genre, ep.album, channelName)
            }
        }
        listVirtualSongs.requestFocus()
    }

    fun buildManageUI() {
        currentMode = BrowserMode.PodcastManage
        scrollViewBrowser?.visibility = View.VISIBLE
        listVirtualSongs.visibility = View.GONE
        containerBrowserItems.removeAllViews()
        tvBrowserPath?.text = "${localize("Podcasts")}: ${localize("Manage Subscriptions")}"

        val btnBack = createListButton("\uE5C4", localize("Back to Podcasts"), null)
        btnBack.setOnClickListener {
            clickFeedback()
            buildChannelsUI()
        }
        containerBrowserItems.addView(btnBack)

        val channels = repository.getSubscriptions()
        if (channels.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = "⚠️ " + localize("No subscriptions found.")
                setTextColor(0xFF888888.toInt())
                gravity = Gravity.CENTER
                setPadding(20, 100, 20, 50)
            }
            containerBrowserItems.addView(tvEmpty)
        } else {
            for (ch in channels) {
                val btnDel = createTablerButton(
                    TablerIcons.TRASH,
                    ch.title,
                    0xFFFF5555.toInt(),
                    0xFFFF5555.toInt()
                )
                btnDel.setOnClickListener {
                    clickFeedback()
                    showDeleteConfirmDialog(ch.title, ch.rssUrl)
                }
                containerBrowserItems.addView(btnDel)
            }
        }

        if (containerBrowserItems.childCount > 1) {
            containerBrowserItems.postDelayed({
                containerBrowserItems.getChildAt(1).requestFocus()
            }, 50)
        }
    }

    private fun showDeleteConfirmDialog(channelName: String, rssUrl: String) {
        ActionScreens.open(confirmScreen(
            localize("Delete Channel"),
            "${localize("Do you want to delete '")}$channelName'?",
            localize("Delete")
        ) {
            repository.removeSubscription(channelName, rssUrl)
            buildManageUI()
        })
    }

    /** The episode screen: Play (downloaded) or Stream, and Download. Back cancels. */
    fun showActionDialog(title: String, audioUrl: String, imageUrl: String, channelName: String) {
        val safeChannel = sanitize(channelName)
        val safeTitle = "${sanitize(title)}.mp3"
        val localFile = File(StoragePaths.getPodcastChannelDir(safeChannel), safeTitle)
        val prefs = activity.getSharedPreferences("AppPrefs", 0)
        val info = ArrayList<String>()
        val actions = ArrayList<ActionScreens.Action>()

        if (localFile.exists() && localFile.length() > 0) {
            actions += ActionScreens.Action(localize("Play (Downloaded)")) {
                val streamKey = "/PODCAST_STREAM/$safeChannel/$safeTitle"
                var savedPos = prefs.getInt("book_pos_${localFile.absolutePath}", 0)
                if (savedPos == 0) savedPos = prefs.getInt("book_pos_$streamKey", 0)
                val playList = arrayListOf(localFile)
                if (savedPos > 0) {
                    AudioPlayerManager.getInstance().playTrackListWithOffset(playList, 0, savedPos)
                } else {
                    AudioPlayerManager.getInstance().playTrackList(playList, 0)
                }
                onNavigateToPlayer()
            }
        } else {
            actions += ActionScreens.Action(localize("Stream (Play)")) {
                Notice.show(activity, "${localize("Connecting to server: ")}\n$title")
                val streamKey = "/PODCAST_STREAM/$safeChannel/$safeTitle"
                var savedPos = prefs.getInt("book_pos_$streamKey", 0)
                if (savedPos == 0) savedPos = prefs.getInt("book_pos_${localFile.absolutePath}", 0)
                AudioPlayerManager.getInstance().playPodcastStream(audioUrl, title, imageUrl, channelName, savedPos)
                onNavigateToPlayer()
            }
            if (downloadManager.isDownloading(audioUrl)) {
                info += localize("Downloading...")
            } else {
                actions += ActionScreens.Action(localize("Download")) {
                    downloadManager.startDownload(
                        title,
                        audioUrl,
                        channelName,
                        object : PodcastDownloadManager.DownloadListener {
                            override fun onProgress(audioUrl: String, progressPercent: Int) {}
                            override fun onComplete(audioUrl: String, destFile: File) {}
                            override fun onError(audioUrl: String, errorMsg: String) {}
                        }
                    )
                }
            }
        }
        ActionScreens.open(ActionScreens.Spec(title, info, actions))
    }

    fun searchApplePodcasts(keyword: String) {
        showLoadingPopup()

        Thread {
            try {
                val results = com.themoon.y1.podcasts.PodcastSearchClient.getInstance().search(keyword, 200)

                activity.runOnUiThread {
                    onHideLoadingPopup()
                    if (results.isEmpty()) {
                        Notice.show(activity, "${localize("No podcasts found for: ")}$keyword")
                    } else {
                        showSearchResultsDialog(results)
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    onHideLoadingPopup()
                    Notice.error(activity, "Search Failed: ${e.message}")
                }
            }
        }.start()
    }

    /** The search results: one row for each podcast; the centre button subscribes. Back cancels. */
    private fun showSearchResultsDialog(results: List<com.themoon.y1.podcasts.PodcastSearchClient.PodcastSearchResult>) {
        ActionScreens.open(ActionScreens.Spec(
            "${localize("Search Results")} (${results.size})",
            actions = results.map { item ->
                ActionScreens.Action(item.name) {
                    try {
                        val podcastDir = StoragePaths.getPodcastsDir()
                        if (!podcastDir.exists()) podcastDir.mkdirs()
                        val subFile = File(podcastDir, "subscriptions.txt")
                        val safeName = item.name.replace("|", "-")
                        subFile.appendText("\n$safeName|${item.feedUrl}")

                        Notice.show(activity, "✅ ${localize("Subscribed: ")}$safeName")
                        buildChannelsUI()
                    } catch (e: Exception) {
                        Notice.error(activity, "Error saving: ${e.message}")
                    }
                }
            }
        ))
    }

    private fun sanitize(input: String): String {
        return input.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
}

