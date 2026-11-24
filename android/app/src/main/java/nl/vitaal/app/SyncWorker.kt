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
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Synchroniseert elk uur op de achtergrond, als de gebruiker daar toestemming voor gaf. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val api = Api(applicationContext)
        if (!api.isSignedIn) return Result.success()
        if (HealthSync.sdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) return Result.success()

        val sync = HealthSync(applicationContext, api)
        return try {
            if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in sync.granted()) {
                return Result.success() // alleen synchroniseren als de app open is
            }
            val n = sync.sync()
            Log.i(TAG, "Achtergrondsync: $n dagwaarden")
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Netwerkfout, later opnieuw", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Sync mislukt", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "VitaalSync"

        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("health-connect-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
