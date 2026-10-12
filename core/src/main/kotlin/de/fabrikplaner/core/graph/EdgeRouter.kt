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
 * Orthogonal routes with at most one vertical jog (two bends).
 * A segment is straight when the ports already share a y.
 * Jog x positions sit in the column gutter, at least [MIN_SEGMENT] from either card,
 * and far enough apart that vertical runs do not share a line.
 * The only collinear overlap allowed is the trunk of one output up to its split,
 * or the last approach into one input.
 */
object EdgeRouter {
    const val MIN_SEGMENT = 36f
    const val LANE_PITCH = 40f
    const val CHANNEL = 8f

    fun coversChannel(node: GraphNode, y: Float): Boolean =
        y > node.y - CHANNEL && y < node.y + GraphMetrics.CARD_H + CHANNEL

    fun bendCount(points: List<RoutePoint>): Int {
        if (points.size < 3) return 0
        var bends = 0
        for (index in 1 until points.lastIndex) {
            val dx1 = points[index].x - points[index - 1].x
            val dy1 = points[index].y - points[index - 1].y
            val dx2 = points[index + 1].x - points[index].x
            val dy2 = points[index + 1].y - points[index].y
            if (abs(dx1 * dy2 - dy1 * dx2) > 0.01f) bends++
        }
        return bends
    }

    /** Widen each column gutter so the jog lanes reserved by [paths] fit. */
    fun reserveLanes(nodes: List<GraphNode>, edges: List<GraphEdge>, game: GameData): List<GraphNode> {
        if (nodes.isEmpty()) return nodes
        val xs = nodes.map { it.x }.distinct().sorted()
        if (xs.size < 2) return nodes
        val planned = plan(nodes, edges, game)
        val offsets = jogOffsets(planned)
        val newX = FloatArray(xs.size)
        newX[0] = xs[0]
        for (index in 1 until xs.size) {
            newX[index] = newX[index - 1] + GraphMetrics.CARD_W + gutterWidth(planned, offsets, index - 1)
        }
        val layerOf = nodes.associate { it.id to xs.indexOf(it.x) }
        return nodes.map { node ->
            val layer = layerOf[node.id] ?: return@map node
            node.copy(x = newX[layer])
        }
    }

    fun paths(nodes: List<GraphNode>, edges: List<GraphEdge>, game: GameData): List<EdgeRoute> {
        if (nodes.isEmpty() || edges.isEmpty()) return emptyList()
        val xs = nodes.map { it.x }.distinct().sorted()
        val planned = plan(nodes, edges, game)
        val offsets = jogOffsets(planned)
        return planned.map { item ->
            EdgeRoute(item.edge.id, simplify(polyline(item, xs, offsets)))
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

    /**
     * Positive-length collinear overlap that is neither the shared trunk of one output
     * nor the shared final approach into one input.
     */
    fun illegalOverlaps(edges: List<GraphEdge>, routes: List<EdgeRoute>): List<String> {
        val problems = mutableListOf<String>()
        for (piece in pieces(edges, routes)) {
            if (piece.to - piece.from <= 1.5f) continue
            if (piece.spans.size <= 1) continue
            if (!piece.horizontal) {
                val where = "x=${piece.fixed} ${piece.from}..${piece.to}"
                problems += "vertical overlap at $where (${piece.spans.joinToString { it.edgeId }})"
                continue
            }
            val prefix = piece.spans.map { it.sourceKey }.distinct().size == 1 &&
                piece.spans.all { abs(it.from - it.sourceAnchor) <= 0.75f }
            val suffix = piece.spans.map { it.targetKey }.distinct().size == 1 &&
                piece.spans.all { abs(it.to - it.targetAnchor) <= 0.75f }
            if (prefix || suffix) continue
            val where = "y=${piece.fixed} ${piece.from}..${piece.to}"
            problems += "overlap at $where (${piece.spans.joinToString { "${it.edgeId}:${it.itemId}" }})"
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

    fun segmentHitsRect(a: RoutePoint, b: RoutePoint, left: Float, top: Float, right: Float, bottom: Float): Boolean {
        val minX = min(a.x, b.x)
        val maxX = max(a.x, b.x)
        val minY = min(a.y, b.y)
        val maxY = max(a.y, b.y)
        if (maxX < left || minX > right || maxY < top || minY > bottom) return false
        if (abs(a.x - b.x) < 0.01f || abs(a.y - b.y) < 0.01f) return true
        return lineHitsRect(a, b, left, top, right, bottom)
    }

    private data class Planned(
        val edge: GraphEdge,
        val sx: Float,
        val sy: Float,
        val tx: Float,
        val ty: Float,
        val sourceLayer: Int,
        val destLayer: Int,
        val gutter: Int?,
    )

    private fun plan(nodes: List<GraphNode>, edges: List<GraphEdge>, game: GameData): List<Planned> {
        val xs = nodes.map { it.x }.distinct().sorted()
        val byId = nodes.associateBy { it.id }
        fun layerOf(node: GraphNode): Int = xs.indexOf(node.x).coerceAtLeast(0)
        val planned = edges.mapNotNull { edge ->
            val from = byId[edge.fromNodeId] ?: return@mapNotNull null
            val to = byId[edge.toNodeId] ?: return@mapNotNull null
            val start = port(from, edge.itemId, game, nodes, edges, output = true)
            val end = port(to, edge.itemId, game, nodes, edges, output = false)
            val source = layerOf(from)
            val dest = layerOf(to)
            Planned(
                edge = edge,
                sx = start.x,
                sy = start.y,
                tx = end.x,
                ty = end.y,
                sourceLayer = source,
                destLayer = dest,
                gutter = chooseGutter(nodes, xs, source, dest, start.y, end.y),
            )
        }
        return separateCrossingJogs(planned, nodes, xs)
    }

    private fun chooseGutter(
        nodes: List<GraphNode>,
        xs: List<Float>,
        source: Int,
        dest: Int,
        sy: Float,
        ty: Float,
    ): Int? {
        if (dest <= source) return null
        val mids = (source + 1 until dest).toList()
        val syClear = channelClear(nodes, xs, mids, sy)
        val tyClear = channelClear(nodes, xs, mids, ty)
        val aligned = abs(sy - ty) <= 0.5f
        if (aligned && syClear) return null
        if (tyClear) return source
        if (syClear && dest > source + 1) return dest - 1
        return source
    }

    /**
     * Two edges that cross inside one gutter cannot both keep a single jog there
     * without laying a horizontal on top of the other. A long edge can take its
     * jog in the other gutter when that channel is open.
     */
    private fun separateCrossingJogs(planned: List<Planned>, nodes: List<GraphNode>, xs: List<Float>): List<Planned> {
        val current = planned.toMutableList()
        repeat(current.size) {
            var moved = false
            for (i in current.indices) {
                for (j in i + 1 until current.size) {
                    val a = current[i]
                    val b = current[j]
                    val gutter = sharedJogGutter(a, b) ?: continue
                    if (!inverted(a, b)) continue
                    val relocated = relocate(a, gutter, nodes, xs) ?: relocate(b, gutter, nodes, xs) ?: continue
                    if (relocated.edge.id == a.edge.id) current[i] = relocated else current[j] = relocated
                    moved = true
                }
            }
            if (!moved) return current
        }
        return current
    }

    private fun sharedJogGutter(a: Planned, b: Planned): Int? {
        val ga = a.gutter ?: return null
        val gb = b.gutter ?: return null
        return if (ga == gb) ga else null
    }

    private fun inverted(a: Planned, b: Planned): Boolean = (a.sy - b.sy) * (a.ty - b.ty) < 0f

    private fun relocate(item: Planned, gutter: Int, nodes: List<GraphNode>, xs: List<Float>): Planned? {
        val span = item.destLayer - item.sourceLayer
        if (span <= 1) return null
        val other = if (gutter == item.sourceLayer) item.destLayer - 1 else item.sourceLayer
        if (other == gutter) return null
        val mids = (item.sourceLayer + 1 until item.destLayer).toList()
        val line = if (other == item.sourceLayer) item.ty else item.sy
        if (!channelClear(nodes, xs, mids, line)) return null
        return item.copy(gutter = other)
    }

    private fun channelClear(nodes: List<GraphNode>, xs: List<Float>, layers: List<Int>, y: Float): Boolean {
        for (layer in layers) {
            val x = xs.getOrNull(layer) ?: continue
            for (node in nodes) {
                if (abs(node.x - x) > 0.1f) continue
                if (coversChannel(node, y)) return false
            }
        }
        return true
    }

    private fun gutterWidth(planned: List<Planned>, offsets: Map<String, Float>, gutter: Int): Float {
        val used = planned.filter { it.gutter == gutter }.mapNotNull { offsets[it.edge.id] }
        if (used.isEmpty()) return GraphMetrics.GAP_X
        val reach = used.max()
        return max(GraphMetrics.GAP_X, reach + 2f * MIN_SEGMENT)
    }

    /**
     * Distance of the jog past the left margin. Leavers of a y sit on or left of enterers of that y,
     * and vertical intervals that overlap stay [LANE_PITCH] apart.
     */
    private fun jogOffsets(planned: List<Planned>): Map<String, Float> {
        val offsets = HashMap<String, Float>()
        val grouped = planned.filter { it.gutter != null }.groupBy { it.gutter }
        for (group in grouped.values) {
            group.forEach { offsets[it.edge.id] = 0f }
            val limit = group.size * LANE_PITCH
            for (pass in 0..(group.size * group.size)) {
                var changed = false
                for (a in group) {
                    for (b in group) {
                        if (a.edge.id == b.edge.id) continue
                        val ao = offsets.getValue(a.edge.id)
                        val bo = offsets.getValue(b.edge.id)
                        val vertical = verticalOverlap(a, b)
                        if (vertical && abs(ao - bo) < LANE_PITCH - 0.1f) {
                            if (bo >= ao) offsets[b.edge.id] = ao + LANE_PITCH else offsets[a.edge.id] = bo + LANE_PITCH
                            changed = true
                        }
                        if (leavesInto(a, b)) {
                            val need = if (vertical) ao + LANE_PITCH else ao
                            if (bo + 0.1f < need) {
                                offsets[b.edge.id] = need
                                changed = true
                            }
                        }
                    }
                }
                for (item in group) {
                    val value = offsets.getValue(item.edge.id)
                    if (value > limit) offsets[item.edge.id] = limit
                }
                if (!changed) break
            }
        }
        return offsets
    }

    private fun verticalOverlap(a: Planned, b: Planned): Boolean {
        val low = max(min(a.sy, a.ty), min(b.sy, b.ty))
        val high = min(max(a.sy, a.ty), max(b.sy, b.ty))
        return high - low > 0.5f
    }

    /** [a] leaves its source y and [b] arrives on that same y, so [a] must not extend past [b]. */
    private fun leavesInto(a: Planned, b: Planned): Boolean =
        abs(a.sy - a.ty) > 0.5f && abs(b.sy - b.ty) > 0.5f && abs(a.sy - b.ty) <= 1f

    private fun polyline(item: Planned, xs: List<Float>, offsets: Map<String, Float>): List<RoutePoint> {
        val start = RoutePoint(item.sx, item.sy)
        val end = RoutePoint(item.tx, item.ty)
        if (item.destLayer <= item.sourceLayer) return backward(start, end)
        val gutter = item.gutter ?: return listOf(start, end)
        val left = xs.getOrNull(gutter)?.plus(GraphMetrics.CARD_W) ?: item.sx
        val jogX = left + MIN_SEGMENT + (offsets[item.edge.id] ?: 0f)
        return listOf(start, RoutePoint(jogX, item.sy), RoutePoint(jogX, item.ty), end)
    }

    private fun backward(start: RoutePoint, end: RoutePoint): List<RoutePoint> {
        val above = min(start.y, end.y) - GraphMetrics.CARD_H - MIN_SEGMENT
        val right = start.x + MIN_SEGMENT
        val left = end.x - MIN_SEGMENT
        return listOf(
            start,
            RoutePoint(right, start.y),
            RoutePoint(right, above),
            RoutePoint(left, above),
            RoutePoint(left, end.y),
            end,
        )
    }

    private data class Span(
        val edgeId: String,
        val itemId: String,
        val sourceKey: String,
        val targetKey: String,
        val horizontal: Boolean,
        val fixed: Float,
        val from: Float,
        val to: Float,
        val sourceAnchor: Float,
        val targetAnchor: Float,
    )

    private data class Piece(
        val horizontal: Boolean,
        val fixed: Float,
        val from: Float,
        val to: Float,
        val spans: List<Span>,
    )

    private fun pieces(edges: List<GraphEdge>, routes: List<EdgeRoute>): List<Piece> {
        val byRoute = routes.associateBy { it.edgeId }
        val spans = mutableListOf<Span>()
        for (edge in edges) {
            val route = byRoute[edge.id] ?: continue
            val points = route.points
            if (points.size < 2) continue
            val sourceKey = edge.fromNodeId + "|" + edge.itemId
            val targetKey = edge.toNodeId + "|" + edge.itemId
            for (index in 0 until points.lastIndex) {
                val a = points[index]
                val b = points[index + 1]
                val dx = abs(a.x - b.x)
                val dy = abs(a.y - b.y)
                if (dx < 0.01f && dy < 0.01f) continue
                if (dy <= 0.5f && dx > 0.5f) {
                    spans += Span(
                        edge.id,
                        edge.itemId,
                        sourceKey,
                        targetKey,
                        true,
                        a.y,
                        min(a.x, b.x),
                        max(a.x, b.x),
                        points.first().x,
                        points.last().x,
                    )
                } else if (dx <= 0.5f && dy > 0.5f) {
                    spans += Span(
                        edge.id,
                        edge.itemId,
                        sourceKey,
                        targetKey,
                        false,
                        a.x,
                        min(a.y, b.y),
                        max(a.y, b.y),
                        points.first().y,
                        points.last().y,
                    )
                }
            }
        }
        val grouped = spans.groupBy { span ->
            span.horizontal to (span.fixed * 100f).toInt()
        }
        val built = mutableListOf<Piece>()
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
                built += Piece(sample.horizontal, sample.fixed, from, to, covering)
            }
        }
        return built
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

    private fun simplify(points: List<RoutePoint>): List<RoutePoint> {
        val compact = mutableListOf<RoutePoint>()
        for (point in points) {
            val last = compact.lastOrNull()
            if (last == null || abs(last.x - point.x) > 0.01f || abs(last.y - point.y) > 0.01f) {
                compact += point
            }
        }
        val turned = mutableListOf<RoutePoint>()
        for (point in compact) {
            if (turned.size >= 2) {
                val a = turned[turned.lastIndex - 1]
                val b = turned[turned.lastIndex]
                val abx = b.x - a.x
                val aby = b.y - a.y
                val bcx = point.x - b.x
                val bcy = point.y - b.y
                if (abs(abx * bcy - aby * bcx) <= 0.01f) {
                    turned.removeAt(turned.lastIndex)
                }
            }
            turned += point
        }
        return turned
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
