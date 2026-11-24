package nl.vitaal.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Hoofdscherm: inloggen, of de tabbladen Vandaag, Gezondheid en Slaap. */
@Composable
fun VitaalRoot(
    vm: MainViewModel,
    onRequestHc: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onGoogleSignIn: () -> Unit,
    onShare: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val p = LocalPalette.current
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }

    val inAuth = !vm.signedIn || vm.authStep !is AuthStep.Login || vm.needsConsent
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

    Scaffold(
        containerColor = p.bg,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (showTabs) NavigationBar(containerColor = p.card, tonalElevation = 0.dp) {
                listOf(
                    Triple(Tab.Today, "Vandaag", VIcons.Today),
                    Triple(Tab.Health, "Gezondheid", VIcons.Health),
                    Triple(Tab.Sleep, "Slaap", VIcons.Moon),
                ).forEach { (tab, label, icon) ->
                    NavigationBarItem(
                        selected = vm.tab == tab,
                        onClick = { vm.tab = tab },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
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
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                vm.authStep is AuthStep.NewPassword -> NewPasswordScreen(vm)
                vm.authStep is AuthStep.Code -> CodeScreen(vm, vm.authStep as AuthStep.Code)
                !vm.signedIn -> LoginScreen(vm, onGoogleSignIn)
                vm.needsConsent -> ConsentScreen(vm, onSignOut)
                vm.editing -> EditScreen(vm)
                vm.detailMetric != null -> DetailScreen(vm)
                vm.showSettings -> ProfileScreen(vm, onRequestHc, onOpenUrl, onShare, onSignOut)
                vm.tab == Tab.Health -> HealthScreen(vm)
                vm.tab == Tab.Sleep -> SleepScreen(vm)
                else -> TodayScreen(vm)
            }
        }
    }
}
