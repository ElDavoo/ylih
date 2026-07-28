package it.eldavo.ylih.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import it.eldavo.ylih.R
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private data class Destination(
    val route: String,
    @param:StringRes val label: Int,
    val icon: ImageVector,
)

private val destinations = listOf(
    Destination("devices", R.string.nav_headphones, Icons.Default.Home),
    Destination("stats", R.string.nav_stats, Icons.Default.DateRange),
    Destination("settings", R.string.nav_settings, Icons.Default.Settings),
)

private const val PAIR_ROUTE = "pair/{pairId}"

// Destinations crossfade. The app bar is not one of them — it sits in the Scaffold, outside the
// NavHost — so it has to be faded by hand to stay in step, and the two only stay in step if they
// read the same spec. Hence the NavHost gets its transitions passed explicitly below rather than
// inheriting navigation-compose's default (identical today: fadeIn/fadeOut of tween(700)); an
// inherited default is a number that can change under us in a dependency bump.
private val NAV_FADE = tween<Float>(durationMillis = 700)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YlihNavHost(
    viewModel: YlihViewModel = viewModel(),
    /**
     * Hoisted only so a test can reach a destination the UI has no way of asking for. A route
     * argument is a string, so the pair route has to cope with one that is not a number, and
     * every button that navigates there builds it from a Long.
     */
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }

    // Scroll state is hoisted so the app bar can ask whether the screen in front of the user
    // actually scrolls. Without that check a short screen still collapses the bar: a LazyColumn
    // dispatches its overscroll up the nested-scroll chain even when there is nothing to scroll,
    // so a drag on the devices list with two pairs on it shrinks the title for no reason.
    val devicesListState = rememberLazyListState()
    val statsListState = rememberLazyListState()
    val settingsScrollState = rememberScrollState()
    val activeScrollState: ScrollableState? = when (currentRoute) {
        "devices" -> devicesListState
        "stats" -> statsListState
        "settings" -> settingsScrollState
        else -> null
    }
    val canScroll by remember(activeScrollState) {
        derivedStateOf {
            activeScrollState?.let { it.canScrollForward || it.canScrollBackward } == true
        }
    }

    // The full name needs two lines, which is a lot of screen to give up permanently. Collapsing
    // it on scroll is what the flexible bar is for: branded at rest, out of the way in use.
    val allowCollapse = remember { mutableStateOf(true) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        canScroll = { allowCollapse.value },
    )

    // The verdict is only ever taken with the bar fully open, and then latched for the whole
    // gesture. Collapsing the bar hands its height back to the content, so a screen that only
    // just overflows stops overflowing halfway through the collapse; sampling canScroll live
    // makes the bar fight itself and snap back — settings did exactly that.
    val expanded by remember { derivedStateOf { scrollBehavior.state.heightOffset == 0f } }
    SideEffect {
        if (expanded) allowCollapse.value = canScroll
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        // Nested scroll on the Scaffold is enough: the LazyColumns inside each screen dispatch
        // their scroll up to it.
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Pair detail brings its own app bar with a back arrow, so the app-level one is only
            // for the three tabs. The flexible medium bar is what lets the full name wrap onto a
            // second line instead of being ellipsised down to "ylih - your life in…".
            //
            // Fading rather than swapping it: the route flips the moment navigate() is called, so
            // dropping the bar on `currentRoute` alone made it vanish a beat before the screen it
            // belongs to had finished crossfading — and took its height with it, jerking the list
            // underneath upwards mid-animation. AnimatedVisibility holds the height for the whole
            // exit and hands it back at the start of the enter, so the two bars trade places in
            // step with the destinations behind them. Tab-to-tab keeps the bar untouched, which is
            // the point of hoisting it here.
            AnimatedVisibility(
                visible = currentRoute != PAIR_ROUTE,
                enter = fadeIn(NAV_FADE),
                exit = fadeOut(NAV_FADE),
            ) {
                MediumFlexibleTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_title),
                            maxLines = 2,
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        bottomBar = {
            // Expressive's shorter bar: the active item gets a filled pill that springs into
            // place rather than the old static indicator.
            ShortNavigationBar {
                destinations.forEach { destination ->
                    ShortNavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "devices",
            enterTransition = { fadeIn(NAV_FADE) },
            exitTransition = { fadeOut(NAV_FADE) },
            popEnterTransition = { fadeIn(NAV_FADE) },
            popExitTransition = { fadeOut(NAV_FADE) },
        ) {
            composable("devices") {
                DevicesScreen(
                    viewModel = viewModel,
                    contentPadding = padding,
                    onOpenPair = { navController.navigate("pair/$it") },
                    listState = devicesListState,
                )
            }
            composable("stats") {
                StatsScreen(
                    viewModel = viewModel,
                    contentPadding = padding,
                    listState = statsListState,
                )
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    contentPadding = padding,
                    scrollState = settingsScrollState,
                )
            }
            composable(PAIR_ROUTE) { entry ->
                val pairId = entry.arguments?.getString("pairId")?.toLongOrNull()
                if (pairId == null) {
                    navController.popBackStack()
                } else {
                    PairDetailScreen(
                        viewModel = viewModel,
                        pairId = pairId,
                        contentPadding = padding,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }

    // Outside the Scaffold: a dialog is not laid out by it, and the welcome has to sit above the
    // whole app, tabs included.
    val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()
    if (onboardingDone == false) {
        WelcomeDialog(onDismiss = viewModel::completeOnboarding)
    }
}
