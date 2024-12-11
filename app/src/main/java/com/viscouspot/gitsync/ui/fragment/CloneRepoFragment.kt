package com.viscouspot.gitsync.ui.fragment

import android.app.Dialog
import android.app.ProgressDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.viscouspot.gitsync.R
import com.viscouspot.gitsync.ui.adapter.RepoListAdapter
import com.viscouspot.gitsync.util.GitManager
import com.viscouspot.gitsync.util.provider.GitProviderManager
import com.viscouspot.gitsync.util.Helper
import com.viscouspot.gitsync.util.LogType
import com.viscouspot.gitsync.util.Logger.log
import com.viscouspot.gitsync.util.SettingsManager
import com.viscouspot.gitsync.util.rightDrawable

class CloneRepoFragment(
    private val settingsManager: SettingsManager,
    private val gitManager: GitManager,
    private val dirSelectionCallback: ((dirUri: Uri?) -> Unit)
): DialogFragment(R.layout.fragment_clone_repo) {
    private val repoList = mutableListOf<Pair<String, String>>()
    private var repoUrl = ""
    private var loadNextRepos: (() -> Unit)? = {}
    private var callback: ((dirUri: Uri?) -> Unit) = {dirUri: Uri? -> dirSelectionCallback(dirUri)}
    private val adapter = RepoListAdapter(repoList) {
        repoUrl = it.second
        selectLocalDir()
    }
    private lateinit var repoListRecycler: RecyclerView
    private var loadingRepos = false

    private lateinit var dirSelectionLauncher: ActivityResultLauncher<Uri?>

    override fun onAttach(context: Context) {
        super.onAttach(context)

        dirSelectionLauncher = Helper.getDirSelectionLauncher(this, requireContext()) {
            callback.invoke(it)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_clone_repo, container, false)
        adapter.notifyItemRangeRemoved(0, repoList.size)
        repoList.clear()

        repoListRecycler = view.findViewById(R.id.repoList)
        val repoUrlEditText = view.findViewById<EditText>(R.id.repoUrlEditText)
        val invalidRepoError = view.findViewById<TextView>(R.id.invalidRepoError)
        val pullButton = view.findViewById<MaterialButton>(R.id.pullButton)
        val divider = view.findViewById<View>(R.id.divider)
        val localRepo = view.findViewById<MaterialButton>(R.id.localRepo)
        repoListRecycler.setLayoutManager(GridLayoutManager(context, 1))

        invalidRepoError.text = ""
        setLoadingRepos(true)

        val repoListSupported = GitProviderManager.getManager(requireContext(), settingsManager).getRepos(settingsManager.getGitAuthCredentials().second, ::addRepos) {
            loadNextRepos = it
        }

        if (!repoListSupported) {
            repoListRecycler.visibility = View.GONE
        }

        repoUrlEditText.doOnTextChanged { _, _, _, _ ->
            repoUrlEditText.rightDrawable(null)
        }

        pullButton.setOnClickListener {
            val invalidRepoErrorText = Helper.isValidGitRepo(repoUrlEditText.text.toString())
            if (invalidRepoErrorText == null) {
                repoUrl = repoUrlEditText.text.toString()
                invalidRepoError.text = ""
                selectLocalDir()
            } else {
                repoUrlEditText.rightDrawable(R.drawable.circle_xmark)
                TextViewCompat.setCompoundDrawableTintList(repoUrlEditText, ContextCompat.getColorStateList(requireContext(), R.color.auth_red))
                invalidRepoError.text = invalidRepoErrorText
            }
        }

        repoListRecycler.setAdapter(adapter)
        repoListRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val linearLayoutManager = recyclerView.layoutManager as LinearLayoutManager?
                if (!loadingRepos) {
                    if (linearLayoutManager != null && linearLayoutManager.findLastCompletelyVisibleItemPosition() == repoList.size - 1) {
                        setLoadingRepos(true)
                        loadNextRepos?.invoke()
                    }
                }
            }
        })

        localRepo.setOnClickListener {
            selectLocalRepo()
        }

        return view
    }

    private fun setLoadingRepos(loading: Boolean) {
        activity?.runOnUiThread {
            if (loading && loadNextRepos != null) {
                repoList.add(Pair("Loading...", ""))
                    adapter.notifyItemInserted(repoList.size - 1)
                    repoListRecycler.scrollToPosition(repoList.size - 1)
            } else {
                val loadingIndex = repoList.indexOfFirst { it.first == "Loading..." }
                if (loadingIndex > -1) {
                    repoList.removeAt(loadingIndex)
                        adapter.notifyItemRemoved(loadingIndex)
                }
            }
            loadingRepos = loading
        }
    }

    private fun addRepos(repos: List<Pair<String, String>>) {
        activity?.runOnUiThread {
            setLoadingRepos(false)
            val prevEnd = repoList.size
            repoList.addAll(repos)

            if (!isAdded || isStateSaved) return@runOnUiThread
            adapter.notifyItemRangeInserted(prevEnd, repos.size)
            repoListRecycler.scrollToPosition(prevEnd)
        }
    }

    private fun localRepoCallback(dirUri: Uri?){
        dirSelectionCallback.invoke(dirUri)
        dismissAllowingStateLoss()
    }

    private fun selectLocalRepo() {
        callback = ::localRepoCallback
        dirSelectionLauncher.launch(null)
    }

    private fun localDirCallback(dirUri: Uri?) {
        if (dirUri == null) {
            dirSelectionCallback.invoke(null)
            dismissAllowingStateLoss()
            return
        }

        val cloneDialog = ProgressDialog(requireContext(), R.style.AlertDialogTheme).apply {
            setTitle(getString(R.string.cloning_repository))
            setMessage(getString(R.string.clone_message))
            max = 100
            setCancelable(false)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            show()
        }
        gitManager.cloneRepository(repoUrl, dirUri,
            { task -> activity?.runOnUiThread { cloneDialog.setMessage("${getString(R.string.clone_message)}$task") } },
            { progress -> activity?.runOnUiThread { cloneDialog.progress = progress } },
            { error ->
                log(LogType.CloneRepo, error)
                activity?.runOnUiThread {
                    Toast.makeText(context, getString(R.string.clone_failed), Toast.LENGTH_SHORT).show()
                    cloneDialog.dismiss()
                    val message = if (getString(R.string.clone_failed) == error) "" else error
                    AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                        .setTitle(getString(R.string.clone_failed))
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                        }
                        .show()
                }
            },
            {
                activity?.runOnUiThread {
                    cloneDialog.dismiss()
                    dirSelectionCallback.invoke(dirUri)
                    dismissAllowingStateLoss()
                }
            })
    }

    private fun selectLocalDir() {
        AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
            .setTitle(getString(R.string.select_clone_directory))
            .setPositiveButton(getString(R.string.select)) { _, _ ->
                callback = ::localDirCallback
                dirSelectionLauncher.launch(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window!!.requestFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog
        if (dialog != null) {
            dialog.window!!.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            dialog.window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }
}