package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Modifier

class M4bChapterParserParityTest {

    private fun createAtom(type: String, payload: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        val totalSize = 8 + payload.size
        dos.writeInt(totalSize)
        dos.write(type.toByteArray(Charsets.US_ASCII))
        dos.write(payload)
        return baos.toByteArray()
    }

    private fun createChplPayload(
        version: Int,
        chapters: List<Pair<Long, String>>
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(version) // 1 byte version
        dos.write(byteArrayOf(0, 0, 0)) // 3 bytes flags
        dos.writeByte(0) // 1 byte reserved

        if (version == 1) {
            dos.writeInt(chapters.size)
        } else {
            dos.writeByte(chapters.size)
        }

        for ((startTime100ns, title) in chapters) {
            dos.writeLong(startTime100ns)
            val titleBytes = title.toByteArray(Charsets.UTF_8)
            dos.writeByte(titleBytes.size)
            dos.write(titleBytes)
        }

        return baos.toByteArray()
    }

    private fun writeTempM4bFile(bytes: ByteArray, suffix: String = ".m4b"): File {
        val tempFile = File.createTempFile("test_audio", suffix)
        tempFile.deleteOnExit()
        FileOutputStream(tempFile).use { it.write(bytes) }
        return tempFile
    }

    // 1. extractChapters(null) -> empty ChapterResult (no crash)
    @Test
    fun extractChapters_nullFile_returnsEmptyChapterResultWithoutCrash() {
        val result = M4bChapterParser.extractChapters(null)
        assertNotNull(result)
        assertTrue(result.chaptersMap.isEmpty())
        assertTrue(result.timestamps.isEmpty())
    }

    // 2. extractChapters(fileNotM4b) -> empty ChapterResult
    @Test
    fun extractChapters_nonExistentFile_returnsEmptyChapterResult() {
        val file = File("non_existent_file_12345.m4b")
        assertFalse(file.exists())
        val result = M4bChapterParser.extractChapters(file)
        assertNotNull(result)
        assertTrue(result.chaptersMap.isEmpty())
        assertTrue(result.timestamps.isEmpty())
    }

    @Test
    fun extractChapters_fileNotM4b_returnsEmptyChapterResult() {
        val extensions = listOf(".mp3", ".txt", ".m4a", ".wav", ".flac", ".m4b.bak", "")
        for (ext in extensions) {
            val file = writeTempM4bFile("dummy audio content".toByteArray(), suffix = ext)
            try {
                val result = M4bChapterParser.extractChapters(file)
                assertNotNull("Result should not be null for $ext", result)
                assertTrue("chaptersMap should be empty for $ext", result.chaptersMap.isEmpty())
                assertTrue("timestamps should be empty for $ext", result.timestamps.isEmpty())
            } finally {
                file.delete()
            }
        }
    }

    // 3. Check ChapterResult instantiation and class accessibility
    @Test
    fun chapterResult_instantiationAndClassAccessibility() {
        // Can be instantiated directly without an outer class instance
        val result = M4bChapterParser.ChapterResult()
        assertNotNull(result)
        assertNotNull(result.chaptersMap)
        assertNotNull(result.timestamps)
        assertTrue(result.chaptersMap.isEmpty())
        assertTrue(result.timestamps.isEmpty())

        // Verify fields are mutable and accessible
        result.chaptersMap[1000] = "Chapter 1"
        result.timestamps.add(1000)
        assertEquals(1, result.chaptersMap.size)
        assertEquals("Chapter 1", result.chaptersMap[1000])
        assertEquals(listOf(1000), result.timestamps)

        // Verify via reflection: static nested class, not an inner class holding outer reference
        val clazz = M4bChapterParser.ChapterResult::class.java
        assertTrue("ChapterResult should be a static member class", Modifier.isStatic(clazz.modifiers))
        val noArgCtor = clazz.constructors.firstOrNull { it.parameterTypes.isEmpty() }
        assertNotNull("ChapterResult must have a public 0-arg constructor", noArgCtor)
    }

    // 4. Synthesize mock M4B binary in memory using File.createTempFile("test", ".m4b"):
    // moov -> udta -> chpl atom. Version 0, 1-byte count (2 chapters), startTime100ns conversion,
    // UTF-8 title decoding with bookmark emoji prefix \uD83D\uDD16 (🔖), verify timestamps and chaptersMap.
    @Test
    fun extractChapters_synthesizedMockM4b_version0_parsesCorrectly() {
        // 2 chapters:
        // Chapter 1: 0 100ns -> 0 ms
        // Chapter 2: 150_000_000 100ns -> 15,000 ms (15 sec)
        val chapters = listOf(
            0L to "Prologue",
            150_000_000L to "The Journey Begins"
        )

        val chplPayload = createChplPayload(version = 0, chapters = chapters)
        val chplAtom = createAtom("chpl", chplPayload)
        val udtaAtom = createAtom("udta", chplAtom)
        val moovAtom = createAtom("moov", udtaAtom)

        val tempFile = writeTempM4bFile(moovAtom)
        try {
            val result = M4bChapterParser.extractChapters(tempFile)
            assertNotNull(result)
            assertEquals(2, result.timestamps.size)
            assertEquals(2, result.chaptersMap.size)

            assertEquals(0, result.timestamps[0])
            assertEquals(15000, result.timestamps[1])

            // Bookmark emoji prefix check: \uD83D\uDD16 (🔖)
            val expectedEmojiPrefix = "\uD83D\uDD16 "
            assertEquals("${expectedEmojiPrefix}Prologue", result.chaptersMap[0])
            assertEquals("${expectedEmojiPrefix}The Journey Begins", result.chaptersMap[15000])
            assertEquals("🔖 Prologue", result.chaptersMap[0])
            assertEquals("🔖 The Journey Begins", result.chaptersMap[15000])
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun extractChapters_startTime100nsConversionToMilliseconds() {
        // Test various 100ns conversion boundaries: startTimeMs = (startTime100ns / 10000).toInt()
        val chapters = listOf(
            10_000L to "1 Millisecond",
            10_000_000L to "1000 Milliseconds",
            654_320_000L to "65432 Milliseconds",
            36_000_000_000L to "1 Hour (3,600,000 ms)"
        )

        val chplAtom = createAtom("chpl", createChplPayload(version = 0, chapters = chapters))
        val udtaAtom = createAtom("udta", chplAtom)
        val moovAtom = createAtom("moov", udtaAtom)

        val tempFile = writeTempM4bFile(moovAtom)
        try {
            val result = M4bChapterParser.extractChapters(tempFile)
            assertEquals(listOf(1, 1000, 65432, 3600000), result.timestamps)
            assertEquals("🔖 1 Millisecond", result.chaptersMap[1])
            assertEquals("🔖 1000 Milliseconds", result.chaptersMap[1000])
            assertEquals("🔖 65432 Milliseconds", result.chaptersMap[65432])
            assertEquals("🔖 1 Hour (3,600,000 ms)", result.chaptersMap[3600000])
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun extractChapters_utf8TitleDecodingAndEmojiPrefix() {
        // Test non-ASCII multi-byte UTF-8 titles across scripts
        val chapters = listOf(
            0L to "Rozdział 1: Źdźbło i Jabłko",
            20_000_000L to "第2章: 桜の木の下で",
            40_000_000L to "제3장: 새로운 시작 🎧",
            60_000_000L to "Chapitre 4: À l'orée du bois"
        )

        val chplAtom = createAtom("chpl", createChplPayload(version = 0, chapters = chapters))
        val udtaAtom = createAtom("udta", chplAtom)
        val moovAtom = createAtom("moov", udtaAtom)

        val tempFile = writeTempM4bFile(moovAtom)
        try {
            val result = M4bChapterParser.extractChapters(tempFile)
            assertEquals(4, result.chaptersMap.size)
            assertEquals("\uD83D\uDD16 Rozdział 1: Źdźbło i Jabłko", result.chaptersMap[0])
            assertEquals("\uD83D\uDD16 第2章: 桜の木の下で", result.chaptersMap[2000])
            assertEquals("\uD83D\uDD16 제3장: 새로운 시작 🎧", result.chaptersMap[4000])
            assertEquals("\uD83D\uDD16 Chapitre 4: À l'orée du bois", result.chaptersMap[6000])
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun extractChapters_version1ChplAtom_parses4ByteChapterCount() {
        // Version 1 uses a 4-byte Int for chapter count
        val chapters = listOf(
            0L to "Chapter One",
            50_000_000L to "Chapter Two",
            100_000_000L to "Chapter Three"
        )

        val chplAtom = createAtom("chpl", createChplPayload(version = 1, chapters = chapters))
        val udtaAtom = createAtom("udta", chplAtom)
        val moovAtom = createAtom("moov", udtaAtom)

        val tempFile = writeTempM4bFile(moovAtom)
        try {
            val result = M4bChapterParser.extractChapters(tempFile)
            assertEquals(3, result.timestamps.size)
            assertEquals(listOf(0, 5000, 10000), result.timestamps)
            assertEquals("🔖 Chapter One", result.chaptersMap[0])
            assertEquals("🔖 Chapter Two", result.chaptersMap[5000])
            assertEquals("🔖 Chapter Three", result.chaptersMap[10000])
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun extractChapters_navigatesContainerWithSiblingAtoms() {
        // Realistic MP4 layout: ftyp box -> moov box (mvhd -> trak -> udta (chpl))
        val ftypPayload = "M4B mp42isom".toByteArray(Charsets.US_ASCII)
        val ftypAtom = createAtom("ftyp", ftypPayload)

        val mvhdPayload = ByteArray(108) // dummy mvhd content
        val mvhdAtom = createAtom("mvhd", mvhdPayload)

        val chplPayload = createChplPayload(version = 0, listOf(0L to "Intro"))
        val chplAtom = createAtom("chpl", chplPayload)
        val udtaAtom = createAtom("udta", chplAtom)

        // Combine mvhd + udta inside moov payload
        val moovPayload = mvhdAtom + udtaAtom
        val moovAtom = createAtom("moov", moovPayload)

        val fullFile = ftypAtom + moovAtom
        val tempFile = writeTempM4bFile(fullFile)
        try {
            val result = M4bChapterParser.extractChapters(tempFile)
            assertEquals(1, result.timestamps.size)
            assertEquals(0, result.timestamps[0])
            assertEquals("🔖 Intro", result.chaptersMap[0])
        } finally {
            tempFile.delete()
        }
    }

    // 5. Error handling: truncated atom size < 8 stops scanning cleanly
    @Test
    fun extractChapters_atomSizeLessThan8_stopsScanningCleanly() {
        val testSizes = listOf(0, 1, 4, 7, -1, -100)

        for (invalidSize in testSizes) {
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeInt(invalidSize)
            dos.write("moov".toByteArray(Charsets.US_ASCII))
            dos.write(ByteArray(32)) // some trailing bytes

            val tempFile = writeTempM4bFile(baos.toByteArray())
            try {
                val result = M4bChapterParser.extractChapters(tempFile)
                assertNotNull("Result should not be null for size=$invalidSize", result)
                assertTrue("chaptersMap should be empty for size=$invalidSize", result.chaptersMap.isEmpty())
                assertTrue("timestamps should be empty for size=$invalidSize", result.timestamps.isEmpty())
            } finally {
                tempFile.delete()
            }
        }
    }

    @Test
    fun extractChapters_truncatedFile_handlesEofGracefully() {
        // 1. Completely empty file (0 bytes)
        val emptyFile = writeTempM4bFile(ByteArray(0))
        try {
            val result = M4bChapterParser.extractChapters(emptyFile)
            assertNotNull(result)
            assertTrue(result.chaptersMap.isEmpty())
        } finally {
            emptyFile.delete()
        }

        // 2. File truncated in the middle of atom header (3 bytes)
        val partialHeaderFile = writeTempM4bFile(byteArrayOf(0, 0, 10))
        try {
            val result = M4bChapterParser.extractChapters(partialHeaderFile)
            assertNotNull(result)
            assertTrue(result.chaptersMap.isEmpty())
        } finally {
            partialHeaderFile.delete()
        }

        // 3. File claiming moov size=100, but only 12 bytes long
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(100)
        dos.write("moov".toByteArray(Charsets.US_ASCII))
        dos.writeInt(4) // invalid child atom size
        val truncatedContainerFile = writeTempM4bFile(baos.toByteArray())
        try {
            val result = M4bChapterParser.extractChapters(truncatedContainerFile)
            assertNotNull(result)
            assertTrue(result.chaptersMap.isEmpty())
        } finally {
            truncatedContainerFile.delete()
        }
    }

    @Test
    fun extractChapters_caseInsensitiveM4bExtension() {
        val chplPayload = createChplPayload(version = 0, listOf(0L to "Case Test"))
        val chplAtom = createAtom("chpl", chplPayload)
        val udtaAtom = createAtom("udta", chplAtom)
        val moovAtom = createAtom("moov", udtaAtom)

        for (ext in listOf(".M4B", ".m4B", ".M4b")) {
            val tempFile = writeTempM4bFile(moovAtom, suffix = ext)
            try {
                val result = M4bChapterParser.extractChapters(tempFile)
                assertEquals(1, result.timestamps.size)
                assertEquals("🔖 Case Test", result.chaptersMap[0])
            } finally {
                tempFile.delete()
            }
        }
    }

    @Test
    fun extractChapters_timestampsSortedAscendingEvenIfDefinedOutOfOrder() {
        // Chapters defined out of chronological order in file
        val chapters = listOf(
            30_000_000L to "Third",
            10_000_000L to "First",
            20_000_000L to "Second"
        )

        val chplAtom = createAtom("chpl", createChplPayload(version = 0, chapters = chapters))
        val udtaAtom = createAtom("udta", chplAtom)
        val moovAtom = createAtom("moov", udtaAtom)

        val tempFile = writeTempM4bFile(moovAtom)
        try {
            val result = M4bChapterParser.extractChapters(tempFile)
            // TreeMap and result.timestamps.addAll(result.chaptersMap.keys) ensures sorting
            assertEquals(listOf(1000, 2000, 3000), result.timestamps)
            assertEquals("🔖 First", result.chaptersMap[1000])
            assertEquals("🔖 Second", result.chaptersMap[2000])
            assertEquals("🔖 Third", result.chaptersMap[3000])
        } finally {
            tempFile.delete()
        }
    }
}
