package com.viscouspot.gitsync.util

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.viscouspot.gitsync.R
import com.viscouspot.gitsync.util.provider.GitProviderManager

class SettingsManager internal constructor(private val context: Context) {
    private val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val settingsSharedPref = EncryptedSharedPreferences.create(
        context,
        "git_sync_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun clearAll() {
        with(settingsSharedPref.edit()) {
            clear()
            apply()
        }
    }

//    fun resetFirstTime() {
//        with(settingsSharedPref.edit()) {
//            putBoolean("isFirstTime", true)
//            apply()
//        }
//    }

    fun isFirstTime(): Boolean {
        return settingsSharedPref.getBoolean("isFirstTime", true)
    }

//    fun setHadFirstTime() {
//        with(settingsSharedPref.edit()) {
//            putBoolean("isFirstTime", false)
//            apply()
//        }
//    }

    fun getOnboardingStep(): Int {
        return settingsSharedPref.getInt("onboardingStep", 0)
    }

    fun setOnboardingStep(step: Int) {
        if (getOnboardingStep() == -1) return
        with(settingsSharedPref.edit()) {
            putInt("onboardingStep", step)
            apply()
        }
    }

    fun getSyncMessage(): String {
        return settingsSharedPref.getString("syncMessage", null) ?: context.getString(R.string.sync_message)
    }

    fun setSyncMessage(syncMessage: String) {
        with(settingsSharedPref.edit()) {
            putString("syncMessage", syncMessage)
            apply()
        }
    }

    fun getSyncMessageEnabled(): Boolean {
        return settingsSharedPref.getBoolean("syncMessageEnabled", true)
    }

    fun setSyncMessageEnabled(enabled: Boolean) {
        with(settingsSharedPref.edit()) {
            putBoolean("syncMessageEnabled", enabled)
            apply()
        }
    }

    fun getGitDirUri(): Uri? {
        val dirUri = settingsSharedPref.getString("gitDirUri", "")

        if (dirUri == "") return null
        return Uri.parse(dirUri)
    }

    fun setGitDirUri(dirUri: String) {
        with(settingsSharedPref.edit()) {
            putString("gitDirUri", dirUri)
            apply()
        }
    }

    fun getGitProvider(): GitProviderManager.Companion.Provider {
        val gitProviderString = settingsSharedPref.getString("gitProvider", "").toString()
        if (gitProviderString.isEmpty()) return GitProviderManager.Companion.Provider.GITHUB
        val providerEntry = GitProviderManager.detailsMap.firstNotNullOf {
            it.takeIf { it.value.first == gitProviderString }
        }

        return providerEntry.key
    }

    fun setGitProvider(provider: GitProviderManager.Companion.Provider) {
        with(settingsSharedPref.edit()) {
            putString("gitProvider", GitProviderManager.detailsMap[provider]?.first)
            apply()
        }
    }

    fun getGitAuthCredentials(): Pair<String, String> {
        return Pair(
            settingsSharedPref.getString("gitAuthUsername", "")!!,
            settingsSharedPref.getString("gitAuthToken", "")!!
        )
    }

    fun setGitAuthCredentials(username: String, accessToken: String) {
        with(settingsSharedPref.edit()) {
            putString("gitAuthUsername", username)
            putString("gitAuthToken", accessToken)
            apply()
        }
    }

    fun getGitSshPrivateKey(): String {
        return settingsSharedPref.getString("gitSshKey", "").toString()
    }

    fun setGitSshPrivateKey(gitSshKey: String) {
        with(settingsSharedPref.edit()) {
            putString("gitSshKey", gitSshKey)
            apply()
        }
    }

    fun getApplicationObserverEnabled(): Boolean {
        return settingsSharedPref.getBoolean("applicationObserverEnabled", false)
    }

    fun setApplicationObserverEnabled(enabled: Boolean) {
        with(settingsSharedPref.edit()) {
            putBoolean("applicationObserverEnabled", enabled)
            apply()
        }
    }

    fun getApplicationPackage(): String {
        return settingsSharedPref.getString("packageName", "")!!
    }

//    fun setApplicationPackage(packageName: String) {
//        with(settingsSharedPref.edit()) {
//            putString("packageName", packageName)
//            apply()
//        }
//    }

    fun getApplicationPackages(): Set<String> {
        return settingsSharedPref.getStringSet("packageNames", setOf())!!
    }

    fun setApplicationPackages(packageNames: List<String>) {
        with(settingsSharedPref.edit()) {
            putStringSet("packageNames", packageNames.toSet())
            apply()
        }
    }

    fun getSyncOnAppOpened(): Boolean {
        return settingsSharedPref.getBoolean("syncOnAppOpened", false)
    }

    fun setSyncOnAppOpened(enabled: Boolean) {
        with(settingsSharedPref.edit()) {
            putBoolean("syncOnAppOpened", enabled)
            apply()
        }
    }

    fun getSyncOnAppClosed(): Boolean {
        return settingsSharedPref.getBoolean("syncOnAppClosed", false)
    }

    fun setSyncOnAppClosed(enabled: Boolean) {
        with(settingsSharedPref.edit()) {
            putBoolean("syncOnAppClosed", enabled)
            apply()
        }
    }

    fun runMigrations() {
        val oldApplicationPackage = getApplicationPackage()
        if (oldApplicationPackage != "" && getApplicationPackages().isEmpty()) {
            setApplicationPackages(listOf(oldApplicationPackage))
        }

        val oldHadFirstTime = isFirstTime()
        if (!oldHadFirstTime) {
            setOnboardingStep(-1)
        }
    }
}
