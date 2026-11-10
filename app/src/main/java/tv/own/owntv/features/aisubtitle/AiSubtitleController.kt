// DO NOT EDIT — generated
package tv.own.owntv.features.aisubtitle

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlaybackEngine

/**
 * AI 实时字幕运行状态：
 * - [Off]：已关闭
 * - [Preparing]：正在提前收听并缓冲翻译（主播放器暂停，显示剩余倒计时秒数，复刻 VsTV 延迟直播对齐体验）
 * - [Running]：延迟直播同步播放中，实时按时间戳对齐弹出双语/中文翻译字幕
 * - [Error]：配置缺失或连接异常提示
 */
sealed interface AiSubtitleState {
    data object Off : AiSubtitleState
    data class Preparing(val secondsLeft: Int, val leadSeconds: Int) : AiSubtitleState
    data class Running(val statusHint: String = "") : AiSubtitleState
    data class Error(val message: String) : AiSubtitleState
}

/**
 * AI 实时字幕与延迟直播核心调度控制器 ([AiSubtitleController])。
 *
 * 核心机制：
 * 1. **提前收听 + 延迟直播对齐（Lead-time Delayed Live）**：
 *    开启 AI 字幕时，控制器立即启动后台 [AiAudioScout] 探路播放器拉取直播流最前沿音频并送往 [AiSubtitleEngine]；
 *    同时将当前主画面播放器 ([PlaybackEngine]) 暂停 `leadSeconds` 秒（例如 5 秒），并在屏幕上方展示倒计时横幅：
 *    `AI 字幕准备中 · 正在提前收听并翻译，约 X 秒后开始播放`。
 *    倒计时结束后自动恢复主画面播放，此时主画面刚好落后直播前沿 `leadSeconds` 秒，而 AI 翻译耗时恰好被这段提前量抵消，
 *    从而实现音画与翻译字幕的精准同步！
 * 2. **时间戳字幕队列调度**：
 *    每条由探路器在 `audioStartElapsedMs` 采集生成的字幕，其对应的主画面播放时刻为：
 *    `displayAtElapsedMs = audioStartElapsedMs + leadSeconds * 1000L`。
 *    调度器以 100ms 精度轮询队列，到点准时上屏展示，并在持续时长结束后自动淡出清除。
 */
@UnstableApi
class AiSubtitleController(
    context: Context,
    private val ownTVPlayer: OwnTVPlayer,
    private val livePreviewEngine: LivePreviewEngine,
) {
    val configStore = AiSubtitleConfigStore(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<AiSubtitleState>(AiSubtitleState.Off)
    val state: StateFlow<AiSubtitleState> = _state.asStateFlow()

    private val _activeCue = MutableStateFlow<AiSubtitleCue?>(null)
    val activeCue: StateFlow<AiSubtitleCue?> = _activeCue.asStateFlow()

    private val pendingCues = ArrayDeque<AiSubtitleCue>()

    private var scout: AiAudioScout? = null
    private var engine: AiSubtitleEngine? = null
    private var prepareJob: Job? = null
    private var cueTickerJob: Job? = null
    private var activeStreamUrl: String? = null
    private var pausedMainPlayerForLead = false
    private var boundPlaybackEngine: PlaybackEngine? = null

    init {
        scout = AiAudioScout(
            context = context.applicationContext,
            onFramePcm = { bytes -> engine?.onFramePcm(bytes) },
            onSegmentPcm = { chunk -> engine?.onSegmentPcm(chunk) },
            onScoutError = { err ->
                Log.w(TAG, "AudioScout error: $err")
            },
        )
    }

    /** 是否已开启 AI 字幕（含准备中与运行中） */
    val isEnabled: Boolean
        get() = _state.value !is AiSubtitleState.Off

    /**
     * 切换 AI 实时字幕开关。
     * @param playbackEngine 当前全屏/小窗正在播放的引擎
     * @param fallbackStreamUrl 若从引擎未直接读到 URL 时的备用流地址
     */
    fun toggle(playbackEngine: PlaybackEngine, fallbackStreamUrl: String? = null) {
        if (isEnabled) {
            stop()
        } else {
            start(playbackEngine, fallbackStreamUrl)
        }
    }

    /**
     * 启动 AI 实时字幕与延迟直播流水线。
     */
    fun start(playbackEngine: PlaybackEngine, fallbackStreamUrl: String? = null) {
        val cfg = configStore.config.value
        if (!cfg.isConfigured) {
            _state.value = AiSubtitleState.Error("请先在「AI 字幕设置」中配置 API Key 或扫码填入")
            return
        }

        val streamUrl = resolveActiveStreamUrl(fallbackStreamUrl, playbackEngine)
        if (streamUrl.isNullOrBlank()) {
            _state.value = AiSubtitleState.Error("未检测到当前正在播放的直播流地址")
            return
        }

        stopInternal(resumeIfPaused = false)
        boundPlaybackEngine = playbackEngine
        activeStreamUrl = streamUrl

        // 1. 构建并启动对应的 AI 翻译引擎
        val newEngine: AiSubtitleEngine = when (cfg.provider) {
            AiSubtitleProvider.OPENAI_COMPAT -> OpenAiCompatEngine(
                config = cfg,
                onCueReady = ::enqueueCue,
                onEngineStatus = ::onEngineStatusUpdate,
            )
            AiSubtitleProvider.OPENAI_REALTIME, AiSubtitleProvider.TVGO -> RealtimeWsEngine(
                config = cfg,
                onCueReady = ::enqueueCue,
                onEngineStatus = ::onEngineStatusUpdate,
            )
        }
        engine = newEngine
        newEngine.start()

        // 2. 启动后台音频探路器 (AiAudioScout) 超前拉取音频流
        scout?.start(streamUrl)

        // 3. 启动主画面延迟直播倒计时（绝不强制暂停主画面，避免部分电视硬件/HLS无时移流卡死）
        val leadSecs = cfg.leadSeconds.coerceIn(0, AiSubtitleConfigStore.MAX_LEAD_SECONDS)
        if (leadSecs > 0) {
            prepareJob = scope.launch {
                for (remaining in leadSecs downTo 1) {
                    _state.value = AiSubtitleState.Preparing(
                        secondsLeft = remaining,
                        leadSeconds = leadSecs,
                    )
                    delay(1000L)
                }
                _state.value = AiSubtitleState.Running()
            }
        } else {
            _state.value = AiSubtitleState.Running()
        }

        // 4. 启动字幕时间戳对齐上屏协程
        cueTickerJob = scope.launch {
            while (true) {
                tickCueDisplay()
                delay(100L)
            }
        }
    }

    /**
     * 当用户换台 (Zap) 或流地址变化时调用：若 AI 字幕处于开启状态，自动切换到新频道重新探路与对齐。
     */
    fun onStreamChanged(playbackEngine: PlaybackEngine, newStreamUrl: String?) {
        if (!isEnabled) return
        if (newStreamUrl.isNullOrBlank()) {
            stop()
            return
        }
        if (newStreamUrl != activeStreamUrl) {
            start(playbackEngine, newStreamUrl)
        }
    }

    /** 停止 AI 实时字幕并恢复正常播放状态 */
    fun stop() {
        stopInternal(resumeIfPaused = true)
    }

    private fun stopInternal(resumeIfPaused: Boolean) {
        prepareJob?.cancel()
        prepareJob = null
        cueTickerJob?.cancel()
        cueTickerJob = null

        if (resumeIfPaused && pausedMainPlayerForLead) {
            val eng = boundPlaybackEngine
            if (eng != null && !eng.isPlaying.value) {
                runCatching { eng.togglePlayPause() }
            }
        }
        pausedMainPlayerForLead = false
        boundPlaybackEngine = null
        activeStreamUrl = null

        scout?.stop()
        engine?.stop()
        engine = null

        synchronized(pendingCues) {
            pendingCues.clear()
        }
        _activeCue.value = null
        _state.value = AiSubtitleState.Off
    }

    private var activeCueStartElapsedMs = 0L
    private var lastActivityElapsedMs = 0L

    private fun enqueueCue(cue: AiSubtitleCue) {
        val now = SystemClock.elapsedRealtime()
        val cfg = configStore.config.value
        val leadMs = cfg.leadSeconds * 1000L
        val syncOffsetMs = cfg.syncOffsetMs.toLong()
        val isRunning = _state.value is AiSubtitleState.Running
        lastActivityElapsedMs = now

        val current = _activeCue.value

        // 1. 同一句的流式内容增量追加 (同一 Cue ID)
        if (current != null && current.id == cue.id) {
            _activeCue.value = cue
            if (current.translatedText.isBlank() && cue.translatedText.isNotBlank()) {
                activeCueStartElapsedMs = now // 中文译文刚出现，启动 1.8s 最小驻留保护倒计时
            }
        } else if (isRunning && now >= cue.audioStartElapsedMs + leadMs + syncOffsetMs) {
            // 2. 跨句翻篇 (全新 Cue ID)：判断当前屏幕上的旧句是否需要最小驻留保护
            val currentHasChinese = current != null && current.translatedText.isNotBlank()
            val currentDisplayDuration = now - activeCueStartElapsedMs
            if (currentHasChinese && currentDisplayDuration < MIN_TRANSLATION_DISPLAY_MS) {
                // 当前中文在屏幕展示尚未满 1.8 秒：放入待展示队列缓冲，坚决不暴力强刷，防止中文一闪而过
                // 留待 tickCueDisplay() 在满 1.8s 后平滑接替
            } else {
                // 已满足 1.8 秒保底阅读时间（或旧句无中文），立即平滑接替展示
                _activeCue.value = cue
                activeCueStartElapsedMs = if (cue.translatedText.isNotBlank()) now else 0L
            }
        }

        synchronized(pendingCues) {
            val idx = pendingCues.indexOfFirst { it.id == cue.id }
            if (idx >= 0) {
                val list = pendingCues.toMutableList()
                list[idx] = cue
                pendingCues.clear()
                pendingCues.addAll(list)
            } else {
                while (pendingCues.size >= MAX_PENDING_CUES) {
                    pendingCues.removeFirst()
                }
                pendingCues.addLast(cue)
            }
        }
    }

    private fun tickCueDisplay() {
        val now = SystemClock.elapsedRealtime()
        val cfg = configStore.config.value
        val leadMs = cfg.leadSeconds * 1000L
        val syncOffsetMs = cfg.syncOffsetMs.toLong()
        val current = _activeCue.value

        // 核心保护：若当前屏幕已有中文且展示还未满 1.8 秒，等待满足保底阅读时长后再翻篇
        val currentHasChinese = current != null && current.translatedText.isNotBlank()
        val currentDisplayDuration = now - activeCueStartElapsedMs
        val isLockedByMinDisplay = currentHasChinese && currentDisplayDuration < MIN_TRANSLATION_DISPLAY_MS

        if (!isLockedByMinDisplay) {
            var toShow: AiSubtitleCue? = null
            synchronized(pendingCues) {
                val ready = pendingCues.filter { cue ->
                    now >= cue.audioStartElapsedMs + leadMs + syncOffsetMs
                }
                if (ready.isNotEmpty()) {
                    val latest = ready.last()
                    // 仅当准备展示的 Cue 与当前不同，或者包含更新的文本内容时切换
                    if (current == null || latest.id != current.id || latest.translatedText.length > current.translatedText.length) {
                        toShow = latest
                        while (pendingCues.isNotEmpty() && pendingCues.first().id != latest.id) {
                            pendingCues.removeFirst()
                        }
                        if (!latest.isPartial) {
                            pendingCues.removeFirstOrNull()
                        }
                    }
                }
            }

            if (toShow != null) {
                _activeCue.value = toShow
                if (toShow.translatedText.isNotBlank()) {
                    if (current == null || toShow.id != current.id || activeCueStartElapsedMs == 0L) {
                        activeCueStartElapsedMs = now
                    }
                } else {
                    activeCueStartElapsedMs = 0L
                }
                return
            }
        }

        // 静音与停顿消隐：距最后活动超过 2.8 秒且已满足 1.8 秒最小阅读时长，平滑消隐
        if (current != null) {
            val quietDuration = now - lastActivityElapsedMs
            if (quietDuration > SILENCE_FADE_OUT_MS && !isLockedByMinDisplay) {
                _activeCue.value = null
                activeCueStartElapsedMs = 0L
            }
        }
    }

    private fun onEngineStatusUpdate(status: String) {
        val cur = _state.value
        if (cur is AiSubtitleState.Running) {
            _state.value = AiSubtitleState.Running(status)
        }
    }

    /**
     * 解析当前正在播放的直播流或视频流 URL：
     * 优先使用传入的频道 URL，其次从传入的 PlaybackEngine 获取，最后回退读取 `LivePreviewEngine.currentUrl` 与 `OwnTVPlayer`。
     */
    private fun resolveActiveStreamUrl(fallbackUrl: String?, playbackEngine: PlaybackEngine? = null): String? {
        val engUrl = (playbackEngine as? LivePreviewEngine)?.currentUrl
        Log.i(TAG, "resolveActiveStreamUrl: fallback=$fallbackUrl, engUrl=$engUrl, preview=${livePreviewEngine.currentUrl}, ownTVPlayer=${ownTVPlayer.currentMediaUrl}")
        if (!fallbackUrl.isNullOrBlank()) return fallbackUrl
        engUrl?.takeIf { it.isNotBlank() }?.let { return it }
        livePreviewEngine.currentUrl?.takeIf { it.isNotBlank() }?.let { return it }
        ownTVPlayer.currentMediaUrl?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    companion object {
        private const val TAG = "AiSubtitleController"
        private const val MAX_PENDING_CUES = 20
        /** 中文译文保底安全阅读时长，彻底杜绝快语速下一闪而过 */
        private const val MIN_TRANSLATION_DISPLAY_MS = 1800L
        /** 静音停顿后平滑消隐时间 */
        private const val SILENCE_FADE_OUT_MS = 2800L
    }
}
