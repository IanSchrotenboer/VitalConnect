package nl.vitaal.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.health.connect.client.PermissionController

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val hcPermissions: ActivityResultLauncher<Set<String>> =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            vm.onHcPermissionsResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        setContent {
            VitaalTheme {
                VitaalRoot(vm, onRequestHc = ::requestHealthConnect, onOpenUrl = ::openUrl)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshHcStatus()
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "vitaal" && data.host == "garmin") {
            vm.onGarminReturn(data.getQueryParameter("status"))
            intent.data = null
        }
    }

    private fun requestHealthConnect() {
        if (vm.hcStatus == HcStatus.UPDATE_REQUIRED || vm.hcStatus == HcStatus.UNAVAILABLE) {
            openUrl("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
        } else {
            hcPermissions.launch(HealthSync.PERMISSIONS)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            vm.message = "Kon de link niet openen."
        }
    }
}
