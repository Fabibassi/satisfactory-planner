package de.fabrikplaner.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.fabrikplaner.app.R
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
import de.fabrikplaner.core.model.GraphNode
import de.fabrikplaner.core.model.ItemForm
import de.fabrikplaner.core.model.NodeKind
import de.fabrikplaner.core.model.SavedPlan
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

private data class PortRef(val nodeId: String, val itemId: String, val world: Offset)

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
    val nodesState = rememberUpdatedState(plan.nodes)
    val scaleState = rememberUpdatedState(scale)
    val offsetState = rememberUpdatedState(offset)
    val onCameraState = rememberUpdatedState(onCamera)
    val onSelectState = rememberUpdatedState(onSelect)
    val onMoveState = rememberUpdatedState(onMove)
    val onMoveEndState = rememberUpdatedState(onMoveEnd)
    val onConnectState = rememberUpdatedState(onConnect)
    var rubberBand by androidx.compose.runtime.remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
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
                        val output = findOutput(nodes, game, startWorld)
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
                            val target = findInput(nodesState.value, game, end, output.itemId)
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
                Canvas(Modifier.size(4000.dp, 4000.dp)) {
                    fun px(world: Offset) = Offset(world.x * densityScale, world.y * densityScale)
                    plan.edges.forEach { edge ->
                        val from = plan.nodes.find { it.id == edge.fromNodeId } ?: return@forEach
                        val to = plan.nodes.find { it.id == edge.toNodeId } ?: return@forEach
                        val start = px(portWorld(from, game, edge.itemId, output = true))
                        val end = px(portWorld(to, game, edge.itemId, output = false))
                        val mid = (start.x + end.x) / 2f
                        val path = Path().apply {
                            moveTo(start.x, start.y)
                            cubicTo(mid, start.y, mid, end.y, end.x, end.y)
                        }
                        val liquid = game.itemOrNull(edge.itemId)?.form == ItemForm.LIQUID
                        drawPath(path, if (liquid) PipeLiquid else BeltSolid, style = Stroke(width = 5f))
                    }
                    rubberBand?.let { (start, end) ->
                        drawLine(Color.White, px(start), px(end), strokeWidth = 4f)
                    }
                }
                plan.nodes.forEach { node ->
                    NodeCard(node, game, balance, locale, node.id == selectedId)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun NodeCard(
    node: GraphNode,
    game: GameData,
    balance: BalanceReport?,
    locale: Locale,
    selected: Boolean,
) {
    val report = balance?.nodes?.get(node.id)
    val densityScale = LocalDensity.current.density
    val stripe = when (report?.worst) {
        FlowStatus.UNDER -> StatusUnder
        FlowStatus.OVER -> StatusOver
        FlowStatus.BALANCED -> StatusOk
        else -> MaterialTheme.colorScheme.outline
    }
    val recipe = node.recipeId?.let { game.recipeOrNull(it) }
    val title = recipe?.name?.let { if (locale.language == "en") it.en else it.de }
        ?: node.itemId?.let { game.itemOrNull(it) }?.let { if (locale.language == "en") it.name.en else it.name.de }
        ?: node.kind.name
    val subtitle = when (node.kind) {
        NodeKind.RECIPE -> {
            val building = node.buildingId?.let { game.buildingOrNull(it) }
            val buildingName = building?.let { if (locale.language == "en") it.name.en else it.name.de }.orEmpty()
            "$buildingName × ${String.format(locale, "%.2f", node.machineCount)}"
        }
        NodeKind.SOURCE, NodeKind.SINK -> "${String.format(locale, "%.1f", node.ratePerMinute)} / min"
        else -> node.itemId.orEmpty()
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .offset { IntOffset((node.x * densityScale).roundToInt(), (node.y * densityScale).roundToInt()) }
            .size(GraphMetrics.CARD_W.dp, GraphMetrics.CARD_H.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else stripe, shape)
            .padding(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val line = report?.inputs?.firstOrNull() ?: report?.outputs?.firstOrNull()
        if (line != null) {
            val name = game.itemOrNull(line.itemId)?.let { if (locale.language == "en") it.name.en else it.name.de } ?: line.itemId
            Text(
                "$name ${String.format(locale, "%.1f", line.actualPerMinute)}/${String.format(locale, "%.1f", line.expectedPerMinute)}",
                color = stripe,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.fillMaxSize()) {
            val inputs = inputItems(node, game)
            val outputs = outputItems(node, game)
            inputs.forEachIndexed { index, _ ->
                PortDot(
                    Modifier.align(Alignment.CenterStart).offset(x = (-16).dp, y = portOffset(index, inputs.size)),
                    Color(0xFF90A4AE),
                )
            }
            outputs.forEachIndexed { index, itemId ->
                val liquid = game.itemOrNull(itemId)?.form == ItemForm.LIQUID
                PortDot(
                    Modifier.align(Alignment.CenterEnd).offset(x = 16.dp, y = portOffset(index, outputs.size)),
                    if (liquid) PipeLiquid else BeltSolid,
                )
            }
        }
    }
}

@Composable
private fun PortDot(modifier: Modifier, color: Color) {
    Box(modifier.size(14.dp).background(color, CircleShape))
}

private fun portOffset(index: Int, count: Int): androidx.compose.ui.unit.Dp {
    if (count <= 1) return 8.dp
    val span = 36f
    val start = -span / 2f
    return (start + span * index / (count - 1)).dp
}

private fun inputItems(node: GraphNode, game: GameData): List<String> = when (node.kind) {
    NodeKind.RECIPE -> node.recipeId?.let { game.recipeOrNull(it) }?.inputs?.map { it.itemId }.orEmpty()
    NodeKind.SINK, NodeKind.SPLITTER, NodeKind.MERGER -> listOfNotNull(node.itemId)
    NodeKind.SOURCE -> emptyList()
}

private fun outputItems(node: GraphNode, game: GameData): List<String> = when (node.kind) {
    NodeKind.RECIPE -> node.recipeId?.let { game.recipeOrNull(it) }?.outputs?.map { it.itemId }.orEmpty()
    NodeKind.SOURCE, NodeKind.SPLITTER, NodeKind.MERGER -> listOfNotNull(node.itemId)
    NodeKind.SINK -> emptyList()
}

private fun portWorld(node: GraphNode, game: GameData, itemId: String, output: Boolean): Offset {
    val items = if (output) outputItems(node, game) else inputItems(node, game)
    val index = items.indexOf(itemId).let { if (it < 0) 0 else it }
    val point = if (output) GraphMetrics.outputPort(node, index, items.size.coerceAtLeast(1))
    else GraphMetrics.inputPort(node, index, items.size.coerceAtLeast(1))
    return Offset(point.first, point.second)
}

private fun findNode(nodes: List<GraphNode>, world: Offset): GraphNode? =
    nodes.lastOrNull { node ->
        world.x in node.x..(node.x + GraphMetrics.CARD_W) && world.y in node.y..(node.y + GraphMetrics.CARD_H)
    }

private fun findOutput(nodes: List<GraphNode>, game: GameData, world: Offset): PortRef? {
    var best: PortRef? = null
    var bestDist = 28f
    for (node in nodes) {
        outputItems(node, game).forEachIndexed { index, itemId ->
            val point = GraphMetrics.outputPort(node, index, outputItems(node, game).size)
            val dist = hypot(world.x - point.first, world.y - point.second)
            if (dist < bestDist) {
                bestDist = dist
                best = PortRef(node.id, itemId, Offset(point.first, point.second))
            }
        }
    }
    return best
}

private fun findInput(nodes: List<GraphNode>, game: GameData, world: Offset, itemId: String): String? {
    var best: String? = null
    var bestDist = 36f
    for (node in nodes) {
        val items = inputItems(node, game)
        items.forEachIndexed { index, candidate ->
            if (candidate != itemId) return@forEachIndexed
            val point = GraphMetrics.inputPort(node, index, items.size)
            val dist = hypot(world.x - point.first, world.y - point.second)
            if (dist < bestDist) {
                bestDist = dist
                best = node.id
            }
        }
    }
    if (best != null) return best
    val node = findNode(nodes, world) ?: return null
    return if (inputItems(node, game).contains(itemId)) node.id else null
}
