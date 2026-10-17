package com.themoon.y1.managers

import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayList
import java.util.TreeMap

/**
 * Parses chapter metadata from M4B/MP4 audio container chpl atoms.
 */
class M4bChapterParser {

    class ChapterResult {

        var chaptersMap: TreeMap<Int, String> = TreeMap()
        var timestamps: MutableList<Int> = ArrayList()
    }

    companion object {

        fun extractChapters(m4bFile: File?): ChapterResult {
            val result = ChapterResult()
            if (m4bFile == null || !m4bFile.exists() || !m4bFile.name.lowercase().endsWith(".m4b")) {
                return result
            }

            try {
                val raf = RandomAccessFile(m4bFile, "r")
                var pos: Long = 0
                val length = raf.length()

                while (pos < length) {
                    raf.seek(pos)
                    val size = raf.readInt()
                    if (size < 8) break

                    val typeBytes = ByteArray(4)
                    raf.read(typeBytes)
                    val type = String(typeBytes)

                    if (type == "moov" || type == "udta") {
                        pos += 8
                        continue
                    }

                    if (type == "chpl") {
                        val version = raf.readUnsignedByte()
                        raf.skipBytes(3) // skip flags
                        raf.skipBytes(1) // skip reserved

                        val chapterCount = if (version == 1) {
                            raf.readInt()
                        } else {
                            raf.readUnsignedByte()
                        }

                        for (i in 0 until chapterCount) {
                            val startTime100ns = raf.readLong()
                            val startTimeMs = (startTime100ns / 10000).toInt()

                            val titleLen = raf.readUnsignedByte()
                            val titleBytes = ByteArray(titleLen)
                            raf.read(titleBytes)
                            val title = String(titleBytes, Charsets.UTF_8)

                            result.chaptersMap[startTimeMs] = "\uD83D\uDD16 $title"
                        }
                        break
                    }
                    pos += size.toLong()
                }
                raf.close()

                result.timestamps.addAll(result.chaptersMap.keys)

            } catch (e: Exception) {
                e.printStackTrace()
            }

            return result
        }
    }
}
