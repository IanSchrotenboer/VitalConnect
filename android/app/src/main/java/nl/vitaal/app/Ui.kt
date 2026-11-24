package nl.vitaal.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val TABS = listOf(
    Triple(Tab.Today, "Vandaag", VIcons.Today),
    Triple(Tab.Health, "Gezondheid", VIcons.Health),
    Triple(Tab.Sleep, "Slaap", VIcons.Moon),
)

/**
 * Hoofdscherm: inloggen, kennismaking, of de tabbladen Vandaag, Gezondheid en Slaap.
 * Op telefoons staan de tabbladen onderaan, op brede schermen (opengevouwen foldables, tablets) aan de zijkant.
 */
@Composable
fun VitaalRoot(
    vm: MainViewModel,
    onRequestHc: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onGoogleSignIn: () -> Unit,
    onShare: (String) -> Unit,
    onSignOut: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val p = LocalPalette.current
    val width = currentWidthClass()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }

    val inAuth = !vm.signedIn || vm.authStep !is AuthStep.Login || vm.needsConsent || vm.onboarding != null
    val overlay = vm.editing || vm.detailMetric != null || vm.showSettings
    val showTabs = !inAuth && !overlay

    BackHandler(enabled = !inAuth && overlay) {
        when {
            vm.detailMetric != null -> vm.closeDetail()
            vm.editing -> vm.editing = false
            else -> vm.showSettings = false
        }
    }
    BackHandler(enabled = showTabs && vm.tab != Tab.Today) { vm.tab = Tab.Today }
    BackHandler(enabled = vm.onboarding == Onboarding.Profile) { vm.onboarding = Onboarding.Import }

    CompositionLocalProvider(LocalWidthClass provides width) {
        Scaffold(
            containerColor = p.bg,
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                if (showTabs && !width.wide) NavigationBar(containerColor = p.card, tonalElevation = 0.dp) {
                    TABS.forEach { (tab, label, icon) ->
                        NavigationBarItem(
                            selected = vm.tab == tab,
                            onClick = { vm.tab = tab },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = p.primary, selectedTextColor = p.primary,
                                unselectedIconColor = p.muted, unselectedTextColor = p.muted,
                                indicatorColor = p.primary.copy(alpha = 0.12f),
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            Row(Modifier.padding(pad).fillMaxSize()) {
                if (showTabs && width.wide) NavigationRail(containerColor = p.card, modifier = Modifier.fillMaxHeight()) {
                    TABS.forEach { (tab, label, icon) ->
                        NavigationRailItem(
                            selected = vm.tab == tab,
                            onClick = { vm.tab = tab },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = p.primary, selectedTextColor = p.primary,
                                unselectedIconColor = p.muted, unselectedTextColor = p.muted,
                                indicatorColor = p.primary.copy(alpha = 0.12f),
                            ),
                        )
                    }
                }
                // Inhoud gecentreerd en begrensd, zodat het ook op een opengevouwen scherm rustig leest
                val max = if (inAuth || overlay) FormMaxWidth else DashboardMaxWidth
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = max).fillMaxSize()) {
                        when {
                            vm.authStep is AuthStep.NewPassword -> NewPasswordScreen(vm)
                            vm.authStep is AuthStep.Code -> CodeScreen(vm, vm.authStep as AuthStep.Code)
                            !vm.signedIn -> LoginScreen(vm, onGoogleSignIn, onOpenUrl)
                            vm.needsConsent -> ConsentScreen(vm, onSignOut)
                            vm.onboarding == Onboarding.Import -> ImportScreen(vm, onRequestHc, onOpenUrl)
                            vm.onboarding == Onboarding.Profile -> OnboardingProfileScreen(vm)
                            vm.editing -> EditScreen(vm)
                            vm.detailMetric != null -> DetailScreen(vm)
                            vm.showSettings -> ProfileScreen(vm, onRequestHc, onOpenUrl, onShare, onSignOut, onRequestNotifications)
                            vm.tab == Tab.Health -> HealthScreen(vm)
                            vm.tab == Tab.Sleep -> SleepScreen(vm)
                            else -> TodayScreen(vm)
                        }
                    }
                }
            }
        }
    }
}
