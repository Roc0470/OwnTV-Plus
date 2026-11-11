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
 * - [Preparing]：正在超前收听并缓冲翻译（主播放器暂停等待真实音频超前量就绪）
 * - [Running]：实时同传运行中，按音频 PTS 与微调偏移量精准弹出双语/中文翻译字幕
 * - [Error]：配置缺失或连接异常提示
 */
sealed interface AiSubtitleState {
    data object Off : AiSubtitleState
    data class Preparing(val secondsLeft: Int, val leadSeconds: Int) : AiSubtitleState
    data class Running(val statusHint: String = "") : AiSubtitleState
    data class Error(val message: String) : AiSubtitleState
}

/**
 * AI 实时字幕核心调度控制器 ([AiSubtitleController])。
 *
 * 三大同步机制：
 * 1. **真实超前探路 + 单连接自动回退**：
 *    优先启动后台静音 [AiAudioScout] 超前拉取音频轨，待探路器真正产出首帧音频后，按住主画面攒够 `leadSeconds` 的真实音频超前量；
 *    若遇到单连接限制（Max Connections = 1）导致独立探路器连接报错或超时，自动销毁探路器并无缝回退至主播放器 `TapForwardingAudioSink` 旁路直采。
 * 2. **音频 PTS 双时钟锚定 + 卡顿冻结保护**：
 *    彻底废除系统挂钟硬算，采用探路器累计解码音频时钟 (`scout.decodedAudioMs`) 与主画面真实播放时钟 (`mainPlayedPtsMs`) 双时钟对齐；
 *    当主画面发生网络缓冲卡顿 (`buffering == true`) 或暂停时，`mainPlayedPtsMs` 立即冻结，字幕停止抢跑并提示“等待画面缓冲同步中…”，
 *    卡顿超长时自动暂停后台探路器，恢复播放后无缝接续。
 * 3. **60 秒时间轴环形窗口 + 毫秒级双向微调 (`syncOffsetMs`)**：
 *    保留最近 60 秒内所有已播与待播字幕，支持 `-10s ~ +15s` 实时提前/延后微调与一键“立即对齐当前语音”。
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

    /** 60 秒时间轴环形字幕窗口（同时保留已展示与待展示字幕，供提前/延后微调实时回溯与前瞻） */
    private val timelineCues = ArrayDeque<AiSubtitleCue>()

    private var scout: AiAudioScout? = null
    private var engine: AiSubtitleEngine? = null
    private var prepareJob: Job? = null
    private var cueTickerJob: Job? = null
    private var activeStreamUrl: String? = null
    private var pausedMainPlayerForLead = false
    private var boundPlaybackEngine: PlaybackEngine? = null

    /** 主播放器自对齐起点以来真实播放推进的毫秒数（卡顿/暂停期间严格冻结） */
    @Volatile private var mainPlayedPtsMs = 0L
    private var lastTickElapsedMs = 0L
    private var wasMainBuffering = false
    private var bufferingStartElapsedMs = 0L
    private var activeCueStartElapsedMs = 0L
    private var lastCueActivityElapsedMs = 0L

    init {
        scout = AiAudioScout(
            context = context.applicationContext,
            onFramePcm = { bytes, ptsMs -> engine?.onFramePcm(bytes, ptsMs) },
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
     */
    fun toggle(playbackEngine: PlaybackEngine, fallbackStreamUrl: String? = null) {
        if (isEnabled) {
            stop()
        } else {
            start(playbackEngine, fallbackStreamUrl)
        }
    }

    /**
     * 启动 AI 实时字幕与超前对齐流水线。
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
        // 换台或重新开启时自动归零单次会话微调偏移量
        configStore.resetSyncOffset()

        boundPlaybackEngine = playbackEngine
        activeStreamUrl = streamUrl
        mainPlayedPtsMs = 0L
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        wasMainBuffering = false
        bufferingStartElapsedMs = 0L
        activeCueStartElapsedMs = 0L
        lastCueActivityElapsedMs = SystemClock.elapsedRealtime()

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

        // 2. 启动独立超前探路器；若遇单连接限制失败则无缝回退至主播放器旁路直采
        val leadSecs = cfg.leadSeconds.coerceIn(AiSubtitleConfigStore.MIN_LEAD_SECONDS, AiSubtitleConfigStore.MAX_LEAD_SECONDS)
        val targetLeadMs = leadSecs * 1000L

        scout?.startStandalone(
            url = streamUrl,
            onStandaloneFailed = {
                scope.launch {
                    fallbackToDirectTap("直播源限制单连接，已自动切换为主播放器直采模式")
                }
            },
        )

        // 3. 启动主画面延迟直播准备协程（等待探路器产出真实超前音频后再恢复主画面播放）
        prepareJob = scope.launch {
            pausedMainPlayerForLead = false

            val keepPausedJob = launch {
                playbackEngine.isPlaying.collect { playing ->
                    if (playing && scout?.isDirectTapMode != true) {
                        runCatching {
                            playbackEngine.togglePlayPause()
                            pausedMainPlayerForLead = true
                            Log.i(TAG, "Preparing: intercepted playing state, held main player in pause")
                        }
                    }
                }
            }

            if (playbackEngine.isPlaying.value) {
                runCatching {
                    playbackEngine.togglePlayPause()
                    pausedMainPlayerForLead = true
                }
            }

            _state.value = AiSubtitleState.Preparing(secondsLeft = leadSecs, leadSeconds = leadSecs)

            // 阶段 A：等待独立探路器连接成功并产出第一帧音频（最多等待 3.8 秒，超时说明单连接源冲突，立即回退直采）
            var connectWaitMs = 0L
            while (connectWaitMs < 3800L && (scout?.decodedAudioMs ?: 0L) < 100L && scout?.isDirectTapMode != true) {
                delay(100L)
                connectWaitMs += 100L
            }

            if (scout?.isDirectTapMode == true || (scout?.decodedAudioMs ?: 0L) < 100L) {
                keepPausedJob.cancel()
                if (scout?.isDirectTapMode != true) {
                    fallbackToDirectTap("已自动切换为主播放器零冲突直采模式")
                }
                return@launch
            }

            // 阶段 B：探路器已正常解码音频，继续按住主画面直到攒足 targetLeadMs 真实音频超前量
            var holdElapsedMs = 0L
            val maxHoldWallMs = targetLeadMs + 2500L
            while (holdElapsedMs < maxHoldWallMs && scout?.isDirectTapMode != true) {
                val decodedMs = scout?.decodedAudioMs ?: 0L
                if (decodedMs >= targetLeadMs) break
                val remainMs = (targetLeadMs - decodedMs).coerceAtLeast(0L)
                val secLeft = ((remainMs + 999L) / 1000L).toInt().coerceIn(1, leadSecs)
                _state.value = AiSubtitleState.Preparing(secondsLeft = secLeft, leadSeconds = leadSecs)
                delay(100L)
                holdElapsedMs += 100L
            }

            keepPausedJob.cancel()

            if (!playbackEngine.isPlaying.value) {
                runCatching {
                    playbackEngine.togglePlayPause()
                    Log.i(TAG, "Preparing completed: resumed main player (scoutLead=${scout?.decodedAudioMs}ms)")
                }
            }
            pausedMainPlayerForLead = false
            mainPlayedPtsMs = 0L
            lastTickElapsedMs = SystemClock.elapsedRealtime()
            _state.value = AiSubtitleState.Running("AI 实时同传已开启，正在同步收听...")
        }

        // 4. 启动 100ms 精度双时钟推进与字幕时间轴门禁协程
        var tickCount = 0
        cueTickerJob = scope.launch {
            while (true) {
                advancePlayClockAndTick()
                tickCount++
                if (tickCount % 5 == 0 && scout?.isDirectTapMode == true) {
                    val curEngine = boundPlaybackEngine
                    val targetLive = (curEngine as? LivePreviewEngine) ?: livePreviewEngine
                    scout?.attachToLiveEngine(targetLive)
                }
                delay(100L)
            }
        }
    }

    /**
     * 当单连接源无法启动第二个播放器连接时，无缝回退至主播放器 AudioSink 内存旁路直采。
     */
    private fun fallbackToDirectTap(reasonHint: String) {
        prepareJob?.cancel()
        prepareJob = null
        scout?.stopStandaloneOnly()

        val eng = boundPlaybackEngine
        if (pausedMainPlayerForLead && eng != null && !eng.isPlaying.value) {
            runCatching { eng.togglePlayPause() }
        }
        pausedMainPlayerForLead = false

        val targetLive = (eng as? LivePreviewEngine) ?: livePreviewEngine
        val attached = scout?.attachToLiveEngine(targetLive) == true
        Log.i(TAG, "Fallback to Direct Tap mode: attached=$attached, reason=$reasonHint")

        mainPlayedPtsMs = scout?.decodedAudioMs ?: 0L
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        _state.value = AiSubtitleState.Running(reasonHint)
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
        if (newStreamUrl != activeStreamUrl || playbackEngine !== boundPlaybackEngine) {
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
        mainPlayedPtsMs = 0L
        wasMainBuffering = false

        scout?.stop()
        engine?.stop()
        engine = null

        synchronized(timelineCues) {
            timelineCues.clear()
        }
        _activeCue.value = null
        _state.value = AiSubtitleState.Off
    }

    /**
     * 调整字幕同步偏移量（毫秒，正数延后，负数提前），并立即在 60 秒时间轴窗口上重算对齐上屏。
     */
    fun adjustSyncOffset(deltaMs: Int): Int {
        val newOffset = configStore.adjustSyncOffset(deltaMs)
        Log.i(TAG, "adjustSyncOffset: delta=${deltaMs}ms, newOffset=${newOffset}ms, mainPlayedPts=${mainPlayedPtsMs}ms")
        tickCueDisplay(forceImmediate = true)
        return newOffset
    }

    /**
     * 重置字幕同步偏移量至 0 毫秒，并立即重算对齐上屏。
     */
    fun resetSyncOffset(): Int {
        val newOffset = configStore.resetSyncOffset()
        Log.i(TAG, "resetSyncOffset: mainPlayedPts=${mainPlayedPtsMs}ms")
        tickCueDisplay(forceImmediate = true)
        return newOffset
    }

    /**
     * 一键“立即对齐当前语音”：消除历史累计卡顿或跳帧引起的偏差，将主播放游标直接对齐至最新译出字幕。
     */
    fun syncToLiveNow() {
        configStore.resetSyncOffset()
        val scoutPts = scout?.decodedAudioMs ?: 0L
        val latestCuePts = synchronized(timelineCues) { timelineCues.lastOrNull()?.audioPtsMs }
        mainPlayedPtsMs = when {
            latestCuePts != null -> latestCuePts
            scoutPts > 1500L -> scoutPts - 1500L
            else -> scoutPts
        }
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        scout?.setStandalonePaused(false)
        Log.i(TAG, "syncToLiveNow: snapped mainPlayedPtsMs to ${mainPlayedPtsMs}ms (scoutPts=${scoutPts}ms)")
        tickCueDisplay(forceImmediate = true)
    }

    /**
     * 推进主画面有效播放时钟（处理缓冲卡顿冻结、超长卡顿暂停探路器、恢复后跳帧重同步）并刷新字幕显示。
     */
    private fun advancePlayClockAndTick() {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTickElapsedMs).coerceIn(0L, 500L)
        lastTickElapsedMs = now

        if (_state.value !is AiSubtitleState.Running) return

        val eng = boundPlaybackEngine
        val isBuffering = eng?.buffering?.value == true
        val isPlaying = eng?.isPlaying?.value == true
        val scoutPts = scout?.decodedAudioMs ?: 0L
        val cfg = configStore.config.value
        val targetLeadMs = cfg.leadSeconds * 1000L

        if (scout?.isDirectTapMode == true) {
            // 直采模式下，探路器解码进度即主播放器真实播放进度
            mainPlayedPtsMs = scoutPts
            if (isBuffering) {
                wasMainBuffering = true
                _state.value = AiSubtitleState.Running("等待画面缓冲同步中…")
                return
            } else if (wasMainBuffering) {
                wasMainBuffering = false
                _state.value = AiSubtitleState.Running("")
            }
        } else {
            // 独立超前探路模式：仅当主播放器真正播放且未卡顿时推进 mainPlayedPtsMs
            if (!pausedMainPlayerForLead && isPlaying && !isBuffering) {
                if (wasMainBuffering) {
                    val stallMs = now - bufferingStartElapsedMs
                    wasMainBuffering = false
                    scout?.setStandalonePaused(false)
                    // 若卡顿后探路器与主播放时钟差距过大（例如直播源断流跳帧重连到了最新切片），自动收缩对齐到最新语音窗口
                    val currentGap = scoutPts - mainPlayedPtsMs
                    if (stallMs > 2500L && currentGap > targetLeadMs + 4500L) {
                        val snapped = (scoutPts - targetLeadMs).coerceAtLeast(mainPlayedPtsMs)
                        Log.i(TAG, "Recovered from ${stallMs}ms stall (gap=${currentGap}ms): auto-resynced mainPlayedPtsMs $mainPlayedPtsMs -> $snapped")
                        mainPlayedPtsMs = snapped
                    }
                    _state.value = AiSubtitleState.Running("")
                }
                if (scoutPts > 0L) {
                    mainPlayedPtsMs = (mainPlayedPtsMs + dt).coerceAtMost(scoutPts)
                }
                // 若探路器曾因主画面卡顿暂停，在主画面追近后恢复探路器
                if (scout?.isStandalonePausedForMainStall == true && (scoutPts - mainPlayedPtsMs) <= targetLeadMs + 3000L) {
                    scout?.setStandalonePaused(false)
                }
            } else if (!pausedMainPlayerForLead) {
                // 主画面正在卡顿缓冲或暂停：严格冻结 mainPlayedPtsMs！
                if (isBuffering) {
                    if (!wasMainBuffering) {
                        wasMainBuffering = true
                        bufferingStartElapsedMs = now
                        Log.i(TAG, "Main player buffering detected at mainPlayedPtsMs=${mainPlayedPtsMs}ms (scoutPts=${scoutPts}ms): freezing subtitle clock")
                    }
                    _state.value = AiSubtitleState.Running("等待画面缓冲同步中…")
                }
                // 若主画面卡顿导致探路器超前超过 targetLeadMs + 6s，暂停探路器拉流等待主画面
                if ((scoutPts - mainPlayedPtsMs) > targetLeadMs + 6000L) {
                    scout?.setStandalonePaused(true)
                }
                // 卡顿期间冻结字幕更新，保留当前屏幕字幕不消失、不抢跑
                return
            }
        }

        tickCueDisplay(forceImmediate = false)
    }

    private fun enqueueCue(cue: AiSubtitleCue) {
        val now = SystemClock.elapsedRealtime()
        lastCueActivityElapsedMs = now

        synchronized(timelineCues) {
            val idx = timelineCues.indexOfFirst { it.id == cue.id }
            if (idx >= 0) {
                val list = timelineCues.toMutableList()
                // 保持该短句首次锁定的起始 audioPtsMs 不变，防止增量更新把起点往后推
                val preservedPts = list[idx].audioPtsMs
                list[idx] = cue.copy(audioPtsMs = preservedPts)
                timelineCues.clear()
                timelineCues.addAll(list)
            } else {
                while (timelineCues.size >= MAX_TIMELINE_CUES) {
                    timelineCues.removeFirst()
                }
                timelineCues.addLast(cue)
            }
        }

        if (_state.value is AiSubtitleState.Running && !wasMainBuffering) {
            tickCueDisplay(forceImmediate = false)
        }
    }

    /**
     * 根据当前有效播放时钟 `effectivePlayPtsMs = mainPlayedPtsMs - syncOffsetMs` 在 60 秒时间轴窗口中匹配应显示的字幕。
     * @param forceImmediate 当用户手动按遥控器微调偏移量或点击“立即对齐”时设为 true，跳过 1.8 秒最小驻留锁立即切换屏幕字幕
     */
    private fun tickCueDisplay(forceImmediate: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val cfg = configStore.config.value
        val syncOffsetMs = cfg.syncOffsetMs.toLong()
        val effectivePlayPtsMs = mainPlayedPtsMs - syncOffsetMs
        val current = _activeCue.value

        // 1. 从 60 秒时间轴环形窗口中筛选出所有已到达显示时刻（audioPtsMs <= effectivePlayPtsMs）且未过期的字幕
        val targetCue: AiSubtitleCue? = synchronized(timelineCues) {
            // 清理超过 60 秒的陈旧历史字幕
            while (timelineCues.size > 1 && timelineCues.first().audioPtsMs < mainPlayedPtsMs - HISTORY_WINDOW_MS) {
                timelineCues.removeFirst()
            }
            val eligible = timelineCues.filter { cue ->
                cue.audioPtsMs <= effectivePlayPtsMs &&
                    (effectivePlayPtsMs - cue.audioPtsMs) <= MAX_CUE_LIFETIME_MS
            }
            eligible.lastOrNull()
        }

        // 2. 如果用户手动调节了“延后”导致当前屏幕字幕变为未来未到时刻的字幕，立即切换回前一句或撤下屏幕
        if (forceImmediate) {
            _activeCue.value = targetCue
            activeCueStartElapsedMs = if (targetCue != null && targetCue.translatedText.isNotBlank()) now else 0L
            return
        }

        if (current != null && current.audioPtsMs > effectivePlayPtsMs) {
            _activeCue.value = targetCue
            activeCueStartElapsedMs = if (targetCue != null && targetCue.translatedText.isNotBlank()) now else 0L
            return
        }

        // 3. 同一句字幕的流式增量更新：只要该句已满足时间门禁（targetCue?.id == current.id 或在时间窗内），实时刷新文字
        if (current != null && targetCue != null && current.id == targetCue.id) {
            if (targetCue.translatedText != current.translatedText || targetCue.originalText != current.originalText || targetCue.isPartial != current.isPartial) {
                val hadChinese = current.translatedText.isNotBlank()
                _activeCue.value = targetCue
                if (!hadChinese && targetCue.translatedText.isNotBlank()) {
                    activeCueStartElapsedMs = now
                }
            }
            return
        }

        // 4. 跨句翻篇：检查当前屏幕上的中文是否已满足 1.8 秒保底阅读驻留时长
        val currentHasChinese = current != null && current.translatedText.isNotBlank()
        val currentDisplayDuration = now - activeCueStartElapsedMs
        val isLockedByMinDisplay = currentHasChinese && currentDisplayDuration < MIN_TRANSLATION_DISPLAY_MS

        if (!isLockedByMinDisplay && targetCue != null) {
            if (current == null || targetCue.id != current.id) {
                _activeCue.value = targetCue
                activeCueStartElapsedMs = if (targetCue.translatedText.isNotBlank()) now else 0L
                return
            }
        }

        // 5. 静音与停顿消隐：当没有新字幕命中且展示已超过消隐时长时平滑隐藏
        if (current != null && !isLockedByMinDisplay) {
            val sinceCuePts = effectivePlayPtsMs - current.audioPtsMs
            val quietWallMs = now - lastCueActivityElapsedMs
            if (sinceCuePts > MAX_CUE_LIFETIME_MS || quietWallMs > SILENCE_FADE_OUT_MS) {
                _activeCue.value = null
                activeCueStartElapsedMs = 0L
            }
        }
    }

    private fun onEngineStatusUpdate(status: String) {
        val cur = _state.value
        if (cur is AiSubtitleState.Running && !wasMainBuffering) {
            _state.value = AiSubtitleState.Running(status)
        }
    }

    /**
     * 解析当前正在播放的直播流或视频流 URL。
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
        /** 时间轴环形缓冲区最大保留条数（约覆盖 60 秒历史与待播字幕） */
        private const val MAX_TIMELINE_CUES = 60
        /** 历史字幕回溯保留时长（60 秒） */
        private const val HISTORY_WINDOW_MS = 60_000L
        /** 单条字幕在时间轴上的最大有效驻留跨度（10 秒） */
        private const val MAX_CUE_LIFETIME_MS = 10_000L
        /** 中文译文保底安全阅读时长，彻底杜绝快语速下一闪而过 */
        private const val MIN_TRANSLATION_DISPLAY_MS = 1800L
        /** 静音停顿后平滑消隐时间（8.5 秒，覆盖句间正常同传推理间隔） */
        private const val SILENCE_FADE_OUT_MS = 8500L
    }
}
