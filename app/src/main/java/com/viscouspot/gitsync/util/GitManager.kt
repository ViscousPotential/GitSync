package com.viscouspot.gitsync.util

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.widget.Toast
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.viscouspot.gitsync.R
import com.viscouspot.gitsync.ui.adapter.Commit
import com.viscouspot.gitsync.util.Helper.sendCheckoutConflictNotification
import com.viscouspot.gitsync.util.Logger.log
import com.viscouspot.gitsync.util.provider.GitProviderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.api.errors.WrongRepositoryStateException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.errors.CheckoutConflictException
import org.eclipse.jgit.errors.NotSupportedException
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.internal.JGitText
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.BatchingProgressMonitor
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.lib.StoredConfig
import org.eclipse.jgit.merge.ResolveMerger
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.JschConfigSessionFactory
import org.eclipse.jgit.transport.OpenSshConfig
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GitManager(private val context: Context, private val settingsManager: SettingsManager) {
    private fun applyCredentials(command: TransportCommand<*, *>) {
        log(settingsManager.getGitProvider())
        if (settingsManager.getGitProvider() == GitProviderManager.Companion.Provider.SSH) {
            val sshSessionFactory = object : JschConfigSessionFactory() {
                override fun configure(host: OpenSshConfig.Host, session: Session) {
                    session.setConfig("StrictHostKeyChecking", "no")
                    session.setConfig("PreferredAuthentications", "publickey,password")
                    session.setConfig("kex", "diffie-hellman-group-exchange-sha256")
                }

                override fun createDefaultJSch(fs: org.eclipse.jgit.util.FS?): JSch {
                    val jsch = super.createDefaultJSch(fs)
                    jsch.addIdentity("key", settingsManager.getGitSshPrivateKey().toByteArray(), null, null)
                    return jsch
                }
            }

            SshSessionFactory.setInstance(sshSessionFactory)
            command.setTransportConfigCallback { transport ->
                if (transport is SshTransport) {
                    transport.sshSessionFactory = sshSessionFactory
                }
            }
        } else {
            val authCredentials = settingsManager.getGitAuthCredentials()
            command.setCredentialsProvider(UsernamePasswordCredentialsProvider(authCredentials.first, authCredentials.second))
        }
    }

    fun cloneRepository(repoUrl: String, userStorageUri: Uri, taskCallback: (action: String) -> Unit, progressCallback: (progress: Int) -> Unit, failureCallback: (error: String) -> Unit, successCallback: () -> Unit) {
        if (!Helper.isNetworkAvailable(context)) {
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                log(LogType.CloneRepo, "Cloning Repo")

                val monitor = object : BatchingProgressMonitor() {
                    override fun onUpdate(taskName: String?, workCurr: Int) { }

                    override fun onUpdate(
                        taskName: String?,
                        workCurr: Int,
                        workTotal: Int,
                        percentDone: Int,
                    ) {
                        taskCallback(taskName ?: "")
                        progressCallback(percentDone)
                    }

                    override fun onEndTask(taskName: String?, workCurr: Int) { }

                    override fun onEndTask(
                        taskName: String?,
                        workCurr: Int,
                        workTotal: Int,
                        percentDone: Int
                    ) { }
                }

                Git.cloneRepository().apply {
                    setURI(repoUrl)
                    setProgressMonitor(monitor)
                    setDirectory(File(Helper.getPathFromUri(context, userStorageUri)))
                    applyCredentials(this)
                }.call()

                log(LogType.CloneRepo, "Repository cloned successfully")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Repository cloned successfully", Toast.LENGTH_SHORT).show()
                }

                successCallback.invoke()
            } catch (e: InvalidRemoteException) {
                failureCallback(context.getString(R.string.invalid_remote))
                return@launch
            } catch (e: TransportException) {
                e.printStackTrace()
                log(e)
                log(e.localizedMessage)
                log(e.cause)
                failureCallback(e.localizedMessage ?: context.getString(R.string.clone_failed))
                return@launch
            } catch (e: GitAPIException) {
                e.printStackTrace()
                log(e)
                log(e.localizedMessage)
                log(e.cause)
                failureCallback(context.getString(R.string.clone_failed))
                return@launch
            }
            catch (e: JGitInternalException) {
                if (e.cause is NotSupportedException) {
                    failureCallback(context.getString(R.string.invalid_remote))
                } else {
                    failureCallback(e.localizedMessage ?: context.getString(R.string.clone_failed))
                }
                return@launch
            } catch (e: NullPointerException) {
                if (e.message?.contains("Inflater has been closed") == true) {
                    failureCallback(context.getString(R.string.large_file))
                    return@launch
                }

                log(context, LogType.CloneRepo, e)
            } catch (e: OutOfMemoryError) {
                failureCallback(context.getString(R.string.out_of_memory))
                return@launch
            } catch (e: Throwable) {
                failureCallback(context.getString(R.string.clone_failed))

                log(context, LogType.CloneRepo, e)
            }
        }
    }

    fun downloadChanges(userStorageUri: Uri, scheduleNetworkSync: () -> Unit, onSync: () -> Unit): Boolean? {
        if (conditionallyScheduleNetworkSync(scheduleNetworkSync)) {
            return null
        }
        try {
            var returnResult: Boolean? = false
            log(LogType.PullFromRepo, "Getting local directory")
            val repo = FileRepository("${Helper.getPathFromUri(context, userStorageUri)}/${context.getString(R.string.git_path)}")
            val git = Git(repo)

            log(LogType.PullFromRepo, "Fetching changes")
            val fetchResult = git.fetch().apply {
                applyCredentials(this)
            }.call()

            if (conditionallyScheduleNetworkSync(scheduleNetworkSync)) {
                return null
            }

            val localHead: ObjectId = repo.resolve(Constants.HEAD)
            val remoteHead: ObjectId = repo.resolve(Constants.FETCH_HEAD)

            if (!fetchResult.trackingRefUpdates.isEmpty() || !localHead.equals(remoteHead)) {
                log(LogType.PullFromRepo, "Pulling changes")
                onSync.invoke()
                val result = git.pull().apply {
                    applyCredentials(this)
                    remote = "origin"
                }.call()

                if (result.mergeResult.failingPaths != null && result.mergeResult.failingPaths.containsValue(
                        ResolveMerger.MergeFailureReason.DIRTY_WORKTREE)) {
                    log(LogType.PullFromRepo, "Merge conflict")
                    return false
                }

                if (!result.mergeResult.mergeStatus.isSuccessful) {
                    log(LogType.PullFromRepo, "Checkout conflict")
                    sendCheckoutConflictNotification(context)
                    return null
                }

                returnResult = if (result.isSuccessful()) {
                    true
                } else {
                    null
                }
            }

            log(LogType.PullFromRepo, "Closing repository")
            closeRepo(repo)

            return returnResult
        } catch (e: CheckoutConflictException) {
            log(LogType.PullFromRepo, e.stackTraceToString())
            return false
        } catch (e: WrongRepositoryStateException) {
            if (e.message?.contains(context.getString(R.string.merging_exception_message)) == true) {
                log(LogType.PullFromRepo, "Merge conflict")
                return false
            }
            log(context, LogType.PullFromRepo, e)
            return null
        } catch (e: TransportException) {
            handleTransportException(e, scheduleNetworkSync)
        } catch (e: Throwable) {
            log(context, LogType.PullFromRepo, e)
        }
        return null
    }

    fun uploadChanges(userStorageUri: Uri, syncMessage: String, scheduleNetworkSync: () -> Unit, onSync: () -> Unit): Boolean? {
        if (conditionallyScheduleNetworkSync(scheduleNetworkSync)) {
            return null
        }
        try {
            var returnResult = false
            log(LogType.PushToRepo, "Getting local directory")

            val repo = FileRepository("${Helper.getPathFromUri(context, userStorageUri)}/${context.getString(R.string.git_path)}")
            val git = Git(repo)

            logStatus(git)
            val status = git.status().call()

            if (status.uncommittedChanges.isNotEmpty() || status.untracked.isNotEmpty()) {
                onSync.invoke()

                log(LogType.PushToRepo, "Adding Files to Stage")

                git.add().apply {
                    addFilepattern(".")
                }.call()

                git.add().apply {
                    addFilepattern(".")
                    isUpdate = true
                }.call()

                log(LogType.PushToRepo, "Getting current time")

                val formattedDate: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())

                log(LogType.PushToRepo, "Committing changes")
                val config: StoredConfig = git.repository.config
                val committerEmail = config.getString("user", null, "email")
                var committerName = config.getString("user", null, "name")
                if (committerName == null || committerName.equals("")) {
                    committerName = settingsManager.getGitAuthCredentials().first
                }

                git.commit().apply {
                    setCommitter(committerName, committerEmail ?: "")
                    message = syncMessage.format(formattedDate)
                }.call()

                returnResult = true
            }

            if (conditionallyScheduleNetworkSync(scheduleNetworkSync)) {
                return null
            }

            log(LogType.PushToRepo, "Pushing changes")
            val pushResults = git.push().apply {
                applyCredentials(this)
                remote = "origin"
            }.call()
            for (pushResult in pushResults) {
                for (remoteUpdate in pushResult.remoteUpdates) {
                    when (remoteUpdate.status) {
                        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> {
                            log(LogType.PushToRepo, "Attempting rebase on REJECTED_NONFASTFORWARD")
                            logStatus(git)
                            val trackingStatus = BranchTrackingStatus.of(git.repository, git.repository.branch)
                                ?: throw Exception(context.getString(R.string.auto_rebase_failed_exception))

                            if (git.repository.repositoryState == RepositoryState.MERGING || git.repository.repositoryState == RepositoryState.MERGING_RESOLVED) {
                                log(LogType.PushToRepo, "Aborting previous merge to ensure clean state for rebase")
                                git.rebase().apply  {
                                    setOperation(RebaseCommand.Operation.ABORT)
                                }.call()
                            }

                            val rebaseResult = git.rebase().apply  {
                                setUpstream(trackingStatus.remoteTrackingBranch)
                            }.call()

                            logStatus(git)

                            if (!rebaseResult.status.isSuccessful) {
                                git.rebase().apply  {
                                    setOperation(RebaseCommand.Operation.ABORT)
                                }.call()

                                downloadChanges(userStorageUri, scheduleNetworkSync, onSync)
                                return false
                            }
                            break
                        }
                        RemoteRefUpdate.Status.NON_EXISTING -> {
                            throw Exception(context.getString(R.string.non_existing_exception))
                        }
                        RemoteRefUpdate.Status.REJECTED_NODELETE -> {
                            throw Exception(context.getString(R.string.rejected_nodelete_exception))
                        }
                        RemoteRefUpdate.Status.REJECTED_OTHER_REASON -> {
                            val reason = remoteUpdate.message
                            throw Exception(if (reason == null || reason == "") context.getString(R.string.rejected_exception) else context.getString(
                                R.string.rejection_with_reason_exception
                            ).format(reason))
                        }
                        RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED -> {
                            throw Exception(context.getString(R.string.remote_changed_exception))
                        }
                        else -> {}
                    }
                }
            }

            if (Looper.myLooper() == null) {
                Looper.prepare()
            }

            logStatus(git)

            log(LogType.PushToRepo, "Closing repository")
            closeRepo(repo)

            return returnResult
        } catch (e: TransportException) {
            handleTransportException(e, scheduleNetworkSync)
        } catch (e: Throwable) {
            log(context, LogType.PushToRepo, e)
        }
        return null
    }

    private fun conditionallyScheduleNetworkSync(scheduleNetworkSync: () -> Unit): Boolean {
        if (!Helper.isNetworkAvailable(context)) {
            scheduleNetworkSync()
            return true
        }
        return false
    }

    private fun handleTransportException(e: TransportException, scheduleNetworkSync: () -> Unit) {
        if (listOf(
            JGitText.get().connectionFailed,
            JGitText.get().connectionTimeOut,
            JGitText.get().transactionAborted,
            JGitText.get().cannotOpenService
        ).any{ e.message.toString().contains(it) } ) {
            scheduleNetworkSync.invoke()
            return
        }

        var message = e.message.toString()
        if (listOf(
            JGitText.get().authenticationNotSupported,
            JGitText.get().notAuthorized,
        ).any {
            message = it
            e.message.toString().contains(it)
        }) {
            log(context, LogType.TransportException, Throwable(message))
            return
        }

        log(context, LogType.TransportException, e)
    }

    private fun logStatus(git: Git) {
        val status = git.status().call()
        log(LogType.GitStatus, """
            HasUncommittedChanges: ${status.hasUncommittedChanges()}
            Missing: ${status.missing}
            Modified: ${status.modified}
            Removed: ${status.removed}
            IgnoredNotInIndex: ${status.ignoredNotInIndex}
            Changed: ${status.changed}
            Untracked: ${status.untracked}
            Added: ${status.added}
            Conflicting: ${status.conflicting}
            UncommittedChanges: ${status.uncommittedChanges}
        """.trimIndent())
    }

    fun getRecentCommits(gitDirPath: String?): List<Commit> {
        try {
            if (gitDirPath == null || !File("$gitDirPath/${context.getString(R.string.git_path)}").exists()) return listOf()

            log(LogType.RecentCommits, ".git folder found")

            val repo = FileRepository("$gitDirPath/${context.getString(R.string.git_path)}")
            val revWalk = RevWalk(repo)

            val localHead = repo.resolve(Constants.HEAD)
            revWalk.markStart(revWalk.parseCommit(localHead))
            log(LogType.RecentCommits, "HEAD parsed")

            revWalk.sort(RevSort.COMMIT_TIME_DESC)

            val commits = mutableListOf<Commit>()
            var count = 0
            val iterator = revWalk.iterator()

            while (iterator.hasNext() && count < 10) {
                val commit = iterator.next()

                val diffFormatter = DiffFormatter(DisabledOutputStream.INSTANCE)
                diffFormatter.setRepository(repo)
                val parent = if (commit.parentCount > 0) commit.getParent(0) else null
                val diffs = if (parent != null) diffFormatter.scan(parent.tree, commit.tree) else listOf()

                var additions = 0
                var deletions = 0
                for (diff in diffs) {
                    val editList = diffFormatter.toFileHeader(diff).toEditList()
                    for (edit in editList) {
                        additions += edit.endB - edit.beginB
                        deletions += edit.endA - edit.beginA
                    }
                }

                commits.add(
                    Commit(
                        commit.shortMessage,
                        commit.authorIdent.name,
                        commit.authorIdent.`when`.time,
                        commit.name.substring(0, 7),
                        additions,
                        deletions
                    )
                )
                count++
            }

            log(LogType.RecentCommits, "Recent commits retrieved")
            revWalk.dispose()
            closeRepo(repo)

            return commits
        } catch (e: java.lang.Exception) {
            log(context, LogType.RecentCommits, e)
        }
        return listOf()
    }

    fun getConflicting(gitDirUri: Uri?): MutableList<String> {
        if (gitDirUri == null) return mutableListOf()

        val repo = FileRepository("${Helper.getPathFromUri(context, gitDirUri)}/${context.getString(R.string.git_path)}")
        val git = Git(repo)
        val status = git.status().call()
        return status.conflicting.toMutableList()
    }

    fun abortMerge(gitDirUri: Uri?) {
        if (gitDirUri == null) return
        val gitDirPath = Helper.getPathFromUri(context, gitDirUri)

        try {
            val repo = FileRepository("$gitDirPath/${context.getString(R.string.git_path)}")
            val git = Git(repo)

            val mergeHeadFile = File("$gitDirPath/${context.getString(R.string.git_merge_head_path)}")
            if (mergeHeadFile.exists()) {
                git.reset().apply  {
                    setMode(ResetCommand.ResetType.HARD)
                }.call()

                val mergeMsgFile = File("$gitDirPath/${context.getString(R.string.git_merge_msg_path)}")
                if (mergeMsgFile.exists()) {
                    mergeMsgFile.delete()
                }
                if (mergeHeadFile.exists()) {
                    mergeHeadFile.delete()
                }
            }

            log(LogType.AbortMerge, "Merge successful")
        } catch (e: IOException) {
            log(context, LogType.AbortMerge, e)
        } catch (e: GitAPIException) {
            log(context, LogType.AbortMerge, e)
        }
    }

    fun readGitignore(gitDirPath: String): String {
        if (!File("$gitDirPath/${context.getString(R.string.gitignore_path)}").exists()) return ""

        val gitignoreFile = File(gitDirPath, context.getString(R.string.gitignore_path))
        return gitignoreFile.readText()
    }

    fun writeGitignore(gitDirPath: String, gitignoreString: String) {
        val gitignoreFile = File(gitDirPath, context.getString(R.string.gitignore_path))
        if (!gitignoreFile.exists()) gitignoreFile.createNewFile()
        try {
            FileWriter(gitignoreFile, false).use { writer ->
                writer.write(gitignoreString)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun readGitInfoExclude(gitDirPath: String): String {
        if (!File("$gitDirPath/${context.getString(R.string.git_info_exclude_path)}").exists()) return ""

        val gitignoreFile = File(gitDirPath, context.getString(R.string.git_info_exclude_path))
        return gitignoreFile.readText()
    }

    fun writeGitInfoExclude(gitDirPath: String, gitignoreString: String) {
        val gitignoreFile = File(gitDirPath, context.getString(R.string.git_info_exclude_path))
        val parentDir = gitignoreFile.parentFile
        if (parentDir != null) {
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }
        }
        if (!gitignoreFile.exists()) gitignoreFile.createNewFile()

        try {
            FileWriter(gitignoreFile, false).use { writer ->
                writer.write(gitignoreString)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun closeRepo(repo: FileRepository) {
        repo.close()
        val lockFile = File(repo.directory, context.getString(R.string.git_lock_path))
        if (lockFile.exists()) lockFile.delete()
    }
}