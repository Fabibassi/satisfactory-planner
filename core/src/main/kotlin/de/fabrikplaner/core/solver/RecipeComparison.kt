package de.fabrikplaner.core.solver

import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.model.Recipe
import kotlin.math.abs

data class RecipeFlow(
    val itemId: String,
    val perMinute: Double,
)

/** One machine of a recipe at 100% clock. */
data class RecipeSheet(
    val recipeId: String,
    val buildingId: String,
    val cycleSeconds: Double,
    val powerMw: Double,
    val inputs: List<RecipeFlow>,
    val outputs: List<RecipeFlow>,
)

data class RateDelta(
    val key: String,
    val baseline: Double,
    val alternate: Double,
) {
    val delta: Double get() = alternate - baseline
}

data class RecipeComparison(
    val itemId: String,
    val baseline: RecipeSheet,
    val alternate: RecipeSheet,
    val raw: List<RateDelta>,
    val machines: List<RateDelta>,
    val byproducts: List<RateDelta>,
    val powerBaselineMw: Double,
    val powerAlternateMw: Double,
)

private const val DELTA_EPS = 1e-4

/**
 * Items that have more than one recipe, sorted by [nameOf] then id.
 * The solver's machine order is ignored, so a selection cannot reshuffle the list.
 */
fun orderedChoiceItemIds(
    game: GameData,
    solved: SolverResult,
    nameOf: (String) -> String = { id -> game.itemOrNull(id)?.name?.de ?: id },
): List<String> {
    return solved.machines.mapNotNull { machine ->
        val recipe = machine.recipeId?.let { game.recipeOrNull(it) } ?: return@mapNotNull null
        val itemId = recipe.outputs.firstOrNull()?.itemId ?: return@mapNotNull null
        if (game.recipesProducing(itemId).size > 1) itemId else null
    }.distinct().sortedWith(compareBy(nameOf, { it }))
}

/**
 * Keeps the relative order of items already on screen. Newly appearing items
 * are appended in [current] order; removed items drop out.
 */
fun stableChoiceOrder(previous: List<String>, current: List<String>): List<String> {
    if (previous.isEmpty()) return current
    val currentSet = current.toSet()
    val previousSet = previous.toSet()
    return previous.filter { it in currentSet } + current.filter { it !in previousSet }
}

/**
 * Default recipe for [itemId] against [alternateRecipeId], with every other
 * selection in [selection] kept on both sides.
 */
fun compareRecipes(
    game: GameData,
    solve: (Map<String, String>) -> SolverResult,
    selection: Map<String, String>,
    itemId: String,
    alternateRecipeId: String,
): RecipeComparison? {
    val baselineRecipe = game.recipeFor(itemId) ?: return null
    val alternateRecipe = game.recipeOrNull(alternateRecipeId) ?: return null
    if (alternateRecipe.id == baselineRecipe.id) return null
    if (alternateRecipe.outputs.firstOrNull()?.itemId != itemId) return null
    val baselineResult = solve(selection + (itemId to baselineRecipe.id))
    val alternateResult = solve(selection + (itemId to alternateRecipe.id))
    return RecipeComparison(
        itemId = itemId,
        baseline = recipeSheet(game, baselineRecipe),
        alternate = recipeSheet(game, alternateRecipe),
        raw = rateDeltas(baselineResult.rawResources, alternateResult.rawResources, changedOnly = false),
        machines = machineDeltas(baselineResult, alternateResult),
        byproducts = rateDeltas(baselineResult.byproducts, alternateResult.byproducts, changedOnly = true),
        powerBaselineMw = baselineResult.totalPowerMw,
        powerAlternateMw = alternateResult.totalPowerMw,
    )
}

private fun recipeSheet(game: GameData, recipe: Recipe): RecipeSheet =
    RecipeSheet(
        recipeId = recipe.id,
        buildingId = recipe.buildingId,
        cycleSeconds = recipe.cycleSeconds,
        powerMw = game.buildingOrNull(recipe.buildingId)?.powerMw ?: 0.0,
        inputs = recipe.inputs.map { RecipeFlow(it.itemId, game.perMinute(it, recipe)) },
        outputs = recipe.outputs.map { RecipeFlow(it.itemId, game.perMinute(it, recipe)) },
    )

private fun rateDeltas(
    baseline: List<ItemRate>,
    alternate: List<ItemRate>,
    changedOnly: Boolean,
): List<RateDelta> {
    val base = baseline.associate { it.itemId to it.perMinute }
    val alt = alternate.associate { it.itemId to it.perMinute }
    return (base.keys + alt.keys).sorted().map { key ->
        RateDelta(key, base[key] ?: 0.0, alt[key] ?: 0.0)
    }.filter { !changedOnly || abs(it.delta) > DELTA_EPS }
}

private fun machineDeltas(baseline: SolverResult, alternate: SolverResult): List<RateDelta> {
    fun counts(result: SolverResult): Map<String, Double> =
        result.machines.mapNotNull { machine ->
            machine.recipeId?.let { id -> id to machine.machineCount }
        }.toMap()

    val base = counts(baseline)
    val alt = counts(alternate)
    return (base.keys + alt.keys).sorted().map { key ->
        RateDelta(key, base[key] ?: 0.0, alt[key] ?: 0.0)
    }.filter { abs(it.delta) > DELTA_EPS }
}
