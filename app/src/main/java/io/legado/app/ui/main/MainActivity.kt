@file:Suppress("DEPRECATION")

package io.legado.app.ui.main

import android.graphics.Rect
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.doOnLayout
import androidx.core.view.get
import androidx.core.view.postDelayed
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityMainBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.storage.BackupServer
import io.legado.app.help.BottomBarSkinManager
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.help.SourceSharePassphraseImportPolicy
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.sync.ServerSync
import io.legado.app.help.sync.SyncManager
import io.legado.app.help.sync.SyncSocket
import io.legado.app.help.update.AppUpdate
import io.legado.app.help.update.isIgnoredAppUpdate
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.about.UpdateDialog
import io.legado.app.ui.autoTask.ImportAutoTaskDialog
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportDictRuleDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.BookshelfFragment1
import io.legado.app.ui.main.bookshelf.style2.BookshelfFragment2
import io.legado.app.ui.main.explore.ExploreFragment
import io.legado.app.ui.main.my.MyFragment
import io.legado.app.ui.main.rss.RssFragment
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.clearClip
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getClipText
import io.legado.app.utils.isCreated
import io.legado.app.utils.imeHeight
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.observeEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.views.bottomPadding
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.hours

/**
 * 主界面
 */
@Suppress("PrivatePropertyName")
class MainActivity : VMBaseActivity<ActivityMainBinding, MainViewModel>(),
    BottomNavigationView.OnNavigationItemSelectedListener,
    BottomNavigationView.OnNavigationItemReselectedListener,
    MainViewModel.CallBack {

    override val binding by viewBinding(ActivityMainBinding::inflate)
    override val viewModel by viewModels<MainViewModel>()
    private val idBookshelf = 0
    private val idBookshelf1 = 11
    private val idBookshelf2 = 12
    private val idExplore = 1
    private val idRss = 2
    private val idMy = 3
    private var exitTime: Long = 0
    private var bookshelfReselected: Long = 0
    private var exploreReselected: Long = 0
    private var pagePosition = 0
    private val fragmentMap = hashMapOf<Int, Fragment>()
    private var bottomMenuCount = 4
    private val EXIT_INTERVAL = 2000L
    private val realPositions = arrayOf(idBookshelf, idExplore, idRss, idMy)
    private val menuIdToSlot = linkedMapOf(
        R.id.menu_bookshelf to "bookshelf",
        R.id.menu_discovery to "home",
        R.id.menu_rss to "notes",
        R.id.menu_my_config to "settings",
    )
    private val adapter by lazy {
        TabFragmentPageAdapter(supportFragmentManager)
    }
    private var onUpBooksBadgeView: BadgeView? = null
    private var lastPassphraseText: String? = null
    private var pendingPassphraseRead = false
    private val waitDialog by lazy { WaitDialog(this) }
    private var syncPollJob: kotlinx.coroutines.Job? = null
    private var passphraseReadGeneration = 0

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        upBottomMenu()
        initView()
        upHomePage()
        upBottomBarSkin()
        onBackPressedDispatcher.addCallback(this) {
            if (pagePosition != 0) {
                binding.viewPagerMain.currentItem = 0
                return@addCallback
            }
            (fragmentMap[getFragmentId(0)] as? BookshelfFragment2)?.let {
                if (it.back()) {
                    return@addCallback
                }
            }
            if (System.currentTimeMillis() - exitTime > EXIT_INTERVAL) {
                toastOnUi(R.string.double_click_exit)
                exitTime = System.currentTimeMillis()
            } else {
                if (BaseReadAloudService.pause) {
                    finish()
                } else {
                    moveTaskToBack(true)
                }
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        lifecycleScope.launch {
            //隐私协议
            if (!privacyPolicy()) return@launch
            //版本更新
            upVersion()
            //绑定手机号(恢复备份期间不把旧数据推回服务器)
            SyncManager.beginStartupQuiet()
            bindAccountIfNeeded()
            //启动时先拉云端进度/记录/配置(独立作用域, 恢复备份触发的重建不会打断)
            SyncManager.pullAsync(applyConfigs = true) {
                SyncManager.endStartupQuiet()
                SyncManager.bootstrap()
            }
            notifyAppCrash()
            //备份同步
            backupSync()
            //设置回调
            viewModel.setActivityCallback(this@MainActivity)
            //自动更新书源
            binding.viewPagerMain.postDelayed(1000) {
                viewModel.ruleSubsUp()
            }
            scheduleSourceSharePassphraseRead(1500)
            //自动更新书籍
            val isAutoRefreshedBook = savedInstanceState?.getBoolean("isAutoRefreshedBook") ?: false
            if (AppConfig.autoRefreshBook && !isAutoRefreshedBook) {
                binding.viewPagerMain.postDelayed(2000) {
                    viewModel.upAllBookToc()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (SourceSharePassphraseImportPolicy.shouldScheduleOnResume(
                privacyPolicyOk = LocalConfig.privacyPolicyOk
            )
        ) {
            scheduleSourceSharePassphraseRead(500)
        }
        if (ServerSync.isConfigured) {
            SyncSocket.ensureStarted()
            SyncManager.bootstrap()
            syncPollJob?.cancel()
            syncPollJob = lifecycleScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(60_000)
                    SyncManager.pull(applyConfigs = false)
                }
            }
        }
    }

    override fun onPause() {
        syncPollJob?.cancel()
        SyncManager.flushNow()
        super.onPause()
        pendingPassphraseRead = false
        passphraseReadGeneration++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingPassphraseRead && canAwaitPassphraseWindowFocus()) {
            readSourceSharePassphrase(200)
        } else if (hasFocus) {
            pendingPassphraseRead = false
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean = binding.run {
        when (item.itemId) {
            R.id.menu_bookshelf -> {
                viewPagerMain.setCurrentItem(0, false)
                true
            }

            R.id.menu_discovery -> {
                viewPagerMain.setCurrentItem(realPositions.indexOf(idExplore), false)
                true
            }

            R.id.menu_rss -> {
                viewPagerMain.setCurrentItem(realPositions.indexOf(idRss), false)
                true
            }

            R.id.menu_my_config -> {
                viewPagerMain.setCurrentItem(realPositions.indexOf(idMy), false)
                true
            }

            else -> false
        }
    }

    override fun onNavigationItemReselected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_bookshelf -> {
                if (System.currentTimeMillis() - bookshelfReselected > 300) {
                    bookshelfReselected = System.currentTimeMillis()
                } else {
                    (fragmentMap[getFragmentId(0)] as? BaseBookshelfFragment)?.gotoTop()
                }
            }

            R.id.menu_discovery -> {
                if (System.currentTimeMillis() - exploreReselected > 300) {
                    exploreReselected = System.currentTimeMillis()
                } else {
                    (fragmentMap[1] as? ExploreFragment)?.compressExplore()
                }
            }
        }
    }

    private fun initView() = binding.run {
        viewPagerMain.setEdgeEffectColor(primaryColor)
        viewPagerMain.offscreenPageLimit = 3
        viewPagerMain.adapter = adapter
        viewPagerMain.addOnPageChangeListener(PageChangeCallback())
        bottomNavigationView.setOnNavigationItemSelectedListener(this@MainActivity)
        bottomNavigationView.setOnNavigationItemReselectedListener(this@MainActivity)
        if (AppConfig.isEInkMode) {
            bottomNavigationView.setBackgroundResource(R.drawable.bg_eink_border_top)
        }
        bottomNavigationView.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val height = windowInsets.navigationBarHeight
            view.bottomPadding = height
            view.doOnLayout {
                val location = IntArray(2)
                view.getLocationInWindow(location)
                val keyboardTop = window.decorView.height - windowInsets.imeHeight
                // The pager already ends above the bottom navigation, even when IME covers it.
                viewPagerMain.bottomPadding = (location[1] - keyboardTop).coerceAtLeast(0)
                if (windowInsets.imeHeight > 0) viewPagerMain.doOnLayout {
                    currentFocus?.takeIf { it.onCheckIsTextEditor() }?.let { input ->
                        input.requestRectangleOnScreen(Rect(0, 0, input.width, input.height), true)
                    }
                }
            }
            windowInsets.inset(0, 0, 0, height)
        }
    }

    /**
     * 用户隐私与协议
     */
    private suspend fun privacyPolicy(): Boolean = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.privacyPolicyOk) {
            block.resume(true)
            return@sc
        }
        val privacyPolicy = String(assets.open("privacyPolicy.md").readBytes())
        alert(getString(R.string.privacy_policy), privacyPolicy) {
            positiveButton(R.string.agree) {
                LocalConfig.privacyPolicyOk = true
                block.resume(true)
            }
            negativeButton(R.string.refuse) {
                finish()
                block.resume(false)
            }
        }
    }

    /**
     * 版本更新日志
     */
    private suspend fun upVersion() = suspendCancellableCoroutine sc@{ block ->
        if (LocalConfig.versionCode == appInfo.versionCode) {
            if (AppConfig.autoUpdateVariant) {
                if (LocalConfig.lastCheckUpdate + 24.hours.inWholeMilliseconds < System.currentTimeMillis()) {
                    AppUpdate.gitHubUpdate.check(lifecycleScope)
                        .onSuccess {
                            if (isIgnoredAppUpdate(it.tagName, LocalConfig.ignoreUpdateVersion)) {
                                return@onSuccess
                            }
                            if (supportFragmentManager.isStateSaved) return@onSuccess
                            showDialogFragment(
                                UpdateDialog(it)
                            )
                        }
                    LocalConfig.lastCheckUpdate = System.currentTimeMillis()
                }
            }
            block.resume(null)
            return@sc
        }
        LocalConfig.versionCode = appInfo.versionCode
        if (LocalConfig.isFirstOpenApp) {
            val help = String(assets.open("web/help/md/appHelp.md").readBytes())
            val dialog = TextDialog(
                getString(R.string.help),
                help,
                TextDialog.Mode.MD,
                showToc = true,
            )
            dialog.setOnDismissListener {
                block.resume(null)
            }
            showDialogFragment(dialog)
        } else if (!BuildConfig.DEBUG) {
            val log = String(assets.open("updateLog.md").readBytes())
            val dialog = TextDialog(getString(R.string.update_log), log, TextDialog.Mode.MD)
            dialog.setOnDismissListener {
                block.resume(null)
            }
            showDialogFragment(dialog)
        } else {
            block.resume(null)
        }
    }

    /**
     * 首次启动绑定手机号：已有账号自动恢复最新备份，新手机号创建账号并上传初始备份。
     * 本地密码改为在备份设置里按需设置。
     */
    private suspend fun bindAccountIfNeeded() {
        if (LocalConfig.accountBindPrompted) return
        if (BackupServer.phone.isNotEmpty()) {
            LocalConfig.accountBindPrompted = true
            return
        }
        if (!BackupServer.serverUrlConfigured) return
        while (true) {
            val input = promptBindPhone()
            if (input == null) {
                LocalConfig.accountBindPrompted = true
                return
            }
            val phone = normalizePhone(input)
            if (phone == null) {
                toastOnUi(R.string.bind_account_invalid)
                continue
            }
            appCtx.putPrefString(PreferKey.backupPhone, phone)
            val error = bindAccount(phone)
            LocalConfig.accountBindPrompted = true
            if (error == null) return
            if (!confirmBindRetry(error)) return
        }
    }

    private suspend fun promptBindPhone(): String? = suspendCancellableCoroutine { cont ->
        var finished = false
        fun finish(value: String?) {
            if (finished) return
            finished = true
            cont.resume(value)
        }
        val editTextBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.bind_account_phone_hint)
            editView.inputType = InputType.TYPE_CLASS_PHONE
            editView.setText(BackupServer.phone)
        }
        alert(R.string.bind_account_title, R.string.bind_account_summary) {
            customView { editTextBinding.root }
            okButton { finish(editTextBinding.editView.text?.toString()?.trim()) }
            cancelButton { finish(null) }
            onDismiss { finish(null) }
        }
    }

    private suspend fun confirmBindRetry(message: String): Boolean =
        suspendCancellableCoroutine { cont ->
            var finished = false
            fun finish(value: Boolean) {
                if (finished) return
                finished = true
                cont.resume(value)
            }
            alert(getString(R.string.bind_account_failed), message) {
                okButton { finish(true) }
                cancelButton { finish(false) }
                onDismiss { finish(false) }
            }
        }

    private fun normalizePhone(raw: String): String? {
        val phone = raw.replace(Regex("[\\s\\-()]"), "")
        return phone.takeIf { Regex("^\\+?\\d{6,20}$").matches(it) }
    }

    /** @return null 表示绑定完成，否则为可展示的错误信息 */
    private suspend fun bindAccount(phone: String): String? {
        val account = runCatching { BackupServer.checkAccount(phone) }
            .getOrElse { return "${getString(R.string.bind_account_check_failed)}\n${it.localizedMessage}" }
        if (account.exists) {
            val latest = account.latestBackup
            if (latest == null) {
                toastOnUi(R.string.bind_account_bound)
                return null
            }
            waitDialog.setText(R.string.bind_account_restoring).show()
            val result = withContext(IO) { runCatching { BackupServer.restore(latest) } }
            waitDialog.dismiss()
            result.exceptionOrNull()?.let {
                return "${getString(R.string.bind_account_restore_failed)}\n${it.localizedMessage}"
            }
            LocalConfig.lastBackup = maxOf(LocalConfig.lastBackup, latest.createdMillis)
            return null
        }
        val bound = runCatching { BackupServer.bindAccount(phone) }
            .getOrElse { return "${getString(R.string.bind_account_create_failed)}\n${it.localizedMessage}" }
        if (!bound.created) {
            toastOnUi(R.string.bind_account_bound)
            return null
        }
        waitDialog.setText(R.string.bind_account_uploading).show()
        val upload = withContext(IO) {
            runCatching {
                Backup.backupLocked(
                    appCtx,
                    AppConfig.backupPath,
                    uploadServer = true,
                    trigger = "auto"
                )
            }
        }
        waitDialog.dismiss()
        upload.exceptionOrNull()?.let { error ->
            // 退出时还有自动备份兜底, 不打断绑定
            AppLog.put("上传初始备份失败\n${error.localizedMessage}", error)
            toastOnUi(R.string.bind_account_created_backup_later)
            return null
        }
        toastOnUi(R.string.bind_account_created)
        return null
    }

    private fun notifyAppCrash() {
        if (!LocalConfig.appCrash || BuildConfig.DEBUG) {
            return
        }
        LocalConfig.appCrash = false
        alert(getString(R.string.draw), "检测到阅读发生了崩溃，是否打开崩溃日志以便报告问题？") {
            yesButton {
                showDialogFragment<CrashLogsDialog>()
            }
            noButton()
        }
    }

    /**
     * 备份同步
     */
    private fun backupSync() {
        if (!AppConfig.autoCheckNewBackup) {
            return
        }
        lifecycleScope.launch {
            if (BackupServer.isConfigured) {
                val serverBackup = withContext(IO) {
                    runCatching { BackupServer.latestOrNull() }.getOrNull()
                } ?: return@launch
                val backupTime = serverBackup.createdMillis
                if (backupTime - LocalConfig.lastBackup > DateUtils.MINUTE_IN_MILLIS) {
                    alert(R.string.restore, R.string.backup_after_local_restore_confirm) {
                        cancelButton {
                            LocalConfig.lastBackup = maxOf(LocalConfig.lastBackup, backupTime)
                        }
                        okButton {
                            viewModel.restoreServer(serverBackup, backupTime)
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (AppConfig.autoRefreshBook) {
            outState.putBoolean("isAutoRefreshedBook", true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Coroutine.async {
            BookHelp.clearInvalidCache()
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    /**
     * 如果重启太快fragment不会重建,这里更新一下书架的排序
     */
    override fun recreate() {
        (fragmentMap[getFragmentId(0)] as? BaseBookshelfFragment)?.run {
            upSort()
        }
        super.recreate()
    }

    override fun observeLiveBus() {
        viewModel.onUpBooksLiveData.observe(this) {
            if (onUpBooksBadgeView == null) {
                onUpBooksBadgeView = binding.bottomNavigationView.addBadgeView(0)
            }
            onUpBooksBadgeView!!.setBadgeCount(it)
        }
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
        observeEvent<Boolean>(EventBus.NOTIFY_MAIN) {
            binding.apply {
                if (it) {
                    bottomNavigationView.menu.clear()
                    bottomNavigationView.inflateMenu(R.menu.main_bnv)
                    onUpBooksBadgeView = null
                }
                upBottomMenu()
                upBottomBarSkin()
                if (it) {
                    viewPagerMain.setCurrentItem(bottomMenuCount - 1, false)
                }
            }
        }
        observeEvent<String>(EventBus.BOTTOM_BAR_SKIN) {
            upBottomBarSkin()
        }
        observeEvent<String>(PreferKey.threadCount) {
            viewModel.upPool()
        }
        observeEvent<List<Book>>(EventBus.UP_BOOKS_TOC) {
            viewModel.upToc(
                it,
                onlyUpdateRead = false,
                policy = TocUpdatePolicy.SKIP_PRE_DOWNLOAD,
                refreshBookInfo = true,
            )
        }
    }

    private fun upBottomMenu() {
        val showDiscovery = AppConfig.showDiscovery
        val showRss = AppConfig.showRSS
        binding.bottomNavigationView.menu.let { menu ->
            menu.findItem(R.id.menu_discovery).isVisible = showDiscovery
            menu.findItem(R.id.menu_rss).isVisible = showRss
        }
        var index = 0
        if (showDiscovery) {
            index++
            realPositions[index] = idExplore
        }
        if (showRss) {
            index++
            realPositions[index] = idRss
        }
        index++
        realPositions[index] = idMy
        bottomMenuCount = index + 1
        adapter.notifyDataSetChanged()
    }

    private fun scheduleSourceSharePassphraseRead(delayMillis: Long) {
        passphraseReadGeneration++
        pendingPassphraseRead = true
        if (hasWindowFocus()) {
            readSourceSharePassphrase(delayMillis, passphraseReadGeneration)
        }
    }

    private fun canAwaitPassphraseWindowFocus(): Boolean {
        return SourceSharePassphraseImportPolicy.canAwaitWindowFocus(
            privacyPolicyOk = LocalConfig.privacyPolicyOk,
            isFinishing = isFinishing,
            isResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            isFragmentStateSaved = supportFragmentManager.isStateSaved
        )
    }

    private fun readSourceSharePassphrase(
        delayMillis: Long,
        generation: Int = passphraseReadGeneration
    ) {
        pendingPassphraseRead = false
        binding.viewPagerMain.postDelayed(delayMillis) {
            if (generation != passphraseReadGeneration) return@postDelayed
            val hasWindowFocus = hasWindowFocus()
            if (!hasWindowFocus) {
                pendingPassphraseRead = canAwaitPassphraseWindowFocus()
            }
            if (!SourceSharePassphraseImportPolicy.canReadClipboard(
                    privacyPolicyOk = LocalConfig.privacyPolicyOk,
                    isFinishing = isFinishing,
                    isResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                    isFragmentStateSaved = supportFragmentManager.isStateSaved,
                    hasWindowFocus = hasWindowFocus
                )
            ) {
                return@postDelayed
            }
            val text = getClipText()?.takeIf { it.isNotBlank() } ?: return@postDelayed
            if (text == lastPassphraseText) return@postDelayed
            when (val result = SourceSharePassphrase.decode(text)) {
                SourceSharePassphrase.DecodeResult.NotFound -> {
                    lastPassphraseText = null
                }

                SourceSharePassphrase.DecodeResult.Invalid -> {
                    lastPassphraseText = text
                    toastOnUi(R.string.shibboleth_invalid)
                }

                SourceSharePassphrase.DecodeResult.Expired -> {
                    lastPassphraseText = text
                    toastOnUi(R.string.shibboleth_expired)
                }

                is SourceSharePassphrase.DecodeResult.Success -> {
                    lastPassphraseText = text
                    clearClip()
                    val value = result.value
                    when (value.type) {
                        SourceSharePassphrase.Type.BOOK_SOURCE ->
                            showDialogFragment(ImportBookSourceDialog(value.url))

                        SourceSharePassphrase.Type.RSS_SOURCE ->
                            showDialogFragment(ImportRssSourceDialog(value.url))

                        SourceSharePassphrase.Type.DICT_RULE ->
                            showDialogFragment(ImportDictRuleDialog(value.url))

                        SourceSharePassphrase.Type.REPLACE_RULE ->
                            showDialogFragment(ImportReplaceRuleDialog(value.url))

                        SourceSharePassphrase.Type.TOC_RULE ->
                            showDialogFragment(ImportTxtTocRuleDialog(value.url))

                        SourceSharePassphrase.Type.AUTO_TASK ->
                            showDialogFragment(ImportAutoTaskDialog(value.url))
                    }
                }
            }
        }
    }

    private fun upBottomBarSkin() {
        val skin = BottomBarSkinManager.active
        if (skin.isEmpty() || !BottomBarSkinManager.hasSkin(skin)) {
            binding.bottomNavigationView.applySkin(null, 0)
            return
        }
        val sizePx = 30.dpToPx()
        val map = HashMap<Int, StateListDrawable>()
        menuIdToSlot.forEach { (id, slot) ->
            BottomBarSkinManager.getStateDrawable(skin, slot, sizePx)?.let { map[id] = it }
        }
        binding.bottomNavigationView.applySkin(map, sizePx)
    }

    private fun upHomePage() {
        when (AppConfig.defaultHomePage) {
            "bookshelf" -> {}
            "explore" -> if (AppConfig.showDiscovery) {
                binding.viewPagerMain.setCurrentItem(realPositions.indexOf(idExplore), false)
            }

            "rss" -> if (AppConfig.showRSS) {
                binding.viewPagerMain.setCurrentItem(realPositions.indexOf(idRss), false)
            }

            "my" -> binding.viewPagerMain.setCurrentItem(realPositions.indexOf(idMy), false)
        }
    }

    private fun getFragmentId(position: Int): Int {
        val id = realPositions[position]
        if (id == idBookshelf) {
            return if (AppConfig.bookGroupStyle == 1) idBookshelf2 else idBookshelf1
        }
        return id
    }

    private inner class PageChangeCallback : ViewPager.SimpleOnPageChangeListener() {

        override fun onPageSelected(position: Int) {
            pagePosition = position
            binding.bottomNavigationView.menu[realPositions[position]].isChecked = true
        }

    }

    @Suppress("DEPRECATION")
    private inner class TabFragmentPageAdapter(fm: FragmentManager) :
        FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        private fun getId(position: Int): Int {
            return getFragmentId(position)
        }

        override fun getItemPosition(any: Any): Int {
            val position = (any as MainFragmentInterface).position
                ?: return POSITION_NONE
            val fragmentId = getId(position)
            if ((fragmentId == idBookshelf1 && any is BookshelfFragment1)
                || (fragmentId == idBookshelf2 && any is BookshelfFragment2)
                || (fragmentId == idExplore && any is ExploreFragment)
                || (fragmentId == idRss && any is RssFragment)
                || (fragmentId == idMy && any is MyFragment)
            ) {
                return POSITION_UNCHANGED
            }
            return POSITION_NONE
        }

        override fun getItem(position: Int): Fragment {
            return when (getId(position)) {
                idBookshelf1 -> BookshelfFragment1(position)
                idBookshelf2 -> BookshelfFragment2(position)
                idExplore -> ExploreFragment(position)
                idRss -> RssFragment(position)
                else -> MyFragment(position)
            }
        }

        override fun getCount(): Int {
            return bottomMenuCount
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            var fragment = super.instantiateItem(container, position) as Fragment
            if (fragment.isCreated && getItemPosition(fragment) == POSITION_NONE) {
                destroyItem(container, position, fragment)
                fragment = super.instantiateItem(container, position) as Fragment
            }
            fragmentMap[getId(position)] = fragment
            return fragment
        }

    }

    override fun openImportUi(type:Int, source: String) {
        when (type) {
            0 -> showDialogFragment(
                ImportBookSourceDialog(source)
            )
            1 -> showDialogFragment(
                ImportRssSourceDialog(source)
            )
            2 -> showDialogFragment(
                ImportReplaceRuleDialog(source)
            )
        }
    }

}
