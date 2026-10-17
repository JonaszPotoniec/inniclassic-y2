package com.themoon.y1.controllers.player

import android.content.Context
import android.text.Html
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.ThemeManager
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.M4bChapterParser
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.util.ArrayList
import java.util.TreeMap
import java.util.regex.Pattern

/**
 * Controller and parser for synchronized LRC, embedded USLT ID3, and M4B chapter lyrics.
 * Manages the overlay ScrollView and synchronized highlight updates.
 */
class LyricsOverlayController(
    private val context: Context,
    private val playerInnerLayout: ViewGroup
) {

    val lyricScrollView: ScrollView = ScrollView(context).apply {
        visibility = View.GONE
        isScrollbarFadingEnabled = true
    }

    val tvLyrics: TextView = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 16f
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        setLineSpacing(10f, 1.2f)
        setPadding(20, 40, 20, 40)
        typeface = ThemeManager.getCustomFontBold()
    }

    private val currentLyrics = TreeMap<Int, String>()
    private var lyricTimestamps = ArrayList<Int>()
    private var plainLyrics: String? = null
    private var lastLyricIndex = -1

    val hasLyrics: Boolean
        get() = currentLyrics.isNotEmpty() || plainLyrics != null

    init {
        val visLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        lyricScrollView.addView(
            tvLyrics,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        playerInnerLayout.addView(lyricScrollView, 0, visLp)
    }

    fun show() {
        if (!hasLyrics) return
        lyricScrollView.visibility = View.VISIBLE
        if (plainLyrics != null && currentLyrics.isEmpty()) {
            lyricScrollView.post { lyricScrollView.scrollTo(0, 0) }
        }
    }

    fun hide() {
        lyricScrollView.visibility = View.GONE
    }

    fun isShowing(): Boolean = lyricScrollView.visibility == View.VISIBLE

    fun loadLyrics(audioFile: File?) {
        currentLyrics.clear()
        lyricTimestamps.clear()
        lastLyricIndex = -1
        plainLyrics = null
        tvLyrics.text = ""

        if (audioFile == null || !audioFile.exists()) return
        val path = audioFile.absolutePath

        // 1. M4B audiobooks: extract chapter timestamps
        if (path.endsWith(".m4b", ignoreCase = true)) {
            val chResult = M4bChapterParser.extractChapters(audioFile)
            if (chResult.chaptersMap.isNotEmpty()) {
                currentLyrics.putAll(chResult.chaptersMap)
                lyricTimestamps.addAll(chResult.timestamps)
                return
            }
        }

        // 2. External .lrc file in same directory
        val dotIdx = path.lastIndexOf(".")
        if (dotIdx > 0) {
            val lrcFile = File(path.substring(0, dotIdx) + ".lrc")
            if (lrcFile.exists()) {
                try {
                    val br = BufferedReader(InputStreamReader(FileInputStream(lrcFile), "UTF-8"))
                    val pattern = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")
                    var line: String?

                    while (br.readLine().also { line = it } != null) {
                        val m = pattern.matcher(line ?: "")
                        if (m.find()) {
                            val min = m.group(1)?.toInt() ?: 0
                            val sec = m.group(2)?.toInt() ?: 0
                            val msStr = m.group(3) ?: "0"
                            var ms = msStr.toInt()
                            if (msStr.length == 2) ms *= 10
                            val totalMs = (min * 60 * 1000) + (sec * 1000) + ms
                            val text = m.group(4)?.trim() ?: ""
                            if (text.isNotEmpty()) {
                                currentLyrics[totalMs] = text
                            }
                        }
                    }
                    br.close()
                    lyricTimestamps = ArrayList(currentLyrics.keys)
                    return
                } catch (ignored: Exception) {}
            }
        }

        // 3. Embedded tags: ALAC / FLAC / MP3 ID3 USLT
        val lower = path.lowercase()
        if (lower.endsWith(".m4a") || lower.endsWith(".alac")) {
            val alacTags = AudioPlayerManager.getInstance().extractAlacMetadata(audioFile)
            if (alacTags.size > 7 && alacTags[7] != null) {
                plainLyrics = alacTags[7] as? String
            }
        } else if (lower.endsWith(".flac")) {
            val flacTags = AudioPlayerManager.getInstance().extractFlacMetadata(audioFile)
            if (flacTags.size > 7 && flacTags[7] != null) {
                plainLyrics = flacTags[7] as? String
            }
        } else {
            plainLyrics = extractEmbeddedLyrics(audioFile)
        }

        // 4. Test if plain lyrics string contains embedded LRC timestamps
        val candidate = plainLyrics
        if (!candidate.isNullOrEmpty()) {
            val pattern = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")
            var isSyncedLrc = false
            val lines = candidate.split("\\r?\\n".toRegex())

            for (l in lines) {
                val m = pattern.matcher(l)
                if (m.find()) {
                    isSyncedLrc = true
                    val min = m.group(1)?.toInt() ?: 0
                    val sec = m.group(2)?.toInt() ?: 0
                    val msStr = m.group(3) ?: "0"
                    var ms = msStr.toInt()
                    if (msStr.length == 2) ms *= 10
                    val totalMs = (min * 60 * 1000) + (sec * 1000) + ms
                    val text = m.group(4)?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        currentLyrics[totalMs] = text
                    }
                }
            }

            if (isSyncedLrc) {
                lyricTimestamps = ArrayList(currentLyrics.keys)
                plainLyrics = null
                return
            }

            tvLyrics.text = candidate
        }
    }

    private fun extractEmbeddedLyrics(file: File): String? {
        try {
            val raf = RandomAccessFile(file, "r")
            val header = ByteArray(10)
            raf.readFully(header)

            if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                val majorVersion = header[3].toInt()
                val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                        ((header[7].toInt() and 0x7F) shl 14) or
                        ((header[8].toInt() and 0x7F) shl 7) or
                        (header[9].toInt() and 0x7F)

                val readSize = Math.min(tagSize, 512 * 1024)
                val tagData = ByteArray(readSize)
                val actualRead = raf.read(tagData)
                raf.close()

                var pos = 0
                while (pos < actualRead - 10) {
                    val frameId = String(tagData, pos, 4)
                    val frameSize: Int = if (majorVersion == 4) {
                        ((tagData[pos + 4].toInt() and 0x7F) shl 21) or
                                ((tagData[pos + 5].toInt() and 0x7F) shl 14) or
                                ((tagData[pos + 6].toInt() and 0x7F) shl 7) or
                                (tagData[pos + 7].toInt() and 0x7F)
                    } else {
                        ((tagData[pos + 4].toInt() and 0xFF) shl 24) or
                                ((tagData[pos + 5].toInt() and 0xFF) shl 16) or
                                ((tagData[pos + 6].toInt() and 0xFF) shl 8) or
                                (tagData[pos + 7].toInt() and 0xFF)
                    }

                    if (frameSize <= 0 || frameSize > actualRead - pos - 10) break

                    if (frameId == "USLT") {
                        val encoding = tagData[pos + 10].toInt()
                        var textPos = pos + 14

                        if (encoding == 1 || encoding == 2) {
                            while (textPos < pos + 10 + frameSize - 1) {
                                if (tagData[textPos].toInt() == 0 && tagData[textPos + 1].toInt() == 0) {
                                    textPos += 2
                                    break
                                }
                                textPos++
                            }
                        } else {
                            while (textPos < pos + 10 + frameSize) {
                                if (tagData[textPos].toInt() == 0) {
                                    textPos += 1
                                    break
                                }
                                textPos++
                            }
                        }

                        val lyricsLength = (pos + 10 + frameSize) - textPos
                        if (lyricsLength > 0) {
                            val charset = when (encoding) {
                                0 -> "ISO-8859-1"
                                1 -> "UTF-16"
                                2 -> "UTF-16BE"
                                else -> "UTF-8"
                            }
                            return String(tagData, textPos, lyricsLength, charset(charset)).trim()
                        }
                    }
                    pos += 10 + frameSize
                }
            } else {
                raf.close()
            }
        } catch (ignored: Exception) {}
        return null
    }

    fun updateProgress(currentPos: Int, duration: Int) {
        if (!isShowing()) return

        // 1. Plain text unsynced lyrics: autoscroll proportionally
        if (plainLyrics != null && currentLyrics.isEmpty()) {
            val maxScroll = tvLyrics.height - lyricScrollView.height
            if (maxScroll > 0 && duration > 0) {
                val progressRatio = currentPos.toFloat() / duration.toFloat()
                if (progressRatio < 0.15f) {
                    lyricScrollView.smoothScrollTo(0, 0)
                } else if (progressRatio > 0.85f) {
                    lyricScrollView.smoothScrollTo(0, maxScroll)
                } else {
                    val normalizedRatio = (progressRatio - 0.15f) / 0.70f
                    val targetScroll = (maxScroll * normalizedRatio).toInt()
                    lyricScrollView.smoothScrollTo(0, targetScroll)
                }
            }
            return
        }

        // 2. Synchronized LRC: find active lyric line and highlight
        if (currentLyrics.isNotEmpty()) {
            val entry = currentLyrics.floorEntry(currentPos)
            if (entry != null) {
                val activeTimestamp = entry.key
                val activeIndex = lyricTimestamps.indexOf(activeTimestamp)

                if (activeIndex != -1 && activeIndex != lastLyricIndex) {
                    lastLyricIndex = activeIndex

                    val sb = StringBuilder()
                    val totalLines = lyricTimestamps.size
                    val startLine = Math.max(0, activeIndex - 1)
                    val endLine = Math.min(totalLines - 1, activeIndex + 2)

                    for (i in startLine..endLine) {
                        val lyricText = currentLyrics[lyricTimestamps[i]]
                        if (i == activeIndex) {
                            sb.append("<font color='#00FF00'><b>").append(lyricText).append("</b></font><br><br>")
                        } else {
                            sb.append("<font color='#77FFFFFF'>").append(lyricText).append("</font><br><br>")
                        }
                    }

                    tvLyrics.text = Html.fromHtml(sb.toString())
                    tvLyrics.setPadding(20, 10, 20, 10)
                    lyricScrollView.scrollTo(0, 0)
                }
            }
        }
    }
}
