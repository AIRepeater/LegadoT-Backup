package io.legado.app.help.exoplayer

import android.annotation.SuppressLint
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/**
 * 供同一 TTS 段落被 ExoPlayer 多次 open 时共享的底层流与重放缓冲。
 *
 * ExoPlayer(平台 MediaParser) 在嗅探格式后会请求 seek 回起点, 而本数据源不可 seek,
 * 只能由上层关闭后重新 open 实现。共享该缓冲后, 重新 open 时先重放已捕获的前若干
 * 字节, 再继续读同一底层流, 避免对 TTS 服务器重复发起完全相同的第二次网络请求。
 */
class ReplayBuffer(
    private val supplier: () -> InputStream,
    private val captureLimit: Int = DEFAULT_CAPTURE_LIMIT
) {
    companion object {
        /** 捕获上限, 足以覆盖格式嗅探读取的字节数 */
        const val DEFAULT_CAPTURE_LIMIT = 64 * 1024
    }

    private val captured = ByteArrayOutputStream()
    private var stream: InputStream? = null
    private var replayOffset = 0
    private var captureFinished = false
    private var eofReached = false
    private var replayStarted = false

    /** 获取共享底层流, 首次调用时才真正发起网络请求 */
    fun stream(): InputStream = stream ?: supplier.invoke().also { stream = it }

    fun canReplay(): Boolean = replayOffset < captured.size()

    fun startReplay() {
        replayOffset = 0
        replayStarted = true
    }

    /** 从捕获缓冲重放, 返回实际重放字节数; 缓冲耗尽时返回 0 */
    fun replay(buffer: ByteArray, offset: Int, length: Int): Int {
        val remaining = captured.size() - replayOffset
        if (remaining <= 0) return 0
        val count = min(length, remaining)
        System.arraycopy(captured.toByteArray(), replayOffset, buffer, offset, count)
        replayOffset += count
        return count
    }

    /** 捕获从底层流读到的字节, 达到上限后停止捕获 */
    fun capture(buffer: ByteArray, offset: Int, length: Int) {
        if (captureFinished) return
        val count = min(length, captureLimit - captured.size())
        if (count > 0) captured.write(buffer, offset, count)
        if (captured.size() >= captureLimit) captureFinished = true
    }

    fun markEof() {
        eofReached = true
    }

    fun isEofReached(): Boolean = eofReached

    /**
     * 数据源关闭时回调。
     * 嗅探阶段的关闭(已捕获字节但尚未重放且流未读完)保留底层流, 等待重新 open 时重放;
     * 其余情况(流已读完、已开始重放或无捕获数据)直接关闭底层流。
     */
    fun onDataSourceClosed() {
        if (eofReached || replayStarted || captured.size() == 0) {
            closeStream()
        }
    }

    private fun closeStream() {
        stream?.close()
        stream = null
    }
}

@SuppressLint("UnsafeOptInUsageError")
class InputStreamDataSource(
    private val supplier: () -> InputStream,
    private val replayBuffer: ReplayBuffer? = null
) : BaseDataSource(false) {
    private var dataSpec: DataSpec? = null
    private var bytesRemaining: Long = 0
    private var opened = false
    private var replaying = false
    private val inputStream: InputStream by lazy {
        replayBuffer?.stream() ?: supplier.invoke()
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)

        if (replayBuffer?.canReplay() == true && dataSpec.position == 0L) {
            // 嗅探后的重新 open: 从共享缓冲重放, 不再发起网络请求
            replayBuffer.startReplay()
            replaying = true
        } else {
            // 统一走共享缓冲的底层流, 支持流关闭后按需重建
            (replayBuffer?.stream() ?: inputStream).skip(dataSpec.position)
        }

        bytesRemaining = dataSpec.length

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun getUri(): Uri? = dataSpec?.uri

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) {
            return 0
        } else if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }

        val bytesToRead =
            if (bytesRemaining == C.LENGTH_UNSET.toLong()) readLength
            else min(bytesRemaining, readLength.toLong()).toInt()

        val bytesRead = if (replaying) {
            val replayed = replayBuffer!!.replay(buffer, offset, bytesToRead)
            if (replayed > 0) {
                replayed
            } else {
                replaying = false
                // 底层流已读完(如静音短音频): 直接返回 EOF, 避免读已关闭的流
                if (replayBuffer?.isEofReached() == true) -1
                else readFromStream(buffer, offset, bytesToRead)
            }
        } else {
            readFromStream(buffer, offset, bytesToRead)
        }

        if (bytesRead == -1) {
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                // End of stream reached having not read sufficient data.
                throw EOFException()
            }
            return C.RESULT_END_OF_INPUT
        }

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead.toLong()
            bytesTransferred(bytesRead)
        }

        return bytesRead
    }

    private fun readFromStream(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = replayBuffer?.stream() ?: inputStream
        val bytesRead = stream.read(buffer, offset, length)
        if (bytesRead == -1) {
            replayBuffer?.markEof()
        } else if (bytesRead > 0) {
            replayBuffer?.capture(buffer, offset, bytesRead)
        }
        return bytesRead
    }

    @Throws(IOException::class)
    override fun close() {
        if (!opened) {
            return
        }
        try {
            if (replayBuffer == null) {
                inputStream.close()
            } else {
                replayBuffer.onDataSourceClosed()
            }
        } finally {
            opened = false
            transferEnded()
        }
    }
}
