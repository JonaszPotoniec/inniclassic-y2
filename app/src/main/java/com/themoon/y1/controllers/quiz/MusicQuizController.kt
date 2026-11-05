package com.themoon.y1.controllers.quiz

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.controllers.settings.confirmScreen
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.managers.AlbumCoverManager
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.MediaLibraryManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.quiz.MusicQuizEngine
import com.themoon.y1.quiz.QuizLeaderboardManager
import com.themoon.y1.views.Notice
import com.themoon.y1.views.SquareImageView
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Controller for the iPod Music Quiz game.
 * Manages game UI, Start Hub, Leaderboard, endless rounds with non-linear speedup,
 * high-contrast visual answer feedback, 4-option text answers, 3-cover picture options,
 * paused round transitions, and Click Wheel navigation.
 */
class MusicQuizController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val libraryProvider: () -> List<SongItem>,
    private val onExitRequested: () -> Unit,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    companion object {
        private const val MODE_HUB = 0
        private const val MODE_GAME = 1
        private const val MODE_ROUND_PAUSE = 2
        private const val MODE_LEADERBOARD = 3
        private const val MODE_GAME_OVER = 4
    }

    private val engine = MusicQuizEngine(activity)
    private val leaderboardManager = QuizLeaderboardManager(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val coverArtCache = HashMap<String, Boolean>()

    private val layoutQuiz: LinearLayout = if (rootView.id == R.id.layout_music_quiz_mode) rootView as LinearLayout else rootView.findViewById(R.id.layout_music_quiz_mode)
    private val containerQuizItems: LinearLayout = rootView.findViewById(R.id.container_quiz_items)
    private val tvQuizScore: TextView? = rootView.findViewById(R.id.tv_quiz_score)
    private val tvQuizLives: TextView? = rootView.findViewById(R.id.tv_quiz_lives)
    private val tvQuizPrompt: TextView? = rootView.findViewById(R.id.tv_quiz_prompt)
    private val tvQuizRound: TextView? = rootView.findViewById(R.id.tv_quiz_round)
    private val pbQuizTimer: ProgressBar? = rootView.findViewById(R.id.pb_quiz_timer)

    private var currentQuestion: MusicQuizEngine.QuizQuestion? = null
    private var isAnswered = false
    private var wasPlayingBeforeStart = false
    private var countDownTimer: CountDownTimer? = null
    private var currentScreenMode = MODE_HUB
    private var totalQuestionTimeMs = 15000L
    private var currentTimerMillisRemaining = 0L

    /** Back from the Clear Scores screen: the leaderboard again (its fromGameOver), else null. */
    private var returnToLeaderboard: Boolean? = null

    /** True when the last game made a new high score (the game-over menu shows it again after the leaderboard). */
    private var lastGameNewHighScore = false

    /** A heart was bought in this round pause. */
    private var heartBought = false

    /**
     * The menus (start, round complete, game over, leaderboard) are lists on the list model, in a scroll view in
     * the quiz items area; the question screen puts its answer pills or cover tiles there in their place.
     */
    private val menuRows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val menuScroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        addView(menuRows, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val screen = ListScreen(menuRows, null, clickFeedback)

    /** Makes the menu that shows (for a theme change); null while a question shows. */
    private var menu: (() -> ListSpec)? = null

    override fun onEnter(state: ScreenState) {
        layoutQuiz.visibility = View.VISIBLE
        val leaderboard = returnToLeaderboard
        returnToLeaderboard = null
        if (leaderboard != null) showLeaderboardView(leaderboard) else showStartHub()
    }

    override fun onExit() {
        cancelTimer()
        engine.stopPreview()
        screen.clear()
        menu = null
        layoutQuiz.visibility = View.GONE
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean = menu != null && screen.wheel(delta)

    override fun onThemeChanged() {
        menuScroll.setBackgroundColor(ThemeManager.getOverlayBackgroundColor())
        menu?.let { screen.update(it()) }
    }

    /** Shows the menu that [build] makes, with its first row selected. */
    private fun showMenu(build: () -> ListSpec) {
        containerQuizItems.orientation = LinearLayout.VERTICAL
        if (menuScroll.parent !== containerQuizItems) {
            containerQuizItems.removeAllViews()
            containerQuizItems.addView(menuScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        // The list colours of the theme (as Settings), so the rows can be read on the quiz background
        menuScroll.setBackgroundColor(ThemeManager.getOverlayBackgroundColor())
        menu = build
        screen.show(build())
    }

    /** The question screen takes the quiz items area. */
    private fun clearMenu() {
        menu = null
        screen.clear()
        containerQuizItems.removeAllViews()
    }

    private fun menuSpec(rows: List<RowSpec>) = ListSpec(localize("Music Quiz"), rows)

    private fun action(label: String, run: () -> Unit) = RowSpec(label, RowLook.MENU, onClick = run)

    /** A grey row that the wheel skips. */
    private fun disabled(label: String) = RowSpec(label, RowLook.MENU, enabled = false)

    fun showStartHub() {
        currentScreenMode = MODE_HUB
        cancelTimer()
        engine.stopPreview()

        pbQuizTimer?.progress = 0
        val highScore = leaderboardManager.getHighScore()
        val bestText = if (highScore > 0) "\n${localize("Best")}: ${String.format(Locale.US, "$%,d", highScore)}" else ""
        tvQuizPrompt?.text = localize("Music Quiz") + bestText
        tvQuizRound?.text = "♪"
        tvQuizLives?.text = ""
        tvQuizScore?.text = ""

        showMenu {
            menuSpec(listOf(
                action(localize("Play Game")) { startSession() },
                action(localize("Leaderboard")) { showLeaderboardView(fromGameOver = false) },
                action(localize("Exit")) { exitQuiz() }
            ))
        }
    }

    fun startSession() {
        currentScreenMode = MODE_GAME
        wasPlayingBeforeStart = AudioPlayerManager.getInstance().isPlaying()
        if (wasPlayingBeforeStart) {
            AudioPlayerManager.getInstance().playOrPauseMusic()
        }

        engine.coverArtPredicate = { hasCoverArt(it) }

        val hasEnoughSongs = engine.resetSession(libraryProvider())
        if (!hasEnoughSongs) {
            Notice.error(activity, localize("Not enough songs in your library for Music Quiz."))
            showStartHub()
            return
        }

        updateScoreLivesUI()
        loadNextQuestion()
    }

    private fun loadNextQuestion() {
        currentScreenMode = MODE_GAME
        isAnswered = false
        val question = engine.nextQuestion { type, song ->
            val title = "“${song.title}”"
            when (type) {
                MusicQuizEngine.TYPE_ARTIST -> "${localize("Which artist released")} $title?"
                MusicQuizEngine.TYPE_ALBUM -> "${localize("Which album is")} $title ${localize("from?")}"
                MusicQuizEngine.TYPE_YEAR -> "${localize("What year was")} $title ${localize("released?")}"
                MusicQuizEngine.TYPE_ALBUM_COVER -> localize("Which cover matches this song's album?")
                else -> localize("What song is playing?")
            }
        }

        if (question == null) {
            exitQuiz()
            return
        }

        currentQuestion = question
        updateScoreLivesUI()

        tvQuizPrompt?.text = question.promptText
        clearMenu()

        if (question.type == MusicQuizEngine.TYPE_ALBUM_COVER) {
            val bitmaps = question.coverOptions.map { loadSongCoverBitmap(it.file, it.album) }
            if (bitmaps.any { it == null }) {
                question.coverOptions.forEachIndexed { idx, opt ->
                    if (bitmaps[idx] == null) {
                        val key = AlbumCoverManager.getAlbumKey(opt.file, opt.album)
                        coverArtCache[key] = false
                    }
                }
                loadNextQuestion()
                return
            }

            containerQuizItems.orientation = LinearLayout.HORIZONTAL
            for (i in question.coverOptions.indices) {
                val coverOpt = question.coverOptions[i]
                val bmp = bitmaps[i]!!
                val tile = createCoverOptionTile(coverOpt, bmp)
                tile.setOnClickListener {
                    clickFeedback()
                    onAnswerSelected(coverOpt.album)
                }
                containerQuizItems.addView(tile)
            }
        } else {
            containerQuizItems.orientation = LinearLayout.VERTICAL
            for (optionText in question.textOptions) {
                val row = createAnswerPill(optionText)
                row.setOnClickListener {
                    clickFeedback()
                    onAnswerSelected(optionText)
                }
                containerQuizItems.addView(row)
            }
        }

        containerQuizItems.postDelayed({
            if (containerQuizItems.childCount > 0) {
                containerQuizItems.getChildAt(0).requestFocus()
            }
        }, 50)

        startTimer()
    }

    private fun onAnswerSelected(selected: String?) {
        if (isAnswered) return
        isAnswered = true
        cancelTimer()

        val question = currentQuestion ?: return
        val remainingFraction = if (totalQuestionTimeMs > 0L) {
            currentTimerMillisRemaining.toFloat() / totalQuestionTimeMs.toFloat()
        } else 0f

        val correct = engine.submitAnswer(selected, question, remainingFraction)

        // Visual feedback
        if (question.type == MusicQuizEngine.TYPE_ALBUM_COVER) {
            for (i in 0 until containerQuizItems.childCount) {
                val tile = containerQuizItems.getChildAt(i)
                val coverOpt = question.coverOptions.getOrNull(i) ?: continue
                val isThisCorrect = coverOpt.album.equals(question.correctAnswer, ignoreCase = true)
                val isThisSelected = selected != null && coverOpt.album.equals(selected, ignoreCase = true)

                if (isThisSelected) {
                    tile.setBackgroundResource(
                        if (correct) R.drawable.quiz_cover_tile_correct else R.drawable.quiz_cover_tile_incorrect
                    )
                } else if (!correct && isThisCorrect) {
                    tile.setBackgroundResource(R.drawable.quiz_cover_tile_correct)
                }
            }
        } else {
            for (i in 0 until containerQuizItems.childCount) {
                val row = containerQuizItems.getChildAt(i) as? LinearLayout ?: continue
                val tvText = row.getChildAt(1) as? TextView
                val optText = tvText?.text?.toString() ?: ""
                val isThisCorrect = optText.equals(question.correctAnswer, ignoreCase = true)
                val isThisSelected = selected != null && optText.equals(selected, ignoreCase = true)

                if (isThisSelected) {
                    row.setBackgroundResource(
                        if (correct) R.drawable.quiz_answer_pill_correct else R.drawable.quiz_answer_pill_incorrect
                    )
                    tvText?.setTextColor(0xFFFFFFFF.toInt())
                } else if (!correct && isThisCorrect) {
                    row.setBackgroundResource(R.drawable.quiz_answer_pill_correct)
                    tvText?.setTextColor(0xFFFFFFFF.toInt())
                }
            }
        }

        updateScoreLivesUI()

        handler.postDelayed({
            if (engine.isGameOver) {
                showGameOver()
            } else if (engine.isRoundComplete()) {
                showRoundCompleteTransition()
            } else {
                loadNextQuestion()
            }
        }, 800)
    }

    private fun startTimer() {
        cancelTimer()
        totalQuestionTimeMs = engine.getQuestionTimeMs()
        currentTimerMillisRemaining = totalQuestionTimeMs
        pbQuizTimer?.max = totalQuestionTimeMs.toInt()
        pbQuizTimer?.progress = totalQuestionTimeMs.toInt()

        countDownTimer = object : CountDownTimer(totalQuestionTimeMs, 50) {
            override fun onTick(millisUntilFinished: Long) {
                currentTimerMillisRemaining = millisUntilFinished
                pbQuizTimer?.progress = millisUntilFinished.toInt()
            }

            override fun onFinish() {
                currentTimerMillisRemaining = 0L
                pbQuizTimer?.progress = 0
                if (!isAnswered) {
                    onAnswerSelected(null)
                }
            }
        }.start()
    }

    private fun cancelTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
    }

    private fun updateScoreLivesUI() {
        tvQuizScore?.text = String.format(Locale.US, "$%,d", engine.score)
        tvQuizRound?.text = engine.roundNumber.toString()
        if (tvQuizLives != null) {
            val hearts = StringBuilder()
            for (i in 0 until MusicQuizEngine.MAX_LIVES) {
                hearts.append(if (i < engine.lives) "♥ " else "♡ ")
            }
            tvQuizLives.text = hearts.toString().trim()
        }
    }

    private fun showRoundCompleteTransition() {
        currentScreenMode = MODE_ROUND_PAUSE
        cancelTimer()
        engine.stopPreview()
        pbQuizTimer?.progress = 0

        val completedRound = engine.roundNumber
        val perfectRoundBonus = engine.advanceRound()
        heartBought = false

        val bonusNotice = if (perfectRoundBonus) {
            "\n★ " + localize("Perfect Round! +1 Life")
        } else ""

        tvQuizPrompt?.text = "${localize("Round ")}$completedRound ${localize("Complete!")}$bonusNotice"
        updateScoreLivesUI()
        tvQuizRound?.text = completedRound.toString()

        showRoundPauseMenu(completedRound)
    }

    /** Continue, Buy 1 Heart (grey when the score is too low or a heart was bought) and Exit. */
    private fun showRoundPauseMenu(completedRound: Int) {
        showMenu {
            menuSpec(listOf(
                action(localize("Continue")) { loadNextQuestion() },
                if (engine.canBuyHeart()) {
                    action(localize("Buy 1 Heart ($1,500)")) {
                        if (engine.buyHeart()) {
                            updateScoreLivesUI()
                            tvQuizRound?.text = completedRound.toString()
                            heartBought = true
                            showRoundPauseMenu(completedRound)
                        }
                    }
                } else {
                    disabled(if (heartBought) "✓ " + localize("Heart Purchased") else localize("Buy 1 Heart ($1,500)"))
                },
                action(localize("Exit")) { exitQuiz() }
            ))
        }
    }

    private fun showGameOver() {
        currentScreenMode = MODE_GAME_OVER
        cancelTimer()
        engine.stopPreview()
        pbQuizTimer?.progress = 0
        // Once for each game (the menu shows again after the leaderboard)
        lastGameNewHighScore = leaderboardManager.recordScore(engine.score, engine.roundNumber)
        showGameOverMenu()
    }

    private fun showGameOverMenu() {
        currentScreenMode = MODE_GAME_OVER
        val highScore = leaderboardManager.getHighScore()

        val title = localize("Game Over!")
        val scoreStr = String.format(Locale.US, "$%,d", engine.score)
        val roundStr = "${localize("Round ")}${engine.roundNumber}"
        val highScoreNotice = if (lastGameNewHighScore) "🏆 " + localize("New High Score!") else "${localize("Best")}: ${String.format(Locale.US, "$%,d", highScore)}"

        tvQuizPrompt?.text = "$title\n$scoreStr ($roundStr)\n$highScoreNotice"
        updateScoreLivesUI()

        showMenu {
            menuSpec(listOf(
                action(localize("Play Again")) { startSession() },
                action(localize("Leaderboard")) { showLeaderboardView(fromGameOver = true) },
                action(localize("Exit")) { exitQuiz() }
            ))
        }
    }

    private fun showLeaderboardView(fromGameOver: Boolean) {
        currentScreenMode = MODE_LEADERBOARD
        cancelTimer()
        engine.stopPreview()
        pbQuizTimer?.progress = 0

        tvQuizPrompt?.text = localize("Leaderboard")
        tvQuizRound?.text = "★"
        tvQuizLives?.text = ""
        tvQuizScore?.text = ""

        showMenu {
            val rows = ArrayList<RowSpec>()
            val entries = leaderboardManager.getTopEntries()
            if (entries.isEmpty()) {
                rows += RowSpec(localize("No scores yet"), RowLook.INFO)
            } else {
                val dateFormat = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                for ((index, entry) in entries.withIndex()) {
                    val scoreStr = String.format(Locale.US, "$%,d", entry.score)
                    val dateStr = dateFormat.format(java.util.Date(entry.timestamp))
                    rows += RowSpec("#${index + 1}   $scoreStr", RowLook.VALUE, value = "$dateStr   (R${entry.round})", key = "#${index + 1}")
                }
            }
            rows += action(localize("Back")) { if (fromGameOver) showGameOverMenu() else showStartHub() }
            if (entries.isNotEmpty()) {
                rows += action(localize("Clear Scores")) {
                    returnToLeaderboard = fromGameOver
                    ActionScreens.open(confirmScreen(localize("Leaderboard"), "", localize("Clear Scores")) {
                        leaderboardManager.clearLeaderboard()
                        Notice.show(activity, localize("Scores Cleared"))
                        showLeaderboardView(fromGameOver)
                    })
                }
            }
            menuSpec(rows)
        }
    }

    private fun exitQuiz() {
        cancelTimer()
        engine.stopPreview()
        if (wasPlayingBeforeStart) {
            val am = AudioPlayerManager.getInstance()
            if (!am.isPlaying()) {
                am.playOrPauseMusic()
            }
        }
        onExitRequested()
    }

    // The wheel never comes here at a list: onWheel takes it in the menus, and InputRouter steps the answers
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // The focused button clicks on release. A click here also came with each key repeat of a hold.
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return false // Back on release: the router calls onBackPressed
        }

        return true
    }

    override fun onBackPressed(): Boolean {
        if (currentScreenMode == MODE_LEADERBOARD) {
            showStartHub()
            return true
        }
        exitQuiz()
        return true
    }

    private fun createAnswerPill(text: String): LinearLayout {
        val d = activity.resources.displayMetrics.density
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            isSoundEffectsEnabled = false
            setBackgroundResource(R.drawable.quiz_answer_pill_normal)
            val padH = (16 * d).toInt()
            val padV = (8 * d).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (3 * d).toInt(), 0, (3 * d).toInt())
            }
        }

        val dot = View(activity).apply {
            val dotSize = (14 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                rightMargin = (12 * d).toInt()
            }
            setBackgroundResource(R.drawable.quiz_dot_filled)
            visibility = View.INVISIBLE
        }

        val tvText = TextView(activity).apply {
            setText(text)
            textSize = 17f
            setTextColor(0xFF173350.toInt())
            typeface = ThemeManager.getCustomFontBold()
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        row.addView(dot)
        row.addView(tvText)

        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                row.setBackgroundResource(R.drawable.quiz_answer_pill_focused)
                tvText.setTextColor(0xFFFFFFFF.toInt())
                dot.visibility = View.VISIBLE
                tvText.isSelected = true
            } else {
                row.setBackgroundResource(R.drawable.quiz_answer_pill_normal)
                tvText.setTextColor(0xFF173350.toInt())
                dot.visibility = View.INVISIBLE
                tvText.isSelected = false
            }
        }

        return row
    }

    private fun createCoverOptionTile(option: MusicQuizEngine.CoverOption, bitmap: Bitmap): LinearLayout {
        val d = activity.resources.displayMetrics.density
        val tile = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            isSoundEffectsEnabled = false
            setBackgroundResource(R.drawable.quiz_answer_pill_normal)
            val pad = (6 * d).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f).apply {
                setMargins((4 * d).toInt(), 0, (4 * d).toInt(), 0)
            }
        }

        val ivCover = SquareImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bitmap)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        tile.addView(ivCover)

        tile.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            tile.setBackgroundResource(
                if (hasFocus) R.drawable.quiz_answer_pill_focused else R.drawable.quiz_answer_pill_normal
            )
        }

        return tile
    }

    private fun hasCoverArt(song: SongItem): Boolean {
        val album = song.album
        if (album.isNullOrBlank() || album.trim().lowercase().startsWith("unknown")) {
            return false
        }
        val file = song.file
        val key = AlbumCoverManager.getAlbumKey(file, album)
        coverArtCache[key]?.let { return it }

        val bmp = loadSongCoverBitmap(file, album)
        val hasArt = bmp != null
        coverArtCache[key] = hasArt
        return hasArt
    }

    private fun loadSongCoverBitmap(file: File, album: String?): Bitmap? {
        val key = AlbumCoverManager.getAlbumKey(file, album)
        val manager = AlbumCoverManager.getInstance()

        // 1. In-memory LRU
        manager.getMemoryCover(key)?.let { return it }

        // 2. Pre-scaled disk cache
        manager.getThumbnailDirect(key)?.let { return it }

        // 3. Folder cover (cover.jpg, folder.jpg)
        try {
            val folderCover = file.parentFile?.let { MediaLibraryManager.getInstance().findFolderCover(it) }
            if (folderCover != null && folderCover.exists()) {
                val bmp = AlbumCoverManager.decodeSampledBitmapFromFile(
                    folderCover.absolutePath,
                    AlbumCoverManager.THUMB_SIZE,
                    AlbumCoverManager.THUMB_SIZE
                )
                if (bmp != null) return bmp
            }
        } catch (ignored: Throwable) {}

        // 4. StoragePaths covers dir
        try {
            val nameWithoutExt = file.nameWithoutExtension
            val manualCover = File(StoragePaths.getCoversDir(), "$nameWithoutExt.jpg")
            if (manualCover.exists()) {
                val bmp = AlbumCoverManager.decodeSampledBitmapFromFile(
                    manualCover.absolutePath,
                    AlbumCoverManager.THUMB_SIZE,
                    AlbumCoverManager.THUMB_SIZE
                )
                if (bmp != null) return bmp
            }
        } catch (ignored: Throwable) {}

        // 5. Embedded art
        try {
            val path = file.absolutePath
            val lower = path.lowercase(Locale.US)
            var embeddedBytes: ByteArray? = null
            if (lower.endsWith(".m4a") || lower.endsWith(".alac")) {
                val tags = AudioPlayerManager.getInstance().extractAlacMetadata(file)
                if (tags.size > 5 && tags[5] != null) {
                    embeddedBytes = tags[5] as? ByteArray
                }
            } else {
                val mmr = MediaMetadataRetriever()
                val fis = FileInputStream(file)
                try {
                    mmr.setDataSource(fis.fd)
                    embeddedBytes = mmr.embeddedPicture
                } finally {
                    try { fis.close() } catch (ignored: Throwable) {}
                    try { mmr.release() } catch (ignored: Throwable) {}
                }
            }
            if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
                val bmp = AlbumCoverManager.decodeSampledBitmapFromByteArray(
                    embeddedBytes,
                    AlbumCoverManager.THUMB_SIZE,
                    AlbumCoverManager.THUMB_SIZE
                )
                if (bmp != null) return bmp
            }
        } catch (ignored: Throwable) {}

        return null
    }
}
