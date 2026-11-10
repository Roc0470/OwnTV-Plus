#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OwnTV AI 实时字幕与流式同传本地免编译仿真器 (AI Subtitle Test Harness)

功能特性：
1. 0 编译、0 模拟器：直接在 Windows 终端中快速验证阿里百炼 Realtime WebSocket 协议；
2. 输入源二合一：
   - 实时直播流模式 (--stream <url>)：通过 ffmpeg 提取流式 PCM 16kHz 音频；
   - 本地音频回放模式 (--file <audio_path>)：模拟实时语速回放，用于长难句/断句规则极限压测；
3. 1:1 对齐电视端算法：
   - 严格同步 OwnTV Kotlin 端的 shouldSplitSentence 标点/长度切句逻辑；
   - 彻底修复空译文时显示 "..." 的问题（单行英文自适应，不占位）；
   - 模拟 2.8 秒语音停顿平滑消隐。
"""

import os
import sys
import json
import time
import base64
import asyncio
import argparse
import subprocess
import websockets

DEFAULT_API_KEY = "sk-ws-H.YEYHXDX.4dr3.MEUCIBRMF36rp6aH4MsyDRTDcC-a0Tr-iVl3-zmIYUQGCqo9AiEAuhsEZxixCSGeMI1POE05ydzFr05LKuA2wI3JJNh8CRM"
DEFAULT_MODEL = "qwen3.8-livetranslate-flash-realtime"
DEFAULT_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
DEFAULT_STREAM_URL = "https://tgbot-sub.sharewithyou.dpdns.org/live/kLVG3QNyMerpNyCJQFnfvK5G1cx_uaVx5wKm5LnuQ5ueIMZTcd-QNaLBY97lqqNvEvDvNSt2Nj9_7bf5nMA5vHQ2Cg-y8FKjHg6IC50xI7Az6br-bEcpsAU6E7Uo1yEfM06rT8JAC14TYkTQIbcIwkdjG_Q7ATF5pW4coQSr8PqxXCcnvUCzGkSQS8P7cwhwvXH0L1ostAU3LptT5PE02lUaK4JQ40pdW-BmOvRVu5_wX4AhZ4hqlHetW0-tPk0R68pRGwvUSMTAHYfTtiR63s8-3AnSQmf-RQ4NHmlEPjG7WNwpqKNknF6NwtGGY18XaN9L-cNHvTkmTsbjSza6H38Alys4hXXkk-jQTKBZbL8Nlks5b-gxhsxDs9R-bw.m3u8?u=6056050012"

ENGLISH_ABBREVIATIONS = {
    "mr.", "ms.", "mrs.", "dr.", "u.s.", "u.k.", "e.g.", "i.e.", "vs.", "inc.", "ltd.", "no.", "vol."
}

def should_split_by_translation(trans_text: str) -> bool:
    """与 OwnTV Kotlin 端 1:1 对齐的译文驱动断句判断"""
    trans = trans_text.rstrip()
    if len(trans) >= 4 and trans[-1] in ('。', '！', '？', '；'):
        return True

    # 长句遇逗号切分（防止超过 2 行）
    if len(trans) >= 24 and trans[-1] in ('，', ',', ' '):
        return True

    return False

class SubtitleHarness:
    def __init__(self, api_key: str, model_name: str, ws_url: str, target_lang: str, fade_sec: float):
        self.api_key = api_key
        self.model_name = model_name
        self.ws_url = ws_url
        self.target_lang = target_lang
        self.fade_sec = fade_sec

        self.cue_counter = 1
        self.partial_original = []
        self.partial_translated = []
        self.last_speech_time = time.time()
        self.cue_active = False

    def get_current_orig(self) -> str:
        return "".join(self.partial_original).strip()

    def get_current_trans(self) -> str:
        return "".join(self.partial_translated).strip()

    def render_cue_to_console(self, is_final: bool = False):
        orig = self.get_current_orig()
        trans = self.get_current_trans()

        if not orig and not trans:
            return

        self.last_speech_time = time.time()
        self.cue_active = True

        status_tag = "\033[1;32m[定稿切句]\033[0m" if is_final else "\033[1;34m[流式推词]\033[0m"
        print(f"\r\033[K{status_tag} Cue #{self.cue_counter}:")
        if orig:
            print(f"  \033[1;36mEN 原文:\033[0m {orig}")
        if trans:
            print(f"  \033[1;37mZH 译文:\033[0m {trans}")
        else:
            # 严格验证：中文未到达时不输出 '...'，保持单行英文纯净！
            print(f"  \033[90mZH 译文: (流式等待中，电视端仅单行显示英文，无省略号)\033[0m")
        print("-" * 50)
        sys.stdout.flush()

    def finalize_sentence(self):
        orig = self.get_current_orig()
        trans = self.get_current_trans()
        if orig or trans:
            self.render_cue_to_console(is_final=True)
            self.cue_counter += 1
        self.partial_original.clear()
        self.partial_translated.clear()

    async def fade_monitor(self):
        """后台协程：监测静音停顿后的平滑消隐"""
        while True:
            await asyncio.sleep(0.5)
            if self.cue_active and (time.time() - self.last_speech_time) > self.fade_sec:
                print(f"\r\033[K\033[90m[时序消隐] 静音停顿 > {self.fade_sec}s · 字幕平滑消失 (电视画面恢复纯净)\033[0m\n")
                self.cue_active = False
                sys.stdout.flush()

    async def run_ws_loop(self, pcm_queue: asyncio.Queue):
        connect_url = f"{self.ws_url}?model={self.model_name}"
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "X-DashScope-DataInspection": "enable",
        }

        print(f"\n[1/3] 正在建立 WebSocket 握手: {connect_url}")
        try:
            connect_ctx = websockets.connect(connect_url, additional_headers=headers)
        except TypeError:
            connect_ctx = websockets.connect(connect_url, extra_headers=headers)

        async with connect_ctx as ws:
            print("[2/3] WebSocket 握手成功！等待 session.created...")

            # 启动消隐监控
            asyncio.create_task(self.fade_monitor())

            # 启动音频发送协程
            asyncio.create_task(self.audio_sender_loop(ws, pcm_queue))

            # 主事件监听接收循环
            async for raw_msg in ws:
                try:
                    evt = json.loads(raw_msg)
                    evt_type = evt.get("type", "")

                    if evt_type == "session.created":
                        print("[3/3] 收到 session.created，配置实时流式同传参数...")
                        update_msg = {
                            "type": "session.update",
                            "session": {
                                "output_modalities": ["text"],
                                "voice": "Tina",
                                "translation": {"language": self.target_lang}
                            }
                        }
                        await ws.send(json.dumps(update_msg))

                    elif evt_type == "session.updated":
                        print("\033[1;32m✓ 服务端会话配置完成！实时同传流水线已启动，开始捕获字幕...\033[0m\n")

                    elif evt_type == "conversation.item.input_audio_transcription.delta":
                        delta = evt.get("delta", "")
                        if delta:
                            # 译文驱动：英文 ASR 仅做原文流式累加，绝不因为英文句号擅自切断正在计算的中文
                            self.partial_original.append(delta)
                            self.render_cue_to_console(is_final=False)

                    elif evt_type in ("response.text.delta", "response.audio_transcript.delta", "response.output_text.delta"):
                        delta = evt.get("delta", "")
                        if delta:
                            # 仅当中文自身出现句末标点时才切句
                            if should_split_by_translation(self.get_current_trans()):
                                self.finalize_sentence()
                            self.partial_translated.append(delta)
                            self.render_cue_to_console(is_final=False)

                    elif evt_type in (
                        "response.inference_unit_finished",
                        "response.output_item.done",
                        "response.content_part.done",
                        "response.text.done",
                        "response.done",
                        "input_audio_buffer.speech_stopped"
                    ):
                        full_text = evt.get("text", "") or evt.get("transcript", "")
                        if full_text:
                            self.partial_translated = [full_text.strip()]
                        # 仅当中文已产生，或说话彻底停止时，才结算定稿
                        if self.partial_translated or evt_type == "input_audio_buffer.speech_stopped":
                            self.finalize_sentence()

                    elif evt_type == "error":
                        err_msg = evt.get("error", {}).get("message", "未知错误")
                        print(f"\033[1;31m[错误] 服务端报错: {err_msg}\033[0m")

                except Exception as e:
                    print(f"[解析异常] {e}")

    async def audio_sender_loop(self, ws, pcm_queue: asyncio.Queue):
        """持续从队列取出 16kHz PCM 块，以 input_audio_buffer.append 注入"""
        while True:
            chunk = await pcm_queue.get()
            if chunk is None:
                break
            b64_audio = base64.b64encode(chunk).decode("utf-8")
            append_msg = {
                "type": "input_audio_buffer.append",
                "audio": b64_audio
            }
            await ws.send(json.dumps(append_msg))


def stream_audio_producer(source_url: str, pcm_queue: asyncio.Queue, loop):
    """通过 ffmpeg 将音频流转码为 16000Hz 16-bit Mono PCM，每 200ms 推送一块"""
    cmd = [
        "ffmpeg",
        "-loglevel", "quiet",
        "-i", source_url,
        "-f", "s16le",
        "-acodec", "pcm_s16le",
        "-ac", "1",
        "-ar", "16000",
        "pipe:1"
    ]
    print(f"[音频抓取] 启动 ffmpeg 拉取流: {source_url[:60]}...")
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)

    # 16000 sample/s * 2 bytes = 32000 bytes/s -> 200ms = 6400 bytes
    chunk_size = 6400

    try:
        while True:
            raw_bytes = proc.stdout.read(chunk_size)
            if not raw_bytes:
                break
            asyncio.run_coroutine_threadsafe(pcm_queue.put(raw_bytes), loop)
    finally:
        proc.terminate()
        asyncio.run_coroutine_threadsafe(pcm_queue.put(None), loop)


def file_audio_producer(file_path: str, pcm_queue: asyncio.Queue, loop):
    """读取本地音频，按真实语速每 200ms 推送一块"""
    cmd = [
        "ffmpeg",
        "-loglevel", "quiet",
        "-i", file_path,
        "-f", "s16le",
        "-acodec", "pcm_s16le",
        "-ac", "1",
        "-ar", "16000",
        "pipe:1"
    ]
    print(f"[音频回放] 启动本地音频压测文件: {file_path}")
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    chunk_size = 6400

    try:
        while True:
            raw_bytes = proc.stdout.read(chunk_size)
            if not raw_bytes:
                break
            asyncio.run_coroutine_threadsafe(pcm_queue.put(raw_bytes), loop)
            time.sleep(0.2)  # 严格对齐 200ms 实时语速
    finally:
        proc.terminate()
        asyncio.run_coroutine_threadsafe(pcm_queue.put(None), loop)


def main():
    parser = argparse.ArgumentParser(description="OwnTV AI 实时字幕免编译本地仿真器")
    parser.add_argument("--stream", type=str, default=DEFAULT_STREAM_URL, help="在线直播流地址 (HLS m3u8)")
    parser.add_argument("--file", type=str, default="", help="本地压测音频文件 (wav/mp3/m4a)")
    parser.add_argument("--api-key", type=str, default=DEFAULT_API_KEY, help="阿里百炼 DashScope API Key")
    parser.add_argument("--model", type=str, default=DEFAULT_MODEL, help="Realtime 翻译模型")
    parser.add_argument("--target-lang", type=str, default="zh", help="目标语言代码 (默认: zh)")
    parser.add_argument("--fade", type=float, default=2.8, help="停顿平滑消隐时间 (秒)")

    args = parser.parse_args()

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    pcm_queue = asyncio.Queue()

    harness = SubtitleHarness(
        api_key=args.api_key,
        model_name=args.model,
        ws_url=DEFAULT_WS_URL,
        target_lang=args.target_lang,
        fade_sec=args.fade
    )

    import threading
    if args.file:
        producer_thread = threading.Thread(target=file_audio_producer, args=(args.file, pcm_queue, loop), daemon=True)
    else:
        producer_thread = threading.Thread(target=stream_audio_producer, args=(args.stream, pcm_queue, loop), daemon=True)
    producer_thread.start()

    try:
        loop.run_until_complete(harness.run_ws_loop(pcm_queue))
    except KeyboardInterrupt:
        print("\n[退出] 仿真器已被用户手动停止。")


if __name__ == "__main__":
    main()
