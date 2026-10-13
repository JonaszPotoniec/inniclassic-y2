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

    @JvmStatic
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

    @JvmStatic
    @Throws(IOException::class)
    fun child(root: File, name: String?): File {
        if (name.isNullOrEmpty() || name == "." || name == ".." ||
            name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
        ) {
            throw IOException("Invalid file name")
        }
        return resolve(root, name)
    }

    /**
     * Keep the old file intact until the complete request body is durable.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun replace(target: File, input: InputStream, length: Int) {
        if (length < 0) throw IOException("Invalid content length")
        val temporary = File.createTempFile(".y1-upload-", ".tmp", target.parentFile)
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
            if (!temporary.renameTo(target)) throw IOException("Could not replace file")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
