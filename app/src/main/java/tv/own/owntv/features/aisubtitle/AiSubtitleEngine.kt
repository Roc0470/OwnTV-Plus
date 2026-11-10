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
 * @property audioDurationMs 音频持续时长（毫秒）
 * @property isPartial 是否为流式增量未定稿字幕
 */
data class AiSubtitleCue(
    val id: String,
    val originalText: String,
    val translatedText: String,
    val audioStartElapsedMs: Long,
    val audioDurationMs: Long = 3000L,
    val isPartial: Boolean = false,
)

/**
 * AI 实时字幕引擎统一接口。
 */
interface AiSubtitleEngine {
    /** 启动引擎会话 */
    fun start()

    /** 接收 100ms 实时 16kHz Mono 16-bit PCM 音频帧（供 WebSocket 流式引擎使用） */
    fun onFramePcm(pcm16k: ByteArray) {}

    /** 接收 2.0~3.2s 完整音频切片（供 OpenAI 兼容分段引擎使用） */
    fun onSegmentPcm(chunk: Pcm16kChunk) {}

    /** 停止并释放引擎资源 */
    fun stop()
}

/**
 * 引擎一：OpenAI 兼容多供应商引擎 ([OpenAiCompatEngine])。
 *
 * 支持智谱清言 (BigModel)、阿里百炼兼容接口、Google Gemini OpenAI 端点、DeepSeek 及各类 OneAPI/NewAPI 中转站：
 * - **模式 A（两阶段 ASR + LLM 翻译）**：当配置了 `asrModelName`（如 `glm-asr`、`whisper-1`、`FunAudioLLM/SenseVoiceSmall`）时，
 *   先调用 `/audio/transcriptions` 将 16kHz WAV 转为原文，再调用 `/chat/completions` 极速翻译为目标语言；
 * - **模式 B（多模态原生音频直传）**：当 `asrModelName` 留空时（如 `gemini-2.5-flash`、`qwen-omni-turbo`、`gpt-4o-audio-preview`），
 *   直接向 `/chat/completions` 发送包含 `input_audio` (Base64 WAV) 的多模态请求，一次性返回识别原文与译文。
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
                // 如果中转站或模型不支持 /audio/transcriptions，自动回退尝试多模态 input_audio
                Log.w(TAG, "ASR HTTP ${resp.code}: $bodyStr, falling back to multimodal chat")
                processMultimodalAudioChat(chunk)
                return
            }
            parseAsrText(bodyStr)
        }.trim()

        if (transcript.isBlank() || isHallucination(transcript)) return

        val cueId = "oa-${seqCounter.getAndIncrement()}"
        // 先立即推送原文预览，再异步补齐译文（若开启了显示原文）
        if (config.showOriginal) {
            onCueReady(
                AiSubtitleCue(
                    id = cueId,
                    originalText = transcript,
                    translatedText = "",
                    audioStartElapsedMs = chunk.startElapsedMs,
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
                audioDurationMs = chunk.durationMs,
                isPartial = false,
            ),
        )
    }

    /**
     * 模式 B：多模态 `/chat/completions` (`input_audio` Base64 WAV 直传)
     * 适用于 Gemini 2.0/2.5 Flash、Qwen-Omni、GPT-4o-audio 及支持音频输入的 OneAPI 中转站。
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
 * 1:1 还原自 `VsTV` (`v2.8.0` `Lr3/A`, `Lr3/z`, `Lr3/f`) 逆向出的 WebSocket 协议：
 * 1. 连接握手：
 *    - 阿里百炼 / OpenAI Realtime：收到 `session.created` 后发送 `session.update`：
 *      `{"type":"session.update","session":{"output_modalities":["text"],"translation":{"language":"zh"}}}`
 *    - TVGO：连接 `ws://host:port/ai?device={deviceId}`
 * 2. 实时音频流推送：
 *    - 将 16000Hz Mono 16-bit PCM 编码为 Base64，持续发送 `{"type":"input_audio_buffer.append","audio":"..."}`
 * 3. 响应解析与时间戳对齐：
 *    - `input_audio_buffer.speech_started` (`audio_start_ms`)：记录当前语句相对于会话启动的音频起点偏移；
 *    - `conversation.item.input_audio_transcription.delta` / `.completed`：源语言识别原文；
 *    - `response.text.delta` / `.done` 或 `response.audio_transcript.delta` / `.done`：翻译后的目标语言字幕。
 */
class RealtimeWsEngine(
    private val config: AiSubtitleConfig,
    private val onCueReady: (AiSubtitleCue) -> Unit,
    private val onEngineStatus: (String) -> Unit,
) : AiSubtitleEngine {

    private val running = AtomicBoolean(false)
    private val sessionConfigured = AtomicBoolean(false)
    private var webSocket: WebSocket? = null
    private var sessionStartElapsedMs = 0L
    private var currentSpeechStartElapsedMs = 0L

    private val partialOriginal = StringBuilder()
    private val partialTranslated = StringBuilder()
    private val cueCounter = AtomicLong(1L)
    private var activeCueId = "rt-1"

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接不设读取超时
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    override fun start() {
        running.set(true)
        sessionConfigured.set(false)
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        currentSpeechStartElapsedMs = sessionStartElapsedMs
        connectWebSocket()
    }

    private fun buildWsUrl(): String {
        if (config.provider == AiSubtitleProvider.TVGO) {
            val base = config.apiBaseUrl.trim().trimEnd('/')
            val sep = if (base.contains("?")) "&" else "?"
            return "${base}${sep}device=${config.deviceId}"
        }
        // 阿里百炼或 OpenAI Realtime WebSocket
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
                    Log.i(TAG, "Realtime WebSocket connected. Awaiting session.created...")
                    onEngineStatus("Realtime 连接成功，正在初始化会话...")
                    if (config.provider == AiSubtitleProvider.TVGO) {
                        // TVGO 协议连上即就绪
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
                    onEngineStatus("连接中断: ${t.message ?: "网络异常"}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Realtime WebSocket closed: code=$code, reason=$reason")
                    sessionConfigured.set(false)
                }
            },
        )
    }

    /**
     * 发送 session.update 会话配置包（收到 session.created 后调用且仅调用一次）
     */
    private fun sendSessionUpdate(ws: WebSocket) {
        val sessionObj = JSONObject().apply {
            put("output_modalities", JSONArray().put("text"))
            put("voice", "Tina") // 百炼 LiveTranslate 官方默认 preset 音色
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

    override fun onFramePcm(pcm16k: ByteArray) {
        if (!running.get() || !sessionConfigured.get()) return
        val ws = webSocket ?: return
        val b64 = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", b64)
        }
        ws.send(msg.toString())
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
                    onEngineStatus("AI 实时同传已就绪 (${config.modelName})")
                }
                "input_audio_buffer.speech_started" -> {
                    val audioStartMs = evt.optLong("audio_start_ms", -1L)
                    currentSpeechStartElapsedMs = if (audioStartMs >= 0L) {
                        sessionStartElapsedMs + audioStartMs
                    } else {
                        SystemClock.elapsedRealtime()
                    }
                    finalizeCurrentSentence()
                }
                "conversation.item.created", "response.created" -> {
                    // 当新的一轮推理或新对话项生成时，如果上一句已有翻译内容，先平滑结算定稿
                    if (partialTranslated.isNotEmpty() || partialOriginal.isNotEmpty()) {
                        finalizeCurrentSentence()
                    }
                }
                "conversation.item.input_audio_transcription.delta" -> {
                    val delta = evt.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        if (shouldSplitSentence(partialTranslated.toString(), partialOriginal.toString())) {
                            finalizeCurrentSentence()
                        }
                        partialOriginal.append(delta)
                        emitCurrentCue(isPartial = true)
                    }
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = evt.optString("transcript", "")
                    if (transcript.isNotBlank()) {
                        partialOriginal.setLength(0)
                        partialOriginal.append(transcript.trim())
                        emitCurrentCue(isPartial = true)
                    }
                }
                "response.text.delta", "response.audio_transcript.delta", "response.output_text.delta" -> {
                    val delta = evt.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        // 智能断句：如果前文已经以句末标点（。！？.?!）结尾，新到 delta 为新一句话的开始，立即切句
                        if (shouldSplitSentence(partialTranslated.toString(), partialOriginal.toString())) {
                            finalizeCurrentSentence()
                        }
                        partialTranslated.append(delta)
                        emitCurrentCue(isPartial = true)
                    }
                }
                "response.inference_unit_finished", "response.output_item.done", "response.content_part.done",
                "response.text.done", "response.audio_transcript.done", "response.output_text.done",
                "response.done", "input_audio_buffer.speech_stopped" -> {
                    val fullText = evt.optString("text", "").ifBlank { evt.optString("transcript", "") }
                    if (fullText.isNotBlank()) {
                        partialTranslated.setLength(0)
                        partialTranslated.append(fullText.trim())
                    }
                    if (partialTranslated.isNotEmpty() || partialOriginal.isNotEmpty()) {
                        finalizeCurrentSentence()
                    }
                }
                "error" -> {
                    val errMsg = evt.optJSONObject("error")?.optString("message") ?: evt.optString("message", "未知错误")
                    Log.w(TAG, "Realtime server error: $errMsg (raw: $rawJson)")
                    onEngineStatus("服务端错误: $errMsg")
                }
                else -> {
                    // 其他心跳或上下文事件，输出 debug 日志以便追踪百炼原生协议帧
                    Log.d(TAG, "Server event: $type -> ${rawJson.take(150)}")
                }
            }
        }.onFailure { err ->
            Log.e(TAG, "Failed to parse server event: $rawJson", err)
        }
    }

    /**
     * 智能断句判断：
     * 1. 中文译文末尾有明确句末标点（。！？；），且已有至少 6 个字符；
     * 2. 英文原文末尾有 . ! ? 且非英文常见缩写（如 Mr. U.S.）；
     * 3. 超长句子保护：中文连续超过 26 个字符且遇逗号（，,）时分句，确保单屏绝不超过 2 行。
     */
    private fun shouldSplitSentence(transText: String, origText: String): Boolean {
        val trans = transText.trimEnd()
        if (trans.length >= 6) {
            val lastChar = trans.last()
            if (lastChar == '。' || lastChar == '！' || lastChar == '？' || lastChar == '；') {
                return true
            }
        }
        val orig = origText.trimEnd()
        if (orig.length >= 15) {
            val lastChar = orig.last()
            if (lastChar == '!' || lastChar == '?') {
                return true
            }
            if (lastChar == '.') {
                val words = orig.split(' ')
                val lastWord = words.lastOrNull().orEmpty().lowercase()
                val isAbbr = lastWord in listOf("mr.", "ms.", "mrs.", "dr.", "u.s.", "u.k.", "e.g.", "i.e.", "vs.", "inc.", "ltd.")
                if (!isAbbr) {
                    return true
                }
            }
        }
        if (trans.length >= 26) {
            val lastChar = trans.last()
            if (lastChar == '，' || lastChar == ',') {
                return true
            }
        }
        return false
    }

    /**
     * 将当前累积的短句定稿发送至上屏队列，并安全重置缓冲区准备接收下一个短句。
     */
    private fun finalizeCurrentSentence() {
        val orig = if (config.showOriginal) partialOriginal.toString().trim() else ""
        val trans = partialTranslated.toString().trim()
        if (orig.isNotEmpty() || trans.isNotEmpty()) {
            onCueReady(
                AiSubtitleCue(
                    id = activeCueId,
                    originalText = orig,
                    translatedText = trans,
                    audioStartElapsedMs = currentSpeechStartElapsedMs,
                    audioDurationMs = 2800L,
                    isPartial = false,
                ),
            )
        }
        partialOriginal.setLength(0)
        partialTranslated.setLength(0)
        activeCueId = "rt-${cueCounter.getAndIncrement()}"
        currentSpeechStartElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun emitCurrentCue(isPartial: Boolean) {
        val orig = if (config.showOriginal) partialOriginal.toString().trim() else ""
        val trans = partialTranslated.toString().trim()
        if (orig.isEmpty() && trans.isEmpty()) return

        onCueReady(
            AiSubtitleCue(
                id = activeCueId,
                originalText = orig,
                translatedText = trans,
                audioStartElapsedMs = currentSpeechStartElapsedMs,
                audioDurationMs = 2800L,
                isPartial = isPartial,
            ),
        )
    }

    override fun stop() {
        running.set(false)
        sessionConfigured.set(false)
        webSocket?.runCatching { close(1000, "Stopped by user") }
        webSocket = null
    }

    companion object {
        private const val TAG = "RealtimeWsEngine"
    }
}
