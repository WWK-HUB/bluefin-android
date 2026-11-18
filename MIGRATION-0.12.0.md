# 0.12.0-rc1 语音迁移报告：云端优先 + ZipVoice 按需备用

日期：2026-11-18 · 版本：0.12.0-rc1 (versionCode 34) · 包名：com.wwkhub.bluefin

## 1. 架构总览

```
┌────────────────────────── Android App（不含任何供应商密钥）─────────────────────────┐
│  ChatSession ──情绪标记解析──▶ TtsReader ──▶ TtsProviderManager（状态机）            │
│                                              │                                      │
│                    CLOUD_ACTIVE（默认）       │       LOCAL_FALLBACK（按需）         │
│                          ▼                   │              ▼                       │
│              CloudTtsSpeaker                 │     TtsSpeaker (ZipVoice)            │
│   句子级合成·预取·双MediaPlayer·磁盘缓存       │   单句调用·空结果自愈·本地参考音色     │
│                          │                   │              ▲                       │
│              CloudTtsClient                  │     TtsModelManager（暂停/续传）      │
│              CloudIdentity（设备令牌）        │     LocalEngineVerifier（推理验证）    │
└──────────────────────────┼───────────────────┴──────────────────────────────────────┘
                           │ HTTPS + 短期设备令牌（7天，可轮换/吊销）
                           ▼
┌─────────────── Cloudflare Worker（cloud-tts-worker/）───────────────┐
│  认证·限流·每日额度·并发·音色隔离·错误归一化(8类category)·恢复探测      │
│  FISH_API_KEY / TOKEN_SIGNING_KEY / ADMIN_TOKEN 仅在 Worker Secret     │
└──────────────────────────┼────────────────────────────────────────────┘
                           │ model: s2.1-pro-free（硬编码，绝不省略/改写）
                           ▼
                 https://api.fish.audio/v1/tts
```

核心原则：
- **云端优先**：默认 Fish Audio `s2.1-pro-free`（与付费版同模型同质量，$0）。
- **永不付费回退**：Worker 硬编码免费模型头；上游 402 → `MODEL_UNAVAILABLE` 并停止。
  Fish Audio 在缺失 model 头时静默回退付费 `s2.1-pro`——本实现任何路径都不会省略该头。
- **ZipVoice 保留为按需备用**：默认不下载、不内置、不打扰；仅在云端持续不可用且用户明确同意后下载。
- **密钥零客户端暴露**：APK 内只有 Worker 地址（普通配置）与设备会话令牌（可吊销），无 Fish Key。

## 2. 故障分类与切换策略（已验证）

服务端按真实供应商响应归一化，客户端不猜 HTTP 码：

| category | 来源 | 客户端行为 |
|---|---|---|
| TEMPORARY_NETWORK_ERROR | 网络/超时 | 退避重试≤2次，不弹窗 |
| RATE_LIMITED | 429/限流 | 退避重试（尊重 Retry-After），不弹窗 |
| SERVICE_UNAVAILABLE | 上游5xx | 退避重试，不弹窗 |
| QUOTA_EXHAUSTED | Worker每日额度 | 确认性→立即走备用决策 |
| MODEL_UNAVAILABLE | 上游402（免费模型不可用） | 确认性→立即走备用决策，**不尝试付费模型** |
| AUTH_ERROR | 设备令牌401→自动重注册一次；服务端密钥问题→持续 | 持续失败计入streak |
| INVALID_VOICE | 音色无效/未就绪 | 自动重新准备音色一次；仍失败计入streak |
| UNKNOWN_ERROR | 其他 | 安全降级，文字保留 |

切换状态机（`TtsProviderPolicy`，纯函数，JVM 全矩阵测试）：
- 临时错误 → `KeepCloud`（永不触发下载弹窗）
- 持续错误 streak≥2 或确认性错误 → 本地已装且验证通过 → `SwitchLocal`（静默切换）
- 本地未装 → `PromptDownload`（独立 Activity 征询，任何界面可见）
- 用户拒绝 → 24h 冷却 → `FailText`（只提示，不反复弹窗，文字聊天不受影响）
- LOCAL_FALLBACK 期间每次朗读触发恢复探测（≥5min 间隔，Worker `/v1/probe` 仅用免费模型、独立限频、不耗每日额度）
- 探测成功 → 下一回复边界切回 CLOUD_ACTIVE，不打断当前播放

## 3. E2E 验证结果（模拟器 + 真实 workerd + mock 上游，无真实密钥）

测试台：`cloud-tts-worker/test/e2e-server.mjs`（Miniflare 8787 + mock 聊天 8788 + 控制面 8791），
BluefinReview 模拟器（x86_64, API 36），mock 上游返回真实合成 WAV（可听）。

| # | 场景 | 结果 |
|---|---|---|
| 1 | 新装（pm clear）启动 | ✅ files/ 为空，零下载，无下载弹窗 |
| 2 | 新装开启语音（试听自动开启） | ✅ 无需本地模型，无下载门槛 |
| 3 | 云端朗读完整回复 | ✅ 3句分别合成（worker日志），情绪 happy 传递 |
| 4 | 情绪标记 `[[emotion:happy]]` | ✅ UI/转录/朗读均无标记；transcript 持久化 emotion=happy |
| 5 | 句子缓存 | ✅ 3个缓存文件；重复回复命中缓存不再请求 |
| 6 | 注入402 + 本地已装 | ✅ 静默切换 LOCAL_FALLBACK，ZipVoice 3句完整播放（27672+68113+37120帧） |
| 7 | 云端恢复（mode=ok） | ✅ 探测成功→CLOUD_ACTIVE（回复边界），下一回复云端3句 |
| 8 | 设置删除本地模型 | ✅ 目录清除、verified=false、状态回 CLOUD_ACTIVE |
| 9 | 402 + 本地未装 | ✅ FallbackSetupActivity 征询弹窗覆盖聊天界面（178MB/188MB/暂停取消/验证/不删音色） |
| 10 | 拒绝下载 | ✅ 24h冷却写入；再发失败回复不再弹窗；文字聊天正常（failed=false） |
| 11 | 注入500（临时） | ✅ 重试后仅提示，无弹窗、零下载 |
| 12 | 升级场景（旧数据保留） | ✅ 旧 ZipVoice 模型、音色包、58+行转录、设置全部保留 |
| 13 | 全程崩溃检查 | ✅ 无 FATAL / AndroidRuntime 异常 |

升级安装验证：versionCode 33→34 覆盖安装成功，用户数据无损。

## 4. 自动化测试

- **Android JVM：537 个测试，0 失败**（`./gradlew testDebugUnitTest`）
  新增：ReplyEmotionTest（流式标记全切分点矩阵）、TtsProviderPolicyTest（切换全矩阵）、
  TtsFailureTest（category解析）、TtsCacheKeyTest、VoicePackFormat 双绑定、TtsText 云/本地分句、
  NoBuiltInCredentialTest 扩展（无 FISH_API_KEY、无 api.fish.audio 直连、无内置权重、云端客户端零日志）。
- **Worker 单元：11 个测试通过**（令牌签发/验签/过期/篡改、情绪白名单、出站白名单、错误分类）。
- **Worker 集成：49 项检查通过**（`node test/integration.mjs`，真实 workerd + 真实 Durable Objects + mock 上游）：
  注册/伪造令牌/吊销优先于限流、免费模型头强制、指令注入剥离、客户端无法改 model/temperature、
  限速/每日额度/并发、402→MODEL_UNAVAILABLE 且全程零付费调用、私有音色跨设备隔离、
  probe 限频与故障透出、流式字节保真、音色删除清理上游模型。

## 5. APK 体积对比与安全扫描

| 包 | 体积 | 说明 |
|---|---|---|
| 旧 bluefin-0.11.0-rc1-33-arm64.apk | 62,779,195 B | 离线 TTS 必需下载 178MB 模型 |
| 新 bluefin-0.12.0-rc1-34-arm64.apk | 62,841,531 B | **+62,336 B (+0.10%)** |
| 新 x86_64 验收包（双ABI） | 98,560,837 B | 模拟器验证用 |

体积说明：sherpa-onnx 运行库（arm64，约 33MB 压缩后）保留——它同时支撑**语音输入(ASR)**与
**ZipVoice 备用**，无法裁剪。真正的节省：178MB TTS 模型从"必需"变为"可选"，新装用户零模型下载。
若未来放弃语音输入与本地备用，可整体移除 AAR，APK 可降至约 25MB（本次不做，避免破坏无关功能）。

安全扫描（解包 arm64 交付包）：
- ✅ 无 .onnx/.pt/.tflite 模型权重（DebugProbesKt.bin 为 1.7KB Kotlin 调试探针，非模型）
- ✅ 全部文件无 `FISH_API_KEY` 字符串
- ✅ dex/resources/assets 无 `api.fish.audio`（客户端绝不直连供应商）
- ✅ 无 `sk-` 令牌、无硬编码 Bearer 凭据
- ✅ 内置音色 timbre1/timbre2（reference.wav + voice.json）保留
- ✅ native 库仅 arm64-v8a（libonnxruntime + sherpa-onnx ×3）

## 6. 变更文件清单

### 新增（Android，app/src/main/java/com/wwkhub/bluefin/）
| 文件 | 职责 |
|---|---|
| voice/tts/TtsProviderManager.kt | 云端优先⇄本地备用状态机、探测恢复、征询调度 |
| voice/tts/TtsProviderPolicy.kt | 纯函数切换策略（JVM可测） |
| voice/tts/TtsFailure.kt | 8类故障归一化解析 |
| voice/tts/CloudTtsConfig.kt | Worker地址解析（prefs覆盖→BuildConfig），复用EndpointAddressPolicy |
| voice/tts/CloudIdentity.kt | 设备UUID+短期令牌（Keystore加密存储），401自动重注册 |
| voice/tts/CloudTtsClient.kt | Worker HTTP客户端（流式合成/注册/探测/音色），零日志 |
| voice/tts/CloudVoiceManager.kt | 内部音色ID↔Fish模型ID绑定（link/全局映射/零样本克隆） |
| voice/tts/CloudTtsSpeaker.kt | 句子级合成+预取+双MediaPlayer无缝衔接+缓存 |
| voice/tts/TtsCache.kt | 磁盘LRU缓存（50MB上限，key含文本/音色/语速/情绪） |
| voice/tts/TtsEmotion.kt | 8情绪枚举 |
| voice/tts/LocalEngineVerifier.kt | ZipVoice安装后真实推理验证 |
| ReplyEmotion.kt | 流式安全情绪标记解析（不闪现、不朗读、不持久化标记） |
| FallbackSetupActivity.kt | 独立征询+下载(暂停/取消)+验证流程，任何界面可见 |

### 修改（Android）
- ChatSession.kt：情绪标记解析接入流式渲染、persona语音提示（仅开启语音时）、情绪传递与持久化
- TranscriptFile.kt：ChatLine.emotion 字段（向后兼容）
- TtsReader.kt：改为 ProviderManager 门面；自动朗读开关；reread 保留情绪
- TtsSpeaker.kt（ZipVoice）：单句调用+空结果引擎自愈重建+诊断日志（保留为备用引擎）
- TtsModelManager.kt：暂停/Range续传/结果分类（Success/Paused/Cancelled/Failed），保留完整性校验
- TtsText.kt：剥离 [[...]]/[...] 指令；云端整句优先、超长句才按逗号拆；minChars 参数
- VoicePackFormat.kt：kind 接受 zipvoice|fish；可选 fishReferenceId（hex校验）；旧包完全兼容
- BluefinStore.kt：auto_speech/speech_speed/cloud_tts_base/tts_engine_state/fallback_declined_until/zipvoice_verified
- SpeechSettingsSection.kt + section_speech_settings.xml：云端引擎状态、双开关、语速滑条、试听、缓存管理、离线备用面板（默认隐藏）、Worker地址编辑
- MainActivity.kt：移除旧引擎下载门槛流程；设置面板生命周期dispose
- AndroidManifest.xml：注册 FallbackSetupActivity
- build.gradle.kts：versionCode 34 / 0.12.0-rc1；CLOUD_TTS_BASE_URL（-PcloudTtsBase）
- strings.xml / voice_input.xml：隐私与许可文案如实披露云端合成；新增云端/备用文案
- 测试：ReleaseIdentityTest/OverlayLayoutTest（版本与文案）、NoBuiltInCredentialTest（扩展）、TtsTextTest、VoicePackFormatTest

### 新增（后端，仓库 cloud-tts-worker/）
```
wrangler.toml            免费计划配置（SQLite Durable Objects + 限额vars）
src/config.ts            硬编码上游/模型 + 出站白名单（拒绝环回/私有/保留地址）
src/auth.ts              HMAC设备令牌（签发/验签/常量时间比较）
src/emotion.ts           8情绪→S2.1方括号语气映射 + 客户端指令剥离
src/errors.ts            安全错误面 + 8类category
src/fish.ts              上游客户端（model头硬编码s2.1-pro-free，402绝不付费回退）
src/gate.ts              Durable Object：注册表/IP限额/全局并发/音色映射/设备限流额度
src/index.ts             路由：register/refresh/tts/probe/voices(+link)/admin
test/policy.test.ts      单元测试（11）
test/integration.mjs     workerd集成测试（49项，mock上游，无密钥）
test/e2e-server.mjs      模拟器E2E测试台（worker+mock聊天+控制面）
scripts/provision-voices.mjs  内置音色全局克隆（经Worker管理接口）
scripts/verify-deploy.mjs     部署后真实免费模型冒烟验证
README.md                部署与安全文档
```

## 7. 部署步骤

**生产部署已完成**（见第 8 节）：`https://bluefin-tts.pages.dev`，Key 已由用户在本地终端
经 `wrangler pages secret put` 设置（全程未进聊天/源码/仓库）。完整可复现步骤见
`cloud-tts-worker/README.md`。要点：

```bash
cd cloud-tts-worker && npm install
HTTPS_PROXY=http://127.0.0.1:7897 npx wrangler login
HTTPS_PROXY=... npx wrangler pages secret put FISH_API_KEY --project-name bluefin-tts   # 交互粘贴
HTTPS_PROXY=... npx wrangler pages secret put TOKEN_SIGNING_KEY --project-name bluefin-tts
HTTPS_PROXY=... npx wrangler pages secret put ADMIN_TOKEN --project-name bluefin-tts
npm run deploy    # ⚠ Pages Secret 设置后必须重新部署才生效
```

App 交付包已预置生产地址；用户也可在设置里覆盖（未来自有域名等）。

## 8. 生产部署与真实验证（已完成，2026-11-18）

### 部署形态
- **生产端点：`https://bluefin-tts.pages.dev`**（Cloudflare Pages 高级模式 `_worker.js`，Free 计划）
- 备用端点：`https://bluefin-tts.wwkhub.workers.dev`（同代码、共享 DO/KV 状态）
- 选择 Pages 的原因：`*.workers.dev` 在大陆被 DNS 污染（实测解析到 Facebook IP 31.13.73.169）；
  `*.pages.dev` **无代理直连可达**（实测真实 Cloudflare IP 104.18.x，标准 TLS）——用户无障碍。
- 交付 APK 已预置生产地址（dex 验证含 bluefin-tts.pages.dev），开箱即用。
- Secret：FISH_API_KEY（用户终端交互设置）/ TOKEN_SIGNING_KEY / ADMIN_TOKEN（随机生成）。
  **Pages 的 Secret 需重新部署后生效**（与 Workers 不同，已踩坑并文档化）。

### $0 余额关键发现与解决
- 免费模型合成 $0 可用 ✓；但 **POST /model 持久音色创建在 $0 余额被拒**
  （403 "API model creation limit reached (0/0)"）——持久克隆需充值。
- 解决：**官方一次性内联克隆**（`references` 字段）。内置音色参考音频构建时编译进 Worker
  （24kHz WAV→44.1kHz MP3 64kbps，~26KB/个），每句随请求携带，零配置零充值。
- 内联音频必须 **MessagePack** 发送（JSON base64 被 Fish 判无效，实测）；MP3 参考仅 32/44.1kHz。
- 自定义音色：参考音频存 KV（免费档），按设备隔离，同样内联克隆。
- 可选升级：网页手动克隆或充值后 provision 持久 reference_id，自动优先（省每句 ~35KB 上传）。

### 真实端到端结果（模拟器 + 真实 Fish API）
| 项 | 结果 |
|---|---|
| 宿主无代理直连 pages.dev | ✅ healthz 200 / 未认证 401 |
| 真实合成（timbre1，整句中文） | ✅ 200，56,841 B 有效 MP3，**2.7s**（含参考上传+合成+下载） |
| 模拟器预览（真实云端） | ✅ 2 句真实 MP3（27,166 / 22,568 B） |
| 模拟器完整聊天→自动朗读 | ✅ mock 聊天回复（含情绪标记）→ 3 句真实 Fish MP3（27,166 / 21,732 / 50,990 B）播放 |
| 情绪 | ✅ transcript emotion=happy，UI/转录无标记泄漏 |
| 崩溃 | ✅ 0 FATAL |
| timbre2 | ✅ 真实合成 200 |

试听样本：`D:\xiaoshen\xiaoshen-voice-sample.mp3`（真实 s2.1-pro-free + timbre1 内联克隆，
文本"哼，你终于来找我啦！我才没有一直等你呢，只是刚好看了一眼时间而已。今天也要开开心心的哦！"，emotion=happy）。

注：模拟器因本地虚拟化 NAT 不通外网（宿主直连正常），经 `-http-proxy` 走宿主出口完成验证；
App 代码路径与真机完全一致（标准 HttpsURLConnection，无代理配置）。真机在大陆网络直连
pages.dev 即可（宿主已实证可达）。

## 9. 尚未完成 / 需要注意的事项

1. **免费模型公平使用限额**：官方未公布具体数值；Worker 限额（12次/分、2万字/天/设备、全局并发4）
   可在 wrangler.toml 调整。内联克隆每句上传 ~35KB 参考音频，移动网络首句延迟略增（预取流水线掩盖后续句）。
2. **Fish 偶发过度生成**：极短文本偶尔产生偏长音频（实测一次 762KB）；repetition_penalty=1.2 已设，
   属供应商侧行为，不影响正确性。
3. **语音包导入UI自动化**：SAF选择器未做E2E；导入代码未改动且单元测试覆盖（含新fish kind与双绑定）。
4. **ZipVoice 极短回复**：本地引擎对短于参考音频的句子可能返回空音频（引擎固有）；已用单句调用+
   引擎自愈缓解，极短回复在本地模式下仍可能无声（文字保留、不崩溃）；云端模式无此限制。
5. **情绪语气自然度**：S2.1 方括号语气为隐式学习映射，效果因音色而异；当前选用克制提示词
   （worker src/emotion.ts CUES 可调），真实音色试听后可微调。
6. **更新通道**：GitHub update-channel 清单仍指向33；发布34时按你的既有流程更新。
7. **APK签名**：交付包为debug签名（与旧包一致）；正式发布需你的release签名流程。
8. **持久音色升级（可选）**：如希望进一步降低延迟/流量，可在 fish.audio 网页克隆 timbre1/timbre2
   获得模型 ID，经 `POST /admin/voices` 绑定为持久 reference_id（网页克隆额度与 API 创建额度独立）。

## 9. 本地测试台（当前仍在运行，可继续体验）

- 模拟器 emulator-5560 已装 0.12.0-rc1 验收包（预置 http://10.0.2.2:8787）
- E2E台：`node cloud-tts-worker/test/e2e-server.mjs`（端口8787/8788/8791）
- 故障注入：`curl -X POST http://127.0.0.1:8791/mode -d '{"mode":"402"}'`（ok/402/429/500）
- 停止：结束 e2e-server 进程即可释放端口