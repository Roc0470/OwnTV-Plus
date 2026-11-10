// DO NOT EDIT — generated
package tv.own.owntv.features.aisubtitle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
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
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient
import tv.own.owntv.player.StereoDownmix
import tv.own.owntv.player.ffmpeg.FfmpegAudioRenderer
import tv.own.owntv.player.ffmpeg.FfmpegLibrary
import java.io.ByteArrayOutputStream
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
 * @property durationMs 音频时长（毫秒）
 * @property rmsEnergy 归一化均方根能量 (0.0 ~ 1.0)，用于静音检测 (VAD)
 */
data class Pcm16kChunk(
    val pcmBytes: ByteArray,
    val startElapsedMs: Long,
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
 * 1. 接收任意采样率（如 48000Hz、44100Hz、32000Hz）与声道数（1~8 声道）的 16-bit PCM 输入；
 * 2. 将多声道混音为单声道（Mono），并线性插值重采样至 16000Hz 16-bit Little-Endian PCM；
 * 3. 实时回调：
 *    - [onFramePcm]：每约 100ms（3200 字节）回调一次，供 WebSocket Realtime 引擎实时流式推送；
 *    - [onSegmentPcm]：每约 2.8 秒或检测到语句停顿时回调一次，供 OpenAI 兼容分段引擎识别翻译；
 * 4. 向扬声器下游输出等长的静音 PCM 数据，确保此探路播放器（Scout）既维持正常的 Media3 音频时钟推进，
 *    又绝对不会发出任何声音干扰主播放器。
 */
@UnstableApi
class Pcm16kAudioProcessor(
    private val onFramePcm: (ByteArray) -> Unit,
    private val onSegmentPcm: (Pcm16kChunk) -> Unit,
) : BaseAudioProcessor() {

    private var inSampleRate = 48000
    private var inChannels = 2
    private var inEncoding = C.ENCODING_PCM_16BIT
    private var resamplePos = 0.0
    private var totalFramesSent = 0L

    // 100ms 实时流式帧缓冲 (16000Hz * 2 bytes * 0.1s = 3200 bytes)
    private val frameBuffer = ByteArrayOutputStream(6400)

    // 分段音频缓冲（用于 OpenAI 兼容 HTTP 引擎，约 2.4s ~ 3.6s 一段）
    private val segmentBuffer = ByteArrayOutputStream(TARGET_SAMPLE_RATE * 2 * 5)
    private var segmentStartElapsedMs = 0L
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
            segmentSumSquares = 0.0
            segmentSampleCount = 0
            trailingSilentSamples = 0
            totalFramesSent = 0L
        }
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

        // 消耗输入 Buffer 并向下游写入等长全 0 字节（静音，保持 AudioSink 推进）
        val consumedBytes = inFrames * frameSize
        inputBuffer.position(inputBuffer.position() + consumedBytes)
        val silentOut = replaceOutputBuffer(consumedBytes)
        for (i in 0 until consumedBytes) {
            silentOut.put(0.toByte())
        }
        silentOut.flip()

        // 重采样至 16000Hz Mono 16-bit LE
        val step = inSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        var pos = resamplePos
        val nowElapsed = android.os.SystemClock.elapsedRealtime()

        var readyFrame: ByteArray? = null
        var readySegment: Pcm16kChunk? = null

        synchronized(this) {
            if (segmentStartElapsedMs == 0L) {
                segmentStartElapsedMs = nowElapsed
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

                pos += step
            }
            resamplePos = pos - inFrames

            if (frameBuffer.size() >= FRAME_BYTES_100MS) {
                readyFrame = frameBuffer.toByteArray()
                frameBuffer.reset()
                totalFramesSent++
                if (totalFramesSent % 30L == 0L) {
                    Log.d(TAG, "AudioScout streamed ${totalFramesSent * 100 / 1000}s PCM audio to AI engine")
                }
            }

            // 分段切分策略：
            // 1. 已累积 >= 2.0 秒音频，且末尾出现 >= 0.28 秒静音停顿（自然断句）；
            // 2. 或已累积达到最大 3.4 秒强制切分，保证字幕实时性。
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
                    durationMs = durMs,
                    rmsEnergy = rms,
                )
                segmentBuffer.reset()
                segmentStartElapsedMs = nowElapsed
                segmentSumSquares = 0.0
                segmentSampleCount = 0
                trailingSilentSamples = 0
            }
        }

        readyFrame?.let { runCatching { onFramePcm(it) } }
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
 * 后台音频探路器 (AiAudioScout)。
 *
 * 复刻 VsTV 的“提前收听 + 延迟直播”架构：
 * - 当开启 AI 实时字幕时，主画面播放器暂停 `leadSeconds` 秒（例如 5 秒），而 [AiAudioScout] 保持在直播最前沿实时拉取音频轨道；
 * - 禁用视频轨道解码（`C.TRACK_TYPE_VIDEO` disabled），完全不占用硬件视频解码器（MediaCodec Video），只解码音频轨；
 * - 挂载 [Pcm16kAudioProcessor] 实时提取 16000Hz Mono 16-bit PCM 送往 AI 翻译引擎，同时向 AudioTrack 输出全 0 静音。
 */
@UnstableApi
class AiAudioScout(
    private val context: Context,
    private val onFramePcm: (ByteArray) -> Unit,
    private val onSegmentPcm: (Pcm16kChunk) -> Unit,
    private val onScoutError: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null
    private var currentUrl: String? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 启动或切换到指定直播流 URL 的音频探路采集 */
    fun start(url: String, userAgent: String? = null) {
        mainHandler.post {
            stopInternal()
            currentUrl = url
            runCatching {
                val processor = Pcm16kAudioProcessor(
                    onFramePcm = onFramePcm,
                    onSegmentPcm = onSegmentPcm,
                )
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
                        // 不构建任何视频渲染器，彻底节省硬件视频解码器实例
                    }

                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioOutputPlaybackParams: Boolean,
                    ): AudioSink {
                        @Suppress("DEPRECATION")
                        return DefaultAudioSink.Builder()
                            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                            .setEnableFloatOutput(false)
                            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                            .setAudioProcessors(arrayOf(StereoDownmix.processor(), processor))
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
                        super.buildAudioRenderers(
                            context,
                            extensionRendererMode,
                            mediaCodecSelector,
                            enableDecoderFallback,
                            audioSink,
                            eventHandler,
                            eventListener,
                            out,
                        )
                        if (FfmpegLibrary.isAvailable()) {
                            out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
                        }
                    }
                }

                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 1500,
                        /* maxBufferMs = */ 8000,
                        /* bufferForPlaybackMs = */ 500,
                        /* bufferForPlaybackAfterRebufferMs = */ 1000,
                    )
                    .build()

                val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient).apply {
                    setUserAgent(userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_UA)
                }

                val player = ExoPlayer.Builder(context, renderersFactory)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
                    .setLoadControl(loadControl)
                    .build()

                // 静音探路播放器，并禁用画面与字幕解码
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
                        Log.i(TAG, "AiAudioScout playbackState: $stateName, isPlaying=${player.isPlaying}")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(TAG, "AiAudioScout error: ${error.errorCodeName} - ${error.message}")
                        // 直播流瞬时波动自动重连一次
                        val target = currentUrl
                        if (target != null) {
                            mainHandler.postDelayed({
                                if (currentUrl == target && exoPlayer === player) {
                                    runCatching {
                                        player.prepare()
                                        player.playWhenReady = true
                                    }
                                }
                            }, 2000L)
                        } else {
                            onScoutError(error.message ?: error.errorCodeName)
                        }
                    }
                })

                Log.i(TAG, "AiAudioScout preparing stream: $url")
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.playWhenReady = true
                exoPlayer = player
            }.onFailure { e ->
                Log.e(TAG, "Failed to start AiAudioScout", e)
                onScoutError(e.message ?: "Audio scout init failed")
            }
        }
    }

    /** 停止并释放音频探路播放器 */
    fun stop() {
        mainHandler.post { stopInternal() }
    }

    private fun stopInternal() {
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
