package de.fabrikplaner.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.graph.BalanceReport
import de.fabrikplaner.core.graph.GraphBalancer
import de.fabrikplaner.core.graph.GraphLayout
import de.fabrikplaner.core.graph.GraphOps
import de.fabrikplaner.core.model.GraphNode
import de.fabrikplaner.core.model.NodeKind
import de.fabrikplaner.core.model.SavedPlan
import de.fabrikplaner.core.model.TargetRate
import de.fabrikplaner.core.persistence.PlanRepository
import de.fabrikplaner.core.solver.ProductionSolver
import de.fabrikplaner.core.solver.SolverResult
import java.util.UUID

class PlannerViewModel(
    val game: GameData,
    private val repository: PlanRepository,
) : ViewModel() {
    private val solver = ProductionSolver(game)

    var plans by mutableStateOf<List<SavedPlan>>(emptyList())
        private set
    var current by mutableStateOf<SavedPlan?>(null)
        private set
    var result by mutableStateOf<SolverResult?>(null)
        private set
    var balance by mutableStateOf<BalanceReport?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        plans = repository.list()
    }

    fun open(id: String) {
        if (current?.id == id) return
        val loaded = repository.load(id) ?: return
        current = loaded
        recalculate()
        rebalance()
    }

    fun createPlan(name: String): String {
        val plan = SavedPlan(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Plan" },
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        repository.save(plan)
        current = plan
        result = solver.solve(emptyList())
        balance = null
        refresh()
        return plan.id
    }

    fun createExample(name: String): String {
        current = SavedPlan(
            id = UUID.randomUUID().toString(),
            name = name,
            targets = listOf(TargetRate("motor", 10.0)),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        recalculate()
        generateGraph()
        return current?.id ?: ""
    }

    fun deletePlan(id: String) {
        repository.delete(id)
        if (current?.id == id) {
            current = null
            result = null
            balance = null
        }
        refresh()
    }

    fun rename(name: String) = edit { it.copy(name = name) }

    fun addTarget(itemId: String, rate: Double) {
        if (rate <= 0.0 || !rate.isFinite()) return
        edit { plan ->
            val index = plan.targets.indexOfFirst { it.itemId == itemId }
            val targets = if (index >= 0) {
                plan.targets.mapIndexed { i, target -> if (i == index) target.copy(perMinute = rate) else target }
            } else {
                plan.targets + TargetRate(itemId, rate)
            }
            plan.copy(targets = targets)
        }
        recalculate()
    }

    fun updateTargetRate(itemId: String, rate: Double) {
        if (rate <= 0.0 || !rate.isFinite()) return
        edit { plan ->
            plan.copy(targets = plan.targets.map { if (it.itemId == itemId) it.copy(perMinute = rate) else it })
        }
        recalculate()
    }

    fun removeTarget(itemId: String) {
        edit { plan -> plan.copy(targets = plan.targets.filterNot { it.itemId == itemId }) }
        recalculate()
    }

    fun selectRecipe(itemId: String, recipeId: String) {
        edit { plan -> plan.copy(recipeSelection = plan.recipeSelection + (itemId to recipeId)) }
        recalculate()
    }

    fun generateGraph() {
        val plan = current ?: return
        val solved = result ?: return
        val graph = GraphLayout.fromSolution(solved, plan.targets, game)
        edit(rebalance = true) {
            it.copy(nodes = graph.nodes, edges = graph.edges, graphSignature = it.inputSignature())
        }
    }

    fun moveNode(id: String, x: Float, y: Float) {
        edit(persist = false) { plan ->
            plan.copy(nodes = plan.nodes.map { if (it.id == id) it.copy(x = x, y = y) else it })
        }
    }

    fun finishMove() {
        current?.let {
            repository.save(it)
            refresh()
        }
    }

    fun addRecipeNode(recipeId: String, x: Float, y: Float) {
        val recipe = game.recipeOrNull(recipeId) ?: return
        val node = GraphNode(
            id = newId("node"),
            kind = NodeKind.RECIPE,
            x = x,
            y = y,
            recipeId = recipe.id,
            buildingId = recipe.buildingId,
            machineCount = 1.0,
        )
        edit(rebalance = true) { it.copy(nodes = it.nodes + node) }
    }

    fun addSource(itemId: String, x: Float, y: Float) {
        val item = game.itemOrNull(itemId) ?: return
        val node = GraphNode(
            id = newId("src"),
            kind = NodeKind.SOURCE,
            x = x,
            y = y,
            buildingId = item.extractorId,
            itemId = itemId,
            machineCount = 1.0,
            ratePerMinute = item.extractPerMinute ?: 60.0,
        )
        edit(rebalance = true) { it.copy(nodes = it.nodes + node) }
    }

    fun addSink(itemId: String, rate: Double, x: Float, y: Float) {
        if (rate <= 0.0) return
        val node = GraphNode(
            id = newId("sink"),
            kind = NodeKind.SINK,
            x = x,
            y = y,
            itemId = itemId,
            ratePerMinute = rate,
        )
        edit(rebalance = true) { it.copy(nodes = it.nodes + node) }
    }

    fun addPassthrough(kind: NodeKind, itemId: String, x: Float, y: Float) {
        if (kind != NodeKind.SPLITTER && kind != NodeKind.MERGER) return
        val node = GraphNode(id = newId("log"), kind = kind, x = x, y = y, itemId = itemId)
        edit(rebalance = true) { it.copy(nodes = it.nodes + node) }
    }

    fun updateNode(id: String, transform: (GraphNode) -> GraphNode) {
        edit(rebalance = true) { plan ->
            plan.copy(nodes = plan.nodes.map { if (it.id == id) transform(it) else it })
        }
    }

    fun deleteNode(id: String) {
        edit(rebalance = true) { plan ->
            plan.copy(
                nodes = plan.nodes.filterNot { it.id == id },
                edges = plan.edges.filterNot { it.fromNodeId == id || it.toNodeId == id },
            )
        }
    }

    fun deleteEdge(id: String) {
        edit(rebalance = true) { plan -> plan.copy(edges = plan.edges.filterNot { it.id == id }) }
    }

    fun connect(fromId: String, toId: String, itemId: String): Boolean {
        val plan = current ?: return false
        val connected = GraphOps.connect(plan.nodes, plan.edges, fromId, toId, itemId, game, newId("edge"))
        if (!connected.connected) return false
        edit(rebalance = true) { it.copy(nodes = connected.nodes, edges = connected.edges) }
        return true
    }

    private fun recalculate() {
        val plan = current ?: return
        result = solver.solve(plan.targets, plan.recipeSelection)
    }

    private fun rebalance() {
        val plan = current ?: return
        balance = if (plan.nodes.isEmpty()) null else GraphBalancer.evaluate(plan.nodes, plan.edges, game)
    }

    private fun edit(
        persist: Boolean = true,
        rebalance: Boolean = false,
        block: (SavedPlan) -> SavedPlan,
    ) {
        val now = current ?: return
        val next = block(now).copy(updatedAtEpochMs = System.currentTimeMillis())
        current = next
        if (persist) {
            repository.save(next)
            refresh()
        }
        if (rebalance) this.rebalance()
    }

    private fun newId(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    companion object {
        fun factory(game: GameData, repository: PlanRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlannerViewModel(game, repository) as T
            }
    }
}
