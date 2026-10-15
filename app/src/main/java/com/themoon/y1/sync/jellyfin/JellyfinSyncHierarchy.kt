package com.themoon.y1.sync.jellyfin

import java.util.Collections
import java.util.Comparator

/**
 * Hierarchical tree representation and tri-state calculation helper for Jellyfin items.
 * Supports:
 * - TV Shows: Series -> Seasons -> Episodes
 * - Music: Artists -> Albums -> Songs
 * - Movies: Flat list of movies
 */
class JellyfinSyncHierarchy {

    enum class TriState {
        UNCHECKED,
        INDETERMINATE,
        CHECKED
    }

    class SeriesNode(
        val id: String,
        val name: String
    ) {
        val seasons: MutableList<SeasonNode> = ArrayList()
        val allEpisodeIds: MutableSet<String> = HashSet()
        var totalSize: Long = 0

        fun getTriState(selectedIds: Set<String>?): TriState {
            return computeTriState(allEpisodeIds, selectedIds)
        }

        fun getSelectedCount(selectedIds: Set<String>?): Int {
            return countSelected(allEpisodeIds, selectedIds)
        }
    }

    class SeasonNode(
        val id: String,
        val name: String,
        val seasonNumber: Int,
        val seriesName: String
    ) {
        val episodes: MutableList<JellyfinMediaItem> = ArrayList()
        val allEpisodeIds: MutableSet<String> = HashSet()
        var totalSize: Long = 0

        fun getTriState(selectedIds: Set<String>?): TriState {
            return computeTriState(allEpisodeIds, selectedIds)
        }

        fun getSelectedCount(selectedIds: Set<String>?): Int {
            return countSelected(allEpisodeIds, selectedIds)
        }
    }

    class ArtistNode(
        val name: String
    ) {
        val albums: MutableList<AlbumNode> = ArrayList()
        val allSongIds: MutableSet<String> = HashSet()
        var totalSize: Long = 0

        fun getTriState(selectedIds: Set<String>?): TriState {
            return computeTriState(allSongIds, selectedIds)
        }

        fun getSelectedCount(selectedIds: Set<String>?): Int {
            return countSelected(allSongIds, selectedIds)
        }
    }

    class AlbumNode(
        val id: String,
        val name: String,
        val artistName: String
    ) {
        val songs: MutableList<JellyfinMediaItem> = ArrayList()
        val allSongIds: MutableSet<String> = HashSet()
        var totalSize: Long = 0

        fun getTriState(selectedIds: Set<String>?): TriState {
            return computeTriState(allSongIds, selectedIds)
        }

        fun getSelectedCount(selectedIds: Set<String>?): Int {
            return countSelected(allSongIds, selectedIds)
        }
    }

    class AuthorNode(
        val name: String
    ) {
        val books: MutableList<BookNode> = ArrayList()
        val allChapterIds: MutableSet<String> = HashSet()
        var totalSize: Long = 0

        fun getTriState(selectedIds: Set<String>?): TriState {
            return computeTriState(allChapterIds, selectedIds)
        }

        fun getSelectedCount(selectedIds: Set<String>?): Int {
            return countSelected(allChapterIds, selectedIds)
        }
    }

    class BookNode(
        val id: String,
        val name: String,
        val authorName: String
    ) {
        val chapters: MutableList<JellyfinMediaItem> = ArrayList()
        val allChapterIds: MutableSet<String> = HashSet()
        var totalSize: Long = 0

        fun getTriState(selectedIds: Set<String>?): TriState {
            return computeTriState(allChapterIds, selectedIds)
        }

        fun getSelectedCount(selectedIds: Set<String>?): Int {
            return countSelected(allChapterIds, selectedIds)
        }
    }

    val seriesList: MutableList<SeriesNode> = ArrayList()
    val artistsList: MutableList<ArtistNode> = ArrayList()
    val authorsList: MutableList<AuthorNode> = ArrayList()
    val moviesList: MutableList<JellyfinMediaItem> = ArrayList()
    val allItemIds: MutableSet<String> = HashSet()
    var totalLibrarySize: Long = 0

    companion object {

        fun computeTriState(itemIds: Collection<String>?, selectedIds: Set<String>?): TriState {
            if (itemIds.isNullOrEmpty() || selectedIds.isNullOrEmpty()) {
                return TriState.UNCHECKED
            }
            var selectedCount = 0
            for (id in itemIds) {
                if (selectedIds.contains(id)) {
                    selectedCount++
                }
            }
            return when (selectedCount) {
                0 -> TriState.UNCHECKED
                itemIds.size -> TriState.CHECKED
                else -> TriState.INDETERMINATE
            }
        }
        fun countSelected(itemIds: Collection<String>?, selectedIds: Set<String>?): Int {
            if (itemIds.isNullOrEmpty() || selectedIds.isNullOrEmpty()) {
                return 0
            }
            var count = 0
            for (id in itemIds) {
                if (selectedIds.contains(id)) {
                    count++
                }
            }
            return count
        }
        fun buildTvHierarchy(episodes: List<JellyfinMediaItem>?): JellyfinSyncHierarchy {
            val hierarchy = JellyfinSyncHierarchy()
            if (episodes.isNullOrEmpty()) return hierarchy

            val seriesMap = HashMap<String, SeriesNode>()
            val seasonMap = HashMap<String, SeasonNode>()

            for (ep in episodes) {
                val epId = ep.id
                if (epId.isNullOrEmpty()) continue
                hierarchy.allItemIds.add(epId)
                hierarchy.totalLibrarySize += ep.fileSize

                val seriesKey: String = if (!ep.seriesId.isNullOrEmpty()) {
                    ep.seriesId
                } else if (!ep.seriesName.isNullOrEmpty()) {
                    ep.seriesName!!
                } else {
                    "Unknown Series"
                }
                val seriesTitle: String = if (!ep.seriesName.isNullOrEmpty()) ep.seriesName!! else "Unknown Series"

                var sNode = seriesMap[seriesKey]
                if (sNode == null) {
                    sNode = SeriesNode(seriesKey, seriesTitle)
                    seriesMap[seriesKey] = sNode
                    hierarchy.seriesList.add(sNode)
                }
                sNode.allEpisodeIds.add(epId)
                sNode.totalSize += ep.fileSize

                val seasonKey = seriesKey + "_S_" + ep.parentIndexNumber
                val seasonTitle: String = if (!ep.seasonName.isNullOrEmpty()) {
                    ep.seasonName!!
                } else if (ep.parentIndexNumber > 0) {
                    "Season " + ep.parentIndexNumber
                } else {
                    "Specials"
                }

                var seasonNode = seasonMap[seasonKey]
                if (seasonNode == null) {
                    seasonNode = SeasonNode(seasonKey, seasonTitle, ep.parentIndexNumber, seriesTitle)
                    seasonMap[seasonKey] = seasonNode
                    sNode.seasons.add(seasonNode)
                }
                seasonNode.episodes.add(ep)
                seasonNode.allEpisodeIds.add(epId)
                seasonNode.totalSize += ep.fileSize
            }

            // Sort series by name
            Collections.sort(hierarchy.seriesList, Comparator { o1, o2 ->
                o1.name.compareTo(o2.name, ignoreCase = true)
            })

            // Sort seasons by seasonNumber, and episodes by indexNumber
            for (sNode in hierarchy.seriesList) {
                Collections.sort(sNode.seasons, Comparator { o1, o2 ->
                    if (o1.seasonNumber == 0 && o2.seasonNumber > 0) return@Comparator 1
                    if (o2.seasonNumber == 0 && o1.seasonNumber > 0) return@Comparator -1
                    o1.seasonNumber.compareTo(o2.seasonNumber)
                })
                for (seaNode in sNode.seasons) {
                    Collections.sort(seaNode.episodes, Comparator { o1, o2 ->
                        o1.indexNumber.compareTo(o2.indexNumber)
                    })
                }
            }

            return hierarchy
        }
        fun buildMusicHierarchy(songs: List<JellyfinMediaItem>?): JellyfinSyncHierarchy {
            val hierarchy = JellyfinSyncHierarchy()
            if (songs.isNullOrEmpty()) return hierarchy

            val artistMap = HashMap<String, ArtistNode>()
            val albumMap = HashMap<String, AlbumNode>()

            for (song in songs) {
                val songId = song.id
                if (songId.isNullOrEmpty()) continue
                hierarchy.allItemIds.add(songId)
                hierarchy.totalLibrarySize += song.fileSize

                val rawArtist = if (!song.albumArtist.isNullOrBlank()) {
                    song.albumArtist
                } else if (!song.artist.isNullOrBlank()) {
                    song.artist
                } else {
                    "Unknown Artist"
                }
                val artistName: String = if (!rawArtist.isNullOrBlank()) rawArtist else "Unknown Artist"

                val artistKey = artistName.lowercase()
                var artNode = artistMap[artistKey]
                if (artNode == null) {
                    artNode = ArtistNode(artistName)
                    artistMap[artistKey] = artNode
                    hierarchy.artistsList.add(artNode)
                }
                artNode.allSongIds.add(songId)
                artNode.totalSize += song.fileSize

                val rawAlbum = song.album
                val albumTitle: String = if (!rawAlbum.isNullOrBlank()) rawAlbum else "Unknown Album"
                val albumKey = artistKey + "_" + albumTitle.lowercase()

                var albNode = albumMap[albumKey]
                if (albNode == null) {
                    val albumId = song.albumId ?: albumKey
                    albNode = AlbumNode(albumId, albumTitle, artistName)
                    albumMap[albumKey] = albNode
                    artNode.albums.add(albNode)
                }
                albNode.songs.add(song)
                albNode.allSongIds.add(songId)
                albNode.totalSize += song.fileSize
            }

            // Sort artists by name
            Collections.sort(hierarchy.artistsList, Comparator { o1, o2 ->
                o1.name.compareTo(o2.name, ignoreCase = true)
            })

            // Sort albums by name, and songs by indexNumber
            for (artNode in hierarchy.artistsList) {
                Collections.sort(artNode.albums, Comparator { o1, o2 ->
                    o1.name.compareTo(o2.name, ignoreCase = true)
                })
                for (albNode in artNode.albums) {
                    Collections.sort(albNode.songs, Comparator { o1, o2 ->
                        if (o1.parentIndexNumber != o2.parentIndexNumber) {
                            return@Comparator o1.parentIndexNumber.compareTo(o2.parentIndexNumber)
                        }
                        o1.indexNumber.compareTo(o2.indexNumber)
                    })
                }
            }

            return hierarchy
        }
        fun buildMoviesHierarchy(movies: List<JellyfinMediaItem>?): JellyfinSyncHierarchy {
            val hierarchy = JellyfinSyncHierarchy()
            if (movies.isNullOrEmpty()) return hierarchy

            for (m in movies) {
                val mId = m.id
                if (mId.isNullOrEmpty()) continue
                hierarchy.allItemIds.add(mId)
                hierarchy.totalLibrarySize += m.fileSize
                hierarchy.moviesList.add(m)
            }

            Collections.sort(hierarchy.moviesList, Comparator { o1, o2 ->
                val n1 = o1.name ?: ""
                val n2 = o2.name ?: ""
                n1.compareTo(n2, ignoreCase = true)
            })

            return hierarchy
        }

        fun buildBooksHierarchy(chapters: List<JellyfinMediaItem>?): JellyfinSyncHierarchy {
            val hierarchy = JellyfinSyncHierarchy()
            if (chapters.isNullOrEmpty()) return hierarchy

            val authorMap = HashMap<String, AuthorNode>()
            val bookMap = HashMap<String, BookNode>()

            for (ch in chapters) {
                val chId = ch.id
                if (chId.isNullOrEmpty()) continue
                hierarchy.allItemIds.add(chId)
                hierarchy.totalLibrarySize += ch.fileSize

                val rawAuthor = if (!ch.albumArtist.isNullOrBlank()) {
                    ch.albumArtist
                } else if (!ch.artist.isNullOrBlank()) {
                    ch.artist
                } else {
                    "Unknown Author"
                }
                val authorName: String = if (!rawAuthor.isNullOrBlank()) rawAuthor else "Unknown Author"

                val authorKey = authorName.lowercase()
                var autNode = authorMap[authorKey]
                if (autNode == null) {
                    autNode = AuthorNode(authorName)
                    authorMap[authorKey] = autNode
                    hierarchy.authorsList.add(autNode)
                }
                autNode.allChapterIds.add(chId)
                autNode.totalSize += ch.fileSize

                val rawBook = if (!ch.album.isNullOrBlank()) {
                    ch.album
                } else if (!ch.seriesName.isNullOrBlank()) {
                    ch.seriesName
                } else {
                    ch.name
                }
                val bookTitle: String = if (!rawBook.isNullOrBlank()) rawBook else "Unknown Book"
                val bookKey = authorKey + "_" + bookTitle.lowercase()

                var bkNode = bookMap[bookKey]
                if (bkNode == null) {
                    val bookId = ch.albumId ?: ch.parentId ?: ch.seriesId.ifEmpty { bookKey }
                    bkNode = BookNode(bookId, bookTitle, authorName)
                    bookMap[bookKey] = bkNode
                    autNode.books.add(bkNode)
                }
                bkNode.chapters.add(ch)
                bkNode.allChapterIds.add(chId)
                bkNode.totalSize += ch.fileSize
            }

            // Sort authors by name
            Collections.sort(hierarchy.authorsList, Comparator { o1, o2 ->
                o1.name.compareTo(o2.name, ignoreCase = true)
            })

            // Sort books by name, and chapters by parentIndexNumber (part/disc) then indexNumber
            for (autNode in hierarchy.authorsList) {
                Collections.sort(autNode.books, Comparator { o1, o2 ->
                    o1.name.compareTo(o2.name, ignoreCase = true)
                })
                for (bkNode in autNode.books) {
                    Collections.sort(bkNode.chapters, Comparator { o1, o2 ->
                        if (o1.parentIndexNumber != o2.parentIndexNumber) {
                            return@Comparator o1.parentIndexNumber.compareTo(o2.parentIndexNumber)
                        }
                        if (o1.indexNumber != o2.indexNumber) {
                            return@Comparator o1.indexNumber.compareTo(o2.indexNumber)
                        }
                        val n1 = o1.name ?: ""
                        val n2 = o2.name ?: ""
                        n1.compareTo(n2, ignoreCase = true)
                    })
                }
            }

            return hierarchy
        }
    }
}
