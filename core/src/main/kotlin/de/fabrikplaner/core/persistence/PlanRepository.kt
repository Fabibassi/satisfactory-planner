package de.fabrikplaner.core.persistence

import de.fabrikplaner.core.model.SavedPlan
import kotlinx.serialization.json.Json
import java.io.File

class PlanRepository(private val directory: File) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        directory.mkdirs()
    }

    fun list(): List<SavedPlan> =
        directory.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { json.decodeFromString(SavedPlan.serializer(), file.readText()) }.getOrNull()
            }
            ?.sortedByDescending { it.updatedAtEpochMs }
            ?: emptyList()

    fun load(id: String): SavedPlan? {
        val file = file(id)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(SavedPlan.serializer(), file.readText()) }.getOrNull()
    }

    fun save(plan: SavedPlan) {
        directory.mkdirs()
        file(plan.id).writeText(json.encodeToString(SavedPlan.serializer(), plan))
    }

    fun delete(id: String) {
        file(id).delete()
    }

    private fun file(id: String): File = File(directory, "$id.json")
}
