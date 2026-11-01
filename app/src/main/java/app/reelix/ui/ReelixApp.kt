package app.reelix.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.reelix.di.AppContainer
import app.reelix.domain.model.StatusFilter
import app.reelix.ui.screens.detail.ItemDetailScreen
import app.reelix.ui.screens.detail.ItemDetailViewModel
import app.reelix.ui.screens.home.HomeScreen
import app.reelix.ui.screens.home.HomeViewModel
import app.reelix.ui.screens.library.SavedItemsScreen
import app.reelix.ui.screens.library.SavedItemsViewModel
import app.reelix.ui.screens.search.SearchScreen
import app.reelix.ui.screens.search.SearchViewModel
import app.reelix.ui.screens.settings.SettingsScreen
import app.reelix.ui.screens.settings.SettingsViewModel

private enum class TopDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME("home", "Home", Icons.Filled.Home, Icons.Outlined.Home),
    LIBRARY("library", "Saved", Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks),
    SEARCH("search", "Search", Icons.Filled.Search, Icons.Outlined.Search),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

@Composable
fun ReelixApp(
    container: AppContainer,
    openItemId: String? = null,
    onOpenItemConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    var pendingFilter by rememberSaveable { mutableStateOf<StatusFilter?>(null) }
    var pendingSearchTag by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(openItemId) {
        if (!openItemId.isNullOrBlank()) {
            navController.navigate("detail/${Uri.encode(openItemId)}")
            onOpenItemConsumed()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showPrimaryNav = TopDestination.entries.any { it.route == currentRoute }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        if (useNavRail) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (showPrimaryNav) {
                    NavigationRail {
                        TopDestination.entries.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navController.navigateTopLevel(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label,
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag("nav_${dest.route}"),
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    ReelixNavHost(
                        navController = navController,
                        container = container,
                        pendingFilter = pendingFilter,
                        onConsumeFilter = { pendingFilter = null },
                        onSelectLibraryFilter = { filter ->
                            pendingFilter = filter
                            navController.navigateTopLevel(TopDestination.LIBRARY.route)
                        },
                        pendingSearchTag = pendingSearchTag,
                        onConsumeSearchTag = { pendingSearchTag = null },
                        onNavigateSearchWithTag = { tag ->
                            pendingSearchTag = tag
                            navController.navigateTopLevel(TopDestination.SEARCH.route)
                        },
                    )
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    if (showPrimaryNav) {
                        NavigationBar {
                            TopDestination.entries.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navController.navigateTopLevel(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label,
                                        )
                                    },
                                    label = { Text(dest.label) },
                                    modifier = Modifier.testTag("nav_${dest.route}"),
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding()),
                ) {
                    ReelixNavHost(
                        navController = navController,
                        container = container,
                        pendingFilter = pendingFilter,
                        onConsumeFilter = { pendingFilter = null },
                        onSelectLibraryFilter = { filter ->
                            pendingFilter = filter
                            navController.navigateTopLevel(TopDestination.LIBRARY.route)
                        },
                        pendingSearchTag = pendingSearchTag,
                        onConsumeSearchTag = { pendingSearchTag = null },
                        onNavigateSearchWithTag = { tag ->
                            pendingSearchTag = tag
                            navController.navigateTopLevel(TopDestination.SEARCH.route)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReelixNavHost(
    navController: NavHostController,
    container: AppContainer,
    pendingFilter: StatusFilter?,
    onConsumeFilter: () -> Unit,
    onSelectLibraryFilter: (StatusFilter) -> Unit,
    pendingSearchTag: String?,
    onConsumeSearchTag: () -> Unit,
    onNavigateSearchWithTag: (String?) -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = TopDestination.HOME.route,
    ) {
        composable(TopDestination.HOME.route) {
            val vm: HomeViewModel = viewModel(
                factory = HomeViewModel.factory(
                    repository = container.savedItemRepository,
                    shareIntake = container.shareIntakeUseCase,
                    scheduler = container.processingScheduler,
                ),
            )
            HomeScreen(
                viewModel = vm,
                onNavigateToLibrary = onSelectLibraryFilter,
                onNavigateToSearch = onNavigateSearchWithTag,
                onNavigateToSettings = { navController.navigateTopLevel(TopDestination.SETTINGS.route) },
                onOpenItemDetails = { itemId ->
                    navController.navigate("detail/${Uri.encode(itemId)}")
                },
            )
        }

        composable(TopDestination.LIBRARY.route) {
            val vm: SavedItemsViewModel = viewModel(
                factory = SavedItemsViewModel.factory(
                    repository = container.savedItemRepository,
                    scheduler = container.processingScheduler,
                ),
            )
            SavedItemsScreen(
                viewModel = vm,
                initialFilter = pendingFilter,
                onInitialFilterConsumed = onConsumeFilter,
                onOpenDetails = { itemId ->
                    navController.navigate("detail/${Uri.encode(itemId)}")
                },
                onSearchTag = { tag -> onNavigateSearchWithTag(tag) },
            )
        }

        composable(TopDestination.SEARCH.route) {
            val vm: SearchViewModel = viewModel(
                factory = SearchViewModel.factory(container.savedItemRepository),
            )
            SearchScreen(
                viewModel = vm,
                initialTag = pendingSearchTag,
                onInitialTagConsumed = onConsumeSearchTag,
                onOpenDetails = { itemId ->
                    navController.navigate("detail/${Uri.encode(itemId)}")
                },
            )
        }

        composable(TopDestination.SETTINGS.route) {
            val vm: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory(
                    settingsRepository = container.settingsRepository,
                    savedItemRepository = container.savedItemRepository,
                    scheduler = container.processingScheduler,
                    storageInspector = container.storageInspector,
                    modelManager = container.transcriptionModelManager,
                    cloudSyncRepository = container.cloudSyncRepository,
                ),
            )
            SettingsScreen(viewModel = vm)
        }

        composable(
            route = "detail/{itemId}",
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { entry ->
            val itemId = entry.arguments?.getString("itemId").orEmpty()
            val vm: ItemDetailViewModel = viewModel(
                key = "detail_$itemId",
                factory = ItemDetailViewModel.factory(
                    itemId = itemId,
                    repository = container.savedItemRepository,
                    scheduler = container.processingScheduler,
                ),
            )
            ItemDetailScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() },
                onSearchTag = { tag -> onNavigateSearchWithTag(tag) },
            )
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

