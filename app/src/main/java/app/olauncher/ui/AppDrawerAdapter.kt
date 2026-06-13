package app.olauncher.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Filter
import android.widget.Filterable
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.databinding.AdapterAppDrawerBinding
import app.olauncher.databinding.AdapterPrivateSpaceHeaderBinding
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstall
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.Pinyin
import java.text.Normalizer

class AppDrawerAdapter(
    private var flag: Int,
    private val appLabelGravity: Int,
    private val appClickListener: (AppModel) -> Unit,
    private val appInfoListener: (AppModel) -> Unit,
    private val appDeleteListener: (AppModel) -> Unit,
    private val appHideListener: (AppModel, Int) -> Unit,
    private val appRenameListener: (AppModel, String) -> Unit,
    private val appStarClickListener: (AppModel) -> Unit = {},
    private val privateSpaceToggleListener: () -> Unit = {},
    private val privateSpaceSettingsListener: () -> Unit = {},
) : ListAdapter<AppModel, RecyclerView.ViewHolder>(DIFF_CALLBACK), Filterable {

    companion object {
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_PRIVATE_HEADER = 1

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppModel>() {
            override fun areItemsTheSame(oldItem: AppModel, newItem: AppModel): Boolean = when {
                oldItem is AppModel.App && newItem is AppModel.App ->
                    oldItem.appPackage == newItem.appPackage && oldItem.user == newItem.user

                oldItem is AppModel.PinnedShortcut && newItem is AppModel.PinnedShortcut ->
                    oldItem.shortcutId == newItem.shortcutId && oldItem.user == newItem.user

                oldItem is AppModel.PrivateSpaceHeader && newItem is AppModel.PrivateSpaceHeader -> true

                else -> false
            }

            override fun areContentsTheSame(oldItem: AppModel, newItem: AppModel): Boolean =
                oldItem == newItem
        }
    }

    private var autoLaunch = true
    private var isBangSearch = false
    private val appFilter = createAppFilter()
    private val myUserHandle = android.os.Process.myUserHandle()

    var appsList: MutableList<AppModel> = mutableListOf()
    var appFilteredList: MutableList<AppModel> = mutableListOf()

    override fun getItemViewType(position: Int): Int {
        return when (appFilteredList.getOrNull(position)) {
            is AppModel.PrivateSpaceHeader -> VIEW_TYPE_PRIVATE_HEADER
            else -> VIEW_TYPE_APP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PRIVATE_HEADER -> PrivateSpaceHeaderViewHolder(
                AdapterPrivateSpaceHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> ViewHolder(
                AdapterAppDrawerBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        try {
            if (appFilteredList.isEmpty() || position == RecyclerView.NO_POSITION) return
            val appModel = appFilteredList[holder.bindingAdapterPosition]
            when (holder) {
                is PrivateSpaceHeaderViewHolder -> {
                    holder.bind(
                        appLabelGravity,
                        privateSpaceToggleListener,
                        privateSpaceSettingsListener,
                    )
                }

                is ViewHolder -> holder.bind(
                    flag,
                    appLabelGravity,
                    myUserHandle,
                    appModel,
                    appClickListener,
                    appDeleteListener,
                    appInfoListener,
                    appHideListener,
                    appRenameListener,
                    appStarClickListener
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getFilter(): Filter = this.appFilter

    private fun createAppFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(charSearch: CharSequence?): FilterResults {
                isBangSearch = charSearch?.startsWith("!") ?: false
                autoLaunch = charSearch?.startsWith(" ")?.not() ?: true

                val appFilteredList = (if (charSearch.isNullOrBlank()) appsList
                else {
                    val searchStr = charSearch.toString().trim().lowercase()
                    appsList.filter { app ->
                        app !is AppModel.PrivateSpaceHeader && appLabelMatches(app.appLabel, charSearch)
                    }.sortedWith(compareBy(
                        { !it.isStarred }, // 星标置顶
                        {
                            // 计算匹配度得分：越小越靠前
                            val label = it.appLabel
                            val pinyinStr = Pinyin.toPinyin(label, "").lowercase()
                            val initialsStr = calculateInitials(label)
                            
                            val t9Regex = searchStr.map { t9RegexMap[it] ?: it.toString() }.joinToString("")
                            val prefixRegex = try { Regex("^$t9Regex") } catch (e: Exception) { return@compareBy 5 }
                            
                            when {
                                // 首字母前缀匹配 (jd -> 京东)
                                prefixRegex.find(initialsStr)?.range?.start == 0 -> 0
                                // 全拼前缀匹配 (jing -> 京东)
                                prefixRegex.find(pinyinStr)?.range?.start == 0 -> 1
                                // 原文前缀
                                label.lowercase().startsWith(searchStr) -> 2
                                // 首字母包含
                                Regex(t9Regex).containsMatchIn(initialsStr) -> 3
                                // 全拼包含
                                Regex(t9Regex).containsMatchIn(pinyinStr) -> 4
                                // 原文包含
                                label.lowercase().contains(searchStr) -> 5
                                else -> 6
                            }
                        },
                        { it.appLabel } // 字母序兜底
                    ))
                } as MutableList<AppModel>)

                val filterResults = FilterResults()
                filterResults.values = appFilteredList
                return filterResults
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                results?.values?.let {
                    val items = it as MutableList<AppModel>
                    appFilteredList = items
                    submitList(appFilteredList) {
                        autoLaunch()
                    }
                }
            }
        }
    }

    private fun autoLaunch() {
        try {
            if (itemCount == 1
                && autoLaunch
                && isBangSearch.not()
                && flag == Constants.FLAG_LAUNCH_APP
                && appFilteredList.isNotEmpty()
                && appFilteredList[0] !is AppModel.PrivateSpaceHeader
            ) appClickListener(appFilteredList[0])
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // T9 键盘映射：字母/数字 -> 对应按键的所有字符（含数字本身，方便直接输数字搜索）
    private val t9RegexMap = mapOf(
        'a' to "[abc2]", 'b' to "[abc2]", 'c' to "[abc2]", '2' to "[abc2]",
        'd' to "[def3]", 'e' to "[def3]", 'f' to "[def3]", '3' to "[def3]",
        'g' to "[ghi4]", 'h' to "[ghi4]", 'i' to "[ghi4]", '4' to "[ghi4]",
        'j' to "[jkl5]", 'k' to "[jkl5]", 'l' to "[jkl5]", '5' to "[jkl5]",
        'm' to "[mno6]", 'n' to "[mno6]", 'o' to "[mno6]", '6' to "[mno6]",
        'p' to "[pqrs7]", 'q' to "[pqrs7]", 'r' to "[pqrs7]", 's' to "[pqrs7]", '7' to "[pqrs7]",
        't' to "[tuv8]", 'u' to "[tuv8]", 'v' to "[tuv8]", '8' to "[tuv8]",
        'w' to "[wxyz9]", 'x' to "[wxyz9]", 'y' to "[wxyz9]", 'z' to "[wxyz9]", '9' to "[wxyz9]"
    )


    private fun calculateInitials(label: String): String {
        return label.map { char ->
            val p = Pinyin.toPinyin(char.toString(), "")
            if (p.isNotEmpty() && p[0].isLetter()) p[0].lowercase() else null
        }.filterNotNull().joinToString("")
    }

    private fun appLabelMatches(appLabel: String, charSearch: CharSequence): Boolean {
        val searchStr = charSearch.toString().trim().lowercase()
        if (searchStr.isEmpty()) return true

        // 1. 原文匹配（英文、数字或已重命名的应用）
        if (appLabel.contains(searchStr, true)) return true

        // 2. 计算拼音与首字母
        val pinyinStr = Pinyin.toPinyin(appLabel, "").lowercase()
        val initialsStr = calculateInitials(appLabel)
        if (pinyinStr.isEmpty() && initialsStr.isEmpty()) return false

        // 3. 构建 T9 正则
        val t9Regex = searchStr.map { t9RegexMap[it] ?: it.toString() }.joinToString("")

        return try {
            val regex = Regex(t9Regex)
            // 匹配全拼 或 匹配首字母（支持 jd -> 京东）
            regex.containsMatchIn(pinyinStr) || regex.containsMatchIn(initialsStr)
        } catch (e: Exception) {
            false
        }
    }

    fun setAppList(appsList: MutableList<AppModel>) {
        // 强制排序：1. 置顶应用在前 2. 字母/拼音顺序
        appsList.sortWith(compareBy<AppModel>(
            { !it.isStarred }, // 置顶 (isStarred=true -> !true=false -> 0) 排在 未置顶 (isStarred=false -> !false=true -> 1) 前面
            { it }            // 使用 AppModel 的 compareTo (基于 CollationKey 排序)
        ))

        // Add empty app for bottom padding in recyclerview and assign to list
        appsList.add(
            AppModel.App(
                appLabel = "",
                key = null,
                appPackage = "",
                activityClassName = "",
                isNew = false,
                user = android.os.Process.myUserHandle()
            )
        )
        this.appsList = appsList
        this.appFilteredList = appsList
        submitList(appsList)
    }

    fun launchFirstInList() {
        val first = appFilteredList.firstOrNull { it !is AppModel.PrivateSpaceHeader }
        if (first != null) appClickListener(first)
    }

    class PrivateSpaceHeaderViewHolder(private val binding: AdapterPrivateSpaceHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            appLabelGravity: Int,
            toggleListener: () -> Unit,
            settingsListener: () -> Unit,
        ) = with(binding) {
            privateSpaceTitle.gravity = appLabelGravity
            privateSpaceTitle.setOnClickListener { toggleListener() }
            privateSpaceTitle.setOnLongClickListener {
                settingsListener()
                true
            }
        }
    }

    class ViewHolder(private val binding: AdapterAppDrawerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            flag: Int,
            appLabelGravity: Int,
            myUserHandle: UserHandle,
            appModel: AppModel,
            clickListener: (AppModel) -> Unit,
            appDeleteListener: (AppModel) -> Unit,
            appInfoListener: (AppModel) -> Unit,
            appHideListener: (AppModel, Int) -> Unit,
            appRenameListener: (AppModel, String) -> Unit,
            appStarClickListener: (AppModel) -> Unit,
        ) = with(binding) {
            appHideLayout.visibility = View.GONE
            renameLayout.visibility = View.GONE
            appTitle.visibility = View.VISIBLE

            // Show indicators in title based on app type and state
            appTitle.text = buildString {
                if (appModel.isStarred) append("⭐ ")
                append(appModel.appLabel)
                if (appModel.isNew) append(" ✦")
            }
            appTitle.gravity = appLabelGravity
            otherProfileIndicator.isVisible = appModel.user != myUserHandle

            appTitle.setOnClickListener { clickListener(appModel) }

            appTitle.setOnLongClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    appDelete.alpha = when (
                        appModel is AppModel.PinnedShortcut || !root.context.isSystemApp(appModel.appPackage, appModel.user)
                    ) {
                        true -> 1.0f
                        false -> 0.5f
                    }
                    appHide.text = if (flag == Constants.FLAG_HIDDEN_APPS)
                        root.context.getString(R.string.adapter_show)
                    else
                        root.context.getString(R.string.adapter_hide)
                    appTitle.visibility = View.INVISIBLE
                    appHide.alpha = when (appModel is AppModel.PinnedShortcut) {
                        true -> 0.5f
                        false -> 1.0f
                    }
                    appHideLayout.visibility = View.VISIBLE
                    // Only allow renaming non hidden apps
                    appRename.isVisible = flag != Constants.FLAG_HIDDEN_APPS
                }
                true
            }

            // Configure rename behavior
            appRename.setOnClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    etAppRename.setText(appModel.appLabel)
                    etAppRename.setSelectAllOnFocus(true)
                    renameLayout.visibility = View.VISIBLE
                    appHideLayout.visibility = View.GONE
                    etAppRename.showKeyboard()
                    etAppRename.imeOptions = EditorInfo.IME_ACTION_DONE
                }
            }
            etAppRename.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                appTitle.visibility = if (hasFocus) View.INVISIBLE else View.VISIBLE
            }
            etAppRename.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    etAppRename.hint = ""
                }
            })
            etAppRename.setOnEditorActionListener { _, actionCode, _ ->
                if (actionCode == EditorInfo.IME_ACTION_DONE) {
                    val renameLabel = etAppRename.text.toString().trim()
                    if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                        appRenameListener(appModel, renameLabel)
                        renameLayout.visibility = View.GONE
                    }
                    true
                }
                false
            }
            tvSaveRename.setOnClickListener {
                etAppRename.hideKeyboard()
                val renameLabel = etAppRename.text.toString().trim()
                if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                    appRenameListener(appModel, renameLabel)
                    renameLayout.visibility = View.GONE
                } else {
                    appRenameListener(
                        appModel,
                        getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    )
                    renameLayout.visibility = View.GONE
                }
            }
            appInfo.setOnClickListener { appInfoListener(appModel) }
            appDelete.setOnClickListener { appDeleteListener(appModel) }
            appMenuClose.setOnClickListener {
                appHideLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appRenameClose.setOnClickListener {
                renameLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appHide.setOnClickListener { appHideListener(appModel, bindingAdapterPosition) }
            appStar.setOnClickListener { appStarClickListener(appModel) }
        }

        private fun getAppName(context: Context, appPackage: String, user: UserHandle): String {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            return try {
                val activityList = launcherApps.getActivityList(appPackage, user)
                if (activityList.isNotEmpty()) {
                    activityList.first().label.toString()
                } else {
                    val packageManager = context.packageManager
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(appPackage, 0)
                    ).toString()
                }
            } catch (_: Exception) {
                "" // As a fallback, display an empty string.
            }
        }
    }
}
