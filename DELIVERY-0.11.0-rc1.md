# 交付说明 · 0.11.0-rc1（versionCode 32）

本轮把「宠物念出回复」做成一条真实可用的离线链路，并把用户提供的两段录音加工成
两个内置音色。相对 0.10.0-rc6（versionCode 31）全部为增量修改，已提交为
`4a520e1 Add offline speech playback and voice packs for 0.11.0-rc1`。

## 1. 朗读回复

| 项目 | 实现 |
|---|---|
| 触发点 | `TtsReader.onSnapshot()` 监听 `ChatSession`，只认 `phase == IDLE && !workerBusy && responseStartIndex != null` 且 generation 变化 |
| 挂载 | 应用内 `ChatScreen`（onStart/onStop）与悬浮窗聊天面板（`attachVoiceMic`）各 `attach(owner)` 一次，owner 计数避免两处互踩 |
| 合成 | ZipVoice distill int8 零样本克隆，`OfflineTts(null, config)` 走绝对路径；`OfflineTtsSpec` 校验五件套体积 |
| 播放 | `AudioTrack.Builder` 按 `GeneratedAudio.sampleRate`（24 kHz）建轨，`MODE_STREAM`、4096 帧分块 |
| 焦点 | `requestAudioFocus(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)`，`AUDIOFOCUS_LOSS*` 立即停播并释放 |
| 文本 | `TtsText` 先剥 Markdown（代码块/图片/链接/强调符），按 `。！？；…` 切句并合并过短句 |
| 失败 | ZipVoice 合成失败返回空数组而非抛异常，`TtsSpeaker` 以 `samples.size < 1600` 判定并跳过该句 |
| 再读 | 悬浮窗回复页新增「再读一遍」按钮（`overlay_reply_speak`），显式点击不受开关限制 |

## 2. 两个内置音色

由用户提供的两段 Inworld 生成录音切出参考片段，`scripts/make-voice-pack.py` 可重跑。

| 包 | 源 | 片段 | 时长 | 采样 | 文件 |
|---|---|---|---|---|---|
| `timbre1` | `…design-voice-ce960942…wav` | 3.38–6.64 s | 3.26 s | 24 kHz / 16-bit / 单声道 / 78,240 帧 | 156,524 B |
| `timbre2` | `…design-voice-178dcf37…wav` | 2.64–5.70 s | 3.06 s | 24 kHz / 16-bit / 单声道 / 73,440 帧 | 146,924 B |

两段参考文本均为「我才没有一直等你呢，只是刚好看了一眼时间而已」（与音频逐字一致，
含全角标点），`speed 1.0` / `numSteps 4`。基频实测：音色1 中位约 149 Hz，音色2 约 164 Hz。

放在 `app/src/main/assets/voice-packs/`，随 APK 分发，共约 300 KB。

## 3. 语音包导入

独立于角色包格式（`figure.json` 是外观，`voice.json` 是音色）。

```json
{"format":1,"id":"myvoice","name":"我自己","kind":"zipvoice",
 "reference":"reference.wav","referenceText":"……","sampleRate":24000,
 "speed":1.0,"numSteps":4}
```

- 约束：id 匹配 `^[a-z0-9][a-z0-9-]{0,31}$`，不得占用内置 id 或保留 id；参考音频 ≤ 10 s、
  zip 解压后 ≤ 16 MB、条目 ≤ 64、最多 20 个导入包。
- 落盘：`files/voice-packs/<id>/{voice.json,reference.wav}`，写入走 `.tmp-<id>-<nanoTime>` 再 rename。
- 入口：设置 →「朗读回复」→「导入语音包」，走独立 `OpenDocument()` 启动器，不复用角色包选择器。
- 失败文案已改为语音包自己的四条（此前误借角色包的「这个角色包使用了保留编号」）。

## 4. 合成引擎按需下载

- 官方只发 `.tar.bz2`，Android 无 bzip2 解码器，故把解包好的五个文件托管到本仓库
  `tts-models` 预发布标签（<https://github.com/WWK-HUB/bluefin-android/releases/tag/tts-models>）。
- 五件套共 186,114,437 B（177.5 MiB）：`decoder.int8.onnx` 124,657,100 / `vocos_24khz.onnx`
  54,157,409 / `encoder.int8.onnx` 5,570,211 / `lexicon.txt` 1,727,147 / `tokens.txt` 2,570。
- 双源顺序：`gh-proxy.com` 镜像优先，GitHub 直连兜底（实测镜像首跳 200）。
- 下载走 staging 目录 + `.part` + `fd.sync()` + 最小体积校验 + rename，与 ASR 模型同一套形状。
- **APK 不因此变大**：arm64 交付包 62,779,195 B（59.9 MiB），与 0.10.0-rc5 的 62,268,289 B 基本持平。

## 5. 文案、许可与版本

- `about_privacy` 删去「不含语音合成」，改为说明朗读在本机离线合成、文字不上传、
  内置音色来自用户自己的录音参考。
- `about_licenses` 补上 ZipVoice / Vocos 与 ONNX Runtime 的许可说明。
- `THIRD_PARTY_NOTICES.md` 新增合成段，写明参考录音是 voice prompt 而非模型权重。
- `versionCode 32` / `versionName "0.11.0-rc1"`；`ReleaseIdentityTest` 与 `OverlayLayoutTest`
  两处版本锁同步。

## 6. 验收记录

### 自动

- 单测 **497 例，0 失败 0 错误**（0.10.0 基线 468 例，本轮新增 29 例：
  `TtsTextTest`、`VoicePackFormatTest`、`VoicePackRepositoryTest` 等）。
- 契约测试未破：`OverlayOpeningBaselineContractTest` 的九个 sha256 锁、
  `PersonaRulesTest` 的人设隔离区间、`AppVersionTest` 的 `BuildConfig.VERSION_NAME` 禁令、
  `NoBuiltInCredentialTest` 的密钥扫描全部通过。

### 模拟器（BluefinReview，x86_64，1080×2400）

| 项 | 结果 |
|---|---|
| 设置页「朗读回复」段 | 正确显示「语音引擎：已就绪」「当前音色：音色1」「版本 0.11.0-rc1」 |
| 引擎侧载识别 | 五个文件推进 `files/tts-models/…` 后状态立即从「未下载」变「已就绪」 |
| 回复自动朗读 | logcat 出现 `OfflineTtsConfig(…zipvoice=…)` 后紧跟 `AudioTrack: stop(16): called with 27599 frames delivered`、`stop(17): … 26353 frames`，0 FATAL |
| 音色2 | 复测同样两句成播（25,362 / 25,182 帧），无 sherpa 报错 |
| 切换音色 | prefs 的 `voice_pack_id` 由 `timbre1` 变 `timbre2` |
| 语音包导入（正向） | `myvoice.zip` → Toast「当前音色：我自己」，落盘 146,924 + 311 B，写 `voice_pack_id=myvoice`、`voice_pack_revision_myvoice=1` |
| 语音包导入（反向） | 占用内置 id 的包被拒，提示「这个语音包使用了内置音色的编号，换个编号再试。」 |

验收用的 mock 服务与脚本留在 `D:\xiaoshen\android-avd\`（`mock-chat-server.py`、
`seed-endpoint.sh`、`push-tts-engine.sh`、`launch-emu.sh`），不属于仓库。

## 待验收（本轮不具备条件）

1. **真机朗读音质**：模拟器只有软件音频，实际听感、外放音量、蓝牙/耳机切换需真机。
2. **真机内存**：引擎常驻约 180 MB 权重 + ONNX Runtime 会话，低端机（4 GB 以下）待压测。
3. **真实通话打断**：只验证了焦点回调路径，未验证来电、其他应用播放音乐时的让出行为。
4. **大陆网络首次下载**：`gh-proxy.com` 可达性随运营商波动，断流重试与 178 MB 续传未做真机长测。
5. **导入包的十秒上界**：只验证了 3 秒级参考，10 s 参考触发的
   `num_frames <= prompt_feat_len` 退化在 UI 上只表现为「跳过该句」，用户不可见。

## 复现要点

```bash
# 单测 + 打包（arm64 交付包）
cd /d/xiaoshen/bluefin-android
./gradlew testDebugUnitTest assembleDebug --console=plain

# 重新切参考音频（需要两段原始 wav 在 D:/ 下）
python scripts/make-voice-pack.py --source-dir "D:/" --out app/src/main/assets/voice-packs

# 模拟器包（含 x86_64，可跑 TTS）
./gradlew assembleDebug -PvoiceX86=true
```

构建机注意：`repo.maven.apache.org` 直连会被重置，本机靠
`~/.gradle/init.d/aliyun-mirror.gradle` 追加阿里云镜像才能完成依赖解析。

## 签名

仍沿用上轮同一条调试签名链（证书 SHA-256
`572ea4e5d924a9bc99e7e4bc1e0733ab4cce36cf906e7273be2d3aaa6cf98c6c`，DN `CN=Android Debug`），
可覆盖升级 0.10.0-rc6。**正式发布前必须换 release 密钥，且换钥后无法覆盖升级**，
与 0.10.0-rc2 记录的是同一个缺口。

## 产物

| 文件 | 大小 | SHA-256 | ABI |
|---|---|---|---|
| `app/build/delivery/bluefin-0.11.0-rc1-arm64.apk` | 62,779,195 B | `7becaff49e530f25da8a6a988dd4753c3dd9b9104118416541348153241632a0` | arm64-v8a |
| `app/build/outputs/apk/debug/app-debug.apk`（含 x86_64，验收用） | 99,715,213 B | — | arm64-v8a + x86_64 |
