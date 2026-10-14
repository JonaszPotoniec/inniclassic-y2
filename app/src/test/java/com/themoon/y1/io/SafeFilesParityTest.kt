package com.themoon.y1.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files

/**
 * Parity and regression test suite for [SafeFiles].
 *
 * Verifies that the Kotlin conversion maintains full behavioral parity with the original
 * Java implementation and fulfills all atomic write, directory creation, safe read,
 * safe deletion, and temporary file cleanup requirements.
 */
class SafeFilesParityTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private fun rejects(operation: () -> Unit) {
        try {
            operation()
        } catch (expected: IOException) {
            return
        }
        throw AssertionError("Unsafe operation was unexpectedly accepted")
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Atomic write and read: string and binary round-trip preservation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun writeStringToFile_readStringFromFile_roundTripAscii() {
        val target = File(tempFolder.root, "ascii_test.txt")
        val content = "InniClassic Y2 Audio Player - High Fidelity Audio"

        val writeResult = SafeFiles.writeStringToFile(target, content)
        assertTrue("writeStringToFile should succeed", writeResult)
        assertTrue(target.exists())

        val readResult = SafeFiles.readStringFromFile(target)
        assertEquals("readStringFromFile should preserve exact content", content, readResult)
    }

    @Test
    fun writeStringToFile_readStringFromFile_multiLineAndWhitespace() {
        val target = File(tempFolder.root, "multiline.txt")
        val content = "Line 1\nLine 2\r\nLine 3\tTabbed   Spaces\n\nTrailing empty lines\n"

        assertTrue(SafeFiles.writeStringToFile(target, content))
        assertEquals(content, SafeFiles.readStringFromFile(target))
    }

    @Test
    fun writeStringToFile_readStringFromFile_unicodeAndSpecialChars() {
        val target = File(tempFolder.root, "unicode.txt")
        val content = "Piotr Czajkowski - Jezioro łabędzie (Лебединое озеро) / 日本語 トラック 🎵 🎧 100%"

        assertTrue(SafeFiles.writeStringToFile(target, content))
        assertEquals(content, SafeFiles.readStringFromFile(target))
    }

    @Test
    fun writeStringToFile_readStringFromFile_customCharset() {
        val target = File(tempFolder.root, "latin1.txt")
        val content = "Café Müller"
        val charset = Charset.forName("ISO-8859-1")

        assertTrue(SafeFiles.writeStringToFile(target, content, charset))
        assertEquals(content, SafeFiles.readStringFromFile(target, charset))
    }

    @Test
    fun writeStringToFile_readStringFromFile_emptyString() {
        val target = File(tempFolder.root, "empty.txt")
        val content = ""

        assertTrue(SafeFiles.writeStringToFile(target, content))
        assertTrue(target.exists())
        assertEquals(0L, target.length())
        assertEquals("", SafeFiles.readStringFromFile(target))
    }

    @Test
    fun writeStringToFile_readStringFromFile_largePayload() {
        val target = File(tempFolder.root, "large_text.json")
        val sb = StringBuilder(256 * 1024)
        for (i in 0 until 5000) {
            sb.append("{\"id\":").append(i).append(",\"name\":\"Song ").append(i).append("\"}\n")
        }
        val content = sb.toString()

        assertTrue(SafeFiles.writeStringToFile(target, content))
        val readBack = SafeFiles.readStringFromFile(target)
        assertEquals(content, readBack)
    }

    @Test
    fun writeStringToFile_readStringFromFile_stringPathOverload() {
        val target = File(tempFolder.root, "path_overload.txt")
        val path = target.absolutePath
        val content = "Direct path test string"

        assertTrue(SafeFiles.writeStringToFile(path, content))
        assertTrue(target.exists())
        assertEquals(content, SafeFiles.readStringFromFile(path))
    }

    @Test
    fun writeBytesToFile_readBytesFromFile_roundTripBinary() {
        val target = File(tempFolder.root, "binary.dat")
        val bytes = ByteArray(256) { it.toByte() }

        assertTrue(SafeFiles.writeBytesToFile(target, bytes))
        assertTrue(target.exists())
        assertEquals(256L, target.length())

        val readBytes = SafeFiles.readBytesFromFile(target)
        assertNotNull(readBytes)
        assertArrayEquals(bytes, readBytes)
    }

    @Test
    fun writeBytesToFile_readBytesFromFile_nullBytesAndZeroes() {
        val target = File(tempFolder.root, "null_bytes.bin")
        val bytes = byteArrayOf(0x00, 0x55, 0x00, 0xAA.toByte(), 0x00, 0xFF.toByte(), 0x00)

        assertTrue(SafeFiles.writeBytesToFile(target, bytes))
        assertArrayEquals(bytes, SafeFiles.readBytesFromFile(target))
    }

    @Test
    fun writeBytesToFile_readBytesFromFile_emptyByteArray() {
        val target = File(tempFolder.root, "empty.bin")
        val bytes = ByteArray(0)

        assertTrue(SafeFiles.writeBytesToFile(target, bytes))
        assertTrue(target.exists())
        assertEquals(0L, target.length())
        val readBytes = SafeFiles.readBytesFromFile(target)
        assertNotNull(readBytes)
        assertEquals(0, readBytes!!.size)
    }

    @Test
    fun writeBytesToFile_readBytesFromFile_stringPathOverload() {
        val target = File(tempFolder.root, "path_binary.dat")
        val path = target.absolutePath
        val bytes = byteArrayOf(42, 43, 44, 45)

        assertTrue(SafeFiles.writeBytesToFile(path, bytes))
        assertArrayEquals(bytes, SafeFiles.readBytesFromFile(path))
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Automatic parent directory creation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun writeStringToFile_createsDeeplyNestedParentDirectories() {
        val nestedTarget = File(tempFolder.root, "deep/level1/level2/level3/nested_file.txt")
        assertFalse("Parent hierarchy should not exist yet", nestedTarget.parentFile.exists())

        val content = "Deep directory write success"
        val success = SafeFiles.writeStringToFile(nestedTarget, content)

        assertTrue("writeStringToFile should succeed on deeply nested missing dirs", success)
        assertTrue(nestedTarget.exists())
        assertTrue(nestedTarget.parentFile.exists())
        assertEquals(content, SafeFiles.readStringFromFile(nestedTarget))
    }

    @Test
    fun writeBytesToFile_createsDeeplyNestedParentDirectories() {
        val nestedTarget = File(tempFolder.root, "storage/cache/sub/binary_nested.bin")
        assertFalse(nestedTarget.parentFile.exists())

        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val success = SafeFiles.writeBytesToFile(nestedTarget, bytes)

        assertTrue("writeBytesToFile should succeed on deeply nested missing dirs", success)
        assertTrue(nestedTarget.exists())
        assertTrue(nestedTarget.parentFile.exists())
        assertArrayEquals(bytes, SafeFiles.readBytesFromFile(nestedTarget))
    }

    @Test
    fun replace_createsParentDirectoriesAutomatically() {
        val nestedTarget = File(tempFolder.root, "nested_upload/album/cover.jpg")
        assertFalse(nestedTarget.parentFile.exists())

        val data = byteArrayOf(10, 20, 30, 40)
        SafeFiles.replace(nestedTarget, ByteArrayInputStream(data), data.size)

        assertTrue(nestedTarget.exists())
        assertArrayEquals(data, Files.readAllBytes(nestedTarget.toPath()))
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Overwrite behavior: completely replaces contents without lingering bytes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun writeStringToFile_overwriteLongFileWithShorterContent_noLingeringBytes() {
        val target = File(tempFolder.root, "overwrite_test.txt")
        val initialLong = "This is a very long string that takes up substantial bytes on disk."
        assertTrue(SafeFiles.writeStringToFile(target, initialLong))
        assertEquals(initialLong.length.toLong(), target.length())

        val shorter = "Short"
        assertTrue(SafeFiles.writeStringToFile(target, shorter))
        assertEquals("File length must match exact new shorter length", shorter.length.toLong(), target.length())
        assertEquals(shorter, SafeFiles.readStringFromFile(target))
    }

    @Test
    fun writeBytesToFile_overwriteLongFileWithShorterBytes_noLingeringBytes() {
        val target = File(tempFolder.root, "overwrite_bytes.bin")
        val longBytes = ByteArray(1024) { (it % 128).toByte() }
        assertTrue(SafeFiles.writeBytesToFile(target, longBytes))
        assertEquals(1024L, target.length())

        val shortBytes = byteArrayOf(99, 100, 101)
        assertTrue(SafeFiles.writeBytesToFile(target, shortBytes))
        assertEquals(3L, target.length())
        assertArrayEquals(shortBytes, SafeFiles.readBytesFromFile(target))
    }

    @Test
    fun writeStringToFile_overwriteWithEmptyContent() {
        val target = File(tempFolder.root, "overwrite_empty.txt")
        assertTrue(SafeFiles.writeStringToFile(target, "Existing non-empty payload"))
        assertTrue(target.length() > 0)

        assertTrue(SafeFiles.writeStringToFile(target, ""))
        assertEquals(0L, target.length())
        assertEquals("", SafeFiles.readStringFromFile(target))
    }

    @Test
    fun writeStringToFile_multipleSequentialOverwrites() {
        val target = File(tempFolder.root, "consecutive_overwrites.txt")
        for (i in 1..20) {
            val content = "Iteration $i -> ${"X".repeat(i * 10)}"
            assertTrue(SafeFiles.writeStringToFile(target, content))
            assertEquals(content, SafeFiles.readStringFromFile(target))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Safe read on missing files & edge cases
    // ---------------------------------------------------------------------------------------------

    @Test
    fun readStringFromFile_nonExistentFile_returnsNullWithoutException() {
        val nonExistent = File(tempFolder.root, "does_not_exist.txt")
        assertFalse(nonExistent.exists())

        val result = SafeFiles.readStringFromFile(nonExistent)
        assertNull("Missing file read should return null safely", result)
    }

    @Test
    fun readBytesFromFile_nonExistentFile_returnsNullWithoutException() {
        val nonExistent = File(tempFolder.root, "missing_bytes.bin")
        assertFalse(nonExistent.exists())

        val result = SafeFiles.readBytesFromFile(nonExistent)
        assertNull("Missing file byte read should return null safely", result)
    }

    @Test
    fun readStringFromFile_directoryPath_returnsNullWithoutException() {
        val directory = tempFolder.newFolder("some_directory")
        assertTrue(directory.isDirectory)

        val result = SafeFiles.readStringFromFile(directory)
        assertNull("Reading directory as text file should return null safely", result)
    }

    @Test
    fun readBytesFromFile_directoryPath_returnsNullWithoutException() {
        val directory = tempFolder.newFolder("another_directory")
        assertTrue(directory.isDirectory)

        val result = SafeFiles.readBytesFromFile(directory)
        assertNull("Reading directory as binary file should return null safely", result)
    }

    @Test
    fun readMethods_nullInputs_returnNullSafely() {
        assertNull(SafeFiles.readStringFromFile(null as File?))
        assertNull(SafeFiles.readStringFromFile(null as String?))
        assertNull(SafeFiles.readBytesFromFile(null as File?))
        assertNull(SafeFiles.readBytesFromFile(null as String?))
    }

    @Test
    fun writeMethods_nullInputs_returnFalseSafely() {
        assertFalse(SafeFiles.writeStringToFile(null as File?, "content"))
        assertFalse(SafeFiles.writeStringToFile(null as String?, "content"))
        assertFalse(SafeFiles.writeStringToFile(File(tempFolder.root, "test.txt"), null as String?))
        assertFalse(SafeFiles.writeBytesToFile(null as File?, byteArrayOf(1)))
        assertFalse(SafeFiles.writeBytesToFile(null as String?, byteArrayOf(1)))
        assertFalse(SafeFiles.writeBytesToFile(File(tempFolder.root, "test.bin"), null as ByteArray?))
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Safe deletion
    // ---------------------------------------------------------------------------------------------

    @Test
    fun deleteFile_existingFile_returnsTrueAndDeletesFile() {
        val target = File(tempFolder.root, "delete_me.txt")
        assertTrue(SafeFiles.writeStringToFile(target, "Goodbye World"))
        assertTrue(target.exists())

        val deleted = SafeFiles.deleteFile(target)
        assertTrue("deleteFile should return true for existing file", deleted)
        assertFalse("File must no longer exist after deletion", target.exists())
    }

    @Test
    fun deleteFile_nonExistentFile_returnsFalseGracefully() {
        val nonExistent = File(tempFolder.root, "never_created.txt")
        assertFalse(nonExistent.exists())

        val deleted = SafeFiles.deleteFile(nonExistent)
        assertFalse("deleteFile should return false for non-existent file", deleted)
    }

    @Test
    fun deleteFile_directoryWithContents_deletesRecursively() {
        val folder = tempFolder.newFolder("folder_to_delete")
        val childFile = File(folder, "child.txt")
        assertTrue(SafeFiles.writeStringToFile(childFile, "Child content"))
        assertTrue(childFile.exists())

        val deleted = SafeFiles.deleteFile(folder)
        assertTrue(deleted)
        assertFalse(folder.exists())
        assertFalse(childFile.exists())
    }

    @Test
    fun delete_aliasesAndStringOverloads_behaveConsistently() {
        val file1 = File(tempFolder.root, "alias1.txt")
        val file2 = File(tempFolder.root, "alias2.txt")
        val file3 = File(tempFolder.root, "alias3.txt")
        assertTrue(SafeFiles.writeStringToFile(file1, "1"))
        assertTrue(SafeFiles.writeStringToFile(file2, "2"))
        assertTrue(SafeFiles.writeStringToFile(file3, "3"))

        assertTrue(SafeFiles.delete(file1))
        assertFalse(file1.exists())

        assertTrue(SafeFiles.safeDelete(file2))
        assertFalse(file2.exists())

        assertTrue(SafeFiles.deleteFile(file3.absolutePath))
        assertFalse(file3.exists())

        // Non-existent checks with aliases
        assertFalse(SafeFiles.delete(File(tempFolder.root, "missing1.txt")))
        assertFalse(SafeFiles.safeDelete(File(tempFolder.root, "missing2.txt")))
        assertFalse(SafeFiles.deleteFile(File(tempFolder.root, "missing3.txt").absolutePath))
        assertFalse(SafeFiles.delete(null as File?))
        assertFalse(SafeFiles.delete(null as String?))
        assertFalse(SafeFiles.safeDelete(null as File?))
        assertFalse(SafeFiles.safeDelete(null as String?))
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Temporary file cleanup on atomic writes and failures
    // ---------------------------------------------------------------------------------------------

    @Test
    fun writeStringToFile_cleansUpAllTempFilesOnSuccess() {
        val parentDir = tempFolder.newFolder("temp_cleanup_str")
        val target = File(parentDir, "atomic_output.txt")

        assertTrue(SafeFiles.writeStringToFile(target, "Valid data"))

        val filesInParent = parentDir.listFiles() ?: emptyArray()
        assertEquals("Parent dir should contain only the final target file", 1, filesInParent.size)
        assertEquals(target.name, filesInParent[0].name)

        val tempFiles = filesInParent.filter { it.name.endsWith(".tmp") }
        assertTrue("No dangling .tmp files should exist", tempFiles.isEmpty())
    }

    @Test
    fun writeBytesToFile_cleansUpAllTempFilesOnSuccess() {
        val parentDir = tempFolder.newFolder("temp_cleanup_bin")
        val target = File(parentDir, "atomic_bytes.bin")

        assertTrue(SafeFiles.writeBytesToFile(target, byteArrayOf(1, 2, 3)))

        val filesInParent = parentDir.listFiles() ?: emptyArray()
        assertEquals(1, filesInParent.size)
        assertEquals(target.name, filesInParent[0].name)

        val tempFiles = filesInParent.filter { it.name.endsWith(".tmp") }
        assertTrue("No dangling .tmp files should exist", tempFiles.isEmpty())
    }

    @Test
    fun writeMethods_repeatedWritesDoNotAccumulateTempFiles() {
        val parentDir = tempFolder.newFolder("temp_cleanup_loop")
        val target = File(parentDir, "loop_target.txt")

        for (i in 0 until 50) {
            assertTrue(SafeFiles.writeStringToFile(target, "Payload iteration $i"))
        }

        val allFiles = parentDir.listFiles() ?: emptyArray()
        assertEquals("Only one target file should remain", 1, allFiles.size)
        assertFalse("File name must not be a temp file", allFiles[0].name.endsWith(".tmp"))
    }

    @Test
    fun replace_preservesOriginalAndCleansUpTempFileOnInterruptedUpload() {
        val parentDir = tempFolder.newFolder("replace_cleanup")
        val target = File(parentDir, "stream_target.dat")
        val originalData = byteArrayOf(1, 2, 3)

        SafeFiles.replace(target, ByteArrayInputStream(originalData), originalData.size)
        assertArrayEquals(originalData, Files.readAllBytes(target.toPath()))

        // Simulate interrupted stream where available bytes is less than expected length
        rejects {
            SafeFiles.replace(target, ByteArrayInputStream(byteArrayOf(99)), 10)
        }

        // Original file must be preserved
        assertArrayEquals(originalData, Files.readAllBytes(target.toPath()))

        // Check for temp files leaked in parent directory
        val tempFiles = parentDir.listFiles { _, name -> name.endsWith(".tmp") } ?: emptyArray()
        assertTrue("Interrupted replace must not leak .tmp files", tempFiles.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // 7. Security and path resolution parity (resolve and child)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun resolve_and_child_pathConfinementParity() {
        val root = tempFolder.newFolder("safe_root")
        val target = File(root, "valid.mp3")

        // Reject traversal outside root
        rejects { SafeFiles.resolve(root, "../outside") }
        rejects { SafeFiles.resolve(root, "../${root.name}-sibling/file") }
        rejects { SafeFiles.resolve(root, "/etc/passwd") }
        rejects { SafeFiles.resolve(root, null) }
        rejects { SafeFiles.resolve(root, "bad\u0000path") }

        // Reject child invalid names
        rejects { SafeFiles.child(root, "../valid.mp3") }
        rejects { SafeFiles.child(root, "") }
        rejects { SafeFiles.child(root, ".") }
        rejects { SafeFiles.child(root, "..") }
        rejects { SafeFiles.child(root, "sub/file") }
        rejects { SafeFiles.child(root, "sub\\file") }
        rejects { SafeFiles.child(root, null) }

        // Symbolic link outside root should be rejected
        val escapeSymlink = File(root, "escape_link")
        Files.createSymbolicLink(escapeSymlink.toPath(), root.parentFile.toPath())
        rejects { SafeFiles.resolve(root, "escape_link/outside") }

        // Valid relative navigation inside root should succeed
        val resolved = SafeFiles.resolve(root, "subdir/../valid.mp3")
        assertEquals(target.canonicalFile, resolved)

        val childResolved = SafeFiles.child(root, "valid.mp3")
        assertEquals(target.canonicalFile, childResolved)
    }
}
