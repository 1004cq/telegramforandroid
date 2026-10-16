# 应用内浏览器走客户端代理

目标：聊天里点击链接 → `Browser.openUrl` → 应用内 `ArticleViewer` / `BotWebViewContainer.MyWebView` 打开时，HTTP(S) 流量走 **设置里已启用的 SOCKS5 代理**。

---

## 1. 代码位置（打开链接链路）

| 步骤 | 文件 | 作用 |
| --- | --- | --- |
| 入口 | `TMessagesProj/.../messenger/browser/Browser.java` | `openUrl(...)`；`allowInAppBrowser` 为真时走应用内浏览器 |
| 打开 IV/网页 | `Browser.openInTelegramBrowser` → `ArticleViewer.open(url)` | 创建/复用底部浏览器页 |
| WebView | `ui/ArticleViewer.java`（`PageLayout`）创建 `BotWebViewContainer`（`bot=false`） |
| 真正的 WebView | `ui/web/BotWebViewContainer.java` → `MyWebView` | Chromium WebView；`setupWebView` 里创建 |
| 客户端代理配置 | `utils/proxy/ProxySettings.java` + `SharedConfig.currentProxy` | SOCKS5 / MTPROTO / WEB |
| MTProto 侧代理 | `tgnet/ConnectionsManager.setProxySettings` | 只影响 Telegram 协议，**原先不影响 WebView** |

关键判定（应用内打开）：

```java
// Browser.openUrl → inappBrowser
final boolean inappBrowser = allowInAppBrowser && ...
    && MessagesController.getInstance(currentAccount).isWebBrowserOpenInApp(uri.toString()) ...
openInTelegramBrowser(context, uri.toString(), inCaseLoading);
```

---

## 2. CQ 改动说明

Android WebView **不会**自动使用 `ConnectionsManager` 的 MTProto/SOCKS 设置。本分叉用 `androidx.webkit.ProxyController`（`PROXY_OVERRIDE`）给进程内所有 WebView 挂上 SOCKS 规则。

| 代理类型 | 应用内浏览器 |
| --- | --- |
| **SOCKS5**（无认证） | `socks://host:port` |
| **SOCKS5**（用户名/密码） | 本地 `Socks5AuthForwarder`（无认证）→ 上游带认证 SOCKS5 |
| **MTPROTO** | 不支持任意 HTTP；清除 WebView 覆盖（协议仍走 MTProto） |
| **WEB** | 仅隧道化 Telegram DC；清除 WebView 覆盖 |

系统 WebView 需支持 `WebViewFeature.PROXY_OVERRIDE`（较新的 Android System WebView / Chrome）。

---

## 3. 修改 / 新增的文件

| 文件 | 变更 |
| --- | --- |
| `utils/proxy/WebViewProxyHelper.java` | **新增**：同步 ProxyController + Java `HttpURLConnection` 出口 |
| `utils/proxy/Socks5AuthForwarder.java` | **新增**：本地 SOCKS5 认证转发 |
| `tgnet/ConnectionsManager.java` | `init` / `setProxySettings` 后调用 `WebViewProxyHelper` |
| `ui/web/BotWebViewContainer.java` | `setupWebView` 时 `syncFromSharedConfig()`；opener 首请求走 `openConnection` |
| `docs/WEBVIEW_PROXY_zh.md` | 本文档 |

---

## 4. 关键修改示例

**代理变更时同步 WebView（`ConnectionsManager.setProxySettings` 末尾）：**

```java
if (enabled && settings != null && settings.isValid()) {
    WebViewProxyHelper.apply(settings);
} else {
    WebViewProxyHelper.clear();
}
```

**创建应用内 WebView 时再确认一次（`BotWebViewContainer.setupWebView`）：**

```java
webView = replaceWith == null ? new MyWebView(...) : replaceWith;
WebViewProxyHelper.syncFromSharedConfig();
```

**ProxyController 核心（`WebViewProxyHelper.apply`）：**

```java
ProxyConfig config = new ProxyConfig.Builder()
    .addProxyRule("socks://" + host + ":" + port)  // 或 127.0.0.1:本地转发端口
    .addBypassRule("localhost")
    .addBypassRule("127.0.0.1")
    .build();
ProxyController.getInstance().setProxyOverride(config, executor, listener);
```

---

## 5. 重新编译与测试

```bash
# 依赖 / 密钥
git submodule update --init --recursive
cp local.properties.example local.properties   # sdk.dir、签名、TELEGRAM_API_*

# arm64 包（推荐）
./gradlew :TMessagesProj_App:assembleArm64Release

# 产物
# TMessagesProj_App/build/outputs/apk/arm64/release/
# 或仓库脚本复制到 dist/CQ-arm64.apk
```

**功能验证：**

1. 设置 → 代理 → 添加 **SOCKS5**，打开「使用代理」
2. 聊天中点开 `https://` 链接，确认用应用内浏览器（非系统浏览器 / Custom Tabs）
3. 在代理服务器侧看访问日志，或打开 `https://api.ipify.org` / `https://ifconfig.me` 核对出口 IP
4. 关掉代理后刷新，出口应恢复直连
5. （可选）带用户名密码的 SOCKS5 再测一遍

**注意：**

- 设置里需开启「在应用内打开链接」类选项（`isWebBrowserOpenInApp`），否则会走系统浏览器，无法被 ProxyController 接管
- MTPROTO / WEB 代理下，应用内网页仍可能直连；网页场景请用 SOCKS5
- Custom Tabs / 外部浏览器不在本方案范围内

---

## 上游同步提示

合并 DrKLO/Telegram 后若改动了 `ConnectionsManager.setProxySettings` 或 `BotWebViewContainer.setupWebView`，请重新挂上 `WebViewProxyHelper` 调用。
