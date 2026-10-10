package com.engabd.sendpin.library

import com.engabd.sendpin.ma.MaItem
import kotlin.test.Test
import kotlin.test.assertEquals

/** Folders rebuilt from where each file sits, for this phone's music. */
class FolderTreeTest {

    private fun track(id: String, name: String, n: Int? = null, disc: Int? = null) =
        MaItem(id, "local", name, "content://$id", "track", null, null, null, trackNumber = n, discNumber = disc)

    private val entries = listOf(
        "Music/Albums/Coastal Drive/" to track("1", "Salt Air", 2),
        "Music/Albums/Coastal Drive/" to track("2", "Morning Tide", 1),
        "Music/Albums/blue hours/" to track("3", "Night Bus", 1),
        "Music/Albums/Compilations/Late Night/Disc 1/" to track("4", "After Midnight", 1, 1),
        "Music/Loose/" to track("5", "Demo"),
        "Music/" to track("6", "Voice memo"),
    )

    private fun ids(list: List<MaItem>) = list.map { it.itemId }

    @Test
    fun `a level lists its own folders by name, then its tracks in play order`() {
        val (folders, tracks) = FolderTree.level("Music/Albums", entries)
        assertEquals(listOf("Music/Albums/blue hours", "Music/Albums/Coastal Drive", "Music/Albums/Compilations"), folders)
        assertEquals(emptyList(), tracks)

        val (none, album) = FolderTree.level("Music/Albums/Coastal Drive/", entries)
        assertEquals(emptyList(), none)
        assertEquals(listOf("2", "1"), ids(album))
    }

    @Test
    fun `the top holds the first folders and any files sitting there`() {
        val (folders, tracks) = FolderTree.level("Music", entries)
        assertEquals(listOf("Music/Albums", "Music/Loose"), folders)
        assertEquals(listOf("6"), ids(tracks))
        assertEquals(listOf("Music") to emptyList(), FolderTree.level("", entries))
    }

    @Test
    fun `browsing starts below folders that hold only one other folder`() {
        assertEquals("Music", FolderTree.start(entries))
        val nested = listOf("Music/Albums/A/" to track("1", "x"), "Music/Albums/B/" to track("2", "y"))
        assertEquals("Music/Albums", FolderTree.start(nested))
        assertEquals("", FolderTree.start(emptyList()))
    }

    @Test
    fun `everything under a folder plays folder by folder, however deep`() {
        assertEquals(listOf("3", "2", "1", "4"), ids(FolderTree.under("Music/Albums", entries)))
        assertEquals(listOf("4"), ids(FolderTree.under("Music/Albums/Compilations", entries)))
        // "Music/Albums/Coast" is not a parent of "Music/Albums/Coastal Drive".
        assertEquals(emptyList(), FolderTree.under("Music/Albums/Coast", entries))
    }

    @Test
    fun `paths are compared without their slashes, and named by their last part`() {
        assertEquals("Music/Albums", FolderTree.clean("/Music/Albums/"))
        assertEquals("Coastal Drive", FolderTree.name("Music/Albums/Coastal Drive/"))
        assertEquals("Folders", FolderTree.name(""))
    }
}
