package de.fabrikplaner.app.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.fabrikplaner.app.PlannerViewModel
import de.fabrikplaner.app.R
import de.fabrikplaner.app.ui.currentLocale
import de.fabrikplaner.app.ui.formatNumber
import de.fabrikplaner.app.ui.localized
import de.fabrikplaner.app.ui.parsePositive
import de.fabrikplaner.app.ui.plan.ItemPickerDialog
import de.fabrikplaner.app.ui.plan.RecipePickerDialog
import de.fabrikplaner.core.graph.Camera
import de.fabrikplaner.core.graph.CameraMath
import de.fabrikplaner.core.graph.FlowStatus
import de.fabrikplaner.core.graph.GraphMetrics
import de.fabrikplaner.core.model.GraphNode
import de.fabrikplaner.core.model.NodeKind
import java.util.Locale
import kotlin.math.roundToInt

private enum class Picker { NONE, MACHINE_ITEM, SOURCE, SINK, SPLITTER, MERGER, RECIPE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: PlannerViewModel,
    onBack: () -> Unit,
    onOpenPlanner: () -> Unit,
) {
    val plan = viewModel.current
    val locale = currentLocale()
    var scale by rememberSaveable { mutableFloatStateOf(0.85f) }
    var offsetX by rememberSaveable { mutableFloatStateOf(32f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(32f) }
    var viewW by rememberSaveable { mutableIntStateOf(0) }
    var viewH by rememberSaveable { mutableIntStateOf(0) }
    var density by rememberSaveable { mutableFloatStateOf(1f) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(Picker.NONE) }
    var recipeItem by remember { mutableStateOf<String?>(null) }
    if (plan == null) {
        Text(stringResource(R.string.plans_empty), modifier = Modifier.padding(16.dp))
        return
    }
    val selected = plan.nodes.find { it.id == selectedId }
    fun dropSpot(): Offset {
        if (viewW == 0 || viewH == 0) return Offset(80f, 80f)
        val (x, y) = CameraMath.screenToWorld(viewW / 2f, viewH / 2f, Camera(scale, offsetX, offsetY), density)
        return Offset(x - GraphMetrics.CARD_W / 2f, y - GraphMetrics.CARD_H / 2f)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(plan.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenPlanner) {
                        Icon(Icons.Default.Calculate, contentDescription = stringResource(R.string.cd_planner))
                    }
                    IconButton(onClick = { picker = Picker.MACHINE_ITEM }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (plan.graphIsStale()) {
                Text(
                    stringResource(R.string.stale_graph),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            FactoryCanvas(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                plan = plan,
                game = viewModel.game,
                balance = viewModel.balance,
                locale = locale,
                scale = scale,
                offset = Offset(offsetX, offsetY),
                selectedId = selectedId,
                onCamera = { nextScale, nextOffset ->
                    scale = nextScale
                    offsetX = nextOffset.x
                    offsetY = nextOffset.y
                },
                onViewport = { size, densityValue ->
                    viewW = size.width
                    viewH = size.height
                    density = densityValue
                },
                onSelect = { selectedId = it },
                onMove = viewModel::moveNode,
                onMoveEnd = viewModel::finishMove,
                onConnect = viewModel::connect,
                onZoom = { factor ->
                    if (viewW == 0) return@FactoryCanvas
                    val (wx, wy) = CameraMath.screenToWorld(viewW / 2f, viewH / 2f, Camera(scale, offsetX, offsetY), density)
                    val next = (scale * factor).coerceIn(0.25f, 2.8f)
                    scale = next
                    offsetX = viewW / 2f - wx * density * next
                    offsetY = viewH / 2f - wy * density * next
                },
                onFit = {
                    if (plan.nodes.isEmpty() || viewW == 0) return@FactoryCanvas
                    val camera = CameraMath.fit(
                        minX = plan.nodes.minOf { it.x },
                        minY = plan.nodes.minOf { it.y },
                        maxX = plan.nodes.maxOf { it.x + GraphMetrics.CARD_W },
                        maxY = plan.nodes.maxOf { it.y + GraphMetrics.CARD_H },
                        viewW = viewW.toFloat(),
                        viewH = viewH.toFloat(),
                        density = density,
                    )
                    scale = camera.scale
                    offsetX = camera.offsetX
                    offsetY = camera.offsetY
                },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.connect_hint), style = MaterialTheme.typography.bodySmall)
                if (plan.nodes.isEmpty()) {
                    Text(stringResource(R.string.empty_graph))
                    OutlinedButton(onClick = viewModel::generateGraph, enabled = plan.targets.isNotEmpty()) {
                        Text(stringResource(R.string.regenerate_graph))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(false, { picker = Picker.MACHINE_ITEM }, { Text(stringResource(R.string.add_machine)) })
                    FilterChip(false, { picker = Picker.SOURCE }, { Text(stringResource(R.string.add_source)) })
                    FilterChip(false, { picker = Picker.SINK }, { Text(stringResource(R.string.add_sink)) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(false, { picker = Picker.SPLITTER }, { Text(stringResource(R.string.add_splitter)) })
                    FilterChip(false, { picker = Picker.MERGER }, { Text(stringResource(R.string.add_merger)) })
                }
                if (selected == null) {
                    Text(stringResource(R.string.no_selection_hint))
                } else {
                    key(selected.id, selected.kind) {
                        NodeEditor(
                            node = selected,
                            viewModel = viewModel,
                            locale = locale,
                            onDeleted = { selectedId = null },
                        )
                    }
                }
            }
        }
    }
    when (picker) {
        Picker.MACHINE_ITEM -> ItemPickerDialog(
            game = viewModel.game,
            title = stringResource(R.string.add_machine),
            showRate = false,
            onDismiss = { picker = Picker.NONE },
            onPick = { itemId, _ ->
                recipeItem = itemId
                picker = Picker.RECIPE
            },
        )
        Picker.SOURCE -> ItemPickerDialog(
            game = viewModel.game,
            title = stringResource(R.string.add_source),
            showRate = false,
            onDismiss = { picker = Picker.NONE },
            onPick = { itemId, _ ->
                val spot = dropSpot()
                viewModel.addSource(itemId, spot.x, spot.y)
                picker = Picker.NONE
            },
        )
        Picker.SINK -> ItemPickerDialog(
            game = viewModel.game,
            title = stringResource(R.string.add_sink),
            onDismiss = { picker = Picker.NONE },
            onPick = { itemId, rate ->
                val spot = dropSpot()
                viewModel.addSink(itemId, rate, spot.x, spot.y)
                picker = Picker.NONE
            },
        )
        Picker.SPLITTER, Picker.MERGER -> ItemPickerDialog(
            game = viewModel.game,
            title = stringResource(R.string.logistics),
            showRate = false,
            onDismiss = { picker = Picker.NONE },
            onPick = { itemId, _ ->
                val spot = dropSpot()
                val kind = if (picker == Picker.SPLITTER) NodeKind.SPLITTER else NodeKind.MERGER
                viewModel.addPassthrough(kind, itemId, spot.x, spot.y)
                picker = Picker.NONE
            },
        )
        Picker.RECIPE, Picker.NONE -> Unit
    }
    val chosenItem = recipeItem
    if (picker == Picker.RECIPE && chosenItem != null) {
        RecipePickerDialog(
            game = viewModel.game,
            itemId = chosenItem,
            onDismiss = {
                picker = Picker.NONE
                recipeItem = null
            },
            onPick = { recipeId ->
                val spot = dropSpot()
                viewModel.addRecipeNode(recipeId, spot.x, spot.y)
                picker = Picker.NONE
                recipeItem = null
            },
        )
    }
}

@Composable
private fun NodeEditor(
    node: GraphNode,
    viewModel: PlannerViewModel,
    locale: Locale,
    onDeleted: () -> Unit,
) {
    val plan = viewModel.current ?: return
    Text(nodeTitle(node, viewModel, locale), style = MaterialTheme.typography.titleSmall)
    val balance = viewModel.balance?.nodes?.get(node.id)
    if (balance != null) {
        Text(stringResource(R.string.run_ratio, formatNumber(balance.runRatio * 100.0, locale, 0)))
        (balance.inputs + balance.outputs).forEach { port ->
            val name = viewModel.game.itemOrNull(port.itemId)?.localized(locale) ?: port.itemId
            Text(
                stringResource(
                    R.string.input_ratio,
                    name,
                    formatNumber(port.actualPerMinute, locale),
                    formatNumber(port.expectedPerMinute, locale),
                ) + " · " + statusLabel(port.status),
            )
        }
    }
    when (node.kind) {
        NodeKind.RECIPE -> {
            var countText by rememberSaveable(node.id) { mutableStateOf(formatNumber(node.machineCount, locale)) }
            OutlinedTextField(
                value = countText,
                onValueChange = { text ->
                    countText = text
                    parsePositive(text)?.let { value -> viewModel.updateNode(node.id) { it.copy(machineCount = value) } }
                },
                label = { Text(stringResource(R.string.machine_count)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.clock_percent) + " ${node.clockPercent.roundToInt()}")
            Text(stringResource(R.string.clock_note), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = node.clockPercent.toFloat().coerceIn(1f, 250f),
                onValueChange = { value -> viewModel.updateNode(node.id) { it.copy(clockPercent = value.toDouble()) } },
                valueRange = 1f..250f,
            )
        }
        NodeKind.SOURCE, NodeKind.SINK -> {
            var rateText by rememberSaveable(node.id) { mutableStateOf(formatNumber(node.ratePerMinute, locale)) }
            OutlinedTextField(
                value = rateText,
                onValueChange = { text ->
                    rateText = text
                    parsePositive(text)?.let { value ->
                        viewModel.updateNode(node.id) { current ->
                            val perMachine = current.itemId?.let { viewModel.game.itemOrNull(it)?.extractPerMinute }
                            current.copy(
                                ratePerMinute = value,
                                machineCount = perMachine?.let { value / it } ?: current.machineCount,
                            )
                        }
                    }
                },
                label = {
                    Text(stringResource(if (node.kind == NodeKind.SOURCE) R.string.source_rate else R.string.sink_rate))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        else -> Unit
    }
    plan.edges.filter { it.fromNodeId == node.id || it.toNodeId == node.id }.forEach { edge ->
        val itemName = viewModel.game.itemOrNull(edge.itemId)?.localized(locale) ?: edge.itemId
        TextButton(onClick = { viewModel.deleteEdge(edge.id) }) {
            Text("$itemName · ${stringResource(R.string.delete_edge)}")
        }
    }
    OutlinedButton(onClick = {
        viewModel.deleteNode(node.id)
        onDeleted()
    }) { Text(stringResource(R.string.delete_node)) }
}

@Composable
private fun nodeTitle(node: GraphNode, viewModel: PlannerViewModel, locale: Locale): String {
    val recipe = node.recipeId?.let { viewModel.game.recipeOrNull(it) }
    if (recipe != null) return recipe.name.localized(locale)
    val item = node.itemId?.let { viewModel.game.itemOrNull(it)?.localized(locale) }.orEmpty()
    val kind = when (node.kind) {
        NodeKind.SOURCE -> stringResource(R.string.add_source)
        NodeKind.SINK -> stringResource(R.string.add_sink)
        NodeKind.SPLITTER -> stringResource(R.string.add_splitter)
        NodeKind.MERGER -> stringResource(R.string.add_merger)
        NodeKind.RECIPE -> stringResource(R.string.add_machine)
    }
    return "$kind $item".trim()
}

@Composable
private fun statusLabel(status: FlowStatus): String = when (status) {
    FlowStatus.BALANCED -> stringResource(R.string.status_balanced)
    FlowStatus.UNDER -> stringResource(R.string.status_under)
    FlowStatus.OVER -> stringResource(R.string.status_over)
    FlowStatus.UNCONNECTED -> stringResource(R.string.status_unconnected)
    FlowStatus.UNUSED -> stringResource(R.string.status_unused)
}
