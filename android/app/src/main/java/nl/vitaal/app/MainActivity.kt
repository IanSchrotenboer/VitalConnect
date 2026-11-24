package nl.vitaal.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val hcPermissions: ActivityResultLauncher<Set<String>> =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            vm.onHcPermissionsResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        setContent {
            VitaalTheme {
                VitaalRoot(
                    vm,
                    onRequestHc = ::requestHealthConnect,
                    onOpenUrl = ::openUrl,
                    onGoogleSignIn = ::googleSignIn,
                    onShare = ::shareText,
                    onSignOut = ::signOut,
                )
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

    /** vitaal://garmin?status=… en vitaal://google?status=… na het koppelen in de browser. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "vitaal") return
        when (data.host) {
            "garmin" -> vm.onConnectReturn("garmin", data.getQueryParameter("status"))
            "google" -> vm.onConnectReturn("google_health", data.getQueryParameter("status"))
            else -> return
        }
        intent.data = null
    }

    private fun googleSignIn() {
        lifecycleScope.launch {
            try {
                val (idToken, nonce) = GoogleSignIn.idToken(this@MainActivity)
                vm.signInWithGoogle(idToken, nonce)
            } catch (_: GoogleSignIn.Cancelled) {
            } catch (e: Exception) {
                vm.message = e.message ?: "Inloggen met Google is mislukt."
            }
        }
    }

    private fun signOut() {
        vm.signOut()
        lifecycleScope.launch { GoogleSignIn.clear(this@MainActivity) }
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

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "Mijn Vital Connect-data")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Mijn data exporteren"))
    }
}
