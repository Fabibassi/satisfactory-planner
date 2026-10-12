package de.fabrikplaner.core.solver

import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.model.Recipe
import de.fabrikplaner.core.model.TargetRate
import java.util.ArrayDeque
import kotlin.math.max

/**
 * Solves a production plan at 100% clock (no overclock, no power shards).
 *
 * Each crafted item uses one selected recipe. Machine counts are the unknowns of
 * a linear system: net output of every recipe's primary item equals the requested
 * target (zero for intermediates). Byproducts add supply to other rows, so loops
 * such as recycled plastic/rubber and the alumina water/silica feedback have a
 * single solution. If a recipe count would be negative, that recipe is dropped
 * and the item is allowed to stay a surplus byproduct.
 */
class ProductionSolver(private val game: GameData) {
    fun solve(targets: List<TargetRate>, recipeSelection: Map<String, String> = emptyMap()): SolverResult {
        val requested = LinkedHashMap<String, Double>()
        for (target in targets) {
            if (target.perMinute <= 0.0 || !target.perMinute.isFinite()) continue
            require(game.itemOrNull(target.itemId) != null) { "unknown item ${target.itemId}" }
            requested[target.itemId] = (requested[target.itemId] ?: 0.0) + target.perMinute
        }
        val warnings = mutableListOf<SolverWarning>()
        val missing = mutableSetOf<String>()
        val active = LinkedHashSet<Recipe>()
        val queue = ArrayDeque<String>()
        requested.keys.forEach { queue.add(it) }
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val itemId = queue.removeFirst()
            if (!seen.add(itemId)) continue
            val item = game.item(itemId)
            if (item.raw) continue
            val recipe = game.recipeFor(itemId, recipeSelection)
            if (recipe == null) {
                missing += itemId
                continue
            }
            if (!active.add(recipe)) continue
            recipe.inputs.forEach { queue.add(it.itemId) }
        }

        var current = active.sortedBy { it.id }.toMutableList()
        val counts = LinkedHashMap<String, Double>()
        var guard = current.size + 2
        while (guard-- > 0 && current.isNotEmpty()) {
            val primaries = current.map { it.outputs.first().itemId }
            if (primaries.size != primaries.toSet().size) {
                warnings += SolverWarning(WarningCode.SINGULAR)
                current.clear()
                break
            }
            val n = current.size
            val matrix = Array(n) { DoubleArray(n) }
            val rhs = DoubleArray(n)
            for (row in 0 until n) {
                val itemId = primaries[row]
                rhs[row] = requested[itemId] ?: 0.0
                for (col in 0 until n) {
                    val recipe = current[col]
                    matrix[row][col] = game.outputRate(recipe, itemId) - game.inputRate(recipe, itemId)
                }
            }
            val solution = LinearSolver.solve(matrix, rhs)
            if (solution == null) {
                warnings += SolverWarning(WarningCode.SINGULAR)
                current.clear()
                break
            }
            val negative = solution.indices.filter { solution[it] < -1e-5 }.minByOrNull { solution[it] }
            if (negative == null) {
                current.forEachIndexed { index, recipe ->
                    val count = solution[index]
                    if (count > 1e-6) counts[recipe.id] = count
                }
                break
            }
            val removed = current.removeAt(negative)
            warnings += SolverWarning(
                code = WarningCode.CLAMPED,
                itemId = removed.outputs.first().itemId,
            )
        }

        val recipeById = game.recipes.associateBy { it.id }
        val usedRecipes = counts.keys.map { recipeById.getValue(it) }
        val depthMemo = HashMap<String, Int>()
        fun depth(recipeId: String, stack: Set<String>): Int {
            depthMemo[recipeId]?.let { return it }
            if (recipeId in stack) return 1
            val recipe = recipeById.getValue(recipeId)
            var value = 1
            for (input in recipe.inputs) {
                for (producer in usedRecipes) {
                    if (game.outputRate(producer, input.itemId) <= 0.0) continue
                    value = max(value, 1 + depth(producer.id, stack + recipeId))
                }
            }
            depthMemo[recipeId] = value
            return value
        }

        val machines = counts.map { (recipeId, count) ->
            val recipe = recipeById.getValue(recipeId)
            val building = game.building(recipe.buildingId)
            MachineUsage(
                recipeId = recipe.id,
                buildingId = building.id,
                machineCount = count,
                powerMw = craftingPowerMw(building.powerMw, count, 100.0),
                depth = depth(recipe.id, emptySet()),
                inputs = recipe.inputs.map { ItemRate(it.itemId, game.perMinute(it, recipe) * count) },
                outputs = recipe.outputs.map { ItemRate(it.itemId, game.perMinute(it, recipe) * count) },
            )
        }.sortedWith(compareByDescending<MachineUsage> { it.depth }.thenBy { it.recipeId })

        fun net(itemId: String): Double =
            machines.sumOf { machine ->
                val recipe = recipeById.getValue(checkNotNull(machine.recipeId))
                (game.outputRate(recipe, itemId) - game.inputRate(recipe, itemId)) * machine.machineCount
            }

        val touched = LinkedHashSet<String>()
        requested.keys.forEach { touched += it }
        machines.forEach { machine ->
            machine.inputs.forEach { touched += it.itemId }
            machine.outputs.forEach { touched += it.itemId }
        }
        missing.forEach { touched += it }

        val raw = mutableListOf<ItemRate>()
        val byproducts = mutableListOf<ItemRate>()
        for (itemId in touched) {
            val item = game.item(itemId)
            val explicit = requested[itemId] ?: 0.0
            val net = net(itemId)
            if (item.raw) {
                val extract = explicit - net
                if (extract > 1e-4) raw += ItemRate(itemId, extract)
                else if (-extract > 1e-4) byproducts += ItemRate(itemId, -extract)
                continue
            }
            val deficit = explicit - net
            if (deficit > 1e-3) {
                if (game.recipeFor(itemId, recipeSelection) == null) {
                    warnings += SolverWarning(WarningCode.NO_RECIPE, itemId, deficit)
                } else {
                    warnings += SolverWarning(WarningCode.DEFICIT, itemId, deficit)
                }
            }
            val surplus = net - explicit
            if (surplus > 1e-3) byproducts += ItemRate(itemId, surplus)
        }

        val extractors = raw.map { rate ->
            val item = game.item(rate.itemId)
            val building = game.building(item.extractorId!!)
            val perMachine = item.extractPerMinute!!
            val count = rate.perMinute / perMachine
            MachineUsage(
                recipeId = null,
                buildingId = building.id,
                machineCount = count,
                powerMw = craftingPowerMw(building.powerMw, count, 100.0),
                depth = 0,
                inputs = emptyList(),
                outputs = listOf(rate),
            )
        }.sortedBy { it.outputs.first().itemId }

        val totalPower = machines.sumOf { it.powerMw } + extractors.sumOf { it.powerMw }
        return SolverResult(
            machines = machines,
            extractors = extractors,
            rawResources = raw.sortedBy { it.itemId },
            byproducts = byproducts.sortedBy { it.itemId },
            totalPowerMw = totalPower,
            warnings = warnings,
        )
    }
}
