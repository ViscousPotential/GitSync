package com.viscouspot.gitsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.viscouspot.gitsync.util.GitManager
import com.viscouspot.gitsync.util.Helper
import com.viscouspot.gitsync.util.Helper.CONFLICT_NOTIFICATION_ID
import com.viscouspot.gitsync.util.LogType
import com.viscouspot.gitsync.util.Logger.log
import com.viscouspot.gitsync.util.NetworkWorker
import com.viscouspot.gitsync.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

class GitSyncService : Service() {
    private lateinit var fileObserver: FileObserver
    private lateinit var gitManager: GitManager
    private lateinit var settingsManager: SettingsManager

    private var isScheduled: Boolean = false
    private var isSyncing: Boolean = false
    private val debouncePeriod: Long = 10 * 1000

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "git_sync_service_channel"
        const val NOTIFICATION_CHANNEL_NAME = "Git Sync Service"
        const val MERGE = "MERGE"
        const val FORCE_SYNC = "FORCE_SYNC"
        const val APPLICATION_SYNC = "APPLICATION_SYNC"
        const val INTENT_SYNC = "INTENT_SYNC"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == null) {
            return START_STICKY
        }

        when (intent.action) {
            MERGE -> {
                log(LogType.ToServiceCommand, "Merge")
                merge()
            }
            FORCE_SYNC -> {
                log(LogType.ToServiceCommand, "Force Sync")
                debouncedSync(forced = true)
            }
            APPLICATION_SYNC -> {
                log(LogType.ToServiceCommand, "AccessibilityService Sync")
                debouncedSync()
            }
            INTENT_SYNC -> {
                log(LogType.ToServiceCommand, "Intent Sync")
                debouncedSync()
            }
        }

        return START_STICKY
    }


    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        gitManager = GitManager(this, settingsManager)

        startForegroundService()
    }

    private fun startForegroundService() {
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN
            )
        } else {
            TODO("VERSION.SDK_INT < O")
        }
        val manager = this.getSystemService(
            NotificationManager::class.java
        )
        manager?.createNotificationChannel(channel)

        val buttonIntent = Intent(this, GitSyncService::class.java).apply {
            action = FORCE_SYNC
        }
        val buttonPendingIntent = PendingIntent.getService(this, Random.nextInt(0, 100), buttonIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.gitsync_notif)
            .addAction(NotificationCompat.Action(null, getString(R.string.force_sync), buttonPendingIntent))
            .build()

        startForeground(1, notification)
    }

    private fun scheduleNetworkSync() {
        log(LogType.Sync, "Scheduling sync for network regained")
        val constraints: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workRequest = OneTimeWorkRequest.Builder(NetworkWorker::class.java)
            .setConstraints(constraints).build()

        WorkManager.getInstance(this).enqueueUniqueWork("networkScheduledSync", ExistingWorkPolicy.KEEP, workRequest)
        return
    }

    private fun debouncedSync(forced: Boolean = false) {
        if (!Helper.isNetworkAvailable(this, getString(R.string.network_unavailable))) {
            scheduleNetworkSync()
        }
        if (isScheduled) {
            return
        } else {
            if (isSyncing) {
                isScheduled = true
                log(LogType.Sync, "Sync Scheduled")
                return
            } else {
                sync(forced)
            }
        }
    }

    private fun sync(forced: Boolean = false) {
        if (gitManager.getConflicting(settingsManager.getGitDirUri()).isNotEmpty()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    applicationContext,
                    "Ongoing merge conflict",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        log(LogType.Sync, "Start Sync")
        isSyncing = true

        val job = CoroutineScope(Dispatchers.Default).launch {
            val gitDirUri = settingsManager.getGitDirUri()

            if (gitDirUri == null) {
                withContext(Dispatchers.Main) {
                    log(LogType.Sync, "Repository Not Found")
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.repository_not_found),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            var synced = false

            log(LogType.Sync, "Start Pull Repo")
            val pullResult = gitManager.downloadChanges(
                gitDirUri,
                ::scheduleNetworkSync,
            ) {
                synced = true
                displaySyncMessage(getString(R.string.sync_start_pull))
            }

            when (pullResult) {
                null -> {
                    log(LogType.Sync, "Pull Repo Failed")
                    return@launch
                }
                true -> log(LogType.Sync, "Pull Complete")
                false -> log(LogType.Sync, "Pull Not Required")
            }

            while (File(Helper.getPathFromUri(applicationContext, gitDirUri),
                    getString(R.string.git_lock_path)).exists()) {
                delay(1000)
            }

            log(LogType.Sync, "Start Push Repo")
            val pushResult = gitManager.uploadChanges(
                gitDirUri,
                settingsManager.getSyncMessage(),
                ::scheduleNetworkSync,
            ) {
                if (!synced) {
                    displaySyncMessage(getString(R.string.sync_start_push))
                }
            }

            when (pushResult) {
                null -> {
                    log(LogType.Sync, "Push Repo Failed")
                    return@launch
                }
                true -> log(LogType.Sync, "Push Complete")
                false -> log(LogType.Sync, "Push Not Required")
            }

            while (File(Helper.getPathFromUri(applicationContext, gitDirUri), getString(R.string.git_lock_path)).exists()) {
                delay(1000)
            }

            if (!(pushResult || pullResult)) {
                if (forced) {
                    displaySyncMessage(getString(R.string.sync_not_required))
                }
                return@launch
            } else {
                displaySyncMessage(getString(R.string.sync_complete))
            }

        }

        job.invokeOnCompletion {
            log(LogType.Sync, "Sync Complete")

            val intent = Intent(MainActivity.REFRESH)
            LocalBroadcastManager.getInstance(this@GitSyncService).sendBroadcast(intent)

            isSyncing = false
            if (isScheduled) {
                CoroutineScope(Dispatchers.Default).launch {
                    delay(debouncePeriod)
                    log(LogType.Sync, "Scheduled Sync Starting")
                    isScheduled = false
                    sync()
                }
            }
        }
    }

    private fun merge() {
        CoroutineScope(Dispatchers.Default).launch {
            val authCredentials = settingsManager.getGitAuthCredentials()
            if (settingsManager.getGitDirUri() == null || authCredentials.first == "" || authCredentials.second == "" || settingsManager.getGitSshPrivateKey() == "") return@launch

            val pushResult = gitManager.uploadChanges(
                settingsManager.getGitDirUri()!!,
                settingsManager.getSyncMessage(),
                ::scheduleNetworkSync,
            ) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.resolving_merge),
                        Toast.LENGTH_SHORT
                    )
                        .show()
                }
            }

            when (pushResult) {
                null -> {
                    log(LogType.Sync, "Merge Failed")
                    return@launch
                }

                true -> log(LogType.Sync, "Merge Complete")
                false -> log(LogType.Sync, "Merge Not Required")
            }

            debouncedSync(forced = true)

            val intent = Intent(MainActivity.MERGE_COMPLETE)
            LocalBroadcastManager.getInstance(this@GitSyncService).sendBroadcast(intent)

            NotificationManagerCompat.from(applicationContext).cancel(CONFLICT_NOTIFICATION_ID)
        }
    }

    private fun displaySyncMessage(msg: String) {
        if (settingsManager.getSyncMessageEnabled()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            fileObserver.stopWatching()
        } catch (e: Exception) { e.printStackTrace() }
    }
}