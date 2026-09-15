package com.moread.app.ui.bookshelf

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moread.app.AppContainer
import com.moread.app.MoreadApp
import com.moread.app.core.git.GitHubRepoRef
import com.moread.app.core.git.RemoteFile
import com.moread.app.data.db.BookEntity
import com.moread.app.data.db.ProgressEntity
import com.moread.app.data.prefs.GitHubSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class BookshelfViewModel(app: Application) : AndroidViewModel(app) {
        private val SUPPORTED_IMPORT_EXTS =
            setOf("txt", "epub", "mobi", "azw3", "azw", "prc", "pdf", "cbz", "cbr")


    private val container: AppContainer = (app as MoreadApp).container

    init {
        // 排版管线升级：旧 EPUB 的超大章切分偏移失效，进书架时后台静默重解析
        viewModelScope.launch { container.bookstore.migrateEpubLayout() }
    }

    val books = kotlinx.coroutines.flow.combine(
        container.database.bookDao().observeAll(),
        container.database.progressDao().observeAll(),
    ) { all, progress -> sortShelf(all.filter { !it.ephemeral }, progress) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 市面排序：读过的按最近阅读时间置顶，未读的按加入时间倒序。 */
    private fun sortShelf(books: List<BookEntity>, progress: List<ProgressEntity>): List<BookEntity> {
        val lastRead = progress.associateBy { it.bookId } // progress 已按 updatedAt 倒序
        val readOrder = progress.map { it.bookId }
        return books.sortedWith(
            compareByDescending<BookEntity> { it.id in lastRead.keys }
                .thenBy { readOrder.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenByDescending { it.addedAt },
        )
    }

    private val _importingCount = MutableStateFlow(0)
    val importingCount = _importingCount.asStateFlow()

    /** 本地已存在的书：origin → bookId（线上行判定“已存”并取本地封面）。 */
    val savedOrigins = container.database.bookDao().observeAll()
        .map { list -> list.mapNotNull { b -> b.origin?.let { it to b.id } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val githubSettings = container.gitSettings.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GitHubSettings.Snapshot())

    private val _githubBusy = MutableStateFlow(false)
    val githubBusy = _githubBusy.asStateFlow()

    /** 线上书架状态：默认仓库的文件列表 */
    data class OnlineUi(
        val loading: Boolean = false,
        val error: String? = null,
        /** 未配置默认书库地址：引导态（非错误） */
        val needSetup: Boolean = false,
        val repoSlug: String = "",
        val files: List<RemoteFile> = emptyList(),
        /** 正在导入/缓存的文件路径（加载指示） */
        /** path → 字节进度（total=-1 且 done=-1 排队；=0 连接中；total>0 已知总长按字节累计） */
        val working: Map<String, DlState> = emptyMap(),
        /** 全部缓存批次进度 */
        val batchDone: Int = 0,
        val batchTotal: Int = 0,
        /** 批次中失败的书（行内显示“重试”） */
        val failedPaths: Set<String> = emptySet(),
    )

    private val _online = MutableStateFlow(OnlineUi())
    // 下载/解析活跃期间挂前台服务保活（通知常驻+唤醒锁），空闲即撤。
    // 必须作为 _online 之后的属性启动：init 块里 Main.immediate 协程会在
    // 构造期立刻执行，早于下方属性初始化 -> 空指针闪退。
    private val keepAliveWatcher = viewModelScope.launch {
        _online.map { it.working.isNotEmpty() }.distinctUntilChanged().collect { active ->
            val app = getApplication<Application>()
            if (active) com.moread.app.download.KeepAliveService.start(app)
            else com.moread.app.download.KeepAliveService.stop(app)
        }
    }
    val online = _online.asStateFlow()

    /** 刷新线上书架（默认仓库）。 */
    fun refreshOnline() {
        // 刷新只更新文件列表，绝不清空下载/解析进度；批次进行中不允许刷新
        if (_online.value.loading || _online.value.batchTotal > 0) return
        // 未配置书库地址：正常引导态，不是加载失败
        if (githubSettings.value.defaultRepo.isBlank()) {
            _online.value = _online.value.copy(loading = false, error = null, needSetup = true, files = emptyList())
            return
        }
        _online.value = _online.value.copy(loading = true, error = null, needSetup = false)
        githubAction({
            val settings = container.gitSettings.snapshot()
            val ref = com.moread.app.core.git.RepoUrlParser.parse(settings.defaultRepo)
                ?: error("默认书库地址无效，请到「连接设置」修改")
            val api = container.buildGithubApi()
            val branch = api.resolveBranch(ref)
            Triple(ref.repoSlug, branch, api.listBookFiles(ref, branch))
        }) { r ->
            r.onSuccess { (slug, _, files) ->
                _online.value = _online.value.copy(
                    loading = false, error = null, repoSlug = slug, files = files,
                )
            }.onFailure {
                _online.value = _online.value.copy(loading = false, error = it.message)
            }
        }
    }

    /** 线上条目点击：可读格式导入并解析；否则仅缓存。已拉取过的直接复用本地副本。 */
    fun pullOnline(file: RemoteFile, forceCache: Boolean, onDone: (Result<Long>) -> Unit) {
        val readable = !forceCache && isReadableRemote(file.path)
        // 仅缓存类操作显示行内 loading；点读不转圈（直接等阅读器打开）
        if (forceCache || !readable) {
            setWorking(file.path, DlState.CONNECT)
        }
        githubAction({
            val settings = container.gitSettings.snapshot()
            val ref = com.moread.app.core.git.RepoUrlParser.parse(settings.defaultRepo)
                ?: error("默认书库地址无效")
            val api = container.buildGithubApi()
            val branch = api.resolveBranch(ref)
            val origin = "github://${ref.owner}/${ref.repo}/$branch/${file.path}"
            val existing = container.database.bookDao().findByOrigin(origin)
            // 点读复用：临时记录或正式记录都可秒开；持久化去重在 Bookstore 内处理
            if (existing != null && !existing.ephemeral && (
                    existing.status == BookEntity.STATUS_READY ||
                        existing.status == BookEntity.STATUS_CACHED
                    )
            ) {
                return@githubAction existing.id
            }
            val progress = dlProgress(file.path)
            if (readable) {
                // 点读：临时会话（cacheDir），不落书库
                container.bookstore.openRemoteForReading(api, ref, file, progress).getOrThrow()
            } else if (isReadableRemote(file.path)) {
                // 缓存按钮：可读格式入库并解析（与「全部缓存」一致）
                container.bookstore.importFromGithub(api, ref, file, null, progress).getOrThrow()
            } else {
                container.bookstore.cacheRemoteFile(api, ref, file, progress).getOrThrow()
            }
        }) { r ->
            setWorking(file.path, null)
            onDone(r)
        }
    }

    private fun isReadableRemote(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".txt") || lower.endsWith(".epub") ||
            lower.endsWith(".mobi") || lower.endsWith(".azw3") ||
            lower.endsWith(".azw") || lower.endsWith(".prc") ||
            lower.endsWith(".pdf") || lower.endsWith(".cbz") ||
            lower.endsWith(".cbr")
    }

    /** 下载进度状态。QUEUED=排队等待并发位；CONNECT=已开始连接；其余为字节累计。 */
    data class DlState(val done: Long, val total: Long) {
        companion object {
            val QUEUED = DlState(-1, -1)
            val CONNECT = DlState(0, 0)
            val PARSING = DlState(-2, -2)
        }
    }

    private fun setWorking(path: String, state: DlState?) {
        _online.value = _online.value.copy(
            working = _online.value.working.toMutableMap().apply {
                if (state == null) remove(path) else put(path, state)
            },
        )
    }

    private fun dlProgress(path: String): (Long, Long) -> Unit = { d, t ->
        setWorking(path, DlState(d, if (t > 0) t else -1))
    }

    /** 单本缓存（独立协程，可与其他书并行；按钮各自旋转）。 */
    fun cacheOne(file: RemoteFile, onDone: (Result<Long>) -> Unit = {}) {
        viewModelScope.launch {
            setWorking(file.path, DlState.CONNECT)
            val r = runCatching {
                val settings = container.gitSettings.snapshot()
                val ref = com.moread.app.core.git.RepoUrlParser.parse(settings.defaultRepo)
                    ?: error("默认书库地址无效")
                val api = container.buildGithubApi()
                if (isReadableRemote(file.path)) {
                    container.bookstore.importFromGithub(api, ref, file, null, dlProgress(file.path)) { p ->
    setWorking(file.path, DlState(p.toLong(), -2))
}.getOrThrow()
                } else {
                    container.bookstore.cacheRemoteFile(api, ref, file, dlProgress(file.path)).getOrThrow()
                }
            }
            setWorking(file.path, null)
            onDone(r)
        }
    }

    /**
     * 批量缓存：并发限 3（私有仓库只有直连一线，全开会把带宽摊薄到全部超时），
     * 逐本完成计数，回调 (成功数, 失败数)。
     */
    fun cacheFiles(files: List<RemoteFile>, onDone: (Int, Int) -> Unit) {
        if (files.isEmpty()) return
        // 全部行预置旋转（0=排队中），防止用户误点其余书；逐本开始后刷新百分比
        _online.value = _online.value.copy(
            batchTotal = files.size, batchDone = 0, failedPaths = emptySet(),
            working = files.associate { it.path to DlState.QUEUED },
        )
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            val sem = kotlinx.coroutines.sync.Semaphore(3)
            val jobs = files.map { f ->
                launch {
                    sem.withPermit {
                        // 拿到并发位即视为下载中（行内显示进度而非“排队”）
                        setWorking(f.path, DlState.CONNECT)
                        val r = runCatching {
                            val settings = container.gitSettings.snapshot()
                            val ref = com.moread.app.core.git.RepoUrlParser.parse(settings.defaultRepo)
                                ?: error("默认书库地址无效")
                            val api = container.buildGithubApi()
                            if (isReadableRemote(f.path)) {
                                container.bookstore.importFromGithub(api, ref, f, null, dlProgress(f.path)) { p ->
    setWorking(f.path, DlState(p.toLong(), -2))
}.getOrThrow()
                            } else {
                                container.bookstore.cacheRemoteFile(api, ref, f, dlProgress(f.path)).getOrThrow()
                            }
                        }
                        setWorking(f.path, null)
                        if (r.isSuccess) ok++ else {
                            failed++
                            _online.value = _online.value.copy(
                                failedPaths = _online.value.failedPaths + f.path,
                            )
                        }
                        _online.value = _online.value.copy(batchDone = _online.value.batchDone + 1)
                    }
                }
            }
            jobs.joinAll()
            _online.value = _online.value.copy(batchTotal = 0, batchDone = 0)
            onDone(ok, failed)
        }
    }

    /** GitHub 通用动作：busy 包一层，结果回抛给对话框 UI。 */
    private fun <T> githubAction(block: suspend () -> T, onDone: (Result<T>) -> Unit) {
        viewModelScope.launch {
            _githubBusy.value = true
            val r = runCatching { block() }
            r.onFailure { android.util.Log.e("MoRead", "GitHub 操作失败", it) }
            _githubBusy.value = false
            onDone(r)
        }
    }

    fun listGithubFiles(
        url: String,
        onDone: (Result<Triple<GitHubRepoRef, String, List<RemoteFile>>>) -> Unit,
    ) {
        githubAction({
            val ref = com.moread.app.core.git.RepoUrlParser.parse(url) ?: error("无法识别仓库地址")
            val api = container.buildGithubApi()
            val branch = api.resolveBranch(ref)
            Triple(ref, branch, api.listBookFiles(ref, branch))
        }, onDone)
    }

    fun importGithubFile(
        ref: GitHubRepoRef,
        file: RemoteFile,
        onDone: (Result<Long>) -> Unit,
    ) {
        viewModelScope.launch {
            onDone(container.bookstore.importFromGithub(container.buildGithubApi(), ref, file))
        }
    }

    fun updateGithubBook(book: BookEntity, onDone: (Result<String>) -> Unit) {
        githubAction({ container.bookstore.updateFromGithub(container.buildGithubApi(), book.id).getOrThrow() }, onDone)
    }

    fun pushBook(
        book: BookEntity,
        targetUrl: String,
        targetPath: String,
        message: String,
        branch: String = "main",
        onDone: (Result<Unit>) -> Unit,
    ) {
        githubAction({
            val ref = com.moread.app.core.git.RepoUrlParser.parse(targetUrl) ?: error("无法识别仓库地址")
            container.bookstore.pushBookToGithub(
                container.buildGithubApi(), book.id, ref, targetPath, message, branch,
            ).getOrThrow()
        }, onDone)
    }

    fun saveGithubSettings(
        pat: String,
        apiBase: String,
        rawBase: String,
        defaultRepo: String,
        readOnly: Boolean,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch {
            container.gitSettings.save(pat, apiBase, rawBase, defaultRepo, readOnly)
            onDone()
        }
    }

    /** 导入：按真实文件名（DISPLAY_NAME）做扩展名校验，不支持的拒收。 */
    fun import(uris: List<Uri>, onRejected: (Int) -> Unit = {}) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val valid = ArrayList<Uri>(uris.size)
            for (uri in uris) {
                val name = runCatching {
                    getApplication<Application>().contentResolver.query(uri, null, null, null, null)
                        ?.use { c ->
                            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                        }
                }.getOrNull() ?: uri.toString().substringAfterLast('/')
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in SUPPORTED_IMPORT_EXTS) valid += uri
            }
            onRejected(uris.size - valid.size)
            if (valid.isEmpty()) return@launch
            _importingCount.value += valid.size
            for (uri in valid) {
                runCatching { container.bookstore.importBook(uri) }
                _importingCount.value -= 1
            }
        }
    }

    fun delete(book: BookEntity) {
        viewModelScope.launch { container.bookstore.deleteBook(book.id) }
    }

    /** 批量推送选中书籍到默认仓库的 share 分支（分支自动创建），路径按扩展名归位。 */
    fun pushBooksSelected(books: List<BookEntity>, onDone: (Result<Int>) -> Unit) {
        githubAction({
            val settings = container.gitSettings.snapshot()
            val ref = com.moread.app.core.git.RepoUrlParser.parse(settings.defaultRepo)
                ?: error("默认书库地址无效，请先到设置配置")
            val api = container.buildGithubApi()
            var ok = 0
            for (b in books) {
                runCatching {
                    val fileName = b.filePath.substringAfterLast('/')
                    val folder = fileName.substringAfterLast('.', "").lowercase().ifBlank { "txt" }
                    container.bookstore.pushBookToGithub(
                        api, b.id, ref, "$folder/$fileName", "MoRead 批量备份：$fileName", "share",
                    ).getOrThrow()
                }.onSuccess { ok++ }
            }
            ok
        }) { r -> onDone(r) }
    }

    /** 批量删除书（含本地文件/章节/进度/封面）。 */
    fun deleteBooks(ids: List<Long>) {
        viewModelScope.launch {
            for (id in ids) {
                runCatching { container.bookstore.deleteBook(id) }
                runCatching { java.io.File(getApplication<Application>().filesDir, "covers/$id.img").delete() }
            }
        }
    }

    fun reparse(book: BookEntity) {
        viewModelScope.launch { runCatching { container.bookstore.reparse(book.id) } }
    }

    fun rename(bookId: Long, title: String) {
        viewModelScope.launch { container.bookstore.rename(bookId, title) }
    }
}
