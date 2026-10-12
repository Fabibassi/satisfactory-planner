package de.fabrikplaner.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.fabrikplaner.app.R
import de.fabrikplaner.app.ui.formatNumber
import de.fabrikplaner.app.ui.icon.BuildingGlyph
import de.fabrikplaner.app.ui.icon.ItemGlyph
import de.fabrikplaner.app.ui.icon.drawMonogram
import de.fabrikplaner.app.ui.icon.glyphColor
import de.fabrikplaner.app.ui.icon.monogram
import de.fabrikplaner.app.ui.localized
import de.fabrikplaner.app.ui.theme.BeltSolid
import de.fabrikplaner.app.ui.theme.PipeLiquid
import de.fabrikplaner.app.ui.theme.StatusOk
import de.fabrikplaner.app.ui.theme.StatusOver
import de.fabrikplaner.app.ui.theme.StatusUnder
import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.graph.BalanceReport
import de.fabrikplaner.core.graph.Camera
import de.fabrikplaner.core.graph.CameraMath
import de.fabrikplaner.core.graph.FlowStatus
import de.fabrikplaner.core.graph.GraphMetrics
import de.fabrikplaner.core.graph.GraphPorts
import de.fabrikplaner.core.model.GraphNode
import de.fabrikplaner.core.model.ItemForm
import de.fabrikplaner.core.model.NodeKind
import de.fabrikplaner.core.model.SavedPlan
import java.util.Locale
import kotlin.math.hypot

private data class PortRef(val nodeId: String, val itemId: String, val world: Offset)

private data class EdgeDraw(
    val start: Offset,
    val end: Offset,
    val liquid: Boolean,
    val letters: String,
    val colorKey: String,
    val label: String,
    val circular: Boolean,
)

@Composable
fun FactoryCanvas(
    plan: SavedPlan,
    game: GameData,
    balance: BalanceReport?,
    locale: Locale,
    scale: Float,
    offset: Offset,
    selectedId: String?,
    onCamera: (Float, Offset) -> Unit,
    onViewport: (IntSize, Float) -> Unit,
    onSelect: (String) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onMoveEnd: () -> Unit,
    onConnect: (String, String, String) -> Boolean,
    onZoom: (Float) -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val densityScale = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer()
    val nodesState = rememberUpdatedState(plan.nodes)
    val edgesState = rememberUpdatedState(plan.edges)
    val scaleState = rememberUpdatedState(scale)
    val offsetState = rememberUpdatedState(offset)
    val onCameraState = rememberUpdatedState(onCamera)
    val onSelectState = rememberUpdatedState(onSelect)
    val onMoveState = rememberUpdatedState(onMove)
    val onMoveEndState = rememberUpdatedState(onMoveEnd)
    val onConnectState = rememberUpdatedState(onConnect)
    var rubberBand by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val edgeDraws = plan.edges.mapNotNull { edge ->
        val from = plan.nodes.find { it.id == edge.fromNodeId } ?: return@mapNotNull null
        val to = plan.nodes.find { it.id == edge.toNodeId } ?: return@mapNotNull null
        val start = portWorld(from, game, plan, edge.itemId, output = true)
        val end = portWorld(to, game, plan, edge.itemId, output = false)
        val item = game.itemOrNull(edge.itemId)
        val name = item?.localized(locale) ?: edge.itemId
        val rate = balance?.edgeRates?.get(edge.id)
        val label = rate?.let { "${formatNumber(it, locale)} / min" } ?: ""
        EdgeDraw(
            start = start,
            end = end,
            liquid = item?.form == ItemForm.LIQUID || item?.form == ItemForm.GAS,
            letters = monogram(name),
            colorKey = edge.itemId,
            label = label,
            circular = item?.form == ItemForm.LIQUID || item?.form == ItemForm.GAS,
        )
    }
    Box(modifier.clipToBounds().background(Color(0xFF101316))) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { onViewport(it, densityScale) }
                .pointerInput(densityScale) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var scaleLocal = scaleState.value
                        var offsetLocal = offsetState.value
                        fun worldOf(screen: Offset): Offset {
                            val (x, y) = CameraMath.screenToWorld(
                                screen.x,
                                screen.y,
                                Camera(scaleLocal, offsetLocal.x, offsetLocal.y),
                                densityScale,
                            )
                            return Offset(x, y)
                        }
                        val startWorld = worldOf(down.position)
                        val nodes = nodesState.value
                        val edges = edgesState.value
                        val output = findOutput(nodes, edges, game, startWorld)
                        val node = findNode(nodes, startWorld)
                        val mode = when {
                            output != null -> "connect"
                            node != null -> "drag"
                            else -> "pan"
                        }
                        if (node != null) onSelectState.value(node.id)
                        var last = down.position
                        val previous = HashMap<Long, Offset>()
                        previous[down.id.value] = down.position
                        if (output != null) rubberBand = output.world to startWorld
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val a = pressed[0]
                                val b = pressed[1]
                                val prevA = previous[a.id.value] ?: a.position
                                val prevB = previous[b.id.value] ?: b.position
                                val prevDist = hypot(prevA.x - prevB.x, prevA.y - prevB.y).coerceAtLeast(8f)
                                val newDist = hypot(a.position.x - b.position.x, a.position.y - b.position.y).coerceAtLeast(8f)
                                val centroid = Offset((a.position.x + b.position.x) / 2f, (a.position.y + b.position.y) / 2f)
                                val world = worldOf(centroid)
                                scaleLocal = (scaleLocal * (newDist / prevDist)).coerceIn(0.25f, 2.8f)
                                offsetLocal = centroid - Offset(world.x * densityScale * scaleLocal, world.y * densityScale * scaleLocal)
                                onCameraState.value(scaleLocal, offsetLocal)
                                previous[a.id.value] = a.position
                                previous[b.id.value] = b.position
                                last = centroid
                            } else {
                                val change = pressed[0]
                                val delta = change.position - last
                                last = change.position
                                previous[change.id.value] = change.position
                                when (mode) {
                                    "pan" -> {
                                        offsetLocal += delta
                                        onCameraState.value(scaleLocal, offsetLocal)
                                    }
                                    "drag" -> if (node != null) {
                                        val latest = nodesState.value.firstOrNull { it.id == node.id } ?: node
                                        onMoveState.value(
                                            node.id,
                                            latest.x + delta.x / scaleLocal / densityScale,
                                            latest.y + delta.y / scaleLocal / densityScale,
                                        )
                                    }
                                    "connect" -> if (output != null) {
                                        val current = worldOf(change.position)
                                        rubberBand = output.world to current
                                    }
                                }
                            }
                        }
                        if (mode == "connect" && output != null) {
                            val end = rubberBand?.second ?: startWorld
                            val target = findInput(nodesState.value, edgesState.value, game, end, output.itemId)
                            if (target != null) onConnectState.value(output.nodeId, target, output.itemId)
                        }
                        rubberBand = null
                        if (mode == "drag") onMoveEndState.value()
                    }
                },
        ) {
            Box(
                Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                },
            ) {
                Canvas(Modifier.size(8000.dp, 8000.dp)) {
                    fun px(world: Offset) = Offset(world.x * densityScale, world.y * densityScale)
                    edgeDraws.forEach { edge ->
                        val start = px(edge.start)
                        val end = px(edge.end)
                        val mid = (start.x + end.x) / 2f
                        val path = Path().apply {
                            moveTo(start.x, start.y)
                            cubicTo(mid, start.y, mid, end.y, end.x, end.y)
                        }
                        drawPath(path, if (edge.liquid) PipeLiquid else BeltSolid, style = Stroke(width = 5f))
                    }
                    rubberBand?.let { (start, end) ->
                        drawLine(Color.White, px(start), px(end), strokeWidth = 4f)
                    }
                }
                plan.nodes.forEach { node ->
                    val inputs = GraphPorts.orderedInputs(node, game, plan.nodes, plan.edges)
                    val outputs = GraphPorts.orderedOutputs(node, game, plan.nodes, plan.edges)
                    NodeCard(
                        node = node,
                        game = game,
                        balance = balance,
                        locale = locale,
                        selected = node.id == selectedId,
                        densityScale = densityScale,
                        inputs = inputs,
                        outputs = outputs,
                    )
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val camera = Camera(scale, offset.x, offset.y)
                edgeDraws.forEach { edge ->
                    val mid = curveMidpoint(edge.start, edge.end)
                    val (sx, sy) = CameraMath.worldToScreen(mid.x, mid.y, camera, densityScale)
                    drawEdgeLabel(Offset(sx, sy), edge, textMeasurer)
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(onClick = { onZoom(1.2f) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.zoom_in))
            }
            FilledTonalIconButton(onClick = { onZoom(1f / 1.2f) }) {
                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.zoom_out))
            }
            FilledTonalIconButton(onClick = onFit) {
                Text(stringResource(R.string.fit), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun curveMidpoint(start: Offset, end: Offset): Offset {
    val midX = (start.x + end.x) / 2f
    val c1 = Offset(midX, start.y)
    val c2 = Offset(midX, end.y)
    val t = 0.5f
    val u = 1f - t
    return start * (u * u * u) + c1 * (3f * u * u * t) + c2 * (3f * u * t * t) + end * (t * t * t)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEdgeLabel(
    center: Offset,
    edge: EdgeDraw,
    textMeasurer: TextMeasurer,
) {
    val glyph = 22.dp.toPx()
    val gap = 4.dp.toPx()
    val pad = 4.dp.toPx()
    val textLayout = textMeasurer.measure(
        text = edge.label,
        style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium),
    )
    val width = pad + glyph + if (edge.label.isEmpty()) 0f else gap + textLayout.size.width + pad
    val height = maxOf(glyph, textLayout.size.height.toFloat()) + pad * 2
    val topLeft = Offset(center.x - width / 2f, center.y - height / 2f)
    drawRoundRect(
        color = Color(0xF0101316),
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2f, height / 2f),
    )
    drawMonogram(
        topLeft = Offset(topLeft.x + pad, topLeft.y + (height - glyph) / 2f),
        sizePx = glyph,
        letters = edge.letters,
        color = glyphColor(edge.colorKey),
        textMeasurer = textMeasurer,
        circular = edge.circular,
    )
    if (edge.label.isNotEmpty()) {
        drawText(
            textLayout,
            topLeft = Offset(
                topLeft.x + pad + glyph + gap,
                topLeft.y + (height - textLayout.size.height) / 2f,
            ),
        )
    }
}

@Composable
private fun NodeCard(
    node: GraphNode,
    game: GameData,
    balance: BalanceReport?,
    locale: Locale,
    selected: Boolean,
    densityScale: Float,
    inputs: List<String>,
    outputs: List<String>,
) {
    val report = balance?.nodes?.get(node.id)
    val stripe = when (report?.worst) {
        FlowStatus.UNDER -> StatusUnder
        FlowStatus.OVER -> StatusOver
        FlowStatus.BALANCED -> StatusOk
        else -> MaterialTheme.colorScheme.outline
    }
    val recipe = node.recipeId?.let { game.recipeOrNull(it) }
    val item = node.itemId?.let { game.itemOrNull(it) }
        ?: recipe?.outputs?.firstOrNull()?.itemId?.let { game.itemOrNull(it) }
    val title = recipe?.name?.localized(locale)
        ?: item?.localized(locale)
        ?: node.kind.name
    val building = node.buildingId?.let { game.buildingOrNull(it) }
    val buildingName = building?.name?.localized(locale).orEmpty()
    val subtitle = when (node.kind) {
        NodeKind.RECIPE -> "$buildingName × ${String.format(locale, "%.2f", node.machineCount)}"
        NodeKind.SOURCE, NodeKind.SINK -> "${String.format(locale, "%.1f", node.ratePerMinute)} / min"
        else -> item?.localized(locale) ?: node.itemId.orEmpty()
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .graphicsLayer {
                translationX = node.x * densityScale
                translationY = node.y * densityScale
            }
            .size(GraphMetrics.CARD_W.dp, GraphMetrics.CARD_H.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else stripe, shape)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ItemGlyph(
                    name = item?.localized(locale) ?: title,
                    key = item?.id ?: node.id,
                    form = item?.form,
                    size = 32.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (building != null) {
                            BuildingGlyph(buildingName, building.id, size = 16.dp)
                        }
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        inputs.forEachIndexed { index, _ ->
            PortDot(
                y = GraphMetrics.portY(index, inputs.size),
                onRight = false,
                color = Color(0xFF90A4AE),
            )
        }
        outputs.forEachIndexed { index, itemId ->
            val liquid = game.itemOrNull(itemId)?.form == ItemForm.LIQUID || game.itemOrNull(itemId)?.form == ItemForm.GAS
            PortDot(
                y = GraphMetrics.portY(index, outputs.size),
                onRight = true,
                color = if (liquid) PipeLiquid else BeltSolid,
            )
        }
    }
}

@Composable
private fun PortDot(y: Float, onRight: Boolean, color: Color) {
    val radius = GraphMetrics.PORT_RADIUS
    val x = if (onRight) GraphMetrics.CARD_W - radius else -radius
    Box(
        Modifier
            .graphicsLayer {
                translationX = x * density
                translationY = (y - radius) * density
            }
            .size((radius * 2f).dp)
            .background(color, CircleShape),
    )
}

private fun portWorld(node: GraphNode, game: GameData, plan: SavedPlan, itemId: String, output: Boolean): Offset {
    val point = if (output) {
        GraphPorts.outputCenter(node, itemId, game, plan.nodes, plan.edges)
    } else {
        GraphPorts.inputCenter(node, itemId, game, plan.nodes, plan.edges)
    }
    return Offset(point.first, point.second)
}

private fun findNode(nodes: List<GraphNode>, world: Offset): GraphNode? =
    nodes.lastOrNull { node ->
        world.x in node.x..(node.x + GraphMetrics.CARD_W) && world.y in node.y..(node.y + GraphMetrics.CARD_H)
    }

private fun findOutput(nodes: List<GraphNode>, edges: List<de.fabrikplaner.core.model.GraphEdge>, game: GameData, world: Offset): PortRef? {
    var best: PortRef? = null
    var bestDist = 28f
    for (node in nodes) {
        GraphPorts.orderedOutputs(node, game, nodes, edges).forEach { itemId ->
            val point = GraphPorts.outputCenter(node, itemId, game, nodes, edges)
            val dist = hypot(world.x - point.first, world.y - point.second)
            if (dist < bestDist) {
                bestDist = dist
                best = PortRef(node.id, itemId, Offset(point.first, point.second))
            }
        }
    }
    return best
}

private fun findInput(
    nodes: List<GraphNode>,
    edges: List<de.fabrikplaner.core.model.GraphEdge>,
    game: GameData,
    world: Offset,
    itemId: String,
): String? {
    var best: String? = null
    var bestDist = 36f
    for (node in nodes) {
        val items = GraphPorts.orderedInputs(node, game, nodes, edges)
        if (itemId !in items) continue
        val point = GraphPorts.inputCenter(node, itemId, game, nodes, edges)
        val dist = hypot(world.x - point.first, world.y - point.second)
        if (dist < bestDist) {
            bestDist = dist
            best = node.id
        }
    }
    if (best != null) return best
    val node = findNode(nodes, world) ?: return null
    return if (GraphPorts.inputs(node, game).contains(itemId)) node.id else null
}
