// DO NOT EDIT — generated
package tv.own.owntv.features.aisubtitle

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 单条 AI 识别与翻译字幕项。
 *
 * @property id 唯一递增或服务端事件标识
 * @property originalText 源语言识别文本（若为直接出译文的模型则可能为空）
 * @property translatedText 目标语言翻译文本
 * @property audioStartElapsedMs 对应音频块在探路器采集时的系统时钟 (SystemClock.elapsedRealtime())
 * @property audioPtsMs 对应音频块在探路器采集时的累计有效音频时钟（毫秒），用于与主播放器实际播放进度精准对齐
 * @property audioDurationMs 音频持续时长（毫秒）
 * @property isPartial 是否为流式增量未定稿字幕
 */
data class AiSubtitleCue(
    val id: String,
    val originalText: String,
    val translatedText: String,
    val audioStartElapsedMs: Long,
    val audioPtsMs: Long = 0L,
    val audioDurationMs: Long = 3000L,
    val isPartial: Boolean = false,
)

/**
 * AI 实时字幕引擎统一接口。
 */
interface AiSubtitleEngine {
    /** 启动引擎会话 */
    fun start()

    /** 接收 100ms 实时 16kHz Mono 16-bit PCM 音频帧及累计音频 PTS（供 WebSocket 流式引擎使用） */
    fun onFramePcm(pcm16k: ByteArray, audioPtsMs: Long = 0L) {}

    /** 接收 2.0~3.2s 完整音频切片（供 OpenAI 兼容分段引擎使用） */
    fun onSegmentPcm(chunk: Pcm16kChunk) {}

    /** 停止并释放引擎资源 */
    fun stop()
}

/**
 * 引擎一：OpenAI 兼容多供应商引擎 ([OpenAiCompatEngine])。
 */
class OpenAiCompatEngine(
    private val config: AiSubtitleConfig,
    private val onCueReady: (AiSubtitleCue) -> Unit,
    private val onEngineStatus: (String) -> Unit,
) : AiSubtitleEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val seqCounter = AtomicLong(1L)

    // 维护最近 2 句上下文以提升连续对话的翻译准确度与人名连贯性
    private val recentContext = ArrayDeque<String>(3)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override fun start() {
        running.set(true)
        onEngineStatus("OpenAI 兼容引擎已就绪 (${config.modelName})")
    }

    override fun onSegmentPcm(chunk: Pcm16kChunk) {
        if (!running.get()) return
        // VAD 静音过滤：跳过无语音活力的纯静音或底噪片段，节约 Token 并杜绝 Whisper 幻觉
        if (chunk.rmsEnergy < MIN_SPEECH_RMS) return

        scope.launch {
            runCatching {
                if (config.asrModelName.isNotBlank()) {
                    processTwoStageAsrAndChat(chunk)
                } else {
                    processMultimodalAudioChat(chunk)
                }
            }.onFailure { e ->
                Log.w(TAG, "OpenAiCompatEngine segment failed: ${e.message}")
            }
        }
    }

    private fun normalizedBaseUrl(): String {
        val raw = config.apiBaseUrl.trim().trimEnd('/')
        return when {
            raw.endsWith("/chat/completions") -> raw.removeSuffix("/chat/completions")
            raw.endsWith("/audio/transcriptions") -> raw.removeSuffix("/audio/transcriptions")
            else -> raw
        }
    }

    /**
     * 模式 A：两阶段 `/audio/transcriptions` + `/chat/completions`
     */
    private fun processTwoStageAsrAndChat(chunk: Pcm16kChunk) {
        val baseUrl = normalizedBaseUrl()
        val wavBytes = chunk.toWavBytes()

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "audio.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", config.asrModelName.trim())

        if (config.sourceLang.isNotBlank() && config.sourceLang != "auto") {
            multipartBuilder.addFormDataPart("language", config.sourceLang.trim())
        }

        val asrReq = Request.Builder()
            .url("$baseUrl/audio/transcriptions")
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .post(multipartBuilder.build())
            .build()

        val transcript = httpClient.newCall(asrReq).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "ASR HTTP ${resp.code}: $bodyStr, falling back to multimodal chat")
                processMultimodalAudioChat(chunk)
                return
            }
            parseAsrText(bodyStr)
        }.trim()

        if (transcript.isBlank() || isHallucination(transcript)) return

        val cueId = "oa-${seqCounter.getAndIncrement()}"
        if (config.showOriginal) {
            onCueReady(
                AiSubtitleCue(
                    id = cueId,
                    originalText = transcript,
                    translatedText = "",
                    audioStartElapsedMs = chunk.startElapsedMs,
                    audioPtsMs = chunk.audioPtsMs,
                    audioDurationMs = chunk.durationMs,
                    isPartial = true,
                ),
            )
        }

        val translated = translateTextWithLlm(baseUrl, transcript).trim()
        if (translated.isBlank()) return

        synchronized(recentContext) {
            if (recentContext.size >= 2) recentContext.removeFirst()
            recentContext.addLast("$transcript -> $translated")
        }

        onCueReady(
            AiSubtitleCue(
                id = cueId,
                originalText = transcript,
                translatedText = translated,
                audioStartElapsedMs = chunk.startElapsedMs,
                audioPtsMs = chunk.audioPtsMs,
                audioDurationMs = chunk.durationMs,
                isPartial = false,
            ),
        )
    }

    /**
     * 模式 B：多模态 `/chat/completions` (`input_audio` Base64 WAV 直传)
     */
    private fun processMultimodalAudioChat(chunk: Pcm16kChunk) {
        val baseUrl = normalizedBaseUrl()
        val wavBase64 = Base64.encodeToString(chunk.toWavBytes(), Base64.NO_WRAP)
        val targetLanguage = config.targetLangLabel()

        val systemPrompt = buildString {
            append("你是一个专业的电视直播同声传译字幕员。请听这段音频，将其中的语音翻译为自然、简练的")
            append(targetLanguage)
            append("电视字幕。")
            append("如果音频中完全没有人声（仅有音乐、噪音或静音），请只回复空字符串。")
            if (config.showOriginal) {
                append("请严格使用两行输出：第一行输出识别到的源语言原文，第二行输出")
                append(targetLanguage)
                append("译文。不要输出任何解释或多余符号。")
            } else {
                append("请只输出最终的")
                append(targetLanguage)
                append("译文，不要输出原文或任何解释。")
            }
        }

        val contentArray = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", systemPrompt)
                },
            )
            put(
                JSONObject().apply {
                    put("type", "input_audio")
                    put(
                        "input_audio",
                        JSONObject().apply {
                            put("data", wavBase64)
                            put("format", "wav")
                        },
                    )
                },
            )
        }

        val messages = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", contentArray)
                },
            )
        }

        val payload = JSONObject().apply {
            put("model", config.modelName.trim())
            put("messages", messages)
            put("temperature", 0.2)
            put("max_tokens", 180)
        }

        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val reply = httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "Multimodal Chat HTTP ${resp.code}: $bodyStr")
                return
            }
            extractChatContent(bodyStr)
        }.trim()

        if (reply.isBlank() || isHallucination(reply)) return

        val lines = reply.lines().map { it.trim() }.filter { it.isNotBlank() }
        val (orig, trans) = if (config.showOriginal && lines.size >= 2) {
            lines.first() to lines.drop(1).joinToString(" ")
        } else {
            "" to lines.joinToString(" ")
        }

        if (trans.isBlank()) return

        onCueReady(
            AiSubtitleCue(
                id = "mm-${seqCounter.getAndIncrement()}",
                originalText = orig,
                translatedText = trans,
                audioStartElapsedMs = chunk.startElapsedMs,
                audioPtsMs = chunk.audioPtsMs,
                audioDurationMs = chunk.durationMs,
                isPartial = false,
            ),
        )
    }

    private fun translateTextWithLlm(baseUrl: String, sourceText: String): String {
        val targetLanguage = config.targetLangLabel()
        val ctxLines = synchronized(recentContext) { recentContext.toList() }
        val ctxHint = if (ctxLines.isNotEmpty()) {
            "前文参考：\n" + ctxLines.joinToString("\n") + "\n\n"
        } else {
            ""
        }

        val messages = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "你是专业的电视直播实时字幕翻译引擎。请将用户提供的语音识别文本直接翻译为地道、口语化、简练的$targetLanguage。" +
                            "只输出翻译后的文本本身，绝对不要附带引号、解释或前缀。",
                    )
                },
            )
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", "${ctxHint}请翻译以下直播台词：\n$sourceText")
                },
            )
        }

        val payload = JSONObject().apply {
            put("model", config.modelName.trim())
            put("messages", messages)
            put("temperature", 0.2)
            put("max_tokens", 160)
        }

        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "Translate LLM HTTP ${resp.code}: $bodyStr")
                return@use ""
            }
            extractChatContent(bodyStr)
        }
    }

    private fun parseAsrText(jsonStr: String): String = runCatching {
        val obj = JSONObject(jsonStr)
        obj.optString("text", "").ifBlank {
            obj.optJSONObject("data")?.optString("text", "").orEmpty()
        }
    }.getOrDefault("")

    private fun extractChatContent(jsonStr: String): String = runCatching {
        val obj = JSONObject(jsonStr)
        val choices = obj.optJSONArray("choices") ?: return@runCatching ""
        if (choices.length() == 0) return@runCatching ""
        val first = choices.getJSONObject(0)
        val msg = first.optJSONObject("message") ?: first.optJSONObject("delta")
        msg?.optString("content", "").orEmpty()
    }.getOrDefault("")

    /** 过滤常见的 Whisper/ASR 静音幻觉词 */
    private fun isHallucination(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.length <= 1) return true
        return HALLUCINATION_PHRASES.any { normalized == it || normalized.contains(it) }
    }

    override fun stop() {
        running.set(false)
        scope.cancel()
    }

    companion object {
        private const val TAG = "OpenAiCompatEngine"
        private const val MIN_SPEECH_RMS = 0.008f
        private val HALLUCINATION_PHRASES = listOf(
            "thank you for watching",
            "thanks for watching",
            "subtitles by",
            "amara.org",
            "字幕提供",
            "优优独播剧场",
            "请不吝点赞",
        )
    }
}

/**
 * 引擎二：OpenAI Realtime / 阿里百炼 (`qwen3.8-livetranslate-flash-realtime`) / TVGO WebSocket 引擎 ([RealtimeWsEngine])。
 *
 * 采用精确的音频 PTS 时钟锚定机制 ([latestFedAudioPtsMs] & [currentSpeechAudioPtsMs])：
 * - 每帧 100ms PCM 携带探路器的累计有效音频时长 `audioPtsMs`；
 * - 无论是服务端 `speech_started` 还是译文标点流式切句，每句字幕均严格绑定其对应语音被采集时的 `audioPtsMs`，
 *   彻底修复以往 `finalizeCurrentSentence()` 用系统当前时间覆盖语音起点时间戳的缺陷。
 */
class RealtimeWsEngine(
    private val config: AiSubtitleConfig,
    private val onCueReady: (AiSubtitleCue) -> Unit,
    private val onEngineStatus: (String) -> Unit,
) : AiSubtitleEngine {

    private val running = AtomicBoolean(false)
    private val sessionConfigured = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private var sessionStartElapsedMs = 0L
    private var currentSpeechStartElapsedMs = 0L
    private var lastActivityOrCommitMs = 0L
    private val framesSentCounter = AtomicLong(0L)

    /** 最新送入 WebSocket 的累计音频 PTS（毫秒） */
    @Volatile private var latestFedAudioPtsMs = 0L
    /** 当前 WebSocket 会话建立时的基准音频 PTS（毫秒） */
    @Volatile private var sessionBaseAudioPtsMs = 0L
    /** 当前正在累积的短句对应的起始音频 PTS（毫秒） */
    @Volatile private var currentSpeechAudioPtsMs = 0L
    /** 当前短句是否已锁定语音起点 PTS */
    @Volatile private var hasSpeechAnchor = false

    private val partialOriginal = StringBuilder()
    private val partialTranslated = StringBuilder()
    private val cueCounter = AtomicLong(1L)
    private var activeCueId = "rt-1"

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    override fun start() {
        running.set(true)
        sessionConfigured.set(false)
        reconnecting.set(false)
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        currentSpeechStartElapsedMs = sessionStartElapsedMs
        lastActivityOrCommitMs = sessionStartElapsedMs
        latestFedAudioPtsMs = 0L
        sessionBaseAudioPtsMs = 0L
        currentSpeechAudioPtsMs = 0L
        hasSpeechAnchor = false
        framesSentCounter.set(0L)
        connectWebSocket()
    }

    private fun scheduleReconnect(delayMs: Long = 800L) {
        if (!running.get()) return
        if (!reconnecting.compareAndSet(false, true)) return
        sessionConfigured.set(false)
        mainHandler.postDelayed({
            reconnecting.set(false)
            if (running.get()) {
                Log.i(TAG, "Auto-reconnecting Realtime WebSocket...")
                webSocket?.runCatching { cancel() }
                webSocket = null
                connectWebSocket()
            }
        }, delayMs)
    }

    private fun buildWsUrl(): String {
        if (config.provider == AiSubtitleProvider.TVGO) {
            val base = config.apiBaseUrl.trim().trimEnd('/')
            val sep = if (base.contains("?")) "&" else "?"
            return "${base}${sep}device=${config.deviceId}"
        }
        var base = config.apiBaseUrl.trim()
        if (base.startsWith("https://")) base = "wss://" + base.removePrefix("https://")
        if (base.startsWith("http://")) base = "ws://" + base.removePrefix("http://")

        return if (base.contains("model=")) {
            base
        } else {
            val sep = if (base.contains("?")) "&" else "?"
            "${base}${sep}model=${config.modelName.trim()}"
        }
    }

    private fun connectWebSocket() {
        val url = buildWsUrl()
        Log.i(TAG, "Connecting Realtime WebSocket: $url")
        val reqBuilder = Request.Builder().url(url)
        if (config.apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
        }
        val isKeyWithWs = config.apiKey.trim().startsWith("sk-ws-")
        if (config.bailianWorkspace.isNotBlank() && !isKeyWithWs) {
            reqBuilder.header("X-DashScope-WorkSpace", config.bailianWorkspace.trim())
        }
        if (config.provider == AiSubtitleProvider.OPENAI_REALTIME) {
            reqBuilder.header("OpenAI-Beta", "realtime=v1")
        }

        webSocket = wsClient.newWebSocket(
            reqBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sessionStartElapsedMs = SystemClock.elapsedRealtime()
                    lastActivityOrCommitMs = sessionStartElapsedMs
                    sessionBaseAudioPtsMs = latestFedAudioPtsMs
                    Log.i(TAG, "Realtime WebSocket connected (basePts=${sessionBaseAudioPtsMs}ms). Awaiting session.created...")
                    onEngineStatus("Realtime 连接成功，正在初始化会话...")
                    if (config.provider == AiSubtitleProvider.TVGO) {
                        sessionConfigured.set(true)
                        onEngineStatus("TVGO 实时字幕流已就绪")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerEvent(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Realtime WebSocket failure: ${t.message}", t)
                    sessionConfigured.set(false)
                    if (running.get()) {
                        onEngineStatus("正在自动重连同传服务...")
                        scheduleReconnect(1000L)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Realtime WebSocket closed: code=$code, reason=$reason")
                    sessionConfigured.set(false)
                    if (running.get()) {
                        scheduleReconnect(800L)
                    }
                }
            },
        )
    }

    private fun sendSessionUpdate(ws: WebSocket) {
        val sessionObj = JSONObject().apply {
            put("output_modalities", JSONArray().put("text"))
            put("voice", "Tina")
            put("enable_voice_clone", false)
            put(
                "audio",
                JSONObject().apply {
                    put(
                        "input",
                        JSONObject().apply {
                            put(
                                "format",
                                JSONObject().apply {
                                    put("type", "pcm")
                                    put("sample_rate", 16000)
                                },
                            )
                        },
                    )
                },
            )
            put(
                "turn_detection",
                JSONObject().apply {
                    put("type", "speaker_detection")
                    put("threshold", 0.5)
                },
            )
            put(
                "translation",
                JSONObject().apply {
                    put("language", config.targetLang.ifBlank { "zh" })
                },
            )
            if (config.sourceLang.isNotBlank() && config.sourceLang != "auto") {
                put(
                    "input_audio_transcription",
                    JSONObject().apply {
                        put("language", config.sourceLang)
                    },
                )
            }
        }
        val root = JSONObject().apply {
            put("type", "session.update")
            put("session", sessionObj)
        }
        Log.i(TAG, "Sending session.update: $root")
        ws.send(root.toString())
    }

    override fun onFramePcm(pcm16k: ByteArray, audioPtsMs: Long) {
        latestFedAudioPtsMs = audioPtsMs
        if (!running.get() || !sessionConfigured.get()) return
        val ws = webSocket ?: return
        val b64 = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", b64)
        }
        ws.send(msg.toString())
        val count = framesSentCounter.incrementAndGet()
        if (count == 1L || count % 30L == 0L) {
            Log.i(TAG, "RealtimeWsEngine sent $count audio frames (${count / 10.0}s, pts=${audioPtsMs}ms)")
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastActivityOrCommitMs > 45_000L) {
            lastActivityOrCommitMs = now
            Log.i(TAG, "Proactive session refresh at 45s to avoid Bailian 60s response stream timeout")
            if (partialTranslated.isNotEmpty() || partialOriginal.isNotEmpty()) {
                finalizeCurrentSentence()
            }
            scheduleReconnect(100L)
        }
    }

    /** 确保当前正在生成的短句已锁定准确的起始音频 PTS */
    private fun ensureSpeechAnchor(serverAudioStartMs: Long = -1L) {
        if (hasSpeechAnchor) return
        hasSpeechAnchor = true
        currentSpeechStartElapsedMs = SystemClock.elapsedRealtime()
        val rawPts = if (serverAudioStartMs >= 0L) {
            (sessionBaseAudioPtsMs + serverAudioStartMs).coerceAtMost(latestFedAudioPtsMs)
        } else {
            // 识别/翻译首包到达时，对应语音大约在 900ms 前送入
            (latestFedAudioPtsMs - 900L).coerceAtLeast(0L)
        }
        currentSpeechAudioPtsMs = rawPts.coerceAtLeast(currentSpeechAudioPtsMs)
    }

    private fun handleServerEvent(rawJson: String) {
        runCatching {
            val evt = JSONObject(rawJson)
            val type = evt.optString("type", "")
            when (type) {
                "session.created" -> {
                    Log.i(TAG, "Received session.created. Sending session.update...")
                    webSocket?.let { sendSessionUpdate(it) }
                }
                "session.updated" -> {
                    Log.i(TAG, "Received session.updated. Session configured successfully!")
                    sessionConfigured.set(true)
                    sessionBaseAudioPtsMs = latestFedAudioPtsMs
                    lastActivityOrCommitMs = SystemClock.elapsedRealtime()
                    onEngineStatus("AI 实时同传已就绪 (${config.modelName})")
                }
                "input_audio_buffer.speech_started" -> {
                    val audioStartMs = evt.optLong("audio_start_ms", -1L)
                    if (partialTranslated.isEmpty() && partialOriginal.isEmpty()) {
                        hasSpeechAnchor = false
                        ensureSpeechAnchor(audioStartMs)
                    }
                    Log.i(TAG, "Server event: speech_started (audio_start_ms=$audioStartMs, anchorPts=$currentSpeechAudioPtsMs)")
                }
                "input_audio_buffer.speech_stopped", "conversation.item.created" -> {
                    Log.i(TAG, "Server event: $type")
                }
                "response.created" -> {
                    lastActivityOrCommitMs = SystemClock.elapsedRealtime()
                    if (partialTranslated.isNotEmpty()) {
                        finalizeCurrentSentence()
                    }
                    Log.i(TAG, "Server event: response.created")
                }
                "conversation.item.input_audio_transcription.delta" -> {
                    val delta = evt.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        ensureSpeechAnchor()
                        partialOriginal.append(delta)
                        emitCurrentCue(isPartial = true)
                    }
                }
                "conversation.item.input_audio_transcription.text" -> {
                    val full = (evt.optString("text", "") + evt.optString("stash", "")).trim()
                    if (full.isNotEmpty()) {
                        ensureSpeechAnchor()
                        partialOriginal.setLength(0)
                        partialOriginal.append(full)
                        emitCurrentCue(isPartial = true)
                    }
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = evt.optString("transcript", "").ifBlank { evt.optString("text", "") }
                    Log.i(TAG, "ASR completed [$activeCueId]: ${transcript.trim()}")
                    if (transcript.isNotBlank() && partialTranslated.isNotEmpty()) {
                        partialOriginal.setLength(0)
                        partialOriginal.append(transcript.trim())
                        emitCurrentCue(isPartial = true)
                    }
                }
                "response.text.delta", "response.audio_transcript.delta", "response.output_text.delta" -> {
                    val delta = evt.optString("delta", "").ifEmpty { evt.optString("text", "") }
                    if (delta.isNotEmpty()) {
                        if (shouldSplitByTranslation(partialTranslated.toString())) {
                            finalizeCurrentSentence()
                        }
                        ensureSpeechAnchor()
                        partialTranslated.append(delta)
                        Log.i(TAG, "Translation delta [$activeCueId, pts=${currentSpeechAudioPtsMs}ms]: '${partialTranslated}'")
                        emitCurrentCue(isPartial = true)
                    }
                }
                "response.text.text", "response.audio_transcript.text" -> {
                    val full = (evt.optString("text", "") + evt.optString("stash", "")).trim()
                    if (full.isNotEmpty()) {
                        ensureSpeechAnchor()
                        partialTranslated.setLength(0)
                        partialTranslated.append(full)
                        Log.i(TAG, "Translation text [$activeCueId, pts=${currentSpeechAudioPtsMs}ms]: '${partialTranslated}'")
                        emitCurrentCue(isPartial = true)
                    }
                }
                "response.inference_unit_finished" -> {
                    lastActivityOrCommitMs = SystemClock.elapsedRealtime()
                    Log.i(TAG, "Inference unit finished [$activeCueId]: '${partialTranslated}'")
                    if (partialTranslated.isNotEmpty()) {
                        finalizeCurrentSentence()
                    }
                }
                "response.output_item.done", "response.content_part.done",
                "response.text.done", "response.audio_transcript.done", "response.output_text.done",
                "response.done" -> {
                    lastActivityOrCommitMs = SystemClock.elapsedRealtime()
                    Log.i(TAG, "Response turn done [$type, $activeCueId]")
                    if (partialTranslated.isNotEmpty()) {
                        finalizeCurrentSentence()
                    } else {
                        partialOriginal.setLength(0)
                        hasSpeechAnchor = false
                    }
                }
                "error" -> {
                    val errMsg = evt.optJSONObject("error")?.optString("message") ?: evt.optString("message", "未知错误")
                    Log.w(TAG, "Realtime server error: $errMsg (raw: $rawJson)")
                    if (running.get()) {
                        scheduleReconnect(500L)
                    }
                }
                else -> {
                    Log.i(TAG, "Server event [$type]: ${rawJson.take(200)}")
                }
            }
        }.onFailure { err ->
            Log.e(TAG, "Failed to parse server event: $rawJson", err)
        }
    }

    private fun shouldSplitByTranslation(transText: String): Boolean {
        val trans = transText.trimEnd()
        if (trans.length >= 4) {
            val lastChar = trans.last()
            if (lastChar == '。' || lastChar == '！' || lastChar == '？' || lastChar == '；') {
                return true
            }
        }
        if (trans.length >= 24) {
            val lastChar = trans.last()
            if (lastChar == '，' || lastChar == ',' || lastChar == ' ') {
                return true
            }
        }
        return false
    }

    /**
     * 将当前累积的短句定稿发送至上屏队列，并安全推进下一短句的音频 PTS 锚点。
     */
    private fun finalizeCurrentSentence() {
        val orig = if (config.showOriginal) partialOriginal.toString().trim() else ""
        val trans = partialTranslated.toString().trim()
        if (orig.isNotEmpty() || trans.isNotEmpty()) {
            ensureSpeechAnchor()
            onCueReady(
                AiSubtitleCue(
                    id = activeCueId,
                    originalText = orig,
                    translatedText = trans,
                    audioStartElapsedMs = currentSpeechStartElapsedMs,
                    audioPtsMs = currentSpeechAudioPtsMs,
                    audioDurationMs = 3500L,
                    isPartial = false,
                ),
            )
        }
        partialOriginal.setLength(0)
        partialTranslated.setLength(0)
        activeCueId = "rt-${cueCounter.getAndIncrement()}"
        hasSpeechAnchor = false
        // 为连续长语音的下一句预置合理的最低起始 PTS（不倒退）
        currentSpeechAudioPtsMs = (latestFedAudioPtsMs - 800L).coerceAtLeast(currentSpeechAudioPtsMs + 300L)
        currentSpeechStartElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun emitCurrentCue(isPartial: Boolean) {
        val orig = if (config.showOriginal) partialOriginal.toString().trim() else ""
        val trans = partialTranslated.toString().trim()
        if (orig.isEmpty() && trans.isEmpty()) return

        ensureSpeechAnchor()
        onCueReady(
            AiSubtitleCue(
                id = activeCueId,
                originalText = orig,
                translatedText = trans,
                audioStartElapsedMs = currentSpeechStartElapsedMs,
                audioPtsMs = currentSpeechAudioPtsMs,
                audioDurationMs = 3500L,
                isPartial = isPartial,
            ),
        )
    }

    override fun stop() {
        running.set(false)
        sessionConfigured.set(false)
        reconnecting.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.runCatching { close(1000, "Stopped by user") }
        webSocket = null
    }

    companion object {
        private const val TAG = "RealtimeWsEngine"
    }
}
