package de.fabrikplaner.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.fabrikplaner.app.R
import de.fabrikplaner.app.ui.currentLocale
import de.fabrikplaner.app.ui.formatNumber
import de.fabrikplaner.app.ui.localized
import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.solver.RateDelta
import de.fabrikplaner.core.solver.RecipeComparison
import de.fabrikplaner.core.solver.RecipeSheet
import de.fabrikplaner.core.solver.SolverResult
import de.fabrikplaner.core.solver.compareRecipes
import java.util.Locale
import kotlin.math.abs

data class RecipeCompareRequest(
    val itemId: String,
    val recipeId: String,
)

@Composable
fun RecipeCompareButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = stringResource(R.string.recipe_compare_cd),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
fun RecipeCompareHost(
    game: GameData,
    selection: Map<String, String>,
    solve: (Map<String, String>) -> SolverResult,
    request: RecipeCompareRequest?,
    onDismiss: () -> Unit,
) {
    val current = request ?: return
    val comparison = remember(selection, current) {
        compareRecipes(game, solve, selection, current.itemId, current.recipeId)
    } ?: return
    RecipeCompareDialog(game, comparison, onDismiss)
}

@Composable
fun RecipeCompareDialog(
    game: GameData,
    comparison: RecipeComparison,
    onDismiss: () -> Unit,
) {
    val locale = currentLocale()
    val itemName = game.itemOrNull(comparison.itemId)?.localized(locale) ?: comparison.itemId
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.94f),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.recipe_compare_title), style = MaterialTheme.typography.titleLarge)
                Text(itemName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.recipe_per_machine),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecipeSheetColumn(
                            game,
                            comparison.baseline,
                            stringResource(R.string.recipe_compare_standard),
                            Modifier.weight(1f),
                        )
                        RecipeSheetColumn(
                            game,
                            comparison.alternate,
                            stringResource(R.string.recipe_compare_alternate),
                            Modifier.weight(1f),
                        )
                    }
                    Text(stringResource(R.string.recipe_plan_impact), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.raw_title), style = MaterialTheme.typography.titleSmall)
                    comparison.raw
                        .sortedBy { game.itemOrNull(it.key)?.localized(locale) ?: it.key }
                        .forEach { delta ->
                            DeltaBlock(
                                game.itemOrNull(delta.key)?.localized(locale) ?: delta.key,
                                perMinuteLine(delta, locale),
                                delta,
                            )
                        }
                    Text(stringResource(R.string.machines_title), style = MaterialTheme.typography.titleSmall)
                    if (comparison.machines.isEmpty()) {
                        Text(stringResource(R.string.recipe_machines_unchanged), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        comparison.machines
                            .sortedBy { game.recipeOrNull(it.key)?.name?.localized(locale) ?: it.key }
                            .forEach { delta ->
                                val recipe = game.recipeOrNull(delta.key)
                                DeltaBlock(
                                    recipe?.name?.localized(locale) ?: delta.key,
                                    countLine(delta, locale),
                                    delta,
                                )
                            }
                    }
                    if (comparison.byproducts.isNotEmpty()) {
                        Text(stringResource(R.string.byproducts_title), style = MaterialTheme.typography.titleSmall)
                        comparison.byproducts
                            .sortedBy { game.itemOrNull(it.key)?.localized(locale) ?: it.key }
                            .forEach { delta ->
                                DeltaBlock(
                                    game.itemOrNull(delta.key)?.localized(locale) ?: delta.key,
                                    perMinuteLine(delta, locale),
                                    delta,
                                )
                            }
                    }
                    Text(stringResource(R.string.power_label), style = MaterialTheme.typography.titleSmall)
                    DeltaBlock(
                        stringResource(R.string.power_label),
                        powerLine(comparison, locale),
                        RateDelta("power", comparison.powerBaselineMw, comparison.powerAlternateMw),
                    )
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.recipe_close)) }
            }
        }
    }
}

@Composable
private fun RecipeSheetColumn(
    game: GameData,
    sheet: RecipeSheet,
    heading: String,
    modifier: Modifier,
) {
    val locale = currentLocale()
    val recipe = game.recipeOrNull(sheet.recipeId)
    val recipeName = recipe?.name?.localized(locale) ?: sheet.recipeId
    val buildingName = game.buildingOrNull(sheet.buildingId)?.name?.localized(locale) ?: sheet.buildingId
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(heading, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(recipeName, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(buildingName, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.recipe_cycle) + ": " + stringResource(R.string.recipe_seconds, formatNumber(sheet.cycleSeconds, locale)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.recipe_power_each) + ": " + stringResource(R.string.recipe_mw, formatNumber(sheet.powerMw, locale)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.recipe_inputs), style = MaterialTheme.typography.labelMedium)
            if (sheet.inputs.isEmpty()) {
                Text("–", style = MaterialTheme.typography.bodySmall)
            }
            sheet.inputs.forEach { flow ->
                val name = game.itemOrNull(flow.itemId)?.localized(locale) ?: flow.itemId
                Text(
                    "$name  ${stringResource(R.string.per_min, formatNumber(flow.perMinute, locale))}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(stringResource(R.string.recipe_outputs), style = MaterialTheme.typography.labelMedium)
            sheet.outputs.forEach { flow ->
                val name = game.itemOrNull(flow.itemId)?.localized(locale) ?: flow.itemId
                Text(
                    "$name  ${stringResource(R.string.per_min, formatNumber(flow.perMinute, locale))}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DeltaBlock(label: String, value: String, delta: RateDelta) {
    val changed = abs(delta.delta) > 1e-3
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun perMinuteLine(delta: RateDelta, locale: Locale): String {
    val left = stringResource(R.string.per_min, formatNumber(delta.baseline, locale))
    val right = stringResource(R.string.per_min, formatNumber(delta.alternate, locale))
    return "$left → $right (${signedDelta(delta.delta, locale)})"
}

@Composable
private fun countLine(delta: RateDelta, locale: Locale): String =
    "${formatNumber(delta.baseline, locale)} → ${formatNumber(delta.alternate, locale)} (${signedDelta(delta.delta, locale)})"

@Composable
private fun powerLine(comparison: RecipeComparison, locale: Locale): String {
    val left = stringResource(R.string.recipe_mw, formatNumber(comparison.powerBaselineMw, locale))
    val right = stringResource(R.string.recipe_mw, formatNumber(comparison.powerAlternateMw, locale))
    return "$left → $right (${signedDelta(comparison.powerAlternateMw - comparison.powerBaselineMw, locale)})"
}

private fun signedDelta(delta: Double, locale: Locale): String {
    if (abs(delta) < 1e-3) return formatNumber(0.0, locale)
    return if (delta > 0) "+${formatNumber(delta, locale)}" else formatNumber(delta, locale)
}
