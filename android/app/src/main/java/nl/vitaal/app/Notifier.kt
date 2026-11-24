package nl.vitaal.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Instellingen van dit toestel: hoe vaak synchroniseren en welke meldingen. */
class DeviceSettings(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("vitaal_ui", Context.MODE_PRIVATE)

    /** Minuten tussen twee synchronisaties op de achtergrond; 0 = alleen als je de app opent of vernieuwt. */
    var syncMinutes: Int
        get() = prefs.getInt(SYNC, 60)
        set(v) = prefs.edit().putInt(SYNC, v).apply()
    var notifyGoals: Boolean
        get() = prefs.getBoolean(GOALS, true)
        set(v) = prefs.edit().putBoolean(GOALS, v).apply()
    var notifyStatus: Boolean
        get() = prefs.getBoolean(STATUS, false)
        set(v) = prefs.edit().putBoolean(STATUS, v).apply()
    /** Google Health gekoppeld (bijgewerkt bij elke keer laden), zodat de achtergrondtaak geen extra verzoek nodig heeft. */
    var googleConnected: Boolean
        get() = prefs.getBoolean(GOOGLE, false)
        set(v) = prefs.edit().putBoolean(GOOGLE, v).apply()
    /** Laatste keer dat de gegevens zijn bijgewerkt (achtergrond of app). */
    var lastRun: Long
        get() = prefs.getLong(LAST_RUN, 0)
        set(v) = prefs.edit().putLong(LAST_RUN, v).apply()

    /** Doelen waarvoor vandaag al een melding is gestuurd (één melding per doel per dag). */
    fun notifiedToday(): Set<String> =
        if (prefs.getString(NOTIFIED_DAY, null) == LocalDate.now().toString()) prefs.getStringSet(NOTIFIED, emptySet()).orEmpty() else emptySet()

    fun markNotified(metrics: Set<String>) {
        prefs.edit().putString(NOTIFIED_DAY, LocalDate.now().toString())
            .putStringSet(NOTIFIED, notifiedToday() + metrics).apply()
    }

    private companion object {
        const val SYNC = "sync_minutes"
        const val GOALS = "notify_goals"
        const val STATUS = "notify_status"
        const val NOTIFIED = "notified_goals"
        const val NOTIFIED_DAY = "notified_day"
        const val LAST_RUN = "last_run"
        const val GOOGLE = "google_connected"
    }
}

/** Keuzes voor het synchronisatie-interval (minuten; 0 = handmatig). Android staat minimaal 15 minuten toe. */
val SYNC_CHOICES = listOf(15 to "15 min", 30 to "30 min", 60 to "1 uur", 180 to "3 uur", 360 to "6 uur", 0 to "Handmatig")

fun syncLabel(minutes: Int): String = when {
    minutes == 0 -> "alleen als je de app opent"
    minutes < 60 -> "elke $minutes minuten"
    minutes == 60 -> "elk uur"
    else -> "elke ${minutes / 60} uur"
}

/** Meldingen: gehaalde doelen, en een stille vaste melding dat de app op de achtergrond meesynchroniseert. */
object Notifier {
    private const val CH_GOALS = "doelen"
    private const val CH_STATUS = "achtergrond"
    private const val ID_STATUS = 1
    private const val ID_GOAL_BASE = 100

    fun allowed(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_GOALS, "Doelen gehaald", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Een melding als je een dagdoel haalt, zoals je stappendoel."
        })
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Status", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Stille melding met wanneer je gegevens voor het laatst zijn bijgewerkt."
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        })
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Werkt de stille melding bij (of haalt hem weg als die uit staat). justSynced: de gegevens zijn net bijgewerkt. */
    fun updateStatus(ctx: Context, justSynced: Boolean = false) {
        val settings = DeviceSettings(ctx)
        if (justSynced) settings.lastRun = System.currentTimeMillis()
        val lastSync = settings.lastRun
        val nm = NotificationManagerCompat.from(ctx)
        if (!settings.notifyStatus || settings.syncMinutes == 0 || !allowed(ctx)) { nm.cancel(ID_STATUS); return }
        channels(ctx)
        // Kort en rustig; Android zet de app-naam er zelf boven
        val title = if (lastSync > 0) "Bijgewerkt om " +
            Instant.ofEpochMilli(lastSync).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) else "Gesynchroniseerd"
        val n = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(syncLabel(settings.syncMinutes).replaceFirstChar { it.uppercase() })
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(ctx))
            .build()
        try { nm.notify(ID_STATUS, n) } catch (_: SecurityException) {}
    }

    /** Bij uitloggen: de vaste melding weghalen. */
    fun clear(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_STATUS)

    /** Eén melding per gehaald dagdoel per dag. lowerIsBetter-metrics (zoals rusthartslag) tellen niet mee. */
    fun checkGoals(ctx: Context, prefs: Collection<Pref>, today: Map<String, DayValue>, metrics: Map<String, MetricInfo>) {
        val settings = DeviceSettings(ctx)
        if (!settings.notifyGoals || !allowed(ctx)) return
        val done = settings.notifiedToday()
        val reached = prefs.filter { p ->
            val goal = p.goal ?: return@filter false
            val m = metrics[p.metric] ?: return@filter false
            (p.visible || p.inRings) && !m.lowerIsBetter && p.metric !in done && (today[p.metric]?.value ?: 0.0) >= goal
        }
        if (reached.isEmpty()) return
        channels(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        reached.forEach { p ->
            val m = metrics.getValue(p.metric)
            val v = today.getValue(p.metric).value
            val unit = unitOf(p.metric, m.unit).let { if (it.isEmpty() && p.metric == "steps") "stappen" else it }
            val n = NotificationCompat.Builder(ctx, CH_GOALS)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Doel gehaald: ${m.name.lowercase()}")
                .setContentText("${fmt(p.metric, v)} $unit vandaag. Mooi werk!".replace("  ", " "))
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build()
            try { nm.notify(ID_GOAL_BASE + p.metric.hashCode().mod(1000), n) } catch (_: SecurityException) {}
        }
        settings.markNotified(reached.map { it.metric }.toSet())
    }

    /** Voor de achtergrondtaak: één verzoek (app_state voor vandaag, zonder statistieken) en melden wat gehaald is. */
    suspend fun checkGoalsFromServer(ctx: Context, api: Api) {
        if (!DeviceSettings(ctx).notifyGoals || !allowed(ctx)) return
        val day = LocalDate.now()
        val st = api.appState(day, day, withStats = false)
        checkGoals(ctx, st.prefs.values, st.daily.mapNotNull { (m, days) -> days[day]?.let { m to it } }.toMap(), st.metrics)
    }
}
