# 交接文档 · 鳍遇 0.11.0-rc1（朗读回复 + 语音包）

> 写给接手的同学。上一轮（0.10.0）与本轮（0.11.0）的功能都已落地并提交，
> **但「真机上回复没有语音」的修复还在工作区未提交**，这是第一优先事项。
> 文档最后更新：2026-11-18 10:15（Asia/Shanghai）。

---

## 0. 一句话现状

朗读链路在**应用内聊天页**已跑通并有 logcat 证据；**悬浮窗/气泡发送路径**的挂载缺失
已改好并通过编译与单测，但**还没有在设备上复验过**，也没有提交。此外 3 个 commit
没推到 GitHub，`update-channel` 更新通道仍停在 0.10.0-rc6（versionCode 31）。

---

## 1. 仓库与提交状态

```text
仓库     D:\xiaoshen\bluefin-android
远端     https://github.com/WWK-HUB/bluefin-android.git   (origin)
本地分支 main  ahead of origin/main 3 commits
```

| commit | 说明 |
|---|---|
| `968a974` | Harden overlay and screen capture, rebrand to 鳍遇 for 0.9.1-rc1 |
| `4a520e1` | Add offline speech playback and voice packs for 0.11.0-rc1（147 files, +7322/−688） |
| `266f2d5` | Document the 0.11.0-rc1 delivery |

远端仍停在 `67abf6b Document APK downloads and Bluefin 0.9.0 setup`。

**工作区有 3 个文件未提交**（就是本轮 bug 修复，详见第 4 节）：

```text
 M app/src/main/java/com/wwkhub/bluefin/OverlayWindow.kt            +13 −2
 M app/src/main/java/com/wwkhub/bluefin/SpeechSettingsSection.kt    +12 −1
 M app/src/main/java/com/wwkhub/bluefin/voice/tts/TtsReader.kt      +22 −2
```

> 注意：`SpeechSettingsSection.kt` 的换行是 CRLF，git 会提示
> `CRLF will be replaced by LF`，属正常，不要手改行尾。

### 推送不了

`git push origin main` 目前**推不上去**，不是仓库问题，是这台机器的代理链路问题：

- `~/.gitconfig` 里 `http.proxy` / `https.proxy` 都是 `http://127.0.0.1:7897`；
- 代理关着时该端口连不上；开着时 `curl -sv` 显示 CONNECT 隧道 200 建立，
  但紧接着 `schannel: failed to receive handshake, SSL/TLS connection failed`，
  或 `error: RPC failed; curl 55 Send failure: Connection was reset`。
- 直连（`git -c http.proxy= push`）被 reset；`git -c http.sslBackend=openssl`
  无效（该 git 构建没有 openssl 后端，警告后回退 schannel）；
  扫 7890/7891/7892/7899/1080/10808/10809/8080/2080 全不可用；`~/.ssh` 无密钥。
- **但 `gh` CLI 走 API 是完全通的**（`gh api repos/WWK-HUB/bluefin-android/commits/main`
  能正常返回）。所以可选路径：①换一个能用的代理再 push；②用 `gh` 逐文件/逐批上传
  （麻烦但可行）；③直接在网页上传 patch。

---

## 2. 本轮做了什么（0.11.0-rc1）

用户需求原话：「先完善 app 的语音包相关功能，包括语音包导入，宠物读文本，
用户可选下载 tts 等等，然后处理我给你的两段音频，让他们能够做成两个语音包，
可以简单命名为音色1，音色2，最后内置到 app 中」

| 需求 | 实现情况 |
|---|---|
| 朗读回复 | 完成。`TtsReader` 监听 `ChatSession`，回复完成即读 |
| 语音包导入 | 完成。独立 `voice.json` 格式，设置页「导入语音包」 |
| 可选下载 TTS | 完成。默认不下载，首次开启时确认后下载 178 MB 引擎 |
| 内置音色1/音色2 | 完成。两段 Inworld 录音各切 3 秒参考，随 APK 分发约 300 KB |

版本：`versionCode = 32`、`versionName = "0.11.0-rc1"`（`app/build.gradle.kts:16-17`）。

### 两个内置音色的来源参数

| 包 | 源文件 | 片段 | 时长 | 文件 |
|---|---|---|---|---|
| `timbre1` | `D:\inworld-tts-2_grand-puffin-3495__design-voice-ce960942_11-17-2026 16-37-19.wav` | 3.38–6.64 s | 3.26 s | 156,524 B |
| `timbre2` | `D:\inworld-tts-2_grand-puffin-3495__design-voice-178dcf37_11-17-2026 16-28-38.wav` | 2.64–5.70 s | 3.06 s | 146,924 B |

两段参考文本都是「我才没有一直等你呢，只是刚好看了一眼时间而已」，24 kHz / 16-bit /
单声道。**参考文本必须与音频逐字一致（含全角标点与省略号）**，改动会明显影响克隆质量。

重新切片的脚本：`scripts/make-voice-pack.py`

```bash
python scripts/make-voice-pack.py --source-dir "D:/" --out app/src/main/assets/voice-packs
```

---

## 3. 新增/改动的关键文件

### 新增（0.11.0）

```text
app/src/main/java/com/wwkhub/bluefin/
├── VoicePackFormat.kt              语音包清单解析与校验（voice.json）
├── VoicePackRepository.kt          语音包仓库：内置/导入/落盘/zip 分析
├── SpeechSettingsSection.kt        设置页「朗读回复」段
└── voice/tts/
    ├── OfflineTtsSpec.kt           引擎五件套规格 + 下载 URL + 体积校验
    ├── TtsModelManager.kt          引擎下载安装（staging/.part/rename）
    ├── TtsEngineHolder.kt          OfflineTts 单例 + 全局锁
    ├── TtsSpeaker.kt               切句合成 + AudioTrack 播放 + 音频焦点
    ├── TtsReader.kt                ChatSession 监听器 + 说话去重
    ├── TtsText.kt                  剥 Markdown、切句、可朗读字符统计
    └── WavReader.kt                WAV 解析/重采样（读参考音频）

app/src/main/assets/voice-packs/
├── timbre1/{voice.json,reference.wav}
└── timbre2/{voice.json,reference.wav}

app/src/main/res/layout/section_speech_settings.xml
scripts/make-voice-pack.py
DELIVERY-0.11.0-rc1.md
```

### 改动的既有文件

| 文件 | 改了什么 |
|---|---|
| `OverlayWindow.kt` | 挂 `TtsReader`、回复页加「再读一遍」按钮、`collapseOrb` 不再 detach |
| `MainActivity.kt` | 装配 `SpeechSettingsSection`、引擎下载对话框、语音包导入、偏好监听 |
| `ChatScreen.kt` | `onStart`/`onStop` 挂载与释放 `TtsReader` |
| `BluefinStore.kt` | 新增 `KEY_SPEECH_ON`、`KEY_VOICE_PACK_ID` 及读写方法 |
| `sheet_settings.xml` | 新增 `speech_section` 插槽 |
| `overlay_orb.xml` | 新增 `overlay_reply_speak` 按钮 |
| `values/voice_input.xml` | 新增约 30 条 `speech_*` 文案 |
| `strings.xml` | `about_privacy` 删去「不含语音合成」、`about_licenses` 补许可 |
| `THIRD_PARTY_NOTICES.md` | 新增语音合成段 |
| `README.md` | 功能列表、更新历史、使用方法 |

---

## 4. 【最重要】未提交的 bug 修复

### 用户报告（原话）

> 「真机实测发现，安装好引擎后与宠物对话，宠物回复文字后过了很久也没有回复语音」

### 根因（三个叠加）

能产生回复的路径有四条，但**只有一条会挂朗读器**：

| 路径 | 入口 | 是否挂 `TtsReader`（修复前） |
|---|---|---|
| 应用内聊天页 | `ChatScreen.onStart` | ✅ 有 |
| 悬浮窗聊天面板 | `openChatPanel()` → `attachVoiceMic()` | ✅ 有 |
| 气泡输入 | `onBubbleInputSendRequested` → `sendSpeechInput()` | ❌ **没有** |
| 语音识别文本 | `routeVoiceText()` → `sendFromOverlay()` | ❌ **没有** |

**A（主因）**：气泡和语音输入两条路径都不挂朗读器，所以回复回来时
`ChatSession` 的快照回调根本到不了 `TtsReader`，永远没有声音。

**B**：`collapseOrb()` 无条件 `TtsReader.peek()?.detach(this)`。用户发完消息
把面板收起去看宠物，owner 清空 → 监听器被移除 → 回复回来时没人听。

**C（潜在）**：`ChatSession.addListener` 注册时会**立即同步回调一次当前快照**。
如果发送前挂载，会把历史里上一条早就完成的回复当本轮读出来。

### 已改的三处（在工作区，未提交）

**1) `OverlayWindow.kt` `collapseOrb()`（约 1286 行）** —— 删掉 detach：

```kotlin
        // Speech output deliberately outlives the panel: the reply usually
        // finishes after the user collapsed it to watch the pet, so detaching
        // here would silence every answer sent from the overlay. The reader is
        // released on window destroy instead.
```

**2) `OverlayWindow.kt` `sendFromOverlay()`（约 1658 行）** —— 发送时挂载：

```kotlin
        // Any overlay send can produce a reply worth reading, so speech is
        // attached here rather than only on the mic/panel path: the bubble and
        // voice-input routes never pass through attachVoiceMic().
        TtsReader.shared(appContext).attach(this)
```

**3) `OverlayWindow.kt` `sendSpeechInput()`（约 1544 行）** —— 同上，在 blank 检查之前：

```kotlin
        // Speech input is a send path too: attach before the request starts so
        // the reply that this very message produces is read aloud.
        TtsReader.shared(appContext).attach(this)
```

**4) `TtsReader.kt` —— baseline 去重（修 C）**：新增两个字段

```kotlin
    private var baselineGeneration = -1
    private var baselineIndex = -1
```

`attach()` 里在 `addListener` 之前记基线：

```kotlin
        val current = active.snapshot()
        baselineGeneration = current.requestGeneration
        baselineIndex = current.responseStartIndex ?: -1
```

`onSnapshot()` 里多一道判断：

```kotlin
        if (generation == baselineGeneration && index == baselineIndex) return
```

`detach()` 里四个字段全部重置为 `-1`。

> ⚠️ **别改回 `armed` 标志的方案**。试过「只在观察到 `workerBusy` 时 arm」，
> 失败原因是 `ChatSession.notifyListeners()` 用 `handler.post` + `notifyScheduled`
> 去重，快照会合并；很快的一轮可能一帧 busy 都发不出去，arm 永不置位 → 不朗读。
> 现在用的是「快照身份」而不是「armed 状态」。

**5) `SpeechSettingsSection.kt` —— 下载完自动开开关**：点「下载语音引擎」本身就是
要听到声音，原先装完引擎开关仍是关的，现象与「引擎坏了」无法区分。

```kotlin
        engineAction.setOnClickListener {
            activity.promptSpeechEngineDownload {
                if (!BluefinStore.readSpeechOn(prefs)) {
                    BluefinStore.writeSpeechOn(prefs, true)
                    activity.onSpeechPreferenceChanged()
                }
                refresh()
            }
        }
```

### 修复的验证状态

| 项 | 状态 |
|---|---|
| `compileDebugKotlin -PvoiceX86=true` | BUILD SUCCESSFUL |
| `testDebugUnitTest -PvoiceX86=true` | **497 例 0 失败 0 错误** |
| `assembleDebug -PvoiceX86=true` | BUILD SUCCESSFUL，98,498,533 B |
| 应用内聊天页朗读 | ✅ logcat 有 `AudioTrack: stop(16): 26457 frames` / `stop(17): 29157 frames` |
| 悬浮窗/气泡路径朗读 | ❌ **还没验过**，这是要接手的活 |

---

## 5. 【注意】已交付给用户的 APK 是旧包

| 文件 | 时间 | 是否含修复 |
|---|---|---|
| `D:\xiaoshen\bluefin-0.11.0-rc1-arm64.apk` | 11-17 23:38 | ❌ **不含** |
| `app/build/delivery/bluefin-0.11.0-rc1-arm64.apk` | 11-17 23:16 | ❌ **不含** |
| `app/build/outputs/apk/debug/app-debug.apk` | 11-18 01:08 | ✅ 含（x86_64 验收包） |

用户真机测的就是那个**不含修复**的包，所以「没有语音」的现象是预期内的。
重新出包前先跑第 6 节的命令，并且**记得把 `versionCode` 从 32 提到 33**，
否则应用内「检查更新」会判定为「已是最新」。

---

## 6. 构建 / 测试 / 安装

### 构建机必做的环境准备（不做连配置阶段都过不去）

`repo.maven.apache.org` 直连会被 reset。本机靠一个 Gradle init 脚本加阿里云镜像：

`C:/Users/xiaoye/.gradle/init.d/aliyun-mirror.gradle`

```groovy
beforeSettings { settings ->
    settings.pluginManagement.repositories {
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/central' }
        gradlePluginPortal()
    }
}
// 另需对 dependencyResolutionManagement.repositories 追加同样的镜像
```

> 手工往 `~/.gradle/caches/modules-2/files-2.1/` 塞 jar **没用**，离线模式仍报缺元数据。

### 命令

```bash
cd /d/xiaoshen/bluefin-android

# 编译
./gradlew compileDebugKotlin -PvoiceX86=true --console=plain

# 单测（应看到 497 例 0 失败）
./gradlew testDebugUnitTest -PvoiceX86=true --console=plain

# 验收包（含 x86_64，模拟器能跑 TTS）
./gradlew assembleDebug -PvoiceX86=true --console=plain
# → app/build/outputs/apk/debug/app-debug.apk

# 交付包（只含 arm64，约 60 MiB）
./gradlew assembleRelease   # 或按 libs/README 里的 delivery 流程
```

> **不要用 `timeout 500 ./gradlew ...`** —— 本机 `timeout` 包 gradlew 会返回
> exit 126，看起来像构建失败，其实是 timeout 的问题。

### 设备

```bash
ADB="C:/Users/xiaoye/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" install -r -d app/build/outputs/apk/debug/app-debug.apk
```

---

## 7. 复现端到端朗读（模拟器）

素材都在 `D:\xiaoshen\android-avd\`，**不属于仓库**，但可以直接用。

### 7.1 启动模拟器

```bash
# D:/xiaoshen/android-avd/launch-emu.sh 里已经写好
export ANDROID_AVD_HOME='D:\xiaoshen\android-avd'
exec "C:/Users/xiaoye/AppData/Local/Android/Sdk/emulator/emulator.exe" \
  -avd BluefinReview -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect
```

> **`ANDROID_AVD_HOME` 必须用 Windows 反斜杠路径**（`D:\xiaoshen\android-avd`）。
> 传 MSYS 风格的 `/d/xiaoshen/android-avd` 会报 `Unknown AVD name [BluefinReview]`。
> 用 `ops_bash script_path=` 启动，约 55 s 后 `adb devices` 出现 `emulator-5554`。

### 7.2 侧载引擎（省掉 178 MB 下载）

```bash
bash D:/xiaoshen/android-avd/push-tts-engine.sh
```

把五个文件推进 `files/tts-models/zipvoice-distill-int8-zh-en-emilia/`，
设置页「语音引擎」会立刻从「未下载」变「已就绪」。
引擎文件在 `D:\xiaoshen\.voice-verify\zv\sherpa-onnx-zipvoice-distill-int8-zh-en-emilia\`
与 `D:\xiaoshen\.voice-verify\vocoder\vocos_24khz.onnx`。

### 7.3 起 mock 服务并配端点

```bash
python D:/xiaoshen/android-avd/mock-chat-server.py     # 绑 0.0.0.0:8787
bash   D:/xiaoshen/android-avd/seed-endpoint.sh        # 写 endpoint + key 到 prefs
bash   D:/xiaoshen/android-avd/seed-speech.sh          # speech_on=true, voice_pack_id=timbre1
```

mock 固定回复「主人，你终于回来啦！我才没有一直等你呢。」（两句，方便看两条 AudioTrack）。

### 7.4 验应用内路径（已通过）

```bash
ADB="C:/Users/xiaoye/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" logcat -c
# 首页顶部「对话」按钮
"$ADB" shell input tap 510 168
"$ADB" shell input tap 370 2280     # 输入框
"$ADB" shell input text "test"
"$ADB" shell input keyevent 4       # 收键盘
"$ADB" shell input tap 943 2257     # 发送（键盘收起时）
"$ADB" logcat -d | grep -E "AudioTrack: stop|OfflineTtsConfig|FATAL"
```

期望看到 `OfflineTtsConfig(...zipvoice=...)` 后紧跟两行
`AudioTrack: stop(N): called with ~26000 frames delivered`。

**坐标坑**：发送按钮在键盘弹出时是 `943 1540`，收起时是 `943 2257`；点错就毫无反应。

### 7.5 验悬浮窗/气泡路径（**待做**）

```bash
"$ADB" shell appops set com.wwkhub.bluefin SYSTEM_ALERT_WINDOW allow
# 应用内首页右上角悬浮窗按钮
"$ADB" shell input tap 820 112
# 回到桌面后：球在 820 538，宠物在 755 1360
```

上一轮卡在这里：点球/点宠物打开面板**很不稳定**（一次点球没反应，
点 `640 1330` 反而让宠物睡了，面板没出来）。建议接手时：

1. 先确认 `shared_prefs/bluefin.xml` 里 `overlay=true`、`overlay_on=true`；
2. 用 `uiautomator dump` 拿到真实控件坐标再点，别靠截图猜；
3. 或者干脆从**气泡输入**入手（设置里开气泡预览，直接打字发送），
   这条路径同样覆盖本次修复的 `sendSpeechInput`。

`am force-stop` 之后悬浮窗有时不自动恢复，要回应用内再点一次悬浮窗按钮。

---

## 8. 其余待办（按优先级）

### P0 — 把修复验完并出包

1. 在模拟器上复验悬浮窗/气泡路径的朗读（第 7.5 节）。
2. `versionCode` 32 → 33（`app/build.gradle.kts:16`），
   同步 `ReleaseIdentityTest.kt:27-28` 与 `OverlayLayoutTest.kt:287-288` 两处版本锁。
3. 重新出 arm64 交付包，重装到真机确认有声音。
4. 提交这 3 个改动文件。

### P1 — 收口

5. 把 3 个 commit 推到 GitHub（第 1 节的三条路径任选）。
6. 更新 `update-channel`：上传新 APK + 重写 `update-manifest.json`
   （`scripts/make-update-manifest.py` 在仓库里）。
   现在通道里是 `bluefin-0.10.0-rc3.apk` / `bluefin-0.10.0-rc6.apk` / 333 B 清单。
   注意：`update-channel` 是 **Pre-release，勿手动安装**。

### P2 — 已记录但本轮没条件做的

7. **真机朗读音质**：模拟器只有软件音频，外放音量、蓝牙/耳机切换要真机听。
8. **真机内存**：引擎常驻约 180 MB 权重 + ONNX Runtime，4 GB 以下机型待压测。
9. **真实通话打断**：只验了焦点回调路径，未验来电让出。
10. **大陆首次下载**：`gh-proxy.com` 可达性随运营商波动，178 MB 断点续传未做长测。
11. **导入包 10 秒上界**：10 s 参考会触发 `num_frames <= prompt_feat_len` 退化，
    当前在 UI 上只表现为「跳过该句」，用户看不见原因，需要给提示。
12. **app 内录参考音频**：本轮故意没做，留给下一轮。
13. **正式签名**：仍用 `CN=Android Debug`（证书 SHA-256
    `572ea4e5d924a9bc99e7e4bc1e0733ab4cce36cf906e7273be2d3aaa6cf98c6c`）。
    换 release 密钥后**无法覆盖升级**，这个缺口从 0.10.0-rc2 就记着。

---

## 9. 源码里必须知道的约束

### 不能碰的方法体（sha256 契约锁）

`app/src/test/java/com/wwkhub/bluefin/OverlayOpeningBaselineContractTest.kt` 锁死了：

- `OverlayWindow` 的 `show()`、`layoutOrb()`、`screenSize()`、`setupOrb()`、
  `inflateRoot()`、`fullscreenParams(`、`stageAlphaForCurrentDevice()`、`applyStageSize()`
- `OverlayLauncher.launch(`
- `MainActivity.onResume()`

**这些方法体一个字都不能改。** 新逻辑要放新方法（例如 `attachTts()`），
从 `showOrbPage` / `openReplyReader` / `ChatScreen.onStart` 这类非锁定路径调用。

`collapseOrb()` **不在**锁定范围（`BubblePreviewStageCContractTest` 只断言文本里
出现了 `collapseOrb()` 字样），所以本轮能改它。

### 其它测试红线

- `PersonaRulesTest`：`clearHistory()` 到 `private fun runRequest(` 之间的正文
  不得出现 `PersonaRepository` / `PersonaText`。
- `AppVersionTest`：`src/main` 全树 kt/java 禁写 `BuildConfig.VERSION_NAME`；
  `MainActivity.kt` 必须保留 `packageManager.getPackageInfo(packageName, 0).versionName`。
- `NoBuiltInCredentialTest`：扫 `kt/java/xml/json/js/md/html/properties`，
  禁 `sk-[A-Za-z0-9_-]{16,}` 与 `Bearer\s+[A-Za-z0-9_\-.]{16,}`。
- `ReleaseIdentityTest`：`app_name` 必须恰好「鳍遇」，`strings.xml` 不得含
  「蓝鲗/蓝鳐/鳐/鱚/鳞/蓝鳞」；`about_privacy` 要含「朗读回复同样在本机离线合成」
  与「语音输入在本机离线识别」。
- `BubblePreviewIsolationContractTest`：`sheet_settings.xml` 里
  `bubble_preview_switch` 必须恰好 1 处；`MainActivity` 不得出现
  `BubblePreviewController(` / `BubblePreviewWindow(`。

### sherpa-onnx Kotlin 签名（AAR 2.0.1，全是位置参数，不能命名）

```kotlin
// 7 个模型配置参数都是非空类型，没用的必须无参构造，不能传 null
OfflineTtsModelConfig(
    OfflineTtsVitsModelConfig(),
    OfflineTtsMatchaModelConfig(),
    OfflineTtsKokoroModelConfig(),
    zipvoice,                              // 只有这个填实
    OfflineTtsKittenModelConfig(),
    OfflineTtsPocketModelConfig(),
    OfflineTtsSupertonicModelConfig(),
    2, false, "cpu",
)

// (tokens, encoder, decoder, vocoder, dataDir, lexicon, featScale, tShift, targetRms, guidanceScale)
OfflineTtsZipVoiceModelConfig(tokens, encoder, decoder, vocoder, "", lexicon, 0.1f, 0.5f, 0.1f, 1.0f)

// (model, ruleFsts, ruleFars, maxNumSentences, silenceScale)
OfflineTtsConfig(modelConfig, "", "", 1, 0.2f)

// OfflineTts(null, config) —— AssetManager 必须传 null 才走 newFromFile（绝对路径）
val engine = OfflineTts(null, config)

// (silenceScale, speed, sid, referenceAudio, referenceSampleRate, referenceText, numSteps)
GenerationConfig(0.2f, pack.speed, 0, reference, pack.sampleRate, pack.referenceText, pack.numSteps)
```

`data_dir` 自 sherpa-onnx 2.0.0 起**已废弃并被忽略**，中文必须靠 `lexicon.txt`：
不带 lexicon 也能合成，但同一句会从 1.13 s 变成 3.86 s，口音全错。

### ZipVoice 的失败是静默的

合成失败**返回空数组，不抛异常**。`TtsSpeaker` 用 `samples.size < 1600` 判定并跳过该句。
另外参考音频越长，短回复越容易撞 frames 下限（此时也是空输出）：

```text
num_frames (%d) <= prompt_feat_len (%d) with speed %.3f.
The text is too short for this speed. Please use a smaller speed or a shorter reference audio and text.
```

所以内置参考音频都控制在 3 秒左右，不要为了「更像」去换 10 秒的。

---

## 10. 引擎模型分发

官方只发 `.tar.bz2`，Android 没有 bzip2 解码器，所以**把解包好的五个文件
托管到本仓库的 `tts-models` 预发布标签**：

<https://github.com/WWK-HUB/bluefin-android/releases/tag/tts-models>

| 文件 | 字节 |
|---|---|
| `decoder.int8.onnx` | 124,657,100 |
| `vocos_24khz.onnx` | 54,157,409 |
| `encoder.int8.onnx` | 5,570,211 |
| `lexicon.txt` | 1,727,147 |
| `tokens.txt` | 2,570 |
| **合计** | **186,114,437（177.5 MiB）** |

下载双源顺序（`OfflineTtsSpec.urlsFor`）：`gh-proxy.com` 镜像优先，GitHub 直连兜底。
**不要照抄 ASR 的 hf-mirror 双源策略** —— `hf-mirror.com` 与 `modelscope.cn`
对这个模型全是 404，实测只有 GitHub 一条源。

`hifigan_v1/v2/v3` 三个声码器**全部加载失败**，24 kHz ZipVoice 只能配 `vocos_24khz.onnx`
（51.65 MiB），不要再试小的那个。

---

## 11. 杂项环境坑（踩过的，省得再踩）

- `D:/bin/javap.exe` 可读 AAR 签名；类已解在
  `C:/Users/xiaoye/AppData/Local/Temp/bfaar/cls`。
- `.voice-verify/venv` 里只有 `sherpa_onnx 2.0.1`，**没有 numpy / onnx / onnxruntime**，
  数值处理全靠 `array` 模块手写。
- 在 `/d/xiaoshen` 下直接跑 `.voice-verify/venv/Scripts/python.exe` 会
  **Permission denied（exit 126）**；必须先 `cd /d/xiaoshen/.voice-verify` 再用 `./venv/...`。
- `python -c` 里写 `/d/...` 路径会失败，用 `D:/...` 正斜杠。
- Python 3.13.2 在 PATH，直接 `python` 即可。
- `ops_bash` 偶尔抛 `EXECUTOR_UNAVAILABLE ... link count mismatch`，是瞬时的，重试即过；
  复杂命令写进 `.sh` 用 `script_path` 跑更稳。
- 在 `command` 里用 `cat > file <<EOF` 会**静默不落盘**，建脚本要用写文件工具。
- `adb devices` 无设备时会**无限挂住**，要放后台再 kill。
- `gh release create` 必须在 git 仓库目录里跑（否则 `fatal: not a git repository`）。
- `gh` 已登录 `WWK-HUB`，scope 含 `repo` / `workflow` / `delete:packages`。

---

## 12. 相关文档

| 文件 | 内容 |
|---|---|
| `DELIVERY-0.11.0-rc1.md` | 本轮完整交付说明（功能表、验收记录、复现命令） |
| `DELIVERY-0.10.0-rc2.md` | 上一轮交付说明（动作表情、界面精简、应用内更新） |
| `README.md` | 用户向说明，含更新历史与「朗读回复」使用方法 |
| `THIRD_PARTY_NOTICES.md` | 第三方许可，含 Coopanion AGPL 与 sherpa-onnx Apache 2.0 |
| `悬浮窗验收清单.md` | 悬浮窗逐项验收矩阵 |

---

## 13. 接手第一件事清单

- [ ] `git log --oneline -4` 确认三个 commit 在，`git status` 确认 3 个未提交文件在
- [ ] 配好 `~/.gradle/init.d/aliyun-mirror.gradle`，跑一次 `testDebugUnitTest` 确认 497 例全绿
- [ ] 按第 7 节起模拟器 + 侧载引擎 + mock 服务
- [ ] 验应用内路径（应该已经有声音，用来确认环境本身没坏）
- [ ] **验悬浮窗/气泡路径**（本次修复的目标，重点用 `uiautomator dump` 定坐标）
- [ ] 提 `versionCode` 到 33，同步两处版本锁，出 arm64 包
- [ ] 真机确认有声音
- [ ] 提交 + 推送（推送需要解决代理问题）
- [ ] 更新 `update-channel`
