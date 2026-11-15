<p align="center">
  <picture>
    <source media="(prefers-color-scheme: light)" srcset="extras/brand/app-logos/logo_eggshell_light.png">
    <img src="extras/brand/app-logos/logo_eggshell.png" alt="OwnTV" width="360">
  </picture>
</p>

<p align="center">
  <b>Your own IPTV player for Android TV</b><br>
  <sub>Fast · modern · remote-first — bring your own M3U, Xtream or Stalker (MAC) sources</sub>
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20TV-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Compose for TV" src="https://img.shields.io/badge/Jetpack%20Compose-for%20TV-4285F4?logo=jetpackcompose&logoColor=white">
  <img alt="Player" src="https://img.shields.io/badge/engines-libmpv%20%2B%20ExoPlayer-FB8C00">
  <img alt="License" src="https://img.shields.io/badge/license-GPLv3-blue">
  <img alt="Built with the help of AI" src="https://img.shields.io/badge/built%20with-the%20help%20of%20AI-8A2BE2">
  <a href="https://hosted.weblate.org/engage/owntv/">
    <img alt="Translation status" src="https://hosted.weblate.org/widget/owntv/svg-badge.svg">
  </a>
</p>

<p align="center">
  <a href="https://github.com/ahXN00/OwnTV/actions/workflows/android.yml">
    <img alt="Android CI" src="https://github.com/ahXN00/OwnTV/actions/workflows/android.yml/badge.svg">
  </a>
</p>

---
OwnTV is a native **Android TV** IPTV **player**, built with Kotlin and Jetpack Compose for TV. It
runs **two playback engines** — libmpv (FFmpeg) for the widest compatibility, ExoPlayer (Media3) for
near-instant Live TV — and it is a *player only*: you bring your own **Xtream** login, **M3U**
playlist (URL or a local file) or **Stalker/Ministra** portal.

> ⚠️ OwnTV ships **no** channels, playlists, subscriptions or streams. You are responsible for the
> sources you add.

Open source, GPLv3, original code, built with the help of AI. **Android TV only** — leanback
launcher, D-pad first.

---

## 🚀 OwnTV+ (Plus Edition) 功能更新说明 (v5.3.0-ai)

**OwnTV+ (`OwnTV-Plus`)** 在原版 OwnTV 基础上深度集成了 **AI 实时同传双语字幕系统（AI Live Subtitle）**，专为跨语种电视直播打造：

### 🧠 1. 大段完整长字幕模式（Paragraph Semantic Unit）
- **流式语义累积**：告别碎片化短句闪烁与排队滞后，支持将连续语音智能合并为 **22~42 字的大段完整语义长句**，同一张字幕卡片内逐字平滑延伸，达到句末强标点或长停顿后自动翻篇。
- **自适应三行排版**：长字幕容器放宽至 `1220.dp`，原文与译文均支持最多 **3 行折行**；当译文超过 26 字时自动微调字号缩放（`90%`），兼顾远距离阅读清晰度与画面不遮挡。
- **从容阅读驻留与防闪烁消隐**：长句保底驻留时长提升至 **4.5 秒**（下一段长句满 8 字时支持 2.8 秒平滑让位），静音防闪烁消隐时间延长至 **10.0 秒**。

### ⏱️ 2. 双模式音频探路与毫秒级 PTS 音画同步
- **三种音频采集策略一键切换**：
  - **智能自适应 (Auto)**：自动识别直播源连接限制，支持多连接时优先启用后台超前探路，遇到单连接防踢源自动无缝降级为直采模式。
  - **强制延迟探路 (Standalone Lead Scout)**：后台独立超前拉流解码音频（默认 5 秒前置缓冲），建立 **60 秒环形音频 PTS 时间轴**，彻底抵消大模型网络推理耗时，实现主播开口即出字幕。
  - **单连接直采保活 (Direct Tap Mode)**：通过 `TapForwardingAudioSink` 挂载主播放器 `AudioSink` 旁路零拷贝提取 16kHz PCM，**0 额外网络连接、0 解码器冲突**，彻底杜绝单连接 IPTV 源踢线或封号，且彻底解决无声/静音帧导致的 WebSocket 断流问题。
- **缓冲卡顿时钟冻结与追帧重同步**：主画面缓冲卡顿时自动冻结字幕时钟（屏幕字幕不抢跑、不消失），恢复播放后自动对齐最新语音窗口。
- **双向时序校准（`-10.0s` ~ `+15.0s`）**：播放界面支持通过遥控器方向键或设置面板按 `±0.5s` / `±2.0s` 实时微调字幕快慢，并支持**“立即对齐当前语音”**一键复位。

### 🌐 3. 多模型同传引擎与手机扫码 Web 控制台
- **内置主流同传预设**：支持阿里云百炼 `qwen3.8-livetranslate-flash-realtime`（推荐，WebSocket 毫秒级流式同传）、智谱 GLM、Google Gemini、OpenAI Realtime / Whisper 以及 TVGO 引擎。
- **局域网手机扫码配置（`http://<TV_IP>:8964`）**：无需用电视遥控器繁琐输入 API Key，手机扫码即可在网页端一键切换供应商预设、粘贴 API Key、调节字幕位置/字号/背景透明度与时序偏移，配置实时同步上屏。

> ### 📖 New here? **[User Guide →](extras/USER_GUIDE.md)**
> Every feature, where to find it, and the remote shortcuts — on one page.

---

## 💬 Community

Questions, ideas, bug reports — **join the OwnTV Telegram group:**

### 👉 [t.me/owntvplayer](https://t.me/owntvplayer)

<a href="https://t.me/owntvplayer"><img src="extras/qr-codes/telegram_qr_code.jpg" alt="Scan to join the OwnTV Telegram group" width="150"></a>

---

## ✨ Features

### 🎬 Playback
- **Two engines** — mpv for compatibility, ExoPlayer for instant Live TV, with automatic fallback and a per-channel override
- **4K HDR** direct rendering, optional auto frame-rate matching, surround sound with a stereo safety net, 150% volume boost
- **Subtitles** — text, image and closed captions, plus OpenSubtitles search and local files
- Resume, next-episode auto-play, mini-player/PiP, picture-in-picture when you press Home, audio-only mode, live stream-info overlay
- Media-session transport keys and proper audio focus — it behaves like a TV app
- An **error log you can export** for any stream that misbehaves

### 🧭 Browse
- **Home** with Continue Watching and **Now Trending** (TMDB rankings matched to what your provider can actually play)
- Live TV · Movies · Series · Downloads · Guide, with per-profile row order, Favorites, History and search
  (voice search where the TV has a speech recogniser)
- **Multiple playlists** at once, with provider labels everywhere they could be confused
- Rename, hide, reorder and combine categories; bulk rename rules
- **TMDB** posters, plots, cast and trailers in 40 languages — scales to ~50k channels / ~168k movies
- **Two layouts for Movies & Series** — the classic three panels, or **Cinematic**: the focused title's artwork full-bleed behind the whole screen, with its details and cast above a wide poster grid
- Adjustable panel widths per section, including hiding the preview pane entirely

### 📥 Sources & EPG
- **Xtream**, **M3U** and **Stalker/Ministra** — add any of them from your phone over Wi-Fi (QR + PIN)
- **TV Guide** (XMLTV) with a live Now marker, multiple guide sources and automatic channel matching
- **Catch-up TV** up to 7 days, live rewind, and *Go back to…* even for channels with no guide
- Guide time offset, per-source guide logos, per-channel EPG fixes
- **Protected (DRM) channels** — Widevine and ClearKey, read from the playlist, nothing to configure
- Custom **DNS** (Google, Cloudflare, Quad9, DoH or your own)

### ⏺️ Recording & Multiview
- Record live TV from the guide, the channel list or while watching — including every showing of a programme
- **Multiview** — up to four channels at once, with one carrying the sound
- OwnTV measures how many streams your provider allows and warns before it refuses one

### 👥 Profiles & Downloads
- Multiple profiles with their own favourites, history and resume points, PIN locks and a Kids mode; lock single categories behind a PIN, and optionally Settings too
- Per-profile startup: Home, last channel, the last channel in the TV Guide or Live TV, Favorites, or one
  chosen channel
- Offline movies and episodes with pause, resume, retry and a storage bar

### 🎨 Look & robustness
- Your own accent colour and focus highlight, frosted **Glass** with adjustable opacity, and a Stage,
  Picture or Plain background
- **Dark and Light themes** (or follow the TV) across the whole app, the player included
- Font sizing for the interface and for popups, independently
- **Remote Shortcuts** — map spare colour, number, channel and media keys to 26 actions; **Player buttons**
  choose what OK and a held OK do in the player, swap channel up/down, and turn number keys off
- **Menu labels** — the section names beside the menu icons: off, when focused, or always
- **26 interface languages**, RTL-aware, chosen before anything else on a fresh install
- **Backup & Restore** to a single `.own` file, optionally encrypted, locally or over Wi-Fi
- **Set up from another device** — a new box copies everything from the OwnTV device you already
  have over your own Wi-Fi, offered on the first setup screen rather than buried in the menus
- In-app updates; memory-safe lists, auto-reconnect and offline detection throughout

---

## 📸 Screenshots

<table>
  <tr>
    <td align="center"><img src="extras/screenshots/Home.png" alt="Home screen"><br><sub>Home — Continue Watching</sub></td>
    <td align="center"><img src="extras/screenshots/LiveTV_with_PreviewON.png" alt="Live TV with preview"><br><sub>Live TV — preview playing</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="extras/screenshots/EPG_loaded.png" alt="TV Guide"><br><sub>TV Guide (EPG)</sub></td>
    <td align="center"><img src="extras/screenshots/Settings_Main.png" alt="Settings"><br><sub>Settings</sub></td>
  </tr>
</table>

More in **[extras/screenshots/](extras/screenshots/)**.

---

## 🧱 Tech stack

| Area | Choice |
|------|--------|
| Language | Kotlin 2.4.20 (no `kotlin-android` plugin) |
| Build | AGP 9.4.0 / Gradle 9.8.2, KSP2 2.3.11, JDK 25 |
| UI | Jetpack Compose for TV (`androidx.tv:tv-material` 1.1.0), Compose BOM 2026.08.00 |
| Media | **libmpv** (FFmpeg) · **ExoPlayer/Media3** 1.11.1 |
| Database | Room 2.8.5 + Paging 3.5.1 + FTS4 |
| DI · Network · Images | Koin 4.2.2 · OkHttp 5 · Coil 3.6.0 |

`minSdk 26`, `targetSdk 36`, `compileSdk 37`, `applicationId tv.own.owntv`.

> **Build note:** there is no `kotlin-android` plugin. AGP 9 ships its own Kotlin, but the Compose
> compiler plugin pulls the Kotlin Gradle plugin up to the `kotlin` version in
> `gradle/libs.versions.toml` — that is what compiles the app, so keep the Compose compiler plugin on
> exactly that version. KSP versions independently and does not have to match it.

## ⚙️ How it works

- **Parsing & sync** — M3U is line-streamed and Xtream JSON is read with `JsonReader`, so a huge
  provider payload is never fully buffered. Refresh is chunked, cancellable and reports progress.
- **Storage** — a 27-entity Room schema with FTS4 search; Paging 3 with a bounded size keeps memory
  flat across 50k-item lists.
- **EPG** — XMLTV is stream-parsed (gzip-aware) into a rolling window and pruned.
- **Player** — two engines behind one `PlaybackEngine` interface, with a four-rung fallback ladder.
  Live promotes the running preview straight to full screen with no reload.
- **DI** — Koin modules.

### Project layout

This repository is the **Android TV app**. Everything underneath it — database, sync, parsers, EPG,
backup, settings storage, the playback engines and every translated string — is a separate core
library, shared with the [**OwnTV Mobile**](https://github.com/ahXN00/OwnTV_Mobile) app for phones
and tablets.

```
OwnTV/  (this repo)
tv.own.owntv/
├── player/      the TV player HUD, surfaces and mini-player
├── ui/          theme + reusable components
├── features/    setup, shell, live, movies, series, search, downloads, epg, profiles, settings
└── di/          Koin modules

OwnTV_Core/  (separate repo, published as tv.own.owntv:core / :player-core)
├── core/        database, network, parsers, Stalker, repository, sync, strings
└── player-core/ libmpv + ExoPlayer engines, fallback ladder, watchdogs, diagnostics

OwnTV_Mobile/  (separate repo, the phone and tablet app on the same core)
```

**Related repositories**

- 📱 **[OwnTV Mobile](https://github.com/ahXN00/OwnTV_Mobile)** — the phone and tablet app, built on
  the same core.
- 🧩 **[OwnTV Core](https://github.com/ahXN00/OwnTV_Core)** — the shared engine both apps run on.

## 📚 Docs (`extras/`)

- 📖 **[User Guide](extras/USER_GUIDE.md)** — every feature and where to find it.
- 📄 **[Master Product Brief](extras/OwnTV_Master_Product_Brief.md)** — the full as-built feature and
  architecture reference.

## 📥 Installing (Fire TV / Android TV)

Grab the signed APK from the [**latest release**](https://github.com/ahXN00/OwnTV/releases/latest).
This link always points at the newest build:

```
https://github.com/ahXN00/OwnTV/releases/latest/download/OwnTV.apk
```

- **Fire TV** — install **Downloader** (by AFTVnews), then enter code **`4308278`**. Enable *Apps from
  Unknown Sources* if prompted.
- **Android TV / Google TV** — Downloader is on Google Play too, so the same code works. Or sideload
  with *Send files to TV*, a USB drive, or `adb install OwnTV.apk`.

> Install only from this repository's Releases. Each release ships **two APKs**:
> `OwnTV.apk` (arm — every real TV box, and what the Downloader code fetches) and
> `OwnTV-x86_64-vX.X.X.apk` (emulators and rare Intel boxes).

## 🛠️ Building & running

> Only if you want to build from source — otherwise just
> **[install the APK](#-installing-fire-tv--android-tv)**.

Half the app lives in the separate [OwnTV_Core](https://github.com/ahXN00/OwnTV_Core) repository;
Gradle downloads it from OwnTV's public Maven repository by itself — **no account or token needed**.

1. **Get the code** — `git clone https://github.com/ahXN00/OwnTV.git` (or download the ZIP).
2. **Open it** in [Android Studio](https://developer.android.com/studio) and let Gradle sync.
3. **Pick the build variant** — `standard` for real devices and arm emulators, `x86_64` for x86_64
   emulators. This matters: the native player only loads on a matching ABI.
4. **Run** ▶. Minimum **Android 8.0 / API 26**.

Command line: `./gradlew assembleDebug` (`gradlew.bat` on Windows). The APK lands in
`app/build/outputs/apk/`.

**Tested on** a real TCL Google TV and the Android Studio emulator (Android TV and Google TV images).

## 🤝 Contributing

Contributions, bug reports and ideas are welcome — open an issue or a pull request. Please keep the
project's player-only, bring-your-own-source positioning and match the existing code style.

<!-- i18n-contribution:start -->
## Help translate OwnTV

If your language is already available, contribute interface translations across OwnTV's six Android resource components on [Hosted Weblate](https://hosted.weblate.org/projects/owntv/). If it is not listed, [open a language request ticket](https://github.com/ahXN00/OwnTV/issues/new?template=feature_request.yml&title=%5BLanguage%5D%20Add%20) first. A maintainer will review the request, register the locale, and prepare its base translation files on Hosted Weblate. Once the language appears on Hosted Weblate, you can start translating it there. The strings themselves live in [OwnTV's core library repository](https://github.com/ahXN00/OwnTV_Core), together with the language contributor guide covering identifiers, validation, and promotion policy.
<!-- i18n-contribution:end -->

## 🙏 Credits

<img src="extras/partner-logos/Weblate_logo.svg" alt="Weblate" width="200">

OwnTV speaks 26 languages because people translate it on
[**Weblate**](https://weblate.org/), which hosts the project free of charge for libre software.
Thank you to Weblate and to every translator who has given the app their language.

<img src="extras/partner-logos/tmdb_logo.svg" alt="TMDB" width="200">

Movie & series metadata and trailers are provided by [TMDB](https://www.themoviedb.org/).
**This product uses the TMDB API but is not endorsed or certified by TMDB.**

<img src="extras/partner-logos/opensubtitles_logo.svg" alt="OpenSubtitles" width="200">

Subtitle search & download is powered by [OpenSubtitles.com](https://www.opensubtitles.com/).
**This product uses the OpenSubtitles API but is not endorsed or certified by OpenSubtitles.** You sign
in with your own OpenSubtitles account and are subject to their terms and download quotas.

### 🎨 Brand

The lowercase **owntv** wordmark was designed for OwnTV by [@m3th0d93](https://github.com/m3th0d93)
in [issue #227](https://github.com/ahXN00/OwnTV/issues/227).

### ▶️ Playback engines

- **[mpv](https://mpv.io/)** (via [libmpv](https://github.com/mpv-android/mpv-android), built on
  [FFmpeg](https://ffmpeg.org/)) — the default engine, for the widest IPTV/codec, audio and HDR support.
- **[Media3 / ExoPlayer](https://github.com/androidx/media)** — the fast-start engine for Live TV
  preview and HLS.

Which of the two starts a stream is yours to set, separately for Live TV and for Movies & Series
(Settings → Player): either order, or one engine only with the automatic handover turned off.

### 🧩 Built with

[Jetpack Compose for TV](https://developer.android.com/jetpack/compose) ·
[Room](https://developer.android.com/jetpack/androidx/releases/room) ·
[Koin](https://insert-koin.io/) ·
[OkHttp](https://square.github.io/okhttp/) ·
[Coil](https://coil-kt.github.io/coil/) ·
[ZXing](https://github.com/zxing/zxing) — and the wider Kotlin / AndroidX open-source ecosystem.
Thank you to all their maintainers. See each project for its own license.

## ⚖️ Legal

OwnTV is a media **player** only. It ships with no channels, playlists, subscriptions, or content, and
does not endorse or facilitate access to unauthorized streams. Users are solely responsible for the
sources they add and for complying with the laws and rights that apply to them.

## 📄 License

Released under the **GNU General Public License v3.0 (GPLv3)** — see [LICENSE](LICENSE).

In short: you're free to use, study, modify, and redistribute OwnTV, including commercially — but any
redistributed version (including forks and commercial products built on it) must also be licensed under
GPLv3 and its source made available.

---

<sub>OwnTV is an open-source, player-only project, built with the help of AI.</sub>
