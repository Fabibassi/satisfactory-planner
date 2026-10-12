package de.fabrikplaner.core.graph

import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.model.GraphEdge
import de.fabrikplaner.core.model.GraphNode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class RoutePoint(val x: Float, val y: Float)

data class EdgeRoute(val edgeId: String, val points: List<RoutePoint>)

/**
 * Orthogonal routes that stay in the gaps between columns and in channels between cards.
 * A segment may touch the port of its own endpoints. It must not cross any other card.
 */
object EdgeRouter {
    const val CLEARANCE = 36f
    const val LANE_PITCH = 24f

    fun openChannels(nodes: List<GraphNode>, edges: List<GraphEdge>): List<GraphNode> {
        if (nodes.isEmpty()) return nodes
        val yById = channelTops(nodes, edges)
        return nodes.map { node -> yById[node.id]?.let { node.copy(y = it) } ?: node }
    }

    fun paths(nodes: List<GraphNode>, edges: List<GraphEdge>, game: GameData): List<EdgeRoute> {
        if (nodes.isEmpty() || edges.isEmpty()) return emptyList()
        val byId = nodes.associateBy { it.id }
        val xs = nodes.map { it.x }.distinct().sorted()
        val layerOf: (GraphNode) -> Int = { node -> xs.indexOf(node.x).coerceAtLeast(0) }
        val columns = xs.indices.map { index -> nodes.filter { layerOf(it) == index }.sortedBy { it.y } }
        val used = xs.indices.associateWith { mutableListOf<Float>() }
        return edges.mapNotNull { edge ->
            val from = byId[edge.fromNodeId] ?: return@mapNotNull null
            val to = byId[edge.toNodeId] ?: return@mapNotNull null
            val start = port(from, edge.itemId, game, nodes, edges, output = true)
            val end = port(to, edge.itemId, game, nodes, edges, output = false)
            val points = orthogonal(from, to, start, end, layerOf, columns, xs, used)
            EdgeRoute(edge.id, simplify(points))
        }
    }

    fun violations(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        routes: List<EdgeRoute>,
        clearance: Float = 4f,
    ): List<String> {
        val endsOf = edges.associate { it.id to setOf(it.fromNodeId, it.toNodeId) }
        val problems = mutableListOf<String>()
        for (route in routes) {
            val ends = endsOf[route.edgeId] ?: emptySet()
            val points = route.points
            for (index in 0 until points.lastIndex) {
                val a = points[index]
                val b = points[index + 1]
                if (abs(a.x - b.x) < 0.01f && abs(a.y - b.y) < 0.01f) continue
                for (node in nodes) {
                    val foreign = node.id !in ends
                    val hit = if (foreign) {
                        segmentHitsRect(
                            a,
                            b,
                            node.x - clearance,
                            node.y - clearance,
                            node.x + GraphMetrics.CARD_W + clearance,
                            node.y + GraphMetrics.CARD_H + clearance,
                        )
                    } else {
                        segmentHitsRect(
                            a,
                            b,
                            node.x + 1f,
                            node.y + 1f,
                            node.x + GraphMetrics.CARD_W - 1f,
                            node.y + GraphMetrics.CARD_H - 1f,
                        )
                    }
                    if (hit) problems += "${route.edgeId} crosses ${node.id}"
                }
            }
        }
        return problems.distinct()
    }

    private fun channelTops(nodes: List<GraphNode>, edges: List<GraphEdge>): Map<String, Float> {
        val xs = nodes.map { it.x }.distinct().sorted()
        fun layerOf(node: GraphNode): Int = xs.indexOf(node.x)
        val byId = nodes.associateBy { it.id }
        val columns = xs.indices.associateWith { index ->
            nodes.filter { layerOf(it) == index }.sortedBy { it.y }
        }
        data class Item(val key: Double, val tie: String, val nodeId: String?, val edgeId: String?)
        val items = xs.indices.associateWith { mutableListOf<Item>() }
        columns.forEach { (layer, column) ->
            column.forEachIndexed { index, node ->
                items.getValue(layer) += Item(index.toDouble(), node.id, node.id, null)
            }
        }
        for (edge in edges) {
            val from = byId[edge.fromNodeId] ?: continue
            val to = byId[edge.toNodeId] ?: continue
            val lo = min(layerOf(from), layerOf(to))
            val hi = max(layerOf(from), layerOf(to))
            if (hi <= lo + 1) continue
            val src = if (layerOf(from) <= layerOf(to)) from else to
            val dst = if (layerOf(from) <= layerOf(to)) to else from
            val srcIndex = columns.getValue(layerOf(src)).indexOfFirst { it.id == src.id }.coerceAtLeast(0).toDouble()
            val dstIndex = columns.getValue(layerOf(dst)).indexOfFirst { it.id == dst.id }.coerceAtLeast(0).toDouble()
            for (mid in (lo + 1) until hi) {
                val t = (mid - lo).toDouble() / (hi - lo)
                val key = srcIndex + (dstIndex - srcIndex) * t
                items.getValue(mid) += Item(key, edge.id, null, edge.id)
            }
        }
        val yById = HashMap<String, Float>()
        for (layer in xs.indices) {
            val sorted = items.getValue(layer).sortedWith(compareBy({ it.key }, { it.tie }))
            var cursor = GraphMetrics.ORIGIN
            var previous = "none"
            var laneY = GraphMetrics.ORIGIN
            for (item in sorted) {
                if (item.nodeId != null) {
                    if (previous == "node") cursor += GraphMetrics.GAP_Y
                    if (previous == "dummy") cursor = laneY + CLEARANCE
                    yById[item.nodeId] = cursor
                    cursor += GraphMetrics.CARD_H
                    previous = "node"
                } else {
                    laneY = if (previous == "dummy") laneY + LANE_PITCH else cursor + CLEARANCE
                    previous = "dummy"
                }
            }
        }
        return yById
    }

    private fun orthogonal(
        from: GraphNode,
        to: GraphNode,
        start: RoutePoint,
        end: RoutePoint,
        layerOf: (GraphNode) -> Int,
        columns: List<List<GraphNode>>,
        xs: List<Float>,
        used: Map<Int, MutableList<Float>>,
    ): List<RoutePoint> {
        val srcLayer = layerOf(from)
        val dstLayer = layerOf(to)
        val points = mutableListOf(start)
        if (srcLayer == dstLayer) {
            val column = columns.getOrElse(srcLayer) { emptyList() }
            val above = (column.minOfOrNull { it.y } ?: start.y) - CLEARANCE - LANE_PITCH
            val lane = reserve(used.getValue(srcLayer), above, above)
            val right = from.x + GraphMetrics.CARD_W + CLEARANCE
            val left = to.x - CLEARANCE
            points += RoutePoint(right, start.y)
            points += RoutePoint(right, lane)
            points += RoutePoint(left, lane)
            points += RoutePoint(left, end.y)
            points += end
            return points
        }
        val forward = srcLayer < dstLayer
        val firstGutter = gutter(xs, if (forward) srcLayer else srcLayer - 1)
        points += RoutePoint(firstGutter, start.y)
        var gutterX = firstGutter
        var layer = srcLayer
        while (layer != dstLayer) {
            val next = if (forward) layer + 1 else layer - 1
            if (next == dstLayer) break
            val preferred = lerp(start.y, end.y, abs(next - srcLayer).toFloat() / abs(dstLayer - srcLayer))
            val lane = pickLane(columns[next], preferred, used.getValue(next))
            val nextGutter = gutter(xs, if (forward) next else next - 1)
            points += RoutePoint(gutterX, lane)
            points += RoutePoint(nextGutter, lane)
            gutterX = nextGutter
            layer = next
        }
        points += RoutePoint(gutterX, end.y)
        points += end
        return points
    }

    private fun pickLane(column: List<GraphNode>, preferred: Float, used: MutableList<Float>): Float {
        val gaps = freeIntervals(column)
        val finite = gaps.filter { it.first.isFinite() && it.second.isFinite() }
        val sorted = column.sortedBy { it.y }
        val inside = sorted.isNotEmpty() &&
            preferred > sorted.first().y - 1f &&
            preferred < sorted.last().y + GraphMetrics.CARD_H + 1f
        val primary = if (inside && finite.isNotEmpty()) finite else gaps
        val choice = nearestFree(primary, preferred, used)
            ?: nearestFree(gaps, preferred, used)
            ?: ((sorted.maxOfOrNull { it.y + GraphMetrics.CARD_H } ?: preferred) + CLEARANCE + used.size * LANE_PITCH)
        used += choice
        return choice
    }

    private fun nearestFree(gaps: List<Pair<Float, Float>>, preferred: Float, used: List<Float>): Float? {
        val options = mutableListOf<Float>()
        for ((low, high) in gaps) {
            if (low == Float.NEGATIVE_INFINITY) {
                options += high
                for (step in 1..used.size + 3) options += high - step * LANE_PITCH
            } else if (high == Float.POSITIVE_INFINITY) {
                options += low
                for (step in 1..used.size + 3) options += low + step * LANE_PITCH
            } else if (high - low < 0.5f) {
                options += (low + high) / 2f
            } else {
                var y = low
                while (y <= high + 0.01f) {
                    options += y.coerceAtMost(high)
                    if (y >= high - 0.01f) break
                    y = (y + LANE_PITCH).coerceAtMost(high)
                    if (abs(y - options.last()) < 0.01f) break
                }
            }
        }
        return options
            .distinct()
            .filter { candidate -> used.none { abs(it - candidate) < LANE_PITCH - 0.5f } }
            .minByOrNull { abs(it - preferred) }
    }

    private fun reserve(used: MutableList<Float>, preferred: Float, fallback: Float): Float {
        var y = preferred
        while (used.any { abs(it - y) < LANE_PITCH - 0.5f }) y = fallback - (used.size + 1) * LANE_PITCH
        used += y
        return y
    }

    private fun freeIntervals(column: List<GraphNode>): List<Pair<Float, Float>> {
        if (column.isEmpty()) return listOf(Float.NEGATIVE_INFINITY to Float.POSITIVE_INFINITY)
        val sorted = column.sortedBy { it.y }
        val gaps = mutableListOf<Pair<Float, Float>>()
        gaps += Float.NEGATIVE_INFINITY to (sorted.first().y - CLEARANCE)
        for (index in 0 until sorted.lastIndex) {
            val low = sorted[index].y + GraphMetrics.CARD_H + CLEARANCE
            val high = sorted[index + 1].y - CLEARANCE
            if (high - low >= -0.01f) gaps += low to max(low, high)
        }
        gaps += (sorted.last().y + GraphMetrics.CARD_H + CLEARANCE) to Float.POSITIVE_INFINITY
        return gaps
    }

    private fun gutter(xs: List<Float>, layer: Int): Float {
        val left = xs[layer] + GraphMetrics.CARD_W
        val right = xs[layer + 1]
        return (left + right) / 2f
    }

    private fun port(
        node: GraphNode,
        itemId: String,
        game: GameData,
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        output: Boolean,
    ): RoutePoint {
        val point = if (output) {
            GraphPorts.outputCenter(node, itemId, game, nodes, edges)
        } else {
            GraphPorts.inputCenter(node, itemId, game, nodes, edges)
        }
        return RoutePoint(point.first, point.second)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun simplify(points: List<RoutePoint>): List<RoutePoint> {
        val compact = mutableListOf<RoutePoint>()
        for (point in points) {
            val last = compact.lastOrNull()
            if (last == null || abs(last.x - point.x) > 0.01f || abs(last.y - point.y) > 0.01f) {
                compact += point
            }
        }
        return compact
    }

    fun segmentHitsRect(a: RoutePoint, b: RoutePoint, left: Float, top: Float, right: Float, bottom: Float): Boolean {
        val minX = min(a.x, b.x)
        val maxX = max(a.x, b.x)
        val minY = min(a.y, b.y)
        val maxY = max(a.y, b.y)
        if (maxX < left || minX > right || maxY < top || minY > bottom) return false
        if (abs(a.x - b.x) < 0.01f || abs(a.y - b.y) < 0.01f) return true
        return lineHitsRect(a, b, left, top, right, bottom)
    }

    private fun lineHitsRect(a: RoutePoint, b: RoutePoint, left: Float, top: Float, right: Float, bottom: Float): Boolean {
        var t0 = 0f
        var t1 = 1f
        val dx = b.x - a.x
        val dy = b.y - a.y
        fun clip(p: Float, q: Float): Boolean {
            if (abs(p) < 1e-6f) return q >= 0f
            val r = q / p
            if (p < 0f) {
                if (r > t1) return false
                if (r > t0) t0 = r
            } else {
                if (r < t0) return false
                if (r < t1) t1 = r
            }
            return true
        }
        if (!clip(-dx, a.x - left)) return false
        if (!clip(dx, right - a.x)) return false
        if (!clip(-dy, a.y - top)) return false
        if (!clip(dy, bottom - a.y)) return false
        return t0 <= t1
    }
}
