package it.eldavo.ylih.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import it.eldavo.ylih.R
import it.eldavo.ylih.tracking.Hibernation
import it.eldavo.ylih.tracking.Restrictions
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

private data class Destination(
    @param:StringRes val label: Int,
    val icon: ImageVector,
)

// Position in this list is the pager page, which is the only identity a tab has now.
private val destinations = listOf(
    Destination(R.string.nav_headphones, Icons.Default.Home),
    Destination(R.string.nav_stats, Icons.Default.DateRange),
    Destination(R.string.nav_settings, Icons.Default.Settings),
)

private const val TAB_DEVICES = 0
private const val TAB_STATS = 1

// The three tabs are one destination between them, not three. They used to be a destination each,
// which is what made moving between them a navigation and left them with no gesture: a NavHost
// swaps its content on a click and a back stack, and there is nothing to drag. As pages of a pager
// they are laid out side by side, so the swipe is the layout rather than an animation bolted onto
// it — the screen follows the finger and can be caught halfway or thrown back.
private const val TABS_ROUTE = "tabs"
private const val PAIR_ROUTE = "pair/{pairId}"

// What is left in the back stack is the pair page, and it crossfades. The app bar is not a
// destination — it sits in the Scaffold, outside the NavHost — so it has to be faded by hand to
// stay in step, and the two only stay in step if they read the same spec. Hence the NavHost gets
// its transitions passed explicitly below rather than inheriting navigation-compose's default
// (identical today: fadeIn/fadeOut of tween(700)); an inherited default is a number that can
// change under us in a dependency bump.
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
    /**
     * Pair ids tapped on a home-screen widget. The activity hoists this for the same reason it
     * hoists [navController]: the widget knows which pair it means, and this is the seam that
     * carries that through without the NavHost having to read an Intent.
     */
    openPair: Flow<Long> = emptyFlow(),
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

    // Hoisted above the NavHost rather than remembered inside the tabs destination: the pair page
    // is a push, so the tabs leave the composition while it is open, and a pager state remembered
    // down there would hand the user back the first tab on the way out.
    val pagerState = rememberPagerState(pageCount = { destinations.size })
    val scope = rememberCoroutineScope()

    // `currentPage` rather than `settledPage`: it flips at the halfway point of a drag, which is
    // the moment the screen the question is about becomes the one in front of the user.
    val activeScrollState: ScrollableState? = when {
        currentRoute != TABS_ROUTE -> null
        pagerState.currentPage == TAB_DEVICES -> devicesListState
        pagerState.currentPage == TAB_STATS -> statsListState
        else -> settingsScrollState
    }
    val canScroll by remember(activeScrollState) {
        derivedStateOf {
            activeScrollState?.let { it.canScrollForward || it.canScrollBackward } == true
        }
    }

    // The full name is a lot of screen to give up permanently even on one line. Collapsing it on
    // scroll is what the flexible bar is for: branded at rest, out of the way in use.
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

    // Back off a tab returns to the first one, which is what the back stack used to do for free:
    // every tab was navigated to with popUpTo(start), so the headphones tab sat underneath the
    // other two. Left enabled on the pair page this would swallow the pop that closes it — the
    // NavHost registers its own handler after this one and so wins, but only while it has
    // something to pop, which on the tabs alone it does not.
    BackHandler(enabled = currentRoute == TABS_ROUTE && pagerState.currentPage != TAB_DEVICES) {
        scope.launch { pagerState.animateScrollToPage(TAB_DEVICES) }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(openPair) {
        openPair.collect { pairId ->
            // launchSingleTop so tapping the same widget row twice does not stack two copies of
            // the same detail screen behind the back button.
            navController.navigate("pair/$pairId") { launchSingleTop = true }
        }
    }

    Scaffold(
        // Nested scroll on the Scaffold is enough: the LazyColumns inside each screen dispatch
        // their scroll up to it.
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Pair detail brings its own app bar with a back arrow, so the app-level one is only
            // for the three tabs.
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
                            maxLines = 1,
                            // The name fits one line at the size the bar would give it in no
                            // language — 30 characters in English, 40 in Cebuano — so it is
                            // shrunk until it does, rather than wrapped onto a second line or
                            // ellipsised to "ylih - your life in…".
                            //
                            // The ceiling is whatever style the bar provided rather than a
                            // number of our own: the flexible bar interpolates that as it
                            // collapses, so binding to it keeps the collapse animation driving
                            // the size and leaves this only ever taking away. 14sp is a floor
                            // and not a size anything is expected to reach — English and
                            // Polish, the longest of the languages the store screenshots cover,
                            // both settle near the ceiling — so that a language nobody here
                            // reads gets a smaller title instead of a clipped one.
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 14.sp,
                                maxFontSize = LocalTextStyle.current.fontSize,
                            ),
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
                destinations.forEachIndexed { page, destination ->
                    ShortNavigationBarItem(
                        // Nothing is selected while the pair page is up, as when each tab was a
                        // route of its own and none of them was the current one.
                        selected = currentRoute == TABS_ROUTE && pagerState.currentPage == page,
                        onClick = {
                            // The bar is still there on the pair page, where a tap means "come
                            // back to the tabs and show me this one". A no-op on the tabs
                            // themselves, which is where the tap usually comes from.
                            navController.popBackStack(TABS_ROUTE, inclusive = false)
                            scope.launch { pagerState.animateScrollToPage(page) }
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
            startDestination = TABS_ROUTE,
            enterTransition = { fadeIn(NAV_FADE) },
            exitTransition = { fadeOut(NAV_FADE) },
            popEnterTransition = { fadeIn(NAV_FADE) },
            popExitTransition = { fadeOut(NAV_FADE) },
        ) {
            composable(TABS_ROUTE) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // A page is a whole screen and lays itself out from the top; the default
                    // centres one that does not fill the height, which the settings column does
                    // not until it has enough rows in it.
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    when (page) {
                        TAB_DEVICES -> DevicesScreen(
                            viewModel = viewModel,
                            contentPadding = padding,
                            onOpenPair = { navController.navigate("pair/$it") },
                            listState = devicesListState,
                        )
                        TAB_STATS -> StatsScreen(
                            viewModel = viewModel,
                            contentPadding = padding,
                            listState = statsListState,
                        )
                        else -> SettingsScreen(
                            viewModel = viewModel,
                            contentPadding = padding,
                            scrollState = settingsScrollState,
                        )
                    }
                }
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

    // Strictly after the welcome, and never beside it: the first run already spends one dialog
    // explaining the app, and two stacked would be a wall to dismiss before seeing anything.
    val hibernationAsked by viewModel.hibernationAsked.collectAsStateWithLifecycle()
    if (onboardingDone == true && hibernationAsked == false) {
        val context = LocalContext.current
        val hibernation by produceState(Hibernation.UNAVAILABLE, context) {
            value = Restrictions.hibernation(context)
        }
        // Nothing to ask for where the platform does not hibernate apps, or where the user has
        // already exempted ylih — and asking anyway would spend the one prompt on a no-op.
        if (hibernation == Hibernation.ENABLED) {
            val intent = remember(context) { Restrictions.settingsIntent(context) }
            HibernationDialog(
                onOpenSettings = {
                    viewModel.dismissHibernationPrompt()
                    intent?.let { context.startActivity(it) }
                },
                onDismiss = viewModel::dismissHibernationPrompt,
            )
        }
    }
}
