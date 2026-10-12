package de.fabrikplaner.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import de.fabrikplaner.app.PlannerViewModel
import de.fabrikplaner.app.ui.editor.EditorScreen
import de.fabrikplaner.app.ui.plan.AlternateRecipeDialog
import de.fabrikplaner.app.ui.plan.PlanListScreen
import de.fabrikplaner.app.ui.plan.PlannerScreen

sealed interface Screen {
    data object Plans : Screen
    data class Planner(val id: String) : Screen
    data class Editor(val id: String) : Screen
}

@Composable
fun AppRoot(viewModel: PlannerViewModel) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Plans) }
    BackHandler(enabled = stack.size > 1) {
        stack.removeAt(stack.lastIndex)
    }
    when (val screen = stack.last()) {
        Screen.Plans -> PlanListScreen(
            plans = viewModel.plans,
            game = viewModel.game,
            onCreate = { name ->
                val id = viewModel.createPlan(name)
                stack.add(Screen.Planner(id))
            },
            onExample = { name ->
                val id = viewModel.createExample(name)
                stack.add(Screen.Planner(id))
                if (!viewModel.recipePrompt) {
                    viewModel.generateGraph()
                    stack.add(Screen.Editor(id))
                }
            },
            onOpen = { id ->
                viewModel.open(id)
                stack.add(Screen.Planner(id))
            },
            onDelete = viewModel::deletePlan,
        )
        is Screen.Planner -> {
            LaunchedEffect(screen.id) { viewModel.open(screen.id) }
            PlannerScreen(
                viewModel = viewModel,
                onBack = { stack.removeAt(stack.lastIndex) },
                onOpenEditor = {
                    val empty = viewModel.current?.nodes.isNullOrEmpty()
                    val choices = viewModel.choiceItemIds(viewModel.result)
                    if (empty && choices.isNotEmpty()) {
                        viewModel.openRecipePrompt()
                    } else {
                        if (empty && viewModel.result?.machines?.isNotEmpty() == true) {
                            viewModel.generateGraph()
                        }
                        stack.add(Screen.Editor(screen.id))
                    }
                },
            )
        }
        is Screen.Editor -> {
            LaunchedEffect(screen.id) { viewModel.open(screen.id) }
            EditorScreen(
                viewModel = viewModel,
                onBack = { stack.removeAt(stack.lastIndex) },
                onOpenPlanner = {
                    if (stack.getOrNull(stack.lastIndex - 1) is Screen.Planner) {
                        stack.removeAt(stack.lastIndex)
                    } else {
                        stack.add(Screen.Planner(screen.id))
                    }
                },
            )
        }
    }
    if (viewModel.recipePrompt) {
        AlternateRecipeDialog(
            game = viewModel.game,
            initialSelection = viewModel.current?.recipeSelection.orEmpty(),
            solve = viewModel::preview,
            onDismiss = viewModel::dismissRecipePrompt,
            onApply = { selection ->
                val openEditor = stack.last() is Screen.Planner
                val plannerId = (stack.last() as? Screen.Planner)?.id
                viewModel.confirmRecipes(selection, rebuildGraph = true)
                if (openEditor && plannerId != null && stack.last() is Screen.Planner) {
                    stack.add(Screen.Editor(plannerId))
                }
            },
        )
    }
}
