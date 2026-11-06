package com.themoon.y1.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.updateStatusBarPlayOn
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.LibrarySongs
import com.themoon.y1.managers.MediaLibraryManager
import com.themoon.y1.views.Notice
import org.json.JSONObject
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Play On: the music of the Y2 plays on a speaker of the Wi-Fi (Sonos, DLNA, Google Cast). The speaker gets the
 * file from [CastMediaServer] on the Y2. The Y2 player continues with no sound and stays in control; [CastSync]
 * keeps the two together. The wheel changes the volume of the speaker.
 *
 * While the music is paused, [CastIdle] saves the battery: after 5 minutes the wake and Wi-Fi locks go, after 15 minutes
 * (or when the connection is lost) the link closes, but the speaker stays chosen. Screen on or Play connects again;
 * if that fails, the Y2 stays paused (no sound from the Y2) and a notice shows.
 */
object CastManager {
    private const val TAG = "CastManager"
    private const val VOLUME_STEP = 2
    private const val RESUME_ATTEMPTS = 3
    private const val RESUME_RETRY_MS = 3000L

    /** The speaker that plays, or null when the Y2 plays itself. Set and read on the main thread. */
    @Volatile var device: CastDevice? = null
        private set

    val isActive: Boolean get() = device != null

    private val main = Handler(Looper.getMainLooper())
    private var worker: Handler? = null
    private var renderer: CastRenderer? = null
    private var server: CastMediaServer? = null
    private var sync = CastSync()
    private var idle = CastIdle()
    /** True while [resumeLink] tries to connect again. */
    private var resuming = false
    private var session = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var volume: Int? = null
    @Volatile private var volumeTarget: Int? = null
    @Volatile private var lastWheelAt = 0L
    private var ticks = 0
    private var lastDevices: List<CastDevice> = emptyList()

    // --- search ---

    /** The players found by the last search (empty before the first one). */
    val knownDevices: List<CastDevice> get() = lastDevices

    /** Searches in the background and gives the result on the main thread. */
    fun search(done: (List<CastDevice>) -> Unit) {
        workerHandler().post {
            val found = try { CastDiscovery.discover() } catch (e: Exception) { Log.w(TAG, "search: $e"); emptyList() }
            main.post {
                lastDevices = found
                log("search", JSONObject().put("found", found.joinToString { it.id }))
                done(found)
            }
        }
    }

    // --- session ---

    /** Starts to play on [target] (main thread). Another speaker stops first. */
    fun start(target: CastDevice) {
        if (device?.id == target.id) {
            resumeLink("menu")
            return
        }
        if (device != null) stop(stopRemote = true, reason = null)
        val s = ++session
        device = target
        sync = CastSync()
        idle = CastIdle().also { it.start(AudioPlayerManager.getInstance().isPlaying(), elapsed()) }
        resuming = false
        volume = null
        volumeTarget = null
        setLocks(true)
        AudioPlayerManager.getInstance().applyMasterVolume()
        MainActivity.instance?.updateStatusBarPlayOn()
        log("start", JSONObject().put("device", target.id).put("name", target.name))
        notice("${localize("Playing on")} ${target.name}")
        val r = CastRenderer.forDevice(target)
        renderer = r
        workerHandler().post {
            if (s != session) return@post
            try {
                ensureServer()
                volume = r.volume()
            } catch (e: Exception) {
                Log.w(TAG, "start: $e")
            }
        }
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** Back to the Y2 (main thread). The Y2 continues at the same place, with its sound on. */
    fun stop(stopRemote: Boolean = true, reason: String? = null) {
        val d = device ?: return
        val r = renderer
        session++
        device = null
        renderer = null
        resuming = false
        main.removeCallbacks(tick)
        AudioPlayerManager.getInstance().applyMasterVolume()
        MainActivity.instance?.updateStatusBarPlayOn()
        releaseLocks()
        log("stop", JSONObject().put("device", d.id).put("reason", reason ?: "user"))
        if (reason != null) notice("${d.name}: ${localize(reason)}", error = true)
        workerHandler().post {
            if (stopRemote) try { r?.stop() } catch (e: Exception) { Log.w(TAG, "stop: $e") }
            try { r?.close() } catch (_: Exception) {}
            if (device == null) {
                server?.stop()
                server = null
            }
        }
    }

    // --- pause: locks off, link closed, connect again ---

    /** The Y2 player starts to play (any thread): the locks come back at once, or the closed link connects again. */
    fun onLocalPlay() {
        if (device == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onLocalPlay() }
            return
        }
        if (idle.state == CastIdle.State.SUSPENDED) {
            resumeLink("play")
        } else {
            updateIdle(playing = true)
        }
    }

    /** The screen comes on (main thread): a closed link connects again, with no play. */
    fun onScreenOn() {
        if (device == null) return
        if (idle.wake(elapsed())) resumeLink("screen")
    }

    private fun updateIdle(playing: Boolean) {
        val before = idle.state
        val after = idle.tick(playing, elapsed())
        if (after == before) return
        when (after) {
            CastIdle.State.IDLE -> {
                setLocks(false)
                log("idle-release", JSONObject().put("device", device?.id))
            }
            CastIdle.State.ACTIVE -> {
                setLocks(true)
                log("idle-acquire", JSONObject().put("device", device?.id))
            }
            CastIdle.State.SUSPENDED -> suspendLink("paused")
        }
    }

    /**
     * Closes the link (no status, no media server, no locks) but keeps the speaker as the Play On choice (main
     * thread). The speaker keeps its paused track; it cannot play it until the link connects again.
     */
    internal fun suspendLink(reason: String) {
        val d = device ?: return
        val r = renderer
        idle.suspend()
        session++
        renderer = null
        resuming = false
        main.removeCallbacks(tick)
        setLocks(false)
        log("suspend", JSONObject().put("device", d.id).put("reason", reason))
        workerHandler().post {
            try { r?.close() } catch (_: Exception) {}
            server?.stop()
            server = null
        }
    }

    /** Connects again to the chosen speaker after [suspendLink] (main thread). The music does not start by itself. */
    internal fun resumeLink(trigger: String) {
        val d = device ?: return
        if (idle.state != CastIdle.State.SUSPENDED || resuming) return
        resuming = true
        val s = ++session
        // Only the wake lock is necessary for the connection, but a Wi-Fi that sleeps makes it slow
        setLocks(true)
        log("resume", JSONObject().put("device", d.id).put("trigger", trigger))
        if (trigger == "play") notice("${localize("Connecting to")} ${d.name}")
        tryResume(s, d, trigger, 1)
    }

    private class Link(val device: CastDevice, val renderer: CastRenderer, val volume: Int?)

    private fun tryResume(s: Int, d: CastDevice, trigger: String, attempt: Int) {
        workerHandler().post {
            if (s != session) return@post
            // The Wi-Fi can need some seconds after the screen comes on; the speaker can have a new address (DHCP)
            val link = connect(d) ?: if (attempt > 1) findAgain(d)?.let { connect(it) } else null
            main.post {
                when {
                    s != session -> link?.let { l -> workerHandler().post { try { l.renderer.close() } catch (_: Exception) {} } }
                    link != null -> resumed(link, trigger, attempt)
                    attempt < RESUME_ATTEMPTS -> main.postDelayed({ if (s == session) tryResume(s, d, trigger, attempt + 1) }, RESUME_RETRY_MS)
                    else -> resumeFailed(d, trigger)
                }
            }
        }
    }

    /** Cast thread: a renderer that answers, and the media server. Null when the speaker does not answer. */
    private fun connect(d: CastDevice): Link? {
        val r = CastRenderer.forDevice(d)
        return try {
            r.status()
            ensureServer()
            Link(d, r, try { r.volume() } catch (_: Exception) { null })
        } catch (e: Exception) {
            Log.w(TAG, "connect ${d.id}: $e")
            try { r.close() } catch (_: Exception) {}
            null
        }
    }

    /** Cast thread: the same speaker at another address, or null. */
    private fun findAgain(d: CastDevice): CastDevice? {
        val found = try { CastDiscovery.discover() } catch (_: Exception) { emptyList() }
        return found.firstOrNull {
            it.kind == d.kind && it.id != d.id && if (d.rinconId != null) it.rinconId == d.rinconId else it.name == d.name
        }
    }

    private fun resumed(link: Link, trigger: String, attempt: Int) {
        resuming = false
        device = link.device
        renderer = link.renderer
        // A new sync loads the track again (new server port), paused or playing as the Y2 is now
        sync = CastSync()
        volume = link.volume
        volumeTarget = null
        val playing = AudioPlayerManager.getInstance().isPlaying()
        idle.resumed(playing, elapsed())
        setLocks(idle.holdsLocks)
        MainActivity.instance?.updateStatusBarPlayOn()
        log("resumed", JSONObject().put("device", link.device.id).put("trigger", trigger).put("attempt", attempt).put("playing", playing))
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** No connection: the Y2 stays paused, with the speaker as its choice (no sound from the Y2). */
    private fun resumeFailed(d: CastDevice, trigger: String) {
        resuming = false
        setLocks(false)
        val playing = pauseLocal()
        log("resume-failed", JSONObject().put("device", d.id).put("trigger", trigger))
        if (playing || trigger != "screen") notice("${d.name}: ${localize("cannot connect")}", error = true)
    }

    // --- volume ---

    /** The wheel while a speaker plays: changes its volume. False when no speaker plays (main thread). */
    fun adjustVolume(isUp: Boolean): Boolean {
        if (!isActive) return false
        if (idle.state == CastIdle.State.SUSPENDED) {
            resumeLink("wheel")
            return true
        }
        val current = volumeTarget ?: volume ?: return true
        val target = (current + if (isUp) VOLUME_STEP else -VOLUME_STEP).coerceIn(0, 100)
        volumeTarget = target
        lastWheelAt = SystemClock.uptimeMillis()
        MainActivity.instance?.showDynamicVolumeOverlay(target, 100, R.drawable.ic_play_on)
        val s = session
        val w = workerHandler()
        w.removeCallbacks(sendVolume)
        w.post(sendVolume)
        log("volume", JSONObject().put("level", target).put("session", s))
        return true
    }

    private val sendVolume = Runnable {
        val t = volumeTarget ?: return@Runnable
        try {
            renderer?.setVolume(t)
            volume = t
        } catch (e: Exception) {
            Log.w(TAG, "volume: $e")
        }
    }

    // --- tick ---

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!isActive || idle.state == CastIdle.State.SUSPENDED) return
            val local = snapshot()
            updateIdle(local.playing)
            if (idle.state == CastIdle.State.SUSPENDED) return
            val s = session
            val info = local.trackPath?.let { trackInfo(it) }
            workerHandler().post { work(s, local, info) }
            main.postDelayed(this, CastSync.TICK_MS)
        }
    }

    /** True when a speaker plays the current track: a file. The Y2 plays a podcast stream itself (no file to serve). */
    fun playsCurrentTrack(): Boolean {
        if (!isActive) return false
        val m = MainActivity.instance ?: return true
        val file = m.currentPlaylist.getOrNull(m.currentIndex) ?: return true
        return !file.absolutePath.startsWith("/PODCAST_STREAM")
    }

    private fun snapshot(): CastSync.Local {
        val m = MainActivity.instance
        val apm = AudioPlayerManager.getInstance()
        val file = m?.currentPlaylist?.getOrNull(m.currentIndex)
        val path = file?.absolutePath?.takeIf { file.isFile }
        return CastSync.Local(path, apm.isPlaying(), apm.getCurrentPosition().toLong(), apm.getDuration().toLong(), SystemClock.uptimeMillis())
    }

    /** Title, artist and album of a track (main thread: the library is there). */
    private class TrackInfo(val file: File, val title: String, val artist: String, val album: String)

    private fun trackInfo(path: String): TrackInfo {
        val file = File(path)
        val song = LibrarySongs.byPath(path)
        val title = song?.title?.takeIf { it.isNotEmpty() } ?: file.nameWithoutExtension
        val artist = song?.let { if (it.artist.isNotEmpty()) it.artist else it.albumArtist }.orEmpty()
        return TrackInfo(file, title, artist, song?.album.orEmpty())
    }

    /** The embedded picture of the file, else the image of its folder (cast thread). */
    private fun coverBytes(file: File): ByteArray? =
        AudioPlayerManager.getInstance().readTrackTags(file, includeArt = true).art
            ?: try { MediaLibraryManager.getInstance().findFolderCover(file.parentFile)?.readBytes() } catch (_: Exception) { null }

    /** Cast thread: status of the speaker, then the actions of [CastSync]. */
    private fun work(s: Int, local: CastSync.Local, info: TrackInfo?) {
        if (s != session) return
        val r = renderer ?: return
        val remote = if (local.trackPath == sync.loadedTrack) {
            try { r.status() } catch (e: Exception) { Log.w(TAG, "status: $e"); null }
        } else null
        val actions = sync.tick(local, remote)
        // The volume can change in the speaker's app: read it again every 5 s, not while the wheel turns
        if (++ticks % 5 == 0 && SystemClock.uptimeMillis() - lastWheelAt > 3000) {
            try {
                r.volume()?.let { v -> volume = v; volumeTarget = null }
            } catch (_: Exception) {}
        }
        for (a in actions) {
            if (s != session) return
            try {
                when (a) {
                    is CastSync.Action.Load -> load(r, info ?: continue, local, a)
                    CastSync.Action.RemotePlay -> r.play()
                    CastSync.Action.RemotePause -> r.pause()
                    is CastSync.Action.RemoteSeek -> r.seek(a.ms)
                    is CastSync.Action.LocalSeekBy -> onMain(s) { AudioPlayerManager.getInstance().seekRelative(a.deltaMs.toInt(), savePosition = false) }
                    CastSync.Action.LocalPause -> onMain(s) { pauseLocal() }
                    CastSync.Action.LocalPlay -> onMain(s) { if (!AudioPlayerManager.getInstance().isPlaying()) AudioPlayerManager.getInstance().playOrPauseMusic() }
                    is CastSync.Action.End -> onMain(s) { endFromSpeaker(a.reason) }
                }
                if (a !is CastSync.Action.LocalSeekBy) log("action", JSONObject().put("do", a.toString()))
            } catch (e: Exception) {
                Log.w(TAG, "$a: $e")
                log("error", JSONObject().put("do", a.toString()).put("error", e.toString()))
            }
        }
    }

    private fun load(r: CastRenderer, info: TrackInfo, local: CastSync.Local, a: CastSync.Action.Load) {
        val srv = ensureServer()
        val host = localAddressFor(device?.host ?: return) ?: throw java.io.IOException("no Wi-Fi address")
        val base = "http://$host:${srv.port}"
        // The Sonos app shows only HTTPS covers: a Sonos gets the Cover Art Archive image, the others the cover of the Y2
        val cover = if (device?.kind == CastDevice.Kind.SONOS) CastCover.httpsCoverUrl(info.file) else coverBytes(info.file)?.let { b ->
            val png = b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()
            base + srv.registerBytes(b, if (png) "image/png" else "image/jpeg", if (png) "png" else "jpg")
        }
        val media = CastMedia(
            url = base + srv.register(info.file), mimeType = CastProtocol.mimeType(info.file.name),
            title = info.title, artist = info.artist, album = info.album,
            durationMs = local.durationMs, sizeBytes = info.file.length(), coverUrl = cover
        )
        sync.expectedUrl = media.url
        r.load(media, a.startMs, a.play)
        log("load", JSONObject().put("title", info.title).put("startMs", a.startMs).put("play", a.play)
            .put("cover", cover != null))
    }

    /**
     * The speaker stopped or another app took it: the Y2 pauses and has its sound again. A lost connection (also a
     * Wi-Fi that sleeps while the music is paused) keeps the speaker: the Y2 pauses, and Play connects again.
     */
    private fun endFromSpeaker(reason: String) {
        if (!isActive) return
        val playing = pauseLocal()
        if (reason == CastSync.LOST_CONNECTION) {
            val d = device
            suspendLink(reason)
            if (playing && d != null) notice("${d.name}: ${localize(reason)}", error = true)
            return
        }
        stop(stopRemote = false, reason = reason)
    }

    // --- helpers ---

    /** Pauses the Y2 player if it plays (main thread). True if it played. */
    private fun pauseLocal(): Boolean {
        val apm = AudioPlayerManager.getInstance()
        if (!apm.isPlaying()) return false
        apm.playOrPauseMusic()
        return true
    }

    private fun onMain(s: Int, block: () -> Unit) {
        main.post { if (s == session) block() }
    }

    /** The address of the Y2 on the network of [peer] (the Wi-Fi address changes at each Wi-Fi start). */
    private fun localAddressFor(peer: String): String? = try {
        val sock = DatagramSocket()
        try {
            sock.connect(InetAddress.getByName(peer), 9)
            sock.localAddress?.hostAddress?.takeIf { it != "0.0.0.0" }
        } finally {
            sock.close()
        }
    } catch (_: Exception) { null }

    /** Cast thread: the media server, started at the first use after [stop] or [suspendLink] stopped it. */
    private fun ensureServer(): CastMediaServer = server ?: CastMediaServer().also { it.start(); server = it }

    private fun workerHandler(): Handler {
        worker?.let { return it }
        val t = HandlerThread("Cast")
        t.start()
        return Handler(t.looper).also { worker = it }
    }

    private fun elapsed(): Long = SystemClock.elapsedRealtime()

    /** Wake and Wi-Fi locks on or off (the same call two times does nothing). */
    private fun setLocks(on: Boolean) {
        if (!on) {
            if (wakeLock != null || wifiLock != null) releaseLocks()
        } else if (wakeLock == null) {
            acquireLocks(MainActivity.instance?.applicationContext ?: return)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "y2:cast").apply { setReferenceCounted(false); acquire() }
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "y2:cast").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "locks: $e")
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun notice(text: String, error: Boolean = false) {
        val ctx = MainActivity.instance ?: return
        if (error) Notice.error(ctx, text) else Notice.show(ctx, text)
    }

    private fun localize(text: String): String = MainActivity.instance?.t(text) ?: text

    private fun log(what: String, fields: JSONObject) {
        Log.i(TAG, "$what $fields")
    }
}
