package com.viscouspot.gitsync.util

import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.viscouspot.gitsync.R
import com.viscouspot.gitsync.ui.fragment.CloneRepoFragment

class OnboardingController(
    private val context: Context,
    private val activity: AppCompatActivity,
    private val settingsManager: SettingsManager,
    private val authDialog: Dialog?,
    private val cloneRepoFragment: CloneRepoFragment,
    private val updateApplicationObserver: (isChecked: Boolean) -> Unit,
    private val checkAndRequestNotificationPermission: (onGranted: (() -> Unit)?) -> Unit,
    private val checkAndRequestStoragePermission: (onGranted: (() -> Unit)?) -> Unit
) {
    var hasSkipped = false
    private var currentDialog: AlertDialog? = null

    fun show() {
        val authCreds = settingsManager.getGitAuthCredentials()
        if (authCreds.first != "" || authCreds.second != "" || settingsManager.getGitSshPrivateKey() != "") {
            settingsManager.setOnboardingStep(3)
        }
        if (settingsManager.getGitDirUri() != null) {
            settingsManager.setOnboardingStep(4)
        }
        if (settingsManager.getApplicationObserverEnabled()) {
            settingsManager.setOnboardingStep(-1)
        }

        when (settingsManager.getOnboardingStep()) {
            0 -> getWelcomeDialog().show()
            1 -> showAlmostThereOrSkip()
            2 -> getAuthDialog().show()
            3 -> {
                activity.runOnUiThread {
                    if (!cloneRepoFragment.isAdded) {
                        cloneRepoFragment.show(
                            activity.supportFragmentManager,
                            context.getString(R.string.clone_repo_title)
                        )
                    }
                }
            }
            4 -> getEnableAutoSyncDialog().show()
        }
    }

    fun dismissAll() {
        activity.runOnUiThread {
            currentDialog?.dismiss()
        }
    }

    private fun getEnableAutoSyncDialog(): AlertDialog {
        activity.runOnUiThread {
            currentDialog = AlertDialog.Builder(context, R.style.AlertDialogTheme)
                .setCancelable(false)
                .setTitle(context.getString(R.string.enable_autosync_title))
                .setMessage(context.getString(R.string.enable_autosync_message))
                .setPositiveButton(context.getString(android.R.string.ok)) { dialog, _ ->
                    dialog.dismiss()
                    activity.runOnUiThread {
                        updateApplicationObserver(true)
                    }
                }
                .setNegativeButton(
                    context.getString(R.string.skip)
                ) { dialog, _ ->
                    dialog.dismiss()
                    settingsManager.setOnboardingStep(-1)
                }.create()
        }
        return currentDialog!!
    }

    private fun getAuthDialog(): AlertDialog {
        activity.runOnUiThread {
            currentDialog = AlertDialog.Builder(context, R.style.AlertDialogTheme)
                .setCancelable(false)
                .setTitle(context.getString(R.string.auth_dialog_title))
                .setMessage(context.getString(R.string.auth_dialog_message))
                .setPositiveButton(context.getString(android.R.string.ok)) { dialog, _ ->
                    dialog.dismiss()
                    authDialog?.show()
                }
                .setNegativeButton(
                    context.getString(R.string.skip)
                ) { dialog, _ ->
                    dialog.dismiss()
                }.create()
        }
        return currentDialog!!
    }

    private fun getAlmostThereDialogLink(): TextView {
        return TextView(context).apply {
            movementMethod = LinkMovementMethod.getInstance()
            gravity = Gravity.END
            setPadding(
                0,
                0,
                resources.getDimension(R.dimen.space_md).toInt(),
                0
            )
            text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(
                    context.getString(R.string.documentation_html_link).format(context.getString(R.string.docs_link)),
                    Html.FROM_HTML_MODE_LEGACY
                )
            } else {
                Html.fromHtml(
                    context.getString(R.string.documentation_html_link).format(context.getString(R.string.docs_link)),
                )
            }
        }
    }

    private fun getAlmostThereDialog(): AlertDialog {
        activity.runOnUiThread {
            currentDialog = AlertDialog.Builder(context, R.style.AlertDialogTheme)
                .setCancelable(false)
                .setTitle(context.getString(R.string.almost_there_dialog_title))
                .setView(getAlmostThereDialogLink())
                .setMessage(context.getString(R.string.almost_there_dialog_message))
                .setPositiveButton(context.getString(android.R.string.ok)) { dialog, _ ->
                    dialog.dismiss()
                    settingsManager.setOnboardingStep(2)
                    getAuthDialog().show()
                }
                .setNegativeButton(
                    context.getString(android.R.string.cancel)
                ) { dialog, _ ->
                    dialog.dismiss()
                }.create()
        }
        return currentDialog!!
    }

    private fun showAlmostThereOrSkip() {
        settingsManager.setOnboardingStep(1)
        if (hasSkipped) return
        getAlmostThereDialog().show()
    }

    private fun getEnableAllFilesDialog(standalone: Boolean = false): AlertDialog {
        activity.runOnUiThread {
            currentDialog = AlertDialog.Builder(context, R.style.AlertDialogTheme)
                .setCancelable(false)
                .setTitle(context.getString(R.string.all_files_access_dialog_title))
                .setMessage(context.getString(R.string.all_files_access_dialog_message))
                .setPositiveButton(context.getString(android.R.string.ok)) { _, _ -> }
                .create().apply {
                    setOnShowListener {
                        getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            checkAndRequestStoragePermission {
                                dismiss()
                                if (standalone) return@checkAndRequestStoragePermission
                                showAlmostThereOrSkip()
                            }
                            this.getButton(AlertDialog.BUTTON_POSITIVE).text =
                                context.getString(R.string.done)
                        }
                    }
                }
        }
        return currentDialog!!
    }

    private fun showAllFilesAccessOrNext(standalone: Boolean = false): Boolean {
        val hasPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

        if (!hasPermissions) {
            getEnableAllFilesDialog(standalone).show()
            return true
        }

        if (standalone) return false

        showAlmostThereOrSkip()
        return false
    }

    private fun getEnableNotificationsDialog(standalone: Boolean = false): AlertDialog {
        activity.runOnUiThread {
        currentDialog = AlertDialog.Builder(context, R.style.AlertDialogTheme)
            .setCancelable(false)
            .setTitle(context.getString(R.string.notification_dialog_title))
            .setMessage(context.getString(R.string.notification_dialog_message))
            .setPositiveButton(context.getString(android.R.string.ok)) { _, _ -> }
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        checkAndRequestNotificationPermission {
                            dismiss()
                            if (standalone) return@checkAndRequestNotificationPermission
                            showAllFilesAccessOrNext()
                        }
                        getButton(AlertDialog.BUTTON_POSITIVE).text =
                            context.getString(R.string.done)
                    }
                }
            }
        }
        return currentDialog!!
    }

    fun showNotificationsOrNext(standalone: Boolean = false): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            getEnableNotificationsDialog(standalone).show()
            return true
        } else {
            return showAllFilesAccessOrNext(standalone)
        }
    }

    private fun getWelcomeDialog(): AlertDialog {
        activity.runOnUiThread {
            currentDialog = AlertDialog.Builder(context, R.style.AlertDialogTheme)
                .setCancelable(false)
                .setTitle(context.getString(R.string.welcome))
                .setMessage(context.getString(R.string.welcome_message))
                .setPositiveButton(context.getString(R.string.welcome_positive)) { dialog, _ ->
                    dialog.dismiss()
                    showNotificationsOrNext()
                }
                .setNeutralButton(
                    context.getString(R.string.welcome_neutral)
                ) { dialog, _ ->
                    hasSkipped = true
                    dialog.dismiss()
                    showNotificationsOrNext()
                }
                .setNegativeButton(
                    context.getString(R.string.welcome_negative)
                ) { dialog, _ ->
                    hasSkipped = true
                    settingsManager.setOnboardingStep(-1)
                    dialog.dismiss()
                    showNotificationsOrNext()
                }.create()
        }
        return currentDialog!!
    }
}