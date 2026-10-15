package com.themoon.y1

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class StoragePathsParityTest {

    @Before
    fun setUp() {
        StoragePaths.invalidate()
    }

    @After
    fun tearDown() {
        StoragePaths.invalidate()
    }

    // -------------------------------------------------------------------------
    // 1. isStorageVolumeName(name)
    // -------------------------------------------------------------------------

    @Test
    fun isStorageVolumeName_knownVolumesReturnTrue() {
        assertTrue(StoragePaths.isStorageVolumeName("sdcard0"))
        assertTrue(StoragePaths.isStorageVolumeName("sdcard1"))
        assertTrue(StoragePaths.isStorageVolumeName("emulated"))
        assertTrue(StoragePaths.isStorageVolumeName("legacy"))
        assertTrue(StoragePaths.isStorageVolumeName("media_rw"))
    }

    @Test
    fun isStorageVolumeName_caseInsensitive() {
        assertTrue(StoragePaths.isStorageVolumeName("SDCARD0"))
        assertTrue(StoragePaths.isStorageVolumeName("SdCard0"))
        assertTrue(StoragePaths.isStorageVolumeName("SDCARD1"))
        assertTrue(StoragePaths.isStorageVolumeName("SdCard1"))
        assertTrue(StoragePaths.isStorageVolumeName("EMULATED"))
        assertTrue(StoragePaths.isStorageVolumeName("Emulated"))
        assertTrue(StoragePaths.isStorageVolumeName("LEGACY"))
        assertTrue(StoragePaths.isStorageVolumeName("Legacy"))
        assertTrue(StoragePaths.isStorageVolumeName("MEDIA_RW"))
        assertTrue(StoragePaths.isStorageVolumeName("Media_Rw"))
    }

    @Test
    fun isStorageVolumeName_unknownOrInvalidVolumesReturnFalse() {
        assertFalse(StoragePaths.isStorageVolumeName(null))
        assertFalse(StoragePaths.isStorageVolumeName(""))
        assertFalse(StoragePaths.isStorageVolumeName("   "))
        assertFalse(StoragePaths.isStorageVolumeName("storage"))
        assertFalse(StoragePaths.isStorageVolumeName("sdcard"))
        assertFalse(StoragePaths.isStorageVolumeName("sdcard2"))
        assertFalse(StoragePaths.isStorageVolumeName("internal"))
        assertFalse(StoragePaths.isStorageVolumeName("external"))
        assertFalse(StoragePaths.isStorageVolumeName("music"))
        assertFalse(StoragePaths.isStorageVolumeName("system"))
        assertFalse(StoragePaths.isStorageVolumeName("data"))
        assertFalse(StoragePaths.isStorageVolumeName("emulated0"))
    }

    // -------------------------------------------------------------------------
    // 2. toDisplayPath(path)
    // -------------------------------------------------------------------------

    @Test
    fun toDisplayPath_nullAndEmpty() {
        assertEquals("", StoragePaths.toDisplayPath(null))
        assertEquals("", StoragePaths.toDisplayPath(""))
    }

    @Test
    fun toDisplayPath_rootVolumePathsReturnSlash() {
        assertEquals("/", StoragePaths.toDisplayPath(StoragePaths.PRIMARY_PATH))
        assertEquals("/", StoragePaths.toDisplayPath(StoragePaths.SECONDARY_PATH))
        assertEquals("/", StoragePaths.toDisplayPath("/storage/emulated/legacy"))
        assertEquals("/", StoragePaths.toDisplayPath("/sdcard"))
        assertEquals("/", StoragePaths.toDisplayPath(StoragePaths.getPrimaryRoot().absolutePath))
    }

    @Test
    fun toDisplayPath_primaryStoragePathsStripped() {
        assertEquals("/Music", StoragePaths.toDisplayPath("/storage/sdcard0/Music"))
        assertEquals("/Music/Artist/Album/song.mp3", StoragePaths.toDisplayPath("/storage/sdcard0/Music/Artist/Album/song.mp3"))
        assertEquals("/Audiobooks/book.m4b", StoragePaths.toDisplayPath("/storage/sdcard0/Audiobooks/book.m4b"))
        assertEquals("/Podcasts/channel/ep1.mp3", StoragePaths.toDisplayPath("/storage/sdcard0/Podcasts/channel/ep1.mp3"))
        assertEquals("/Photos/vacation.jpg", StoragePaths.toDisplayPath("/storage/sdcard0/Photos/vacation.jpg"))
    }

    @Test
    fun toDisplayPath_secondaryStoragePathsStripped() {
        assertEquals("/Music", StoragePaths.toDisplayPath("/storage/sdcard1/Music"))
        assertEquals("/Music/FLAC/track01.flac", StoragePaths.toDisplayPath("/storage/sdcard1/Music/FLAC/track01.flac"))
        assertEquals("/Audiobooks/Author/Novel.m4b", StoragePaths.toDisplayPath("/storage/sdcard1/Audiobooks/Author/Novel.m4b"))
        assertEquals("/Videos/movie.mp4", StoragePaths.toDisplayPath("/storage/sdcard1/Videos/movie.mp4"))
    }

    @Test
    fun toDisplayPath_legacyAndSdcardPathsStripped() {
        assertEquals("/Music/tune.ogg", StoragePaths.toDisplayPath("/storage/emulated/legacy/Music/tune.ogg"))
        assertEquals("/Podcasts/feed.xml", StoragePaths.toDisplayPath("/storage/emulated/legacy/Podcasts/feed.xml"))
        assertEquals("/Music/tune.ogg", StoragePaths.toDisplayPath("/sdcard/Music/tune.ogg"))
        assertEquals("/Audiobooks/audio.mp3", StoragePaths.toDisplayPath("/sdcard/Audiobooks/audio.mp3"))
    }

    @Test
    fun toDisplayPath_nonStoragePathsPreserved() {
        val appData = "/data/data/com.themoon.y1/databases/library.db"
        assertEquals(appData, StoragePaths.toDisplayPath(appData))
        val etcPath = "/etc/hosts"
        assertEquals(etcPath, StoragePaths.toDisplayPath(etcPath))
        val relative = "relative/dir/file.mp3"
        assertEquals(relative, StoragePaths.toDisplayPath(relative))
    }

    // -------------------------------------------------------------------------
    // 3. isUnderAudiobooks(path / file)
    // -------------------------------------------------------------------------

    @Test
    fun isUnderAudiobooks_stringPath_nullAndEmptyReturnFalse() {
        assertFalse(StoragePaths.isUnderAudiobooks(null as String?))
        assertFalse(StoragePaths.isUnderAudiobooks(""))
    }

    @Test
    fun isUnderAudiobooks_stringPath_primaryAudiobooks() {
        assertTrue(StoragePaths.isUnderAudiobooks("/storage/sdcard0/Audiobooks"))
        assertTrue(StoragePaths.isUnderAudiobooks("/storage/sdcard0/Audiobooks/"))
        assertTrue(StoragePaths.isUnderAudiobooks("/storage/sdcard0/Audiobooks/Author/Book.m4b"))
        assertTrue(StoragePaths.isUnderAudiobooks("/storage/sdcard0/Audiobooks/Chapter1.mp3"))
        assertTrue(StoragePaths.isUnderAudiobooks(StoragePaths.getAudiobooksDir().absolutePath))
        assertTrue(StoragePaths.isUnderAudiobooks("${StoragePaths.getAudiobooksDir().absolutePath}/Book.m4b"))
    }

    @Test
    fun isUnderAudiobooks_stringPath_secondaryAudiobooks() {
        assertTrue(StoragePaths.isUnderAudiobooks("/storage/sdcard1/Audiobooks"))
        assertTrue(StoragePaths.isUnderAudiobooks("/storage/sdcard1/Audiobooks/"))
        assertTrue(StoragePaths.isUnderAudiobooks("/storage/sdcard1/Audiobooks/Author/Novel.m4b"))
    }

    @Test
    fun isUnderAudiobooks_stringPath_outsideAudiobooksReturnsFalse() {
        assertFalse(StoragePaths.isUnderAudiobooks("/storage/sdcard0/Music"))
        assertFalse(StoragePaths.isUnderAudiobooks("/storage/sdcard0/Music/song.mp3"))
        assertFalse(StoragePaths.isUnderAudiobooks("/storage/sdcard1/Music/song.mp3"))
        assertFalse(StoragePaths.isUnderAudiobooks("/storage/sdcard0/Podcasts/ep.mp3"))
        assertFalse(StoragePaths.isUnderAudiobooks("/storage/sdcard0/Videos/clip.mp4"))
        assertFalse(StoragePaths.isUnderAudiobooks("/data/local/tmp/Audiobooks"))
        assertFalse(StoragePaths.isUnderAudiobooks("/storage/sdcard0/Audiobook")) // Not Audiobooks
    }

    @Test
    fun isUnderAudiobooks_file_validAndInvalid() {
        assertFalse(StoragePaths.isUnderAudiobooks(null as File?))
        assertTrue(StoragePaths.isUnderAudiobooks(File("/storage/sdcard0/Audiobooks")))
        assertTrue(StoragePaths.isUnderAudiobooks(File("/storage/sdcard0/Audiobooks/Author/Book.m4b")))
        assertTrue(StoragePaths.isUnderAudiobooks(File("/storage/sdcard1/Audiobooks/Novel.m4b")))
        assertFalse(StoragePaths.isUnderAudiobooks(File("/storage/sdcard0/Music/song.mp3")))
        assertFalse(StoragePaths.isUnderAudiobooks(File("/storage/sdcard1/Music/song.mp3")))
        assertFalse(StoragePaths.isUnderAudiobooks(File("/data/local/tmp/audio.m4b")))
    }

    // -------------------------------------------------------------------------
    // 4. isUnderMusic(path / file)
    // -------------------------------------------------------------------------

    @Test
    fun isUnderMusic_stringPath_nullAndEmptyReturnFalse() {
        assertFalse(StoragePaths.isUnderMusic(null as String?))
        assertFalse(StoragePaths.isUnderMusic(""))
    }

    @Test
    fun isUnderMusic_stringPath_primaryMusic() {
        assertTrue(StoragePaths.isUnderMusic("/storage/sdcard0/Music"))
        assertTrue(StoragePaths.isUnderMusic("/storage/sdcard0/Music/"))
        assertTrue(StoragePaths.isUnderMusic("/storage/sdcard0/Music/Artist/Album/track.mp3"))
        assertTrue(StoragePaths.isUnderMusic("/storage/sdcard0/Music/single.flac"))
        assertTrue(StoragePaths.isUnderMusic(StoragePaths.getMusicDir().absolutePath))
        assertTrue(StoragePaths.isUnderMusic("${StoragePaths.getMusicDir().absolutePath}/track.mp3"))
    }

    @Test
    fun isUnderMusic_stringPath_secondaryMusic() {
        assertTrue(StoragePaths.isUnderMusic("/storage/sdcard1/Music"))
        assertTrue(StoragePaths.isUnderMusic("/storage/sdcard1/Music/"))
        assertTrue(StoragePaths.isUnderMusic("/storage/sdcard1/Music/Rock/tune.mp3"))
    }

    @Test
    fun isUnderMusic_stringPath_outsideMusicReturnsFalse() {
        assertFalse(StoragePaths.isUnderMusic("/storage/sdcard0/Audiobooks"))
        assertFalse(StoragePaths.isUnderMusic("/storage/sdcard0/Audiobooks/book.m4b"))
        assertFalse(StoragePaths.isUnderMusic("/storage/sdcard1/Audiobooks/book.m4b"))
        assertFalse(StoragePaths.isUnderMusic("/storage/sdcard0/Podcasts/show.mp3"))
        assertFalse(StoragePaths.isUnderMusic("/storage/sdcard0/Videos/movie.mp4"))
        assertFalse(StoragePaths.isUnderMusic("/data/data/com.themoon.y1/Music"))
        assertFalse(StoragePaths.isUnderMusic("/storage/sdcard0/Mus/song.mp3"))
    }

    @Test
    fun isUnderMusic_file_validAndInvalid() {
        assertFalse(StoragePaths.isUnderMusic(null as File?))
        assertTrue(StoragePaths.isUnderMusic(File("/storage/sdcard0/Music")))
        assertTrue(StoragePaths.isUnderMusic(File("/storage/sdcard0/Music/Artist/Album/track.mp3")))
        assertTrue(StoragePaths.isUnderMusic(File("/storage/sdcard1/Music/track.mp3")))
        assertFalse(StoragePaths.isUnderMusic(File("/storage/sdcard0/Audiobooks/book.m4b")))
        assertFalse(StoragePaths.isUnderMusic(File("/storage/sdcard1/Audiobooks/book.m4b")))
        assertFalse(StoragePaths.isUnderMusic(File("/tmp/song.mp3")))
    }

    // -------------------------------------------------------------------------
    // 5. isAudiobooksRoot, isVideosRoot, isAnyStorageRoot (String & File)
    // -------------------------------------------------------------------------

    @Test
    fun isAnyStorageRoot_stringAndFile() {
        assertFalse(StoragePaths.isAnyStorageRoot(null as String?))
        assertFalse(StoragePaths.isAnyStorageRoot(null as File?))
        assertFalse(StoragePaths.isAnyStorageRoot(""))

        assertTrue(StoragePaths.isAnyStorageRoot(StoragePaths.PRIMARY_PATH))
        assertTrue(StoragePaths.isAnyStorageRoot(File(StoragePaths.PRIMARY_PATH)))
        assertTrue(StoragePaths.isAnyStorageRoot(StoragePaths.SECONDARY_PATH))
        assertTrue(StoragePaths.isAnyStorageRoot(File(StoragePaths.SECONDARY_PATH)))
        assertTrue(StoragePaths.isAnyStorageRoot(StoragePaths.getPrimaryRoot().absolutePath))
        assertTrue(StoragePaths.isAnyStorageRoot(StoragePaths.getPrimaryRoot()))

        // Subdirectories are not storage roots
        assertFalse(StoragePaths.isAnyStorageRoot("/storage/sdcard0/Music"))
        assertFalse(StoragePaths.isAnyStorageRoot(File("/storage/sdcard0/Music")))
        assertFalse(StoragePaths.isAnyStorageRoot("/storage/sdcard1/Videos"))
        assertFalse(StoragePaths.isAnyStorageRoot(File("/storage/sdcard1/Videos")))
        assertFalse(StoragePaths.isAnyStorageRoot("/data/local/tmp"))
        assertFalse(StoragePaths.isAnyStorageRoot(File("/data/local/tmp")))
    }

    @Test
    fun isVideosRoot_stringAndFile() {
        assertFalse(StoragePaths.isVideosRoot(null as String?))
        assertFalse(StoragePaths.isVideosRoot(null as File?))
        assertFalse(StoragePaths.isVideosRoot(""))

        assertTrue(StoragePaths.isVideosRoot("${StoragePaths.PRIMARY_PATH}/Videos"))
        assertTrue(StoragePaths.isVideosRoot(File("${StoragePaths.PRIMARY_PATH}/Videos")))
        assertTrue(StoragePaths.isVideosRoot("${StoragePaths.SECONDARY_PATH}/Videos"))
        assertTrue(StoragePaths.isVideosRoot(File("${StoragePaths.SECONDARY_PATH}/Videos")))
        assertTrue(StoragePaths.isVideosRoot(File(StoragePaths.getPrimaryRoot(), "Videos").absolutePath))
        assertTrue(StoragePaths.isVideosRoot(File(StoragePaths.getPrimaryRoot(), "Videos")))

        // Subdirectories or roots themselves are not VideosRoot
        assertFalse(StoragePaths.isVideosRoot("/storage/sdcard0"))
        assertFalse(StoragePaths.isVideosRoot(File("/storage/sdcard0")))
        assertFalse(StoragePaths.isVideosRoot("/storage/sdcard0/Videos/Action"))
        assertFalse(StoragePaths.isVideosRoot(File("/storage/sdcard0/Videos/Action")))
        assertFalse(StoragePaths.isVideosRoot("/storage/sdcard0/Music"))
        assertFalse(StoragePaths.isVideosRoot(File("/storage/sdcard0/Music")))
    }

    @Test
    fun isAudiobooksRoot_stringAndFile() {
        assertFalse(StoragePaths.isAudiobooksRoot(null as String?))
        assertFalse(StoragePaths.isAudiobooksRoot(null as File?))
        assertFalse(StoragePaths.isAudiobooksRoot(""))

        assertTrue(StoragePaths.isAudiobooksRoot("${StoragePaths.PRIMARY_PATH}/Audiobooks"))
        assertTrue(StoragePaths.isAudiobooksRoot(File("${StoragePaths.PRIMARY_PATH}/Audiobooks")))
        assertTrue(StoragePaths.isAudiobooksRoot("${StoragePaths.SECONDARY_PATH}/Audiobooks"))
        assertTrue(StoragePaths.isAudiobooksRoot(File("${StoragePaths.SECONDARY_PATH}/Audiobooks")))
        assertTrue(StoragePaths.isAudiobooksRoot(StoragePaths.getAudiobooksDir().absolutePath))
        assertTrue(StoragePaths.isAudiobooksRoot(StoragePaths.getAudiobooksDir()))

        // Subdirectories or other roots are not AudiobooksRoot
        assertFalse(StoragePaths.isAudiobooksRoot("/storage/sdcard0"))
        assertFalse(StoragePaths.isAudiobooksRoot(File("/storage/sdcard0")))
        assertFalse(StoragePaths.isAudiobooksRoot("/storage/sdcard0/Audiobooks/SciFi"))
        assertFalse(StoragePaths.isAudiobooksRoot(File("/storage/sdcard0/Audiobooks/SciFi")))
        assertFalse(StoragePaths.isAudiobooksRoot("/storage/sdcard0/Music"))
        assertFalse(StoragePaths.isAudiobooksRoot(File("/storage/sdcard0/Music")))
    }

    // -------------------------------------------------------------------------
    // 6. containsSameRoot(file1, file2) & containsSameRoot(roots, candidate)
    // -------------------------------------------------------------------------

    @Test
    fun containsSameRoot_filesOnSameStorageVolume_returnsTrue() {
        val file1 = File("/storage/sdcard0/Music/song1.mp3")
        val file2 = File("/storage/sdcard0/Music/song2.mp3")
        assertTrue(StoragePaths.containsSameRoot(file1, file2))

        val musicFile = File("/storage/sdcard0/Music/song.mp3")
        val audioBookFile = File("/storage/sdcard0/Audiobooks/book.m4b")
        assertTrue(StoragePaths.containsSameRoot(musicFile, audioBookFile))

        val rootFile = File("/storage/sdcard0")
        val nestedFile = File("/storage/sdcard0/Podcasts/ep1.mp3")
        assertTrue(StoragePaths.containsSameRoot(rootFile, nestedFile))

        val secFile1 = File("/storage/sdcard1/Music/song.flac")
        val secFile2 = File("/storage/sdcard1/Videos/clip.mp4")
        assertTrue(StoragePaths.containsSameRoot(secFile1, secFile2))
    }

    @Test
    fun containsSameRoot_filesOnDifferentStorageVolumes_returnsFalse() {
        val primaryFile = File("/storage/sdcard0/Music/song.mp3")
        val secondaryFile = File("/storage/sdcard1/Music/song.mp3")
        assertFalse(StoragePaths.containsSameRoot(primaryFile, secondaryFile))

        val primaryBook = File("/storage/sdcard0/Audiobooks/novel.m4b")
        val secondaryBook = File("/storage/sdcard1/Audiobooks/novel.m4b")
        assertFalse(StoragePaths.containsSameRoot(primaryBook, secondaryBook))
    }

    @Test
    fun containsSameRoot_nonStorageFiles_returnsFalse() {
        val tmp1 = File("/tmp/file1.mp3")
        val tmp2 = File("/tmp/file2.mp3")
        assertFalse(StoragePaths.containsSameRoot(tmp1, tmp2))

        val primaryFile = File("/storage/sdcard0/Music/song.mp3")
        assertFalse(StoragePaths.containsSameRoot(primaryFile, tmp1))
        assertFalse(StoragePaths.containsSameRoot(tmp1, primaryFile))
    }

    @Test
    fun containsSameRoot_nullHandling() {
        assertFalse(StoragePaths.containsSameRoot(null as File?, null as File?))
        assertFalse(StoragePaths.containsSameRoot(File("/storage/sdcard0/Music"), null as File?))
        assertFalse(StoragePaths.containsSameRoot(null as File?, File("/storage/sdcard0/Music")))
    }

    @Test
    fun containsSameRoot_collectionAndCandidate() {
        val roots = listOf(File("/storage/sdcard0"), File("/storage/sdcard1"))
        assertTrue(StoragePaths.containsSameRoot(roots, File("/storage/sdcard0")))
        assertTrue(StoragePaths.containsSameRoot(roots, File("/storage/sdcard1")))
        assertFalse(StoragePaths.containsSameRoot(roots, File("/storage/sdcard2")))
        assertFalse(StoragePaths.containsSameRoot(emptyList(), File("/storage/sdcard0")))
    }

    @Test
    fun containsSameRoot_collectionCaseInsensitive() {
        val roots = listOf(File("/storage/sdcard1/Audiobooks"))
        assertTrue(StoragePaths.containsSameRoot(roots, File("/storage/sdcard1/audiobooks")))
        assertTrue(StoragePaths.containsSameRoot(roots, File("/storage/sdcard1/AUDIOBOOKS")))
        assertFalse(StoragePaths.containsSameRoot(roots, File("/storage/sdcard1/Music")))
    }

    // -------------------------------------------------------------------------
    // 7. primaryFile(relativeName)
    // -------------------------------------------------------------------------

    @Test
    fun primaryFile_resolvesRelativeToPrimaryRoot() {
        val expectedRoot = StoragePaths.getPrimaryRoot()
        val simpleFile = StoragePaths.primaryFile("playlist.m3u")
        assertEquals(File(expectedRoot, "playlist.m3u"), simpleFile)
        assertEquals(expectedRoot, simpleFile.parentFile)

        val nestedFile = StoragePaths.primaryFile("sub/dir/test.txt")
        assertEquals(File(expectedRoot, "sub/dir/test.txt"), nestedFile)

        val emptyName = StoragePaths.primaryFile("")
        assertEquals(expectedRoot, emptyName)
    }

    // -------------------------------------------------------------------------
    // 8. getPodcastChannelDir(channelTitle)
    // -------------------------------------------------------------------------

    @Test
    fun getPodcastChannelDir_resolvesUnderPodcastsDir() {
        val podcastsDir = StoragePaths.getPodcastsDir()
        assertNotNull(podcastsDir)
        assertEquals("Podcasts", podcastsDir.name)

        val channelName = "Daily_News"
        val channelDir = StoragePaths.getPodcastChannelDir(channelName)
        assertEquals(File(podcastsDir, channelName), channelDir)
        assertEquals(podcastsDir, channelDir.parentFile)
        assertEquals(channelName, channelDir.name)
    }

    @Test
    fun getPodcastChannelDir_variousChannelTitles() {
        val podcastsDir = StoragePaths.getPodcastsDir()
        val channels = listOf("History_101", "Tech_Crunch_Daily", "Podcast_2026", "Sub_Genre")
        for (channel in channels) {
            val dir = StoragePaths.getPodcastChannelDir(channel)
            assertEquals(File(podcastsDir, channel), dir)
            assertTrue(dir.absolutePath.startsWith(podcastsDir.absolutePath))
        }
    }

    // -------------------------------------------------------------------------
    // 9. invalidate()
    // -------------------------------------------------------------------------

    @Test
    fun invalidate_clearsCachesAndAllowsReprobing() {
        // Prime caches
        val primary1 = StoragePaths.getPrimaryRoot()
        val roots1 = StoragePaths.getAllRoots()
        val hasSecondary1 = StoragePaths.hasSecondaryStorage()

        assertNotNull(primary1)
        assertNotNull(roots1)
        assertFalse(roots1.isEmpty())

        // Invalidate
        StoragePaths.invalidate()

        // Re-probe
        val primary2 = StoragePaths.getPrimaryRoot()
        val roots2 = StoragePaths.getAllRoots()
        val hasSecondary2 = StoragePaths.hasSecondaryStorage()

        assertEquals(primary1.absolutePath, primary2.absolutePath)
        assertEquals(roots1.size, roots2.size)
        assertEquals(hasSecondary1, hasSecondary2)
    }

    @Test
    fun invalidate_successiveCallsDoNotThrow() {
        StoragePaths.invalidate()
        StoragePaths.invalidate()
        StoragePaths.invalidate()
        assertNotNull(StoragePaths.getPrimaryRoot())
    }

    // -------------------------------------------------------------------------
    // 10. Additional Directory Helpers & Constants Parity
    // -------------------------------------------------------------------------

    @Test
    fun constantsParity() {
        assertEquals("/storage/sdcard0", StoragePaths.PRIMARY_PATH)
        assertEquals("/storage/sdcard1", StoragePaths.SECONDARY_PATH)
    }

    @Test
    fun getWebServerRoot_returnsPrimaryRoot() {
        assertEquals(StoragePaths.getPrimaryRoot(), StoragePaths.getWebServerRoot())
    }

    @Test
    fun primaryMediaDirs_haveCorrectNamesAndParents() {
        val primaryRoot = StoragePaths.getPrimaryRoot()
        assertEquals("Music", StoragePaths.getMusicDir().name)
        assertEquals(primaryRoot, StoragePaths.getMusicDir().parentFile)

        assertEquals("Audiobooks", StoragePaths.getAudiobooksDir().name)
        assertEquals(primaryRoot, StoragePaths.getAudiobooksDir().parentFile)

        assertEquals("Videos", StoragePaths.getVideosDir().name)
        assertEquals(primaryRoot, StoragePaths.getVideosDir().parentFile)

        assertEquals("Podcasts", StoragePaths.getPodcastsDir().name)
        assertEquals(primaryRoot, StoragePaths.getPodcastsDir().parentFile)
    }

    @Test
    fun mediaDirsLists_containPrimaryMediaDirs() {
        val musicDirs = StoragePaths.getMusicDirs()
        assertTrue(musicDirs.any { it.name == "Music" })

        val audiobookDirs = StoragePaths.getAudiobooksDirs()
        assertTrue(audiobookDirs.any { it.name == "Audiobooks" })

        val videoDirs = StoragePaths.getVideosDirs()
        assertTrue(videoDirs.any { it.name == "Videos" })
    }

    @Test
    fun appSpecificDirs_haveCorrectNamesAndPrimaryParent() {
        val primaryRoot = StoragePaths.getPrimaryRoot()
        assertEquals("Y1_Covers", StoragePaths.getCoversDir().name)
        assertEquals(primaryRoot, StoragePaths.getCoversDir().parentFile)

        assertEquals(".y1_thumbs_cache", StoragePaths.getThumbsCacheDir().name)
        assertEquals(primaryRoot, StoragePaths.getThumbsCacheDir().parentFile)

        assertEquals("Y1_Themes", StoragePaths.getThemesDir().name)
        assertEquals(primaryRoot, StoragePaths.getThemesDir().parentFile)

        assertEquals("Y1_Languages", StoragePaths.getLanguagesDir().name)
        assertEquals(primaryRoot, StoragePaths.getLanguagesDir().parentFile)

        assertEquals("Y1_Playlists", StoragePaths.getPlaylistsDir().name)
        assertEquals(primaryRoot, StoragePaths.getPlaylistsDir().parentFile)

        assertEquals("Y1_EQs", StoragePaths.getEqDir().name)
        assertEquals(primaryRoot, StoragePaths.getEqDir().parentFile)
    }
}
