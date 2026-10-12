package de.fabrikplaner.core.graph

import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.model.GraphEdge
import de.fabrikplaner.core.model.GraphNode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class RoutePoint(val x: Float, val y: Float)

data class EdgeRoute(val edgeId: String, val points: List<RoutePoint>)

data class EdgeStroke(val a: RoutePoint, val b: RoutePoint, val itemIds: List<String>)

/**
 * Orthogonal routes that stay in the gaps between columns and in channels between cards.
 * A segment may touch the port of its own endpoints. It must not cross any other card.
 */
object EdgeRouter {
    const val CLEARANCE = 36f
    const val LANE_PITCH = 24f
    const val TRACK_PITCH = 18f
    const val GUTTER_MARGIN = 20f

    fun openChannels(nodes: List<GraphNode>, edges: List<GraphEdge>): List<GraphNode> {
        if (nodes.isEmpty()) return nodes
        val yById = channelTops(nodes, edges)
        val shifted = nodes.map { node -> yById[node.id]?.let { node.copy(y = it) } ?: node }
        return spreadColumns(shifted, edges)
    }

    fun paths(nodes: List<GraphNode>, edges: List<GraphEdge>, game: GameData): List<EdgeRoute> {
        if (nodes.isEmpty() || edges.isEmpty()) return emptyList()
        val byId = nodes.associateBy { it.id }
        val xs = nodes.map { it.x }.distinct().sorted()
        val layerOf: (GraphNode) -> Int = { node -> xs.indexOf(node.x).coerceAtLeast(0) }
        val columns = xs.indices.map { index -> nodes.filter { layerOf(it) == index }.sortedBy { it.y } }
        val used = xs.indices.associateWith { mutableListOf<Float>() }
        val tracks = tracksByGutter(nodes, edges, layerOf, xs)
        val reserve = HorizontalReserve()
        fun slotOf(gutter: Int, key: SourcePort): GutterSlot =
            tracks[gutter]?.get(key) ?: GutterSlot(gutterCenter(xs, gutter), gutterCenter(xs, gutter))
        for (edge in edges) {
            val from = byId[edge.fromNodeId] ?: continue
            val to = byId[edge.toNodeId] ?: continue
            val source = layerOf(from)
            val dest = layerOf(to)
            if (source == dest) continue
            val key = SourcePort(edge.fromNodeId, edge.itemId)
            val start = port(from, edge.itemId, game, nodes, edges, output = true)
            val end = port(to, edge.itemId, game, nodes, edges, output = false)
            val forward = source < dest
            val entry = if (forward) source else source - 1
            val exit = if (forward) dest - 1 else dest
            reserve.add(key, start.y, start.x, slotOf(entry, key).spine)
            reserve.add(key, end.y, slotOf(exit, key).join, end.x)
        }
        return edges.mapNotNull { edge ->
            val from = byId[edge.fromNodeId] ?: return@mapNotNull null
            val to = byId[edge.toNodeId] ?: return@mapNotNull null
            val start = port(from, edge.itemId, game, nodes, edges, output = true)
            val end = port(to, edge.itemId, game, nodes, edges, output = false)
            val key = SourcePort(edge.fromNodeId, edge.itemId)
            val points = orthogonal(from, to, start, end, layerOf, columns, used, reserve, key) { gutter ->
                slotOf(gutter, key)
            }
            EdgeRoute(edge.id, simplify(points))
        }
    }

    /**
     * One solid stroke per piece of geometry. Same item sharing a trunk collapses to one line.
     * Different items on the same line come back together so the canvas can alternate their colors.
     */
    fun strokes(edges: List<GraphEdge>, routes: List<EdgeRoute>): List<EdgeStroke> =
        pieces(edges, routes).map { piece ->
            val itemIds = piece.spans.map { it.itemId }.distinct().sorted()
            val a = if (piece.horizontal) RoutePoint(piece.from, piece.fixed) else RoutePoint(piece.fixed, piece.from)
            val b = if (piece.horizontal) RoutePoint(piece.to, piece.fixed) else RoutePoint(piece.fixed, piece.to)
            EdgeStroke(a, b, itemIds)
        }

    fun differentPortOverlaps(edges: List<GraphEdge>, routes: List<EdgeRoute>): List<String> {
        val problems = mutableListOf<String>()
        for (piece in pieces(edges, routes)) {
            if (piece.to - piece.from <= 1.5f) continue
            val ports = piece.spans.map { it.port }.distinct()
            if (ports.size <= 1) continue
            val where = if (piece.horizontal) "y=${piece.fixed}" else "x=${piece.fixed}"
            problems += "${ports.joinToString(" & ")} overlap at $where ${piece.from}..${piece.to}"
        }
        return problems.distinct()
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
        used: Map<Int, MutableList<Float>>,
        reserve: HorizontalReserve,
        portKey: SourcePort,
        slot: (Int) -> GutterSlot,
    ): List<RoutePoint> {
        val srcLayer = layerOf(from)
        val dstLayer = layerOf(to)
        val points = mutableListOf(start)
        if (srcLayer == dstLayer || srcLayer > dstLayer) {
            val above = min(start.y, end.y) - CLEARANCE - LANE_PITCH
            val lane = reserve.claimOpen(portKey, above, start.x, end.x)
            val right = start.x + CLEARANCE
            val left = end.x - CLEARANCE
            points += RoutePoint(right, start.y)
            points += RoutePoint(right, lane)
            points += RoutePoint(left, lane)
            points += RoutePoint(left, end.y)
            points += end
            return points
        }
        val first = slot(srcLayer)
        points += RoutePoint(first.spine, start.y)
        var x = first.spine
        var y = start.y
        var gutter = srcLayer
        while (gutter < dstLayer) {
            val join = slot(gutter).join
            val reachesDest = gutter + 1 == dstLayer
            if (reachesDest) {
                val crossY = reserve.claimOpen(portKey, (y + end.y) / 2f, x, join)
                if (abs(y - crossY) > 0.01f) points += RoutePoint(x, crossY)
                points += RoutePoint(join, crossY)
                if (abs(crossY - end.y) > 0.01f) points += RoutePoint(join, end.y)
                points += end
                break
            }
            val nextSpine = slot(gutter + 1).spine
            val preferred = lerp(start.y, end.y, (gutter + 1 - srcLayer).toFloat() / (dstLayer - srcLayer))
            val lane = claimColumnLane(portKey, preferred, x, nextSpine, columns[gutter + 1], used.getValue(gutter + 1), reserve)
            if (abs(y - lane) > 0.01f) points += RoutePoint(x, lane)
            points += RoutePoint(nextSpine, lane)
            x = nextSpine
            y = lane
            gutter += 1
        }
        return points
    }

    private fun claimColumnLane(
        portKey: SourcePort,
        preferred: Float,
        x0: Float,
        x1: Float,
        column: List<GraphNode>,
        used: MutableList<Float>,
        reserve: HorizontalReserve,
    ): Float {
        var lane = pickLane(column, preferred, used)
        var guard = 0
        while (reserve.conflicts(portKey, lane, x0, x1) && guard < 16) {
            lane = pickLane(column, preferred, used)
            guard++
        }
        reserve.add(portKey, lane, x0, x1)
        return lane
    }

    private class HorizontalReserve {
        private data class Run(val port: SourcePort, val y: Float, val x0: Float, val x1: Float)

        private val runs = mutableListOf<Run>()

        fun add(port: SourcePort, y: Float, a: Float, b: Float) {
            val x0 = min(a, b)
            val x1 = max(a, b)
            if (x1 - x0 <= 0.5f) return
            runs += Run(port, y, x0, x1)
        }

        fun conflicts(port: SourcePort, y: Float, a: Float, b: Float): Boolean {
            val x0 = min(a, b)
            val x1 = max(a, b)
            return runs.any { other ->
                other.port != port &&
                    abs(other.y - y) < 0.75f &&
                    min(x1, other.x1) - max(x0, other.x0) > 1.5f
            }
        }

        fun claimOpen(port: SourcePort, preferred: Float, a: Float, b: Float): Float {
            var y = preferred
            var step = 0
            while (conflicts(port, y, a, b) && step < 40) {
                step++
                val sign = if (step % 2 == 1) 1f else -1f
                y = preferred + sign * ((step + 1) / 2) * TRACK_PITCH
            }
            add(port, y, a, b)
            return y
        }
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

    private data class SourcePort(val nodeId: String, val itemId: String)

    private data class Span(
        val itemId: String,
        val port: SourcePort,
        val horizontal: Boolean,
        val fixed: Float,
        val from: Float,
        val to: Float,
    )

    private data class Piece(
        val horizontal: Boolean,
        val fixed: Float,
        val from: Float,
        val to: Float,
        val spans: List<Span>,
    )

    private fun spreadColumns(nodes: List<GraphNode>, edges: List<GraphEdge>): List<GraphNode> {
        val xs = nodes.map { it.x }.distinct().sorted()
        if (xs.size < 2) return nodes
        val layerOf = nodes.associate { it.id to xs.indexOf(it.x) }
        val ports = Array(xs.size - 1) { linkedSetOf<SourcePort>() }
        for (edge in edges) {
            val a = layerOf[edge.fromNodeId] ?: continue
            val b = layerOf[edge.toNodeId] ?: continue
            if (a == b) continue
            val lo = min(a, b)
            val hi = max(a, b)
            for (gutter in lo until hi) ports[gutter] += SourcePort(edge.fromNodeId, edge.itemId)
        }
        val gap = FloatArray(xs.size - 1) { index ->
            val count = ports[index].size.coerceAtLeast(1)
            val bundles = if (count <= 1) 0f else 2f * (count - 1) * TRACK_PITCH + TRACK_PITCH
            val need = GUTTER_MARGIN * 2f + bundles
            max(GraphMetrics.GAP_X, need)
        }
        val newX = FloatArray(xs.size)
        newX[0] = xs[0]
        for (index in 1 until xs.size) {
            newX[index] = newX[index - 1] + GraphMetrics.CARD_W + gap[index - 1]
        }
        return nodes.map { node ->
            val layer = layerOf[node.id] ?: return@map node
            node.copy(x = newX[layer])
        }
    }

    private data class GutterSlot(val spine: Float, val join: Float)

    private fun tracksByGutter(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        layerOf: (GraphNode) -> Int,
        xs: List<Float>,
    ): Map<Int, Map<SourcePort, GutterSlot>> {
        val byId = nodes.associateBy { it.id }
        val ports = HashMap<Int, LinkedHashSet<SourcePort>>()
        for (edge in edges) {
            val from = byId[edge.fromNodeId] ?: continue
            val to = byId[edge.toNodeId] ?: continue
            val source = layerOf(from)
            val dest = layerOf(to)
            if (source == dest) continue
            val lo = min(source, dest)
            val hi = max(source, dest)
            for (gutter in lo until hi) {
                ports.getOrPut(gutter) { linkedSetOf() } += SourcePort(edge.fromNodeId, edge.itemId)
            }
        }
        return ports.mapValues { (gutter, keys) ->
            val ordered = keys.sortedWith(compareBy({ byId[it.nodeId]?.y ?: 0f }, { it.nodeId }, { it.itemId }))
            val left = xs[gutter] + GraphMetrics.CARD_W
            val right = xs.getOrElse(gutter + 1) { left + GraphMetrics.GAP_X }
            val count = ordered.size
            if (count <= 1) {
                val mid = (left + right) / 2f
                return@mapValues ordered.associateWith { GutterSlot(mid, mid) }
            }
            val usable = (right - left - 2f * GUTTER_MARGIN).coerceAtLeast(TRACK_PITCH)
            val pitch = min(TRACK_PITCH, usable / (2f * (count - 1) + 1f))
            ordered.mapIndexed { index, key ->
                val spine = left + GUTTER_MARGIN + index * pitch
                val join = right - GUTTER_MARGIN - index * pitch
                key to GutterSlot(spine, join)
            }.toMap()
        }
    }

    private fun gutterCenter(xs: List<Float>, gutter: Int): Float {
        val left = xs[gutter] + GraphMetrics.CARD_W
        val right = xs.getOrElse(gutter + 1) { left + GraphMetrics.GAP_X }
        return (left + right) / 2f
    }

    private fun pieces(edges: List<GraphEdge>, routes: List<EdgeRoute>): List<Piece> {
        val byRoute = routes.associateBy { it.edgeId }
        val spans = mutableListOf<Span>()
        for (edge in edges) {
            val route = byRoute[edge.id] ?: continue
            val port = SourcePort(edge.fromNodeId, edge.itemId)
            val points = route.points
            for (index in 0 until points.lastIndex) {
                val a = points[index]
                val b = points[index + 1]
                val dx = abs(a.x - b.x)
                val dy = abs(a.y - b.y)
                if (dx < 0.01f && dy < 0.01f) continue
                if (dy <= 0.5f && dx > 0.5f) {
                    spans += Span(edge.itemId, port, true, a.y, min(a.x, b.x), max(a.x, b.x))
                } else if (dx <= 0.5f && dy > 0.5f) {
                    spans += Span(edge.itemId, port, false, a.x, min(a.y, b.y), max(a.y, b.y))
                }
            }
        }
        val grouped = spans.groupBy { span ->
            Triple(span.horizontal, (span.fixed * 100f).toInt(), 0)
        }
        val pieces = mutableListOf<Piece>()
        for (group in grouped.values) {
            val cuts = group.flatMap { listOf(it.from, it.to) }.distinct().sorted()
            val sample = group.first()
            for (index in 0 until cuts.lastIndex) {
                val from = cuts[index]
                val to = cuts[index + 1]
                if (to - from <= 0.5f) continue
                val mid = (from + to) / 2f
                val covering = group.filter { it.from < mid && it.to > mid }
                if (covering.isEmpty()) continue
                pieces += Piece(sample.horizontal, sample.fixed, from, to, covering)
            }
        }
        return pieces
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
