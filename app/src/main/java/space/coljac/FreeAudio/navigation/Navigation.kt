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
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import space.coljac.FreeAudio.ui.components.BottomPlayerBar
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues

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
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = navigationBarPadding.calculateBottomPadding())
    ) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(bottom = 64.dp)
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
        
        BottomPlayerBar(
            viewModel = viewModel,
            onTalkClick = { talk ->
                navController.navigate(Screen.TalkDetail.createRoute(talk.id))
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
} 