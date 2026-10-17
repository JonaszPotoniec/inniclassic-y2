package com.themoon.y1.io

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SafeFilesTest {

    private fun rejects(operation: () -> Unit) {
        try {
            operation()
        } catch (expected: IOException) {
            return
        }
        throw AssertionError("Unsafe operation accepted")
    }

    @Test
    fun protectsPathsAndPreservesInterruptedUploads() {
        val root = Files.createTempDirectory("inni-files-test").toFile()
        val target = File(root, "song.mp3")
        try {
            rejects { SafeFiles.resolve(root, "../outside") }
            rejects { SafeFiles.resolve(root, "../${root.name}-sibling/file") }
            rejects { SafeFiles.resolve(root, "/etc/passwd") }
            rejects { SafeFiles.child(root, "../song.mp3") }
            rejects { SafeFiles.child(root, "") }
            Files.createSymbolicLink(File(root, "escape").toPath(), root.parentFile.toPath())
            rejects { SafeFiles.resolve(root, "escape/outside") }
            check(SafeFiles.resolve(root, "album/../song.mp3") == target)

            SafeFiles.replace(target, ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
            rejects { SafeFiles.replace(target, ByteArrayInputStream(byteArrayOf(9)), 2) }
            check(Files.readAllBytes(target.toPath()).contentEquals(byteArrayOf(1, 2, 3))) { "Original lost" }
            rejects { SafeFiles.replace(target, ByteArrayInputStream(ByteArray(0)), -1) }
            SafeFiles.replace(target, ByteArrayInputStream(byteArrayOf(4, 5)), 2)
            check(Files.readAllBytes(target.toPath()).contentEquals(byteArrayOf(4, 5))) { "Replacement failed" }
            SafeFiles.replace(target, ByteArrayInputStream(ByteArray(0)), 0)
            check(target.length() == 0L) { "Empty save failed" }
            root.listFiles()?.forEach { file ->
                check(!file.name.startsWith(".y1-upload-")) { "Temporary file leaked" }
            }
            println("SafeFiles: all regression checks passed")
        } finally {
            root.listFiles()?.forEach { Files.delete(it.toPath()) }
            Files.delete(root.toPath())
        }
    }
}
