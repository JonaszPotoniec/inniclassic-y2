package com.themoon.y1.quiz

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import com.themoon.y1.managers.VirtualVolumeManager
import com.themoon.y1.models.SongItem
import java.io.File
import java.util.Collections
import java.util.Random

/**
 * Headless state and game loop engine for iPod Music Quiz.
 * Manages question pool building, 6 question types, 5 rounds x 8 questions,
 * lives/scoring, and audio sample preview player.
 */
class MusicQuizEngine(private val context: Context) {

    companion object {
        const val QUESTIONS_PER_ROUND = 8
        const val TOTAL_ROUNDS = 5
        const val QUESTION_TIME_MS = 15000L
        const val INITIAL_LIVES = 3

        const val TYPE_TITLE = 0
        const val TYPE_ARTIST = 1
        const val TYPE_ALBUM = 2
        const val TYPE_YEAR = 3
        const val TYPE_GENRE = 4
        const val TYPE_ALBUM_COVER = 5
    }

    data class QuizQuestion(
        val type: Int,
        val promptText: String,
        val song: SongItem,
        val correctAnswer: String,
        val textOptions: List<String> = emptyList(),
        val coverOptions: List<File> = emptyList()
    )

    var score: Int = 0
        private set
    var roundNumber: Int = 1
        private set
    var questionInRound: Int = 0
        private set
    var lives: Int = INITIAL_LIVES
        private set
    var isGameOver: Boolean = false
        private set

    private val quizPool = ArrayList<SongItem>()
    private var previewPlayer: MediaPlayer? = null
    private val random = Random()

    fun resetSession(library: List<SongItem>): Boolean {
        score = 0
        roundNumber = 1
        questionInRound = 0
        lives = INITIAL_LIVES
        isGameOver = false

        stopPreview()

        quizPool.clear()
        for (song in library) {
            val path = song.file.absolutePath
            val lower = path.lowercase()
            if (path.contains("/Audiobooks") || path.contains("/Podcasts") || path.startsWith("/PODCAST_STREAM")) {
                continue
            }
            if (lower.endsWith(".flac") || lower.endsWith(".opus") || lower.endsWith(".ogg")) {
                continue
            }
            if (song.title.isNullOrBlank()) {
                continue
            }
            quizPool.add(song)
        }

        return quizPool.size >= 4
    }

    fun isRoundComplete(): Boolean = questionInRound >= QUESTIONS_PER_ROUND
    fun isVictory(): Boolean = isRoundComplete() && roundNumber >= TOTAL_ROUNDS

    fun advanceRound() {
        roundNumber++
        questionInRound = 0
    }

    fun nextQuestion(promptResolver: (Int, SongItem) -> String): QuizQuestion? {
        if (quizPool.size < 4 || isGameOver) return null
        questionInRound++

        val availableTypes = ArrayList<Int>()
        availableTypes.add(TYPE_TITLE)
        if (countDistinctField(TYPE_ARTIST) >= 4) availableTypes.add(TYPE_ARTIST)
        if (countDistinctField(TYPE_ALBUM) >= 4) availableTypes.add(TYPE_ALBUM)
        if (countDistinctField(TYPE_YEAR) >= 4) availableTypes.add(TYPE_YEAR)
        if (countDistinctField(TYPE_GENRE) >= 4) availableTypes.add(TYPE_GENRE)
        if (countDistinctField(TYPE_ALBUM_COVER) >= 3) availableTypes.add(TYPE_ALBUM_COVER)

        val selectedType = availableTypes[random.nextInt(availableTypes.size)]

        val typePool = ArrayList<SongItem>()
        for (s in quizPool) {
            if (isValidField(s, selectedType)) typePool.add(s)
        }
        val pool = if (typePool.isNotEmpty()) typePool else quizPool

        val targetSong = pool[random.nextInt(pool.size)]
        val correctText = getFieldValue(targetSong, selectedType) ?: targetSong.title ?: ""
        val prompt = promptResolver(selectedType, targetSong)

        startPreview(targetSong.file)

        if (selectedType == TYPE_ALBUM_COVER) {
            val albumReps = LinkedHashMap<String, SongItem>()
            for (s in pool) {
                val key = s.album?.trim()?.lowercase() ?: ""
                if (!albumReps.containsKey(key)) albumReps[key] = s
            }
            albumReps[targetSong.album?.trim()?.lowercase() ?: ""] = targetSong

            val repList = ArrayList(albumReps.values)
            Collections.shuffle(repList)

            val coverOptions = ArrayList<SongItem>()
            coverOptions.add(targetSong)
            for (rep in repList) {
                if (coverOptions.size >= 3) break
                val exists = coverOptions.any { it.album.equals(rep.album, ignoreCase = true) }
                if (!exists) coverOptions.add(rep)
            }
            Collections.shuffle(coverOptions)

            return QuizQuestion(
                type = selectedType,
                promptText = prompt,
                song = targetSong,
                correctAnswer = targetSong.album ?: "",
                coverOptions = coverOptions.map { it.file }
            )
        } else {
            val options = LinkedHashSet<String>()
            options.add(correctText)
            var guard = 0
            while (options.size < 4 && guard < 200) {
                guard++
                val candidate = pool[random.nextInt(pool.size)]
                val candText = getFieldValue(candidate, selectedType) ?: continue
                if (!options.any { it.equals(candText, ignoreCase = true) }) {
                    options.add(candText)
                }
            }
            val optionList = ArrayList(options)
            while (optionList.size < 4 && pool.isNotEmpty()) {
                val fallbackText = getFieldValue(pool[random.nextInt(pool.size)], selectedType) ?: ""
                optionList.add(fallbackText)
            }
            Collections.shuffle(optionList)

            return QuizQuestion(
                type = selectedType,
                promptText = prompt,
                song = targetSong,
                correctAnswer = correctText,
                textOptions = optionList
            )
        }
    }

    fun submitAnswer(selected: String?, question: QuizQuestion): Boolean {
        stopPreview()
        val correct = selected != null && selected.equals(question.correctAnswer, ignoreCase = true)
        if (correct) {
            score++
        } else {
            lives--
            if (lives <= 0) {
                isGameOver = true
            }
        }
        return correct
    }

    private fun countDistinctField(type: Int): Int {
        val distinct = HashSet<String>()
        for (s in quizPool) {
            if (isValidField(s, type)) {
                getFieldValue(s, type)?.trim()?.lowercase()?.let { distinct.add(it) }
            }
        }
        return distinct.size
    }

    private fun isValidField(s: SongItem, type: Int): Boolean {
        if (type == TYPE_TITLE) return !s.title.isNullOrBlank()
        val v = getFieldValue(s, type)
        return !v.isNullOrBlank() && !v.trim().lowercase().startsWith("unknown")
    }

    private fun getFieldValue(s: SongItem, type: Int): String? {
        return when (type) {
            TYPE_ARTIST -> s.artist
            TYPE_ALBUM, TYPE_ALBUM_COVER -> s.album
            TYPE_YEAR -> s.year
            TYPE_GENRE -> s.genre
            else -> s.title
        }
    }

    private fun startPreview(file: File) {
        stopPreview()
        try {
            val mp = MediaPlayer()
            previewPlayer = mp
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setDataSource(file.absolutePath)
            VirtualVolumeManager.getInstance().applyTo(mp)

            mp.setOnPreparedListener { player ->
                try {
                    VirtualVolumeManager.getInstance().applyTo(player)
                    val duration = player.duration
                    var start = 0
                    if (duration > 20000) {
                        val maxStart = duration - 12000
                        start = (maxStart * 0.2).toInt() + random.nextInt((maxStart * 0.5).toInt().coerceAtLeast(1))
                    }
                    player.seekTo(start)
                    player.start()
                } catch (ignored: Exception) {}
            }

            mp.setOnCompletionListener { player ->
                try {
                    player.seekTo(0)
                    player.start()
                } catch (ignored: Exception) {}
            }

            mp.prepareAsync()
        } catch (ignored: Exception) {}
    }

    fun stopPreview() {
        previewPlayer?.let { mp ->
            VirtualVolumeManager.getInstance().unregisterMediaPlayer(mp)
            try { mp.stop() } catch (ignored: Exception) {}
            try { mp.release() } catch (ignored: Exception) {}
        }
        previewPlayer = null
    }
}
