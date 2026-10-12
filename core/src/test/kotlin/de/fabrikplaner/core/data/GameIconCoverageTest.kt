package de.fabrikplaner.core.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameIconCoverageTest {
    private val game = GameDataLoader.loadResource()

    @Test
    fun everyItemAndBuildingHasAWikiPng() {
        val root = repoRoot().resolve("gamedata/icons")
        val manifest = root.resolve("manifest.json").readText()
        assertTrue(manifest.contains("https://satisfactory.wiki.gg/"))
        for (item in game.items) {
            assertPng(root.resolve("items/${item.id}.png"))
            assertTrue(manifest.contains("\"id\": \"${item.id}\""), item.id)
        }
        for (building in game.buildings) {
            assertPng(root.resolve("buildings/${building.id}.png"))
            assertTrue(manifest.contains("\"id\": \"${building.id}\""), building.id)
        }
        assertEquals(game.items.size, root.resolve("items").listFiles { file -> file.extension == "png" }!!.size)
        assertEquals(game.buildings.size, root.resolve("buildings").listFiles { file -> file.extension == "png" }!!.size)
    }

    private fun assertPng(file: File) {
        assertTrue(file.isFile, file.path)
        assertTrue(file.length() > 500, file.path)
        val magic = file.inputStream().use { it.readNBytes(8) }
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), magic.take(4).map { it.toInt() and 0xFF }, file.path)
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(4) {
            if (dir.resolve("gamedata/game_data.json").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        error("gamedata not found from ${System.getProperty("user.dir")}")
    }
}
