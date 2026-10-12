package de.fabrikplaner.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LocalizedName(
    val de: String,
    val en: String,
)

@Serializable
enum class ItemForm {
    @SerialName("solid") SOLID,
    @SerialName("liquid") LIQUID,
    @SerialName("gas") GAS,
}

@Serializable
data class Item(
    val id: String,
    val name: LocalizedName,
    val form: ItemForm,
    val raw: Boolean = false,
    val category: String = "basic",
    val defaultRecipeId: String? = null,
    val extractorId: String? = null,
    val extractPerMinute: Double? = null,
)

@Serializable
data class Building(
    val id: String,
    val name: LocalizedName,
    val powerMw: Double,
)

@Serializable
data class ItemAmount(
    val itemId: String,
    val amount: Double,
)

@Serializable
data class Recipe(
    val id: String,
    val name: LocalizedName,
    val alternate: Boolean = false,
    val buildingId: String,
    val cycleSeconds: Double,
    val inputs: List<ItemAmount>,
    val outputs: List<ItemAmount>,
)

@Serializable
data class GameDataFile(
    val gameVersion: String,
    val source: String = "",
    val items: List<Item>,
    val buildings: List<Building>,
    val recipes: List<Recipe>,
)
