package de.fabrikplaner.core.data

import de.fabrikplaner.core.model.Building
import de.fabrikplaner.core.model.GameDataFile
import de.fabrikplaner.core.model.Item
import de.fabrikplaner.core.model.ItemAmount
import de.fabrikplaner.core.model.Recipe

class GameData(file: GameDataFile) {
    val gameVersion: String = file.gameVersion
    val source: String = file.source
    val items: List<Item> = file.items
    val buildings: List<Building> = file.buildings
    val recipes: List<Recipe> = file.recipes

    private val itemsById = items.associateBy { it.id }
    private val buildingsById = buildings.associateBy { it.id }
    private val recipesById = recipes.associateBy { it.id }

    fun item(id: String): Item = itemsById[id] ?: throw IllegalArgumentException("unknown item $id")

    fun itemOrNull(id: String): Item? = itemsById[id]

    fun building(id: String): Building = buildingsById[id] ?: throw IllegalArgumentException("unknown building $id")

    fun buildingOrNull(id: String): Building? = buildingsById[id]

    fun recipe(id: String): Recipe = recipesById[id] ?: throw IllegalArgumentException("unknown recipe $id")

    fun recipeOrNull(id: String): Recipe? = recipesById[id]

    fun perMinute(amount: ItemAmount, recipe: Recipe): Double = amount.amount / recipe.cycleSeconds * 60.0

    fun inputRate(recipe: Recipe, itemId: String): Double =
        recipe.inputs.filter { it.itemId == itemId }.sumOf { perMinute(it, recipe) }

    fun outputRate(recipe: Recipe, itemId: String): Double =
        recipe.outputs.filter { it.itemId == itemId }.sumOf { perMinute(it, recipe) }

    /** Recipes whose first output is [itemId], standard recipes first. */
    fun recipesProducing(itemId: String): List<Recipe> =
        recipes.filter { it.outputs.firstOrNull()?.itemId == itemId }
            .sortedWith(compareBy({ it.alternate }, { it.id }))

    /**
     * Recipe used to craft [itemId]. An explicit selection wins when that recipe's
     * primary output is the item. Otherwise the item default or the unique standard recipe.
     */
    fun recipeFor(itemId: String, selection: Map<String, String> = emptyMap()): Recipe? {
        val selected = selection[itemId]?.let { recipeOrNull(it) }
        if (selected != null && selected.outputs.firstOrNull()?.itemId == itemId) return selected
        val item = itemOrNull(itemId) ?: return null
        val defaultId = item.defaultRecipeId
        if (defaultId != null) {
            val recipe = recipeOrNull(defaultId)
            if (recipe != null && recipe.outputs.firstOrNull()?.itemId == itemId) return recipe
        }
        val standards = recipes.filter { !it.alternate && it.outputs.firstOrNull()?.itemId == itemId }
        return standards.singleOrNull()
    }
}
