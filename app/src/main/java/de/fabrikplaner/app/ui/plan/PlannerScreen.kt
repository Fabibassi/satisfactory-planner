package de.fabrikplaner.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.fabrikplaner.app.PlannerViewModel
import de.fabrikplaner.app.R
import de.fabrikplaner.app.ui.currentLocale
import de.fabrikplaner.app.ui.formatNumber
import de.fabrikplaner.app.ui.localized
import de.fabrikplaner.app.ui.machinesToPlace
import de.fabrikplaner.app.ui.parsePositive
import de.fabrikplaner.core.solver.MachineUsage
import de.fabrikplaner.core.solver.WarningCode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
    viewModel: PlannerViewModel,
    onBack: () -> Unit,
    onOpenEditor: () -> Unit,
) {
    val plan = viewModel.current
    val result = viewModel.result
    val locale = currentLocale()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var recipeForItem by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(plan?.name ?: stringResource(R.string.planner)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenEditor) {
                        Icon(Icons.Default.AccountTree, contentDescription = stringResource(R.string.cd_editor))
                    }
                },
            )
        },
    ) { padding ->
        if (plan == null) {
            Text(stringResource(R.string.plans_empty), modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = plan.name,
                onValueChange = viewModel::rename,
                label = { Text(stringResource(R.string.plan_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(stringResource(R.string.targets_title), style = MaterialTheme.typography.titleMedium)
            if (plan.targets.isEmpty()) {
                Text(stringResource(R.string.generate_first))
            }
            plan.targets.forEach { target ->
                key(target.itemId) {
                    TargetRateRow(
                        label = viewModel.game.itemOrNull(target.itemId)?.localized(locale) ?: target.itemId,
                        rate = target.perMinute,
                        onRate = { viewModel.updateTargetRate(target.itemId, it) },
                        onDelete = { viewModel.removeTarget(target.itemId) },
                    )
                }
            }
            OutlinedButton(onClick = { showPicker = true }) {
                Text(stringResource(R.string.add_target))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenEditor, enabled = plan.targets.isNotEmpty()) {
                    Text(stringResource(R.string.open_editor))
                }
                OutlinedButton(
                    onClick = viewModel::generateGraph,
                    enabled = result != null && plan.targets.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.regenerate_graph))
                }
            }
            if (plan.graphIsStale()) {
                Text(stringResource(R.string.stale_graph), color = MaterialTheme.colorScheme.primary)
            }
            Text(stringResource(R.string.baseline_note), style = MaterialTheme.typography.bodySmall)
            if (result != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.power_label), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.power_mw, formatNumber(result.totalPowerMw, locale, 1)))
                    }
                }
                if (result.warnings.isNotEmpty()) {
                    Text(stringResource(R.string.warnings_title), style = MaterialTheme.typography.titleMedium)
                    result.warnings.forEach { warning ->
                        val itemName = warning.itemId?.let { viewModel.game.itemOrNull(it)?.localized(locale) } ?: ""
                        val amount = formatNumber(warning.amountPerMinute ?: 0.0, locale)
                        val text = when (warning.code) {
                            WarningCode.NO_RECIPE -> stringResource(R.string.warning_no_recipe, itemName, amount)
                            WarningCode.DEFICIT -> stringResource(R.string.warning_deficit, itemName, amount)
                            WarningCode.SINGULAR -> stringResource(R.string.warning_singular)
                            WarningCode.CLAMPED -> stringResource(R.string.warning_clamped, itemName)
                        }
                        Text(text, color = MaterialTheme.colorScheme.error)
                        if (warning.code == WarningCode.NO_RECIPE && warning.itemId != null &&
                            viewModel.game.recipesProducing(warning.itemId!!).isNotEmpty()
                        ) {
                            TextButtonPick(stringResource(R.string.choose_recipe)) { recipeForItem = warning.itemId }
                        }
                    }
                }
                ResourceBlock(
                    title = stringResource(R.string.raw_title),
                    lines = result.rawResources.map { rate ->
                        val name = viewModel.game.item(rate.itemId).localized(locale)
                        "$name  ${stringResource(R.string.per_min, formatNumber(rate.perMinute, locale))}"
                    },
                )
                if (result.extractors.isNotEmpty()) {
                    Text(stringResource(R.string.extractors_title), style = MaterialTheme.typography.titleMedium)
                    result.extractors.forEach { usage ->
                        MachineRow(usage, viewModel, locale.language == "en")
                    }
                }
                Text(stringResource(R.string.machines_title), style = MaterialTheme.typography.titleMedium)
                result.machines.forEach { usage ->
                    val recipe = usage.recipeId?.let { viewModel.game.recipeOrNull(it) }
                    val primary = recipe?.outputs?.firstOrNull()?.itemId
                    val options = primary?.let { viewModel.game.recipesProducing(it) }.orEmpty()
                    MachineRow(usage, viewModel, locale.language == "en")
                    if (primary != null && options.size > 1) {
                        val currentName = recipe.name.localized(locale)
                        AssistChip(
                            onClick = { recipeForItem = primary },
                            label = {
                                Text(
                                    "$currentName · " + if (recipe.alternate) {
                                        stringResource(R.string.alternate_badge)
                                    } else {
                                        stringResource(R.string.standard_badge)
                                    },
                                )
                            },
                        )
                    }
                }
                ResourceBlock(
                    title = stringResource(R.string.byproducts_title),
                    lines = result.byproducts.map { rate ->
                        val name = viewModel.game.item(rate.itemId).localized(locale)
                        "$name  ${stringResource(R.string.per_min, formatNumber(rate.perMinute, locale))}"
                    },
                )
            }
        }
    }
    if (showPicker) {
        ItemPickerDialog(
            game = viewModel.game,
            title = stringResource(R.string.target_dialog_title),
            onDismiss = { showPicker = false },
            onPick = { itemId, rate ->
                viewModel.addTarget(itemId, rate)
                showPicker = false
            },
        )
    }
    val recipeItem = recipeForItem
    if (recipeItem != null) {
        RecipePickerDialog(
            game = viewModel.game,
            itemId = recipeItem,
            onDismiss = { recipeForItem = null },
            onPick = { recipeId ->
                viewModel.selectRecipe(recipeItem, recipeId)
                recipeForItem = null
            },
        )
    }
}

@Composable
private fun TargetRateRow(
    label: String,
    rate: Double,
    onRate: (Double) -> Unit,
    onDelete: () -> Unit,
) {
    val locale = currentLocale()
    var rateText by rememberSaveable(label) { mutableStateOf(formatNumber(rate, locale)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = rateText,
            onValueChange = { text ->
                rateText = text
                parsePositive(text)?.let(onRate)
            },
            label = { Text(stringResource(R.string.rate_label)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete))
        }
    }
}

@Composable
private fun TextButtonPick(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun MachineRow(usage: MachineUsage, viewModel: PlannerViewModel, english: Boolean) {
    val locale = currentLocale()
    val building = viewModel.game.building(usage.buildingId)
    val buildingName = if (english) building.name.en else building.name.de
    val recipeName = usage.recipeId?.let { id ->
        val recipe = viewModel.game.recipe(id)
        if (english) recipe.name.en else recipe.name.de
    }
    val title = recipeName ?: usage.outputs.firstOrNull()?.let { viewModel.game.item(it.itemId).localized(locale) } ?: buildingName
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(
                R.string.machines_line,
                buildingName,
                formatNumber(usage.machineCount, locale),
            ),
        )
        Text(stringResource(R.string.rounded_hint, machinesToPlace(usage.machineCount)))
        Text(stringResource(R.string.power_mw, formatNumber(usage.powerMw, locale, 1)))
    }
}

@Composable
private fun ResourceBlock(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleMedium)
    lines.forEach { Text(it) }
}
