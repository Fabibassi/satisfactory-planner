package de.fabrikplaner.app.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.fabrikplaner.app.R
import de.fabrikplaner.app.ui.currentLocale
import de.fabrikplaner.app.ui.icon.BuildingGlyph
import de.fabrikplaner.app.ui.icon.ItemGlyph
import de.fabrikplaner.app.ui.localized
import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.solver.SolverResult

@Composable
fun AlternateRecipeDialog(
    game: GameData,
    initialSelection: Map<String, String>,
    solve: (Map<String, String>) -> SolverResult,
    choiceItems: (SolverResult) -> List<String>,
    onDismiss: () -> Unit,
    onApply: (Map<String, String>) -> Unit,
) {
    val locale = currentLocale()
    var draft by remember { mutableStateOf(initialSelection) }
    val preview = remember(draft) { solve(draft) }
    val items = choiceItems(preview)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.recipe_prompt_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.recipe_prompt_body), style = MaterialTheme.typography.bodyMedium)
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (items.isEmpty()) {
                        Text(stringResource(R.string.recipe_prompt_none))
                    }
                    items.forEach { itemId ->
                        val item = game.itemOrNull(itemId)
                        val name = item?.localized(locale) ?: itemId
                        val recipes = game.recipesProducing(itemId)
                        val active = preview.machines.firstOrNull { machine ->
                            val recipe = machine.recipeId?.let { game.recipeOrNull(it) }
                            recipe?.outputs?.firstOrNull()?.itemId == itemId
                        }?.recipeId ?: game.recipeFor(itemId, draft)?.id
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ItemGlyph(name, itemId, item?.form)
                                Text(name, style = MaterialTheme.typography.titleSmall)
                            }
                            recipes.forEach { recipe ->
                                val buildingName = game.buildingOrNull(recipe.buildingId)?.name?.localized(locale).orEmpty()
                                val badge = if (recipe.alternate) {
                                    stringResource(R.string.alternate_badge)
                                } else {
                                    stringResource(R.string.standard_badge)
                                }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { draft = draft + (itemId to recipe.id) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = recipe.id == active,
                                        onClick = { draft = draft + (itemId to recipe.id) },
                                    )
                                    BuildingGlyph(buildingName, recipe.buildingId)
                                    Text(
                                        "${recipe.name.localized(locale)} · $badge · $buildingName",
                                        modifier = Modifier.padding(start = 8.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onApply(emptyMap()) }) {
                        Text(stringResource(R.string.recipe_use_defaults))
                    }
                    Button(onClick = { onApply(draft) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.recipe_apply))
                    }
                }
            }
        }
    }
}
