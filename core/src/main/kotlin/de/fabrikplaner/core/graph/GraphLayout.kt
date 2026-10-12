package de.fabrikplaner.core.graph

import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.model.GraphEdge
import de.fabrikplaner.core.model.GraphNode
import de.fabrikplaner.core.model.NodeKind
import de.fabrikplaner.core.model.TargetRate
import de.fabrikplaner.core.solver.SolverResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class FactoryGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
)

object GraphLayout {
    fun fromSolution(result: SolverResult, targets: List<TargetRate>, game: GameData): FactoryGraph {
        val recipeNodes = result.machines.mapNotNull { machine ->
            val recipeId = machine.recipeId ?: return@mapNotNull null
            val recipe = game.recipe(recipeId)
            GraphNode(
                id = "recipe-$recipeId",
                kind = NodeKind.RECIPE,
                x = 0f,
                y = 0f,
                recipeId = recipeId,
                buildingId = recipe.buildingId,
                machineCount = machine.machineCount,
                clockPercent = 100.0,
            )
        }
        val sources = result.extractors.map { extractor ->
            val itemId = extractor.outputs.first().itemId
            GraphNode(
                id = "src-$itemId",
                kind = NodeKind.SOURCE,
                x = 0f,
                y = 0f,
                buildingId = extractor.buildingId,
                itemId = itemId,
                machineCount = extractor.machineCount,
                ratePerMinute = extractor.outputs.first().perMinute,
            )
        }
        val sinks = targets.filter { it.perMinute > 0.0 }.map { target ->
            GraphNode(
                id = "sink-${target.itemId}",
                kind = NodeKind.SINK,
                x = 0f,
                y = 0f,
                itemId = target.itemId,
                ratePerMinute = target.perMinute,
            )
        }
        val nodes = sources + recipeNodes + sinks
        val edges = connect(nodes, game)
        val arranged = arrange(nodes, edges)
        return FactoryGraph(polish(arranged, edges, game), edges)
    }

    /**
     * Keeps the barycenter order, then shifts whole cards so connected ports share a y
     * when the column has room. Long edges keep one open channel through the layers they cross.
     */
    private fun polish(nodes: List<GraphNode>, edges: List<GraphEdge>, game: GameData): List<GraphNode> {
        var current = nodes
        repeat(4) { pass ->
            current = priorityPass(current, edges, game, leftToRight = pass % 2 == 0, bothSides = false)
        }
        current = uncross(current, edges, game)
        repeat(2) { pass ->
            current = priorityPass(current, edges, game, leftToRight = pass % 2 == 0, bothSides = true)
        }
        repeat(3) {
            current = snapJogs(current, edges, game, allowAlign = true)
            current = clearHighways(current, edges, game)
        }
        current = snapJogs(current, edges, game, allowAlign = true)
        current = clearHighways(current, edges, game)
        current = snapJogs(current, edges, game, allowAlign = true)
        current = snapJogs(current, edges, game, allowAlign = false)
        return EdgeRouter.reserveLanes(current, edges, game)
    }

    /**
     * Sugiyama-style placement: longest-path layers left to right, then barycenter sweeps
     * to cut crossings. Ports are ordered separately from the finished coordinates.
     */
    fun arrange(nodes: List<GraphNode>, edges: List<GraphEdge>): List<GraphNode> {
        if (nodes.isEmpty()) return nodes
        val compacted = compactLayers(assignLayers(nodes, edges))
        val layers = nodes.groupBy { compacted[it.id] ?: 0 }
            .mapValues { (_, column) -> column.sortedBy { it.id }.toMutableList() }
            .toMutableMap()
        repeat(8) { pass ->
            sweep(layers, edges, leftToRight = pass % 2 == 0)
        }
        val maxLayer = layers.keys.maxOrNull() ?: 0
        val placed = mutableListOf<GraphNode>()
        for (layer in 0..maxLayer) {
            placed += place(layers[layer].orEmpty(), layer)
        }
        return placed
    }

    fun straightCrossings(nodes: List<GraphNode>, edges: List<GraphEdge>): Int {
        val byId = nodes.associateBy { it.id }
        val segments = edges.mapNotNull { edge ->
            val from = byId[edge.fromNodeId] ?: return@mapNotNull null
            val to = byId[edge.toNodeId] ?: return@mapNotNull null
            Segment(
                edge.fromNodeId,
                edge.toNodeId,
                from.x + GraphMetrics.CARD_W / 2f,
                from.y + GraphMetrics.CARD_H / 2f,
                to.x + GraphMetrics.CARD_W / 2f,
                to.y + GraphMetrics.CARD_H / 2f,
            )
        }
        var count = 0
        for (i in segments.indices) {
            for (j in i + 1 until segments.size) {
                val a = segments[i]
                val b = segments[j]
                if (a.from == b.from || a.from == b.to || a.to == b.from || a.to == b.to) continue
                if (properCross(a, b)) count++
            }
        }
        return count
    }

    private fun assignLayers(nodes: List<GraphNode>, edges: List<GraphEdge>): Map<String, Int> {
        val incoming = edges.groupBy { it.toNodeId }
        val memo = HashMap<String, Int>()
        fun depth(id: String, stack: Set<String>): Int {
            memo[id]?.let { return it }
            if (id in stack) return 0
            val preds = incoming[id].orEmpty()
            val value = if (preds.isEmpty()) 0 else preds.maxOf { 1 + depth(it.fromNodeId, stack + id) }
            memo[id] = value
            return value
        }
        nodes.forEach { depth(it.id, emptySet()) }
        return memo
    }

    private fun compactLayers(layers: Map<String, Int>): Map<String, Int> {
        val used = layers.values.distinct().sorted()
        val remap = used.withIndex().associate { it.value to it.index }
        return layers.mapValues { remap.getValue(it.value) }
    }

    private fun sweep(
        layers: MutableMap<Int, MutableList<GraphNode>>,
        edges: List<GraphEdge>,
        leftToRight: Boolean,
    ) {
        val max = layers.keys.maxOrNull() ?: return
        val sequence = if (leftToRight) (1..max).toList() else (max - 1 downTo 0).toList()
        val outgoing = edges.groupBy { it.fromNodeId }
        val incoming = edges.groupBy { it.toNodeId }
        for (layer in sequence) {
            val neighbor = if (leftToRight) layer - 1 else layer + 1
            val indexOf = layers[neighbor].orEmpty().mapIndexed { index, node -> node.id to index.toDouble() }.toMap()
            val current = layers[layer] ?: continue
            val ranked = current.mapIndexed { fallback, node ->
                val links = if (leftToRight) incoming[node.id].orEmpty() else outgoing[node.id].orEmpty()
                val samples = links.mapNotNull { edge ->
                    val other = if (leftToRight) edge.fromNodeId else edge.toNodeId
                    indexOf[other]
                }
                val score = if (samples.isEmpty()) fallback.toDouble() else samples.average()
                Rank(score, fallback, node)
            }
            layers[layer] = ranked.sortedWith(compareBy({ it.score }, { it.fallback })).map { it.node }.toMutableList()
        }
    }

    private fun place(layerNodes: List<GraphNode>, layer: Int): List<GraphNode> =
        layerNodes.mapIndexed { index, node ->
            val x = GraphMetrics.ORIGIN + layer * (GraphMetrics.CARD_W + GraphMetrics.GAP_X)
            val y = GraphMetrics.ORIGIN + index * (GraphMetrics.CARD_H + GraphMetrics.GAP_Y)
            node.copy(x = x, y = y)
        }

    private fun connect(nodes: List<GraphNode>, game: GameData): List<GraphEdge> {
        val edges = mutableListOf<GraphEdge>()
        val itemIds = LinkedHashSet<String>()
        for (node in nodes) {
            when (node.kind) {
                NodeKind.SOURCE, NodeKind.SINK -> node.itemId?.let { itemIds += it }
                NodeKind.RECIPE -> {
                    val recipe = node.recipeId?.let { game.recipeOrNull(it) } ?: continue
                    recipe.inputs.forEach { itemIds += it.itemId }
                    recipe.outputs.forEach { itemIds += it.itemId }
                }
                else -> node.itemId?.let { itemIds += it }
            }
        }
        var serial = 0
        for (itemId in itemIds) {
            val producers = producers(nodes, game, itemId)
            val consumers = consumers(nodes, game, itemId)
            for (producer in producers) {
                for (consumer in consumers) {
                    if (producer == consumer) continue
                    if (producer.second <= 1e-6 || consumer.second <= 1e-6) continue
                    edges += GraphEdge(
                        id = "e-${serial++}",
                        fromNodeId = producer.first,
                        toNodeId = consumer.first,
                        itemId = itemId,
                    )
                }
            }
        }
        return edges
    }

    private fun producers(nodes: List<GraphNode>, game: GameData, itemId: String): List<Pair<String, Double>> =
        nodes.mapNotNull { node ->
            val amount = when (node.kind) {
                NodeKind.SOURCE -> if (node.itemId == itemId) node.ratePerMinute else 0.0
                NodeKind.RECIPE -> {
                    val recipe = node.recipeId?.let { game.recipeOrNull(it) } ?: return@mapNotNull null
                    game.outputRate(recipe, itemId) * node.machineCount * node.clockPercent / 100.0
                }
                else -> 0.0
            }
            if (amount > 1e-6) node.id to amount else null
        }

    private fun consumers(nodes: List<GraphNode>, game: GameData, itemId: String): List<Pair<String, Double>> =
        nodes.mapNotNull { node ->
            val amount = when (node.kind) {
                NodeKind.SINK -> if (node.itemId == itemId) node.ratePerMinute else 0.0
                NodeKind.RECIPE -> {
                    val recipe = node.recipeId?.let { game.recipeOrNull(it) } ?: return@mapNotNull null
                    game.inputRate(recipe, itemId) * node.machineCount * node.clockPercent / 100.0
                }
                else -> 0.0
            }
            if (amount > 1e-6) node.id to amount else null
        }

    private fun properCross(a: Segment, b: Segment): Boolean {
        val o1 = orient(a.x1, a.y1, a.x2, a.y2, b.x1, b.y1)
        val o2 = orient(a.x1, a.y1, a.x2, a.y2, b.x2, b.y2)
        val o3 = orient(b.x1, b.y1, b.x2, b.y2, a.x1, a.y1)
        val o4 = orient(b.x1, b.y1, b.x2, b.y2, a.x2, a.y2)
        return o1 != o2 && o3 != o4 && o1 != 0 && o2 != 0 && o3 != 0 && o4 != 0
    }

    private fun orient(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Int {
        val value = (by - ay).toDouble() * (cx - bx) - (bx - ax).toDouble() * (cy - by)
        return when {
            value > 1e-3 -> 1
            value < -1e-3 -> -1
            else -> 0
        }
    }

    private fun priorityPass(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        game: GameData,
        leftToRight: Boolean,
        bothSides: Boolean,
    ): List<GraphNode> {
        val xs = nodes.map { it.x }.distinct().sorted()
        val order = if (leftToRight) xs else xs.asReversed()
        val yOf = nodes.associate { it.id to it.y }.toMutableMap()
        fun materialize(): List<GraphNode> = nodes.map { it.copy(y = yOf.getValue(it.id)) }
        for (x in order) {
            val snapshot = materialize()
            val column = snapshot.filter { it.x == x }.sortedBy { it.y }
            val desired = column.map { node ->
                val samples = linkedSamples(node, snapshot, edges, game, leftToRight, bothSides)
                if (samples.isEmpty()) node.y else median(samples)
            }
            val packed = pack(desired, GraphMetrics.CARD_H + GraphMetrics.GAP_Y)
            column.forEachIndexed { index, node -> yOf[node.id] = packed[index] }
        }
        return materialize()
    }

    private fun linkedSamples(
        node: GraphNode,
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        game: GameData,
        leftToRight: Boolean,
        bothSides: Boolean,
    ): List<Float> {
        val links = edges.filter { edge ->
            when {
                bothSides -> edge.fromNodeId == node.id || edge.toNodeId == node.id
                leftToRight -> edge.toNodeId == node.id
                else -> edge.fromNodeId == node.id
            }
        }
        return links.mapNotNull { edge ->
            val incoming = edge.toNodeId == node.id
            val other = nodes.find { it.id == if (incoming) edge.fromNodeId else edge.toNodeId } ?: return@mapNotNull null
            val myOff = portOffset(node, edge.itemId, game, nodes, edges, output = !incoming)
            val otherOff = portOffset(other, edge.itemId, game, nodes, edges, output = incoming)
            other.y + otherOff - myOff
        }
    }

    private fun portOffset(
        node: GraphNode,
        itemId: String,
        game: GameData,
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        output: Boolean,
    ): Float {
        val y = if (output) {
            GraphPorts.outputCenter(node, itemId, game, nodes, edges).second
        } else {
            GraphPorts.inputCenter(node, itemId, game, nodes, edges).second
        }
        return y - node.y
    }

    private fun pack(desired: List<Float>, sep: Float): List<Float> {
        val n = desired.size
        if (n == 0) return emptyList()
        val y = desired.toFloatArray()
        for (index in 1 until n) {
            val floor = y[index - 1] + sep
            if (y[index] < floor) y[index] = floor
        }
        for (index in n - 2 downTo 0) {
            val cap = y[index + 1] - sep
            val floor = if (index == 0) Float.NEGATIVE_INFINITY else y[index - 1] + sep
            y[index] = desired[index].coerceIn(floor, cap)
        }
        return y.toList()
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    private fun uncross(nodes: List<GraphNode>, edges: List<GraphEdge>, game: GameData): List<GraphNode> {
        var current = nodes
        repeat(16) {
            val before = inversionCount(current, edges, game)
            var best = current
            var bestScore = before
            for (column in current.groupBy { it.x }.values) {
                val ordered = column.sortedBy { it.y }
                for (index in 0 until ordered.lastIndex) {
                    val upper = ordered[index]
                    val lower = ordered[index + 1]
                    val trial = current.map {
                        when (it.id) {
                            upper.id -> it.copy(y = lower.y)
                            lower.id -> it.copy(y = upper.y)
                            else -> it
                        }
                    }
                    val score = inversionCount(trial, edges, game)
                    if (score < bestScore) {
                        best = trial
                        bestScore = score
                    }
                }
            }
            if (bestScore >= before) return current
            current = best
        }
        return current
    }

    private fun inversionCount(nodes: List<GraphNode>, edges: List<GraphEdge>, game: GameData): Int {
        val xs = nodes.map { it.x }.distinct().sorted()
        val byId = nodes.associateBy { it.id }
        data class Ends(val sLayer: Int, val dLayer: Int, val sy: Float, val ty: Float)
        val ends = edges.mapNotNull { edge ->
            val from = byId[edge.fromNodeId] ?: return@mapNotNull null
            val to = byId[edge.toNodeId] ?: return@mapNotNull null
            val source = xs.indexOf(from.x)
            val dest = xs.indexOf(to.x)
            if (dest <= source) return@mapNotNull null
            val sy = GraphPorts.outputCenter(from, edge.itemId, game, nodes, edges).second
            val ty = GraphPorts.inputCenter(to, edge.itemId, game, nodes, edges).second
            Ends(source, dest, sy, ty)
        }
        var count = 0
        for (i in ends.indices) {
            for (j in i + 1 until ends.size) {
                val a = ends[i]
                val b = ends[j]
                if (max(a.sLayer, b.sLayer) >= min(a.dLayer, b.dLayer)) continue
                if ((a.sy - b.sy) * (a.ty - b.ty) < 0f) count++
            }
        }
        return count
    }

    private fun snapJogs(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        game: GameData,
        allowAlign: Boolean,
    ): List<GraphNode> {
        var current = nodes
        for (pass in 0 until 6) {
            var changed = false
            for (edge in edges) {
                val from = current.find { it.id == edge.fromNodeId } ?: continue
                val to = current.find { it.id == edge.toNodeId } ?: continue
                if (from.x >= to.x) continue
                val sy = GraphPorts.outputCenter(from, edge.itemId, game, current, edges).second
                val ty = GraphPorts.inputCenter(to, edge.itemId, game, current, edges).second
                val raw = ty - sy
                val distance = abs(raw)
                if (distance < 0.5f) continue
                val undersized = distance < EdgeRouter.MIN_SEGMENT - 0.05f
                var applied = false
                if (allowAlign && distance <= 80f) {
                    val options = listOf(to.id to -raw, from.id to raw)
                    for ((id, delta) in options) {
                        val node = current.first { it.id == id }
                        if (!fits(current, id, node.y + delta)) continue
                        val trial = current.map { if (it.id == id) it.copy(y = it.y + delta) else it }
                        if (blockedHighways(trial, edges, game) > blockedHighways(current, edges, game)) continue
                        current = trial
                        applied = true
                        changed = true
                        break
                    }
                }
                if (applied || !undersized) continue
                val expand = if (raw >= 0f) EdgeRouter.MIN_SEGMENT - distance else -(EdgeRouter.MIN_SEGMENT - distance)
                val target = current.first { it.id == to.id }
                current = if (fits(current, to.id, target.y + expand)) {
                    current.map { if (it.id == to.id) it.copy(y = it.y + expand) else it }
                } else {
                    val source = current.first { it.id == from.id }
                    if (fits(current, from.id, source.y - expand)) {
                        current.map { if (it.id == from.id) it.copy(y = it.y - expand) else it }
                    } else {
                        shiftTail(current, to.id, expand)
                    }
                }
                changed = true
            }
            if (!changed) break
        }
        return current
    }

    private fun clearHighways(nodes: List<GraphNode>, edges: List<GraphEdge>, game: GameData): List<GraphNode> {
        var current = nodes
        for (pass in 0 until 8) {
            var changed = false
            for (edge in edges) {
                var guard = 0
                while (guard++ < 8) {
                    val from = current.find { it.id == edge.fromNodeId } ?: break
                    val to = current.find { it.id == edge.toNodeId } ?: break
                    val xs = current.map { it.x }.distinct().sorted()
                    val source = xs.indexOf(from.x)
                    val dest = xs.indexOf(to.x)
                    if (dest <= source + 1) break
                    val sy = GraphPorts.outputCenter(from, edge.itemId, game, current, edges).second
                    val ty = GraphPorts.inputCenter(to, edge.itemId, game, current, edges).second
                    val mids = current.filter {
                        val layer = xs.indexOf(it.x)
                        layer in (source + 1) until dest
                    }
                    val syClear = mids.none { EdgeRouter.coversChannel(it, sy) }
                    val tyClear = mids.none { EdgeRouter.coversChannel(it, ty) }
                    val aligned = abs(sy - ty) <= 0.5f
                    if (aligned && syClear) break
                    if (!aligned && (syClear || tyClear)) break
                    val line = when {
                        aligned -> sy
                        !syClear && !tyClear -> if (shiftCost(mids, sy) <= shiftCost(mids, ty)) sy else ty
                        !syClear -> sy
                        else -> ty
                    }
                    val blocker = mids.filter { EdgeRouter.coversChannel(it, line) }.minByOrNull { it.y } ?: break
                    val delta = bumped(clearanceDelta(blocker, line))
                    if (abs(delta) < 0.01f) break
                    current = shiftTail(current, blocker.id, delta)
                    changed = true
                }
            }
            if (!changed) break
        }
        return current
    }

    private fun blockedHighways(nodes: List<GraphNode>, edges: List<GraphEdge>, game: GameData): Int {
        val xs = nodes.map { it.x }.distinct().sorted()
        val byId = nodes.associateBy { it.id }
        var count = 0
        for (edge in edges) {
            val from = byId[edge.fromNodeId] ?: continue
            val to = byId[edge.toNodeId] ?: continue
            val source = xs.indexOf(from.x)
            val dest = xs.indexOf(to.x)
            if (dest <= source + 1) continue
            val sy = GraphPorts.outputCenter(from, edge.itemId, game, nodes, edges).second
            val ty = GraphPorts.inputCenter(to, edge.itemId, game, nodes, edges).second
            val mids = nodes.filter {
                val layer = xs.indexOf(it.x)
                layer in (source + 1) until dest
            }
            val syClear = mids.none { EdgeRouter.coversChannel(it, sy) }
            val tyClear = mids.none { EdgeRouter.coversChannel(it, ty) }
            val aligned = abs(sy - ty) <= 0.5f
            if (aligned && !syClear) count++
            if (!aligned && !syClear && !tyClear) count++
        }
        return count
    }

    private fun shiftCost(nodes: List<GraphNode>, line: Float): Float =
        nodes.sumOf { abs(clearanceDelta(it, line)).toDouble() }.toFloat()

    private fun clearanceDelta(node: GraphNode, line: Float): Float {
        if (!EdgeRouter.coversChannel(node, line)) return 0f
        val down = (line + EdgeRouter.CHANNEL) - node.y
        val up = (line - EdgeRouter.CHANNEL - GraphMetrics.CARD_H) - node.y
        return if (abs(up) < abs(down)) up else down
    }

    private fun bumped(delta: Float): Float {
        if (abs(delta) < 0.01f) return 0f
        if (abs(delta) >= EdgeRouter.MIN_SEGMENT) return delta
        return if (delta > 0f) EdgeRouter.MIN_SEGMENT else -EdgeRouter.MIN_SEGMENT
    }

    private fun fits(nodes: List<GraphNode>, nodeId: String, newY: Float): Boolean {
        val node = nodes.first { it.id == nodeId }
        for (other in nodes) {
            if (other.id == nodeId || other.x != node.x) continue
            val upper = min(newY, other.y)
            val lower = max(newY, other.y)
            if (lower < upper + GraphMetrics.CARD_H + 8f) return false
        }
        return true
    }

    private fun shiftTail(nodes: List<GraphNode>, nodeId: String, delta: Float): List<GraphNode> {
        val node = nodes.first { it.id == nodeId }
        val column = nodes.filter { it.x == node.x }.sortedBy { it.y }
        val index = column.indexOfFirst { it.id == nodeId }
        val ids = if (delta >= 0f) {
            column.drop(index).map { it.id }.toSet()
        } else {
            column.take(index + 1).map { it.id }.toSet()
        }
        return nodes.map { if (it.id in ids) it.copy(y = it.y + delta) else it }
    }

    private data class Rank(val score: Double, val fallback: Int, val node: GraphNode)

    private data class Segment(
        val from: String,
        val to: String,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
    )
}
