# cloud-tts-worker

鳍遇（Bluefin）Android 桌宠的云端语音代理。**Fish Audio `s2.1-pro-free` 专用**，
供应商 API Key 只存在于 Cloudflare Worker Secret，绝不进入 APK、客户端配置或日志。

## 架构

```
Android App ──设备令牌──▶ 本 Worker ──FISH_API_KEY──▶ https://api.fish.audio
（不含供应商密钥）        （认证/限流/配额/音色隔离）      （model: s2.1-pro-free，硬编码）
```

- 客户端只持有 HMAC 签名的短期设备令牌（7 天，可轮换），不持有 Fish Key。
- `model` 请求头在 `src/fish.ts` 中硬编码为 `s2.1-pro-free`。Fish Audio 在缺失该头时
  会回退到**付费** `s2.1-pro`，因此本服务任何路径都不会省略或改写它。
- 上游 402（免费模型不可用）→ 返回 `MODEL_UNAVAILABLE` 并停止，**绝不回退付费模型**。
- 出站仅允许 `https://api.fish.audio`（`config.ts` 白名单校验，拒绝环回/私有/保留地址）。
- 错误按真实供应商响应归一化为 8 类 `category`，客户端据此决定重试或切换本地备用引擎。

## 接口

| 路由 | 认证 | 说明 |
|---|---|---|
| `GET /healthz` | 无 | 存活与模型声明 |
| `POST /v1/register` | 无（IP 限额 + 设备总数上限） | 注册设备 UUID，签发令牌 |
| `POST /v1/token/refresh` | 设备令牌 | 轮换令牌 |
| `POST /v1/tts` | 设备令牌 | 流式合成 `{text, voiceId, speed, emotion}` |
| `POST /v1/probe` | 设备令牌 | 云端恢复探测（独立限频，不耗每日额度） |
| `POST /v1/voices` | 设备令牌 | 上传参考音频 → 私有零样本克隆 |
| `POST /v1/voices/link` | 设备令牌 | 绑定已有 Fish reference_id（服务端验证可见性） |
| `GET /v1/voices` | 设备令牌 | 列出全局 + 本设备音色（不含 Fish 模型 ID） |
| `DELETE /v1/voices/:id` | 设备令牌 | 解绑并删除上游私有模型 |
| `GET/POST /admin/*` | ADMIN_TOKEN | 设备列表/吊销、全局音色 provision/clone/删除 |

限额（`wrangler.toml [vars]`，部署者可改，客户端不可改）：
单设备 12 次/分钟、20000 字/天、并发 1；全局并发 4（Fish 免费档 5 并发留余量）；
单 IP 注册 5 次/天；设备总数 100；每设备音色 20；单句 ≤500 字；参考音频 ≤4MB。

## 部署（免费方案）

**生产端点：`https://bluefin-tts.pages.dev`（Cloudflare Pages，已部署并真实验证）。**
选择 Pages 而非 workers.dev 的原因：`*.workers.dev` 在中国大陆被 DNS 污染（实测解析到
Facebook IP），而 `*.pages.dev` 直连可达（实测真实 Cloudflare IP，标准 TLS，无需代理、
无需自有域名）——最终用户无障碍使用。

前提：Cloudflare 账号（Free 计划）、Fish Audio API Key、Node ≥ 20。
本目录所有命令在仓库的 `cloud-tts-worker/` 下执行。大陆网络下 wrangler 命令需带系统代理：
`HTTPS_PROXY=http://127.0.0.1:7897`（按你的实际代理端口）。

```bash
npm install
HTTPS_PROXY=http://127.0.0.1:7897 npx wrangler login    # 浏览器 OAuth

# 生成两个随机串（各运行一次）
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"

# Pages Secret（交互输入，值不进源码/仓库/聊天）
HTTPS_PROXY=... npx wrangler pages secret put FISH_API_KEY --project-name bluefin-tts
HTTPS_PROXY=... npx wrangler pages secret put TOKEN_SIGNING_KEY --project-name bluefin-tts
HTTPS_PROXY=... npx wrangler pages secret put ADMIN_TOKEN --project-name bluefin-tts

npm run deploy        # 构建（含内置音色编译）+ 部署 Pages
```

**⚠ Pages 与 Workers 的关键差异：Secret 设置后必须重新部署才生效。**
（Workers 即时生效；Pages 的 Secret 绑定到部署快照。）

### $0 余额的关键事实（已实测）

- `s2.1-pro-free` 合成：$0 余额可用（实测 200）。
- **POST /model 持久音色创建：$0 余额被拒**（403 "API model creation limit reached
  (0/0). Top up API credit to raise the limit."）——持久克隆需要充值，不是无条件免费。
- 本服务的解决方案：**官方支持的一次性内联克隆**（TTS `references` 字段）。内置
  timbre1/timbre2 的参考音频在构建时编译进 Worker（`scripts/build-builtin-voices.mjs`，
  24kHz WAV → 44.1kHz MP3 64kbps，每个 ~26KB），每次合成随请求携带，**无需创建模型、
  无需充值、任何设备零配置即用**。
- 内联音频**必须用 MessagePack 二进制**发送（`application/msgpack`）；JSON base64
  会被 Fish 判为无效音频（实测 400 "Reference Audio is not valid"）。MP3 参考仅支持
  32/44.1kHz（24kHz MP3 被拒；WAV 支持 24kHz）。
- 设备导入的自定义音色：参考音频存 KV（免费档），同样走内联克隆，按设备隔离。
- 升级路径（可选，降低每句 ~35KB 上传）：在 fish.audio 网页手动克隆音色获得模型 ID，
  经 `POST /admin/voices`  provision 为持久 `reference_id`；或充值后用
  `POST /admin/voices/clone`。持久映射自动优先于内联（`resolveVoiceForTts`）。

### 验证

```bash
WORKER_URL=https://bluefin-tts.pages.dev node scripts/verify-deploy.mjs   # 真实合成→verify-output.mp3
# 管理诊断（仅所有者，ADMIN_TOKEN）：
curl -H "Authorization: Bearer $ADMIN_TOKEN" "https://bluefin-tts.pages.dev/admin/health?probe=1"
```

App 交付包已预置 `-PcloudTtsBase=https://bluefin-tts.pages.dev`；用户也可在
「设置 → 朗读回复 → 修改云端服务地址」覆盖（例如未来自有域名）。

### 备用端点

`https://bluefin-tts.wwkhub.workers.dev`（同代码同 DO/KV 状态，`npm run deploy:worker`）
仅代理环境可用；生产请用 pages.dev。

## 测试（无需任何真实密钥）

```bash
npm test                    # 策略单元测试（令牌/情绪/白名单/错误分类）
node test/integration.mjs   # workerd + 真实 DO + mock 上游，49 项契约检查
node test/e2e-server.mjs    # 模拟器 E2E 测试台：worker=8787 mock聊天=8788 控制面=8791
```

E2E 台故障注入：`curl -X POST http://127.0.0.1:8791/mode -d '{"mode":"402"}'`（ok/402/429/500）。
App 验收包用 `-PcloudTtsBase=http://10.0.2.2:8787` 构建即可对接（模拟器经 10.0.2.2 访问宿主）。

集成测试覆盖：注册/伪造令牌/吊销、免费模型头强制、指令注入剥离、限速/每日额度/
并发、402→MODEL_UNAVAILABLE 且无付费回退、私有音色跨设备隔离、probe、流式字节保真。

## 安全清单

- [x] FISH_API_KEY 仅在 Worker Secret；源码/示例/测试无可用凭据字面量
- [x] 客户端令牌与供应商密钥完全分离；令牌 7 天过期、可轮换、可吊销
- [x] 上游主机/模型/请求头不可被客户端指定；出站白名单拒绝私有地址
- [x] 日志只含路由、状态、设备哈希、耗时；无文本/令牌/密钥/上游响应体
- [x] 原始供应商错误不转发；统一安全错误码 + category
- [x] 每设备限频/额度/并发 + 全局并发 + 注册 IP 限额 + 设备总数上限
- [x] 音色映射按设备隔离；私有模型 visibility=private

### 依赖审计说明（2026-11-18）

- Mimosa 深度扫描：0 发现（调用图覆盖部分不完整，结论 inconclusive，非运行时验证）。
- `undici`（miniflare 传递开发依赖）3 条高危公告 → 已用 overrides 固定 ^7.30.2 修复，集成测试复跑通过。
- `sharp`（miniflare 传递开发依赖，libheif/librsvg 公告）截至 0.35.5 无修复版；
  仅影响本地 `wrangler pages dev` 的 Images 模拟（本项目不使用），**线上部署包不含
  sharp/undici**（esbuild 仅打包 src/ 依赖：@msgpack/msgpack）。上游发布修复版后升级即可。

## 参考录音清理

`DELETE /v1/voices/{id}` 先撤下设备私有映射，再清理此服务存储的参考录音。返回 `{"deleted":true}` 时表示已收到清理确认。链接到已有 Fish 模型的记录仅解除绑定。

独立 Worker 的 Cron 每天 UTC 03:37 生成清理报告。部署配置的 `VOICE_RETENTION_ENABLED` 默认为 `false`。上线前通过授权的 `POST /admin/voice-maintenance` 发送 `{}` 查看计数，按返回的 `cursor` 继续检查所有页面。报告只包含待回收设备和音频计数。比较活跃样本及其映射后，在独立 Worker 的配置启用该变量。每日分批释放超过 90 天的设备名额和持有的副本。可在紧急情况下将开关改回 `false` 并重新部署。 [Cloudflare Cron Triggers](https://developers.cloudflare.com/workers/configuration/cron-triggers/)
