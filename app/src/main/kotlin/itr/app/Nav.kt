package itr.app

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import itr.app.di.ScanControllerFactory
import itr.scan.ScanWizardScreen

@Composable
fun ItrNav(scanControllerFactory: ScanControllerFactory) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onScan = { navController.navigate("scan") },
                onDetail = { navController.navigate("detail/$it") },
                onSettings = { navController.navigate("settings") },
            )
        }
        composable("scan") {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings = settingsViewModel.settings.collectAsStateWithLifecycle().value
            ScanWizardScreen(
                createController = { session, lifecycle -> scanControllerFactory.create(session, lifecycle) },
                units = settings.units,
                snapByDefault = settings.snapByDefault,
                onFinished = { navController.popBackStack() },
            )
        }
        composable(
            route = "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            DetailScreen(onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
