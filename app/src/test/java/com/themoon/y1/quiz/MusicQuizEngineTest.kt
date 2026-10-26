package com.themoon.y1.quiz

import com.themoon.y1.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

class MusicQuizEngineTest {

    // A fixed seed makes the random questions the same at each run.
    private fun newEngine() = MusicQuizEngine(random = Random(42))

    private fun createSong(
        title: String,
        artist: String = "Artist A",
        album: String = "Album A",
        year: String = "2000",
        path: String = "/storage/sdcard0/Music/$title.mp3"
    ): SongItem {
        return SongItem(File(path), title, artist, album, year, "Rock")
    }

    @Test
    fun testResetSession_validation() {
        val engine = newEngine()
        val emptyList = emptyList<SongItem>()
        assertFalse(engine.resetSession(emptyList))

        val songs = listOf(
            createSong("Song 1"),
            createSong("Song 2"),
            createSong("Song 3")
        )
        assertFalse(engine.resetSession(songs))

        val fourSongs = listOf(
            createSong("Song 1"),
            createSong("Song 2"),
            createSong("Song 3"),
            createSong("Song 4")
        )
        assertTrue(engine.resetSession(fourSongs))
        assertEquals(1, engine.roundNumber)
        assertEquals(0, engine.questionInRound)
        assertEquals(3, engine.lives)
        assertEquals(0, engine.score)
        assertFalse(engine.isGameOver)
    }

    @Test
    fun testQuestionsPerRound_isSix() {
        val engine = newEngine()
        val songs = (1..10).map { createSong("Song $it") }
        engine.resetSession(songs)

        assertFalse(engine.isRoundComplete())
        for (i in 1..5) {
            engine.nextQuestion { _, song -> song.title }
            assertEquals(i, engine.questionInRound)
            assertFalse(engine.isRoundComplete())
        }
        engine.nextQuestion { _, song -> song.title }
        assertEquals(6, engine.questionInRound)
        assertTrue(engine.isRoundComplete())
    }

    @Test
    fun testNonLinearQuestionTimeScaling() {
        val engine = newEngine()
        val songs = (1..10).map { createSong("Song $it") }
        engine.resetSession(songs)

        assertEquals(15000L, engine.getQuestionTimeMs()) // Round 1
        engine.advanceRound()
        assertEquals(13500L, engine.getQuestionTimeMs()) // Round 2
        engine.advanceRound()
        assertEquals(11500L, engine.getQuestionTimeMs()) // Round 3
        engine.advanceRound()
        assertEquals(9500L, engine.getQuestionTimeMs())  // Round 4
        engine.advanceRound()
        assertEquals(8000L, engine.getQuestionTimeMs())  // Round 5
        engine.advanceRound()
        assertEquals(6800L, engine.getQuestionTimeMs())  // Round 6
        engine.advanceRound()
        assertEquals(5800L, engine.getQuestionTimeMs())  // Round 7
        engine.advanceRound()
        assertEquals(5000L, engine.getQuestionTimeMs())  // Round 8 (floor)
        engine.advanceRound()
        assertEquals(5000L, engine.getQuestionTimeMs())  // Round 9 (remains floor)
    }

    @Test
    fun testPerfectRoundLifeRecovery_cappedAtFive() {
        val engine = newEngine()
        val songs = (1..10).map { createSong("Song $it") }
        engine.resetSession(songs)
        assertEquals(3, engine.lives)

        // Perfect round: 0 mistakes
        var bonus = engine.advanceRound()
        assertTrue(bonus)
        assertEquals(4, engine.lives)

        // Another perfect round: reaches cap of 5
        bonus = engine.advanceRound()
        assertTrue(bonus)
        assertEquals(5, engine.lives)

        // Cap reached: no more lives awarded above MAX_LIVES (5)
        bonus = engine.advanceRound()
        assertFalse(bonus)
        assertEquals(5, engine.lives)

        // Simulate mistake
        val q = engine.nextQuestion { _, song -> song.title }!!
        engine.submitAnswer("Definitely Wrong Answer", q)
        assertEquals(4, engine.lives)
        assertEquals(1, engine.mistakesInRound)

        // Imperfect round should NOT award life
        bonus = engine.advanceRound()
        assertFalse(bonus)
        assertEquals(4, engine.lives)
    }

    @Test
    fun testCoverArtPredicate_filtersCoverlessAlbums() {
        val engine = newEngine()
        val songs = listOf(
            createSong("Song 1", album = "Album 1"),
            createSong("Song 2", album = "Album 2"),
            createSong("Song 3", album = "Album 3"),
            createSong("Song 4", album = "Album 4"),
            createSong("Song 5", album = "Album 5")
        )

        // Case 1: coverArtPredicate is null -> Album cover never selected
        engine.resetSession(songs)
        for (i in 0 until 50) {
            val q = engine.nextQuestion { _, s -> s.title }
            assertNotNull(q)
            assertTrue("Type should never be ALBUM_COVER when predicate is null", q!!.type != MusicQuizEngine.TYPE_ALBUM_COVER)
        }

        // Case 2: Only 2 albums have artwork (less than threshold of 3)
        val albumsWithArt = setOf("Album 1", "Album 2")
        engine.resetSession(songs)
        engine.coverArtPredicate = { it.album in albumsWithArt }
        for (i in 0 until 50) {
            val q = engine.nextQuestion { _, s -> s.title }
            assertNotNull(q)
            assertTrue("Type should not be ALBUM_COVER with < 3 covered albums", q!!.type != MusicQuizEngine.TYPE_ALBUM_COVER)
        }

        // Case 3: 3 albums have artwork -> Album cover questions CAN be generated, and only use those 3 albums
        val threeAlbumsWithArt = setOf("Album 1", "Album 2", "Album 3")
        engine.resetSession(songs)
        engine.coverArtPredicate = { it.album in threeAlbumsWithArt }

        var foundCoverQuestion = false
        for (i in 0 until 200) {
            val q = engine.nextQuestion { _, s -> s.title } ?: break
            if (q.type == MusicQuizEngine.TYPE_ALBUM_COVER) {
                foundCoverQuestion = true
                assertTrue(q.correctAnswer in threeAlbumsWithArt)
                for (coverOpt in q.coverOptions) {
                    assertTrue(coverOpt.album in threeAlbumsWithArt)
                }
            }
        }
        assertTrue("Should have generated at least one ALBUM_COVER question", foundCoverQuestion)
    }

    @Test
    fun testTieredYearDistractorDistances_notCentered() {
        val engine = newEngine()
        val songs = listOf(
            createSong("Song 1", year = "2000"),
            createSong("Song 2", year = "1995"),
            createSong("Song 3", year = "1980"),
            createSong("Song 4", year = "1970")
        )
        engine.resetSession(songs)

        var correctWasMin = false
        var correctWasMax = false

        // Test Round 1 (Tier 10..20)
        for (i in 0 until 50) {
            val q = engine.nextQuestion { type, s -> s.title } ?: break
            if (q.type == MusicQuizEngine.TYPE_YEAR) {
                val correct = q.correctAnswer.toInt()
                val sorted = q.textOptions.map { it.toInt() }.sorted()
                assertEquals(4, sorted.size)
                assertTrue("Correct year must be in options", sorted.contains(correct))
                if (sorted.first() == correct) correctWasMin = true
                if (sorted.last() == correct) correctWasMax = true

                for (j in 0 until 3) {
                    val diff = sorted[j + 1] - sorted[j]
                    assertTrue("Diff $diff should be >= 10 for R1-2", diff >= 10)
                }
            }
        }
        assertTrue("Correct year should sometimes be the minimum option", correctWasMin)
        assertTrue("Correct year should sometimes be the maximum option", correctWasMax)

        // Advance to Round 4 (Tier 5..15)
        for (r in 1..3) engine.advanceRound()
        assertEquals(4, engine.roundNumber)
        for (i in 0 until 30) {
            val q = engine.nextQuestion { type, s -> s.title } ?: break
            if (q.type == MusicQuizEngine.TYPE_YEAR) {
                val sorted = q.textOptions.map { it.toInt() }.sorted()
                for (j in 0 until 3) {
                    val diff = sorted[j + 1] - sorted[j]
                    assertTrue("Diff $diff should be >= 5 for R3-5", diff >= 5)
                }
            }
        }

        // Advance to Round 7 (Tier 3..8)
        for (r in 4..6) engine.advanceRound()
        assertEquals(7, engine.roundNumber)
        for (i in 0 until 30) {
            val q = engine.nextQuestion { type, s -> s.title } ?: break
            if (q.type == MusicQuizEngine.TYPE_YEAR) {
                val sorted = q.textOptions.map { it.toInt() }.sorted()
                for (j in 0 until 3) {
                    val diff = sorted[j + 1] - sorted[j]
                    assertTrue("Diff $diff should be >= 3 for R6-8", diff >= 3)
                }
            }
        }

        // Advance to Round 9 (Tier 1..5)
        for (r in 7..8) engine.advanceRound()
        assertEquals(9, engine.roundNumber)
        for (i in 0 until 30) {
            val q = engine.nextQuestion { type, s -> s.title } ?: break
            if (q.type == MusicQuizEngine.TYPE_YEAR) {
                val sorted = q.textOptions.map { it.toInt() }.sorted()
                for (j in 0 until 3) {
                    val diff = sorted[j + 1] - sorted[j]
                    assertTrue("Diff $diff should be >= 1 for R8+", diff >= 1)
                }
            }
        }
    }

    @Test
    fun testTimeDecayScoring() {
        val engine = newEngine()
        val songs = (1..10).map { createSong("Song $it") }
        engine.resetSession(songs)

        val q1 = engine.nextQuestion { _, s -> s.title }!!
        // Instant answer: fraction 1.0f -> 100 + 900 = 1000 base pts
        engine.submitAnswer(q1.correctAnswer, q1, remainingFraction = 1.0f)
        assertEquals(1000, engine.score)

        val q2 = engine.nextQuestion { _, s -> s.title }!!
        // Delayed answer: fraction 0.0f -> 100 base pts
        engine.submitAnswer(q2.correctAnswer, q2, remainingFraction = 0.0f)
        assertEquals(1100, engine.score)
    }

    @Test
    fun testBuyHeart_pricingAndLimits() {
        val engine = newEngine()
        val songs = (1..10).map { createSong("Song $it") }
        engine.resetSession(songs)

        assertEquals(3, engine.lives)
        assertEquals(0, engine.score)
        assertFalse(engine.heartBoughtThisRound)

        // 1. Cannot buy heart with 0 score (insufficient funds)
        assertFalse(engine.canBuyHeart())
        assertFalse(engine.buyHeart())
        assertEquals(3, engine.lives)
        assertEquals(0, engine.score)

        // 2. Score points by answering questions
        val q1 = engine.nextQuestion { _, s -> s.title }!!
        engine.submitAnswer(q1.correctAnswer, q1, remainingFraction = 1.0f) // 1000 pts
        val q2 = engine.nextQuestion { _, s -> s.title }!!
        engine.submitAnswer(q2.correctAnswer, q2, remainingFraction = 1.0f) // 1000 pts -> 2000 total

        assertEquals(2000, engine.score)
        assertEquals(3, engine.lives)
        assertTrue(engine.canBuyHeart())

        // 3. Purchase 1 heart
        assertTrue(engine.buyHeart())
        assertEquals(500, engine.score) // 2000 - 1500 = 500
        assertEquals(4, engine.lives)   // 3 + 1 = 4
        assertTrue(engine.heartBoughtThisRound)

        // 4. Cannot buy more than 1 heart in same round
        assertFalse(engine.canBuyHeart())
        assertFalse(engine.buyHeart())
        assertEquals(500, engine.score)
        assertEquals(4, engine.lives)

        // Make an intentional mistake so perfect round life is not awarded on advanceRound
        val qWrong = engine.nextQuestion { _, s -> s.title }!!
        engine.submitAnswer("Wrong Answer", qWrong)
        assertEquals(3, engine.lives) // 4 - 1 = 3
        assertEquals(1, engine.mistakesInRound)

        // 5. Advance to next round -> reset heartBoughtThisRound
        val bonus = engine.advanceRound()
        assertFalse(bonus) // No bonus due to mistake
        assertFalse(engine.heartBoughtThisRound)
        assertEquals(3, engine.lives)

        // Still cannot buy because score is 500 (< 1500)
        assertFalse(engine.canBuyHeart())
        assertFalse(engine.buyHeart())

        // Score more points in round 2 (multiplier is 1.1x -> 1100 pts)
        val q3 = engine.nextQuestion { _, s -> s.title }!!
        engine.submitAnswer(q3.correctAnswer, q3, remainingFraction = 1.0f) // 1100 pts -> 1600 total
        assertEquals(1600, engine.score)
        assertTrue(engine.canBuyHeart())

        // Buy heart in second round -> reaches 4 lives
        assertTrue(engine.buyHeart())
        assertEquals(100, engine.score) // 1600 - 1500 = 100
        assertEquals(4, engine.lives)
        assertTrue(engine.heartBoughtThisRound)

        // 6. Next round: perfect round awards +1 life, reaching MAX_LIVES (5)
        val round2Bonus = engine.advanceRound()
        assertTrue(round2Bonus)
        assertFalse(engine.heartBoughtThisRound)
        assertEquals(5, engine.lives) // MAX_LIVES reached

        val q4 = engine.nextQuestion { _, s -> s.title }!!
        engine.submitAnswer(q4.correctAnswer, q4, remainingFraction = 1.0f) // +1200 -> 1300
        val q5 = engine.nextQuestion { _, s -> s.title }!!
        engine.submitAnswer(q5.correctAnswer, q5, remainingFraction = 1.0f) // +1200 -> 2500
        assertEquals(2500, engine.score)

        // Has 2500 score (>= 1500) and hasn't bought this round, but lives == MAX_LIVES (5) -> cannot buy!
        assertFalse(engine.canBuyHeart())
        assertFalse(engine.buyHeart())
        assertEquals(2500, engine.score)
        assertEquals(5, engine.lives)
    }
}
