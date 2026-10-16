# CQ Telegram Android — 优化方案（对照清单）

**基准：** DrKLO/Telegram 13.0.2 分叉（`je.cq.tg`）  
**原则：** 跟上游小版本为主干；CQ 层只做可回滚、高收益改动，避免全仓重写。  
**相关：** `CQ_OPTIMIZATION_zh.md`（审计）、`CHAT_MESSAGE_LOAD_ANIM_zh.md`、`WEBVIEW_PROXY_zh.md`

---

## 总览：清单 ↔ 状态 ↔ 下一步

| # | 清单项 | 状态 | 下一步（技术粒度） |
| --- | --- | --- | --- |
| 1 | UI 现代化（靠拢 MD3） | 部分：Liquid Glass / 品牌 | 设置/登录等「旧感」表面点状对齐 MD3 token，不重做聊天自绘 |
| 2 | 动画与性能（RV / 插入 / 滚动） | **完整进出场**：聊天历史/新消息/首屏 + 对话列表插入；大批量前 N 条完整播 | 媒体网格掉帧；真机 60/120fps 压测 |
| 3 | 依赖更新 | 部分：FCM BoM / minSdk | 分批去掉 SafetyNet、收紧 Stripe/isoparser；关 Jetifier 需验证 |
| 4 | 推送与代理 | **进行中**：WebView SOCKS；登录代理常显 | 代理下 FCM/长连接稳定性；MTPROTO 网页仍无法走 WebView |
| 5 | Kotlin 化 | 未系统推进 | 仅 CQ 自有新文件用 Kotlin；上游 Java 跟官方迁移 |

---

## 1. UI 现代化 → Material Design 3

**现状：** Telegram 主体是自绘 UI，不是标准 Material 组件树。全盘 MD3 成本极高且会与上游冲突。

**方案（CQ 层）：**

| 优先级 | 动作 | 涉及面 |
| --- | --- | --- |
| P0 | 保持 Liquid Glass / 品牌 / 动态色跟随上游 | 已有 `LiteMode.FLAG_LIQUID_GLASS` |
| P1 | 设置、代理、登录等「系统感」页面：圆角、对比度、触控反馈对齐 MD3 | `Proxy*Activity`、`LoginActivity`、部分 Settings |
| P2 | 抽出 CQ 色板 / 字号 token（CSS 式变量在 Theme 层），避免散落硬编码 | `Theme` / CQ overlay |
| 不做 | 重写 `ChatMessageCell` 为 Material Card | 与上游 merge 成本不可接受 |

**验收：** 设置/代理页在浅色/深色下对比度合格；聊天主路径视觉不劣于上游。

---

## 2. 动画与性能（RecyclerView / 消息插入 / 滚动）

**现状：** `ChatListItemAnimator` 已增强（历史 VPA 淡入上移、新消息 Spring+Y、首屏渐显、大批量降级）。详见 PR `chat-message-load-anim`。

**方案：**

| 优先级 | 动作 | 文件 / 手段 |
| --- | --- | --- |
| P0 | 合入聊天加载动画；真机验证 200–300ms / FastOutSlowIn / Spring | `ChatListItemAnimator`、`ChatActivity` |
| P1 | 对话列表滚动：减少 `notifyDataSetChanged`、控制 bind 重活 | `DialogsActivity` / adapter |
| P1 | 快速滑动时强制跳过 item 动画（已有 degrade，可再挂钩 fling） | `shouldSkipAddAnimations` |
| P2 | Systrace / Macrobenchmark：冷开聊天、上滑历史、持续收消息 | 本地脚本 + 中端真机 |
| 约束 | 继续用内嵌 RecyclerView 副本；优先 VPA / SpringAnimation | 见动画规范图 |

**验收：** 60fps 滚动不因插入动画掉帧；低端机自动降级后无明显卡顿。

---

## 3. 依赖更新

**现状：** AGP/Gradle/targetSdk 已较新；Firebase 已抬 BoM；仍有 SafetyNet、旧 Stripe、Jetifier 等。

**方案（分批，每批可独立回滚）：**

| 批次 | 内容 | 风险 |
| --- | --- | --- |
| A | 确认 FCM / Play Services 与 BoM 一致；推送回归 | 中 |
| B | 移除或隔离 `play-services-safetynet` → Play Integrity（若仍需） | 中高 |
| C | 分叉不用的支付/解析库 exclude（Stripe、多余 maps 等） | 低（需确认功能） |
| D | 关闭 Jetifier（先 `./gradlew :TMessagesProj:dependencies` 扫 support） | 中 |
| E | Java toolchain 8 → 17（desugar 收紧）— 跟上游节奏 | 高 |

**验收：** Release 包可装可登录；推送可达；APK 体积不反弹。

---

## 4. 推送与代理

**现状：**

- 登录页代理按钮常显  
- 应用内浏览器：SOCKS5 → `ProxyController`（PR `webview-inapp-proxy`）  
- MTPROTO / WEB 类型仍只服务 Telegram 协议，不能承载任意 HTTP

**方案：**

| 优先级 | 动作 | 说明 |
| --- | --- | --- |
| P0 | 合入 WebView SOCKS；文档告知用户网页请用 SOCKS5 | `WebViewProxyHelper` |
| P1 | 代理切换时：`ConnectionsManager` + WebView 同步（已挂钩）；补推送通道探活 | 弱网 / 切代理后收消息延迟 |
| P1 | SOCKS 认证路径回归（本地 `Socks5AuthForwarder`） | 带账号密码代理 |
| P2 | 代理下 FCM 与 MTProto 推送兜底策略梳理（不改 native 则只做 Java 侧超时/重连提示） | `GcmPushListenerService` 等 |
| 不做 | 把 MTPROTO 伪装成通用 HTTP 代理给 WebView | 协议不匹配 |

**验收：** SOCKS 下聊天 + 应用内打开链接出口一致；断代切换代理后可恢复。

---

## 5. Kotlin 化

**现状：** 主体仍为 Java；全量迁移工作量极大，且与上游冲突严重。

**方案：**

| 规则 | 做法 |
| --- | --- |
| 新写 CQ 层工具 | 优先 Kotlin（如后续 `cq/` 包内 helper） |
| 上游文件 | **不**为了 Kotlin 化而大面积 rename；等官方提交 |
| 渐进点 | 仅转换 CQ 自有、无上游对应的小文件；配合 ktlint |
| 协程 | 新网络/IO 可用协程；不替换现有 `DispatchQueue` 全局模型 |

**验收：** CQ 新增模块可 Kotlin 编译进包；上游 merge 无额外冲突峰值。

---

## 建议推进顺序（按收益 / 冲突）

```
① 合入：聊天动画 + WebView 代理（已开 PR，体验/代理清单对齐）
② 依赖批次 A/B（推送相关库）+ 代理下推送回归
③ UI：设置/代理页 MD3 点状打磨（不碰消息气泡）
④ 对话列表滚动性能扫尾
⑤ 依赖 C/D（体积与构建时间）
⑥ Kotlin：仅 CQ 新代码；不排全仓迁移
```

---

## 编译与回归（每批合入后）

```bash
./gradlew :TMessagesProj:compileReleaseJavaWithJavac
./gradlew :TMessagesProj_App:assembleArm64Release
```

**最小真机清单：** 登录 → 开 SOCKS 代理 → 收发消息 → 上滑历史 → 点链接应用内打开 → 杀进程后推送/唤醒。

---

## 明确不做（控制范围）

- 全 App Material3 组件替换  
- 自研 MTPROTO↔HTTP 网页代理桥  
- 全仓 Java→Kotlin  
- 替换内嵌 RecyclerView / 大改 JNI  

上游体验与安全修复：**持续 `scripts/sync-upstream.sh` 跟 DrKLO 小版本**，比本地重写更划算。
