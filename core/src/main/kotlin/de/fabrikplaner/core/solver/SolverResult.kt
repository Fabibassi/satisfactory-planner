package de.fabrikplaner.core.solver

data class ItemRate(
    val itemId: String,
    val perMinute: Double,
)

enum class WarningCode {
    NO_RECIPE,
    DEFICIT,
    SINGULAR,
    CLAMPED,
}

data class SolverWarning(
    val code: WarningCode,
    val itemId: String? = null,
    val amountPerMinute: Double? = null,
)

data class MachineUsage(
    val recipeId: String?,
    val buildingId: String,
    val machineCount: Double,
    val powerMw: Double,
    val depth: Int,
    val inputs: List<ItemRate>,
    val outputs: List<ItemRate>,
)

data class SolverResult(
    val machines: List<MachineUsage>,
    val extractors: List<MachineUsage>,
    val rawResources: List<ItemRate>,
    val byproducts: List<ItemRate>,
    val totalPowerMw: Double,
    val warnings: List<SolverWarning>,
) {
    fun machine(recipeId: String): MachineUsage =
        machines.single { it.recipeId == recipeId }

    fun machineOrNull(recipeId: String): MachineUsage? =
        machines.singleOrNull { it.recipeId == recipeId }

    fun raw(itemId: String): Double =
        rawResources.singleOrNull { it.itemId == itemId }?.perMinute ?: 0.0

    fun byproduct(itemId: String): Double =
        byproducts.singleOrNull { it.itemId == itemId }?.perMinute ?: 0.0
}
