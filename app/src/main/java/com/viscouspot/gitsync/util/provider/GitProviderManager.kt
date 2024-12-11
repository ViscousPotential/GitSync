package com.viscouspot.gitsync.util.provider

import android.content.Context
import android.net.Uri
import com.viscouspot.gitsync.R
import com.viscouspot.gitsync.util.SettingsManager

interface GitProviderManager {
    val oAuthSupport: Boolean;

    companion object {
        enum class Provider {
            GITHUB,
            GITEA,
            HTTPS,
            SSH,
        }

        val detailsMap: Map<Provider, Pair<String, Int>> = mapOf(
            Provider.GITHUB to Pair("GitHub", R.drawable.provider_github),
            Provider.GITEA to Pair("Gitea", R.drawable.provider_gitea),
            Provider.HTTPS to Pair("HTTP/S", R.drawable.provider_https),
            Provider.SSH to Pair("SSH", R.drawable.provider_ssh),
        )

        private val managerMap: Map<Provider, (Context) -> GitProviderManager> = mapOf(
            Provider.GITHUB to { context -> GithubManager(context) },
            Provider.GITEA to { context -> GiteaManager(context) },
            Provider.HTTPS to { _ -> HttpsManager() },
            Provider.SSH to { _ -> SshManager() },
        )

        fun getManager(context: Context, settingsManager: SettingsManager): GitProviderManager {
            return managerMap[settingsManager.getGitProvider()]?.invoke(context)
                ?: throw IllegalArgumentException("No manager found")
        }
    }

    fun launchOAuthFlow() {}

    fun getOAuthCredentials(
        uri: Uri?,
        setCallback: (username: String?, accessToken: String?) -> Unit
    ) {}

    fun getRepos(
        accessToken: String,
        updateCallback: (repos: List<Pair<String, String>>) -> Unit,
        nextPageCallback: (nextPage: (() -> Unit)?) -> Unit
    ): Boolean { return false }
}