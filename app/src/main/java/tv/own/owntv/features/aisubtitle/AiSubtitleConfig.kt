// DO NOT EDIT — generated
package tv.own.owntv.features.aisubtitle

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * AI 实时字幕服务提供商类型。
 * 支持：
 * 1. [OPENAI_COMPAT]：OpenAI 兼容接口（智谱 GLM、阿里百炼、Google Gemini、DeepSeek、各类 OneAPI/NewAPI 中转站）
 * 2. [OPENAI_REALTIME]：OpenAI Realtime / 阿里百炼实时语音翻译 WebSocket 协议（如 qwen3.8-livetranslate-flash-realtime、glm-realtime）
 * 3. [TVGO]：局域网/自建 tvgo 实时字幕后端（ws://host:port/ai?device=xxx）
 */
enum class AiSubtitleProvider(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val defaultAsrModel: String,
) {
    OPENAI_COMPAT(
        id = "openai_compat",
        displayName = "OpenAI 兼容接口 (智谱 / 阿里 / 谷歌 / 中转站)",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4-flash",
        defaultAsrModel = "glm-asr",
    ),
    OPENAI_REALTIME(
        id = "openai_realtime",
        displayName = "Realtime 实时流式 WebSocket (阿里百炼 / OpenAI)",
        defaultBaseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
        defaultModel = "qwen3.8-livetranslate-flash-realtime",
        defaultAsrModel = "",
    ),
    TVGO(
        id = "tvgo",
        displayName = "TVGO 局域网语音服务 (兼容 VsTV)",
        defaultBaseUrl = "ws://192.168.0.2:1666/ai",
        defaultModel = "default",
        defaultAsrModel = "",
    );

    companion object {
        fun fromId(id: String?): AiSubtitleProvider =
            entries.firstOrNull { it.id == id } ?: OPENAI_COMPAT
    }
}

/**
 * AI 实时字幕垂直位置预设。
 */
enum class SubtitlePositionPreset(val id: String, val displayName: String, val baseOffsetDp: Int) {
    BOTTOM("bottom", "标准底部 (常规影视贴底)", 0),
    NEWS_ELEVATED("news_elevated", "新闻抬高 (避让跑马灯 +72dp)", 72),
    TOP("top", "画面顶部 (体育赛事比分在底部)", 0);

    companion object {
        fun fromId(id: String?): SubtitlePositionPreset =
            entries.firstOrNull { it.id == id } ?: BOTTOM
    }
}

/**
 * AI 实时字幕渲染视觉风格。
 */
enum class SubtitleRenderStyle(val id: String, val displayName: String) {
    TRANSLUCENT_CARD("card", "电视半透明卡片 (强抗复杂画面干扰)"),
    CINEMA_TRANSPARENT("cinema", "电影透亮无框 (纯粗体文字 + 立体阴影)");

    companion object {
        fun fromId(id: String?): SubtitleRenderStyle =
            entries.firstOrNull { it.id == id } ?: TRANSLUCENT_CARD
    }
}

/**
 * 常用服务商一键预设配置，方便用户在电视端或手机扫码页一键填入。
 */
enum class AiProviderPreset(
    val id: String,
    val title: String,
    val provider: AiSubtitleProvider,
    val baseUrl: String,
    val modelName: String,
    val asrModelName: String,
    val description: String,
) {
    ZHIPU(
        id = "zhipu",
        title = "智谱清言 (BigModel)",
        provider = AiSubtitleProvider.OPENAI_COMPAT,
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        modelName = "glm-4-flash",
        asrModelName = "glm-asr",
        description = "智谱 GLM-ASR 语音识别 + GLM-4-Flash 极速翻译",
    ),
    ALI_BAILIAN_WS(
        id = "ali_bailian_ws",
        title = "阿里百炼 Realtime (VsTV 同款低延迟)",
        provider = AiSubtitleProvider.OPENAI_REALTIME,
        baseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
        modelName = "qwen3.8-livetranslate-flash-realtime",
        asrModelName = "",
        description = "阿里云百炼 WebSocket 实时流式同传，延迟最低",
    ),
    ALI_BAILIAN_OPENAI(
        id = "ali_bailian_openai",
        title = "阿里百炼 OpenAI 兼容 (Qwen-Omni / SenseVoice)",
        provider = AiSubtitleProvider.OPENAI_COMPAT,
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        modelName = "qwen-omni-turbo",
        asrModelName = "",
        description = "百炼 OpenAI 兼容多模态音频直传翻译",
    ),
    GOOGLE_GEMINI(
        id = "google_gemini",
        title = "Google Gemini (OpenAI 兼容端点)",
        provider = AiSubtitleProvider.OPENAI_COMPAT,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        modelName = "gemini-2.5-flash",
        asrModelName = "",
        description = "Gemini 原生多模态音频理解与实时字幕翻译",
    ),
    RELAY_STATION(
        id = "relay_station",
        title = "自定义中转站 (OneAPI / NewAPI)",
        provider = AiSubtitleProvider.OPENAI_COMPAT,
        baseUrl = "https://api.openai.com/v1",
        modelName = "gpt-4o-mini",
        asrModelName = "whisper-1",
        description = "支持任意 OpenAI 协议中转站 (Whisper + Chat 或多模态)",
    ),
    TVGO_LAN(
        id = "tvgo_lan",
        title = "TVGO 局域网服务 (VsTV 协议)",
        provider = AiSubtitleProvider.TVGO,
        baseUrl = "ws://192.168.0.2:1666/ai",
        modelName = "default",
        asrModelName = "",
        description = "局域网自建 tvgo 实时识别与翻译后端",
    ),
}

/**
 * AI 实时字幕持久化配置数据模型。
 */
data class AiSubtitleConfig(
    /** 当前选择的协议提供商 */
    val provider: AiSubtitleProvider = AiSubtitleProvider.OPENAI_COMPAT,
    /** API 基础地址（如 https://.../v1 或 wss://.../realtime） */
    val apiBaseUrl: String = AiSubtitleProvider.OPENAI_COMPAT.defaultBaseUrl,
    /** API 密钥 (Bearer Token / API Key) */
    val apiKey: String = "",
    /** 翻译或全模态模型名称 (如 glm-4-flash, gemini-2.5-flash, qwen-omni-turbo, qwen3.8-livetranslate-flash-realtime) */
    val modelName: String = AiSubtitleProvider.OPENAI_COMPAT.defaultModel,
    /** 可选的独立语音识别 (ASR) 模型名称（如 whisper-1, glm-asr, FunAudioLLM/SenseVoiceSmall；留空则使用多模态 input_audio 直传） */
    val asrModelName: String = AiSubtitleProvider.OPENAI_COMPAT.defaultAsrModel,
    /** 阿里百炼专属 Workspace ID（可选，如 ws-xxxx，若填写则自动拼接百炼专属域名） */
    val bailianWorkspace: String = "",
    /** 源语言代码（auto 表示自动检测，或 en/ja/ko/ru/fr/es 等） */
    val sourceLang: String = "auto",
    /** 目标翻译语言代码（zh 表示简体中文，en 表示英文，ja 表示日文等） */
    val targetLang: String = "zh",
    /** 延迟直播提前收听秒数（3 ~ 12 秒，默认 5 秒，复刻 VsTV 延迟直播对齐机制） */
    val leadSeconds: Int = 5,
    /** 是否同时显示识别出的原文字幕（双语字幕模式） */
    val showOriginal: Boolean = true,
    /** 字幕字号缩放比例（0.8f ~ 1.6f，默认 1.0f） */
    val fontSizeScale: Float = 1.0f,
    /** 是否在打开播放器时自动开启 AI 实时同传（默认关闭，保持原版零干扰秒播） */
    val autoStart: Boolean = false,
    /** 垂直位置预设（标准底部、新闻抬高、画面顶部） */
    val positionPreset: SubtitlePositionPreset = SubtitlePositionPreset.BOTTOM,
    /** 像素级垂直推拉微调量 (-100dp .. +200dp) */
    val verticalOffsetDp: Int = 0,
    /** 渲染视觉风格（电视半透明卡片 / 电影透亮无框） */
    val renderStyle: SubtitleRenderStyle = SubtitleRenderStyle.TRANSLUCENT_CARD,
    /** 字幕时序快慢微调偏移量（毫秒，-5000ms ~ +5000ms，默认 0ms；负数表示字幕提前，正数表示字幕延后） */
    val syncOffsetMs: Int = 0,
    /** 设备唯一标识（用于 tvgo 协议握手） */
    val deviceId: String = "",
) {
    /** 检查当前配置是否已具备发起请求的基本凭据 */
    val isConfigured: Boolean
        get() = when (provider) {
            AiSubtitleProvider.TVGO -> apiBaseUrl.isNotBlank()
            AiSubtitleProvider.OPENAI_COMPAT, AiSubtitleProvider.OPENAI_REALTIME ->
                apiBaseUrl.isNotBlank() && apiKey.isNotBlank() && modelName.isNotBlank()
        }

    /** 目标语言的中文可读名称 */
    fun targetLangLabel(): String = when (targetLang) {
        "zh" -> "简体中文"
        "zh-TW" -> "繁體中文"
        "en" -> "English"
        "ja" -> "日本語"
        "ko" -> "한국어"
        else -> targetLang
    }
}

/**
 * AI 实时字幕配置仓库，基于 SharedPreferences 持久化并向 UI 暴露响应式 [StateFlow]。
 */
class AiSubtitleConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AiSubtitleConfig> = _config.asStateFlow()

    private fun load(): AiSubtitleConfig {
        var devId = prefs.getString(KEY_DEVICE_ID, null).orEmpty()
        if (devId.isBlank()) {
            devId = UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString(KEY_DEVICE_ID, devId).apply()
        }
        val provider = AiSubtitleProvider.fromId(prefs.getString(KEY_PROVIDER, null))
        return AiSubtitleConfig(
            provider = provider,
            apiBaseUrl = prefs.getString(KEY_BASE_URL, null) ?: provider.defaultBaseUrl,
            apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
            modelName = prefs.getString(KEY_MODEL, null) ?: provider.defaultModel,
            asrModelName = prefs.getString(KEY_ASR_MODEL, null) ?: provider.defaultAsrModel,
            bailianWorkspace = prefs.getString(KEY_BAILIAN_WS, null).orEmpty(),
            sourceLang = prefs.getString(KEY_SOURCE_LANG, null) ?: "auto",
            targetLang = prefs.getString(KEY_TARGET_LANG, null) ?: "zh",
            leadSeconds = prefs.getInt(KEY_LEAD_SECONDS, 5).coerceIn(MIN_LEAD_SECONDS, MAX_LEAD_SECONDS),
            showOriginal = prefs.getBoolean(KEY_SHOW_ORIGINAL, true),
            fontSizeScale = prefs.getFloat(KEY_FONT_SCALE, 1.0f).coerceIn(0.75f, 1.6f),
            autoStart = prefs.getBoolean(KEY_AUTO_START, false),
            positionPreset = SubtitlePositionPreset.fromId(prefs.getString(KEY_POSITION_PRESET, null)),
            verticalOffsetDp = prefs.getInt(KEY_VERTICAL_OFFSET, 0).coerceIn(-100, 200),
            renderStyle = SubtitleRenderStyle.fromId(prefs.getString(KEY_RENDER_STYLE, null)),
            syncOffsetMs = prefs.getInt(KEY_SYNC_OFFSET, 0).coerceIn(MIN_SYNC_OFFSET_MS, MAX_SYNC_OFFSET_MS),
            deviceId = devId,
        )
    }

    /** 更新并持久化配置 */
    fun update(transform: (AiSubtitleConfig) -> AiSubtitleConfig) {
        val next = transform(_config.value).let {
            it.copy(
                leadSeconds = it.leadSeconds.coerceIn(MIN_LEAD_SECONDS, MAX_LEAD_SECONDS),
                fontSizeScale = it.fontSizeScale.coerceIn(0.75f, 1.6f),
                verticalOffsetDp = it.verticalOffsetDp.coerceIn(-100, 200),
                syncOffsetMs = it.syncOffsetMs.coerceIn(MIN_SYNC_OFFSET_MS, MAX_SYNC_OFFSET_MS),
            )
        }
        prefs.edit()
            .putString(KEY_PROVIDER, next.provider.id)
            .putString(KEY_BASE_URL, next.apiBaseUrl.trim())
            .putString(KEY_API_KEY, next.apiKey.trim())
            .putString(KEY_MODEL, next.modelName.trim())
            .putString(KEY_ASR_MODEL, next.asrModelName.trim())
            .putString(KEY_BAILIAN_WS, next.bailianWorkspace.trim())
            .putString(KEY_SOURCE_LANG, next.sourceLang.trim().ifBlank { "auto" })
            .putString(KEY_TARGET_LANG, next.targetLang.trim().ifBlank { "zh" })
            .putInt(KEY_LEAD_SECONDS, next.leadSeconds)
            .putBoolean(KEY_SHOW_ORIGINAL, next.showOriginal)
            .putFloat(KEY_FONT_SCALE, next.fontSizeScale)
            .putBoolean(KEY_AUTO_START, next.autoStart)
            .putString(KEY_POSITION_PRESET, next.positionPreset.id)
            .putInt(KEY_VERTICAL_OFFSET, next.verticalOffsetDp)
            .putString(KEY_RENDER_STYLE, next.renderStyle.id)
            .putInt(KEY_SYNC_OFFSET, next.syncOffsetMs)
            .apply()
        _config.value = next
    }

    /** 调整字幕同步偏移量（毫秒，正数延后，负数提前） */
    fun adjustSyncOffset(deltaMs: Int) {
        update { it.copy(syncOffsetMs = (it.syncOffsetMs + deltaMs).coerceIn(MIN_SYNC_OFFSET_MS, MAX_SYNC_OFFSET_MS)) }
    }

    /** 重置字幕同步偏移量至 0 毫秒 */
    fun resetSyncOffset() {
        update { it.copy(syncOffsetMs = 0) }
    }

    /** 应用预设模板（保留已填写的 API Key） */
    fun applyPreset(preset: AiProviderPreset) {
        update { current ->
            current.copy(
                provider = preset.provider,
                apiBaseUrl = preset.baseUrl,
                modelName = preset.modelName,
                asrModelName = preset.asrModelName,
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "owntv_ai_subtitle_prefs"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_BASE_URL = "api_base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model_name"
        private const val KEY_ASR_MODEL = "asr_model_name"
        private const val KEY_BAILIAN_WS = "bailian_workspace"
        private const val KEY_SOURCE_LANG = "source_lang"
        private const val KEY_TARGET_LANG = "target_lang"
        private const val KEY_LEAD_SECONDS = "lead_seconds"
        private const val KEY_SHOW_ORIGINAL = "show_original"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_POSITION_PRESET = "position_preset"
        private const val KEY_VERTICAL_OFFSET = "vertical_offset"
        private const val KEY_RENDER_STYLE = "render_style"
        private const val KEY_SYNC_OFFSET = "sync_offset_ms"
        private const val KEY_DEVICE_ID = "device_id"

        const val MIN_LEAD_SECONDS = 3
        const val MAX_LEAD_SECONDS = 12
        const val MIN_SYNC_OFFSET_MS = -5000
        const val MAX_SYNC_OFFSET_MS = 5000
        const val STEP_SYNC_OFFSET_MS = 500
    }
}
