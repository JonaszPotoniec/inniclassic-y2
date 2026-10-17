package com.themoon.y1.io

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * File operations shared by the upload server and bundled theme installer.
 */
object SafeFiles {
    @Throws(IOException::class)
    fun resolve(root: File, path: String?): File {
        if (path == null || File(path).isAbsolute || path.indexOf('\u0000') >= 0) {
            throw IOException("Invalid path")
        }
        val base = root.canonicalFile
        val file = File(base, path).canonicalFile
        if (file != base && !file.path.startsWith(base.path + File.separator)) {
            throw IOException("Path outside shared folder")
        }
        return file
    }
    @Throws(IOException::class)
    fun child(root: File, name: String?): File {
        if (name.isNullOrEmpty() || name == "." || name == ".." ||
            name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
        ) {
            throw IOException("Invalid file name")
        }
        return resolve(root, name)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun replace(target: File, input: InputStream, length: Int) {
        if (length < 0) throw IOException("Invalid content length")
        val targetCanonical = target.canonicalFile
        val parent = targetCanonical.parentFile
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs() && !parent.exists()) {
                throw IOException("Could not create parent directories")
            }
        }
        val temporary = File.createTempFile(".y1-upload-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(8192)
                var remaining = length
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) throw EOFException("Incomplete upload")
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.fd.sync()
            }
            if (!temporary.renameTo(targetCanonical)) {
                if (targetCanonical.exists()) {
                    targetCanonical.delete()
                }
                if (!temporary.renameTo(targetCanonical)) {
                    throw IOException("Could not replace file")
                }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /**
     * Atomically write a byte array to a file.
     * Automatically creates parent directories if they don't exist.
     * Uses a temporary file and syncs to disk before atomically replacing target.
     * Cleans up temporary files in all failure cases.
     */
    @JvmStatic
    fun writeBytesToFile(file: File?, bytes: ByteArray?): Boolean {
        if (file == null || bytes == null) return false
        return try {
            val target = file.canonicalFile
            if (target.isDirectory) return false
            val parent = target.parentFile
            if (parent != null && !parent.exists()) {
                if (!parent.mkdirs() && !parent.exists()) {
                    return false
                }
            }
            val temporary = File.createTempFile(".y1-safe-", ".tmp", parent)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
                if (!temporary.renameTo(target)) {
                    if (target.exists()) {
                        target.delete()
                    }
                    if (!temporary.renameTo(target)) {
                        return false
                    }
                }
                true
            } finally {
                if (temporary.exists()) {
                    temporary.delete()
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun writeBytesToFile(path: String?, bytes: ByteArray?): Boolean {
        if (path == null) return false
        return writeBytesToFile(File(path), bytes)
    }

    /**
     * Atomically write a string to a file using the specified charset (default UTF-8).
     * Automatically creates parent directories if they don't exist.
     */
    @JvmStatic
    @JvmOverloads
    fun writeStringToFile(file: File?, content: String?, charset: java.nio.charset.Charset = Charsets.UTF_8): Boolean {
        if (file == null || content == null) return false
        return writeBytesToFile(file, content.toByteArray(charset))
    }

    @JvmStatic
    @JvmOverloads
    fun writeStringToFile(path: String?, content: String?, charset: java.nio.charset.Charset = Charsets.UTF_8): Boolean {
        if (path == null) return false
        return writeStringToFile(File(path), content, charset)
    }

    /**
     * Safely read byte contents of a file. Returns null if file is null, does not exist, or is not a file.
     * Catches and suppresses any I/O exceptions.
     */
    @JvmStatic
    fun readBytesFromFile(file: File?): ByteArray? {
        if (file == null || !file.exists() || !file.isFile) return null
        return try {
            file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    @JvmStatic
    fun readBytesFromFile(path: String?): ByteArray? {
        if (path == null) return null
        return readBytesFromFile(File(path))
    }

    /**
     * Safely read text contents of a file using the specified charset (default UTF-8).
     * Returns null if file is null, does not exist, or is not a file.
     * Catches and suppresses any I/O exceptions.
     */
    @JvmStatic
    @JvmOverloads
    fun readStringFromFile(file: File?, charset: java.nio.charset.Charset = Charsets.UTF_8): String? {
        if (file == null || !file.exists() || !file.isFile) return null
        return try {
            file.readText(charset)
        } catch (_: Exception) {
            null
        }
    }

    @JvmStatic
    @JvmOverloads
    fun readStringFromFile(path: String?, charset: java.nio.charset.Charset = Charsets.UTF_8): String? {
        if (path == null) return null
        return readStringFromFile(File(path), charset)
    }

    /**
     * Safely delete a file or directory. Returns true if the file existed and was successfully deleted.
     * Handles non-existent files or null inputs gracefully by returning false without throwing exceptions.
     */
    @JvmStatic
    fun deleteFile(file: File?): Boolean {
        if (file == null || !file.exists()) return false
        return try {
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun deleteFile(path: String?): Boolean {
        if (path == null) return false
        return deleteFile(File(path))
    }

    @JvmStatic
    fun delete(file: File?): Boolean = deleteFile(file)

    @JvmStatic
    fun delete(path: String?): Boolean = deleteFile(path)

    @JvmStatic
    fun safeDelete(file: File?): Boolean = deleteFile(file)

    @JvmStatic
    fun safeDelete(path: String?): Boolean = deleteFile(path)
}
