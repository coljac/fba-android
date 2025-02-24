package space.coljac.FreeAudio.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import space.coljac.FreeAudio.ui.screens.SearchScreen
import space.coljac.FreeAudio.ui.screens.TalkDetailScreen
import space.coljac.FreeAudio.viewmodel.AudioViewModel
import space.coljac.FreeAudio.ui.screens.HomeScreen

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object TalkDetail : Screen("talk/{talkId}") {
        fun createRoute(talkId: String) = "talk/$talkId"
    }
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    viewModel: AudioViewModel
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                onTalkSelected = { talk ->
                    navController.navigate(Screen.TalkDetail.createRoute(talk.id))
                }
            )
        }
        
        composable(Screen.Search.route) {
            SearchScreen(
                viewModel = viewModel,
                onTalkSelected = { talk ->
                    navController.navigate(Screen.TalkDetail.createRoute(talk.id))
                }
            )
        }
        
        composable(
            route = Screen.TalkDetail.route,
            arguments = listOf(
                navArgument("talkId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val talkId = backStackEntry.arguments?.getString("talkId")
                ?: return@composable
            TalkDetailScreen(
                viewModel = viewModel,
                talkId = talkId,
                onNavigateUp = { navController.navigateUp() }
            )
        }
    }
} 