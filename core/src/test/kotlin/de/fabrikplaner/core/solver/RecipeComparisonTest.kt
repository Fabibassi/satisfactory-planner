package de.fabrikplaner.core.solver

import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.data.GameDataLoader
import de.fabrikplaner.core.model.Building
import de.fabrikplaner.core.model.GameDataFile
import de.fabrikplaner.core.model.Item
import de.fabrikplaner.core.model.ItemAmount
import de.fabrikplaner.core.model.ItemForm
import de.fabrikplaner.core.model.LocalizedName
import de.fabrikplaner.core.model.Recipe
import de.fabrikplaner.core.model.TargetRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeComparisonTest {
    private val game = GameDataLoader.loadResource()
    private val solver = ProductionSolver(game)
    private val motors = listOf(TargetRate("motor", 10.0))

    @Test
    fun recipesProducingPutsTheItemDefaultFirst() {
        val widget = GameData(
            GameDataFile(
                gameVersion = "test",
                items = listOf(
                    Item(
                        "widget",
                        LocalizedName("Widget", "Widget"),
                        ItemForm.SOLID,
                        defaultRecipeId = "b_std",
                    ),
                ),
                buildings = listOf(Building("constructor", LocalizedName("Konstruktor", "Constructor"), 4.0)),
                recipes = listOf(
                    Recipe("a_std", LocalizedName("A", "A"), false, "constructor", 2.0, emptyList(), listOf(ItemAmount("widget", 1.0))),
                    Recipe("b_std", LocalizedName("B", "B"), false, "constructor", 4.0, emptyList(), listOf(ItemAmount("widget", 2.0))),
                    Recipe("c_alt", LocalizedName("C", "C"), true, "constructor", 6.0, emptyList(), listOf(ItemAmount("widget", 3.0))),
                ),
            ),
        )
        assertEquals(listOf("b_std", "a_std", "c_alt"), widget.recipesProducing("widget").map { it.id })
        assertEquals(listOf("screw", "cast_screw", "steel_screw"), game.recipesProducing("screw").map { it.id })
    }

    @Test
    fun choiceOrderFollowsItemNameAndIgnoresSolverOrder() {
        val nameOf = { id: String -> game.item(id).name.de }
        val defaults = solver.solve(motors)
        val cast = solver.solve(motors, mapOf("screw" to "cast_screw"))
        val legacyDefaults = legacyChoiceOrder(defaults)
        val legacyCast = legacyChoiceOrder(cast)
        assertNotEquals(legacyDefaults, legacyCast)
        val orderedDefaults = orderedChoiceItemIds(game, defaults, nameOf)
        val orderedCast = orderedChoiceItemIds(game, cast, nameOf)
        assertEquals(orderedDefaults.sortedWith(compareBy(nameOf, { it })), orderedDefaults)
        val shared = orderedDefaults.filter { it in orderedCast.toSet() }
        assertEquals(shared, orderedCast.filter { it in orderedDefaults.toSet() })
        assertTrue("screw" in orderedDefaults)
    }

    @Test
    fun stableChoiceOrderKeepsEarlierRowsWhenTheSetChanges() {
        assertEquals(listOf("a", "b", "c"), stableChoiceOrder(listOf("a", "b", "c"), listOf("c", "a", "b")))
        assertEquals(listOf("a", "c", "d"), stableChoiceOrder(listOf("a", "b", "c"), listOf("d", "a", "c")))
        assertEquals(listOf("b", "a"), stableChoiceOrder(emptyList(), listOf("b", "a")))
    }

    @Test
    fun castScrewComparisonShowsRatesAndPlanDelta() {
        val comparison = compareRecipes(
            game,
            solve = { selection -> solver.solve(motors, selection) },
            selection = emptyMap(),
            itemId = "screw",
            alternateRecipeId = "cast_screw",
        )
        assertNotNull(comparison)
        assertEquals("screw", comparison.baseline.recipeId)
        assertEquals("cast_screw", comparison.alternate.recipeId)
        assertEquals("constructor", comparison.baseline.buildingId)
        assertEquals("constructor", comparison.alternate.buildingId)
        assertClose(6.0, comparison.baseline.cycleSeconds)
        assertClose(24.0, comparison.alternate.cycleSeconds)
        assertClose(4.0, comparison.baseline.powerMw)
        assertClose(4.0, comparison.alternate.powerMw)
        assertClose(10.0, comparison.baseline.inputs.single { it.itemId == "iron_rod" }.perMinute)
        assertClose(40.0, comparison.baseline.outputs.single { it.itemId == "screw" }.perMinute)
        assertClose(12.5, comparison.alternate.inputs.single { it.itemId == "iron_ingot" }.perMinute)
        assertClose(50.0, comparison.alternate.outputs.single { it.itemId == "screw" }.perMinute)

        val screw = comparison.machines.single { it.key == "screw" }
        assertClose(12.5, screw.baseline)
        assertClose(0.0, screw.alternate)
        val castScrew = comparison.machines.single { it.key == "cast_screw" }
        assertClose(0.0, castScrew.baseline)
        assertClose(10.0, castScrew.alternate)
        val rods = comparison.machines.single { it.key == "iron_rod" }
        assertClose(15.0, rods.baseline)
        assertClose(100.0 / 15.0, rods.alternate)
        val ore = comparison.raw.single { it.key == "iron_ore" }
        assertClose(315.0, ore.baseline)
        assertClose(315.0, ore.alternate)
        val copper = comparison.raw.single { it.key == "copper_ore" }
        assertClose(80.0, copper.baseline)
        assertClose(80.0, copper.alternate)
        assertClose(5057.0 / 12.0, comparison.powerBaselineMw, 1e-3)
    }

    @Test
    fun ironWireComparisonDropsCopper() {
        val comparison = compareRecipes(
            game,
            solve = { selection -> solver.solve(motors, selection) },
            selection = emptyMap(),
            itemId = "wire",
            alternateRecipeId = "iron_wire",
        )
        assertNotNull(comparison)
        assertEquals("wire", comparison.baseline.recipeId)
        assertEquals("iron_wire", comparison.alternate.recipeId)
        assertClose(5.0 * 60.0 / 24.0, comparison.alternate.inputs.single { it.itemId == "iron_ingot" }.perMinute)
        assertClose(9.0 * 60.0 / 24.0, comparison.alternate.outputs.single { it.itemId == "wire" }.perMinute)
        val copper = comparison.raw.single { it.key == "copper_ore" }
        assertClose(80.0, copper.baseline)
        assertClose(0.0, copper.alternate)
        val iron = comparison.raw.single { it.key == "iron_ore" }
        assertClose(315.0, iron.baseline)
        assertClose(3635.0 / 9.0, iron.alternate)
        val ironWire = comparison.machines.single { it.key == "iron_wire" }
        assertClose(0.0, ironWire.baseline)
        assertClose(160.0 / 22.5, ironWire.alternate)
        assertTrue(comparison.machines.none { it.key == "copper_ingot" && it.alternate > 0.0 })
    }

    @Test
    fun comparisonKeepsTheRestOfTheDraftSelection() {
        val comparison = compareRecipes(
            game,
            solve = { selection -> solver.solve(motors, selection) },
            selection = mapOf("screw" to "cast_screw"),
            itemId = "wire",
            alternateRecipeId = "iron_wire",
        )
        assertNotNull(comparison)
        assertTrue(comparison.machines.none { it.key == "cast_screw" })
        assertTrue(comparison.machines.any { it.key == "iron_wire" })
        val withCast = solver.solve(motors, mapOf("screw" to "cast_screw"))
        val both = solver.solve(motors, mapOf("screw" to "cast_screw", "wire" to "iron_wire"))
        assertClose(withCast.machine("cast_screw").machineCount, both.machine("cast_screw").machineCount)
    }

    @Test
    fun comparisonRejectsTheDefaultAndForeignRecipes() {
        assertNull(
            compareRecipes(
                game,
                solve = { selection -> solver.solve(motors, selection) },
                selection = emptyMap(),
                itemId = "screw",
                alternateRecipeId = "screw",
            ),
        )
        assertNull(
            compareRecipes(
                game,
                solve = { selection -> solver.solve(motors, selection) },
                selection = emptyMap(),
                itemId = "screw",
                alternateRecipeId = "iron_wire",
            ),
        )
    }

    private fun legacyChoiceOrder(solved: SolverResult): List<String> =
        solved.machines.mapNotNull { machine ->
            val recipe = machine.recipeId?.let { game.recipeOrNull(it) } ?: return@mapNotNull null
            val itemId = recipe.outputs.firstOrNull()?.itemId ?: return@mapNotNull null
            if (game.recipesProducing(itemId).size > 1) itemId else null
        }.distinct()
}
