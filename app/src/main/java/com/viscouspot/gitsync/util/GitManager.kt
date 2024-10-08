package com.viscouspot.gitsync.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.syari.kgit.KGit
import com.viscouspot.gitsync.Secrets
import com.viscouspot.gitsync.ui.adapter.Commit
import com.viscouspot.gitsync.util.Logger.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.BatchingProgressMonitor
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class GitManager(private val context: Context, private val activity: AppCompatActivity? = null) {
    private val GITHUB_AUTH_URL = "https://github.com/login/oauth/authorize"
    private val GIT_SCOPE = "repo"
    private val client = OkHttpClient()

    fun launchGithubOAuthFlow() {
        val fullAuthUrl = "$GITHUB_AUTH_URL?client_id=${Secrets.GIT_CLIENT_ID}&scope=$GIT_SCOPE&state=${UUID.randomUUID()}"

        if (activity == null) {
            log("GithubFlow", "Activity Not Found")
            return
        }

        log("GithubFlow", "Launching Flow")
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fullAuthUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun getGithubAuthCredentials(code: String, state: String, setCallback: (username: String, authToken: String) -> Unit) {
        val authTokenRequest: Request = Request.Builder()
            .url("https://github.com/login/oauth/access_token?client_id=${Secrets.GIT_CLIENT_ID}&client_secret=${Secrets.GIT_CLIENT_SECRET}&code=$code&state=$state")
            .post("".toRequestBody())
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(authTokenRequest).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                log(context, "GithubAuthCredentials", e)
            }

            override fun onResponse(call: Call, response: Response) {
                log("GithubAuthCredentials", "Auth Token Obtained")
                val authToken = JSONObject(response.body?.string() ?: "").getString("access_token")

                getGithubProfile(authToken, {
                    setCallback.invoke(it, authToken)
                }, { })
            }
        })
    }

    fun getGithubProfile(authToken: String, successCallback: (username: String) -> Unit, failureCallback: () -> Unit) {
        val profileRequest: Request = Request.Builder()
            .url("https://api.github.com/user")
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "token $authToken")
            .build()

        log("GithubAuthCredentials", "Getting User Profile")
        client.newCall(profileRequest).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                log(context, "GithubAuthCredentials", e)
                failureCallback.invoke()
            }

            override fun onResponse(call: Call, response: Response) {
                val json = JSONObject(response.body?.string())
                val username = json.getString("login")

                log("GithubAuthCredentials", "Username Retrieved")
                successCallback.invoke(username)
            }

        })
    }

    fun getRepos(authToken: String, updateCallback: (repos: List<Pair<String, String>>) -> Unit, nextPageCallback: (nextPage: (() -> Unit)?) -> Unit){
        log("GetRepos", "Getting User Repos")
        getReposRequest(authToken, "https://api.github.com/user/repos", updateCallback, nextPageCallback)
    }

    private fun getReposRequest(authToken: String, url: String, updateCallback: (repos: List<Pair<String, String>>) -> Unit, nextPageCallback: (nextPage: (() -> Unit)?) -> Unit) {
        client.newCall(
            Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "token $authToken")
                .build()
        ).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                log(context, "GetRepos", e)
            }

            override fun onResponse(call: Call, response: Response) {
                log("GetRepos", "Repos Received")

                val jsonArray = JSONArray(response.body?.string())
                val repoMap: MutableList<Pair<String, String>> = mutableListOf()

                val link = response.headers["link"] ?: ""

                if (link != "") {
                    val regex = "<(.*?)>; rel=\"next\"".toRegex()

                    val match = regex.find(link)
                    val result = match?.groups?.get(1)?.value

                    val nextLink = result ?: ""
                    if (nextLink != "") {
                        nextPageCallback {
                            getReposRequest(authToken, nextLink, updateCallback, nextPageCallback)
                        }
                    } else {
                        nextPageCallback(null)
                    }
                }

                for (i in 0..<jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.getString("name")
                    val cloneUrl = obj.getString("clone_url")
                    repoMap.add(Pair(name, cloneUrl))
                }

                updateCallback.invoke(repoMap)
            }
        })
    }

    fun cloneRepository(repoUrl: String, userStorageUri: Uri, username: String, token: String, progressCallback: (progress: Int) -> Unit, callback: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                log("CloneRepo", "Cloning Repo")

                val monitor = object : BatchingProgressMonitor() {
                    override fun onUpdate(taskName: String?, workCurr: Int, duration: Duration?) {}

                    override fun onUpdate(
                        taskName: String?,
                        workCurr: Int,
                        workTotal: Int,
                        percentDone: Int,
                        duration: Duration?
                    ) {
                        progressCallback(percentDone)
                    }

                    override fun onEndTask(taskName: String?, workCurr: Int, duration: Duration?) {
                    }

                    override fun onEndTask(
                        taskName: String?,
                        workCurr: Int,
                        workTotal: Int,
                        percentDone: Int,
                        duration: Duration?
                    ) {}
                }

                KGit.cloneRepository {
                    setURI(repoUrl)
                    setProgressMonitor(monitor)
                    setDirectory(File(Helper.getPathFromUri(context, userStorageUri)))
                    setCredentialsProvider(UsernamePasswordCredentialsProvider(username, token))
                }

                log("CloneRepo", "Repository cloned successfully")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Repository cloned successfully", Toast.LENGTH_SHORT).show()
                }

                callback.invoke()
            } catch (e: Exception) {
                log(context, "CloneRepo", e)

                log("CloneRepo", "Repository clone failed")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to clone repository", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun pullRepository(userStorageUri: Uri, username: String, token: String, onSync: () -> Unit): Boolean? {
        try {
            var returnResult: Boolean? = false
            log("PullFromRepo", "Getting local directory")
            val repo = FileRepository("${Helper.getPathFromUri(context, userStorageUri)}/.git")
            val git = KGit(repo)
            val cp = UsernamePasswordCredentialsProvider(username, token)

            log("PullFromRepo", "Fetching changes")
            val fetchResult = git.fetch {
                setCredentialsProvider(cp)
            }

            if (!fetchResult.trackingRefUpdates.isEmpty()) {
                log("PullFromRepo", "Pulling changes")
                onSync.invoke()
                val result = git.pull {
                    setCredentialsProvider(cp)
                    remote = "origin"
                }
                if (result.isSuccessful()) {
                    returnResult = true
                } else {
                    returnResult = null
                }
            }

            log("PullFromRepo", "Closing repository")
            closeRepo(repo)

            return returnResult
        } catch (e: Exception) {
            log(context, "PullFromRepo", e)
        }
        return null
    }

    fun pushAllToRepository(repoUrl: String, userStorageUri: Uri, username: String, token: String, onSync: () -> Unit): Boolean? {
        try {
            var returnResult = false
            log("PushToRepo", "Getting local directory")

            val repo = FileRepository("${Helper.getPathFromUri(context, userStorageUri)}/.git")
            val git = KGit(repo)

            logStatus(git)
            val status = git.status()

            if (status.uncommittedChanges.isNotEmpty() || status.untracked.isNotEmpty()) {
                onSync.invoke()
                log("PushToRepo", "Adding Files to Stage")
                git.add {
                    addFilepattern(".")
                }
                git.add {
                    addFilepattern(".")
                    isUpdate = true
                }

                log("PushToRepo", "Getting current time")
                val currentDateTime = LocalDateTime.now()
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

                log("PushToRepo", "Committing changes")
                git.commit {
                    setCommitter(username, "")
                    message = "Last Sync: ${currentDateTime.format(formatter)} (Mobile)"
                }

                returnResult = true
            }

            log("PushToRepo", "Pushing changes")
            for (pushResult in git.push {
                setCredentialsProvider(UsernamePasswordCredentialsProvider(username, token))
                remote = repoUrl
            }) {
                for (remoteUpdate in pushResult.remoteUpdates) {
//                    if (remoteUpdate.trackingRefUpdate )
                    when (remoteUpdate.status) {
                        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> {
                            log("PushToRepo", "Attempting rebase on REJECTED_NONFASTFORWARD")
                            val rebaseResult = git.rebase {
                                setUpstream("origin/master")
                                // TODO: compliance for main + master
                            }

                            if (rebaseResult.status != RebaseResult.Status.OK) {
                                git.rebase {
                                    setOperation(RebaseCommand.Operation.ABORT)
                                }
                                throw Error("Remote is further ahead than local and we could not automatically rebase for you!")
                            }
                            break
                        }
                        else -> {}
                    }
                }
            }

            if (Looper.myLooper() == null) {
                Looper.prepare()
            }

            logStatus(git)

            log("PushToRepo", "Closing repository")
            closeRepo(repo)

            return returnResult
        } catch (e: Exception) {
            log(context, "PushToRepo", e)
        }
        return null
    }

    private fun logStatus(git: KGit) {
        val status = git.status()
        log("GitStatus.HasUncommittedChanges", status.hasUncommittedChanges().toString())
        log("GitStatus.Missing", status.missing.toString())
        log("GitStatus.Modified", status.modified.toString())
        log("GitStatus.Removed", status.removed.toString())
        log("GitStatus.IgnoredNotInIndex", status.ignoredNotInIndex.toString())
        log("GitStatus.Changed", status.changed.toString())
        log("GitStatus.Untracked", status.untracked.toString())
        log("GitStatus.Added", status.added.toString())
        log("GitStatus.Conflicting", status.conflicting.toString())
        log("GitStatus.UncommittedChanges", status.uncommittedChanges.toString())
    }

    fun getRecentCommits(gitDirPath: String): List<Commit> {
        try {
            if (!File("$gitDirPath/.git").exists()) return listOf()

            val repo = FileRepository("$gitDirPath/.git")
            val revWalk = RevWalk(repo)

            val headRef = repo.fullBranch ?: repo.findRef("HEAD")?.target?.name
            val head = repo.resolve(headRef)
            revWalk.markStart(revWalk.parseCommit(head))
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

            revWalk.dispose()
            closeRepo(repo)

            return commits
        } catch (e: java.lang.Exception) {
            log(context, "RecentCommits", e)
        }
        return listOf()
    }

    private fun closeRepo(repo: FileRepository) {
        repo.close()
        val lockFile = File(repo.directory, "index.lock")
        if (lockFile.exists()) lockFile.delete()
    }
}