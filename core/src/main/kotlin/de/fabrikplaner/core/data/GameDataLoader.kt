package de.fabrikplaner.core.data

import de.fabrikplaner.core.model.GameDataFile
import kotlinx.serialization.json.Json

object GameDataLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): GameData = GameData(json.decodeFromString(GameDataFile.serializer(), text))

    fun loadResource(path: String = "/game_data.json"): GameData {
        val stream = GameDataLoader::class.java.getResourceAsStream(path)
            ?: error("Bundled game data not found at $path")
        return parse(stream.bufferedReader().readText())
    }
}
