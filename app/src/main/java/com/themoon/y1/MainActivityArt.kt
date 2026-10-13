package com.themoon.y1

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.themoon.y1.managers.MediaLibraryManager
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

internal fun MainActivity.updateMainMenuBackgroundInternal() {
    val bgView = ivMainBg ?: return
    try {
        val savedBgPath = prefs.getString("bg_path", null)
        val currentTheme = ThemeManager.getCurrentTheme()

        if ("THEME_DEFAULT" == savedBgPath) {
            var themeBg: Bitmap? = null
            if (!currentTheme.bgImage.isNullOrEmpty()) {
                val bgFile = File(currentTheme.folderPath, currentTheme.bgImage)
                if (bgFile.exists()) {
                    themeBg = BitmapFactory.decodeFile(bgFile.absolutePath)
                }
            }
            if (themeBg == null) themeBg = ThemeManager.getCustomIcon("background.png", this, 0)
            if (themeBg == null) themeBg = ThemeManager.getCustomIcon("bg.png", this, 0)
            if (themeBg != null) {
                bgView.setImageBitmap(themeBg)
                return
            }
        } else if (!savedBgPath.isNullOrEmpty()) {
            val bgFile = File(savedBgPath)
            if (bgFile.exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val customBg = BitmapFactory.decodeFile(savedBgPath, opts)
                if (customBg != null) {
                    bgView.setImageBitmap(customBg)
                    return
                }
            }
        }

        val sourceBitmap = if (lastAlbumArtBytes != null && lastAlbumArtBytes!!.isNotEmpty()) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeByteArray(lastAlbumArtBytes, 0, lastAlbumArtBytes!!.size, opts)
        } else {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeResource(resources, R.drawable.default_back, opts)
        }

        if (sourceBitmap != null) {
            val blurred = applyGaussianBlur(sourceBitmap)
            bgView.setImageBitmap(blurred)
            if (sourceBitmap != blurred) sourceBitmap.recycle()
        } else {
            bgView.setImageResource(R.drawable.default_back)
        }
    } catch (_: Throwable) {
        bgView.setImageResource(R.drawable.default_back)
    }
}

internal fun MainActivity.applyCachedCoverArtInternal(imagePath: String) {
    try {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = BitmapFactory.decodeFile(imagePath, opts) ?: return
        ivAlbumArt.setImageBitmap(bmp)
        currentAlbumColor = try {
            bmp.getPixel(bmp.width / 2, (bmp.height * 0.8).toInt()) or 0xFF000000.toInt()
        } catch (_: Exception) {
            ThemeManager.getListButtonFocusedBg() or 0xFF000000.toInt()
        }

        val optsBg = BitmapFactory.Options().apply { inSampleSize = 4 }
        val sourceBg = BitmapFactory.decodeFile(imagePath, optsBg)
        val blurred = applyGaussianBlur(sourceBg)
        ivPlayerBgBlur.setImageBitmap(blurred)
        if (sourceBg != blurred) sourceBg?.recycle()

        val file = File(imagePath)
        val bytes = ByteArray(file.length().toInt())
        val buf = BufferedInputStream(FileInputStream(file))
        buf.read(bytes, 0, bytes.size)
        buf.close()

        lastAlbumArtBytes = bytes
        updateMainMenuBackgroundInternal()
        refreshNowPlayingPreview()
        sendBluetoothMetaToCar()
    } catch (_: Exception) {}
}

internal fun MainActivity.fetchRandomIdleCoverAsyncInternal() {
    Thread({
        if (MainActivity.customLibrary.isNotEmpty()) {
            val randomSong = MainActivity.customLibrary.randomOrNull()
            if (randomSong != null) {
                val cover = MediaLibraryManager.getInstance().findFolderCover(randomSong.file.parentFile)
                if (cover != null && cover.exists()) {
                    val bmp = BitmapFactory.decodeFile(cover.absolutePath)
                    if (bmp != null) {
                        runOnUiThread {
                            homeWidgetsController?.idleRandomCoverBitmap = bmp
                            homeWidgetsController?.refreshWidgets()
                        }
                    }
                }
            }
        }
    }, "y1-random-cover").start()
}
