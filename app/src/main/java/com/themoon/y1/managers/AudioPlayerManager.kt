package com.themoon.y1.managers

import com.themoon.y1.Prefs
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.audio.AudioCapabilities
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.audio.AudioRendererEventListener
import com.google.android.exoplayer2.audio.AudioSink
import com.google.android.exoplayer2.audio.DefaultAudioSink
import com.google.android.exoplayer2.audio.SonicAudioProcessor
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.core.TimeFormat
import com.themoon.y1.views.Notice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Audio playback coordinator managing ExoPlayer, fallback MediaPlayer for FLAC,
 * and audio metadata extraction across formats (Opus, OGG Vorbis, FLAC, ALAC/M4A).
 */
class AudioPlayerManager private constructor() {
    var exoPlayer: SimpleExoPlayer? = null
    val customEqProcessor: Y1EqAudioProcessor = Y1EqAudioProcessor()
    val crossfeedProcessor: Y1CrossfeedAudioProcessor = Y1CrossfeedAudioProcessor()
    var legacyPlayer: MediaPlayer? = null
    var isUsingLegacyPlayer: Boolean = false
    // The file that the legacy player has prepared, or null.
    private var legacyPlayerPath: String? = null

    private val flacLegacyFailedPaths: MutableSet<String> = HashSet()
    private var isGaplessQueueActive: Boolean = false
    private var currentFileInputStream: FileInputStream? = null

    private var currentSpeed: Float = 1.0f
    private var currentMasterVolume: Float = 1.0f

    private var pendingScrobbleArtist: String? = null
    private var pendingScrobbleTitle: String? = null
    private var pendingScrobbleAlbum: String? = null
    private var pendingScrobblePath: String? = null

    // The cover of a new track: read and decoded on its own thread, shown only for the track that is still loaded
    private val artRequests = TrackArtRequests()
    private val artExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "y1-track-art") }

    // The covers of the current track and of the 2 tracks after and before it in the queue: a skip shows the
    // cover with the title. Read one at a time on a low-priority thread, from 1 s after the last track change.
    private val neighbourCovers = NeighbourCovers<TrackArt>(NeighbourCovers.SIZE)
    private val coverPrefetch = Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
            r.run()
        }, "y1-cover-prefetch")
    }
    private val coverPrefetchHandler = Handler(Looper.getMainLooper())
    @Volatile private var coverPrefetchRun = 0

    @Volatile var isPlayingActive: Boolean = false
        set(value) {
            if (field != value) com.themoon.y1.battery.BatteryHistory.setPlaying(com.themoon.y1.battery.BatteryHistory.MUSIC, value)
            field = value
        }
    @Volatile var currentlyPlayingFilePath: String? = null

    internal var isRestoringPlaybackState: Boolean = false
    private val positionSaveHandler = Handler(Looper.getMainLooper())
    private val positionSaveRunnable = object : Runnable {
        override fun run() {
            if (isPlaying()) {
                savePlaybackPositionOnly(false)
                positionSaveHandler.postDelayed(this, 5000)
            }
        }
    }

    private fun startPeriodicPositionSaver() {
        positionSaveHandler.removeCallbacks(positionSaveRunnable)
        positionSaveHandler.postDelayed(positionSaveRunnable, 5000)
        MainActivity.instance?.let { PlayHistoryManager.getInstance(it).onPlaybackStarted() }
    }

    private fun stopPeriodicPositionSaver() {
        positionSaveHandler.removeCallbacks(positionSaveRunnable)
    }

    fun setMasterVolume(volume: Float) {
        currentMasterVolume = volume.coerceIn(0.0f, 1.0f)
        applyMasterVolume()
    }

    fun getMasterVolume(): Float = currentMasterVolume

    fun applyMasterVolume() {
        try {
            exoPlayer?.volume = currentMasterVolume
        } catch (ignored: Throwable) {}

        try {
            legacyPlayer?.setVolume(currentMasterVolume, currentMasterVolume)
        } catch (ignored: Throwable) {}
    }

    fun initPlayer(context: Context) {
        if (exoPlayer != null) return

        val renderersFactory = object : DefaultRenderersFactory(context.applicationContext) {
            override fun buildAudioRenderers(
                ctx: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: java.util.ArrayList<com.google.android.exoplayer2.Renderer>
            ) {
                val procList = ArrayList<AudioProcessor>()
                try {
                    val ctor = Class.forName("com.google.android.exoplayer2.audio.ToInt16PcmAudioProcessor")
                        .getDeclaredConstructor()
                    ctor.isAccessible = true
                    procList.add(ctor.newInstance() as AudioProcessor)
                } catch (ignored: Exception) {}

                val immortalSonic = object : AudioProcessor {
                    private val sonic = SonicAudioProcessor()
                    private var active = false

                    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
                        var safeSampleRate = inputAudioFormat.sampleRate
                        if (safeSampleRate > 48000) {
                            safeSampleRate = 48000
                        }
                        sonic.setOutputSampleRateHz(safeSampleRate)
                        val outputFormat = sonic.configure(inputAudioFormat)
                        active = sonic.isActive
                        return outputFormat
                    }

                    override fun isActive(): Boolean = active
                    override fun queueInput(inputBuffer: ByteBuffer) = sonic.queueInput(inputBuffer)
                    override fun queueEndOfStream() = sonic.queueEndOfStream()
                    override fun getOutput(): ByteBuffer = sonic.output
                    override fun isEnded(): Boolean = sonic.isEnded
                    override fun flush() = sonic.flush()
                    override fun reset() = sonic.reset()
                }

                procList.add(customEqProcessor)
                procList.add(crossfeedProcessor)
                procList.add(immortalSonic)

                val processors = procList.toTypedArray()
                val strict16BitCaps = AudioCapabilities(intArrayOf(2), 2)
                val customSink = DefaultAudioSink(strict16BitCaps, processors)

                val customSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    val decoders = mediaCodecSelector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    if (mimeType != null && (mimeType.contains("mp4a") || mimeType.contains("aac") || mimeType.contains("alac"))) {
                        val safeDecoders = ArrayList<MediaCodecInfo>()
                        for (info in decoders) {
                            if (info.name != null && info.name.lowercase(Locale.US).contains("mtk")) {
                                continue
                            }
                            safeDecoders.add(info)
                        }
                        return@MediaCodecSelector safeDecoders
                    }
                    decoders
                }

                super.buildAudioRenderers(
                    ctx,
                    extensionRendererMode,
                    customSelector,
                    enableDecoderFallback,
                    customSink,
                    eventHandler,
                    eventListener,
                    out
                )
            }
        }

        renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        exoPlayer = SimpleExoPlayer.Builder(context.applicationContext, renderersFactory).build().apply {
            // Partial wake lock while it plays: music continues when Android turns the screen off.
            setWakeMode(C.WAKE_MODE_LOCAL)
            val initialRepeatMode = MainActivity.instance?.prefs?.let { Prefs.REPEAT_MODE.get(it) } ?: 0
            repeatMode = when (initialRepeatMode) {
                1 -> Player.REPEAT_MODE_ONE
                2 -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            addListener(object : Player.EventListener {
                override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                    isPlayingActive = playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)
                    if (isPlayingActive) AudioSources.app.musicStarts()
                    if (playbackState == Player.STATE_READY && !isUsingLegacyPlayer) {
                        if (playWhenReady) {
                            startPeriodicPositionSaver()
                        } else {
                            stopPeriodicPositionSaver()
                        }
                        MainActivity.instance?.let { main ->
                            main.runOnUiThread {
                                try {
                                    AudioEffectManager.getInstance().applyAudioEffects()
                                    AudioEffectManager.getInstance().applyEqProfile()
                                    main.setupVisualizer()

                                    val duration = getDuration()
                                    main.tvPlayerTimeTotal.text = main.playerController.rightTimeText(getCurrentPosition().toInt(), duration.toInt())
                                    if (playWhenReady) {
                                        LastFmScrobbler.getInstance(main).onTrackStart(
                                            pendingScrobbleArtist,
                                            pendingScrobbleTitle,
                                            pendingScrobbleAlbum,
                                            duration.toLong(),
                                            pendingScrobblePath
                                        )
                                    }
                                    if (main.currentPlaylist.isNotEmpty()) {
                                        main.updateAudioQualityInfo(main.currentPlaylist[main.currentIndex])
                                    }
                                } catch (ignored: Exception) {}
                            }
                        }
                    } else if (playbackState == Player.STATE_ENDED && !isUsingLegacyPlayer) {
                        isPlayingActive = false
                        stopPeriodicPositionSaver()
                        handleTrackCompletion()
                    }

                    MainActivity.instance?.let { main ->
                        main.runOnUiThread {
                            main.updatePlayerUI()
                        }
                    }
                }

                override fun onPlayerError(error: ExoPlaybackException) {
                    isPlayingActive = false
                    val currentPath = MainActivity.instance?.let { it.currentPlaylist.getOrNull(it.currentIndex)?.absolutePath }
                    android.util.Log.e("AudioPlayerManager", "ExoPlayer playback error on $currentPath: ${error.message}", error)
                    if (!isUsingLegacyPlayer) {
                        handleTrackError("Cannot play this file.")
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (!isGaplessQueueActive || MainActivity.instance == null) return
                    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) return

                    val main = MainActivity.instance ?: return
                    val newIndex = exoPlayer?.currentWindowIndex ?: return
                    if (newIndex < 0 || newIndex >= main.currentPlaylist.size) return

                    main.currentIndex = newIndex
                    main.runOnUiThread {
                        prepareMusicTrack(newIndex)
                        main.updatePlayerUI()
                    }
                }
            })
        }
        applyMasterVolume()
    }

    fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        if (exoPlayer != null && !isUsingLegacyPlayer) {
            exoPlayer?.setPlaybackParameters(PlaybackParameters(speed, 1.0f))
        } else if (isUsingLegacyPlayer) {
            MainActivity.instance?.let { main ->
                main.runOnUiThread {
                    Notice.show(main, "Speed control is disabled for FLAC files on this device.")
                }
            }
        }
    }

    fun setShuffleMode(isShuffle: Boolean) {
        if (exoPlayer != null && !isUsingLegacyPlayer) {
            exoPlayer?.shuffleModeEnabled = isShuffle
        }
    }

    fun setRepeatMode(mode: Int) {
        if (exoPlayer != null && !isUsingLegacyPlayer) {
            val exoRepeatMode = when (mode) {
                1 -> Player.REPEAT_MODE_ONE
                2 -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            exoPlayer?.repeatMode = exoRepeatMode
        }
    }

    fun getCurrentSpeed(): Float = currentSpeed

    private fun handleTrackCompletion() {
        val main = MainActivity.instance ?: return
        main.runOnUiThread {
            try {
                val repeatMode = Prefs.REPEAT_MODE.get(main.prefs)
                if (repeatMode == 1) {
                    if (isUsingLegacyPlayer && legacyPlayer != null) {
                        legacyPlayer?.seekTo(0)
                        legacyPlayer?.start()
                    } else if (exoPlayer != null) {
                        exoPlayer?.seekTo(0)
                        exoPlayer?.playWhenReady = true
                    }
                    LastFmScrobbler.getInstance(main).rearmCurrentTrack()
                } else if (repeatMode == 2) {
                    nextTrack()
                } else {
                    if (main.currentIndex < main.currentPlaylist.size - 1) {
                        nextTrack()
                    } else {
                        isPlayingActive = false
                        stopPeriodicPositionSaver()
                        main.isPausedByHand = true
                        main.currentIndex = 0

                        if (isUsingLegacyPlayer && legacyPlayer != null) {
                            try {
                                legacyPlayer?.pause()
                                legacyPlayer?.seekTo(0)
                            } catch (ignored: Exception) {}
                        } else if (exoPlayer != null) {
                            exoPlayer?.playWhenReady = false
                            if (isGaplessQueueActive) {
                                exoPlayer?.seekTo(0, 0L)
                            }
                        }

                        prepareMusicTrack(0, initialSeekMs = 0L)
                        main.updatePlayerUI()
                        savePlaybackState(sync = true)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AudioPlayerManager", "Error in handleTrackCompletion: ${e.message}", e)
            }
        }
    }

    private fun handleTrackError(errorMsg: String) {
        val main = MainActivity.instance ?: return
        main.runOnUiThread {
            Notice.error(main, "$errorMsg Skipping...")
            nextTrack()
        }
    }

    fun playTrackList(list: List<File>, index: Int, initialSeekMs: Long = -1L) {
        saveAudiobookBookmarkIfNeeded()

        val main = MainActivity.instance ?: return
        initPlayer(main)

        val newList = ArrayList(list)
        if (newList.isEmpty()) return

        var targetIndex = index
        if (targetIndex < 0 || targetIndex >= newList.size) targetIndex = 0
        val targetSong = newList[targetIndex]

        main.originalPlaylist.clear()
        main.originalPlaylist.addAll(newList)
        main.currentPlaylist.clear()
        main.currentPlaylist.addAll(newList)

        val isShuffle = Prefs.SHUFFLE.get(main.prefs)
        val repeatPref = Prefs.REPEAT_MODE.get(main.prefs)
        if (isShuffle) {
            shuffleWithFirst(main.currentPlaylist, targetSong)
            main.currentIndex = 0
        } else {
            main.currentIndex = targetIndex
        }

        if (!isUsingLegacyPlayer && exoPlayer != null) {
            exoPlayer?.shuffleModeEnabled = isShuffle
            setRepeatMode(repeatPref)
        }
        main.isPausedByHand = false

        isGaplessQueueActive = !isShuffle && shouldUseGaplessQueue(main.currentPlaylist)
        if (isGaplessQueueActive) {
            val mediaItems = ArrayList<MediaItem>()
            for (f in main.currentPlaylist) {
                mediaItems.add(MediaItem.fromUri(Uri.fromFile(f)))
            }
            legacyPlayer?.let {
                it.stop()
                it.reset()
            }
            exoPlayer?.let { exo ->
                exo.stop()
                exo.setMediaItems(mediaItems, main.currentIndex, if (initialSeekMs > 0) initialSeekMs else 0L)
                exo.shuffleModeEnabled = false
                val exoRepeatMode = when (repeatPref) {
                    1 -> Player.REPEAT_MODE_ONE
                    2 -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
                exo.repeatMode = exoRepeatMode
                exo.setPlaybackParameters(PlaybackParameters(currentSpeed, 1.0f))
                exo.prepare()
                if (!main.isPausedByHand) exo.playWhenReady = true
            }
        }

        val seekParam = if (initialSeekMs >= 0) initialSeekMs else 0L
        prepareMusicTrack(main.currentIndex, initialSeekMs = seekParam)
        main.updatePlayerUI()
    }

    internal fun shouldUseGaplessQueue(list: List<File>): Boolean {
        val main = MainActivity.instance
        if (main?.isAudiobookLibraryMode == true) return false
        if (list.size > 15) return false
        for (f in list) {
            val p = f.absolutePath
            if (p.contains("/Audiobooks", ignoreCase = true) || p.contains("/Podcasts", ignoreCase = true) || p.startsWith("/PODCAST_STREAM")) {
                return false
            }
            if (f.name.lowercase(Locale.US).endsWith(".flac")) return false
        }
        return true
    }

    fun playPodcastStream(url: String, title: String, imageUrl: String?, channelName: String, offsetMs: Int) {
        val main = MainActivity.instance ?: return

        try {
            legacyPlayer?.let {
                it.stop()
                it.reset()
            }
            if (exoPlayer == null) {
                initPlayer(main.applicationContext)
            } else {
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
            }

            isUsingLegacyPlayer = false
            isGaplessQueueActive = false

            val safeChannel = channelName.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
            val safeTitle = title.replace("[\\\\/:*?\"<>|]".toRegex(), "_") + ".mp3"
            main.currentPlaylist.clear()
            main.currentPlaylist.add(File("/PODCAST_STREAM/$safeChannel", safeTitle))
            main.currentIndex = 0

            val mediaItem = MediaItem.fromUri(Uri.parse(url))
            val dataSourceFactory = DefaultDataSourceFactory(main, Util.getUserAgent(main, "Y1_Launcher"))
            val extractorsFactory = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory).createMediaSource(mediaItem)

            exoPlayer?.setMediaSource(mediaSource)
            exoPlayer?.prepare()

            if (offsetMs > 0) {
                exoPlayer?.seekTo(offsetMs.toLong())
            }
            exoPlayer?.setPlaybackParameters(PlaybackParameters(currentSpeed, 1.0f))

            artRequests.cancel()
            main.runOnUiThread {
                main.isPausedByHand = false
                main.tvPlayerTitle.text = title
                main.tvPlayerArtist.text = channelName
                main.tvPlayerAlbum.text = ""
                main.ivAlbumArt.setImageResource(R.drawable.default_album)
                main.updatePlayerUI()
            }

            if (!imageUrl.isNullOrEmpty()) {
                Thread {
                    try {
                        val imgUrl = java.net.URL(imageUrl)
                        val conn = imgUrl.openConnection() as java.net.HttpURLConnection
                        conn.doInput = true
                        conn.connect()
                        val bmp = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                        if (bmp != null) {
                            main.runOnUiThread {
                                main.ivAlbumArt.setImageBitmap(bmp)
                                main.updateMainMenuBackground()
                            }
                        }
                    } catch (ignored: Exception) {}
                }.start()
            }

            exoPlayer?.playWhenReady = true
        } catch (ignored: Exception) {}
    }

    fun playTrackListWithOffset(list: List<File>, index: Int, offsetMs: Int) {
        val initialSeek = if (offsetMs > 0) offsetMs.toLong() else -1L
        playTrackList(list, index, initialSeekMs = initialSeek)
        if (offsetMs > 0) {
            val totalSec = offsetMs / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            MainActivity.instance?.let { main ->
                main.runOnUiThread {
                    Notice.show(main, "Resuming playback from ${min}m ${sec}s")
                }
            }
        }
    }

    fun setupFolderPlaylist(clickedFile: File, parentFolder: File) {
        val main = MainActivity.instance ?: return

        val folderAudio = ArrayList<File>()
        val files = parentFolder.listFiles()
        if (files != null) {
            for (f in files) {
                if (f.isFile && isAudioFile(f.name)) folderAudio.add(f)
            }
            folderAudio.sortWith { f1, f2 -> f1.name.compareTo(f2.name, ignoreCase = true) }
        }
        var idx = folderAudio.indexOf(clickedFile)
        if (idx == -1) idx = 0
        playTrackList(folderAudio, idx)
    }

    private fun isAudioFile(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".wav") ||
                lower.endsWith(".ogg") || lower.endsWith(".m4a") || lower.endsWith(".aac") ||
                lower.endsWith(".ape") || lower.endsWith(".wma") || lower.endsWith(".opus") ||
                lower.endsWith(".m4b")
    }

    fun playOrPauseMusic() {
        if (isUsingLegacyPlayer && legacyPlayer != null) {
            val player = legacyPlayer ?: return
            if (player.isPlaying) {
                if (AudioOutputLossManager.ignoresPause()) return
                isPlayingActive = false
                saveAudiobookBookmarkIfNeeded()
                player.pause()
                stopPeriodicPositionSaver()
                MainActivity.instance?.isPausedByHand = true
                savePlaybackState(sync = true)
            } else {
                AudioSources.app.musicStarts()
                isPlayingActive = true
                player.start()
                startPeriodicPositionSaver()
                MainActivity.instance?.isPausedByHand = false
                savePlaybackPositionOnly(sync = false)
            }
        } else if (exoPlayer != null) {
            val player = exoPlayer ?: return
            if (player.playWhenReady) {
                if (AudioOutputLossManager.ignoresPause()) return
                isPlayingActive = false
                saveAudiobookBookmarkIfNeeded()
                player.playWhenReady = false
                stopPeriodicPositionSaver()
                MainActivity.instance?.isPausedByHand = true
                savePlaybackState(sync = true)
            } else {
                isPlayingActive = true
                player.playWhenReady = true
                startPeriodicPositionSaver()
                MainActivity.instance?.isPausedByHand = false
                savePlaybackPositionOnly(sync = false)
            }
        }
        MainActivity.instance?.updatePlayerUI()
    }

    /**
     * Stop: pause and go back to the start of the current track. The queue is kept.
     * Audiobooks and podcasts keep their position (bookmark), so for them Stop only pauses.
     */
    fun stopMusic() {
        val main = MainActivity.instance
        val keepPosition = main != null && main.currentIndex in main.currentPlaylist.indices &&
            tracksBookmark(main.currentPlaylist[main.currentIndex].absolutePath, main)
        try {
            if (isUsingLegacyPlayer && legacyPlayer != null) {
                val player = legacyPlayer ?: return
                if (player.isPlaying) {
                    saveAudiobookBookmarkIfNeeded()
                    player.pause()
                }
                if (!keepPosition) player.seekTo(0)
            } else if (exoPlayer != null) {
                val player = exoPlayer ?: return
                if (player.playWhenReady) {
                    saveAudiobookBookmarkIfNeeded()
                    player.playWhenReady = false
                }
                if (!keepPosition) player.seekTo(0)
            } else {
                return
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioPlayerManager", "Failed to stop: ${e.message}")
        }
        isPlayingActive = false
        stopPeriodicPositionSaver()
        main?.isPausedByHand = true
        savePlaybackState(sync = true)
        main?.updatePlayerUI()
    }

    fun nextTrack() {
        saveAudiobookBookmarkIfNeeded()
        val main = MainActivity.instance ?: return
        if (main.currentPlaylist.isEmpty()) return

        if (isGaplessQueueActive && exoPlayer != null) {
            val exo = exoPlayer ?: return
            if (exo.hasNext()) {
                exo.next()
            } else {
                exo.seekTo(0, 0)
            }
            return
        }

        main.currentIndex = (main.currentIndex + 1) % main.currentPlaylist.size
        prepareMusicTrack(main.currentIndex)
        main.updatePlayerUI()
    }

    fun prevTrack() {
        saveAudiobookBookmarkIfNeeded()
        val main = MainActivity.instance ?: return
        if (main.currentPlaylist.isEmpty()) return

        if (isGaplessQueueActive && exoPlayer != null) {
            val exo = exoPlayer ?: return
            if (exo.hasPrevious()) {
                exo.previous()
            } else {
                exo.seekTo(main.currentPlaylist.size - 1, 0)
            }
            return
        }

        main.currentIndex--
        if (main.currentIndex < 0) main.currentIndex = main.currentPlaylist.size - 1
        prepareMusicTrack(main.currentIndex)
        main.updatePlayerUI()
    }

    fun seekRelative(offsetMs: Int, savePosition: Boolean = true) {
        val currentPos = getCurrentPosition().toLong()
        val duration = getDuration().toLong()
        var targetPos = currentPos + offsetMs
        if (targetPos < 0) targetPos = 0
        if (targetPos > duration && duration > 0) targetPos = duration

        if (isUsingLegacyPlayer && legacyPlayer != null) {
            legacyPlayer?.seekTo(targetPos.toInt())
        } else if (exoPlayer != null) {
            exoPlayer?.seekTo(targetPos)
        }
        if (savePosition) savePlaybackPositionOnly(sync = true)
    }

    fun prepareMusicTrack(index: Int, initialSeekMs: Long = 0L, initialDurationMs: Long = 0L) {
        val main = MainActivity.instance ?: return
        if (main.currentPlaylist.isEmpty()) return

        val track = main.currentPlaylist[index]
        currentlyPlayingFilePath = track.absolutePath
        main.lastAlbumArtBytes = null

        if (!track.exists() || track.length() < 1024) {
            val size = if (track.exists()) track.length() else -1L
            android.util.Log.e("AudioPlayerManager", "Track is missing or corrupt (<1024 bytes, size=$size): ${track.absolutePath}")
            main.tvPlayerTitle.text = "Corrupted File"
            main.tvPlayerArtist.text = "Skipping..."
            main.tvPlayerAlbum.text = ""
            main.ivAlbumArt.setImageResource(R.drawable.default_album)

            main.consecutiveErrorCount++
            if (main.consecutiveErrorCount >= main.currentPlaylist.size) {
                Notice.error(main, "All tracks failed. Playback stopped.")
                main.isPausedByHand = true
                main.updatePlayerUI()
                main.consecutiveErrorCount = 0
            } else {
                Notice.error(main, "Corrupted file detected. Skipping...")
                Handler(Looper.getMainLooper()).postDelayed({ nextTrack() }, 1500)
            }
            return
        }

        main.tvPlayerTitle.text = track.name
        main.tvPlayerArtist.text = "Loading..."
        main.tvPlayerAlbum.text = ""
        main.ivAlbumArt.setImageResource(R.drawable.default_album)
        val isAudiobookOrPodcast = main.isAudiobookLibraryMode ||
                StoragePaths.isUnderAudiobooks(track.absolutePath) ||
                track.absolutePath.contains("/Audiobooks", ignoreCase = true) ||
                track.absolutePath.contains("/Podcasts", ignoreCase = true) ||
                track.absolutePath.startsWith("/PODCAST_STREAM")

        val effectiveSeekMs = if (initialSeekMs > 0) {
            initialSeekMs
        } else if (isAudiobookOrPodcast) {
            main.prefs.getInt("book_pos_" + track.absolutePath, 0).toLong()
        } else {
            0L
        }

        val effectiveDurMs = if (initialDurationMs > 0) {
            initialDurationMs
        } else if (isAudiobookOrPodcast) {
            main.prefs.getInt("book_dur_" + track.absolutePath, 0).toLong()
        } else {
            0L
        }

        if (effectiveSeekMs > 0) {
            main.tvPlayerTimeCurrent.text = TimeFormat.format(effectiveSeekMs)
            if (effectiveDurMs > 0) {
                main.tvPlayerTimeTotal.text = main.playerController.rightTimeText(effectiveSeekMs.toInt(), effectiveDurMs.toInt())
                main.playerProgress.progress = ((effectiveSeekMs * 1000) / effectiveDurMs).toInt()
            } else {
                main.tvPlayerTimeTotal.text = main.playerController.rightTimeText(0, 0)
                main.playerProgress.progress = 0
            }
        } else {
            main.playerProgress.progress = 0
            main.tvPlayerTimeCurrent.text = "0:00"
            main.tvPlayerTimeTotal.text = main.playerController.rightTimeText(0, 0)
        }

        val ext = track.name.lowercase(Locale.US)
        val isFlac = ext.endsWith(".flac")
        isUsingLegacyPlayer = isFlac && !flacLegacyFailedPaths.contains(track.absolutePath)

        var t: String? = null
        var a: String? = null
        var album: String? = null
        main.lastAlbumArtBytes = null

        var safeFileName = track.name
        val safeFileNameDot = safeFileName.lastIndexOf(".")
        if (safeFileNameDot > 0) {
            safeFileName = safeFileName.substring(0, safeFileNameDot)
        }
        val coverFile = File(StoragePaths.getCoversDir(), "$safeFileName.jpg")

        val isPodcast = track.absolutePath.contains("/Podcasts/")
        var podcastCoverFile: File? = null
        if (isPodcast) {
            val podcastFolder = track.parentFile
            podcastCoverFile = File(podcastFolder, "cover.jpg")
        }

        try {
            // Title, artist and album from the library, so that a track change does not wait for a file read.
            // Only a file that is not in the library (for example a podcast episode) is read here, without its cover.
            val song = LibrarySongs.byPath(track.absolutePath)
            if (song != null) {
                t = song.title
                a = song.artist.takeUnless { it == "Unknown Artist" || it == "Unknown Author" }
                album = song.album.takeUnless { it == "Unknown Album" || it == "Unknown Book" }
            } else {
                val tags = readTrackTags(track, includeArt = false)
                t = tags.title
                a = tags.artist
                album = tags.album
            }

            if (main.prefs.contains("meta_title_" + track.absolutePath)) {
                t = main.prefs.getString("meta_title_" + track.absolutePath, t)
                a = main.prefs.getString("meta_artist_" + track.absolutePath, a)
            }

            if (a.isNullOrBlank() || a.equals("Unknown Artist", ignoreCase = true) || a.equals("Unknown Author", ignoreCase = true)) {
                try {
                    val parentName = track.parentFile?.parentFile?.name
                    val folderName = track.parentFile?.name

                    if (parentName != null && parentName != "Music" && parentName != "Audiobooks" &&
                        !StoragePaths.isStorageVolumeName(parentName) && parentName != "Y1_Playlists"
                    ) {
                        a = parentName
                    } else if (folderName != null && folderName != "Music" && folderName != "Audiobooks" &&
                        !StoragePaths.isStorageVolumeName(folderName) && folderName != "Y1_Playlists"
                    ) {
                        a = folderName
                    }
                } catch (ignored: Exception) {}
            }

            if (!t.isNullOrBlank()) main.tvPlayerTitle.text = t
            else main.tvPlayerTitle.text = safeFileName

            if (!a.isNullOrBlank()) main.tvPlayerArtist.text = a
            else main.tvPlayerArtist.text = if (track.absolutePath.contains("/Audiobooks") || main.isAudiobookLibraryMode) main.t("Unknown Author") else main.t("Unknown Artist")

            main.tvPlayerAlbum.text = if (!album.isNullOrBlank()) album.trim() else ""

            pendingScrobbleArtist = a
            pendingScrobbleTitle = if (!t.isNullOrBlank()) t else safeFileName
            pendingScrobbleAlbum = album
            pendingScrobblePath = track.absolutePath
        } catch (ignored: Throwable) {}

        // The cover is read and decoded on the art thread, and shows here if the track is still the same
        loadTrackArt(main, artRequests.start(track.absolutePath), track) { art, later ->
            main.lastAlbumArtBytes = art.bytes
            offerWidgetArt(main, art)
            var artApplied = false
            if (main.lastAlbumArtBytes != null && (main.lastAlbumArtBytes?.size ?: 0) > 0) {
                try {
                    val bmpCenter = art.embedded
                    if (bmpCenter != null) {
                        main.ivAlbumArt.setImageBitmap(bmpCenter)
                        main.updateMainMenuBackground()
                        main.refreshNowPlayingPreview()
                        artApplied = true
                    }
                } catch (ignored: Throwable) {}
            }

            if (!artApplied) {
                if (isPodcast && podcastCoverFile != null && podcastCoverFile.exists()) {
                    try {
                        val bmp = art.podcastCover
                        if (bmp != null) {
                            main.ivAlbumArt.setImageBitmap(bmp)
                            main.updateMainMenuBackground()
                            main.refreshNowPlayingPreview()
                            artApplied = true
                        }
                    } catch (e: Exception) {
                        main.applyCachedCoverArt(podcastCoverFile.absolutePath)
                        artApplied = true
                    }
                } else if (coverFile.exists()) {
                    showCoverFile(main, art)
                    artApplied = true
                } else {
                    val folderCover = art.folderCover
                    if (folderCover != null && folderCover.exists()) {
                        showCoverFile(main, art)
                        artApplied = true
                    } else {
                        main.ivAlbumArt.setImageResource(R.drawable.default_album)
                        main.updateMainMenuBackground()
                        main.refreshNowPlayingPreview()
                    }
                }
            }
            // The art reflection, and the cover in the AVRCP track data
            // (A cover from the cache shows before the caller updates the player UI)
            if (artApplied && later) main.updatePlayerUI()
        }

        try {
            if (isUsingLegacyPlayer) {
                exoPlayer?.let {
                    it.stop()
                    it.clearMediaItems()
                }

                val player = legacyPlayer ?: MediaPlayer().apply {
                    setWakeMode(main.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                    legacyPlayer = this
                }
                legacyPlayerPath = null
                player.reset()

                player.setOnCompletionListener { handleTrackCompletion() }
                player.setOnErrorListener { _, what, extra ->
                    if (isFlac && flacLegacyFailedPaths.add(track.absolutePath)) {
                        prepareMusicTrack(index)
                    } else {
                        handleTrackError("Legacy Player Error: $what (extra $extra)")
                    }
                    true
                }

                currentFileInputStream?.let {
                    try { it.close() } catch (ignored: Exception) {}
                }
                currentFileInputStream = FileInputStream(track)
                player.setDataSource(currentFileInputStream!!.fd)
                player.prepare()
                legacyPlayerPath = track.absolutePath

                if (effectiveSeekMs > 0) {
                    player.seekTo(effectiveSeekMs.toInt())
                }

                if (!main.isPausedByHand) {
                    AudioSources.app.musicStarts()
                    player.start()
                    startPeriodicPositionSaver()
                } else {
                    stopPeriodicPositionSaver()
                }
                applyMasterVolume()

                AudioEffectManager.getInstance().applyAudioEffects()
                main.setupVisualizer()

                val duration = player.duration
                main.tvPlayerTimeTotal.text = main.playerController.rightTimeText(player.currentPosition, duration)
                if (!main.isPausedByHand) {
                    LastFmScrobbler.getInstance(main).onTrackStart(
                        pendingScrobbleArtist,
                        pendingScrobbleTitle,
                        pendingScrobbleAlbum,
                        duration.toLong(),
                        pendingScrobblePath
                    )
                }
            } else if (isGaplessQueueActive) {
                exoPlayer?.setPlaybackParameters(PlaybackParameters(currentSpeed, 1.0f))
            } else {
                legacyPlayer?.let {
                    it.stop()
                    it.reset()
                }

                if (exoPlayer == null) {
                    initPlayer(main.applicationContext)
                } else {
                    exoPlayer?.stop()
                }

                val mediaItem = MediaItem.fromUri(Uri.fromFile(track))
                val dataSourceFactory = DefaultDataSourceFactory(main, Util.getUserAgent(main, "Y1_Launcher"))
                val extractorsFactory = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory).createMediaSource(mediaItem)

                exoPlayer?.setMediaSource(mediaSource)
                exoPlayer?.prepare()

                val isShuffle = Prefs.SHUFFLE.get(main.prefs)
                exoPlayer?.shuffleModeEnabled = isShuffle

                if (effectiveSeekMs > 0) {
                    exoPlayer?.seekTo(effectiveSeekMs)
                }

                exoPlayer?.setPlaybackParameters(PlaybackParameters(currentSpeed, 1.0f))

                if (!main.isPausedByHand) {
                    exoPlayer?.playWhenReady = true
                    startPeriodicPositionSaver()
                } else {
                    exoPlayer?.playWhenReady = false
                    stopPeriodicPositionSaver()
                }
                applyMasterVolume()
            }

            main.consecutiveErrorCount = 0
            main.tvPlayerTrackCount.text = "${index + 1} of ${main.currentPlaylist.size}"
            if (!isRestoringPlaybackState) {
                savePlaybackState(sync = false)
            }
        } catch (e: Throwable) {
            main.consecutiveErrorCount++
            val failReason = when (e) {
                is OutOfMemoryError -> "Album Art is too huge!"
                is java.io.FileNotFoundException -> "File not found"
                is java.io.IOException -> "Broken file"
                else -> "Unknown Error"
            }

            main.tvPlayerTitle.text = "Load Failed"
            main.tvPlayerArtist.text = failReason
            main.tvPlayerAlbum.text = ""
            Notice.error(main, failReason)

            if (main.consecutiveErrorCount >= main.currentPlaylist.size) {
                main.isPausedByHand = true
                main.updatePlayerUI()
                main.consecutiveErrorCount = 0
            } else {
                Handler(Looper.getMainLooper()).postDelayed({ nextTrack() }, 2000)
            }
        }
    }

    /** Title, artist and album of a file, and with [includeArt] its embedded cover. Any thread. */
    internal class TrackTags(val title: String?, val artist: String?, val album: String?, val art: ByteArray?)

    internal fun readTrackTags(track: File, includeArt: Boolean): TrackTags {
        val name = track.name.lowercase(Locale.US)
        val tags = when {
            name.endsWith(".opus") -> extractOpusMetadata(track, includeArt)
            name.endsWith(".flac") -> extractFlacMetadata(track, includeArt)
            name.endsWith(".m4a") || name.endsWith(".alac") -> extractAlacMetadata(track, includeArt)
            name.endsWith(".ogg") -> extractOggVorbisMetadata(track, includeArt)
            else -> null
        }
        if (tags != null) {
            return TrackTags(tags[0] as? String, tags[1] as? String, tags[2] as? String, if (includeArt) tags[5] as? ByteArray else null)
        }
        var mmr: MediaMetadataRetriever? = null
        var fis: FileInputStream? = null
        return try {
            mmr = MediaMetadataRetriever()
            fis = FileInputStream(track)
            mmr.setDataSource(fis.fd)
            var artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            if (artist.isNullOrEmpty()) {
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            }
            TrackTags(
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist,
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                if (includeArt) mmr.embeddedPicture else null
            )
        } catch (ignored: Throwable) {
            TrackTags(null, null, null, null)
        } finally {
            try { fis?.close() } catch (ignored: Throwable) {}
            try { mmr?.release() } catch (ignored: Throwable) {}
        }
    }

    /** True while the cover of a new track is still loading (Now Playing shows the default cover). */
    fun isArtLoading(): Boolean = artRequests.isLoading

    /**
     * The cover of a track, read and decoded on the art thread, in the order that prepareMusicTrack uses:
     * the embedded cover ([bytes], [embedded]), the podcast cover, the cached cover or the folder cover ([fileCover]).
     */
    internal class TrackArt {
        var bytes: ByteArray? = null
        var embedded: Bitmap? = null
        var podcastCover: Bitmap? = null
        var folderCover: File? = null
        var fileCover: Bitmap? = null
        var fileBytes: ByteArray? = null

        /** A cover to show; without one the cache keeps nothing (a cover file can come later). */
        val isFound: Boolean get() = embedded != null || podcastCover != null || fileCover != null
    }

    /**
     * Shows the cover of [track]: at once from the cache of the neighbour tracks, else read and decoded on the
     * art thread and shown on the main thread if the track is still the same ([show] gets later = true).
     * Until then Now Playing shows the default cover.
     */
    private fun loadTrackArt(main: MainActivity, ticket: TrackArtRequests.Ticket, track: File, show: (TrackArt, Boolean) -> Unit) {
        scheduleCoverPrefetch()
        val cached = neighbourCovers.get(ticket.path)
        if (cached != null) {
            artRequests.finish(ticket)
            show(cached, false)
            return
        }
        artExecutor.execute {
            // A newer track came while this one waited: do not read its cover
            if (!artRequests.isCurrent(ticket)) return@execute
            val art = TrackArt()
            try {
                readTrackArt(main, art, track)
            } catch (ignored: Throwable) {}
            if (art.isFound) neighbourCovers.put(ticket.path, art)
            main.runOnUiThread {
                if (!artRequests.finish(ticket) || currentlyPlayingFilePath != ticket.path) return@runOnUiThread
                if (MainActivity.instance !== main) return@runOnUiThread
                try {
                    show(art, true)
                } catch (ignored: Throwable) {}
            }
        }
    }

    /** Reads the covers of the neighbour tracks 1 s after the last track change (a fast run of skips waits). */
    private fun scheduleCoverPrefetch() {
        val run = ++coverPrefetchRun
        coverPrefetchHandler.removeCallbacksAndMessages(null)
        coverPrefetchHandler.postDelayed({ prefetchNeighbourCovers(run) }, NeighbourCovers.PREFETCH_DELAY_MS)
    }

    private fun prefetchNeighbourCovers(run: Int) {
        val main = MainActivity.instance ?: return
        val queue = main.currentPlaylist
        val window = NeighbourCovers.window(main.currentIndex, queue.size, NeighbourCovers.RADIUS).map { queue[it] }
        neighbourCovers.keepOnly(window.map { it.absolutePath })
        for (track in window) {
            if (neighbourCovers.contains(track.absolutePath)) continue
            coverPrefetch.execute {
                // A track change came: its own prefetch reads the covers of its neighbours
                if (run != coverPrefetchRun) return@execute
                val art = TrackArt()
                try {
                    readTrackArt(main, art, track)
                } catch (ignored: Throwable) {}
                if (art.isFound && run == coverPrefetchRun) neighbourCovers.put(track.absolutePath, art)
            }
        }
    }

    /** Any thread: the cover of [track], in the order that prepareMusicTrack shows it. */
    private fun readTrackArt(main: MainActivity, art: TrackArt, track: File) {
        art.bytes = readTrackTags(track, includeArt = true).art
        val bytes = art.bytes
        if (bytes != null && bytes.isNotEmpty()) {
            try {
                val optsCenter = BitmapFactory.Options().apply { inSampleSize = 2 }
                art.embedded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, optsCenter)
            } catch (ignored: Throwable) {}
            if (art.embedded != null) return
        }

        var safeFileName = track.name
        val safeFileNameDot = safeFileName.lastIndexOf(".")
        if (safeFileNameDot > 0) safeFileName = safeFileName.substring(0, safeFileNameDot)
        val coverFile = File(StoragePaths.getCoversDir(), "$safeFileName.jpg")
        val podcastCoverFile = if (track.absolutePath.contains("/Podcasts/")) File(track.parentFile, "cover.jpg") else null

        if (podcastCoverFile != null && podcastCoverFile.exists()) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(podcastCoverFile.absolutePath, opts)

            var scale = 1
            while (opts.outWidth / scale > 500 || opts.outHeight / scale > 500) {
                scale *= 2
            }

            opts.inJustDecodeBounds = false
            opts.inSampleSize = scale
            art.podcastCover = BitmapFactory.decodeFile(podcastCoverFile.absolutePath, opts)
        } else if (coverFile.exists()) {
            readCoverFile(art, coverFile)
        } else {
            art.folderCover = main.findFolderCover(track.parentFile)
            art.folderCover?.let { if (it.exists()) readCoverFile(art, it) }
        }
    }

    /**
     * Main thread: the home-screen widgets get the cover bytes of [art] (the embedded cover or the cover file) with the
     * bitmap that Now Playing shows, in place of a second decode on the main thread. Their own (larger) bitmap is decoded
     * on the prefetch thread when the track change settles, and replaces it if the cover is still the same.
     */
    private fun offerWidgetArt(main: MainActivity, art: TrackArt) {
        val hwc = main.homeWidgetsController ?: return
        val (bytes, shown) = when {
            art.embedded != null -> art.bytes to art.embedded
            art.podcastCover == null && art.fileCover != null -> art.fileBytes to art.fileCover
            else -> return
        }
        if (bytes == null || bytes.isEmpty() || shown == null) return
        hwc.offerDecodedArt(bytes, shown)
        val run = coverPrefetchRun
        coverPrefetchHandler.postDelayed({
            coverPrefetch.execute {
                // A track change came: the widgets get the cover of the new track
                if (run != coverPrefetchRun) return@execute
                val bmp = com.themoon.y1.controllers.home.HomeWidgetsController.decodeAlbumArtSmart(bytes) ?: return@execute
                main.runOnUiThread { main.homeWidgetsController?.setDecodedArt(bytes, bmp) }
            }
        }, NeighbourCovers.PREFETCH_DELAY_MS)
    }

    /** Art thread: a cover image file, as MainActivity.applyCachedCoverArt reads it. */
    private fun readCoverFile(art: TrackArt, file: File) {
        art.fileCover = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return
        art.fileBytes = try { file.readBytes() } catch (ignored: Exception) { null }
    }

    /** Main thread: a cover file that the art thread read, as MainActivity.applyCachedCoverArt shows it. */
    private fun showCoverFile(main: MainActivity, art: TrackArt) {
        val bmp = art.fileCover ?: return
        main.ivAlbumArt.setImageBitmap(bmp)
        main.lastAlbumArtBytes = art.fileBytes
        main.refreshNowPlayingPreview()
        main.sendBluetoothMetaToCar()
    }

    /** Changes when the play queue or its order changes (a shuffle), not when only the track changes. */
    internal fun queueStamp(queue: List<File>, original: List<File>): List<Int> =
        listOf(queue.size, queue.hashCode(), original.size, original.hashCode())

    private fun isAudiobookPath(filePath: String, main: MainActivity): Boolean =
        StoragePaths.isUnderAudiobooks(filePath) || filePath.contains("/Audiobooks", ignoreCase = true) || main.isAudiobookLibraryMode

    /** Audiobooks and podcasts keep a bookmark of their position. */
    private fun tracksBookmark(filePath: String, main: MainActivity): Boolean =
        isAudiobookPath(filePath, main) || filePath.contains("/Podcasts") || filePath.startsWith("/PODCAST_STREAM")

    private fun saveAudiobookBookmarkIfNeeded() {
        try {
            val main = MainActivity.instance
            if (main != null && main.currentPlaylist.isNotEmpty()) {
                if (main.currentIndex in 0 until main.currentPlaylist.size) {
                    val filePath = main.currentPlaylist[main.currentIndex].absolutePath
                    val isAudiobook = isAudiobookPath(filePath, main)
                    if (tracksBookmark(filePath, main)) {
                        val editor = main.prefs.edit()
                            .putInt("book_pos_$filePath", getCurrentPosition())
                            .putInt("book_dur_$filePath", getDuration())
                        if (isAudiobook) {
                            editor.putString("last_played_audiobook_path", filePath)
                        }
                        editor.apply()
                    }
                }
            }
        } catch (ignored: Exception) {}
    }

    fun getCurrentPosition(): Int {
        if (isUsingLegacyPlayer && legacyPlayer != null) return legacyPlayer?.currentPosition ?: 0
        if (!isUsingLegacyPlayer && exoPlayer != null) {
            val pos = exoPlayer?.currentPosition ?: 0
            return if (pos < 0) 0 else pos.toInt()
        }
        return 0
    }

    fun getDuration(): Int {
        if (isUsingLegacyPlayer && legacyPlayer != null) return legacyPlayer?.duration ?: 0
        if (!isUsingLegacyPlayer && exoPlayer != null) {
            val duration = exoPlayer?.duration ?: 0
            return if (duration < 0) 0 else duration.toInt()
        }
        return 0
    }

    /**
     * The duration of the file at [path] in ms, or 0 when the player does not know it. During a track
     * change the player can still hold the previous file; then it is 0, not the previous duration.
     */
    fun getDurationOf(path: String): Int {
        if (isUsingLegacyPlayer) return if (legacyPlayerPath == path) getDuration() else 0
        val uri = exoPlayer?.currentMediaItem?.playbackProperties?.uri ?: return 0
        // A podcast stream has a web address, not the "/PODCAST_STREAM/..." name of the playlist.
        val isStream = path.startsWith("/PODCAST_STREAM")
        val same = if (isStream) uri.scheme != "file" else uri.path == path
        return if (same) getDuration() else 0
    }

    fun isPlaying(): Boolean {
        val mainLooper = Looper.getMainLooper()
        if (mainLooper != null && Looper.myLooper() == mainLooper) {
            val playing = if (isUsingLegacyPlayer && legacyPlayer != null) {
                legacyPlayer?.isPlaying == true
            } else if (!isUsingLegacyPlayer && exoPlayer != null) {
                exoPlayer?.playWhenReady == true
            } else {
                false
            }
            isPlayingActive = playing
            return playing
        }
        return isPlayingActive
    }

    fun isFileCurrentlyPlaying(file: File?): Boolean {
        if (file == null) return false
        if (!isPlaying()) return false
        val currentPath = currentlyPlayingFilePath ?: MainActivity.instance?.let { main ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                main.currentPlaylist.getOrNull(main.currentIndex)?.absolutePath
            } else {
                synchronized(main.currentPlaylist) {
                    main.currentPlaylist.getOrNull(main.currentIndex)?.absolutePath
                }
            }
        }
        return currentPath != null && currentPath == file.absolutePath
    }

    fun getAudioSessionId(): Int {
        if (isUsingLegacyPlayer && legacyPlayer != null) return legacyPlayer?.audioSessionId ?: 0
        if (!isUsingLegacyPlayer && exoPlayer != null) return exoPlayer?.audioSessionId ?: 0
        return 0
    }

    fun releasePlayer() {
        isPlayingActive = false
        currentlyPlayingFilePath = null
        stopPeriodicPositionSaver()
        exoPlayer?.release()
        exoPlayer = null
        legacyPlayer?.release()
        legacyPlayer = null
        try {
            currentFileInputStream?.close()
        } catch (ignored: Exception) {}
        currentFileInputStream = null
    }

    fun extractOpusMetadata(file: File): Array<Any?> = extractOpusMetadata(file, true)

    fun extractOpusMetadata(file: File, includeArt: Boolean): Array<Any?> {
        val tags = arrayOfNulls<Any>(11)
        try {
            val fis = FileInputStream(file)
            val bos = ByteArrayOutputStream()
            val header = ByteArray(27)
            var totalRead = 0

            while (totalRead < 1500000 && fis.read(header) == 27) {
                if (header[0] != 'O'.code.toByte() || header[1] != 'g'.code.toByte() || header[2] != 'g'.code.toByte() || header[3] != 'S'.code.toByte()) break

                val pageSegments = header[26].toInt() and 0xFF
                val segmentTable = ByteArray(pageSegments)
                fis.read(segmentTable)

                var pageSize = 0
                for (i in 0 until pageSegments) pageSize += (segmentTable[i].toInt() and 0xFF)

                val pageData = ByteArray(pageSize)
                val read = fis.read(pageData)
                if (read > 0) bos.write(pageData, 0, read)

                totalRead += (27 + pageSegments + pageSize)
            }
            fis.close()

            val buffer = bos.toByteArray()
            val magic = "OpusTags".toByteArray(Charsets.UTF_8)
            var p = -1
            for (i in 0..(buffer.size - magic.size)) {
                var match = true
                for (j in magic.indices) {
                    if (buffer[i + j] != magic[j]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    p = i
                    break
                }
            }

            if (p != -1) {
                p += 8
                val vendorLen = (buffer[p].toInt() and 0xFF) or
                        ((buffer[p + 1].toInt() and 0xFF) shl 8) or
                        ((buffer[p + 2].toInt() and 0xFF) shl 16) or
                        ((buffer[p + 3].toInt() and 0xFF) shl 24)
                p += 4 + vendorLen

                val commentsCount = (buffer[p].toInt() and 0xFF) or
                        ((buffer[p + 1].toInt() and 0xFF) shl 8) or
                        ((buffer[p + 2].toInt() and 0xFF) shl 16) or
                        ((buffer[p + 3].toInt() and 0xFF) shl 24)
                p += 4

                var i = 0
                while (i < commentsCount && p < buffer.size - 4) {
                    val commentLen = (buffer[p].toInt() and 0xFF) or
                            ((buffer[p + 1].toInt() and 0xFF) shl 8) or
                            ((buffer[p + 2].toInt() and 0xFF) shl 16) or
                            ((buffer[p + 3].toInt() and 0xFF) shl 24)
                    p += 4
                    if (commentLen <= 0 || p + commentLen > buffer.size) break

                    val comment = String(buffer, p, commentLen, Charsets.UTF_8)
                    p += commentLen
                    val upper = comment.uppercase(Locale.US)

                    if (upper.startsWith("TITLE=")) {
                        tags[0] = comment.substring(6)
                    } else if (upper.startsWith("ARTIST=") || upper.startsWith("AUTHOR=")) {
                        tags[1] = comment.substring(comment.indexOf("=") + 1)
                    } else if (upper.startsWith("ALBUM=")) {
                        tags[2] = comment.substring(6)
                    } else if (upper.startsWith("DATE=") || upper.startsWith("YEAR=")) {
                        tags[3] = comment.substring(comment.indexOf("=") + 1)
                    } else if (upper.startsWith("GENRE=")) {
                        val g = comment.substring(6).trim()
                        if (g.isNotEmpty()) {
                            val existing = tags[4] as? String
                            tags[4] = if (existing.isNullOrBlank()) g else "$existing; $g"
                        }
                    } else if (upper.startsWith("TRACKNUMBER=") || upper.startsWith("TRACKNUM=")) {
                        tags[6] = comment.substring(comment.indexOf("=") + 1)
                    } else if (upper.startsWith("ALBUMARTIST=") || upper.startsWith("ALBUM_ARTIST=")) {
                        tags[8] = comment.substring(comment.indexOf("=") + 1)
                    } else if (upper.startsWith("COMPOSER=")) {
                        tags[9] = comment.substring(comment.indexOf("=") + 1)
                    } else if (upper.startsWith("COMPILATION=")) {
                        tags[10] = comment.substring(comment.indexOf("=") + 1)
                    } else if (includeArt && upper.startsWith("METADATA_BLOCK_PICTURE=")) {
                        try {
                            val base64Data = comment.substring(23).replace("\\s".toRegex(), "")
                            val flacPic = Base64.decode(base64Data, Base64.DEFAULT)

                            var ptr = 4
                            val mimeLen = ((flacPic[ptr].toInt() and 0xFF) shl 24) or
                                    ((flacPic[ptr + 1].toInt() and 0xFF) shl 16) or
                                    ((flacPic[ptr + 2].toInt() and 0xFF) shl 8) or
                                    (flacPic[ptr + 3].toInt() and 0xFF)
                            ptr += 4 + mimeLen

                            val descLen = ((flacPic[ptr].toInt() and 0xFF) shl 24) or
                                    ((flacPic[ptr + 1].toInt() and 0xFF) shl 16) or
                                    ((flacPic[ptr + 2].toInt() and 0xFF) shl 8) or
                                    (flacPic[ptr + 3].toInt() and 0xFF)
                            ptr += 4 + descLen
                            ptr += 16

                            val picDataLen = ((flacPic[ptr].toInt() and 0xFF) shl 24) or
                                    ((flacPic[ptr + 1].toInt() and 0xFF) shl 16) or
                                    ((flacPic[ptr + 2].toInt() and 0xFF) shl 8) or
                                    (flacPic[ptr + 3].toInt() and 0xFF)
                            ptr += 4

                            if (ptr + picDataLen <= flacPic.size) {
                                val img = ByteArray(picDataLen)
                                System.arraycopy(flacPic, ptr, img, 0, picDataLen)
                                tags[5] = img
                            }
                        } catch (ignored: Exception) {}
                    }
                    i++
                }
            }
        } catch (ignored: Exception) {}
        return tags
    }

    fun extractOggVorbisMetadata(file: File): Array<Any?> = extractOggVorbisMetadata(file, true)

    fun extractOggVorbisMetadata(file: File, includeArt: Boolean): Array<Any?> {
        val tags = arrayOfNulls<Any>(11)
        try {
            val fis = FileInputStream(file)
            val bos = ByteArrayOutputStream()
            val header = ByteArray(27)
            var totalRead = 0

            while (totalRead < 1500000 && fis.read(header) == 27) {
                if (header[0] != 'O'.code.toByte() || header[1] != 'g'.code.toByte() || header[2] != 'g'.code.toByte() || header[3] != 'S'.code.toByte()) break

                val pageSegments = header[26].toInt() and 0xFF
                val segmentTable = ByteArray(pageSegments)
                fis.read(segmentTable)

                var pageSize = 0
                for (i in 0 until pageSegments) pageSize += (segmentTable[i].toInt() and 0xFF)

                val pageData = ByteArray(pageSize)
                val read = fis.read(pageData)
                if (read > 0) bos.write(pageData, 0, read)

                totalRead += (27 + pageSegments + pageSize)
            }
            fis.close()

            val buffer = bos.toByteArray()
            val opusMagic = "OpusHead".toByteArray(Charsets.UTF_8)
            if (buffer.size >= opusMagic.size) {
                var isActuallyOpus = true
                for (j in opusMagic.indices) {
                    if (buffer[j] != opusMagic[j]) {
                        isActuallyOpus = false
                        break
                    }
                }
                if (isActuallyOpus) {
                    return extractOpusMetadata(file, includeArt)
                }
            }

            val magic = byteArrayOf(0x03, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
            var p = -1
            for (i in 0..(buffer.size - magic.size)) {
                var match = true
                for (j in magic.indices) {
                    if (buffer[i + j] != magic[j]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    p = i
                    break
                }
            }

            if (p != -1) {
                p += magic.size
                val vendorLen = (buffer[p].toInt() and 0xFF) or
                        ((buffer[p + 1].toInt() and 0xFF) shl 8) or
                        ((buffer[p + 2].toInt() and 0xFF) shl 16) or
                        ((buffer[p + 3].toInt() and 0xFF) shl 24)
                p += 4 + vendorLen

                val commentsCount = (buffer[p].toInt() and 0xFF) or
                        ((buffer[p + 1].toInt() and 0xFF) shl 8) or
                        ((buffer[p + 2].toInt() and 0xFF) shl 16) or
                        ((buffer[p + 3].toInt() and 0xFF) shl 24)
                p += 4

                var i = 0
                while (i < commentsCount && p < buffer.size - 4) {
                    val commentLen = (buffer[p].toInt() and 0xFF) or
                            ((buffer[p + 1].toInt() and 0xFF) shl 8) or
                            ((buffer[p + 2].toInt() and 0xFF) shl 16) or
                            ((buffer[p + 3].toInt() and 0xFF) shl 24)
                    p += 4
                    if (commentLen <= 0 || p + commentLen > buffer.size) break

                    val comment = String(buffer, p, commentLen, Charsets.UTF_8)
                    p += commentLen
                    val upper = comment.uppercase(Locale.US)

                    if (upper.startsWith("TITLE=")) {
                        tags[0] = comment.substring(6)
                    } else if (upper.startsWith("ARTIST=") || upper.startsWith("AUTHOR=")) {
                        tags[1] = comment.substring(comment.indexOf("=") + 1)
                    } else if (upper.startsWith("ALBUM=")) {
                        tags[2] = comment.substring(6)
                    } else if (upper.startsWith("DATE=") || upper.startsWith("YEAR=")) {
                        tags[3] = comment.substring(comment.indexOf("=") + 1)
                    } else if (upper.startsWith("GENRE=")) {
                        val g = comment.substring(6).trim()
                        if (g.isNotEmpty()) {
                            val existing = tags[4] as? String
                            tags[4] = if (existing.isNullOrBlank()) g else "$existing; $g"
                        }
                    } else if (upper.startsWith("TRACKNUMBER=") || upper.startsWith("TRACKNUM=")) {
                        tags[6] = comment.substring(comment.indexOf("=") + 1)
                    } else if (upper.startsWith("ALBUMARTIST=") || upper.startsWith("ALBUM_ARTIST=")) {
                        tags[8] = comment.substring(comment.indexOf("=") + 1)
                    } else if (upper.startsWith("COMPOSER=")) {
                        tags[9] = comment.substring(comment.indexOf("=") + 1)
                    } else if (upper.startsWith("COMPILATION=")) {
                        tags[10] = comment.substring(comment.indexOf("=") + 1)
                    } else if (includeArt && upper.startsWith("METADATA_BLOCK_PICTURE=")) {
                        try {
                            val base64Data = comment.substring(23).replace("\\s".toRegex(), "")
                            val flacPic = Base64.decode(base64Data, Base64.DEFAULT)

                            var ptr = 4
                            val mimeLen = ((flacPic[ptr].toInt() and 0xFF) shl 24) or
                                    ((flacPic[ptr + 1].toInt() and 0xFF) shl 16) or
                                    ((flacPic[ptr + 2].toInt() and 0xFF) shl 8) or
                                    (flacPic[ptr + 3].toInt() and 0xFF)
                            ptr += 4 + mimeLen

                            val descLen = ((flacPic[ptr].toInt() and 0xFF) shl 24) or
                                    ((flacPic[ptr + 1].toInt() and 0xFF) shl 16) or
                                    ((flacPic[ptr + 2].toInt() and 0xFF) shl 8) or
                                    (flacPic[ptr + 3].toInt() and 0xFF)
                            ptr += 4 + descLen
                            ptr += 16

                            val picDataLen = ((flacPic[ptr].toInt() and 0xFF) shl 24) or
                                    ((flacPic[ptr + 1].toInt() and 0xFF) shl 16) or
                                    ((flacPic[ptr + 2].toInt() and 0xFF) shl 8) or
                                    (flacPic[ptr + 3].toInt() and 0xFF)
                            ptr += 4

                            if (ptr + picDataLen <= flacPic.size) {
                                val img = ByteArray(picDataLen)
                                System.arraycopy(flacPic, ptr, img, 0, picDataLen)
                                tags[5] = img
                            }
                        } catch (ignored: Exception) {}
                    } else if (includeArt && upper.startsWith("COVERART=")) {
                        try {
                            val base64Data = comment.substring(9).replace("\\s".toRegex(), "")
                            tags[5] = Base64.decode(base64Data, Base64.DEFAULT)
                        } catch (ignored: Exception) {}
                    }
                    i++
                }
            }
        } catch (ignored: Exception) {}
        return tags
    }

    fun extractFlacMetadata(file: File): Array<Any?> = extractFlacMetadata(file, true)

    fun extractFlacMetadata(file: File, includeArt: Boolean): Array<Any?> {
        val tags = arrayOfNulls<Any>(11)
        try {
            val raf = RandomAccessFile(file, "r")
            val header = ByteArray(4)
            raf.readFully(header)

            if (header[0] != 'f'.code.toByte() || header[1] != 'L'.code.toByte() || header[2] != 'a'.code.toByte() || header[3] != 'C'.code.toByte()) {
                raf.close()
                return tags
            }

            var isLast = false
            while (!isLast) {
                val blockHeader = raf.readUnsignedByte()
                isLast = (blockHeader and 0x80) != 0
                val blockType = blockHeader and 0x7F
                val length = (raf.readUnsignedByte() shl 16) or (raf.readUnsignedByte() shl 8) or raf.readUnsignedByte()

                if (blockType == 4) {
                    val commentData = ByteArray(length)
                    raf.readFully(commentData)
                    try {
                        var p = 0
                        val vendorLen = (commentData[p].toInt() and 0xFF) or
                                ((commentData[p + 1].toInt() and 0xFF) shl 8) or
                                ((commentData[p + 2].toInt() and 0xFF) shl 16) or
                                ((commentData[p + 3].toInt() and 0xFF) shl 24)
                        p += 4 + vendorLen
                        val listLen = (commentData[p].toInt() and 0xFF) or
                                ((commentData[p + 1].toInt() and 0xFF) shl 8) or
                                ((commentData[p + 2].toInt() and 0xFF) shl 16) or
                                ((commentData[p + 3].toInt() and 0xFF) shl 24)
                        p += 4
                        var i = 0
                        while (i < listLen && p < commentData.size - 4) {
                            val strLen = (commentData[p].toInt() and 0xFF) or
                                    ((commentData[p + 1].toInt() and 0xFF) shl 8) or
                                    ((commentData[p + 2].toInt() and 0xFF) shl 16) or
                                    ((commentData[p + 3].toInt() and 0xFF) shl 24)
                            p += 4
                            val comment = String(commentData, p, strLen, Charsets.UTF_8)
                            p += strLen
                            val upper = comment.uppercase(Locale.US)

                            if (upper.startsWith("TITLE=")) {
                                tags[0] = comment.substring(6)
                            } else if (upper.startsWith("ARTIST=") || upper.startsWith("AUTHOR=")) {
                                tags[1] = comment.substring(comment.indexOf("=") + 1)
                            } else if (upper.startsWith("ALBUM=")) {
                                tags[2] = comment.substring(6)
                            } else if (upper.startsWith("DATE=") || upper.startsWith("YEAR=")) {
                                tags[3] = comment.substring(comment.indexOf("=") + 1)
                            } else if (upper.startsWith("GENRE=")) {
                                val g = comment.substring(6).trim()
                                if (g.isNotEmpty()) {
                                    val existing = tags[4] as? String
                                    tags[4] = if (existing.isNullOrBlank()) g else "$existing; $g"
                                }
                            } else if (upper.startsWith("TRACKNUMBER=") || upper.startsWith("TRACKNUM=")) {
                                tags[6] = comment.substring(comment.indexOf("=") + 1)
                            } else if (upper.startsWith("LYRICS=")) {
                                tags[7] = comment.substring(7)
                            } else if (upper.startsWith("ALBUMARTIST=") || upper.startsWith("ALBUM_ARTIST=")) {
                                tags[8] = comment.substring(comment.indexOf("=") + 1)
                            } else if (upper.startsWith("COMPOSER=")) {
                                tags[9] = comment.substring(comment.indexOf("=") + 1)
                            } else if (upper.startsWith("COMPILATION=")) {
                                tags[10] = comment.substring(comment.indexOf("=") + 1)
                            }
                            i++
                        }
                    } catch (ignored: Exception) {}
                } else if (blockType == 6) {
                    if (includeArt) {
                        raf.readInt() // picType
                        val mimeLen = raf.readInt()
                        raf.skipBytes(mimeLen)
                        val descLen = raf.readInt()
                        raf.skipBytes(descLen)
                        raf.skipBytes(16)
                        val picDataLen = raf.readInt()
                        val picData = ByteArray(picDataLen)
                        raf.readFully(picData)
                        tags[5] = picData
                    } else {
                        raf.skipBytes(length)
                    }
                } else {
                    raf.skipBytes(length)
                }
            }
            raf.close()
        } catch (ignored: Exception) {}
        return tags
    }

    fun extractAlacMetadata(file: File): Array<Any?> = extractAlacMetadata(file, true)

    fun extractAlacMetadata(file: File, includeArt: Boolean): Array<Any?> {
        val tags = arrayOfNulls<Any>(11)
        try {
            val raf = RandomAccessFile(file, "r")
            val fileSize = raf.length()
            var pos = 0L
            val iso88591 = Charset.forName("ISO-8859-1")

            while (pos < fileSize) {
                raf.seek(pos)
                val size = raf.readInt()
                if (size <= 0) break

                val typeBytes = ByteArray(4)
                raf.readFully(typeBytes)
                val type = String(typeBytes, iso88591)

                if (type == "moov" || type == "udta" || type == "meta" || type == "ilst") {
                    if (type == "meta") pos += 4
                    pos += 8
                    continue
                }

                if (type == "\u00A9nam" || type == "\u00A9ART" || type == "\u00A9alb" ||
                    type == "\u00A9day" || type == "\u00A9gen" || (includeArt && type == "covr") ||
                    type == "trkn" || type == "\u00A9lyr" || type == "aART" || type == "\u00A9wrt" ||
                    type == "cpil"
                ) {
                    raf.seek(pos + 8)
                    val dataSize = raf.readInt()
                    val dataTypeBytes = ByteArray(4)
                    raf.readFully(dataTypeBytes)
                    val dataType = String(dataTypeBytes, iso88591)

                    if (dataType == "data") {
                        raf.skipBytes(8)
                        val actualDataSize = dataSize - 16
                        if (actualDataSize in 1..10485759) {
                            val data = ByteArray(actualDataSize)
                            raf.readFully(data)

                            when (type) {
                                "\u00A9nam" -> tags[0] = String(data, Charsets.UTF_8)
                                "\u00A9ART" -> tags[1] = String(data, Charsets.UTF_8)
                                "\u00A9alb" -> tags[2] = String(data, Charsets.UTF_8)
                                "\u00A9day" -> tags[3] = String(data, Charsets.UTF_8)
                                "\u00A9gen" -> {
                                    val g = String(data, Charsets.UTF_8).trim()
                                    if (g.isNotEmpty()) {
                                        val existing = tags[4] as? String
                                        tags[4] = if (existing.isNullOrBlank()) g else "$existing; $g"
                                    }
                                }
                                "covr" -> tags[5] = data
                                "trkn" -> {
                                    if (actualDataSize >= 4) {
                                        tags[6] = (data[3].toInt() and 0xFF).toString()
                                    }
                                }
                                "\u00A9lyr" -> tags[7] = String(data, Charsets.UTF_8)
                                "aART" -> tags[8] = String(data, Charsets.UTF_8)
                                "\u00A9wrt" -> tags[9] = String(data, Charsets.UTF_8)
                                "cpil" -> tags[10] = if (data[0].toInt() != 0) "1" else "0"
                            }
                        }
                    }
                }
                pos += size
            }
            raf.close()
        } catch (ignored: Exception) {}
        return tags
    }

    // The queue that the play queue files hold (queueStamp), or null before the first write
    private var savedQueueStamp: List<Int>? = null

    fun savePlaybackState(sync: Boolean = false) {
        val main = MainActivity.instance ?: return
        if (isRestoringPlaybackState) return
        if (main.currentPlaylist.isEmpty() || main.currentIndex !in main.currentPlaylist.indices) return
        val currentFile = main.currentPlaylist[main.currentIndex]
        val pos = getCurrentPosition().toLong().coerceAtLeast(0L)
        val dur = getDuration().toLong().coerceAtLeast(0L)

        try {
            // The play queue files change only with the queue: a track change does not write them again
            val queueStamp = queueStamp(main.currentPlaylist, main.originalPlaylist)
            if (queueStamp != savedQueueStamp) {
                val playlistFile = File(main.filesDir, "last_playback_playlist.txt")
                val origPlaylistFile = File(main.filesDir, "last_playback_original.txt")

                val sb = java.lang.StringBuilder()
                for (f in main.currentPlaylist) {
                    sb.append(f.absolutePath).append('\n')
                }
                val temp = File(main.filesDir, "last_playback_playlist.tmp")
                temp.writeText(sb.toString())
                temp.renameTo(playlistFile)

                val sbOrig = java.lang.StringBuilder()
                for (f in main.originalPlaylist) {
                    sbOrig.append(f.absolutePath).append('\n')
                }
                val tempOrig = File(main.filesDir, "last_playback_original.tmp")
                tempOrig.writeText(sbOrig.toString())
                tempOrig.renameTo(origPlaylistFile)
                savedQueueStamp = queueStamp
            }

            val editor = main.prefs.edit()
                .putString("last_played_song_path", currentFile.absolutePath)
                .putInt("last_played_song_index", main.currentIndex)
                .putLong("last_played_position", pos)
                .putLong("last_played_duration", dur)
                .putBoolean("last_played_is_audiobook", main.isAudiobookLibraryMode)
                .putBoolean("has_saved_playback_state", true)

            if (main.isAudiobookLibraryMode || currentFile.absolutePath.contains("/Audiobooks")) {
                editor.putString("last_played_audiobook_path", currentFile.absolutePath)
            }

            if (sync) editor.commit() else editor.apply()
        } catch (e: Exception) {
            android.util.Log.e("AudioPlayerManager", "Failed to save playback state: ${e.message}")
        }
    }

    fun savePlaybackPositionOnly(sync: Boolean = false) {
        val main = MainActivity.instance ?: return
        if (isRestoringPlaybackState) return
        if (main.currentPlaylist.isEmpty() || main.currentIndex !in main.currentPlaylist.indices) return
        val pos = getCurrentPosition().toLong().coerceAtLeast(0L)
        if (pos <= 0L) return
        val editor = main.prefs.edit().putLong("last_played_position", pos)
        val dur = getDuration().toLong().coerceAtLeast(0L)
        if (dur > 0L) editor.putLong("last_played_duration", dur)
        if (sync) editor.commit() else editor.apply()
        saveAudiobookBookmarkIfNeeded()
    }

    fun restoreLastPlaybackState(main: MainActivity): Boolean {
        if (!main.prefs.getBoolean("has_saved_playback_state", false)) return false
        val savedPath = main.prefs.getString("last_played_song_path", null) ?: return false
        val targetFile = File(savedPath)

        if (!targetFile.exists()) {
            if (ExternalSdMountMonitor.hasSecondarySlot() && !ExternalSdMountMonitor.isSecondaryReady() &&
                (savedPath.contains("sdcard1") || savedPath.contains("external_sd") || savedPath.contains("MicroSD"))
            ) {
                android.util.Log.i("AudioPlayerManager", "Postponing playback restore until secondary storage is ready: $savedPath")
                main.isPendingRestorePlayback = true
                return false
            }
            android.util.Log.w("AudioPlayerManager", "Saved song does not exist: $savedPath")
            return false
        }

        initPlayer(main.applicationContext)

        isRestoringPlaybackState = true
        try {
            val playlistFile = File(main.filesDir, "last_playback_playlist.txt")
            val origPlaylistFile = File(main.filesDir, "last_playback_original.txt")
            val restoredPlaylist = ArrayList<File>()
            val restoredOriginal = ArrayList<File>()

            if (playlistFile.exists()) {
                try {
                    playlistFile.forEachLine { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            val f = File(trimmed)
                            if (f.exists()) {
                                restoredPlaylist.add(f)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AudioPlayerManager", "Failed to read saved playlist: ${e.message}")
                }
            }

            if (restoredPlaylist.isEmpty() || !restoredPlaylist.any { it.absolutePath == targetFile.absolutePath }) {
                restoredPlaylist.clear()
                restoredPlaylist.add(targetFile)
            }

            if (origPlaylistFile.exists()) {
                try {
                    origPlaylistFile.forEachLine { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            val f = File(trimmed)
                            if (f.exists()) {
                                restoredOriginal.add(f)
                            }
                        }
                    }
                } catch (ignored: Exception) {}
            }
            if (restoredOriginal.isEmpty()) {
                restoredOriginal.addAll(restoredPlaylist)
            }

            val savedPos = main.prefs.getLong("last_played_position", 0L).coerceAtLeast(0L)
            val savedDur = main.prefs.getLong("last_played_duration", 0L).coerceAtLeast(0L)
            val isAudiobook = main.prefs.getBoolean("last_played_is_audiobook", false)

            if ((isAudiobook || StoragePaths.isUnderAudiobooks(targetFile.absolutePath)) && restoredPlaylist.size <= 1) {
                val parent = targetFile.parentFile
                if (parent != null && parent.exists()) {
                    val chaps = parent.listFiles()?.filter { AudiobookManager.isAudioFile(it) }?.sortedWith { f1, f2 ->
                        val t1 = MainActivity.trackNumberMap[f1.absolutePath] ?: 0
                        val t2 = MainActivity.trackNumberMap[f2.absolutePath] ?: 0
                        if (t1 != t2 && t1 > 0 && t2 > 0) t1.compareTo(t2) else f1.name.compareTo(f2.name, ignoreCase = true)
                    }
                    if (!chaps.isNullOrEmpty() && chaps.size > 1) {
                        restoredPlaylist.clear()
                        restoredPlaylist.addAll(chaps)
                        restoredOriginal.clear()
                        restoredOriginal.addAll(chaps)
                    }
                }
            }

            var savedIndex = main.prefs.getInt("last_played_song_index", 0)
            if (savedIndex !in restoredPlaylist.indices || restoredPlaylist[savedIndex].absolutePath != targetFile.absolutePath) {
                savedIndex = restoredPlaylist.indexOfFirst { it.absolutePath == targetFile.absolutePath }
                if (savedIndex == -1) savedIndex = 0
            }

            main.originalPlaylist.clear()
            main.originalPlaylist.addAll(restoredOriginal)
            main.currentPlaylist.clear()
            main.currentPlaylist.addAll(restoredPlaylist)
            main.currentIndex = savedIndex
            main.isPausedByHand = true
            main.isAudiobookLibraryMode = isAudiobook

            val isShuffle = Prefs.SHUFFLE.get(main.prefs)
            val repeatPref = Prefs.REPEAT_MODE.get(main.prefs)
            if (!isUsingLegacyPlayer && exoPlayer != null) {
                exoPlayer?.shuffleModeEnabled = isShuffle
                setRepeatMode(repeatPref)
            }

            isGaplessQueueActive = !isShuffle && shouldUseGaplessQueue(main.currentPlaylist)
            if (isGaplessQueueActive) {
                val mediaItems = ArrayList<MediaItem>()
                for (f in main.currentPlaylist) {
                    mediaItems.add(MediaItem.fromUri(Uri.fromFile(f)))
                }
                legacyPlayer?.let {
                    it.stop()
                    it.reset()
                }
                exoPlayer?.let { exo ->
                    exo.stop()
                    exo.setMediaItems(mediaItems, main.currentIndex, savedPos)
                    exo.shuffleModeEnabled = false
                    val exoRepeatMode = when (repeatPref) {
                        1 -> Player.REPEAT_MODE_ONE
                        2 -> Player.REPEAT_MODE_ALL
                        else -> Player.REPEAT_MODE_OFF
                    }
                    exo.repeatMode = exoRepeatMode
                    exo.setPlaybackParameters(PlaybackParameters(currentSpeed, 1.0f))
                    exo.playWhenReady = false
                    exo.prepare()
                }
            }

            prepareMusicTrack(main.currentIndex, initialSeekMs = savedPos, initialDurationMs = savedDur)
            main.updatePlayerUI()
            main.refreshNowPlayingPreview()
            main.updateMainMenuBackground()
            return true
        } finally {
            isRestoringPlaybackState = false
        }
    }

    companion object {
        @Volatile
        private var instance: AudioPlayerManager? = null
        @Synchronized
        fun getInstance(): AudioPlayerManager {
            var inst = instance
            if (inst == null) {
                inst = AudioPlayerManager()
                instance = inst
            }
            return inst
        }
    }
}

/**
 * Shuffles [list] in place with [first] moved to the front, so a shuffled run that starts on
 * [first] reaches every other track before the list ends.
 */
internal fun <T> shuffleWithFirst(list: MutableList<T>, first: T) {
    list.remove(first)
    Collections.shuffle(list)
    list.add(0, first)
}
