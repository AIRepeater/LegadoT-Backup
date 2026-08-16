package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
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
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.exoplayer.ReplayBuffer
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.mapAsyncIndexed
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
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
    private val ttsFolderPath: String by lazy {
        cacheDir.absolutePath + File.separator + "httpTTS" + File.separator
    }
    private val cache by lazy {
        SimpleCache(
            File(cacheDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
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
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        cancelActiveDownloaders()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            removeCacheFile()
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
                // 相同文本段落会算出同一文件名, 按文件名加锁避免并发写坏同一文件
                val writingLocks = ConcurrentHashMap<String, Mutex>()
                // 并行下载(保序交付), 避免长段落顺序请求导致等待
                contentList.asFlow()
                    .mapAsyncIndexed(concurrency) { index, content ->
                        ensureActive()
                        if (index < nowSpeak) return@mapAsyncIndexed null
                        // 缓存 key 始终用完整段落文本, 保证前后跳段时一致
                        val fileName = md5SpeakFileName(content)
                        val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                        if (speakText.isEmpty()) {
                            AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$content")
                            createSilentSound(fileName)
                        } else {
                            writingLocks.getOrPut(fileName) { Mutex() }.withLock {
                                if (!hasSpeakFile(fileName)) {
                                    runCatching {
                                        val inputStream = getSpeakStream(httpTts, speakText)
                                        if (inputStream != null) {
                                            createSpeakFile(fileName, inputStream)
                                        } else {
                                            createSilentSound(fileName)
                                        }
                                    }.onFailure {
                                        if (it !is CancellationException) {
                                            // 规则错误/重试超限等持续故障: 保留原"暂停阅读"契约
                                            pauseReadAloud()
                                        }
                                        throw it
                                    }
                                }
                            }
                        }
                        fileName
                    }.collect { fileName ->
                        if (fileName == null) return@collect
                        val file = getSpeakFileAsMd5(fileName)
                        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                        launch(Main) {
                            // 任务已被取消时丢弃入列, 避免陈旧段落插回重建后的播放列表
                            if (taskJob?.isActive != true) return@launch
                            exoPlayer.addMediaItem(mediaItem)
                        }
                    }
                preDownloadAudios(httpTts)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS) {
        val textChapter = ReadBook.nextTextChapter ?: return
        val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .take(10)
            .toList()
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            val fileName = md5SpeakFileName(content, textChapter)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                createSilentSound(fileName)
            } else if (!hasSpeakFile(fileName)) {
                runCatching {
                    val inputStream = getSpeakStream(httpTts, speakText)
                    if (inputStream != null) {
                        createSpeakFile(fileName, inputStream)
                    } else {
                        createSilentSound(fileName)
                    }
                }
            }
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
                                AppLog.put("TTS预下载出错\n${e.localizedMessage}", e)
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
                        // 缓存 key 始终用完整段落文本, 保证前后跳段时一致
                        val speakText = content.replace(AppPattern.notReadAloudRegex, "")
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
                            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
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
                    preDownloadAudiosStream(httpTts, downloaderChannel)
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

    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Pair<String, Downloader>>
    ) {
        val textChapter = ReadBook.nextTextChapter ?: return
        val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .toList()
        // 相同文本段落会算出同一缓存 key, 跨任务去重避免重复下载
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            val fileName = md5SpeakFileName(content, textChapter)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty() || !downloadedKeys.add(fileName)) return@forEach
            val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
            // 投递到并行下载池, 在途下载数由消费者数限制, 无需用播放加载状态门控
            downloaderChannel.send(fileName to createDownloader(dataSourceFactory, fileName))
        }
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String
    ): CacheDataSource.Factory {
        // 同一段落的流与重放缓冲在多次 open 间共享,
        // MediaParser 嗅探后重新 open 时从缓冲重放, 避免对 TTS 服务器重复请求
        val replayBuffer = ReplayBuffer(supplier = {
            if (speakText.isEmpty()) {
                null
            } else {
                kotlin.runCatching {
                    runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                        getSpeakStream(httpTts, speakText)
                    }
                }.onFailure {
                    when (it) {
                        is InterruptedException,
                        is CancellationException -> Unit

                        else -> pauseReadAloud()
                    }
                }.getOrThrow()
            } ?: resources.openRawResource(R.raw.silent_sound)
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
        speakText: String
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
                    // 下载成功, 清零连续错误计数
                    downloadErrorNo.set(0)
                    return stream
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        if (downloadErrorNo.incrementAndGet() > 5) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            throw e
                        }
                    }

                    else -> {
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        e.printOnDebug()
                        // 跨段落累计: 连续错误超阈值时暂停阅读, 保留原熔断契约
                        if (downloadErrorNo.incrementAndGet() > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
                            break
                        }
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
        return MD5Utils.md5Encode16(textChapter?.title ?: "") + "_" +
                MD5Utils.md5Encode16("${ReadAloud.httpTTS?.url}-|-$speechRate-|-$content")
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.mp3")
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, inputStream: InputStream) {
        FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3").outputStream().use { out ->
            inputStream.use {
                it.copyTo(out)
            }
        }
    }

    /**
     * 移除缓存文件
     */
    private fun removeCacheFile() {
        val titleMd5 = MD5Utils.md5Encode16(textChapter?.title ?: "")
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L
            if ((!it.name.startsWith(titleMd5)
                        && System.currentTimeMillis() - it.lastModified() > 600000)
                || isSilentSound
            ) {
                FileUtils.delete(it.absolutePath)
            }
        }
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
        if (AppConfig.streamReadAloudAudio) {
            return
        }
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
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
