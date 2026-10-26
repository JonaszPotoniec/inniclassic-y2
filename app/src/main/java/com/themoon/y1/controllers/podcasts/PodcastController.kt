package com.themoon.y1.controllers.podcasts

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.models.PodcastChannel
import com.themoon.y1.models.PodcastEpisode
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.podcasts.PodcastDownloadManager
import com.themoon.y1.podcasts.PodcastRepository
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
                clickFeedback()
                buildChannelsUI()
                true
            }
            is BrowserMode.PodcastChannels -> {
                clickFeedback()
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
                    Toast.makeText(activity, localize("Connecting to server: ") + ch.title, Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(
                        activity,
                        localize("Failed to fetch episodes: ") + (e.message ?: "Unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
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
        val d = activity.resources.displayMetrics.density
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(ThemeManager.getOverlayBackgroundColor() or 0xFF000000.toInt())
                cornerRadius = 15 * d
                setStroke((1 * d).toInt(), 0x33FFFFFF)
            }
            background = bg
            setPadding((15 * d).toInt(), (20 * d).toInt(), (15 * d).toInt(), (15 * d).toInt())
        }

        val tvTitle = TextView(activity).apply {
            text = "🗑️ " + localize("Delete Channel")
            setTextColor(0xFFFF5555.toInt())
            textSize = 18f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (10 * d).toInt())
        }
        rootLayout.addView(tvTitle)

        val tvMsg = TextView(activity).apply {
            text = "${localize("Do you want to delete '")}$channelName'?"
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (20 * d).toInt())
            setLineSpacing(5f, 1.2f)
        }
        rootLayout.addView(tvMsg)

        val wheelListener = View.OnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == 21 || keyCode == 19) {
                    val idx = rootLayout.indexOfChild(v)
                    for (i in idx - 1 downTo 0) {
                        if (rootLayout.getChildAt(i).isFocusable) {
                            rootLayout.getChildAt(i).requestFocus()
                            clickFeedback()
                            return@OnKeyListener true
                        }
                    }
                    return@OnKeyListener true
                }
                if (keyCode == 22 || keyCode == 20) {
                    val idx = rootLayout.indexOfChild(v)
                    for (i in idx + 1 until rootLayout.childCount) {
                        if (rootLayout.getChildAt(i).isFocusable) {
                            rootLayout.getChildAt(i).requestFocus()
                            clickFeedback()
                            return@OnKeyListener true
                        }
                    }
                    return@OnKeyListener true
                }
            }
            false
        }

        val btnDelete = createTablerButton(TablerIcons.TRASH, localize("Delete"), 0xFFFF5555.toInt(), 0xFFFF5555.toInt()).apply {
            setOnKeyListener(wheelListener)
            setOnClickListener {
                clickFeedback()
                dialog.dismiss()
                repository.removeSubscription(channelName, rssUrl)
                buildManageUI()
            }
        }
        rootLayout.addView(btnDelete)

        val btnCancel = createListButton("\uE5CD", localize("Cancel"), ThemeManager.getTextColorPrimary()).apply {
            setOnKeyListener(wheelListener)
            setOnClickListener {
                clickFeedback()
                dialog.dismiss()
            }
        }
        rootLayout.addView(btnCancel)

        dialog.setContentView(rootLayout)
        dialog.window?.setLayout((300 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
        btnDelete.requestFocus()
    }

    fun showActionDialog(title: String, audioUrl: String, imageUrl: String, channelName: String) {
        val safeChannel = sanitize(channelName)
        val safeTitle = "${sanitize(title)}.mp3"
        val localFile = File(StoragePaths.getPodcastChannelDir(safeChannel), safeTitle)

        val d = activity.resources.displayMetrics.density
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(ThemeManager.getOverlayBackgroundColor() or 0x88000000.toInt())
                cornerRadius = 15 * d
                setStroke((1 * d).toInt(), 0x33FFFFFF)
            }
            background = bg
            setPadding((15 * d).toInt(), (20 * d).toInt(), (15 * d).toInt(), (15 * d).toInt())
        }

        val tvTitle = TextView(activity).apply {
            text = title
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 17f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (20 * d).toInt())
        }
        rootLayout.addView(tvTitle)

        val wheelListener = View.OnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == 21) {
                    val idx = rootLayout.indexOfChild(v)
                    for (i in idx - 1 downTo 0) {
                        if (rootLayout.getChildAt(i).isFocusable) {
                            rootLayout.getChildAt(i).requestFocus()
                            clickFeedback()
                            return@OnKeyListener true
                        }
                    }
                    return@OnKeyListener true
                }
                if (keyCode == 22) {
                    val idx = rootLayout.indexOfChild(v)
                    for (i in idx + 1 until rootLayout.childCount) {
                        if (rootLayout.getChildAt(i).isFocusable) {
                            rootLayout.getChildAt(i).requestFocus()
                            clickFeedback()
                            return@OnKeyListener true
                        }
                    }
                    return@OnKeyListener true
                }
            }
            false
        }

        val prefs = activity.getSharedPreferences("AppPrefs", 0)

        if (localFile.exists() && localFile.length() > 0) {
            val btnPlay = createListButton("\uE037", localize("Play (Downloaded)"), null).apply {
                setOnKeyListener(wheelListener)
                setOnClickListener {
                    clickFeedback()
                    dialog.dismiss()
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
            }
            rootLayout.addView(btnPlay)
        } else {
            val btnStream = createListButton("\uE037", localize("Stream (Play)"), null).apply {
                setOnKeyListener(wheelListener)
                setOnClickListener {
                    clickFeedback()
                    dialog.dismiss()
                    Toast.makeText(activity, "${localize("Connecting to server: ")}\n$title", Toast.LENGTH_SHORT).show()
                    val streamKey = "/PODCAST_STREAM/$safeChannel/$safeTitle"
                    var savedPos = prefs.getInt("book_pos_$streamKey", 0)
                    if (savedPos == 0) savedPos = prefs.getInt("book_pos_${localFile.absolutePath}", 0)

                    AudioPlayerManager.getInstance().playPodcastStream(audioUrl, title, imageUrl, channelName, savedPos)
                    onNavigateToPlayer()
                }
            }
            rootLayout.addView(btnStream)

            if (downloadManager.isDownloading(audioUrl)) {
                val btnDownloading = createListButton("\uE863", localize("Downloading..."), null).apply {
                    setOnKeyListener(wheelListener)
                }
                rootLayout.addView(btnDownloading)
            } else {
                val btnDown = createListButton("\uE2C4", localize("Download"), null).apply {
                    setOnKeyListener(wheelListener)
                    setOnClickListener {
                        clickFeedback()
                        dialog.dismiss()
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
                rootLayout.addView(btnDown)
            }
        }

        dialog.setContentView(rootLayout)
        dialog.window?.setLayout((300 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    fun searchApplePodcasts(keyword: String) {
        showLoadingPopup()

        Thread {
            try {
                val results = com.themoon.y1.podcasts.PodcastSearchClient.getInstance().search(keyword, 200)

                activity.runOnUiThread {
                    onHideLoadingPopup()
                    if (results.isEmpty()) {
                        Toast.makeText(activity, "${localize("No podcasts found for: ")}$keyword", Toast.LENGTH_SHORT).show()
                    } else {
                        showSearchResultsDialog(results)
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    onHideLoadingPopup()
                    Toast.makeText(activity, "Search Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showSearchResultsDialog(results: List<com.themoon.y1.podcasts.PodcastSearchClient.PodcastSearchResult>) {
        val d = activity.resources.displayMetrics.density

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ThemeManager.getOverlayBackgroundColor() or -0x1000000)
                cornerRadius = 15 * d
            }
            setPadding((10 * d).toInt(), (20 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        }

        val tvTitle = TextView(activity).apply {
            text = "🔍 ${localize("Search Results")} (${results.size})"
            setTextColor(0xFF00FFFF.toInt())
            textSize = 18f
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (15 * d).toInt())
        }
        rootLayout.addView(tvTitle)

        val listView = ListView(activity).apply {
            divider = ColorDrawable(0x00000000)
            dividerHeight = if (ThemeManager.isClassicTheme()) 0 else (4 * d).toInt()
            selector = ColorDrawable(Color.TRANSPARENT)
            isScrollbarFadingEnabled = false
            itemsCanFocus = true
        }

        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = results.size
            override fun getItem(position: Int): Any = results[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val itemBtn: LinearLayout
                val ivThumb: android.widget.ImageView
                val t1: TextView
                val t2: TextView

                if (convertView == null) {
                    itemBtn = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        isFocusable = true
                        isClickable = true
                        isSoundEffectsEnabled = false
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            setColor(ThemeManager.getListButtonNormalBg())
                            cornerRadius = 6 * d
                        }
                        setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
                        layoutParams = android.widget.AbsListView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }

                    ivThumb = android.widget.ImageView(activity).apply {
                        layoutParams = LinearLayout.LayoutParams((55 * d).toInt(), (55 * d).toInt()).apply {
                            rightMargin = (15 * d).toInt()
                        }
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(0x22FFFFFF)
                    }

                    val textContainer = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                    }

                    t1 = TextView(activity).apply {
                        setTextColor(ThemeManager.getTextColorPrimary())
                        textSize = 16f
                        typeface = ThemeManager.getCustomFontBold()
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }

                    t2 = TextView(activity).apply {
                        setTextColor(ThemeManager.getTextColorSecondary())
                        textSize = 13f
                        typeface = ThemeManager.getCustomFont()
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }

                    textContainer.addView(t1)
                    textContainer.addView(t2)
                    itemBtn.addView(ivThumb)
                    itemBtn.addView(textContainer)
                    itemBtn.tag = arrayOf(ivThumb, t1, t2)
                } else {
                    itemBtn = convertView as LinearLayout
                    val holder = itemBtn.tag as Array<*>
                    ivThumb = holder[0] as android.widget.ImageView
                    t1 = holder[1] as TextView
                    t2 = holder[2] as TextView
                }

                val item = results[position]
                t1.text = item.name
                t2.text = item.author

                ivThumb.setImageBitmap(null)
                ivThumb.tag = item.artworkUrl

                if (item.artworkUrl.isNotEmpty()) {
                    Thread {
                        try {
                            val request = okhttp3.Request.Builder().url(item.artworkUrl).build()
                            val response = okhttp3.OkHttpClient().newCall(request).execute()
                            if (response.isSuccessful) {
                                val stream = response.body()?.byteStream()
                                val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                                stream?.close()
                                activity.runOnUiThread {
                                    if (item.artworkUrl == ivThumb.tag && bmp != null) {
                                        ivThumb.setImageBitmap(bmp)
                                    }
                                }
                            }
                        } catch (ignored: Exception) {}
                    }.start()
                }

                itemBtn.setOnFocusChangeListener { v, hasFocus ->
                    val focusedBg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(ThemeManager.getListButtonFocusedBg())
                        cornerRadius = 6 * d
                    }
                    val normalBg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(ThemeManager.getListButtonNormalBg())
                        cornerRadius = 6 * d
                    }
                    if (hasFocus) {
                        v.background = focusedBg
                        t1.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                        t2.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                    } else {
                        v.background = normalBg
                        t1.setTextColor(ThemeManager.getTextColorPrimary())
                        t2.setTextColor(ThemeManager.getTextColorSecondary())
                    }
                }

                itemBtn.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (keyCode == 21 || keyCode == 19) {
                            val targetPos = position - 1
                            if (targetPos >= 0) {
                                listView.setSelectionFromTop(targetPos, (10 * d).toInt())
                                listView.post {
                                    val visibleIdx = targetPos - listView.firstVisiblePosition
                                    if (visibleIdx in 0 until listView.childCount) {
                                        listView.getChildAt(visibleIdx).requestFocus()
                                    }
                                }
                                clickFeedback()
                            }
                            return@setOnKeyListener true
                        }
                        if (keyCode == 22 || keyCode == 20) {
                            val targetPos = position + 1
                            if (targetPos < results.size) {
                                listView.setSelectionFromTop(targetPos, (10 * d).toInt())
                                listView.post {
                                    val visibleIdx = targetPos - listView.firstVisiblePosition
                                    if (visibleIdx in 0 until listView.childCount) {
                                        listView.getChildAt(visibleIdx).requestFocus()
                                    }
                                }
                                clickFeedback()
                            }
                            return@setOnKeyListener true
                        }
                    }
                    false
                }

                itemBtn.setOnClickListener {
                    clickFeedback()
                    try {
                        val podcastDir = StoragePaths.getPodcastsDir()
                        if (!podcastDir.exists()) podcastDir.mkdirs()
                        val subFile = File(podcastDir, "subscriptions.txt")
                        val safeName = item.name.replace("|", "-")
                        subFile.appendText("\n$safeName|${item.feedUrl}")

                        Toast.makeText(activity, "✅ ${localize("Subscribed: ")}$safeName", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        buildChannelsUI()
                    } catch (e: Exception) {
                        Toast.makeText(activity, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                return itemBtn
            }
        }
        listView.adapter = adapter

        val listWrapper = LinearLayout(activity).apply {
            addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (320 * d).toInt()))
        }
        rootLayout.addView(listWrapper)

        dialog.setContentView(rootLayout)
        dialog.window?.setLayout((340 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()

        listView.postDelayed({
            if (listView.childCount > 0) {
                listView.getChildAt(0).requestFocus()
            }
        }, 100)
    }

    private fun sanitize(input: String): String {
        return input.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
}

