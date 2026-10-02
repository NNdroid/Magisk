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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.VMFactory
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.model.module.LocalModule
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
    var currentPage by rememberSaveable { mutableIntStateOf(initialPage) }
    val fabFocusRequester = remember { FocusRequester() }
    val moduleContentFocusRequester = remember { FocusRequester() }
    val tabFocusRequesters = remember(visibleTabs) {
        List(visibleTabs.size) { FocusRequester() }
    }
    var moduleFabAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val currentTab = visibleTabs[currentPage]
    val isModulesTab = currentTab == Tab.MODULES
    val modulesNavFocusRequester = visibleTabs.indexOf(Tab.MODULES)
        .takeIf { it >= 0 }
        ?.let(tabFocusRequesters::get)

    LaunchedEffect(currentPage) {
        tabFocusRequesters.getOrNull(currentPage)?.requestFocus()
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
                    .tvFocusFrame(shape = RoundedCornerShape(20.dp))
                    .focusProperties {
                        up = moduleContentFocusRequester
                        modulesNavFocusRequester?.let { left = it }
                        down = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(CoreR.string.module_action_install_external),
                    modifier = Modifier.size(34.dp),
                )
            }
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        NavigationRail(
            modifier = Modifier
                .fillMaxHeight()
                .width(176.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(
                painter = painterResource(CoreR.drawable.ic_magisk_outline),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(CoreR.string.magisk),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
            )

            visibleTabs.forEachIndexed { index, tab ->
                NavigationRailItem(
                    selected = currentPage == index,
                    onClick = { currentPage = index },
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .fillMaxWidth()
                        .focusRequester(tabFocusRequesters[index])
                        .tvFocusFrame(shape = RoundedCornerShape(24.dp)),
                    icon = {
                        Icon(
                            imageVector = ImageVector.vectorResource(tab.iconRes),
                            contentDescription = stringResource(tab.titleRes),
                            modifier = Modifier.size(32.dp),
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
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .focusProperties {
                        left = tabFocusRequesters[currentPage]
                        if (currentTab == Tab.MODULES) {
                            down = fabFocusRequester
                        }
                    }
                    .focusGroup()
            ) {
                when (currentTab) {
                    Tab.HOME -> {
                        val vm: HomeViewModel = viewModel(factory = VMFactory)
                        val installVm: InstallViewModel = viewModel(factory = VMFactory)
                        LaunchedEffect(currentTab) { vm.startLoading() }
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
                        LaunchedEffect(currentTab) { vm.startLoading() }
                        SuperuserScreen(vm)
                    }
                    Tab.LOG -> {
                        val vm: LogViewModel = viewModel(factory = VMFactory)
                        LaunchedEffect(currentTab) { vm.startLoading() }
                        LogScreen(vm)
                    }
                    Tab.MODULES -> {
                        val vm: ModuleViewModel = viewModel(factory = VMFactory)
                        LaunchedEffect(currentTab) { vm.startLoading() }
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
}
