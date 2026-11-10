// DO NOT EDIT — generated
package tv.own.owntv.features.aisubtitle

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import tv.own.owntv.core.companion.CompanionLink
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 轻量级局域网手机扫码配置服务 ([AiSubtitleWebServer])。
 *
 * 允许用户在电视屏幕弹出二维码后，用手机微信或系统浏览器扫码打开局域网配置页 (`http://<tv-ip>:8091/`)，
 * 一键选择智谱、阿里百炼、Google Gemini、OneAPI 中转站或 TVGO 预设，粘贴 `API Key` / `Base URL` / `Model`，
 * 保存后电视端即时热更新生效，免去用电视遥控器逐个敲字母的繁琐。
 */
class AiSubtitleWebServer(
    private val configStore: AiSubtitleConfigStore,
    private val onConfigUpdatedFromWeb: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    var listeningUrl: String? = null
        private set

    /** 启动局域网 HTTP 监听，默认使用端口 8091（若被占用自动顺延） */
    fun start(preferredPort: Int = DEFAULT_PORT): String {
        stop()
        for (port in preferredPort..(preferredPort + 5)) {
            runCatching {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                val url = CompanionLink.lanUrl(port)
                listeningUrl = url
                acceptJob = scope.launch {
                    while (isActive && !ss.isClosed) {
                        val client = runCatching { ss.accept() }.getOrNull() ?: break
                        launch { handleClient(client) }
                    }
                }
                return url
            }.onFailure { e ->
                Log.w(TAG, "Port $port bind failed: ${e.message}")
            }
        }
        val fallback = CompanionLink.lanUrl(preferredPort)
        listeningUrl = fallback
        return fallback
    }

    /** 停止局域网配置服务器 */
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        listeningUrl = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val out = sock.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty()

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            if (method == "POST" && path.startsWith("/api/save")) {
                val bodyChars = CharArray(contentLength.coerceIn(0, 65536))
                var readTotal = 0
                while (readTotal < bodyChars.size) {
                    val r = reader.read(bodyChars, readTotal, bodyChars.size - readTotal)
                    if (r <= 0) break
                    readTotal += r
                }
                val bodyStr = String(bodyChars, 0, readTotal)
                val ok = applyJsonConfig(bodyStr)
                val respJson = JSONObject().apply {
                    put("ok", ok)
                    put("message", if (ok) "配置已成功同步至电视端 OwnTV！" else "解析配置失败")
                }.toString()
                writeHttpResponse(out, 200, "application/json; charset=utf-8", respJson.toByteArray(Charsets.UTF_8))
            } else {
                val html = buildHtmlPage(configStore.config.value)
                writeHttpResponse(out, 200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun applyJsonConfig(jsonStr: String): Boolean = runCatching {
        val obj = JSONObject(jsonStr)
        configStore.update { cur ->
            cur.copy(
                provider = AiSubtitleProvider.fromId(obj.optString("provider", cur.provider.id)),
                apiBaseUrl = obj.optString("apiBaseUrl", cur.apiBaseUrl).trim(),
                apiKey = obj.optString("apiKey", cur.apiKey).trim(),
                modelName = obj.optString("modelName", cur.modelName).trim(),
                asrModelName = obj.optString("asrModelName", cur.asrModelName).trim(),
                bailianWorkspace = obj.optString("bailianWorkspace", cur.bailianWorkspace).trim(),
                sourceLang = obj.optString("sourceLang", cur.sourceLang).trim().ifBlank { "auto" },
                targetLang = obj.optString("targetLang", cur.targetLang).trim().ifBlank { "zh" },
                leadSeconds = obj.optInt("leadSeconds", cur.leadSeconds),
                showOriginal = obj.optBoolean("showOriginal", cur.showOriginal),
            )
        }
        onConfigUpdatedFromWeb()
        true
    }.getOrDefault(false)

    private fun writeHttpResponse(out: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val statusText = if (status == 200) "OK" else "Bad Request"
        val headers = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun buildHtmlPage(cfg: AiSubtitleConfig): String {
        val esc = { s: String ->
            s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
        }
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>OwnTV · AI 实时字幕与延迟直播配置</title>
<style>
  :root { --bg: #0c1116; --card: #161f29; --accent: #2dd4bf; --text: #f1f5f9; --muted: #94a3b8; }
  * { box-sizing: border-box; }
  body { margin: 0; padding: 16px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: var(--bg); color: var(--text); }
  .wrap { max-width: 540px; margin: 0 auto; }
  h1 { font-size: 20px; margin: 4px 0 6px; color: var(--accent); }
  .sub { font-size: 13px; color: var(--muted); margin-bottom: 16px; line-height: 1.45; }
  .card { background: var(--card); border-radius: 14px; padding: 16px; margin-bottom: 14px; border: 1px solid rgba(255,255,255,0.07); }
  .label { display: block; font-size: 13px; font-weight: 600; margin-bottom: 6px; color: #cbd5e1; }
  .hint { font-size: 12px; color: var(--muted); margin-top: 4px; margin-bottom: 12px; }
  input, select { width: 100%; padding: 11px 12px; border-radius: 10px; border: 1px solid rgba(255,255,255,0.14); background: #0f172a; color: #fff; font-size: 14px; outline: none; }
  input:focus, select:focus { border-color: var(--accent); }
  .presets { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 10px; }
  .preset-btn { padding: 9px 10px; border-radius: 9px; border: 1px solid rgba(45,212,191,0.35); background: rgba(45,212,191,0.1); color: var(--accent); font-size: 12px; font-weight: 600; cursor: pointer; text-align: left; }
  .preset-btn:active { background: rgba(45,212,191,0.25); }
  .row { display: flex; gap: 10px; }
  .row > div { flex: 1; }
  .btn { width: 100%; padding: 14px; border-radius: 12px; border: none; background: var(--accent); color: #042f2e; font-size: 16px; font-weight: 700; cursor: pointer; margin-top: 8px; }
  .toast { display: none; margin-top: 12px; padding: 12px; border-radius: 10px; background: rgba(45,212,191,0.18); color: var(--accent); font-size: 14px; text-align: center; font-weight: 600; }
</style>
</head>
<body>
<div class="wrap">
  <h1>OwnTV · AI 实时字幕配置</h1>
  <div class="sub">支持 OpenAI 兼容接口（智谱 GLM / 阿里百炼 / Google Gemini / OneAPI 中转站）以及 VsTV 同款 Realtime 实时流式 WebSocket 与 TVGO 局域网服务。</div>

  <div class="card">
    <span class="label">⚡ 一键填入常用服务商预设</span>
    <div class="presets">
      <button type="button" class="preset-btn" onclick="applyPreset('openai_compat','https://open.bigmodel.cn/api/paas/v4','glm-4-flash','glm-asr')">智谱清言 (GLM-ASR + Flash)</button>
      <button type="button" class="preset-btn" onclick="applyPreset('openai_realtime','wss://dashscope.aliyuncs.com/api-ws/v1/realtime','qwen3.8-livetranslate-flash-realtime','')">阿里百炼 Realtime (VsTV同款)</button>
      <button type="button" class="preset-btn" onclick="applyPreset('openai_compat','https://dashscope.aliyuncs.com/compatible-mode/v1','qwen-omni-turbo','')">阿里百炼 Qwen-Omni 多模态</button>
      <button type="button" class="preset-btn" onclick="applyPreset('openai_compat','https://generativelanguage.googleapis.com/v1beta/openai','gemini-2.5-flash','')">Google Gemini 2.5 Flash</button>
      <button type="button" class="preset-btn" onclick="applyPreset('openai_compat','https://api.openai.com/v1','gpt-4o-mini','whisper-1')">OpenAI / 中转站 (Whisper+Chat)</button>
      <button type="button" class="preset-btn" onclick="applyPreset('tvgo','ws://192.168.0.2:1666/ai','default','')">TVGO 局域网语音后端</button>
    </div>
  </div>

  <div class="card">
    <label class="label">协议类型 (Provider)</label>
    <select id="provider">
      <option value="openai_compat" ${if (cfg.provider == AiSubtitleProvider.OPENAI_COMPAT) "selected" else ""}>OpenAI 兼容接口 (智谱 / 阿里 / 谷歌 / 中转站)</option>
      <option value="openai_realtime" ${if (cfg.provider == AiSubtitleProvider.OPENAI_REALTIME) "selected" else ""}>Realtime 实时流式 WebSocket (阿里百炼 / OpenAI)</option>
      <option value="tvgo" ${if (cfg.provider == AiSubtitleProvider.TVGO) "selected" else ""}>TVGO 局域网服务 (ws://host:port/ai)</option>
    </select>
    <div class="hint">中转站、智谱、Gemini 选第 1 项；阿里百炼 qwen3.8-livetranslate 选第 2 项。</div>

    <label class="label">API 接口地址 (Base URL / WebSocket URL)</label>
    <input id="apiBaseUrl" type="text" value="${esc(cfg.apiBaseUrl)}" placeholder="https://api.example.com/v1">
    <div class="hint">填写到 /v1 即可（如 https://open.bigmodel.cn/api/paas/v4 或中转站 https://xxx/v1）。</div>

    <label class="label">API Key (密钥 / Token)</label>
    <input id="apiKey" type="password" value="${esc(cfg.apiKey)}" placeholder="sk-...">
    <div class="hint">密钥仅保存在您的电视本地，绝不上传第三方。</div>

    <label class="label">翻译 / 全模态模型名称 (Model)</label>
    <input id="modelName" type="text" value="${esc(cfg.modelName)}" placeholder="glm-4-flash / gemini-2.5-flash / gpt-4o-mini">
    <div class="hint">用于翻译或直接多模态听音翻译的模型名。</div>

    <label class="label">独立语音识别 ASR 模型名 (可选)</label>
    <input id="asrModelName" type="text" value="${esc(cfg.asrModelName)}" placeholder="留空则使用多模态音频直传；或填 whisper-1 / glm-asr">
    <div class="hint">若使用两阶段识别（如智谱 glm-asr 或中转站 whisper-1）请填写；若模型原生支持听音频（如 gemini-2.5-flash / qwen-omni-turbo）请留空。</div>

    <label class="label">阿里百炼专属 Workspace ID (可选)</label>
    <input id="bailianWorkspace" type="text" value="${esc(cfg.bailianWorkspace)}" placeholder="例如 ws-xxxx (非必填)">
  </div>

  <div class="card">
    <div class="row">
      <div>
        <label class="label">延迟直播提前量 (秒)</label>
        <select id="leadSeconds">
          ${(3..10).joinToString("") { s -> "<option value=\"$s\" ${if (cfg.leadSeconds == s) "selected" else ""}>提前 $s 秒 (推荐 5s)</option>" }}
        </select>
      </div>
      <div>
        <label class="label">目标字幕语言</label>
        <select id="targetLang">
          <option value="zh" ${if (cfg.targetLang == "zh") "selected" else ""}>简体中文 (zh)</option>
          <option value="zh-TW" ${if (cfg.targetLang == "zh-TW") "selected" else ""}>繁體中文 (zh-TW)</option>
          <option value="en" ${if (cfg.targetLang == "en") "selected" else ""}>English (en)</option>
          <option value="ja" ${if (cfg.targetLang == "ja") "selected" else ""}>日本語 (ja)</option>
        </select>
      </div>
    </div>
    <div style="margin-top: 12px;">
      <label class="label">双语字幕显示</label>
      <select id="showOriginal">
        <option value="true" ${if (cfg.showOriginal) "selected" else ""}>显示「源语言原文 + 中文译文」双语字幕</option>
        <option value="false" ${if (!cfg.showOriginal) "selected" else ""}>仅显示翻译后的目标语言字幕</option>
      </select>
    </div>
  </div>

  <button class="btn" onclick="saveConfig()">保存并同步至电视</button>
  <div id="toast" class="toast"></div>
</div>

<script>
function applyPreset(provider, url, model, asr) {
  document.getElementById('provider').value = provider;
  document.getElementById('apiBaseUrl').value = url;
  document.getElementById('modelName').value = model;
  document.getElementById('asrModelName').value = asr;
}
async function saveConfig() {
  const payload = {
    provider: document.getElementById('provider').value,
    apiBaseUrl: document.getElementById('apiBaseUrl').value,
    apiKey: document.getElementById('apiKey').value,
    modelName: document.getElementById('modelName').value,
    asrModelName: document.getElementById('asrModelName').value,
    bailianWorkspace: document.getElementById('bailianWorkspace').value,
    leadSeconds: parseInt(document.getElementById('leadSeconds').value || '5', 10),
    targetLang: document.getElementById('targetLang').value,
    showOriginal: document.getElementById('showOriginal').value === 'true'
  };
  const res = await fetch('/api/save', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(payload)
  });
  const data = await res.json();
  const t = document.getElementById('toast');
  t.style.display = 'block';
  t.textContent = data.message || '已保存';
}
</script>
</body>
</html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "AiSubtitleWebServer"
        const val DEFAULT_PORT = 8091
    }
}
