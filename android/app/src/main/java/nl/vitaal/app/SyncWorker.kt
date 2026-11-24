package nl.vitaal.app

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Synchroniseert op de achtergrond met het interval dat de gebruiker koos (Profiel → Koppelingen):
 * Health Connect (als daar achtergrondtoegang voor is) en Google Health (als die gekoppeld is).
 * Garmin stuurt nieuwe data zelf na elke sync van het horloge. Daarna: meldingen voor gehaalde doelen
 * en de stille statusmelding bijwerken.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val api = Api(ctx)
        if (!api.isSignedIn) return Result.success()
        val hc = HealthSync(ctx, api)
        return try {
            if (HealthSync.sdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE &&
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in hc.granted()
            ) {
                Log.i(TAG, "Health Connect: ${hc.sync()} dagwaarden")
            }
            // De server haalt de cloudbronnen (Google Health, Suunto) vanuit de achtergrond hooguit eens per 30 minuten op
            if (DeviceSettings(ctx).cloudConnected) runCatching {
                api.function("sync", buildJsonObject { put("background", true) })
            }
            Notifier.checkGoalsFromServer(ctx, api)
            Notifier.updateStatus(ctx, justSynced = true)
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Netwerkfout, later opnieuw")
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Sync mislukt: ${e.javaClass.simpleName}")
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "VitaalSync"
        private const val NAME = "health-connect-sync"

        /** Plant de achtergrondtaak volgens de instelling; 0 minuten = geen achtergrondsync. */
        fun schedule(ctx: Context, replace: Boolean = false) {
            val wm = WorkManager.getInstance(ctx)
            val minutes = DeviceSettings(ctx).syncMinutes
            if (minutes == 0) { wm.cancelUniqueWork(NAME); return }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(maxOf(15, minutes).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
