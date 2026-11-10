package app.invilink.xboard.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Intent
import app.invilink.xboard.MainActivity
import app.invilink.xboard.security.AccessStore
import app.invilink.xboard.security.PinLoginResult
import app.invilink.xboard.security.PinResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import kotlin.random.Random

/** Persistent opt-in scheduler; only explicit stealth entry suppresses notifications. */
object UpdateScheduler {
    private const val JOB_ID = 24018
    private const val NOTIFICATION_ID = 24018
    private const val CHANNEL = "app_updates"
    private val mutableAvailableEvents = MutableStateFlow(0L)
    val availableEvents = mutableAvailableEvents.asStateFlow()
    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)
    fun notificationsAllowed(context: Context): Boolean = !prefs(context).getBoolean("stealth", false) &&
        runCatching { !AccessStore(context).primaryPinRequired }.getOrDefault(false)

    @Synchronized fun ifNotificationsAllowed(context: Context, action: () -> Unit) {
        if (notificationsAllowed(context)) action()
    }

    /** Serialize PIN verification with notification posting, including persisted privacy state. */
    @Synchronized fun verifyLogin(context: Context, verify: () -> PinLoginResult): PinLoginResult {
        val result = verify()
        if (result.result == PinResult.Accepted) {
            prefs(context).edit().putBoolean("stealth", result.stealth).commit()
            if (result.stealth) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.cancel(NOTIFICATION_ID)
                manager.cancel(3101) // Connectivity notification, not other app notifications.
            }
        }
        return result
    }
    /** Returns false if Android rejected scheduling. */
    @Synchronized fun setEnabled(context: Context, enabled: Boolean): Boolean {
        prefs(context).edit().putBoolean("enabled", enabled).commit()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!enabled) {
            scheduler.cancel(JOB_ID)
            prefs(context).edit().remove("releaseVersion").commit()
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            return true
        }
        return schedule(context)
    }
    /** Restore a missing job after force-stop, scheduling failure, or process interruption. */
    @Synchronized fun ensureScheduled(context: Context): Boolean {
        if (!isEnabled(context)) return true
        val scheduler = context.getSystemService(JobScheduler::class.java)
        return scheduler.getPendingJob(JOB_ID) != null || schedule(context)
    }

    /** Call when the user explicitly declines an update. Applies to all background prompts for 24h. */
    @Synchronized fun decline(context: Context) {
        prefs(context).edit().putLong("declinedAt", System.currentTimeMillis()).remove("releaseVersion").commit()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
    fun notificationsPermissionGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @Synchronized internal fun schedule(context: Context): Boolean {
        if (!isEnabled(context)) return false
        val now = System.currentTimeMillis()
        val target = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, Random.nextInt(24)); set(Calendar.MINUTE, Random.nextInt(60))
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency((target - now).coerceAtLeast(60_000L))
            .setPersisted(true).build()
        return context.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS
    }
    private fun coolingDown(context: Context): Boolean {
        val declined = prefs(context).getLong("declinedAt", 0L)
        return isUpdateCoolingDown(declined, System.currentTimeMillis())
    }

    @Synchronized internal fun recordAvailable(context: Context, release: UpdateRelease) {
        if (!isEnabled(context) || !notificationsAllowed(context) || coolingDown(context)) return
        prefs(context).edit().putString("releaseVersion", release.version.text)
            .putString("releaseUrl", release.url).putLong("releaseSize", release.size)
            .putString("releaseSha256", release.sha256).commit()
        notifyIfAllowed(context)
        mutableAvailableEvents.value++
    }

    @Synchronized fun takeAvailable(context: Context): UpdateRelease? {
        if (!isEnabled(context) || !notificationsAllowed(context) || coolingDown(context)) return null
        val p = prefs(context)
        val version = SemVersion.parse(p.getString("releaseVersion", null) ?: return null) ?: return null
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() ?: return null
        val current = SemVersion.parseInstalled(installed.versionName ?: "") ?: return null
        p.edit().remove("releaseVersion").commit()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        if (version <= current) return null
        val release = UpdateRelease(version, p.getString("releaseUrl", null) ?: return null,
            p.getLong("releaseSize", 0), p.getString("releaseSha256", null) ?: return null)
        return runCatching {
            validateRelease(release)
            release
        }.getOrNull()
    }

    @Synchronized internal fun notifyIfAllowed(context: Context) {
        if (!isEnabled(context) || !notificationsAllowed(context) || !notificationsPermissionGranted(context) || coolingDown(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Обновления приложения", NotificationManager.IMPORTANCE_DEFAULT))
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 24018, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val deleted = PendingIntent.getBroadcast(context, 24018, Intent(context, UpdateDismissReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Доступно обновление")
            .setContentText("Откройте приложение для проверки обновления")
            .setContentIntent(pending).setDeleteIntent(deleted).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { manager.notify(NOTIFICATION_ID, notification) } catch (_: SecurityException) { }
    }
}

class UpdateDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = UpdateScheduler.decline(context)
}

class UpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        if (!UpdateScheduler.isEnabled(this)) return false
        work = scope.launch {
            try {
                val result = UpdateClient(applicationContext).check()
                if (result is UpdateCheck.Available) UpdateScheduler.recordAvailable(applicationContext, result.release)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Next daily check retries; no private error notification. */ }
            jobFinished(params, false)
            UpdateScheduler.schedule(applicationContext)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel(); work = null
        return UpdateScheduler.isEnabled(this)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
