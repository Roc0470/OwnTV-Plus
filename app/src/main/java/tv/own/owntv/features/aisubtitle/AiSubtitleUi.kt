// DO NOT EDIT — generated
package tv.own.owntv.features.aisubtitle

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.player.PlaybackEngine
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StagePopupCheck
import tv.own.owntv.ui.stage.StagePopupIcon
import tv.own.owntv.ui.stage.StagePopupLabel
import tv.own.owntv.ui.stage.StagePopupOption
import tv.own.owntv.ui.stage.StagePopupRadio
import tv.own.owntv.ui.stage.StageStepper
import tv.own.owntv.ui.theme.LocalUiFontScaleFactor
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * AI 实时字幕与延迟直播屏幕叠加层 ([AiSubtitleOverlay])。
 *
 * 挂载在视频画布上方（同时支持 ExoPlayer 直播引擎与 MPV 播放引擎，支持全屏及小窗缩放）：
 * 1. 当处于 [AiSubtitleState.Preparing] 阶段时：在画面正上方偏中显示半透明胶囊提示：
 *    `✦ AI 字幕准备中 · 正在提前收听并翻译，约 X 秒后开始播放`（1:1 复刻 VsTV 延迟直播准备横幅）；
 * 2. 当处于 [AiSubtitleState.Running] 阶段且存在当前激活的 [AiSubtitleCue] 时：
 *    在画面底部居中渲染半透明圆角字幕框（双语模式：上方浅青色原文 + 下方大号纯白加粗中文译文）；
 * 3. 当处于 [AiSubtitleState.Error] 阶段时：短暂展示错误原因提示。
 */
@UnstableApi
@Composable
fun AiSubtitleOverlay(
    modifier: Modifier = Modifier,
    sizeScale: Float = 1f,
    controller: AiSubtitleController = koinInject(),
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val cue by controller.activeCue.collectAsStateWithLifecycle()
    val config by controller.configStore.config.collectAsStateWithLifecycle()

    if (state is AiSubtitleState.Off) return

    val uiFontCompensation = 1f / LocalUiFontScaleFactor.current
    val scale = config.fontSizeScale * sizeScale * uiFontCompensation

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(
                horizontal = 40.dp * sizeScale,
                vertical = 48.dp * sizeScale,
            ),
    ) {
        // 1. 准备阶段（提前收听缓冲倒计时横幅）或错误提示
        when (val s = state) {
            is AiSubtitleState.Preparing -> {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 18.dp * sizeScale)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xD90D1821))
                        .padding(horizontal = 24.dp * sizeScale, vertical = 12.dp * sizeScale),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OwnTVIcon(
                        icon = OwnTVIcon.SPARKLE,
                        tint = Color(0xFF2DD4BF),
                        modifier = Modifier.size((22 * sizeScale).dp),
                    )
                    Text(
                        text = "AI 字幕准备中 · 正在提前收听并翻译，约 ${s.secondsLeft} 秒后开始播放",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = (20 * sizeScale * uiFontCompensation).sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
            is AiSubtitleState.Error -> {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 18.dp * sizeScale)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xD93B1219))
                        .padding(horizontal = 22.dp * sizeScale, vertical = 10.dp * sizeScale),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "AI 字幕提示：${s.message}",
                        style = TextStyle(
                            color = Color(0xFFFFB4AB),
                            fontSize = (18 * sizeScale * uiFontCompensation).sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
            else -> Unit
        }

        // 2. 实时双语/中文翻译字幕卡片（底部居中）
        val active = cue
        if (active != null && (active.translatedText.isNotBlank() || active.originalText.isNotBlank())) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 1120.dp * sizeScale)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 22.dp * sizeScale, vertical = 10.dp * sizeScale),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (config.showOriginal && active.originalText.isNotBlank()) {
                    Text(
                        text = active.originalText,
                        textAlign = TextAlign.Center,
                        style = TextStyle(
                            color = Color(0xFF99F6E4),
                            fontSize = (18f * scale).sp,
                            lineHeight = (23f * scale).sp,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Normal,
                            shadow = Shadow(color = Color.Black, offset = Offset(0f, 1.5f), blurRadius = 4f),
                        ),
                    )
                    Spacer(Modifier.height(4.dp * sizeScale))
                }
                if (active.translatedText.isNotBlank()) {
                    Text(
                        text = active.translatedText,
                        textAlign = TextAlign.Center,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = (25f * scale).sp,
                            lineHeight = (31f * scale).sp,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.SemiBold,
                            shadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 6f),
                        ),
                    )
                }
            }
        }
    }
}

/**
 * AI 实时字幕与延迟直播设置弹窗 ([AiSubtitleSettingsDialog])。
 *
 * 支持遥控器 D-pad 全功能操作：
 * - 一键开启/关闭当前频道的 AI 实时字幕；
 * - ◀ / ▶ 调节“延迟直播提前收听秒数”（3s ~ 12s，默认 5s）；
 * - 切换双语显示（原文 + 译文 vs 仅译文）；
 * - ◀ / ▶ 调节字幕字号比例（80% ~ 150%）；
 * - 一键切换常用服务商预设（智谱清言 / 阿里百炼 Realtime / 阿里百炼多模态 / Google Gemini / 中转站 / TVGO）；
 * - 打开“手机扫码配置”面板，自动在电视端启动 `AiSubtitleWebServer` 并渲染二维码，手机扫码即可粘贴 API Key 和自定义中转站地址。
 */
@UnstableApi
@Composable
fun AiSubtitleSettingsDialog(
    player: PlaybackEngine,
    onDismiss: () -> Unit,
    controller: AiSubtitleController = koinInject(),
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val config by controller.configStore.config.collectAsStateWithLifecycle()
    var showQrPanel by remember { mutableStateOf(false) }

    if (showQrPanel) {
        AiSubtitleQrCodeDialog(
            configStore = controller.configStore,
            onBack = { showQrPanel = false },
            onDismiss = onDismiss,
        )
        return
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(8) {
            delay(40)
            if (runCatching { firstFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    val isRunning = state !is AiSubtitleState.Off
    val statusSubtitle = when (val s = state) {
        is AiSubtitleState.Off -> if (config.isConfigured) {
            "当前已关闭 · 已配置 ${config.provider.displayName} (${config.modelName})"
        } else {
            "当前未配置 API Key · 请点击下方「手机扫码配置」快速填入"
        }
        is AiSubtitleState.Preparing -> "正在提前收听并翻译，约 ${s.secondsLeft} 秒后恢复画面播放…"
        is AiSubtitleState.Running -> "运行中 · 延迟直播 ${config.leadSeconds} 秒对齐 (${config.modelName})"
        is AiSubtitleState.Error -> "异常：${s.message}"
    }

    StagePopup(
        onDismiss = onDismiss,
        title = "AI 实时字幕与延迟直播",
        eyebrow = "AI LIVE SUBTITLE · 兼容 VsTV & OpenAI 多模型",
        eyebrowAccent = true,
        eyebrowIcon = OwnTVIcon.SPARKLE,
        width = 760.mpx,
        scroll = false,
        buttons = {
            StageButton(
                text = "手机扫码配置 API",
                icon = OwnTVIcon.PHONE,
                onClick = { showQrPanel = true },
                height = 54.mpx,
                textSize = 18,
            )
            StageButton(
                text = "完成",
                onClick = onDismiss,
                height = 54.mpx,
                textSize = 18,
                tinted = true,
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(6.mpx),
        ) {
            // 1. 总开关
            item {
                StagePopupOption(
                    title = if (isRunning) "关闭 AI 实时字幕" else "开启 AI 实时字幕（延迟直播同步）",
                    subtitle = statusSubtitle,
                    chosen = isRunning,
                    modifier = Modifier.focusRequester(firstFocus),
                    leading = { focused -> StagePopupCheck(isRunning, focused) },
                    onClick = {
                        if (!config.isConfigured && !isRunning) {
                            showQrPanel = true
                        } else {
                            controller.toggle(player)
                        }
                    },
                )
            }

            // 2. 手机扫码配置入口（强烈推荐）
            item {
                StagePopupOption(
                    title = "手机扫码配置 API Key / 模型 / 中转站",
                    subtitle = if (config.apiKey.isNotBlank()) {
                        "已配置密钥 (${config.apiKey.take(5)}***) · 地址: ${config.apiBaseUrl}"
                    } else {
                        "用手机浏览器或微信扫码，一键粘贴智谱/阿里/谷歌/中转站接口与密钥"
                    },
                    leading = { StagePopupIcon(OwnTVIcon.PHONE) },
                    value = "扫码",
                    onClick = { showQrPanel = true },
                )
            }

            // 3. 延迟直播与显示参数微调
            item {
                StagePopupLabel("延迟直播与字幕显示设置", Modifier.padding(top = 10.mpx))
            }

            item {
                AiStepperRow(
                    title = "延迟直播提前收听时间",
                    subtitle = "开启字幕时主画面暂停等待秒数，抵消 AI 识别与翻译耗时（推荐 5 秒）",
                    valueText = "${config.leadSeconds} 秒",
                    onLeft = {
                        controller.configStore.update {
                            it.copy(leadSeconds = (it.leadSeconds - 1).coerceAtLeast(AiSubtitleConfigStore.MIN_LEAD_SECONDS))
                        }
                    },
                    onRight = {
                        controller.configStore.update {
                            it.copy(leadSeconds = (it.leadSeconds + 1).coerceAtMost(AiSubtitleConfigStore.MAX_LEAD_SECONDS))
                        }
                    },
                )
            }

            item {
                StagePopupOption(
                    title = "显示双语字幕（源语言原文 + 中文译文）",
                    subtitle = if (config.showOriginal) "当前：同时显示识别原文与翻译结果" else "当前：仅显示翻译后的目标语言",
                    leading = { focused -> StagePopupCheck(config.showOriginal, focused) },
                    onClick = {
                        controller.configStore.update { it.copy(showOriginal = !it.showOriginal) }
                    },
                )
            }

            item {
                val pct = (config.fontSizeScale * 100).toInt()
                AiStepperRow(
                    title = "AI 字幕字号缩放",
                    subtitle = "使用遥控器 ◀ / ▶ 调整字幕大小",
                    valueText = "$pct%",
                    onLeft = {
                        controller.configStore.update {
                            it.copy(fontSizeScale = (it.fontSizeScale - 0.1f).coerceIn(0.8f, 1.5f))
                        }
                    },
                    onRight = {
                        controller.configStore.update {
                            it.copy(fontSizeScale = (it.fontSizeScale + 0.1f).coerceIn(0.8f, 1.5f))
                        }
                    },
                )
            }

            // 4. 服务商预设快速切换
            item {
                StagePopupLabel("快速切换 AI 模型服务商预设", Modifier.padding(top = 10.mpx))
            }

            items(AiProviderPreset.entries.size) { index ->
                val preset = AiProviderPreset.entries[index]
                val selected = config.provider == preset.provider &&
                    config.apiBaseUrl == preset.baseUrl &&
                    config.modelName == preset.modelName
                StagePopupOption(
                    title = preset.title,
                    subtitle = "${preset.description} (${preset.modelName})",
                    chosen = selected,
                    leading = { focused -> StagePopupRadio(selected, focused) },
                    onClick = {
                        controller.configStore.applyPreset(preset)
                    },
                )
            }
        }
    }
}

@Composable
private fun AiStepperRow(
    title: String,
    subtitle: String,
    valueText: String,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    StagePopupOption(
        title = title,
        subtitle = subtitle,
        onClick = onRight,
        modifier = Modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> {
                    onLeft()
                    true
                }
                Key.DirectionRight -> {
                    onRight()
                    true
                }
                else -> false
            }
        },
        trailing = { StageStepper(valueText) },
    )
}

/**
 * 手机扫码配置弹窗 ([AiSubtitleQrCodeDialog])。
 * 打开时自动启动 `AiSubtitleWebServer`，展示局域网二维码与访问地址，手机端保存后实时刷新。
 */
@Composable
private fun AiSubtitleQrCodeDialog(
    configStore: AiSubtitleConfigStore,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val config by configStore.config.collectAsStateWithLifecycle()
    var serverUrl by remember { mutableStateOf("") }
    var syncedNotice by remember { mutableStateOf(false) }

    val webServer = remember(configStore) {
        AiSubtitleWebServer(
            configStore = configStore,
            onConfigUpdatedFromWeb = { syncedNotice = true },
        )
    }

    DisposableEffect(webServer) {
        val url = webServer.start()
        serverUrl = url
        onDispose { webServer.stop() }
    }

    val qrBitmap = remember(serverUrl) {
        if (serverUrl.isNotBlank()) CompanionLink.renderQr(serverUrl, 512) else null
    }

    val doneFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { doneFocus.requestFocus() }
    }

    StagePopup(
        onDismiss = onBack,
        title = "手机扫码配置 AI 字幕接口",
        eyebrow = "REMOTE CONFIGURATION · 局域网免遥控器输入",
        eyebrowAccent = true,
        eyebrowIcon = OwnTVIcon.PHONE,
        body = "请使用手机（需与电视连接同一 Wi-Fi / 局域网）扫描下方二维码，或直接在手机/电脑浏览器打开右侧网址，即可一键粘贴智谱、阿里百炼、Google Gemini、中转站 API Key 与模型名称。",
        width = 760.mpx,
        buttons = {
            StageButton(
                text = "返回设置",
                onClick = onBack,
                height = 54.mpx,
                textSize = 18,
            )
            StageButton(
                text = "完成并关闭",
                onClick = onDismiss,
                height = 54.mpx,
                textSize = 18,
                tinted = true,
                modifier = Modifier.focusRequester(doneFocus),
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.mpx),
            horizontalArrangement = Arrangement.spacedBy(28.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (qrBitmap != null) {
                Box(
                    modifier = Modifier
                        .size(210.mpx)
                        .clip(RoundedCornerShape(18.mpx))
                        .background(Color.White)
                        .padding(12.mpx),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "AI Subtitle Config QR Code",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.mpx),
            ) {
                Text(
                    text = "局域网配置网址：",
                    style = stageText(17, 500),
                    color = StageColors.Muted,
                )
                Text(
                    text = serverUrl.ifBlank { "正在启动局域网服务…" },
                    style = stageText(24, 800),
                    color = stageAccent.accent,
                )
                Spacer(Modifier.height(4.mpx))
                Text(
                    text = "当前生效协议：${config.provider.displayName}",
                    style = stageText(17, 600),
                    color = StageColors.Text,
                )
                Text(
                    text = "当前模型：${config.modelName}" +
                        (if (config.asrModelName.isNotBlank()) " + ${config.asrModelName}" else ""),
                    style = stageText(16, 500),
                    color = StageColors.Muted,
                )
                Text(
                    text = "API Key 状态：" + if (config.apiKey.isNotBlank()) "已配置 (${config.apiKey.take(5)}***)" else "未填写",
                    style = stageText(16, 500),
                    color = if (config.apiKey.isNotBlank()) Color(0xFF4ADE80) else Color(0xFFFBBF24),
                )
                if (syncedNotice) {
                    Text(
                        text = "✓ 已收到手机端同步的最新配置！",
                        style = stageText(18, 700),
                        color = Color(0xFF2DD4BF),
                    )
                }
            }
        }
    }
}
