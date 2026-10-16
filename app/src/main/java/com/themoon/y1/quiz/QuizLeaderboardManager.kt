package com.themoon.y1.quiz

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class LeaderboardEntry(
    val score: Int,
    val round: Int,
    val timestamp: Long
)

class QuizLeaderboardManager(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    companion object {
        private const val PREFS_NAME = "AppPrefs"
        private const val KEY_LEADERBOARD = "quiz_leaderboard_entries"
        private const val KEY_LEGACY_HIGH_SCORE = "quiz_high_score"
        const val MAX_ENTRIES = 10
    }

    fun getTopEntries(): List<LeaderboardEntry> {
        val jsonStr = prefs.getString(KEY_LEADERBOARD, null) ?: return emptyList()
        val list = ArrayList<LeaderboardEntry>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val score = obj.optInt("score", 0)
                val round = obj.optInt("round", 1)
                val time = obj.optLong("time", 0L)
                list.add(LeaderboardEntry(score, round, time))
            }
        } catch (ignored: Exception) {}

        list.sortWith(compareByDescending<LeaderboardEntry> { it.score }.thenByDescending { it.timestamp })
        return list
    }

    /**
     * Records a score to the leaderboard.
     * Returns true if this is the all-time high score (#1).
     */
    fun recordScore(score: Int, round: Int, timestamp: Long = System.currentTimeMillis()): Boolean {
        if (score <= 0) return false

        val entries = ArrayList(getTopEntries())
        val previousHighScore = entries.firstOrNull()?.score ?: prefs.getInt(KEY_LEGACY_HIGH_SCORE, 0)

        entries.add(LeaderboardEntry(score, round, timestamp))
        entries.sortWith(compareByDescending<LeaderboardEntry> { it.score }.thenByDescending { it.timestamp })

        val trimmed = if (entries.size > MAX_ENTRIES) entries.subList(0, MAX_ENTRIES) else entries

        try {
            val jsonArray = JSONArray()
            for (entry in trimmed) {
                val obj = JSONObject()
                obj.put("score", entry.score)
                obj.put("round", entry.round)
                obj.put("time", entry.timestamp)
                jsonArray.put(obj)
            }
            val isNewHighScore = score > previousHighScore
            val editor = prefs.edit()
            editor.putString(KEY_LEADERBOARD, jsonArray.toString())
            if (isNewHighScore) {
                editor.putInt(KEY_LEGACY_HIGH_SCORE, score)
            }
            editor.apply()
            return isNewHighScore
        } catch (ignored: Exception) {
            return false
        }
    }

    fun getHighScore(): Int {
        val top = getTopEntries().firstOrNull()?.score
        return top ?: prefs.getInt(KEY_LEGACY_HIGH_SCORE, 0)
    }

    fun clearLeaderboard() {
        prefs.edit().remove(KEY_LEADERBOARD).remove(KEY_LEGACY_HIGH_SCORE).apply()
    }
}
