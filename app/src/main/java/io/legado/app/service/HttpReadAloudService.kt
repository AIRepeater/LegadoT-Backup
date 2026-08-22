package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.exoplayer.ReplayBuffer
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.mapAsyncIndexed
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                1800_000_000,
                1800_000_000,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            ).build()
        ).build()
    }

    /** 音频缓存上限(字节): 由"朗读缓存大小"设置项控制, 缓存实例创建时生效 */
    private val ttsCacheMaxBytes: Long get() = AppConfig.ttsCacheSize * 1024L * 1024
    private val cache by lazy {
        SimpleCache(
            File(cacheDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(ttsCacheMaxBytes),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var playErrorNo = 0
    // 服务级"连续下载失败"熔断计数: 跨段落累计, 成功时清零
    private val downloadErrorNo = AtomicInteger(0)
    // 预下载失败熔断计数: 每轮任务与下载成功时清零, 达阈值中止本轮预下载, 避免持续故障下逐段空请求
    private val preDownloadErrorNo = AtomicInteger(0)
    private val downloadTaskActiveLock = Mutex()
    // 从段落中间起播时, 完整段音频就绪后需 seek 到的估算位置
    private var pendingSeekMs = C.TIME_UNSET
    // 跨任务共享的已下载/在途缓存 key, 切章时清空; 避免任务重建后孤儿下载与新任务重复请求
    private val downloadedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var downloadedKeysTitle: String? = null
    // 在途下载器登记: download(null) 为阻塞调用, 不响应协程取消, 重建任务时需显式 cancel
    private val activeDownloaders: MutableMap<Downloader, String> =
        Collections.synchronizedMap(mutableMapOf())

    companion object {
        /** TTS 段落并行下载并发数 */
        private const val ttsConcurrentDownload = 4

        /** 预下载连续失败熔断阈值 */
        private const val ttsPreDownloadErrorLimit = 5

        /** 清除缓存请求有效时限: stopService 到 onDestroy 通常毫秒级, 超时视为悬挂请求忽略 */
        private const val clearCacheRequestTimeoutMs = 60_000L

        /** 设置页发起的清除缓存请求时间戳(ms); 服务销毁释放缓存后据此执行删除 */
        @Volatile
        var clearCacheRequestedAt = 0L

        /** 服务实例存活标记: 供设置页判断在线朗读服务是否正持有缓存句柄 */
        @Volatile
        var isRunning = false
            private set

        /** 服务未运行时由设置页调用: 异步清理全部 TTS 缓存目录 */
        fun clearTtsCacheDirsAsync(onCleared: (() -> Unit)? = null) {
            Coroutine.async {
                // 旧版非流式文件缓存目录不再使用, 无索引库, 直接删除
                FileUtils.delete(File(appCtx.cacheDir, "httpTTS").absolutePath)
                // 异步期间服务可能已启动并持有缓存句柄, 复查避免误删在用目录
                if (isRunning) return@async
                deleteSimpleCacheDir("httpTTS_cache")
                onCleared?.invoke()
            }
        }

        /** 先整体改名再删除, 避免删除期间目录内写入干扰; 经 SimpleCache.delete 连同索引库一并清理 */
        private fun deleteSimpleCacheDir(name: String) {
            val dir = File(appCtx.cacheDir, name)
            if (!dir.exists()) return
            val trash = File(appCtx.cacheDir, "$name.trash.${System.currentTimeMillis()}")
            SimpleCache.delete(
                if (dir.renameTo(trash)) trash else dir,
                StandaloneDatabaseProvider(appCtx)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        downloadTask?.cancel()
        cancelActiveDownloaders()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            // 旧版非流式文件缓存目录不再使用, 顺带清理
            FileUtils.delete(File(appCtx.cacheDir, "httpTTS").absolutePath)
            // 旧目录删除耗时期间新服务实例可能已重建缓存, 复查避免误删在用目录, 放弃悬挂请求
            if (isRunning) {
                clearCacheRequestedAt = 0L
                return@async
            }
            // 设置页发起的清缓存请求: 此时缓存已释放, 删除目录与索引库, 下次启动自动重建
            if (clearCacheRequestedAt != 0L
                && System.currentTimeMillis() - clearCacheRequestedAt <= clearCacheRequestTimeoutMs
            ) {
                clearCacheRequestedAt = 0L
                deleteSimpleCacheDir("httpTTS_cache")
                appCtx.toastOnUi(R.string.clear_cache_success)
            }
        }
    }

    override fun play() {
        pageChanged = false
        pendingSeekMs = C.TIME_UNSET
        exoPlayer.stop()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun playStop() {
        exoPlayer.stop()
        playIndexJob?.cancel()
    }

    private fun updateNextPos() {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            nextChapter()
        }
    }

    /**
     * 根据 TTS 源配置的并发率计算并行下载并发数:
     * 空或 "0" 表示不限制, 使用默认并发;
     * "N/M" 表示 M 毫秒内最多 N 个请求, 取 N(不超过默认并发), 限流器自身保证不超;
     * 纯数字表示两次请求的最小间隔毫秒数, 本质为串行, 降为 1
     */
    private fun resolveConcurrency(concurrentRate: String?): Int {
        return when {
            concurrentRate.isNullOrBlank() || concurrentRate == "0" -> ttsConcurrentDownload
            "/" in concurrentRate -> (concurrentRate.substringBefore("/").toIntOrNull() ?: 1)
                .coerceIn(1, ttsConcurrentDownload)

            else -> 1
        }
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        cancelActiveDownloaders()
        // 从段落中间起播: 缓存整段音频, 就绪后按文本占比 seek 到起播位置,
        // 避免截断文本导致跳回该段时缓存 key 不一致
        val startPos = paragraphStartPos
        val contentLength = contentList.getOrNull(nowSpeak)?.length ?: 0
        if (startPos > 0 && startPos < contentLength) {
            pendingSeekMs = startPos * 1000L / contentLength
        }
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val taskJob = currentCoroutineContext()[Job]
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val concurrency = resolveConcurrency(httpTts.concurrentRate)
                // 切换章节时重置跨任务 key 集合
                val title = textChapter?.title ?: ""
                if (downloadedKeysTitle != title) {
                    downloadedKeys.clear()
                    downloadedKeysTitle = title
                }
                // 并行下载(保序交付), 避免长段落顺序请求导致等待
                contentList.asFlow()
                    .mapAsyncIndexed(concurrency) { index, content ->
                        ensureActive()
                        if (index < nowSpeak) return@mapAsyncIndexed null
                        // 缓存 key 始终用完整段落文本, 保证前后跳段时一致
                        val fileName = md5SpeakFileName(content)
                        // 同步剔除段评占位符, 保证发送文本与缓存 key 一致
                        val speakText = content
                            .replace(ChapterProvider.reviewChar, "")
                            .replace(AppPattern.notReadAloudRegex, "")
                        val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                        if (speakText.isEmpty()) {
                            // 空段落不预下载, 播放时经数据源按需写入无声音频
                            AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$content")
                        } else if (downloadedKeys.add(fileName)) {
                            try {
                                downloadTrack(fileName, createDownloader(dataSourceFactory, fileName))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // 规则错误/重试超限等持续故障: 保留原"失败暂停阅读并终止任务"契约;
                                // 任务重建取消在途下载产生的中断异常不视为失败, 避免误暂停新任务播放
                                if (taskJob?.isActive == true) {
                                    downloadedKeys.remove(fileName)
                                    pauseReadAloud()
                                }
                                throw e
                            }
                        }
                        fileName to dataSourceFactory
                    }.collect { result ->
                        if (result == null) return@collect
                        val (fileName, dataSourceFactory) = result
                        val mediaSource = createMediaSource(dataSourceFactory, fileName)
                        launch(Main) {
                            // 任务已被取消时丢弃入列, 避免陈旧段落插回重建后的播放列表
                            if (taskJob?.isActive != true) return@launch
                            exoPlayer.addMediaSource(mediaSource)
                        }
                    }
                preDownloadAudios(httpTts) { fileName, downloader ->
                    try {
                        downloadTrack(fileName, downloader)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 预下载失败不暂停朗读, 释放 key 供后续任务重试;
                        // 任务重建显式取消在途下载产生中断异常, 不计入熔断计数(与流式分支一致)
                        if (taskJob?.isActive == true) {
                            downloadedKeys.remove(fileName)
                        }
                        if (e !is InterruptedException && e !is InterruptedIOException) {
                            preDownloadErrorNo.incrementAndGet()
                            AppLog.put("TTS预下载出错\n${e.localizedMessage}", e)
                        }
                    }
                }
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        cancelActiveDownloaders()
        // 从段落中间起播: 缓存整段音频, 就绪后按文本占比 seek 到起播位置,
        // 避免截断文本导致跳回该段时缓存 key 不一致
        val startPos = paragraphStartPos
        val contentLength = contentList.getOrNull(nowSpeak)?.length ?: 0
        if (startPos > 0 && startPos < contentLength) {
            pendingSeekMs = startPos * 1000L / contentLength
        }
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val taskJob = currentCoroutineContext()[Job]
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val concurrency = resolveConcurrency(httpTts.concurrentRate)
                val downloaderChannel = Channel<Pair<String, Downloader>>()
                // N 个消费者构成并行下载池, rendezvous channel 天然限流为 N 路并发
                repeat(concurrency) {
                    launch {
                        for ((fileName, downloader) in downloaderChannel) {
                            try {
                                downloadTrack(fileName, downloader)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // 单段失败容错, 释放 key 供后续任务重试, 避免一个段落异常取消全部并行下载
                                if (taskJob?.isActive == true) downloadedKeys.remove(fileName)
                                // 任务重建显式取消在途下载产生中断异常, 不计入熔断计数
                                if (e !is InterruptedException && e !is InterruptedIOException) {
                                    preDownloadErrorNo.incrementAndGet()
                                    AppLog.put("TTS预下载出错\n${e.localizedMessage}", e)
                                }
                            }
                        }
                    }
                }
                // 切换章节时重置跨任务 key 集合
                val title = textChapter?.title ?: ""
                if (downloadedKeysTitle != title) {
                    downloadedKeys.clear()
                    downloadedKeysTitle = title
                }
                try {
                    // 当前段立即入列表保证及时起播, 由播放器按需加载并写入缓存;
                    // 后续段先下载后按序入列表, 避免播放器预取与下载池并发写同一缓存 key
                    contentList.getOrNull(nowSpeak)?.let { content ->
                        // 缓存 key 始终用完整段落文本, 保证前后跳段时一致;
                        // 发送文本同步剔除段评占位符, 与缓存 key 一致
                        val speakText = content
                            .replace(ChapterProvider.reviewChar, "")
                            .replace(AppPattern.notReadAloudRegex, "")
                        if (speakText.isEmpty()) {
                            AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$content")
                        }
                        val fileName = md5SpeakFileName(content)
                        // 预留当前段缓存 key, 后续重复段落不投下载池, 避免与播放加载并发写同一缓存
                        downloadedKeys.add(fileName)
                        val mediaSource = createMediaSource(
                            createDataSourceFactory(httpTts, speakText), fileName
                        )
                        launch(Main) {
                            // 任务已被取消时丢弃入列, 避免陈旧段落插回重建后的播放列表
                            if (taskJob?.isActive != true) return@launch
                            exoPlayer.addMediaSource(mediaSource)
                        }
                    }
                    contentList.asFlow()
                        .mapAsyncIndexed(concurrency) { index, content ->
                            ensureActive()
                            if (index <= nowSpeak) return@mapAsyncIndexed null
                            // 发送文本剔除段评占位符, 与缓存 key 一致
                            val speakText = content
                                .replace(ChapterProvider.reviewChar, "")
                                .replace(AppPattern.notReadAloudRegex, "")
                            if (speakText.isEmpty()) {
                                AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$content")
                            }
                            val fileName = md5SpeakFileName(content)
                            val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                            if (speakText.isNotEmpty() && downloadedKeys.add(fileName)) {
                                try {
                                    downloadTrack(fileName, createDownloader(dataSourceFactory, fileName))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // 下载失败不阻断播放, 释放 key 后回退到播放器按需加载(ReplayBuffer)
                                    if (taskJob?.isActive == true) downloadedKeys.remove(fileName)
                                    AppLog.put("TTS段落下载出错\n${e.localizedMessage}", e)
                                }
                            }
                            dataSourceFactory to fileName
                        }.collect { result ->
                            if (result == null) return@collect
                            val (dataSourceFactory, fileName) = result
                            val mediaSource = createMediaSource(dataSourceFactory, fileName)
                            launch(Main) {
                                if (taskJob?.isActive != true) return@launch
                                exoPlayer.addMediaSource(mediaSource)
                            }
                        }
                    preDownloadAudios(httpTts) { fileName, downloader ->
                        // 投递到并行下载池, 在途下载数由消费者数限制, 无需用播放加载状态门控
                        downloaderChannel.send(fileName to downloader)
                    }
                } finally {
                    downloaderChannel.close()
                }
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    /** 登记并执行阻塞式下载, 完成后移出登记; 供任务重建时显式取消在途下载 */
    private fun downloadTrack(fileName: String, downloader: Downloader) {
        activeDownloaders[downloader] = fileName
        try {
            downloader.download(null)
            // 下载成功即服务可用, 清零预下载熔断计数, 保持"连续失败"语义
            preDownloadErrorNo.set(0)
        } finally {
            activeDownloaders.remove(downloader)
        }
    }

    /** 取消所有在途下载: download(null) 不响应协程取消, 孤儿下载会与新任务并发写同一缓存 */
    private fun cancelActiveDownloaders() {
        synchronized(activeDownloaders) {
            activeDownloaders.forEach { (downloader, fileName) ->
                runCatching { downloader.cancel() }
                // 被取消的在途下载不再完成, 释放 key 供新任务重试
                downloadedKeys.remove(fileName)
            }
            activeDownloaders.clear()
        }
    }

    /**
     * 预下载后续章节音频
     * 预下载章数 N(AppConfig.ttsPreDownloadChapterNum):
     * N=0 时仅预下载下一章开头(前2页), 且沿用历史行为, 下一章未就绪时直接跳过;
     * N>0 时完整预下载后续 N 章音频, 并对第 N+1 章预下载开头。
     * 由远及近倒序下载, 避免缓存逼近上限时 LRU 把临近章节先行逐出;
     * 缓存写满时中止本轮预下载, 避免逐出当前章未播段落。
     * @param submit 段落下载投递: 流式发往并行下载池, 非流式顺序执行
     */
    private suspend fun CoroutineScope.preDownloadAudios(
        httpTts: HttpTTS,
        submit: suspend (fileName: String, downloader: Downloader) -> Unit
    ) {
        val preDownloadNum = AppConfig.ttsPreDownloadChapterNum
        val book = ReadBook.book ?: return
        val baseIndex = ReadBook.durChapterIndex
        val nextTextChapter = ReadBook.nextTextChapter
        preDownloadErrorNo.set(0)
        for (offset in preDownloadNum + 1 downTo 1) {
            ensureActive()
            // 循环期间用户切章则放弃本轮, 由新任务重新预下载
            if (ReadBook.durChapterIndex != baseIndex) return
            // 持续故障熔断: 预下载失败达阈值即中止本轮, 不再逐段空请求
            if (preDownloadErrorNo.get() >= ttsPreDownloadErrorLimit) return
            val textChapter = when {
                offset > 1 -> loadTextChapter(book, baseIndex + offset)
                // 排版完成的下一章可直接使用; 未完成时另行本地排版, 避免与排版线程并发读写 pages
                nextTextChapter?.isCompleted == true -> nextTextChapter
                preDownloadNum == 0 -> return
                else -> loadTextChapter(book, baseIndex + 1)
            } ?: continue
            // 第 N+1 章只预下载开头(前2页), 其余整章预下载
            val pageEndIndex = if (offset == preDownloadNum + 1) 1 else textChapter.pages.lastIndex
            textChapter.getNeedReadAloud(0, readAloudByPage, 0, pageEndIndex)
                .splitToSequence("\n")
                .filter { it.isNotEmpty() }
                .forEach { content ->
                    ensureActive()
                    if (preDownloadErrorNo.get() >= ttsPreDownloadErrorLimit) return
                    // 缓存已满时中止预下载, 避免继续写入触发 LRU 逐出当前章未播段落
                    if (cache.cacheSpace <= 0) return
                    val fileName = md5SpeakFileName(content, textChapter)
                    // 发送文本剔除段评占位符, 与缓存 key 一致, 预下载与播放期同文本段落互相命中
                    val speakText = content
                        .replace(ChapterProvider.reviewChar, "")
                        .replace(AppPattern.notReadAloudRegex, "")
                    // 空段落由播放时按需写入无声音频; 相同文本会算出同一缓存 key, 跨任务去重避免重复下载
                    if (speakText.isEmpty() || !downloadedKeys.add(fileName)) return@forEach
                    submit(
                        fileName,
                        createDownloader(createDataSourceFactory(httpTts, speakText, true), fileName)
                    )
                }
        }
    }

    /** 加载并排版远章供预下载; 正文缺失/获取失败/含图片(排版会顺带批量下载图片)时返回 null */
    private suspend fun CoroutineScope.loadTextChapter(
        book: Book,
        chapterIndex: Int
    ): TextChapter? {
        val chapter = ReadBook.chapterList?.getOrNull(chapterIndex) ?: return null
        val content = BookHelp.getContent(book, chapter)
            ?: ReadBook.bookSource?.let { source ->
                try {
                    WebBook.getContentAwait(source, book, chapter)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            ?: return null
        if (content.contains("<img", ignoreCase = true)) return null
        // 与播放侧同链路排版, 保证段落切分与缓存 key 一致
        val textChapter = ReadBook.typesetChapterAsync(this, book, chapter, content)
        return runCatching {
            // 消费排版分页直至通道正常关闭(即排版完成), 不读 isCompleted 避免标志位可见性竞态
            for (page in textChapter.layoutChannel) {
            }
            textChapter
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.put("TTS预下载排版远章失败\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String,
        isPreDownload: Boolean = false
    ): CacheDataSource.Factory {
        // 同一段落的流与重放缓冲在多次 open 间共享,
        // MediaParser 嗅探后重新 open 时从缓冲重放, 避免对 TTS 服务器重复请求
        val replayBuffer = ReplayBuffer(supplier = {
            if (speakText.isEmpty()) {
                null
            } else {
                kotlin.runCatching {
                    runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                        getSpeakStream(httpTts, speakText, !isPreDownload)
                    }
                }.onFailure {
                    when (it) {
                        is InterruptedException,
                        is CancellationException -> Unit

                        // 预下载失败不暂停朗读, 由播放到该段落时按需重试
                        else -> if (!isPreDownload) pauseReadAloud()
                    }
                }.getOrThrow()
            } ?: if (isPreDownload) {
                // 预下载语境不允许静音占位入缓存, 获取不到音频流直接失败, 交由调用方释放 key 重试
                throw NoStackTraceException("TTS预下载获取音频流失败")
            } else {
                resources.openRawResource(R.raw.silent_sound)
            }
        })
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource({ replayBuffer.streamAt(0) }, replayBuffer)
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    private fun createMediaSource(factory: DataSource.Factory, fileName: String): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(MediaItem.fromUri(fileName))
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        countError: Boolean = true
    ): InputStream? {
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    source = httpTts,
                    readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext(),
                    variables = mapOf(
                        AppConst.JsVarName.SPEAK_TEXT to speakText,
                        AppConst.JsVarName.SPEAK_SPEED to speechRate,
                    )
                )
                var response = analyzeUrl.getResponseAwait()
                currentCoroutineContext().ensureActive()
                val checkJs = httpTts.loginCheckJs
                if (checkJs?.isNotBlank() == true) {
                    response = analyzeUrl.evalJS(checkJs, response) as Response
                }
                response.headers["Content-Type"]?.let { contentType ->
                    val contentType = contentType.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.body.string())
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException(
                                "TTS服务器返回错误：" + response.body.string()
                            )
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                response.body.byteStream().let { stream ->
                    // 下载成功, 清零连续错误计数(预下载语境不参与播放熔断计数)
                    if (countError) {
                        downloadErrorNo.set(0)
                    }
                    return stream
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        // 预下载语境不弹 toast, 失败次数由预下载熔断计数兜底
                        AppLog.put("js错误\n${e.localizedMessage}", e, countError)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        // 预下载语境不计数不重试, 直接失败该段, 由调用方释放缓存 key 供后续重试
                        if (!countError) throw e
                        if (downloadErrorNo.incrementAndGet() > 5) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            throw e
                        }
                    }

                    else -> {
                        // 预下载语境不计数不重试, 直接失败该段, 由调用方释放缓存 key 供后续重试
                        if (!countError) throw e
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        e.printOnDebug()
                        // 跨段落累计: 连续错误超阈值时暂停阅读, 保留原熔断契约
                        if (downloadErrorNo.incrementAndGet() > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        }
                        AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
                        break
                    }
                }
            }
        }
        return null
    }

    private fun md5SpeakFileName(
        content: String,
        textChapter: TextChapter? = this.textChapter
    ): String {
        // key 剔除段评占位符: 计数随排版路径/时间变化, 保留会使同一段落 key 漂移导致缓存永不命中
        val keyContent = content.replace(ChapterProvider.reviewChar, "")
        return MD5Utils.md5Encode16(textChapter?.title ?: "") + "_" +
                MD5Utils.md5Encode16("${ReadAloud.httpTTS?.url}-|-$speechRate-|-$keyContent")
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = contentList[nowSpeak].length
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..contentList[nowSpeak].length) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + i.toInt())
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        cancelActiveDownloaders()
        pendingSeekMs = C.TIME_UNSET
        // 缓存 key 含 speechRate, 调速后旧 key 全部失效, 清空避免集合无谓增长
        downloadedKeys.clear()
        downloadedKeysTitle = null
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        if (AppConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                if (pendingSeekMs != C.TIME_UNSET && exoPlayer.currentTimeline.windowCount > 0) {
                    // 从段落中间起播: 按当前段(首项)时长与文本占比估算起播位置 seek, 仅消费一次;
                    // 暂停状态下也需完成, 避免恢复时从头朗读
                    val window = Timeline.Window()
                    val duration = exoPlayer.currentTimeline.getWindow(0, window).durationMs
                    if (duration != C.TIME_UNSET && duration > 0) {
                        exoPlayer.seekTo(duration * pendingSeekMs / 1000)
                        pendingSeekMs = C.TIME_UNSET
                    }
                    // duration 未知时保留 pendingSeekMs, 待下次 READY 再消费
                }
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 结束
                playErrorNo = 0
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        updateNextPos()
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put("朗读错误\n${contentList[nowSpeak]}", error)
        deleteCurrentSpeakFile()
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        val mediaItem = exoPlayer.currentMediaItem ?: return
        // 播放出错的段落缓存已损坏, 移除缓存资源以便重试重新下载
        cache.removeResource(mediaItem.localConfiguration!!.uri.toString())
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

}
