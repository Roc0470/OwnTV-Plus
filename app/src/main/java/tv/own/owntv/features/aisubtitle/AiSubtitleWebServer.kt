// DO NOT EDIT — generated
package tv.own.owntv.features.aisubtitle

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.companion.CompanionLink
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 轻量级局域网手机扫码配置服务 ([AiSubtitleWebServer])。
 *
 * 允许用户在电视屏幕弹出二维码后，用手机微信或系统浏览器扫码打开局域网配置页 (`http://<tv-ip>:8091/`)，
 * 一键选择智谱、阿里百炼、Google Gemini、OneAPI 中转站或 TVGO 预设，粘贴 `API Key` / `Base URL` / `Model`，
 * 提供【一键连通性测试 (Test Connection)】，保存后电视端即时热更新生效。
 */
class AiSubtitleWebServer(
    private val configStore: AiSubtitleConfigStore,
    private val onConfigUpdatedFromWeb: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val testHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

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
            sock.soTimeout = 15_000
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

            if (method == "POST" && path.startsWith("/api/test")) {
                val bodyStr = readRequestBody(reader, contentLength)
                val result = runTestConnection(bodyStr)
                val respJson = JSONObject().apply {
                    put("ok", result.first)
                    put("message", result.second)
                }.toString()
                writeHttpResponse(out, 200, "application/json; charset=utf-8", respJson.toByteArray(Charsets.UTF_8))
            } else if (method == "POST" && path.startsWith("/api/save")) {
                val bodyStr = readRequestBody(reader, contentLength)
                val ok = applyJsonConfig(bodyStr)
                val respJson = JSONObject().apply {
                    put("ok", ok)
                    put("message", if (ok) "配置已成功保存并同步至电视端 OwnTV！" else "解析配置数据失败，请重试")
                }.toString()
                writeHttpResponse(out, 200, "application/json; charset=utf-8", respJson.toByteArray(Charsets.UTF_8))
            } else {
                val html = buildHtmlPage(configStore.config.value)
                writeHttpResponse(out, 200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun readRequestBody(reader: BufferedReader, length: Int): String {
        if (length <= 0) return ""
        val bodyChars = CharArray(length.coerceIn(0, 131072))
        var readTotal = 0
        while (readTotal < bodyChars.size) {
            val r = reader.read(bodyChars, readTotal, bodyChars.size - readTotal)
            if (r <= 0) break
            readTotal += r
        }
        return String(bodyChars, 0, readTotal)
    }

    /**
     * 测试用户填写的接口与密钥是否能正常连通。
     */
    private fun runTestConnection(jsonStr: String): Pair<Boolean, String> = runCatching {
        val obj = JSONObject(jsonStr)
        val providerStr = obj.optString("provider", "openai_compat")
        val provider = AiSubtitleProvider.fromId(providerStr)
        var url = obj.optString("apiBaseUrl", "").trim()
        val key = obj.optString("apiKey", "").trim()
        val model = obj.optString("modelName", "").trim()
        val wsId = obj.optString("bailianWorkspace", "").trim()

        if (url.isBlank()) {
            return Pair(false, "API 接口地址不能为空")
        }

        val start = SystemClock.elapsedRealtime()

        if (provider == AiSubtitleProvider.OPENAI_COMPAT) {
            val base = url.trimEnd('/')
            val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            val testPayload = JSONObject().apply {
                put("model", model.ifBlank { "qwen-plus" })
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", "ping")
                }))
                put("max_tokens", 1)
            }
            val reqBuilder = Request.Builder()
                .url(endpoint)
                .post(testPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (key.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $key")
            }
            val call = testHttpClient.newCall(reqBuilder.build())
            val resp = call.execute()
            val cost = SystemClock.elapsedRealtime() - start
            val code = resp.code
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                return Pair(true, "接口验证成功！OpenAI 兼容接口响应正常 (HTTP $code，耗时: ${cost}ms)")
            } else if (code == 401) {
                return Pair(false, "鉴权失败：HTTP 401 Unauthorized，请核对 API Key 是否正确输入")
            } else if (code == 404) {
                return Pair(false, "端点未找到：HTTP 404 Not Found，请检查 Base URL 是否填写正确（通常需填写至 /v1）")
            } else {
                val errSnippet = JSONObject(body).optJSONObject("error")?.optString("message") ?: body.take(120)
                return Pair(false, "接口返回错误 HTTP $code: $errSnippet")
            }
        } else {
            // Realtime WebSocket 模式 (阿里云百炼 / TVGO)
            var wsUrl = url
            if (wsUrl.startsWith("https://")) wsUrl = "wss://" + wsUrl.removePrefix("https://")
            if (wsUrl.startsWith("http://")) wsUrl = "ws://" + wsUrl.removePrefix("http://")

            if (provider == AiSubtitleProvider.TVGO) {
                val sep = if (wsUrl.contains("?")) "&" else "?"
                wsUrl = "${wsUrl}${sep}device=test"
            } else {
                if (wsId.isNotBlank() && wsUrl.contains("aliyuncs.com") && !wsUrl.contains(wsId)) {
                    wsUrl = "wss://${wsId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime"
                }
                if (!wsUrl.contains("model=")) {
                    val sep = if (wsUrl.contains("?")) "&" else "?"
                    wsUrl = "${wsUrl}${sep}model=${model.ifBlank { "qwen3.8-livetranslate-flash-realtime" }}"
                }
            }

            val reqBuilder = Request.Builder().url(wsUrl)
            if (key.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $key")
            }
            if (provider == AiSubtitleProvider.OPENAI_REALTIME) {
                reqBuilder.header("OpenAI-Beta", "realtime=v1")
            }

            val latch = CountDownLatch(1)
            val success = AtomicBoolean(false)
            val errMsg = StringBuilder()

            val ws = testHttpClient.newWebSocket(
                reqBuilder.build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        success.set(true)
                        latch.countDown()
                        webSocket.close(1000, "test done")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        success.set(true)
                        latch.countDown()
                        webSocket.close(1000, "test done")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val code = response?.code
                        errMsg.append(t.message ?: (if (code != null) "HTTP $code" else "连接中断"))
                        latch.countDown()
                    }
                },
            )

            val finished = latch.await(6, TimeUnit.SECONDS)
            val cost = SystemClock.elapsedRealtime() - start
            if (!finished) {
                runCatching { ws.cancel() }
                return Pair(false, "连接超时：6秒内未能与指定 WebSocket 建立连接")
            }
            if (success.get()) {
                return Pair(true, "握手成功！Realtime WebSocket 流式服务正常连通 (耗时: ${cost}ms)")
            } else {
                return Pair(false, "握手失败：$errMsg")
            }
        }
    }.getOrElse { e ->
        Pair(false, "测试异常: ${e.message}")
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
  :root { --bg: #0c1116; --card: #161f29; --accent: #2dd4bf; --accent-hover: #14b8a6; --blue: #38bdf8; --text: #f1f5f9; --muted: #94a3b8; --border: rgba(255,255,255,0.08); }
  * { box-sizing: border-box; }
  body { margin: 0; padding: 16px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: var(--bg); color: var(--text); }
  .wrap { max-width: 540px; margin: 0 auto; padding-bottom: 40px; }
  h1 { font-size: 20px; margin: 6px 0 8px; color: var(--accent); }
  .sub { font-size: 13px; color: var(--muted); margin-bottom: 16px; line-height: 1.5; }
  .card { background: var(--card); border-radius: 14px; padding: 16px; margin-bottom: 14px; border: 1px solid var(--border); box-shadow: 0 4px 16px rgba(0,0,0,0.25); }
  .label { display: block; font-size: 13px; font-weight: 600; margin-bottom: 6px; color: #cbd5e1; }
  .hint { font-size: 12px; color: var(--muted); margin-top: 4px; margin-bottom: 12px; line-height: 1.4; }
  input, select { width: 100%; padding: 11px 12px; border-radius: 10px; border: 1px solid rgba(255,255,255,0.15); background: #0f172a; color: #fff; font-size: 14px; outline: none; transition: border-color 0.2s; }
  input:focus, select:focus { border-color: var(--accent); }
  .presets { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 4px; }
  .preset-btn { padding: 9px 10px; border-radius: 9px; border: 1px solid rgba(45,212,191,0.35); background: rgba(45,212,191,0.1); color: var(--accent); font-size: 12px; font-weight: 600; cursor: pointer; text-align: left; }
  .preset-btn:active { background: rgba(45,212,191,0.25); }
  .row { display: flex; gap: 10px; }
  .row > div { flex: 1; }
  .btn-group { display: flex; flex-direction: column; gap: 10px; margin-top: 14px; }
  .btn { width: 100%; padding: 14px; border-radius: 12px; border: none; font-size: 15px; font-weight: 700; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 8px; }
  .btn-primary { background: var(--accent); color: #042f2e; }
  .btn-primary:active { background: var(--accent-hover); }
  .btn-test { background: rgba(56, 189, 248, 0.15); color: var(--blue); border: 1px solid rgba(56, 189, 248, 0.4); }
  .btn-test:active { background: rgba(56, 189, 248, 0.25); }
  .status-box { display: none; padding: 14px; border-radius: 12px; margin-top: 12px; font-size: 13.5px; line-height: 1.5; font-weight: 500; }
  .status-success { background: rgba(45,212,191,0.15); border: 1px solid var(--accent); color: #5eead4; }
  .status-error { background: rgba(244,63,94,0.15); border: 1px solid #f43f5e; color: #fda4af; }
  .status-info { background: rgba(56,189,248,0.15); border: 1px solid var(--blue); color: #7dd3fc; }
</style>
</head>
<body>
<div class="wrap">
  <h1>OwnTV · AI 实时字幕配置</h1>
  <div class="sub">支持 OpenAI 兼容通用接口（阿里百炼 / 智谱 GLM / Google Gemini / 中转站）及 VsTV 同款 Realtime 实时流式 WebSocket 与 TVGO 局域网服务。</div>

  <div class="card">
    <span class="label">⚡ 一键填入常用服务商预设</span>
    <div class="presets">
      <button type="button" class="preset-btn" onclick="applyPreset('openai_realtime','wss://dashscope.aliyuncs.com/api-ws/v1/realtime','qwen3.8-livetranslate-flash-realtime','')">阿里百炼 Realtime (VsTV同款)</button>
      <button type="button" class="preset-btn" onclick="applyPreset('openai_compat','https://dashscope.aliyuncs.com/compatible-mode/v1','qwen-plus','')">阿里百炼 (OpenAI兼容直连)</button>
      <button type="button" class="preset-btn" onclick="applyPreset('openai_compat','https://open.bigmodel.cn/api/paas/v4','glm-4-flash','glm-asr')">智谱清言 (GLM-ASR + Flash)</button>
      <button type="button" class="preset-btn" onclick="applyPreset('openai_compat','https://generativelanguage.googleapis.com/v1beta/openai','gemini-2.5-flash','')">Google Gemini 2.5 Flash</button>
      <button type="button" class="preset-btn" onclick="applyPreset('openai_compat','https://api.openai.com/v1','gpt-4o-mini','whisper-1')">OpenAI / 中转站 (Whisper+Chat)</button>
      <button type="button" class="preset-btn" onclick="applyPreset('tvgo','ws://192.168.0.2:1666/ai','default','')">TVGO 局域网服务</button>
    </div>
  </div>

  <div class="card">
    <label class="label">协议类型 (Provider)</label>
    <select id="provider">
      <option value="openai_realtime" ${if (cfg.provider == AiSubtitleProvider.OPENAI_REALTIME) "selected" else ""}>Realtime 实时流式 WebSocket (阿里百炼 / VsTV同款)</option>
      <option value="openai_compat" ${if (cfg.provider == AiSubtitleProvider.OPENAI_COMPAT) "selected" else ""}>OpenAI 兼容接口 (阿里 / 智谱 / 谷歌 / OneAPI 中转站)</option>
      <option value="tvgo" ${if (cfg.provider == AiSubtitleProvider.TVGO) "selected" else ""}>TVGO 局域网服务 (ws://host:port/ai)</option>
    </select>
    <div class="hint">阿里云百炼专属 qwen3.8-livetranslate 选第 1 项；中转站、通用多模态模型选第 2 项。</div>

    <label class="label">API 接口地址 (Base URL / WebSocket URL)</label>
    <input id="apiBaseUrl" type="text" value="${esc(cfg.apiBaseUrl)}" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1 或 wss://...">
    <div class="hint">填写到版本前缀即可（如 https://dashscope.aliyuncs.com/compatible-mode/v1 或中转站地址）。</div>

    <label class="label">API Key (密钥 / Token)</label>
    <input id="apiKey" type="password" value="${esc(cfg.apiKey)}" placeholder="sk-...">
    <div class="hint">支持百炼个人及工作空间 Key（sk-ws-xxx）。密钥仅保存在您的电视本地，绝不上传第三方。</div>

    <label class="label">翻译 / 全模态模型名称 (Model)</label>
    <input id="modelName" type="text" value="${esc(cfg.modelName)}" placeholder="qwen3.8-livetranslate-flash-realtime / qwen-plus / glm-4-flash">
    <div class="hint">用于语音识别与翻译的目标模型名称。</div>

    <label class="label">独立语音识别 ASR 模型名 (可选)</label>
    <input id="asrModelName" type="text" value="${esc(cfg.asrModelName)}" placeholder="留空则使用多模态直传；或填 glm-asr / whisper-1">
    <div class="hint">若使用双阶段识别（如智谱 glm-asr）可填写；若使用百炼 Realtime 或 Gemini 请留空。</div>

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

  <div class="btn-group">
    <button type="button" class="btn btn-test" id="testBtn" onclick="testConnection()">🔍 测试接口连通性 (Test)</button>
    <button type="button" class="btn btn-primary" id="saveBtn" onclick="saveConfig()">💾 保存并同步至电视</button>
  </div>

  <div id="statusBox" class="status-box"></div>
</div>

<script>
function applyPreset(provider, url, model, asr) {
  document.getElementById('provider').value = provider;
  document.getElementById('apiBaseUrl').value = url;
  document.getElementById('modelName').value = model;
  document.getElementById('asrModelName').value = asr;
  showStatus('info', '已填入预设模板，请输入或确认您的 API Key 后点击测试或保存。');
}

function getFormData() {
  return {
    provider: document.getElementById('provider').value,
    apiBaseUrl: document.getElementById('apiBaseUrl').value.trim(),
    apiKey: document.getElementById('apiKey').value.trim(),
    modelName: document.getElementById('modelName').value.trim(),
    asrModelName: document.getElementById('asrModelName').value.trim(),
    bailianWorkspace: document.getElementById('bailianWorkspace').value.trim(),
    leadSeconds: parseInt(document.getElementById('leadSeconds').value || '5', 10),
    targetLang: document.getElementById('targetLang').value,
    showOriginal: document.getElementById('showOriginal').value === 'true'
  };
}

function showStatus(type, msg) {
  const box = document.getElementById('statusBox');
  box.className = 'status-box status-' + type;
  box.style.display = 'block';
  box.textContent = msg;
  box.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

async function testConnection() {
  const btn = document.getElementById('testBtn');
  const payload = getFormData();
  if (!payload.apiBaseUrl) {
    showStatus('error', '请输入 API 接口地址');
    return;
  }
  btn.disabled = true;
  btn.textContent = '⏳ 正在探测接口与鉴权...';
  showStatus('info', '正在向指定接口发起连通性与密钥鉴权测试，请稍候...');
  try {
    const res = await fetch('/api/test', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(payload)
    });
    const data = await res.json();
    if (data.ok) {
      showStatus('success', '✅ ' + (data.message || '接口连通成功！'));
    } else {
      showStatus('error', '❌ ' + (data.message || '接口连接失败'));
    }
  } catch (e) {
    showStatus('error', '❌ 无法连接电视端配置服务: ' + (e.message || e));
  } finally {
    btn.disabled = false;
    btn.textContent = '🔍 测试接口连通性 (Test)';
  }
}

async function saveConfig() {
  const btn = document.getElementById('saveBtn');
  const payload = getFormData();
  btn.disabled = true;
  btn.textContent = '💾 正在同步...';
  try {
    const res = await fetch('/api/save', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(payload)
    });
    const data = await res.json();
    if (data.ok) {
      showStatus('success', '🎉 ' + (data.message || '配置已成功同步至电视！'));
      alert('✅ 配置已成功保存！电视端 OwnTV 已即时热更新生效。');
    } else {
      showStatus('error', '❌ ' + (data.message || '保存失败'));
    }
  } catch (e) {
    showStatus('error', '❌ 同步失败: ' + (e.message || e));
  } finally {
    btn.disabled = false;
    btn.textContent = '💾 保存并同步至电视';
  }
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
