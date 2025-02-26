package space.coljac.FreeAudio.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import space.coljac.FreeAudio.ui.screens.FavoritesScreen
import space.coljac.FreeAudio.ui.screens.SearchScreen
import space.coljac.FreeAudio.ui.screens.TalkDetailScreen
import space.coljac.FreeAudio.ui.screens.DownloadedScreen
import space.coljac.FreeAudio.viewmodel.AudioViewModel
import space.coljac.FreeAudio.ui.screens.HomeScreen
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import space.coljac.FreeAudio.ui.components.BottomPlayerBar
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object Favorites : Screen("favorites")
    data object Downloads : Screen("downloads")
    data object TalkDetail : Screen("talk/{talkId}") {
        fun createRoute(talkId: String) = "talk/$talkId"
    }
}

data class BottomNavItem(
    val screen: Screen,
    val icon: ImageVector,
    val label: String
)

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    viewModel: AudioViewModel
) {
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    val bottomNavItems = listOf(
        BottomNavItem(Screen.Home, Icons.Default.Home, "Home"),
        BottomNavItem(Screen.Favorites, Icons.Default.Favorite, "Favorites"),
        BottomNavItem(Screen.Downloads, Icons.Default.Download, "Downloads")
    )
    
    val showBottomNav = !currentRoute.isNullOrEmpty() && 
                      !currentRoute.startsWith("talk/") && 
                      (currentRoute == Screen.Home.route || 
                       currentRoute == Screen.Favorites.route || 
                       currentRoute == Screen.Downloads.route)
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = navigationBarPadding.calculateBottomPadding())
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route,
                    modifier = Modifier.padding(bottom = if (showBottomNav) 128.dp else 64.dp)
                ) {
                    composable(Screen.Home.route) {
                        HomeScreen(
                            viewModel = viewModel,
                            onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                            onNavigateToFavorites = { navController.navigate(Screen.Favorites.route) },
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
                    
                    composable(Screen.Favorites.route) {
                        FavoritesScreen(
                            viewModel = viewModel,
                            onNavigateUp = { navController.navigate(Screen.Home.route) },
                            onTalkSelected = { talk ->
                                navController.navigate(Screen.TalkDetail.createRoute(talk.id))
                            }
                        )
                    }
                    
                    composable(Screen.Downloads.route) {
                        DownloadedScreen(
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
                        .padding(bottom = if (showBottomNav) 64.dp else 0.dp)
                )
                
                if (showBottomNav) {
                    NavigationBar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                    ) {
                        bottomNavItems.forEach { item ->
                            NavigationBarItem(
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                                selected = currentRoute == item.screen.route,
                                onClick = {
                                    if (currentRoute != item.screen.route) {
                                        navController.navigate(item.screen.route) {
                                            popUpTo(navController.graph.startDestinationId)
                                            launchSingleTop = true
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
} 