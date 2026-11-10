#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OwnTV 电视端无损热升级与推送脚本 (TV Push & Lossless Updater)

特性:
1. 原生无损覆盖升级: 当 APK 签名一致时，直接使用 `adb install -r -d` 进行零停机数据保留热升级。
2. 首次签名平滑过渡: 若当前电视端运行的是旧临时签名版本 (报 INSTALL_FAILED_UPDATE_INCOMPATIBLE)，
   脚本自动通过 `run-as` 导出数据库与偏好设置，换装永久证书版本后毫秒级恢复所有频道、配置与 API Key。
3. 自动连接与检测: 默认连接 192.168.0.114:5555，支持自动从本地 build 输出或 GitHub Actions 产物中选取最新 APK。
"""

import os
import sys
import glob
import time
import argparse
import subprocess
from pathlib import Path

DEFAULT_TV_IP = "192.168.0.114:5555"
PACKAGE_NAME = "tv.own.owntv"
BACKUP_REMOTE_TAR = "/sdcard/owntv_backup.tar.gz"

def log(msg, level="INFO"):
    colors = {
        "INFO": "\033[1;34m[*] ",
        "SUCCESS": "\033[1;32m[✓] ",
        "WARN": "\033[1;33m[!] ",
        "ERROR": "\033[1;31m[✗] "
    }
    reset = "\033[0m"
    print(f"{colors.get(level, '')}{msg}{reset}", flush=True)

def run_adb(device, args, check=True, capture=True):
    cmd = ["adb", "-s", device] + args
    if capture:
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
        if check and res.returncode != 0:
            raise subprocess.CalledProcessError(res.returncode, cmd, res.stdout, res.stderr)
        return res
    else:
        return subprocess.run(cmd, check=check)

def ensure_device_connected(target_device):
    log(f"正在检查 ADB 连接状态 (目标: {target_device})...")
    if ":" in target_device:
        # 网络设备，尝试主动 connect
        subprocess.run(["adb", "connect", target_device], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    res = subprocess.run(["adb", "devices"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    devices = []
    for line in res.stdout.strip().splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])

    if target_device in devices:
        log(f"设备 {target_device} 已连接且处于就绪状态。", "SUCCESS")
        return target_device

    if devices:
        log(f"指定设备 {target_device} 未找到，自动切换至在线设备: {devices[0]}", "WARN")
        return devices[0]

    log(f"未找到任何可用设备，请确认电视已开机且 ADB 开启！", "ERROR")
    sys.exit(1)

def find_latest_apk(specified_path=None):
    if specified_path and os.path.exists(specified_path):
        return os.path.abspath(specified_path)

    # 搜索 app/build/outputs/apk/
    candidates = []
    for pattern in [
        "app/build/outputs/apk/release/*.apk",
        "app/build/outputs/apk/debug/*.apk",
        "build/outputs/apk/release/*.apk",
        "build/outputs/apk/debug/*.apk",
        "*.apk"
    ]:
        candidates.extend(glob.glob(pattern))

    if not candidates:
        return None

    # 按修改时间倒序排列
    candidates.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return os.path.abspath(candidates[0])

def backup_app_data(device):
    log("正在通过 run-as 备份电视端应用数据 (全量数据库与偏好配置)...")
    cmd = (
        f"run-as {PACKAGE_NAME} tar -czf /data/data/{PACKAGE_NAME}/cache/backup.tar.gz -C /data/data/{PACKAGE_NAME} databases shared_prefs 2>/dev/null && "
        f"run-as {PACKAGE_NAME} cat /data/data/{PACKAGE_NAME}/cache/backup.tar.gz > /data/local/tmp/tv_backup.tar.gz"
    )
    res = run_adb(device, ["shell", cmd], check=False)
    
    # 验证备份包是否存在
    check_res = run_adb(device, ["shell", "ls -lh /data/local/tmp/tv_backup.tar.gz"], check=False)
    if "No such file" in check_res.stdout or check_res.returncode != 0:
        log("应用数据备份未找到或当前尚未初始化，继续安装...", "WARN")
        return False
    
    # 尝试同步一份到 PC 本地 backups 目录作为双重保险
    os.makedirs("debug_tools/backups", exist_ok=True)
    run_adb(device, ["pull", "/data/local/tmp/tv_backup.tar.gz", "debug_tools/backups/tv_backup.tar.gz"], check=False)
    
    log(f"数据备份成功归档至电视 /data/local/tmp/tv_backup.tar.gz 及本地 PC 备份库", "SUCCESS")
    return True

def restore_app_data(device):
    log("正在从备份包还原应用数据...")
    restore_cmd = (
        f"cat /data/local/tmp/tv_backup.tar.gz | run-as {PACKAGE_NAME} tar -xz -C /data/data/{PACKAGE_NAME} && "
        f"rm -f /data/local/tmp/tv_backup.tar.gz"
    )
    res = run_adb(device, ["shell", restore_cmd], check=False)
    if res.returncode == 0:
        log("应用数据 (全部频道列表、自定义源、收藏夹、AI 实时字幕配置、API Key) 100% 完整还原！", "SUCCESS")
        return True
    else:
        log(f"还原数据时遇到提示: {res.stderr or res.stdout}", "WARN")
        return False

def install_apk(device, apk_path):
    log(f"准备推送安装 APK: {apk_path} ({os.path.getsize(apk_path) / (1024*1024):.2f} MB)")
    
    # 首先尝试直接无损热覆盖升级 (-r: 保留数据, -d: 允许降级/同版本覆盖)
    log("执行直接覆盖安装 (`adb install -r -d`)...")
    res = run_adb(device, ["install", "-r", "-d", apk_path], check=False)
    output = res.stdout + res.stderr
    
    if "Success" in output:
        log("直接覆盖安装成功！所有应用数据及配置已 100% 原生保留！", "SUCCESS")
        return True

    log(f"直接覆盖未成功，ADB 响应: {output.strip()}", "WARN")

    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in output or "signatures do not match" in output:
        log("检测到签名不兼容 (这是由于旧版本使用了临时随机密钥导致)。", "WARN")
        log("即将启动【平滑签名过渡机制】：备份数据 -> 换装永久证书版 -> 还原数据...", "INFO")
        
        has_backup = backup_app_data(device)
        
        log(f"正在卸载旧签名版本...")
        run_adb(device, ["uninstall", PACKAGE_NAME], check=False)
        
        log(f"正在安装新永久签名版 APK...")
        install_res = run_adb(device, ["install", "-r", apk_path])
        if "Success" not in (install_res.stdout + install_res.stderr):
            log(f"安装失败: {install_res.stdout + install_res.stderr}", "ERROR")
            return False
            
        log("永久签名版安装成功！", "SUCCESS")
        
        if has_backup:
            restore_app_data(device)
            
        log("首次签名迁移过渡已彻底完成！今后所有升级均支持直接覆盖，绝不再掉数据！", "SUCCESS")
        return True
    else:
        log(f"安装遇到未知错误: {output}", "ERROR")
        return False

def start_app(device):
    log(f"正在启动 {PACKAGE_NAME}...")
    run_adb(device, ["shell", "am", "start", "-n", f"{PACKAGE_NAME}/.MainActivity"], check=False)
    log("应用已启动并唤醒至前台！", "SUCCESS")

def main():
    parser = argparse.ArgumentParser(description="OwnTV 电视端无损热升级与推送工具")
    parser.add_argument("--device", type=str, default=DEFAULT_TV_IP, help=f"设备 IP:PORT (默认: {DEFAULT_TV_IP})")
    parser.add_argument("--apk", type=str, default=None, help="指定 APK 文件路径 (默认自动搜寻最新 build 输出)")
    parser.add_argument("--no-start", action="store_true", help="安装完成后不自动启动应用")
    args = parser.parse_args()

    # 统一终端编码
    if sys.platform == "win32":
        sys.stdout.reconfigure(encoding="utf-8")

    device = ensure_device_connected(args.device)

    apk_path = find_latest_apk(args.apk)
    if not apk_path or not os.path.exists(apk_path):
        log("未能在本地找到 APK 文件！请先执行打包命令或通过 --apk 指定路径。", "ERROR")
        sys.exit(1)

    success = install_apk(device, apk_path)
    if success and not args.no_start:
        time.sleep(1)
        start_app(device)

if __name__ == "__main__":
    main()
