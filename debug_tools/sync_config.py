#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OwnTV AI 实时字幕一键配置同步工具 (免遥控器、秒级注入)
用法：
  python tools/sync_config.py --api-key "sk-..."
  python tools/sync_config.py --device 192.168.0.196:5555 --model qwen3.8-livetranslate-flash-realtime
"""

import os
import sys
import argparse
import subprocess

PREFS_TEMPLATE = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="target_lang">{target_lang}</string>
    <string name="source_lang">{source_lang}</string>
    <string name="model_name">{model_name}</string>
    <int name="lead_seconds" value="{lead_seconds}" />
    <string name="provider">{provider}</string>
    <string name="api_key">{api_key}</string>
    <boolean name="show_original" value="{show_original}" />
    <float name="font_scale" value="{font_scale}" />
    <string name="api_base_url">{api_base_url}</string>
</map>
"""

DEFAULT_API_KEY = "sk-ws-H.YEYHXDX.4dr3.MEUCIBRMF36rp6aH4MsyDRTDcC-a0Tr-iVl3-zmIYUQGCqo9AiEAuhsEZxixCSGeMI1POE05ydzFr05LKuA2wI3JJNh8CRM"

def get_connected_devices():
    output = subprocess.check_output(["adb", "devices"], text=True)
    devices = []
    for line in output.strip().splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices

def main():
    parser = argparse.ArgumentParser(description="一键同步 OwnTV AI 实时字幕配置")
    parser.add_argument("--device", type=str, default="", help="指定设备序列号/IP:PORT (默认自动选取在线设备)")
    parser.add_argument("--api-key", type=str, default=DEFAULT_API_KEY, help="API Key")
    parser.add_argument("--model", type=str, default="qwen3.8-livetranslate-flash-realtime", help="同传模型")
    parser.add_argument("--lead", type=int, default=5, help="延迟直播秒数")
    parser.add_argument("--show-orig", action="store_true", default=True, help="是否显示双语原文")

    args = parser.parse_args()

    devices = get_connected_devices()
    if not devices:
        print("[错误] 未发现任何在线的 ADB 设备！请确保模拟器或电视已开启并 adb connect。")
        sys.exit(1)

    # 优先匹配当前活跃的设备（192.168.0.199 或带 5555 的在线设备）
    target_device = args.device
    if not target_device:
        for d in devices:
            if "192.168.0.199" in d:
                target_device = d
                break
        if not target_device:
            target_device = devices[0]

    print(f"[*] 目标设备: {target_device}")

    xml_content = PREFS_TEMPLATE.format(
        target_lang="zh",
        source_lang="auto",
        model_name=args.model,
        lead_seconds=args.lead,
        provider="openai_realtime",
        api_key=args.api_key,
        show_original="true" if args.show_orig else "false",
        font_scale=1.1,
        api_base_url="wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
    )

    tmp_path = os.path.join(os.environ.get("TEMP", "."), "owntv_sub_prefs.xml")
    with open(tmp_path, "w", encoding="utf-8") as f:
        f.write(xml_content.strip())

    remote_dest = "/data/data/tv.own.owntv/shared_prefs/owntv_ai_subtitle_prefs.xml"
    print(f"[*] 正在推送配置文件至 {remote_dest}...")

    # 推送至临时目录再移动并设置权限
    subprocess.check_call(["adb", "-s", target_device, "push", tmp_path, "/data/local/tmp/prefs.xml"])
    cmd = (
        "appUid=$(stat -c '%u' /data/data/tv.own.owntv 2>/dev/null || echo 10058); "
        "mkdir -p /data/data/tv.own.owntv/shared_prefs; "
        "cp /data/local/tmp/prefs.xml /data/data/tv.own.owntv/shared_prefs/owntv_ai_subtitle_prefs.xml; "
        "chown -R ${appUid}:${appUid} /data/data/tv.own.owntv/shared_prefs; "
        "chmod 660 /data/data/tv.own.owntv/shared_prefs/owntv_ai_subtitle_prefs.xml"
    )
    subprocess.check_call(["adb", "-s", target_device, "shell", cmd])

    print("\033[1;32m[成功] AI 实时字幕配置已秒级同步至目标设备！重启或在播放器开启即可生效。\033[0m")

if __name__ == "__main__":
    main()
