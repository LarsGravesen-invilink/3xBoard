package app.invilink.xboard.panels

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal object PanelSyncScheduler {
    private const val JOB_ID = 24017
    @Volatile private var authenticatedUntil = 0L

    val allowed: Boolean
        get() = false

    fun allowUntil(deadline: Long) {
        authenticatedUntil = deadline
    }

    fun cancel(context: Context) {
        authenticatedUntil = 0L
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    fun schedule(context: Context) {
        if (!allowed) return
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, PanelSyncService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        scheduler.schedule(job)
    }
}

class PanelSyncService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!PanelSyncScheduler.allowed) return false
        work = scope.launch {
            val connectivity = ConnectivityObserver(applicationContext)
            try {
                if (!PanelSyncScheduler.allowed) {
                    jobFinished(params, false)
                    return@launch
                }
                if (!connectivity.online.value) {
                    jobFinished(params, true)
                    return@launch
                }
                val panels = PanelStore(applicationContext).load()
                if (!PanelSyncScheduler.allowed) {
                    jobFinished(params, false)
                    return@launch
                }
                val result = OfflinePanelRepository(applicationContext, PanelApi()).synchronize(panels)
                jobFinished(params, PanelSyncScheduler.allowed && result.pending > 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                jobFinished(params, PanelSyncScheduler.allowed)
            } finally {
                connectivity.close()
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        work = null
        return PanelSyncScheduler.allowed
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
