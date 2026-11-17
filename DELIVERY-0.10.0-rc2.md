# 交付说明 · 0.10.0-rc2（versionCode 27）

三项需求按「动作修复 → 界面精简 → 应用内更新」顺序实施并分别验收。基线为 `main`
（968a974）加上工作区中 codex 已完成的语音输入改动（未提交）；本轮全部为增量修改，
未提交任何 commit，未发布任何 Release。

测试 APK（均与已分发的 v0.9.0 同一签名链，可覆盖升级）：

| 文件 | 用途 | ABI | 更新源 |
|---|---|---|---|
| `D:\xiaoshen\.update-verify\delivery\bluefin-0.10.0-rc2-default.apk` | 正式候选包 | arm64-v8a | GitHub 单源（`update-channel` 预发布标签） |
| `D:\xiaoshen\.update-verify\delivery\bluefin-0.10.0-rc2-test.apk` | 验收/模拟器包 | arm64-v8a + x86_64 | 本地测试源（`-PupdatePrimary/-PupdateFallback/-PupdateAllowInsecureLan`，仅 debug 生效） |

两包 versionCode 27 / versionName 0.10.0-rc2，签名证书 SHA-256
`572ea4e5d924a9bc99e7e4bc1e0733ab4cce36cf906e7273be2d3aaa6cf98c6c`（与 v0.9.0 一致）。
模拟器当前处于测试链末端 29（rc4）；装回 27 需 `adb install -r -d`。

## 1. 补齐动作与表情

改动：
- 新增 `ActionVocab.kt`：以当前角色 `PackManifest.vocab` 为唯一来源，分「动作」「表情」两栏。
- `MainActivity.showActions()`：删除写死的 5 动作回退与独立「恢复站立」按钮；首页与导入角色走同一清单路径，「站立」作为动作之一只列一次；空清单角色仍显示「这个角色没有可做的动作」。
- `OverlayWindow.refreshActionButtons()`：删除内置 5 动作硬编码；小球动作页改为「动作」「表情」两个网格（`overlay_orb.xml` 新增分栏容器），沿用原 `act()` 派发与 walk 限制。
- `sheet_actions.xml`、`overlay_orb.xml`：分栏标题与网格；删除 6 条不再使用的动作字符串。
- 新增 `ActionVocabTest`（5 例）：内置/哑光冬装 21 动作 19 表情、站立一次、导入角色只列自己的清单、空清单、标签回退。

验收（BluefinReview 模拟器，截图与脚本见 `D:\xiaoshen\.update-verify\`）：
- 首页动作面板：内置蓝鳍 43 个文本节点 = 标题 + 2 分栏标题 + 21 动作 + 19 表情；哑光冬装相同。
- 小球动作页：内置与哑光冬装均 21 动作 + 19 表情，站立一次，无多余按钮（滚动全量清点）。
- 派发：首页与小球分别点按 跳跃/鞠躬/伸懒腰/眨眼，WebView QA 状态确认
  `jump→air/land`、`bow→bow_2/bow_3`、`stretch→stretch`、`wink→talk` 且眼部像素变化；
  小球另有 logcat `orb action <word>`。截图 `act-*.png`、`orb-*.png`。
- 导入角色实测：whale 包（DeepSeek 大肥鱼）首页恰好列出其自身 18 动作 + 19 表情 = 37 键
  （站起/跳/小跳/走走/招手…，不含蓝鳍的 站立/伸懒腰/抱抱/趴下）。
- 单测 465 例全绿；`OverlayOpeningBaselineContractTest` 的 SHA 锁未被破坏。

## 2. 精简界面

改动：
- 「关于」首屏只保留 `版本 %s` 与四个入口：检查更新、第三方许可、项目来源、隐私说明；
  完整内容改为点开后的对话框（`about_licenses/about_source/about_privacy`，源自原 about 长文与 THIRD_PARTY_NOTICES）。
- 缩短 7 处重复说明：底边走动、音效、暂停并隐藏、悬浮窗开关、重新询问权限、角色导入、引导第 3 步（不再枚举 5 个动作）。
- 保留：全部功能入口、权限提示（悬浮窗/麦克风/看屏/未知来源）、故障提示（加载超时、导入失败、气泡回退等）、
  小球穿透恢复说明（「开启后无法点开小球。可在应用内「设置」关闭。」与对应 toast）。
- `ReleaseIdentityTest` 要求的隐私措辞保留在 `about_privacy` 中，测试不改即过；`OverlayLayoutTest` 的版本断言随版本升级同步。

验收：
- 关于首屏 dump 仅 版本 + 4 入口；三个对话框全文可见（许可含 AGPL/Apache/MIT 与模型许可）。
- 回归：气泡弹键盘（`mInputShown=true`）→ 发送 → 头顶流式回复气泡（截图 `bubble-streaming.png`）→ 记录面板同步；
  应用内对话收发（mock OpenAI 接口）；麦克风录音计时 → 识别 → 空结果妥善提示（logcat `BluefinVoice`）；
  哑光冬装切换；小球透明度 39%→20%→100% 像素级生效；宠物/小球穿透相互独立；
  小球穿透开启后点按穿透、从应用设置关闭后小球恢复可点（logcat `orb expanded`）。

## 3. 应用内更新

改动（新增 `update/` 包 + 接线）：
- `UpdateManifest`：清单解析（versionCode/versionName/apkUrl/sha256/sizeBytes/notes），非法即弃用该源。
- `UpdateUrlPolicy`：分发仅 https；http 仅 debug 测试构建的本机/局域网地址（镜像模型端点策略）。
- `UpdatePolicy`：递增 versionCode 门槛；安装包必须为本包名、与清单同版本、且高于已装版本；SHA-256 比对；签名证书集合必须与已装应用完全一致。
- `UpdateDownloader`：流式下载 + 滚动 SHA-256 + 200MB 上限 + 协作取消（取消/失败删除残包）。
- `UpdateInstaller`：读取安装包身份与签名、未知来源权限判断、FileProvider + 系统安装 Intent（系统安装器始终二次确认）。
- `UpdateCoordinator`：主源→备用顺序检查；下载→校验→交系统安装；全部回调主线程；忙锁防并发。
- 入口：设置 → 关于 → 检查更新；检查/下载进度对话框可取消；失败对话框带重试；未知来源引导「去开启」后返回自动续装。
- 清单与权限：`REQUEST_INSTALL_PACKAGES`、FileProvider（`cache/updates/`）。
- 源配置：`build.gradle.kts` 三个 BuildConfig 字段；**产品决策（2026-11-16，用户确认）：GitHub 单源**，
  默认主源 = `https://github.com/WWK-HUB/bluefin-android/releases/download/update-channel/update-manifest.json`
  （维护中的预发布标签，随每次发版更新清单与 APK），备用默认空；
  大陆直连风险以应用内文案告知（`update_github_hint`，出现在发现新版本与失败对话框）。
  多源顺序回退架构保留，未来国内主源 = 改一处配置；
  测试构建用 `-PupdatePrimary=… -PupdateFallback=… -PupdateAllowInsecureLan=true` 注入。
- `scripts/make-update-manifest.py`：由签名 APK 生成两源共用的 `update-manifest.json`（强制 https 基址）。
- 单测 16 例（清单/地址策略/校验决策/下载成功/404/超尺寸/取消残包）。

验收（本地测试源 = 国内主源角色 8765 + 备用角色 8766，`adb reverse`；证据截图/日志同目录）：

| 场景 | 结果 |
|---|---|
| 主源正常：检查→下载→校验→系统确认→覆盖安装 27→28 | PASS |
| 覆盖升级数据保留：聊天记录 46 条、模型配置、人设、whale 角色包 | PASS（28→29 复核含自定义人设） |
| SHA-256 被篡改 | PASS：校验失败提示 + 重试 |
| 安装包为它包（framework-res） | PASS：包名不一致拒绝 |
| 清单版本与安装包不符（29 清单 / 28 包） | PASS：版本不一致拒绝 |
| 不递增 versionCode（28 = 已装） | PASS：toast 已是最新版本 |
| 签名不一致（另钥重签 29 包） | PASS：签名不一致拒绝，提示迁移方案 |
| 双源皆不可达 | PASS：失败对话框 + 重试；恢复主源后重试成功 |
| 仅备用可达 | PASS：来源显示 GitHub 备用，完整下载校验到系统安装器 |
| 仅主源可达（限速 1.5MB/s） | PASS：独立完成检查/下载/校验/交安装器 |
| 下载中取消 | PASS：进度 12% 时取消，对话框关闭，残包删除 |
| 取消后重试 | PASS：重新下载直至系统安装器 |
| 未知来源未授权 | PASS：引导对话框→系统设置开启→返回自动续装 |
| 系统安装器取消 | PASS：返回应用无损，版本不变 |
| 默认源正式包（主源空、GitHub 不可达） | PASS：失败提示 + 重试，不影响其它功能 |
| **真实 GitHub 通道**（update-channel 预发布，2026-11-16 经用户确认创建） | 见下 |

真实 GitHub 链路（`https://github.com/WWK-HUB/bluefin-android/releases/tag/update-channel`，
prerelease，资产 = `bluefin-0.10.0-rc3.apk`(28) + `update-manifest.json`；非正式 Release）：
- 检查：模拟器直连拉取清单成功 → 「发现新版本 0.10.0-rc3 / 更新来源：GitHub」+ 代理告知文案。
- 大陆直连下载：资产 CDN（release-assets.githubusercontent.com）被阻断 → 失败对话框含
  「下载失败：failed to connect …」+ 代理告知 + 重试；重试换边缘节点仍阻断时同样妥善提示。
- **代理路径（已告知用户的 workaround）**：模拟器全局代理 10.0.2.2:7897（宿主 Clash）→
  下载 62MB 完成 → SHA-256/包名/versionCode/签名四重校验 → 系统安装器确认 → 覆盖 27→28；
  人设（自定义句）、whale 角色包、46 条聊天记录全部保留；验收后已清除模拟器代理。
- 通道维护约定：每次发版更新该标签的 manifest 与 APK（`scripts/make-update-manifest.py`
  生成清单，base-url 用 `…/releases/download/update-channel/`）；正式 Release 仍需另行确认。

### 签名核对与迁移缺口（实施前已核对）

- 实际分发给用户的旧包：GitHub Release v0.9.0 资产 `bluefin-android-0.9.0-debug.apk`
  （19,613,593 字节，versionCode 24）。apksigner 证书 SHA-256
  `572ea4e5…98c6c`，DN `CN=Android Debug` —— 与本机 `~/.android/debug.keystore` 完全一致。
- 因此本轮测试链（27/28/29）沿用同一 debug 签名，覆盖升级与数据保留已实测成立；
  更新客户端 additionally 强制「下载包签名 == 已装签名」，异签包在安装前即被拒绝。
- **缺口**：仓库与本机均无正式 release 签名材料；现行分发链是 debug 密钥（且仅存于本机）。
  若将来改用 release 密钥，所有已装用户将无法覆盖升级（系统签名不一致），属于迁移缺口：
  需要先决策（延续现链 / release 密钥 + 数据导出导入迁移 / 明确告知卸载重装并备份），
  不能把卸载重装当作覆盖升级。本轮未擅自生成或切换 release 密钥。
- 建议：正式 Release 前备份该 debug keystore 或建立 release 密钥与迁移方案；两源上传同一份签名 APK 与同一份清单。

## 待验收（不具备条件或产品决策放弃）

- ~~国内主源独立链路~~：产品决策改为 GitHub 单源 + 告知代理；国内主源架构保留，未来接入时再验。
- 电信 / 联通 / 移动 实网更新（无真机与运营商网络）；含「大陆直连 GitHub」（当前网络实测被阻断，
  客户端提示符合预期）与「代理后更新」（模拟器经宿主代理已实测通过）两种用户路径的真机复核。
- 正式 release 签名链的覆盖升级（签名材料未就绪，见上）。

## 复现要点

```bash
# 单测与动画回归
./gradlew :app:testDebugUnitTest          # 465 例
node scripts/test-pet-animation.mjs
# 测试源包
./gradlew :app:assembleDebug -PvoiceX86=true \
  -PupdatePrimary=http://127.0.0.1:8765/manifest.json \
  -PupdateFallback=http://127.0.0.1:8766/manifest.json -PupdateAllowInsecureLan=true
# 聊天回归用 mock OpenAI 接口（对话页/气泡收发验收所用）
python -I D:\xiaoshen\.voice-verify\mock-slow.py   # 127.0.0.1:18765，配合 adb reverse tcp:18765
# 本地源（目录与清单见 D:\xiaoshen\.update-verify\www-primary / www-fallback）
python -m http.server 8765 --bind 127.0.0.1 --directory www-primary
python -m http.server 8766 --bind 127.0.0.1 --directory www-fallback
adb reverse tcp:8765 tcp:8765 && adb reverse tcp:8766 tcp:8766
# 发布时生成清单（两源同一份）
python scripts/make-update-manifest.py signed.apk --version-code 28 --version-name 0.10.0-rc3 \
  --base-url https://<国内域名>/<路径>/ --notes "…"
```

验收脚本与截图：`D:\xiaoshen\.update-verify\`（`verify-actions.mjs`、`verify-orb-actions.mjs`、
`run-check.mjs`、`run-download.mjs`、`ui.mjs`、`act-*.png`、`orb-*.png`、`bubble-streaming.png`、
`s4-toast2.png`、`transcript-after.json`、`transcript-final.json` 等）。