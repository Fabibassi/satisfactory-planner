package de.fabrikplaner.core.persistence

import de.fabrikplaner.core.model.GraphEdge
import de.fabrikplaner.core.model.GraphNode
import de.fabrikplaner.core.model.NodeKind
import de.fabrikplaner.core.model.SavedPlan
import de.fabrikplaner.core.model.TargetRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.nio.file.Files

class PlanRepositoryTest {
    @Test
    fun roundTripAndSkipCorruptFiles() {
        val dir = Files.createTempDirectory("plans").toFile()
        val repo = PlanRepository(dir)
        val plan = SavedPlan(
            id = "plan-1",
            name = "Motoren",
            targets = listOf(TargetRate("motor", 10.0)),
            recipeSelection = mapOf("screw" to "cast_screw"),
            nodes = listOf(GraphNode("n", NodeKind.SINK, 1f, 2f, itemId = "motor", ratePerMinute = 10.0)),
            edges = listOf(GraphEdge("e", "a", "n", "motor")),
            graphSignature = "motor=10.0|screw=cast_screw",
            updatedAtEpochMs = 42L,
        )
        repo.save(plan)
        val loaded = repo.load("plan-1")
        assertEquals(plan, loaded)
        assertEquals(listOf(plan), repo.list())
        dir.resolve("broken.json").writeText("{not json")
        assertEquals(1, repo.list().size)
        repo.delete("plan-1")
        assertNull(repo.load("plan-1"))
        assertEquals(0, repo.list().size)
    }
}
