package it.eldavo.ylih.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.core.layout.WindowSizeClass
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
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

// The three tabs are one destination: as separate routes, switching had no gesture (a NavHost
// swaps content on click). As pager pages they lay out side by side, so the swipe is the layout
// itself — the screen follows the finger.
private const val TABS_ROUTE = "tabs"
private const val PAIR_ROUTE = "pair/{pairId}"

// The pair page, left in the back stack, scales and fades — NavMotion.kt has the spec and why the
// bar reads only its fade. All six transitions are passed explicitly rather than inherited, since
// navigation-compose's defaults can change under a dependency bump with no way for the bar to
// follow (NavMotion.kt has the incident). The bar's fade keys on the route, which flips only when
// a pop commits, so it plays once the gesture releases, not during it.

/**
 * How wide a screen's content is ever allowed to get.
 *
 * At `targetSdk 37` the platform ignores orientation/resizability restrictions above 600dp,
 * stretching the phone layout across a tablet — lists of a dozen words on a line, unreadable
 * twice. A ceiling, not a size: above every phone width, so it can't affect phone layout.
 */
private val CONTENT_MAX_WIDTH = 640.dp

/**
 * Centres a screen and stops it stretching, at the one place all four flow through.
 *
 * Each screen still takes its own `contentPadding` and scroll state; none knows this exists, so it
 * lives here once rather than four times over.
 */
@Composable
private fun ContentWidth(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = CONTENT_MAX_WIDTH)) { content() }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YlihNavHost(
    viewModel: YlihViewModel = viewModel(factory = YlihViewModel.Factory),
    /**
     * Hoisted only so a test can reach a destination the UI has no way to ask for. A route
     * argument is a string, so the pair route must cope with a non-number one, and every button
     * that navigates there builds it from a Long.
     */
    navController: NavHostController = rememberNavController(),
    /**
     * Pair ids tapped on a home-screen widget. The activity hoists this like [navController]: the
     * widget knows which pair it means, and this seam carries that through without the NavHost
     * reading an Intent.
     */
    openPair: Flow<Long> = emptyFlow(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }

    // Hoisted so the app bar can ask whether the screen in front actually scrolls — without it, a
    // short screen still collapses the bar, since a LazyColumn dispatches overscroll up the chain
    // with nothing to scroll (e.g. a two-pair devices list).
    val devicesListState = rememberLazyListState()
    val statsListState = rememberLazyListState()
    val settingsScrollState = rememberScrollState()

    // Hoisted above the NavHost, not remembered inside the tabs destination: the pair page is a
    // push, so tabs leave the composition while it's open, and a state remembered there would hand
    // back the first tab on return.
    val pagerState = rememberPagerState(pageCount = { destinations.size })
    val scope = rememberCoroutineScope()

    // A tablet or unfolded foldable, at Material's medium-width breakpoint. Uses
    // currentWindowAdaptiveInfoV2, not currentWindowAdaptiveInfo: the latter is deprecated here (it
    // can't see the large/extra-large classes), and a deprecation fails this build.
    val wide = currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    // One lambda for both bar and rail: same control drawn twice, so one place to get "come back to
    // the tabs and show this" right.
    val selectTab: (Int) -> Unit = { page ->
        // Still there on the pair page, where a tap means both things at once; a no-op on the tabs
        // themselves, where the tap usually comes from.
        navController.popBackStack(TABS_ROUTE, inclusive = false)
        scope.launch { pagerState.animateScrollToPage(page) }
    }
    val tabSelected: (Int) -> Boolean = { page ->
        // Nothing selected while the pair page is up, as when each tab was its own route.
        currentRoute == TABS_ROUTE && pagerState.currentPage == page
    }

    // currentPage, not settledPage: it flips at the drag's halfway point, when the screen becomes
    // the one in front of the user.
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

    // Taken only with the bar fully open, then latched for the gesture: collapsing hands the bar's
    // height back to the content, so a screen that only just overflows stops overflowing
    // mid-collapse, and sampling canScroll live would make the bar fight itself and snap back —
    // settings did exactly that.
    //
    // Through snapshotFlow, not SideEffect: a SideEffect body isn't a snapshot observer, so reading
    // the two values there registered no dependency — the latch only re-ran on some unrelated
    // recomposition (a route change, a page flip), so a list that grew past the fold mid-session (a
    // pair connects, a chip appears) left the bar stuck refusing to collapse.
    LaunchedEffect(scrollBehavior) {
        snapshotFlow { (scrollBehavior.state.heightOffset == 0f) to canScroll }
            .collect { (expanded, scrollable) -> if (expanded) allowCollapse.value = scrollable }
    }

    // Back off a tab returns to the first, which the back stack used to do for free (every tab
    // navigated via popUpTo(start), so headphones sat underneath). Left enabled on the pair page
    // this would swallow its pop — the NavHost's own handler registers after this one and wins, but
    // only while it has something to pop, which the tabs alone don't.
    BackHandler(enabled = currentRoute == TABS_ROUTE && pagerState.currentPage != TAB_DEVICES) {
        scope.launch { pagerState.animateScrollToPage(TAB_DEVICES) }
    }

    // Under repeatOnLifecycle like every flow the UI reads, or a message emitted while the activity
    // is stopped is consumed by a host nobody sees, then lost.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        viewModel.messages
            .flowWithLifecycle(lifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .collect { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(openPair) {
        openPair.collect { pairId ->
            // launchSingleTop so tapping the same widget row twice does not stack two copies of
            // the same detail screen behind the back button.
            navController.navigate("pair/$pairId") { launchSingleTop = true }
        }
    }

    // The rail sits beside the Scaffold, not inside it: Scaffold has no side slot, and a rail in
    // the content lambda would make it compute insets and bottom-bar padding for the full window,
    // not the narrower content width.
    Row(Modifier.fillMaxSize()) {
        if (wide) {
            WideNavigationRail {
                destinations.forEachIndexed { page, destination ->
                    WideNavigationRailItem(
                        selected = tabSelected(page),
                        onClick = { selectTab(page) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.label)) },
                        // Collapsed: three one-word labels don't need the expanded rail's width,
                        // and expanding it would be a control of its own.
                        railExpanded = false,
                    )
                }
            }
        }
        Scaffold(
            // Nested scroll on the Scaffold is enough: the LazyColumns inside each screen dispatch
            // scroll up to it.
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            // enableEdgeToEdge is on and the rail already consumes the start and vertical bars; at
            // the default the Scaffold would consume the start inset again and pad content away
            // from a rail that already made room for it.
            contentWindowInsets = if (wide) {
                ScaffoldDefaults.contentWindowInsets
                    .only(WindowInsetsSides.End + WindowInsetsSides.Vertical)
            } else {
                ScaffoldDefaults.contentWindowInsets
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                // Pair detail brings its own app bar with a back arrow, so the app-level one is only
                // for the tabs.
                //
                // Fading rather than swapping: the route flips the instant navigate() runs, so
                // dropping the bar on `currentRoute` alone made it vanish a beat before the
                // crossfading screen finished, taking its height and jerking the list upward
                // mid-animation. AnimatedVisibility holds the height until its own exit ends (the
                // shared spec puts that at the moment the screen finishes fading) and returns it at
                // the enter's start, so the two bars trade places in step. Hoisting it here keeps it
                // untouched tab-to-tab.
                AnimatedVisibility(
                    visible = currentRoute != PAIR_ROUTE,
                    enter = barEnter(),
                    exit = barExit(),
                ) {
                    MediumFlexibleTopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.app_title),
                                maxLines = 1,
                                // Fits one line at the bar's own size in no language (30 characters
                                // in English, 40 in Cebuano), so it shrinks rather than wrapping or
                                // ellipsising to "ylih - your life in…".
                                //
                                // The ceiling is the bar's own style, not ours: the flexible bar
                                // interpolates it while collapsing, so binding to it keeps the
                                // animation driving the size. 14sp is a floor, not an expected size —
                                // English and Polish, the longest store-screenshot languages, both
                                // settle near the ceiling — so an unread language gets a smaller
                                // title instead of a clipped one.
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
                // Nothing when the rail is up: the same three destinations down both edges would be
                // one control drawn twice.
                if (!wide) {
                    // Expressive's shorter bar: the active item gets a filled pill that springs
                    // into place rather than a static indicator.
                    ShortNavigationBar {
                        destinations.forEachIndexed { page, destination ->
                            ShortNavigationBarItem(
                                selected = tabSelected(page),
                                onClick = { selectTab(page) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(stringResource(destination.label)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = TABS_ROUTE,
                enterTransition = { navEnter() },
                exitTransition = { navExit() },
                popEnterTransition = { navPopEnter() },
                popExitTransition = { navPopExit() },
                // A back *gesture* is seeked through these two, not the pair above, and they're
                // only ours if named — left unnamed, a NavHost silently takes the library's own.
                // NavMotion.kt has why that matters.
                predictivePopEnterTransition = { navPredictivePopEnter(it) },
                predictivePopExitTransition = { navPredictivePopExit(it) },
            ) {
                composable(TABS_ROUTE) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        // A page is a whole screen laid out from the top; the default centres one
                        // that doesn't fill the height, which settings doesn't until it has enough
                        // rows.
                        verticalAlignment = Alignment.Top,
                    ) { page ->
                        ContentWidth {
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
                }
                composable(PAIR_ROUTE) { entry ->
                    val pairId = entry.arguments?.getString("pairId")?.toLongOrNull()
                    if (pairId == null) {
                        // In an effect, not the composition body: popping the back stack while it
                        // composes is a side effect on the thing doing the composing.
                        LaunchedEffect(entry) { navController.popBackStack() }
                    } else {
                        ContentWidth {
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
        }
    }

    // Outside the Scaffold: a dialog isn't laid out by it, and the welcome must sit above the whole
    // app, tabs included.
    val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()
    if (onboardingDone == false) {
        WelcomeDialog(
            onDismiss = viewModel::completeOnboarding,
            onPermissionResult = viewModel::syncWithSystem,
        )
    }

    // Strictly after the welcome, never beside it: the first run already spends one dialog
    // explaining the app, and two stacked would be a wall to dismiss before seeing anything.
    val hibernationAsked by viewModel.hibernationAsked.collectAsStateWithLifecycle()
    if (onboardingDone == true && hibernationAsked == false) {
        val context = LocalContext.current
        val hibernation by produceState(Hibernation.UNAVAILABLE, context) {
            value = Restrictions.hibernation(context)
        }
        // Nothing to ask where the platform doesn't hibernate apps, or the user already exempted
        // ylih — asking anyway would spend the one prompt on a no-op.
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
