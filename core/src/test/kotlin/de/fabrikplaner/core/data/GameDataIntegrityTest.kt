package de.fabrikplaner.core.data

import de.fabrikplaner.core.solver.assertClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameDataIntegrityTest {
    private val game = GameDataLoader.loadResource()

    @Test
    fun referencesAndDefaultsAreConsistent() {
        assertEquals("1.0", game.gameVersion)
        assertTrue(game.source.contains("1.0"))
        assertEquals(game.items.size, game.items.map { it.id }.toSet().size)
        assertEquals(game.recipes.size, game.recipes.map { it.id }.toSet().size)
        assertTrue(game.recipes.any { it.alternate })
        val standards = mutableMapOf<String, MutableList<String>>()
        for (recipe in game.recipes) {
            assertTrue(game.buildingOrNull(recipe.buildingId) != null, recipe.id)
            assertTrue(recipe.cycleSeconds > 0)
            assertTrue(recipe.outputs.isNotEmpty())
            for (stack in recipe.inputs + recipe.outputs) {
                assertTrue(game.itemOrNull(stack.itemId) != null, "${recipe.id} -> ${stack.itemId}")
                assertTrue(stack.amount > 0)
            }
            if (!recipe.alternate) {
                standards.getOrPut(recipe.outputs.first().itemId) { mutableListOf() } += recipe.id
            }
        }
        for (item in game.items) {
            if (item.raw) {
                assertTrue(game.buildingOrNull(item.extractorId ?: "") != null, item.id)
                assertTrue((item.extractPerMinute ?: 0.0) > 0)
            }
            item.defaultRecipeId?.let { recipeId ->
                val recipe = game.recipe(recipeId)
                assertEquals(item.id, recipe.outputs.first().itemId)
            }
            val options = standards[item.id].orEmpty()
            if (options.size > 1) {
                assertTrue(item.defaultRecipeId in options, "${item.id} defaults ${item.defaultRecipeId} not in $options")
            }
            if (!item.raw) {
                val resolved = game.recipeFor(item.id)
                if (options.size == 1) assertEquals(options.single(), resolved?.id)
            }
        }
    }

    @Test
    fun wikiPerMinuteRatesMatchCycleAmounts() {
        val expected = mapOf(
            "motor" to 5.0,
            "rotor" to 4.0,
            "stator" to 5.0,
            "screw" to 40.0,
            "iron_rod" to 15.0,
            "iron_ingot" to 30.0,
            "iron_plate" to 20.0,
            "reinforced_iron_plate" to 5.0,
            "modular_frame" to 2.0,
            "steel_ingot" to 45.0,
            "steel_pipe" to 20.0,
            "steel_beam" to 15.0,
            "encased_industrial_beam" to 6.0,
            "wire" to 30.0,
            "cable" to 30.0,
            "copper_ingot" to 30.0,
            "concrete" to 15.0,
            "plastic" to 20.0,
            "rubber" to 20.0,
            "fuel" to 40.0,
            "residual_fuel" to 40.0,
            "heavy_modular_frame" to 2.0,
            "computer" to 2.5,
            "circuit_board" to 7.5,
            "alumina_solution" to 120.0,
            "aluminum_scrap" to 360.0,
            "aluminum_ingot" to 60.0,
            "cast_screw" to 50.0,
            "iron_wire" to 22.5,
            "solid_steel_ingot" to 60.0,
            "recycled_plastic" to 60.0,
            "recycled_rubber" to 60.0,
            "diluted_fuel" to 100.0,
            "pure_iron_ingot" to 65.0,
            "heavy_oil_residue" to 40.0,
        )
        for ((recipeId, perMinute) in expected) {
            val recipe = game.recipe(recipeId)
            val actual = game.perMinute(recipe.outputs.first(), recipe)
            assertClose(perMinute, actual, 1e-6)
        }
        val rotor = game.recipe("rotor")
        assertClose(20.0, game.inputRate(rotor, "iron_rod"))
        assertClose(100.0, game.inputRate(rotor, "screw"))
        val hmf = game.recipe("heavy_modular_frame")
        assertClose(10.0, game.inputRate(hmf, "modular_frame"))
        assertClose(40.0, game.inputRate(hmf, "steel_pipe"))
        assertClose(10.0, game.inputRate(hmf, "encased_industrial_beam"))
        assertClose(240.0, game.inputRate(hmf, "screw"))
        val plastic = game.recipe("plastic")
        assertClose(10.0, game.outputRate(plastic, "heavy_oil_residue"))
        val rubber = game.recipe("rubber")
        assertClose(20.0, game.outputRate(rubber, "heavy_oil_residue"))
    }
}
