package de.fabrikplaner.app.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.fabrikplaner.app.R
import de.fabrikplaner.app.ui.currentLocale
import de.fabrikplaner.app.ui.icon.BuildingGlyph
import de.fabrikplaner.app.ui.icon.ItemGlyph
import de.fabrikplaner.app.ui.localized
import de.fabrikplaner.app.ui.parsePositive
import de.fabrikplaner.core.solver.SolverResult
import de.fabrikplaner.core.data.GameData

private val categoryOrder = listOf(
    "raw", "ingot", "basic", "industrial", "oil", "advanced", "electronics", "aluminum",
)

@Composable
fun ItemPickerDialog(
    game: GameData,
    title: String,
    showRate: Boolean = true,
    onDismiss: () -> Unit,
    onPick: (itemId: String, rate: Double) -> Unit,
) {
    val locale = currentLocale()
    var query by rememberSaveable { mutableStateOf("") }
    var rateText by rememberSaveable { mutableStateOf("10") }
    val filtered = game.items.filter { item ->
        val name = item.localized(locale)
        query.isBlank() || name.contains(query, ignoreCase = true) || item.id.contains(query, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (showRate) {
                    OutlinedTextField(
                        value = rateText,
                        onValueChange = { rateText = it },
                        label = { Text(stringResource(R.string.rate_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    if (filtered.isEmpty()) {
                        Text(stringResource(R.string.no_matches))
                    }
                    categoryOrder.forEach { category ->
                        val group = filtered.filter { it.category == category }.sortedBy { it.localized(locale) }
                        if (group.isNotEmpty()) {
                            Text(
                                categoryLabel(category),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                            group.forEach { item ->
                                val name = item.localized(locale)
                                PickerRow(name, { ItemGlyph(name, item.id, item.form, size = 32.dp) }) {
                                    val rate = if (showRate) parsePositive(rateText) ?: return@PickerRow else 0.0
                                    onPick(item.id, rate)
                                }
                            }
                        }
                    }
                    filtered.filter { it.category !in categoryOrder }.sortedBy { it.localized(locale) }.forEach { item ->
                        val name = item.localized(locale)
                        PickerRow(name, { ItemGlyph(name, item.id, item.form, size = 32.dp) }) {
                            val rate = if (showRate) parsePositive(rateText) ?: return@PickerRow else 0.0
                            onPick(item.id, rate)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun RecipePickerDialog(
    game: GameData,
    itemId: String,
    selection: Map<String, String> = emptyMap(),
    solve: ((Map<String, String>) -> SolverResult)? = null,
    onDismiss: () -> Unit,
    onPick: (recipeId: String) -> Unit,
) {
    val locale = currentLocale()
    val item = game.itemOrNull(itemId)
    val recipes = game.recipesProducing(itemId)
    val defaultId = game.recipeFor(itemId)?.id
    var compare by remember { mutableStateOf<RecipeCompareRequest?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item?.localized(locale) ?: stringResource(R.string.choose_recipe)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                recipes.forEach { recipe ->
                    val badge = if (recipe.alternate) {
                        stringResource(R.string.alternate_badge)
                    } else {
                        stringResource(R.string.standard_badge)
                    }
                    val building = game.buildingOrNull(recipe.buildingId)?.name?.localized(locale).orEmpty()
                    val recipeName = recipe.name.localized(locale)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier
                                .weight(1f)
                                .clickable { onPick(recipe.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ItemGlyph(item?.localized(locale) ?: recipeName, itemId, item?.form, size = 28.dp)
                            BuildingGlyph(building, recipe.buildingId, modifier = Modifier.padding(start = 4.dp), size = 22.dp)
                            Text(
                                "$recipeName · $badge · $building",
                                modifier = Modifier.padding(start = 8.dp).weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (solve != null && recipe.id != defaultId) {
                            RecipeCompareButton { compare = RecipeCompareRequest(itemId, recipe.id) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    val solvePlan = solve
    if (solvePlan != null) {
        RecipeCompareHost(
            game = game,
            selection = selection,
            solve = solvePlan,
            request = compare,
            onDismiss = { compare = null },
        )
    }
}

@Composable
private fun PickerRow(label: String, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = icon,
        modifier = Modifier.clickable(onClick = onClick).fillMaxWidth(),
    )
}

@Composable
private fun categoryLabel(category: String): String = when (category) {
    "raw" -> stringResource(R.string.category_raw)
    "ingot" -> stringResource(R.string.category_ingot)
    "basic" -> stringResource(R.string.category_basic)
    "industrial" -> stringResource(R.string.category_industrial)
    "oil" -> stringResource(R.string.category_oil)
    "advanced" -> stringResource(R.string.category_advanced)
    "electronics" -> stringResource(R.string.category_electronics)
    "aluminum" -> stringResource(R.string.category_aluminum)
    else -> stringResource(R.string.category_other)
}
