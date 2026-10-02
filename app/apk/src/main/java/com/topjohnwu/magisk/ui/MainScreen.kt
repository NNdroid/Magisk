package com.topjohnwu.magisk.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.VMFactory
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.model.module.LocalModule
import com.topjohnwu.magisk.ui.component.isTelevision
import com.topjohnwu.magisk.ui.component.tvFocusFrame
import com.topjohnwu.magisk.ui.home.HomeScreen
import com.topjohnwu.magisk.ui.home.HomeViewModel
import com.topjohnwu.magisk.ui.install.InstallViewModel
import com.topjohnwu.magisk.ui.log.LogScreen
import com.topjohnwu.magisk.ui.log.LogViewModel
import com.topjohnwu.magisk.ui.module.ModuleScreen
import com.topjohnwu.magisk.ui.module.ModuleViewModel
import com.topjohnwu.magisk.ui.navigation.CollectNavEvents
import com.topjohnwu.magisk.ui.navigation.LocalNavigator
import com.topjohnwu.magisk.ui.settings.SettingsScreen
import com.topjohnwu.magisk.ui.settings.SettingsViewModel
import com.topjohnwu.magisk.ui.superuser.SuperuserScreen
import com.topjohnwu.magisk.ui.superuser.SuperuserViewModel
import kotlinx.coroutines.launch
import com.topjohnwu.magisk.core.R as CoreR

enum class Tab(val titleRes: Int, val iconRes: Int) {
    MODULES(CoreR.string.modules, R.drawable.ic_module),
    SUPERUSER(CoreR.string.superuser, CoreR.drawable.ic_superuser),
    HOME(CoreR.string.section_home, R.drawable.ic_home),
    LOG(CoreR.string.logs, R.drawable.ic_bug),
    SETTINGS(CoreR.string.settings, R.drawable.ic_settings);
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    initialTab: Int = Tab.HOME.ordinal,
    superuserViewModel: SuperuserViewModel? = null,
    onAuthenticate: ((onSuccess: () -> Unit) -> Unit)? = null,
) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val tv = isTelevision()
    val visibleTabs = remember {
        Tab.entries.filter { tab ->
            when (tab) {
                Tab.SUPERUSER -> Info.showSuperUser
                Tab.MODULES -> Info.env.isActive && LocalModule.loaded()
                else -> true
            }
        }
    }
    val initialPage = visibleTabs.indexOf(Tab.entries[initialTab]).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { visibleTabs.size })
    val fabFocusRequester = remember { FocusRequester() }
    val moduleContentFocusRequester = remember { FocusRequester() }
    val tabFocusRequesters = remember(visibleTabs) {
        List(visibleTabs.size) { FocusRequester() }
    }
    var moduleFabAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val isModulesTab = visibleTabs.getOrNull(pagerState.currentPage) == Tab.MODULES
    val modulesNavFocusRequester = visibleTabs.indexOf(Tab.MODULES)
        .takeIf { it >= 0 }
        ?.let(tabFocusRequesters::get)

    LaunchedEffect(tv, pagerState.currentPage) {
        if (tv) {
            tabFocusRequesters.getOrNull(pagerState.currentPage)?.requestFocus()
        }
    }

    val moduleFab: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = isModulesTab && moduleFabAction != null,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            FloatingActionButton(
                onClick = { moduleFabAction?.invoke() },
                modifier = Modifier
                    .focusRequester(fabFocusRequester)
                    .tvFocusFrame(enabled = tv, shape = RoundedCornerShape(20.dp))
                    .focusProperties {
                        up = moduleContentFocusRequester
                        if (tv) {
                            modulesNavFocusRequester?.let { left = it }
                            down = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        } else {
                            modulesNavFocusRequester?.let { down = it }
                            right = FocusRequester.Cancel
                        }
                    },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(CoreR.string.module_action_install_external),
                    modifier = Modifier.size(if (tv) 32.dp else 28.dp),
                )
            }
        }
    }

    val pagerContent: @Composable (PaddingValues) -> Unit = { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            beyondViewportPageCount = 0,
            userScrollEnabled = !tv,
        ) { page ->
            val isCurrentPage = pagerState.currentPage == page
            val tab = visibleTabs[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusProperties {
                        onEnter = {
                            if (!isCurrentPage) {
                                cancelFocusChange()
                            }
                        }
                        onExit = {
                            if (!tv && (
                                    requestedFocusDirection == FocusDirection.Left ||
                                        requestedFocusDirection == FocusDirection.Right ||
                                        requestedFocusDirection == FocusDirection.Up
                                    )
                            ) {
                                cancelFocusChange()
                            }
                        }
                        if (tv) {
                            left = tabFocusRequesters[page]
                        }
                        if (tab == Tab.MODULES) {
                            down = fabFocusRequester
                        }
                    }
                    .focusGroup()
            ) {
                when (tab) {
                    Tab.HOME -> {
                        val vm: HomeViewModel = viewModel(factory = VMFactory)
                        val installVm: InstallViewModel = viewModel(factory = VMFactory)
                        LaunchedEffect(isCurrentPage) {
                            if (isCurrentPage) vm.startLoading()
                        }
                        CollectNavEvents(vm, navigator)
                        CollectNavEvents(installVm, navigator)
                        HomeScreen(vm, installVm)
                    }
                    Tab.SUPERUSER -> {
                        val activity = LocalActivity.current as? ComponentActivity
                        val vm: SuperuserViewModel = superuserViewModel
                            ?: if (activity != null) {
                                viewModel(viewModelStoreOwner = activity, factory = VMFactory)
                            } else {
                                viewModel(factory = VMFactory)
                            }
                        LaunchedEffect(onAuthenticate) {
                            if (onAuthenticate != null) {
                                vm.authenticate = onAuthenticate
                            }
                        }
                        LaunchedEffect(isCurrentPage) {
                            if (isCurrentPage) vm.startLoading()
                        }
                        SuperuserScreen(vm)
                    }
                    Tab.LOG -> {
                        val vm: LogViewModel = viewModel(factory = VMFactory)
                        LaunchedEffect(isCurrentPage) {
                            if (isCurrentPage) vm.startLoading()
                        }
                        LogScreen(vm)
                    }
                    Tab.MODULES -> {
                        val vm: ModuleViewModel = viewModel(factory = VMFactory)
                        LaunchedEffect(isCurrentPage) {
                            if (isCurrentPage) vm.startLoading()
                        }
                        CollectNavEvents(vm, navigator)
                        ModuleScreen(
                            viewModel = vm,
                            onRegisterFab = { moduleFabAction = it },
                            contentFocusRequester = moduleContentFocusRequester,
                        )
                    }
                    Tab.SETTINGS -> {
                        val vm: SettingsViewModel = viewModel(factory = VMFactory)
                        LaunchedEffect(onAuthenticate) {
                            if (onAuthenticate != null) {
                                vm.authenticate = onAuthenticate
                            }
                        }
                        CollectNavEvents(vm, navigator)
                        SettingsScreen(vm)
                    }
                }
            }
        }
    }

    val phoneNavigation: @Composable () -> Unit = {
        ShortNavigationBar {
            visibleTabs.forEachIndexed { index, tab ->
                val isModulesItem = tab == Tab.MODULES
                ShortNavigationBarItem(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    modifier = Modifier
                        .focusRequester(tabFocusRequesters[index])
                        .focusProperties {
                            if (isModulesTab && isModulesItem) {
                                up = fabFocusRequester
                            }
                        },
                    icon = {
                        Icon(
                            imageVector = ImageVector.vectorResource(tab.iconRes),
                            contentDescription = stringResource(tab.titleRes),
                        )
                    },
                    label = { Text(stringResource(tab.titleRes)) },
                )
            }
        }
    }

    if (tv) {
        Row(modifier = modifier.fillMaxSize()) {
            NavigationRail(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(148.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Spacer(Modifier.height(24.dp))
                visibleTabs.forEachIndexed { index, tab ->
                    val selected = pagerState.currentPage == index
                    NavigationRailItem(
                        selected = selected,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .fillMaxWidth()
                            .focusRequester(tabFocusRequesters[index])
                            .tvFocusFrame(
                                enabled = true,
                                shape = RoundedCornerShape(24.dp),
                            ),
                        icon = {
                            Icon(
                                imageVector = ImageVector.vectorResource(tab.iconRes),
                                contentDescription = stringResource(tab.titleRes),
                                modifier = Modifier.size(30.dp),
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(tab.titleRes),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        alwaysShowLabel = true,
                    )
                }
            }

            Scaffold(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                floatingActionButton = moduleFab,
                content = pagerContent,
            )
        }
    } else {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = phoneNavigation,
            floatingActionButton = moduleFab,
            content = pagerContent,
        )
    }
}
