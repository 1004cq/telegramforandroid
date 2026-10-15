# Telegram Android / CQ — 项目审计与优化计划

**基准：** DrKLO/Telegram `f32ffeec`（13.0.2 / 7175）  
**对照 APK：** `https://me.cq.je/CQ.apk`（`je.cq.tg`，versionCode `71759`）  
**审查方式：** 只读审计 + 低风险、可回滚的 CQ 分发层改动（见下方「已落地」）

---

## 结论（先看这个）

这是上游巨型项目，**不适合一次性全仓重写**。优先顺序：

1. **体积 / 分发**（立刻见效）— arm64-only APK  
2. **分叉安全默认项** — 关闭 Passkeys / Play Billing，更新地址指向 me.cq.je  
3. **构建卫生** — 密钥外置、Gradle 缓存、精简日常模块  
4. **依赖现代化** — 分批升 FCM / 去掉 SafetyNet / Stripe / isoparser 等陈旧库  
5. **Java API 现代化** — `startActivityForResult` / `onBackPressed` 等，需按模块渐进  
6. **NDK / UI** — 保持与上游同步，少改 JNI  
7. **CI / 质量门禁** — 补 assemble + lint 的最小流水线  

---

## 已落地（本仓库 CQ 层）

| 改动 | 说明 |
| --- | --- |
| `APP_PACKAGE=je.cq.tg` | 与线上 CQ.apk 包名一致 |
| `AppName=CQ` | 应用显示名 |
| `arm64` productFlavor | 仅打包 `arm64-v8a`，输出 `CQ-arm64.apk` |
| `CQ_ARM64_ONLY` | `gradle.properties` 开关，为 true 时跳过 afat 通用包 |
| `BuildVars` 分叉默认 | `SUPPORTS_PASSKEYS=false`、`IS_BILLING_UNAVAILABLE=true`、更新 URL → me.cq.je |
| `ApplicationLoaderImpl.isStandalone()` | 侧载分发走 standalone 更新路径 |
| `org.gradle.caching=true` | 加速重复构建 |
| `local.properties.example` | 密钥 / SDK 不进仓库 |
| `scripts/sync-upstream.sh` | 同步上游后提醒重放 CQ 覆盖层 |

### 推荐本地构建

```bash
git submodule update --init --recursive
cp local.properties.example local.properties   # 填入 sdk.dir 与签名

# 与当前线上一致的四 ABI 包：
./gradlew :TMessagesProj_App:assembleAfatRelease

# 体积优化包（真机几乎都是 arm64）：
./gradlew :TMessagesProj_App:assembleArm64Release
# 或 CQ_ARM64_ONLY=true ./gradlew :TMessagesProj_App:assembleRelease
```

**体积依据（CQ.apk 未压缩分析）：**

| 组件 | 约 |
| --- | --- |
| lib/x86 + x86_64 | 48.7 MB |
| lib/armeabi-v7a | 17.9 MB |
| lib/arm64-v8a | 22.1 MB |
| DEX + res + assets | ~58 MB |

去掉 x86 / x86_64 / armeabi-v7a 后，原生库可少约 **67 MB 未压缩**（压缩进 APK 后通常少十几到几十 MB）。

---

## 七域审计摘要

### 1. 构建系统与 Android 工具链 — 中风险

| 项 | 现状 | 建议 |
| --- | --- | --- |
| AGP | 8.13.2（与 APK metadata 一致） | 保持，随上游升级 |
| Gradle | 8.13 | 保持 |
| compile/targetSdk | 36 | 良好 |
| minSdk | 23 | 可评估升到 26+ 以删旧路径（破坏性） |
| Java `sourceCompatibility` | 仍为 1.8（desugar 开启） | 中期升 17；需全模块回归 |
| `configureondemand` | false | 保持（与 AGP 8 更稳） |
| Jetifier | 仍开启 | 验证无 support 传递依赖后关闭，缩短配置时间 |
| 签名密码 | 曾写在 `gradle.properties` | 已改为示例默认 + `local.properties` 文档 |

### 2. 依赖与 Firebase — 高收益 / 中风险

| 依赖 | 版本 | 问题 | 建议 |
| --- | --- | --- | --- |
| `firebase-messaging` | 22.0.0 | 偏旧 | 升到 BoM 对齐的现代 FCM（随上游） |
| `google-services` 插件 | 4.3.15 | 偏旧 | 升到 4.4.x |
| `play-services-safetynet` | 18.0.1 | **已弃用** | 新逻辑走 Play Integrity；逐步清空 `SAFETYNET_KEY` |
| `stripe-android` | 2.0.2 | 极旧 | 若分叉不用官方支付可 exclude |
| `isoparser` | 1.0.6 | 陈旧 | 评估替换或收紧使用面 |
| `play-services-maps` | 18.1.0 | 可升 | 随上游 |
| `credentials` | 1.6.0-alpha04 | alpha | 等稳定版；分叉已关 Passkeys |
| HockeyApp 模块 | `TMessagesProj_AppHockeyApp` | App Center 退役 | 日常构建勿用；仅保留兼容 |

### 3. Java / Kotlin 现代化 — 工作量大，宜渐进

粗扫描 `TMessagesProj/src`（数量级，含部分 androidx 副本）：

| 模式 | 约出现次数 | 方向 |
| --- | --- | --- |
| `onBackPressed` | ~320 | OnBackPressedDispatcher |
| `startActivityForResult` | ~81 | Activity Result API |
| `android.support.*` 引用痕迹 | ~76 | 多为媒体/车机路径，确认是否仍编译进包 |
| `AsyncTask` | ~63 | Executor / 协程（Kotlin 增量） |
| `NetworkInfo` | ~22 | `ConnectivityManager` NetworkCallback |

**原则：** 只改 CQ 自有代码与明确 bug；上游大面积 API 迁移应跟官方提交，避免 merge 地狱。

### 4. NDK / C++ — 低优先级改代码，高优先级保可编译

- `ndkVersion 27.2.12479018`，CMake 3.22.1，LTO / gc-sections 已开（上游已较激进）
- `ANDROID_PLATFORM=android-21` 与 `minSdk 23` 略不一致，可与上游一并抬到 23
- 第三方（ffmpeg / boringssl / libvpx / td …）全是 submodule — **必须** `git submodule update --init --recursive`
- CQ 分发优先 **少 ABI**，比手改编解码更划算

### 5. UI / 动画 / 渲染 — 观察项

- 自绘 UI 占比极高，RecyclerView 为内嵌副本 — 不要轻易换官方 androidx RecyclerView（已被 exclude）
- 性能问题优先用 Systrace / Macrobenchmark 对「打开对话 / 滚动聊天」取样，再定点优化
- 避免在热路径加额外模糊 / 阴影 / overdraw

### 6. 测试 / CI / 质量门禁 — 缺口大

- 有 `TMessagesProj_AppTests`，但侧载分叉通常无 CI
- **建议最小门禁：** PR 上 `assembleArm64Release` + `lint`（可 `checkReleaseBuilds false` 保持）
- 补一条「APK 包名 = `je.cq.tg`、versionName 非空」的 smoke 脚本

### 7. CQ 定制层与发布安全 — 已开始加固

| 风险 | 状态 |
| --- | --- |
| 包名 / 应用名与线上一致 | 已配置 |
| 更新 URL 仍指向 Play | 已改为 me.cq.je |
| Passkeys 在分叉上无效却开启 | 已关闭 |
| Play Billing 在侧载包上的噪音 | 已标不可用 |
| `APP_ID` / `APP_HASH` | 已写入 CQ 自有密钥（`BuildVars`） |
| `google-services.json` 含项目信息 | 勿把生产密钥提交到公开 fork；按需轮换 |
| 默认 keystore 密码 `android` | 仅本地调试；发布用 `local.properties` |

---

## 明确不建议现在做的事

- 全仓 Java 8 → 17 无回归地一刀切  
- 大改 JNI / 自建 ffmpeg  
- 删除 `TMessagesProj` 内嵌 RecyclerView 去「跟官方 androidx」  
- 在未理解 SMS Jobs 的情况下复制整份 Standalone 模块  

---

## 下一步（按性价比）

1. 打出 `CQ-arm64.apk`，真机安装对比体积与启动，再发布到 `me.cq.je`  
2. 关 Jetifier 试编译；失败再按依赖回退  
3. 加 GitHub Actions：submodule + arm64 assemble  
4. 跟随上游小版本（13.0.x），用 `scripts/sync-upstream.sh` 合并后重放本文件「已落地」清单  
