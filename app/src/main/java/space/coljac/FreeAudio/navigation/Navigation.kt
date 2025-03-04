package space.coljac.FreeAudio.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
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
        BottomNavItem(Screen.Search, Icons.Default.Search, "Search"),
        BottomNavItem(Screen.Favorites, Icons.Default.Favorite, "Favorites"),
        BottomNavItem(Screen.Downloads, Icons.Default.Download, "Downloads")
    )
    
    val showBottomNav = !currentRoute.isNullOrEmpty() && 
                      !currentRoute.startsWith("talk/") && 
                      (currentRoute == Screen.Home.route || 
                       currentRoute == Screen.Search.route ||
                       currentRoute == Screen.Favorites.route || 
                       currentRoute == Screen.Downloads.route)
    
    // Create a compact layout with no extra whitespace
    val currentTalk by viewModel.currentTalk.collectAsState()
    val hasPlayerBar = currentTalk != null && 
        currentRoute?.startsWith("talk/") != true // Hide on talk detail screen
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            // This is crucial - only take the space actually needed
            Column(
                modifier = Modifier.wrapContentHeight()
            ) {
                // Only show player bar if there's an active talk and not on detail screen
                if (hasPlayerBar) {
                    BottomPlayerBar(
                        viewModel = viewModel,
                        onTalkClick = { talk ->
                            navController.navigate(Screen.TalkDetail.createRoute(talk.id))
                        },
                        currentScreen = if (currentRoute?.startsWith("talk/") == true) "TalkDetail" else ""
                    )
                }
                
                // Navigation bar for main screens
                if (showBottomNav) {
                    NavigationBar {
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
    ) { padding ->
        // Main content area
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(padding)
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
    }
} 