// DO NOT EDIT — generated
package tv.own.owntv.features.aisubtitle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.ffmpeg.FfmpegAudioRenderer
import tv.own.owntv.player.ffmpeg.FfmpegLibrary
import java.io.ByteArrayOutputStream
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

private const val TAG = "AiAudioScout"

/**
 * 16kHz 单声道 16-bit PCM 音频块数据包。
 *
 * @property pcmBytes 16000Hz, Mono, 16-bit Little-Endian 原始 PCM 字节数组
 * @property startElapsedMs 该段音频开始采集时的系统时钟 (SystemClock.elapsedRealtime())
 * @property audioPtsMs 该段音频对应的累计已解码有效音频时钟（毫秒），用于与主播放器实际播放进度精准对齐
 * @property durationMs 音频时长（毫秒）
 * @property rmsEnergy 归一化均方根能量 (0.0 ~ 1.0)，用于静音检测 (VAD)
 */
data class Pcm16kChunk(
    val pcmBytes: ByteArray,
    val startElapsedMs: Long,
    val audioPtsMs: Long = 0L,
    val durationMs: Long,
    val rmsEnergy: Float,
) {
    /** 将 16kHz Mono 16-bit PCM 封装为标准 44 字节 RIFF WAV 字节数组，供 OpenAI 兼容接口使用 */
    fun toWavBytes(): ByteArray {
        val dataSize = pcmBytes.size
        val totalDataLen = dataSize + 36
        val byteRate = TARGET_SAMPLE_RATE * 1 * 2 // 32000 bytes/sec
        val out = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        out.put('R'.code.toByte())
        out.put('I'.code.toByte())
        out.put('F'.code.toByte())
        out.put('F'.code.toByte())
        out.putInt(totalDataLen)
        out.put('W'.code.toByte())
        out.put('A'.code.toByte())
        out.put('V'.code.toByte())
        out.put('E'.code.toByte())
        // fmt sub-chunk
        out.put('f'.code.toByte())
        out.put('m'.code.toByte())
        out.put('t'.code.toByte())
        out.put(' '.code.toByte())
        out.putInt(16) // PCM fmt chunk size
        out.putShort(1.toShort()) // AudioFormat = 1 (PCM)
        out.putShort(1.toShort()) // NumChannels = 1 (Mono)
        out.putInt(TARGET_SAMPLE_RATE) // SampleRate = 16000
        out.putInt(byteRate) // ByteRate = 32000
        out.putShort(2.toShort()) // BlockAlign = 2
        out.putShort(16.toShort()) // BitsPerSample = 16
        // data sub-chunk
        out.put('d'.code.toByte())
        out.put('a'.code.toByte())
        out.put('t'.code.toByte())
        out.put('a'.code.toByte())
        out.putInt(dataSize)
        out.put(pcmBytes)
        return out.array()
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 16000
    }
}

/**
 * 16kHz 单声道 16-bit PCM 音频重采样与静音输出处理器。
 *
 * 核心职责：
 * 1. 接收任意采样率（如 48000Hz、44100Hz、32000Hz）与声道数（1~8 声道）的 16-bit / Float PCM 输入；
 * 2. 将多声道混音为单声道（Mono），并线性插值重采样至 16000Hz 16-bit Little-Endian PCM；
 * 3. 维护累计有效音频时钟 [cumulativeAudioMs]，实时回调：
 *    - [onFramePcm]：每约 100ms（3200 字节）回调一次，携带当前帧起始的 `audioPtsMs`；
 *    - [onSegmentPcm]：每约 2.8 秒或检测到语句停顿时回调一次，携带切片起始的 `audioPtsMs`；
 * 4. 支持两种工作模式：
 *    - **独立 DefaultAudioSink 静音超前探路模式 ([queueInput])**向下游输出等长静音字节，维持正常的硬件 AudioTrack 时钟；
 *    - **主播放器无损旁路镜像模式 ([tapPcmBuffer])**直接从主播放器 AudioSink 旁路复制已消费的 PCM 字节，零额外网络连接。
 */
@UnstableApi
class Pcm16kAudioProcessor(
    private val onFramePcm: (ByteArray, Long) -> Unit,
    private val onSegmentPcm: (Pcm16kChunk) -> Unit,
) : BaseAudioProcessor() {

    @Volatile private var inSampleRate = 48000
    @Volatile private var inChannels = 2
    @Volatile private var inEncoding = C.ENCODING_PCM_16BIT
    private var resamplePos = 0.0
    private var totalFramesSent = 0L

    /** 累计已解码输出的 16kHz 音频毫秒数（每 3200 字节精确递增 100ms） */
    @Volatile var cumulativeAudioMs: Long = 0L
        private set

    // 100ms 实时流式帧缓冲 (16000Hz * 2 bytes * 0.1s = 3200 bytes)
    private val frameBuffer = ByteArrayOutputStream(6400)

    // 分段音频缓冲（用于 OpenAI 兼容 HTTP 引擎，约 2.0s ~ 3.2s 一段）
    private val segmentBuffer = ByteArrayOutputStream(TARGET_SAMPLE_RATE * 2 * 5)
    private var segmentStartElapsedMs = 0L
    private var segmentStartPtsMs = 0L
    private var segmentSumSquares = 0.0
    private var segmentSampleCount = 0
    private var trailingSilentSamples = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            Log.w(TAG, "Pcm16kAudioProcessor: unsupported encoding=$enc")
            return AudioProcessor.AudioFormat.NOT_SET
        }
        inSampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        inChannels = inputAudioFormat.channelCount.coerceIn(1, 8)
        inEncoding = enc
        resamplePos = 0.0
        Log.i(TAG, "Pcm16kAudioProcessor configured: sampleRate=$inSampleRate, channels=$inChannels, encoding=$inEncoding")
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        resamplePos = 0.0
    }

    override fun onReset() {
        resamplePos = 0.0
        synchronized(this) {
            frameBuffer.reset()
            segmentBuffer.reset()
            segmentStartElapsedMs = 0L
            segmentStartPtsMs = 0L
            segmentSumSquares = 0.0
            segmentSampleCount = 0
            trailingSilentSamples = 0
            totalFramesSent = 0L
            cumulativeAudioMs = 0L
        }
    }

    /**
     * 主播放器旁路镜像入口：直接处理从主播放器 AudioSink.handleBuffer 旁路复制的 PCM 缓冲区，不修改原缓冲区。
     */
    fun tapPcmBuffer(
        dupBuffer: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        encoding: Int,
    ) {
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return
        val sr = if (sampleRate > 0) sampleRate.coerceAtLeast(8000) else inSampleRate
        val ch = if (channels > 0) channels.coerceIn(1, 8) else inChannels
        if (sr != inSampleRate || ch != inChannels || encoding != inEncoding) {
            inSampleRate = sr
            inChannels = ch
            inEncoding = encoding
            resamplePos = 0.0
            Log.i(TAG, "Pcm16kAudioProcessor tap format updated: sampleRate=$inSampleRate, channels=$inChannels, encoding=$inEncoding")
        }
        processInternal(dupBuffer, advanceInputPosition = false)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val bytesPerSample = if (inEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = inChannels * bytesPerSample
        val inFrames = remaining / frameSize
        if (inFrames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val consumedBytes = inFrames * frameSize
        processInternal(inputBuffer, advanceInputPosition = true)

        // 向下游 DefaultAudioSink 输出等长的静音 PCM 数据，驱动硬件 AudioTrack 时钟正常走字进入 STATE_READY
        val silentOut = replaceOutputBuffer(consumedBytes)
        for (i in 0 until consumedBytes) {
            silentOut.put(0.toByte())
        }
        silentOut.flip()
    }

    private fun processInternal(inputBuffer: ByteBuffer, advanceInputPosition: Boolean) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val bytesPerSample = if (inEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = inChannels * bytesPerSample
        val inFrames = remaining / frameSize
        if (inFrames <= 0) {
            if (advanceInputPosition) inputBuffer.position(inputBuffer.limit())
            return
        }

        val monoIn = FloatArray(inFrames)
        val leBuffer = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        if (inEncoding == C.ENCODING_PCM_FLOAT) {
            val srcFloat = leBuffer.asFloatBuffer()
            for (f in 0 until inFrames) {
                var sum = 0f
                val baseIdx = f * inChannels
                for (c in 0 until inChannels) {
                    sum += srcFloat.get(baseIdx + c)
                }
                monoIn[f] = (sum / inChannels).coerceIn(-1.0f, 1.0f) * 32767f
            }
        } else {
            val srcShort = leBuffer.asShortBuffer()
            for (f in 0 until inFrames) {
                var sum = 0f
                val baseIdx = f * inChannels
                for (c in 0 until inChannels) {
                    sum += srcShort.get(baseIdx + c).toFloat()
                }
                monoIn[f] = sum / inChannels
            }
        }

        if (advanceInputPosition) {
            val consumedBytes = inFrames * frameSize
            inputBuffer.position(inputBuffer.position() + consumedBytes)
        }

        // 重采样至 16000Hz Mono 16-bit LE
        val step = inSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        var pos = resamplePos
        val nowElapsed = SystemClock.elapsedRealtime()

        val readyFrames = mutableListOf<Pair<ByteArray, Long>>()
        var readySegment: Pcm16kChunk? = null

        synchronized(this) {
            if (segmentStartElapsedMs == 0L) {
                segmentStartElapsedMs = nowElapsed
                segmentStartPtsMs = cumulativeAudioMs
            }
            while (pos < inFrames) {
                val idx = pos.toInt()
                val frac = (pos - idx).toFloat()
                val s0 = monoIn[idx]
                val s1 = if (idx + 1 < inFrames) monoIn[idx + 1] else s0
                val sampleFloat = (s0 + (s1 - s0) * frac).coerceIn(-32768f, 32767f)
                val sampleShort = sampleFloat.toInt().toShort()

                val lo = (sampleShort.toInt() and 0xFF).toByte()
                val hi = ((sampleShort.toInt() shr 8) and 0xFF).toByte()

                frameBuffer.write(lo.toInt())
                frameBuffer.write(hi.toInt())

                segmentBuffer.write(lo.toInt())
                segmentBuffer.write(hi.toInt())

                val norm = sampleFloat / 32768f
                val sq = (norm * norm).toDouble()
                segmentSumSquares += sq
                segmentSampleCount++

                if (kotlin.math.abs(norm) < SILENCE_SAMPLE_THRESHOLD) {
                    trailingSilentSamples++
                } else {
                    trailingSilentSamples = 0
                }

                if (frameBuffer.size() >= FRAME_BYTES_100MS) {
                    val framePts = cumulativeAudioMs
                    readyFrames.add(frameBuffer.toByteArray() to framePts)
                    frameBuffer.reset()
                    totalFramesSent++
                    cumulativeAudioMs = totalFramesSent * 100L
                    if (totalFramesSent == 1L || totalFramesSent % 30L == 0L) {
                        Log.i(TAG, "AudioScout streamed ${cumulativeAudioMs / 1000.0}s PCM audio to AI engine (sr=$inSampleRate, ch=$inChannels)")
                    }
                }

                pos += step
            }
            resamplePos = pos - inFrames

            val reachedNaturalBreak =
                segmentSampleCount >= MIN_SEGMENT_SAMPLES && trailingSilentSamples >= PAUSE_SILENCE_SAMPLES
            val reachedMaxLen = segmentSampleCount >= MAX_SEGMENT_SAMPLES

            if (reachedNaturalBreak || reachedMaxLen) {
                val pcmBytes = segmentBuffer.toByteArray()
                val durMs = (segmentSampleCount * 1000L) / TARGET_SAMPLE_RATE
                val rms = if (segmentSampleCount > 0) {
                    sqrt(segmentSumSquares / segmentSampleCount).toFloat()
                } else {
                    0f
                }
                readySegment = Pcm16kChunk(
                    pcmBytes = pcmBytes,
                    startElapsedMs = segmentStartElapsedMs,
                    audioPtsMs = segmentStartPtsMs,
                    durationMs = durMs,
                    rmsEnergy = rms,
                )
                segmentBuffer.reset()
                segmentStartElapsedMs = nowElapsed
                segmentStartPtsMs = cumulativeAudioMs
                segmentSumSquares = 0.0
                segmentSampleCount = 0
                trailingSilentSamples = 0
            }
        }

        for ((frame, pts) in readyFrames) {
            runCatching { onFramePcm(frame, pts) }
        }
        readySegment?.let { runCatching { onSegmentPcm(it) } }
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 16000
        private const val FRAME_BYTES_100MS = 3200 // 100ms @ 16kHz 16-bit mono
        private const val MIN_SEGMENT_SAMPLES = (TARGET_SAMPLE_RATE * 2.0).toInt() // 2.0s
        private const val MAX_SEGMENT_SAMPLES = (TARGET_SAMPLE_RATE * 3.2).toInt() // 3.2s
        private const val PAUSE_SILENCE_SAMPLES = (TARGET_SAMPLE_RATE * 0.25).toInt() // 250ms 停顿断句
        private const val SILENCE_SAMPLE_THRESHOLD = 0.012f
    }
}

/**
 * 挂载于主播放器 (LivePreviewEngine) 音频渲染器上的无损旁路镜像 AudioSink。
 */
@UnstableApi
class TapForwardingAudioSink(
    private val delegate: AudioSink,
    @Volatile var activeProcessor: Pcm16kAudioProcessor?,
    private val playerRef: ExoPlayer,
) : AudioSink by delegate {

    @Volatile private var configuredSampleRate = 48000
    @Volatile private var configuredChannels = 2
    @Volatile private var configuredEncoding = C.ENCODING_PCM_16BIT

    init {
        syncFormatFromPlayer()
    }

    fun syncFormatFromPlayer() {
        runCatching {
            val fmt = playerRef.audioFormat
            if (fmt != null) {
                if (fmt.sampleRate > 0) configuredSampleRate = fmt.sampleRate
                if (fmt.channelCount > 0) configuredChannels = fmt.channelCount
                if (fmt.pcmEncoding == C.ENCODING_PCM_16BIT || fmt.pcmEncoding == C.ENCODING_PCM_FLOAT) {
                    configuredEncoding = fmt.pcmEncoding
                }
            }
        }
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (inputFormat.sampleRate > 0) configuredSampleRate = inputFormat.sampleRate
        if (inputFormat.channelCount > 0) configuredChannels = inputFormat.channelCount
        val enc = if (inputFormat.pcmEncoding == C.ENCODING_PCM_16BIT || inputFormat.pcmEncoding == C.ENCODING_PCM_FLOAT) {
            inputFormat.pcmEncoding
        } else {
            C.ENCODING_PCM_16BIT
        }
        configuredEncoding = enc
        Log.i(TAG, "TapForwardingAudioSink configure: sr=$configuredSampleRate, ch=$configuredChannels, enc=$configuredEncoding, mime=${inputFormat.sampleMimeType}")
        delegate.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val proc = activeProcessor
        if (proc == null) {
            return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        val startPos = buffer.position()
        val origLimit = buffer.limit()
        val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

        val handled = delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val endPos = if (handled) origLimit else buffer.position()

        if (endPos > startPos) {
            dup.position(startPos)
            dup.limit(endPos)
            proc.tapPcmBuffer(dup, configuredSampleRate, configuredChannels, configuredEncoding)
        }
        return handled
    }
}

/**
 * 音频采集与超前探路器 (AiAudioScout)。
 *
 * 采用“超前探路优先 + 单连接异常自动回退直采”双模自适应架构：
 * 1. **主模式（超前探路对齐模式）**：启动独立静音 ExoPlayer 超前拉取音频轨，配合主画面暂停攒出 `leadSeconds` 真实音频超前量，
 *    既能 100% 抵消 AI 识别翻译延迟，又能为用户的“字幕提前/延后”双向微调提供充足的未来字幕缓冲；
 * 2. **回退模式（零连接旁路直采模式）**：若遇到单连接限制（Max Connections = 1）导致独立探路器连接报错或超时，
 *    自动销毁独立播放器并无缝切换至 [TapForwardingAudioSink] 挂载主播放器内存直采，确保不断流。
 */
@UnstableApi
class AiAudioScout(
    private val context: Context,
    private val onFramePcm: (ByteArray, Long) -> Unit,
    private val onSegmentPcm: (Pcm16kChunk) -> Unit,
    private val onScoutError: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null
    private var currentUrl: String? = null
    private val attachedSinks = mutableListOf<TapForwardingAudioSink>()
    @Volatile private var activeProcessor: Pcm16kAudioProcessor? = null

    /** 当前是否处于主播放器内存直采回退模式（false 表示独立超前探路模式） */
    @Volatile var isDirectTapMode: Boolean = false
        private set

    /** 独立探路器当前是否因主播放器长时间卡顿而被临时暂停等待 */
    @Volatile var isStandalonePausedForMainStall: Boolean = false
        private set

    /** 当前会话已累计解码并送往 AI 的有效音频毫秒数 */
    val decodedAudioMs: Long
        get() = activeProcessor?.cumulativeAudioMs ?: 0L

    private val okHttpClient: OkHttpClient by lazy {
        val base = runCatching {
            org.koin.core.context.GlobalContext.get().get<tv.own.owntv.core.network.StreamingHttpClient>().client
        }.getOrElse {
            runCatching {
                org.koin.core.context.GlobalContext.get().get<OkHttpClient>()
            }.getOrNull()
        }
        if (base != null) {
            base.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        } else {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    /**
     * 当主播放器卡顿导致探路器超前过多时，可临时暂停/恢复独立探路播放器，防止无限超前脱节。
     */
    fun setStandalonePaused(paused: Boolean) {
        if (isDirectTapMode) return
        if (isStandalonePausedForMainStall == paused) return
        isStandalonePausedForMainStall = paused
        mainHandler.post {
            val p = exoPlayer ?: return@post
            runCatching {
                p.playWhenReady = !paused
                Log.i(TAG, "Standalone AiAudioScout paused=$paused (decodedAudioMs=$decodedAudioMs)")
            }
        }
    }

    /**
     * 挂载到主播放器 [LivePreviewEngine] 的音频渲染器上（零网络连接、零额外解码器）。
     */
    fun attachToLiveEngine(liveEngine: LivePreviewEngine): Boolean {
        return runCatching {
            val playerField = findField(liveEngine.javaClass, "player") ?: return false
            playerField.isAccessible = true
            val player = playerField.get(liveEngine) as? ExoPlayer ?: return false

            isDirectTapMode = true
            val proc = activeProcessor ?: Pcm16kAudioProcessor(
                onFramePcm = onFramePcm,
                onSegmentPcm = onSegmentPcm,
            ).also { activeProcessor = it }

            var hookedCount = 0
            for (i in 0 until player.rendererCount) {
                val renderer = player.getRenderer(i)
                if (renderer.trackType == C.TRACK_TYPE_AUDIO) {
                    val sinkField = findField(renderer.javaClass, "audioSink") ?: continue
                    sinkField.isAccessible = true
                    val currentSink = sinkField.get(renderer) as? AudioSink ?: continue
                    if (currentSink is TapForwardingAudioSink) {
                        currentSink.activeProcessor = proc
                        currentSink.syncFormatFromPlayer()
                        if (!attachedSinks.contains(currentSink)) {
                            attachedSinks.add(currentSink)
                        }
                        hookedCount++
                    } else {
                        val tapSink = TapForwardingAudioSink(currentSink, proc, player)
                        sinkField.set(renderer, tapSink)
                        attachedSinks.add(tapSink)
                        hookedCount++
                        Log.i(TAG, "Successfully attached TapForwardingAudioSink to ${renderer.javaClass.simpleName}!")
                    }
                }
            }
            hookedCount > 0
        }.getOrElse { e ->
            Log.w(TAG, "attachToLiveEngine failed: ${e.message}", e)
            false
        }
    }

    private fun findField(clazz: Class<*>, fieldName: String): Field? {
        var cur: Class<*>? = clazz
        while (cur != null && cur != Any::class.java) {
            try {
                return cur.getDeclaredField(fieldName)
            } catch (_: NoSuchFieldException) {
                cur = cur.superclass
            }
        }
        return null
    }

    /** 兼容旧版调用入口 */
    fun start(url: String, userAgent: String? = null, headers: Map<String, String>? = null) {
        startStandalone(url, userAgent, headers = headers, onStandaloneFailed = null)
    }

    /**
     * 启动独立超前音频探路采集。
     * @param headers 可选的 HTTP 请求头（如 Referer / Cookie 等）
     * @param onStandaloneFailed 当独立探路因单连接限制等原因建连失败时回调，由控制器无缝回退至主播放器直采
     */
    fun startStandalone(
        url: String,
        userAgent: String? = null,
        headers: Map<String, String>? = null,
        onStandaloneFailed: (() -> Unit)? = null,
    ) {
        mainHandler.post {
            stopStandaloneInternal()
            for (sink in attachedSinks) {
                sink.activeProcessor = null
            }
            isDirectTapMode = false
            isStandalonePausedForMainStall = false
            currentUrl = url
            runCatching {
                val processor = Pcm16kAudioProcessor(
                    onFramePcm = onFramePcm,
                    onSegmentPcm = onSegmentPcm,
                )
                activeProcessor = processor

                val renderersFactory = object : DefaultRenderersFactory(context) {
                    override fun buildVideoRenderers(
                        context: Context,
                        extensionRendererMode: Int,
                        mediaCodecSelector: MediaCodecSelector,
                        enableDecoderFallback: Boolean,
                        eventHandler: Handler,
                        eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                        allowedVideoJoiningTimeMs: Long,
                        out: ArrayList<Renderer>,
                    ) {
                        // 不构建任何视频渲染器，节省硬件视频解码器实例
                    }

                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioOutputPlaybackParams: Boolean,
                    ): AudioSink {
                        return DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(enableFloatOutput)
                            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                            .setAudioProcessors(arrayOf(processor))
                            .build()
                    }

                    override fun buildAudioRenderers(
                        context: Context,
                        extensionRendererMode: Int,
                        mediaCodecSelector: MediaCodecSelector,
                        enableDecoderFallback: Boolean,
                        audioSink: AudioSink,
                        eventHandler: Handler,
                        eventListener: AudioRendererEventListener,
                        out: ArrayList<Renderer>,
                    ) {
                        if (FfmpegLibrary.isAvailable()) {
                            out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
                        }
                        val softwareOnlySelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
                            val all = mediaCodecSelector.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
                            val sw = all.filter { it.softwareOnly || !it.hardwareAccelerated }
                            if (sw.isNotEmpty()) sw else all
                        }
                        super.buildAudioRenderers(
                            context,
                            extensionRendererMode,
                            softwareOnlySelector,
                            enableDecoderFallback,
                            audioSink,
                            eventHandler,
                            eventListener,
                            out,
                        )
                    }
                }

                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1200, 8000, 400, 800)
                    .build()

                val effectiveUa = headers?.get("User-Agent")?.takeIf { it.isNotBlank() }
                    ?: userAgent?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_UA

                val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient).apply {
                    setUserAgent(effectiveUa)
                    if (!headers.isNullOrEmpty()) {
                        val cleanHeaders = headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
                        if (cleanHeaders.isNotEmpty()) {
                            setDefaultRequestProperties(cleanHeaders)
                        }
                    }
                }

                val player = ExoPlayer.Builder(context, renderersFactory)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
                    .setLoadControl(loadControl)
                    .build()

                player.volume = 0f
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()

                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val stateName = when (playbackState) {
                            Player.STATE_IDLE -> "STATE_IDLE"
                            Player.STATE_BUFFERING -> "STATE_BUFFERING"
                            Player.STATE_READY -> "STATE_READY"
                            Player.STATE_ENDED -> "STATE_ENDED"
                            else -> "UNKNOWN($playbackState)"
                        }
                        Log.i(TAG, "Standalone AiAudioScout playbackState: $stateName, isPlaying=${player.isPlaying}")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(TAG, "Standalone AiAudioScout error: ${error.errorCodeName} - ${error.message}")
                        if (processor.cumulativeAudioMs <= 0L && onStandaloneFailed != null) {
                            // 尚未产出任何音频即报错（典型如单连接限制 HTTP 403/503）：立即通知控制器回退至主播放器直采
                            onStandaloneFailed.invoke()
                            return
                        }
                        val target = currentUrl
                        if (target != null) {
                            mainHandler.postDelayed({
                                if (currentUrl == target && exoPlayer === player && !isDirectTapMode) {
                                    runCatching {
                                        player.prepare()
                                        player.playWhenReady = !isStandalonePausedForMainStall
                                    }
                                }
                            }, 1500L)
                        } else {
                            onScoutError(error.message ?: error.errorCodeName)
                        }
                    }
                })

                Log.i(TAG, "Standalone AiAudioScout preparing stream: $url")
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.playWhenReady = true
                exoPlayer = player
            }.onFailure { e ->
                Log.e(TAG, "Failed to start standalone AiAudioScout", e)
                onStandaloneFailed?.invoke() ?: onScoutError(e.message ?: "Audio scout init failed")
            }
        }
    }

    /** 仅停止独立探路器（保留或准备切换到主播放器直采） */
    fun stopStandaloneOnly() {
        mainHandler.post {
            stopStandaloneInternal()
        }
    }

    /** 停止旁路采集与独立探路播放器 */
    fun stop() {
        mainHandler.post {
            for (sink in attachedSinks) {
                sink.activeProcessor = null
            }
            attachedSinks.clear()
            activeProcessor = null
            isDirectTapMode = false
            isStandalonePausedForMainStall = false
            stopStandaloneInternal()
        }
    }

    private fun stopStandaloneInternal() {
        currentUrl = null
        exoPlayer?.run {
            runCatching { stop() }
            runCatching { release() }
        }
        exoPlayer = null
    }

    companion object {
        private const val TAG = "AiAudioScout"
        private const val DEFAULT_UA = "VLC/3.0.20 LibVLC/3.0.20"
    }
}
