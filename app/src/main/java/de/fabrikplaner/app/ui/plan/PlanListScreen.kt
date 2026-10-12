package de.fabrikplaner.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.fabrikplaner.app.R
import de.fabrikplaner.app.ui.currentLocale
import de.fabrikplaner.app.ui.formatNumber
import de.fabrikplaner.app.ui.localized
import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.model.SavedPlan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanListScreen(
    plans: List<SavedPlan>,
    game: GameData,
    onCreate: (String) -> Unit,
    onExample: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAbout: () -> Unit,
) {
    val locale = currentLocale()
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val defaultName = stringResource(R.string.default_plan_name)
    val exampleName = stringResource(R.string.example_name)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.plans_title)) },
                actions = {
                    IconButton(onClick = onAbout) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.about_cd))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                name = defaultName
                showCreate = true
            }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedButton(
                    onClick = { onExample(exampleName) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.example_plan))
                }
            }
            if (plans.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.plans_empty),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            items(plans, key = { it.id }) { plan ->
                Card(onClick = { onOpen(plan.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(plan.name, style = MaterialTheme.typography.titleMedium)
                            val summary = plan.targets.joinToString {
                                val label = game.itemOrNull(it.itemId)?.localized(locale) ?: it.itemId
                                "$label ${formatNumber(it.perMinute, locale)}"
                            }
                            if (summary.isNotBlank()) {
                                Text(summary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        IconButton(onClick = { onDelete(plan.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete))
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.unofficial_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.new_plan_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.plan_name_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCreate = false
                    onCreate(name.ifBlank { defaultName })
                }) { Text(stringResource(R.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
