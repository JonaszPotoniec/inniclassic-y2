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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.quiz.MusicQuizEngine
import com.themoon.y1.views.SquareImageView
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Controller for the iPod Music Quiz game.
 * Manages game UI, 4-option text answers, 3-cover picture options, round transitions,
 * timers, scoring, and Click Wheel navigation.
 */
class MusicQuizController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val libraryProvider: () -> List<SongItem>,
    private val onExitRequested: () -> Unit,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val engine = MusicQuizEngine(activity)
    private val handler = Handler(Looper.getMainLooper())

    private val layoutQuiz: LinearLayout = rootView.findViewById(R.id.layout_music_quiz_mode)
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

    override fun onEnter(state: ScreenState) {
        layoutQuiz.visibility = View.VISIBLE
        startSession()
    }

    override fun onExit() {
        cancelTimer()
        engine.stopPreview()
        layoutQuiz.visibility = View.GONE
    }

    fun startSession() {
        wasPlayingBeforeStart = AudioPlayerManager.getInstance().isPlaying()
        if (wasPlayingBeforeStart) {
            AudioPlayerManager.getInstance().playOrPauseMusic()
        }

        val hasEnoughSongs = engine.resetSession(libraryProvider())
        if (!hasEnoughSongs) {
            Toast.makeText(activity, localize("Not enough songs in your library for Music Quiz."), Toast.LENGTH_LONG).show()
            exitQuiz()
            return
        }

        updateScoreLivesUI()
        loadNextQuestion()
    }

    private fun loadNextQuestion() {
        if (engine.isRoundComplete()) {
            if (engine.isVictory()) {
                showVictory()
            } else {
                showRoundCompleteTransition()
            }
            return
        }

        isAnswered = false
        val question = engine.nextQuestion { type, song ->
            val title = "“${song.title}”"
            when (type) {
                MusicQuizEngine.TYPE_ARTIST -> "${localize("Which artist released")} $title?"
                MusicQuizEngine.TYPE_ALBUM -> "${localize("Which album is")} $title ${localize("from?")}"
                MusicQuizEngine.TYPE_YEAR -> "${localize("What year was")} $title ${localize("released?")}"
                MusicQuizEngine.TYPE_GENRE -> "${localize("What genre is")} $title?"
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
        containerQuizItems.removeAllViews()

        if (question.type == MusicQuizEngine.TYPE_ALBUM_COVER) {
            containerQuizItems.orientation = LinearLayout.HORIZONTAL
            for (coverFile in question.coverOptions) {
                val tile = createCoverOptionTile(coverFile)
                tile.setOnClickListener {
                    clickFeedback()
                    onAnswerSelected(coverFile.parentFile?.name ?: "")
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
        val correct = engine.submitAnswer(selected, question)

        if (correct) {
            Toast.makeText(activity, "✅ " + localize("Correct!"), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                activity,
                "❌ " + localize("It was: ") + question.correctAnswer,
                Toast.LENGTH_SHORT
            ).show()
        }

        updateScoreLivesUI()

        handler.postDelayed({
            if (engine.isGameOver) {
                showGameOver()
            } else {
                loadNextQuestion()
            }
        }, 1200)
    }

    private fun startTimer() {
        cancelTimer()
        pbQuizTimer?.max = MusicQuizEngine.QUESTION_TIME_MS.toInt()
        pbQuizTimer?.progress = MusicQuizEngine.QUESTION_TIME_MS.toInt()

        countDownTimer = object : CountDownTimer(MusicQuizEngine.QUESTION_TIME_MS, 100) {
            override fun onTick(millisUntilFinished: Long) {
                pbQuizTimer?.progress = millisUntilFinished.toInt()
            }

            override fun onFinish() {
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
        tvQuizScore?.text = String.format(Locale.US, "$%,d", engine.score * 100)
        tvQuizRound?.text = engine.questionInRound.toString()
        if (tvQuizLives != null) {
            val hearts = StringBuilder()
            for (i in 0 until MusicQuizEngine.INITIAL_LIVES) {
                hearts.append(if (i < engine.lives) "♥ " else "♡ ")
            }
            tvQuizLives.text = hearts.toString().trim()
        }
    }

    private fun showRoundCompleteTransition() {
        pbQuizTimer?.progress = 0
        tvQuizPrompt?.text = "${localize("Round ")}${engine.roundNumber} ${localize("Complete!")}"
        updateScoreLivesUI()

        containerQuizItems.orientation = LinearLayout.VERTICAL
        containerQuizItems.removeAllViews()

        val btnContinue = createAnswerPill(localize("Continue"))
        btnContinue.setOnClickListener {
            clickFeedback()
            engine.advanceRound()
            loadNextQuestion()
        }
        containerQuizItems.addView(btnContinue)

        containerQuizItems.postDelayed({
            if (containerQuizItems.childCount > 0) {
                containerQuizItems.getChildAt(0).requestFocus()
            }
        }, 50)
    }

    private fun showVictory() {
        val prefs = activity.getSharedPreferences("AppPrefs", 0)
        var highScore = prefs.getInt("quiz_high_score", 0)
        val isNewHighScore = engine.score > highScore
        if (isNewHighScore) {
            prefs.edit().putInt("quiz_high_score", engine.score).apply()
            highScore = engine.score
        }

        tvQuizPrompt?.text = "🏆 " + localize("You beat all 5 rounds!") + "\n" +
                (if (isNewHighScore) localize("New High Score!") else "${localize("Best")}: $${highScore * 100}")
        pbQuizTimer?.progress = 0
        updateScoreLivesUI()

        containerQuizItems.orientation = LinearLayout.VERTICAL
        containerQuizItems.removeAllViews()

        val btnAgain = createAnswerPill(localize("Play Again"))
        btnAgain.setOnClickListener {
            clickFeedback()
            startSession()
        }
        containerQuizItems.addView(btnAgain)

        val btnExit = createAnswerPill(localize("Exit"))
        btnExit.setOnClickListener {
            clickFeedback()
            exitQuiz()
        }
        containerQuizItems.addView(btnExit)

        containerQuizItems.postDelayed({
            if (containerQuizItems.childCount > 0) {
                containerQuizItems.getChildAt(0).requestFocus()
            }
        }, 50)
    }

    private fun showGameOver() {
        val prefs = activity.getSharedPreferences("AppPrefs", 0)
        var highScore = prefs.getInt("quiz_high_score", 0)
        val isNewHighScore = engine.score > highScore
        if (isNewHighScore) {
            prefs.edit().putInt("quiz_high_score", engine.score).apply()
            highScore = engine.score
        }

        tvQuizPrompt?.text = localize("Game Over!") + "\n" +
                (if (isNewHighScore) "🏆 " + localize("New High Score!") else "${localize("Best")}: $${highScore * 100}")
        pbQuizTimer?.progress = 0
        updateScoreLivesUI()

        containerQuizItems.orientation = LinearLayout.VERTICAL
        containerQuizItems.removeAllViews()

        val btnAgain = createAnswerPill(localize("Play Again"))
        btnAgain.setOnClickListener {
            clickFeedback()
            startSession()
        }
        containerQuizItems.addView(btnAgain)

        val btnExit = createAnswerPill(localize("Exit"))
        btnExit.setOnClickListener {
            clickFeedback()
            exitQuiz()
        }
        containerQuizItems.addView(btnExit)

        containerQuizItems.postDelayed({
            if (containerQuizItems.childCount > 0) {
                containerQuizItems.getChildAt(0).requestFocus()
            }
        }, 50)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == 21) {
            val current = activity.currentFocus
            if (current != null && current.parent == containerQuizItems) {
                val idx = containerQuizItems.indexOfChild(current)
                for (i in idx - 1 downTo 0) {
                    val next = containerQuizItems.getChildAt(i)
                    if (next != null && next.visibility == View.VISIBLE && next.isFocusable) {
                        next.requestFocus()
                        clickFeedback()
                        break
                    }
                }
            }
            return true
        }

        if (keyCode == 22) {
            val current = activity.currentFocus
            if (current != null && current.parent == containerQuizItems) {
                val idx = containerQuizItems.indexOfChild(current)
                for (i in idx + 1 until containerQuizItems.childCount) {
                    val next = containerQuizItems.getChildAt(i)
                    if (next != null && next.visibility == View.VISIBLE && next.isFocusable) {
                        next.requestFocus()
                        clickFeedback()
                        break
                    }
                }
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val current = activity.currentFocus
            current?.performClick()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            exitQuiz()
            return true
        }

        return true
    }

    override fun onBackPressed(): Boolean {
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

    private fun createCoverOptionTile(songFile: File): LinearLayout {
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
            setImageResource(R.drawable.default_album)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        tile.addView(ivCover)

        loadQuizCoverArt(songFile) { bmp ->
            bmp?.let { ivCover.setImageBitmap(it) }
        }

        tile.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            tile.setBackgroundResource(
                if (hasFocus) R.drawable.quiz_answer_pill_focused else R.drawable.quiz_answer_pill_normal
            )
        }

        return tile
    }

    private fun loadQuizCoverArt(file: File, callback: (Bitmap?) -> Unit) {
        Thread {
            var art: Bitmap? = null
            try {
                val path = file.absolutePath
                val lower = path.lowercase(Locale.US)
                if (lower.endsWith(".m4a") || lower.endsWith(".alac")) {
                    val tags = AudioPlayerManager.getInstance().extractAlacMetadata(file)
                    if (tags != null && tags.size > 5 && tags[5] != null) {
                        val pic = tags[5] as ByteArray
                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                        art = BitmapFactory.decodeByteArray(pic, 0, pic.size, opts)
                    }
                } else {
                    val mmr = MediaMetadataRetriever()
                    val fis = FileInputStream(file)
                    try {
                        mmr.setDataSource(fis.fd)
                        val pic = mmr.embeddedPicture
                        if (pic != null && pic.isNotEmpty()) {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                            art = BitmapFactory.decodeByteArray(pic, 0, pic.size, opts)
                        }
                    } finally {
                        try { fis.close() } catch (ignored: Exception) {}
                        try { mmr.release() } catch (ignored: Exception) {}
                    }
                }
            } catch (ignored: Exception) {}

            if (art == null) {
                try {
                    val nameWithoutExt = file.nameWithoutExtension
                    val manualCoverFile = File(StoragePaths.getCoversDir(), "$nameWithoutExt.jpg")
                    if (manualCoverFile.exists()) {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                        art = BitmapFactory.decodeFile(manualCoverFile.absolutePath, opts)
                    }
                } catch (ignored: Exception) {}
            }

            val finalArt = art
            activity.runOnUiThread {
                callback(finalArt)
            }
        }.start()
    }
}
