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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductionSolverTest {
    private val game = GameDataLoader.loadResource()
    private val solver = ProductionSolver(game)

    @Test
    fun tenMotorsUsesDefaultChainAndWikiOreTotals() {
        val result = solver.solve(listOf(TargetRate("motor", 10.0)))
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertClose(2.0, result.machine("motor").machineCount)
        assertClose(5.0, result.machine("rotor").machineCount)
        assertClose(4.0, result.machine("stator").machineCount)
        assertClose(12.5, result.machine("screw").machineCount)
        assertClose(15.0, result.machine("iron_rod").machineCount)
        assertClose(7.5, result.machine("iron_ingot").machineCount)
        assertClose(3.0, result.machine("steel_pipe").machineCount)
        assertClose(2.0, result.machine("steel_ingot").machineCount)
        assertClose(160.0 / 30.0, result.machine("wire").machineCount)
        assertClose(80.0 / 30.0, result.machine("copper_ingot").machineCount)
        assertEquals(
            setOf(
                "motor", "rotor", "stator", "screw", "iron_rod", "iron_ingot",
                "steel_pipe", "steel_ingot", "wire", "copper_ingot",
            ),
            result.machines.map { it.recipeId }.toSet(),
        )
        assertClose(315.0, result.raw("iron_ore"))
        assertClose(80.0, result.raw("copper_ore"))
        assertClose(90.0, result.raw("coal"))
        assertClose(5057.0 / 12.0, result.totalPowerMw, 1e-3)
        assertTrue(result.byproducts.isEmpty())
        assertEquals("assembler", result.machine("motor").buildingId)
        assertClose(30.0, result.machine("motor").powerMw)
    }

    @Test
    fun castScrewRemovesRodDemandForScrews() {
        val result = solver.solve(
            targets = listOf(TargetRate("motor", 10.0)),
            recipeSelection = mapOf("screw" to "cast_screw"),
        )
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertNull(result.machineOrNull("screw"))
        assertClose(10.0, result.machine("cast_screw").machineCount)
        assertClose(100.0 / 15.0, result.machine("iron_rod").machineCount)
        assertClose(315.0, result.raw("iron_ore"))
        assertClose(80.0, result.raw("copper_ore"))
    }

    @Test
    fun ironWireDropsCopperAndRaisesIron() {
        val result = solver.solve(
            targets = listOf(TargetRate("motor", 10.0)),
            recipeSelection = mapOf("wire" to "iron_wire"),
        )
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertNull(result.machineOrNull("wire"))
        assertNull(result.machineOrNull("copper_ingot"))
        assertClose(0.0, result.raw("copper_ore"))
        assertClose(3635.0 / 9.0, result.raw("iron_ore"))
        assertClose(160.0 / 22.5, result.machine("iron_wire").machineCount)
    }

    @Test
    fun pureIronIngotUsesLessOreAndSomeWater() {
        val result = solver.solve(
            targets = listOf(TargetRate("iron_ingot", 30.0)),
            recipeSelection = mapOf("iron_ingot" to "pure_iron_ingot"),
        )
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertClose(30.0 / 65.0, result.machine("pure_iron_ingot").machineCount)
        assertClose(210.0 / 13.0, result.raw("iron_ore"))
        assertClose(120.0 / 13.0, result.raw("water"))
    }

    @Test
    fun plasticLeavesHeavyOilResidue() {
        val result = solver.solve(listOf(TargetRate("plastic", 20.0)))
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertClose(1.0, result.machine("plastic").machineCount)
        assertClose(30.0, result.raw("crude_oil"))
        assertClose(10.0, result.byproduct("heavy_oil_residue"))
        assertClose(40.0, result.totalPowerMw)
    }

    @Test
    fun residualFuelWithoutHorRecipeReportsTheGap() {
        val result = solver.solve(
            targets = listOf(TargetRate("plastic", 20.0), TargetRate("fuel", 20.0)),
            recipeSelection = mapOf("fuel" to "residual_fuel"),
        )
        val hor = result.warnings.single { it.itemId == "heavy_oil_residue" }
        assertEquals(WarningCode.NO_RECIPE, hor.code)
        assertClose(20.0, hor.amountPerMinute ?: 0.0)
        assertClose(1.0, result.machine("plastic").machineCount)
        assertClose(0.5, result.machine("residual_fuel").machineCount)
    }

    @Test
    fun heavyOilResidueAlternateCoversFuelAndPlastic() {
        val result = solver.solve(
            targets = listOf(TargetRate("plastic", 20.0), TargetRate("fuel", 20.0)),
            recipeSelection = mapOf(
                "fuel" to "residual_fuel",
                "heavy_oil_residue" to "heavy_oil_residue",
            ),
        )
        assertTrue(result.warnings.none { it.code == WarningCode.NO_RECIPE || it.code == WarningCode.DEFICIT })
        assertClose(0.5, result.machine("heavy_oil_residue").machineCount)
        assertClose(45.0, result.raw("crude_oil"))
        assertClose(10.0, result.byproduct("polymer_resin"))
    }

    @Test
    fun recycledPlasticAndRubberCycle() {
        val result = solver.solve(
            targets = listOf(TargetRate("plastic", 60.0)),
            recipeSelection = mapOf(
                "plastic" to "recycled_plastic",
                "rubber" to "recycled_rubber",
            ),
        )
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertClose(4.0 / 3.0, result.machine("recycled_plastic").machineCount)
        assertClose(2.0 / 3.0, result.machine("recycled_rubber").machineCount)
        assertClose(1.5, result.machine("fuel").machineCount)
        assertClose(90.0, result.raw("crude_oil"))
        assertClose(45.0, result.byproduct("polymer_resin"))
        assertClose(0.0, result.byproduct("rubber"))
    }

    @Test
    fun aluminumScrapReusesWaterByproduct() {
        val result = solver.solve(listOf(TargetRate("aluminum_scrap", 360.0)))
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertClose(1.0, result.machine("aluminum_scrap").machineCount)
        assertClose(2.0, result.machine("alumina_solution").machineCount)
        assertNull(result.machineOrNull("silica"))
        assertClose(240.0, result.raw("bauxite"))
        assertClose(120.0, result.raw("coal"))
        assertClose(240.0, result.raw("water"))
        assertClose(100.0, result.byproduct("silica"))
        assertClose(160.0, result.totalPowerMw)
    }

    @Test
    fun aluminumIngotUsesSilicaByproductAndStillMinesQuartz() {
        val result = solver.solve(listOf(TargetRate("aluminum_ingot", 60.0)))
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertClose(1.0, result.machine("aluminum_ingot").machineCount)
        assertClose(0.25, result.machine("aluminum_scrap").machineCount)
        assertClose(0.5, result.machine("alumina_solution").machineCount)
        assertClose(4.0 / 3.0, result.machine("silica").machineCount)
        assertClose(60.0, result.raw("bauxite"))
        assertClose(30.0, result.raw("coal"))
        assertClose(60.0, result.raw("water"))
        assertClose(30.0, result.raw("raw_quartz"))
    }

    @Test
    fun platesAndRodsShareIngots() {
        val result = solver.solve(
            listOf(TargetRate("iron_plate", 5.0), TargetRate("iron_rod", 5.0)),
        )
        assertClose(0.25, result.machine("iron_plate").machineCount)
        assertClose(1.0 / 3.0, result.machine("iron_rod").machineCount)
        assertClose(12.5 / 30.0, result.machine("iron_ingot").machineCount)
        assertClose(12.5, result.raw("iron_ore"))
    }

    @Test
    fun heavyModularFrameIsSolvable() {
        val result = solver.solve(listOf(TargetRate("heavy_modular_frame", 1.0)))
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
        assertClose(0.5, result.machine("heavy_modular_frame").machineCount)
        assertClose(2.5, result.machine("modular_frame").machineCount)
        assertTrue(result.rawResources.all { it.perMinute > 0.0 })
    }

    @Test
    fun rawTargetIsOnlyExtraction() {
        val result = solver.solve(listOf(TargetRate("iron_ore", 120.0)))
        assertTrue(result.machines.isEmpty())
        assertTrue(result.warnings.isEmpty())
        assertClose(120.0, result.raw("iron_ore"))
        assertClose(2.0, result.extractors.single().machineCount)
        assertClose(10.0, result.totalPowerMw)
    }

    @Test
    fun unknownItemThrows() {
        assertFailsWith<IllegalArgumentException> {
            solver.solve(listOf(TargetRate("not_an_item", 1.0)))
        }
    }

    @Test
    fun emptyRequest() {
        val result = solver.solve(emptyList())
        assertTrue(result.machines.isEmpty())
        assertEquals(0.0, result.totalPowerMw)
    }

    @Test
    fun negativeRecipeIsClampedWhenByproductCoversDemand() {
        val synthetic = syntheticGame()
        val result = ProductionSolver(synthetic).solve(
            listOf(TargetRate("b", 1.0), TargetRate("d", 1.0)),
        )
        assertNull(result.machineOrNull("make_c"))
        assertClose(1.0, result.machine("make_b").machineCount)
        assertClose(1.0, result.machine("make_a").machineCount)
        assertClose(1.0, result.machine("make_d").machineCount)
        assertClose(2.0, result.byproduct("c"))
        assertEquals(1, result.warnings.count { it.code == WarningCode.CLAMPED && it.itemId == "c" })
        assertTrue(result.warnings.none { it.code == WarningCode.DEFICIT || it.code == WarningCode.NO_RECIPE })
        assertClose(1.0, result.raw("ore"))
    }

    @Test
    fun powerScalesWithClockExponent() {
        assertClose(4.0, craftingPowerMw(4.0, 1.0, 100.0))
        val half = craftingPowerMw(4.0, 1.0, 50.0)
        assertTrue(half < 2.0, "underclock should use less than linear power, was $half")
        assertTrue(half > 1.4, "expected about 1.6 MW, was $half")
    }

    private fun syntheticGame(): GameData {
        fun n(id: String) = LocalizedName(id, id)
        fun raw(id: String) = Item(id, n(id), ItemForm.SOLID, raw = true, extractorId = "miner", extractPerMinute = 60.0)
        fun crafted(id: String) = Item(id, n(id), ItemForm.SOLID)
        fun perMinute(id: String, building: String, inputs: List<Pair<String, Double>>, outputs: List<Pair<String, Double>>) =
            Recipe(
                id = id,
                name = n(id),
                buildingId = building,
                cycleSeconds = 60.0,
                inputs = inputs.map { ItemAmount(it.first, it.second) },
                outputs = outputs.map { ItemAmount(it.first, it.second) },
            )
        return GameData(
            GameDataFile(
                gameVersion = "test",
                items = listOf(raw("ore"), crafted("a"), crafted("b"), crafted("c"), crafted("d")),
                buildings = listOf(Building("miner", n("miner"), 5.0), Building("assembler", n("assembler"), 15.0)),
                recipes = listOf(
                    perMinute("make_a", "assembler", listOf("ore" to 1.0), listOf("a" to 1.0)),
                    perMinute("make_b", "assembler", listOf("a" to 1.0), listOf("b" to 1.0, "c" to 3.0)),
                    perMinute("make_c", "assembler", listOf("ore" to 1.0), listOf("c" to 1.0)),
                    perMinute("make_d", "assembler", listOf("c" to 1.0), listOf("d" to 1.0)),
                ),
            ),
        )
    }
}
