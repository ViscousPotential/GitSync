package com.viscouspot.gitsync.util

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.viscouspot.gitsync.GitSyncService
import com.viscouspot.gitsync.GitSyncService.Companion.FORCE_SYNC

class NetworkWorker(private val context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        val forceSyncIntent = Intent(context, GitSyncService::class.java)
        forceSyncIntent.setAction(FORCE_SYNC)
        context.startService(forceSyncIntent)
        return Result.success()
    }
}