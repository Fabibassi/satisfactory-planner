package de.fabrikplaner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import de.fabrikplaner.app.ui.AppRoot
import de.fabrikplaner.app.ui.theme.FabrikplanerTheme
import de.fabrikplaner.core.data.GameDataLoader
import de.fabrikplaner.core.persistence.PlanRepository
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val json = assets.open("game_data.json").bufferedReader().use { it.readText() }
        val game = GameDataLoader.parse(json)
        val repository = PlanRepository(File(filesDir, "plans"))
        val viewModel = ViewModelProvider(
            this,
            PlannerViewModel.factory(game, repository),
        )[PlannerViewModel::class.java]
        setContent {
            FabrikplanerTheme {
                AppRoot(viewModel)
            }
        }
    }
}
