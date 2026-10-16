package org.telegram.utils.proxy;

import android.os.Build;
import android.os.Looper;
import android.text.TextUtils;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Routes Chromium WebView / in-app browser traffic through the same proxy
 * configured for the Telegram client ({@link SharedConfig#currentProxy}).
 *
 * <p>Uses {@link ProxyController} (process-wide). Supported for SOCKS5.
 * MTPROTO / WEB proxies cannot carry arbitrary HTTP(S) for WebView, so those
 * clear any previous WebView override (Telegram protocol still uses them via
 * {@link org.telegram.tgnet.ConnectionsManager}).
 */
public final class WebViewProxyHelper {
    private static final Object lock = new Object();
    private static final AtomicReference<String> appliedKey = new AtomicReference<>("");
    private static Socks5AuthForwarder authForwarder;
    private static boolean applied;
    private static volatile ProxySettings socksForJavaConnections;

    private WebViewProxyHelper() {
    }

    /** Apply or clear WebView proxy to match the current client proxy setting. */
    public static void syncFromSharedConfig() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread(WebViewProxyHelper::syncFromSharedConfig);
            return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            FileLog.d("WebViewProxyHelper: PROXY_OVERRIDE not supported on this WebView");
            return;
        }

        ProxySettings settings = null;
        if (SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null) {
            settings = SharedConfig.currentProxy.settings;
        }
        apply(settings);
    }

    /**
     * Apply WebView proxy for the given settings (or clear when null / unsupported).
     * Called from {@link org.telegram.tgnet.ConnectionsManager#setProxySettings}.
     */
    public static void apply(ProxySettings settings) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final ProxySettings copy = settings;
            AndroidUtilities.runOnUIThread(() -> apply(copy));
            return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            return;
        }

        final String key = keyFor(settings);
        if (key.equals(appliedKey.get()) && applied) {
            return;
        }

        if (settings == null || !settings.isValid() || settings.getType() != ProxySettings.Type.SOCKS5) {
            clearInternal("unsupported-or-disabled type=" + (settings == null ? "null" : settings.getType()));
            return;
        }

        try {
            final String proxyRule;
            synchronized (lock) {
                stopAuthForwarderLocked();
                socksForJavaConnections = settings;
                installSocksAuthenticator(settings);
                if (Socks5AuthForwarder.needsAuth(settings)) {
                    authForwarder = new Socks5AuthForwarder(
                            settings.getAddress(),
                            settings.getPort(),
                            settings.getUser(),
                            settings.getPassword());
                    authForwarder.start();
                    proxyRule = "socks://" + "127.0.0.1:" + authForwarder.getLocalPort();
                } else {
                    proxyRule = "socks://" + formatHostPort(settings.getAddress(), settings.getPort());
                }
            }

            ProxyConfig.Builder builder = new ProxyConfig.Builder()
                    .addProxyRule(proxyRule)
                    .addBypassRule("localhost")
                    .addBypassRule("127.0.0.1")
                    .addBypassRule("[::1]")
                    .addBypassRule("<local>");

            Executor executor = AndroidUtilities::runOnUIThread;
            ProxyController.getInstance().setProxyOverride(builder.build(), executor, () -> {
                appliedKey.set(key);
                applied = true;
                FileLog.d("WebViewProxyHelper: applied " + proxyRule);
            });
        } catch (Exception e) {
            FileLog.e(e);
            clearInternal("apply-failed");
        }
    }

    public static void clear() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread(WebViewProxyHelper::clear);
            return;
        }
        clearInternal("clear");
    }

    private static void clearInternal(String reason) {
        synchronized (lock) {
            stopAuthForwarderLocked();
            socksForJavaConnections = null;
            installSocksAuthenticator(null);
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            applied = false;
            appliedKey.set("");
            return;
        }
        if (!applied && TextUtils.isEmpty(appliedKey.get())) {
            return;
        }
        try {
            Executor executor = AndroidUtilities::runOnUIThread;
            ProxyController.getInstance().clearProxyOverride(executor, () -> {
                applied = false;
                appliedKey.set("");
                FileLog.d("WebViewProxyHelper: cleared (" + reason + ")");
            });
        } catch (Exception e) {
            FileLog.e(e);
            applied = false;
            appliedKey.set("");
        }
    }

    private static void stopAuthForwarderLocked() {
        if (authForwarder != null) {
            authForwarder.stop();
            authForwarder = null;
        }
    }

    private static String keyFor(ProxySettings settings) {
        if (settings == null || !settings.isValid()) {
            return "off";
        }
        return settings.getType() + "|" + settings.getAddress() + "|" + settings.getPort()
                + "|" + settings.getUser() + "|" + settings.getPassword() + "|" + settings.getSecret();
    }

    private static String formatHostPort(String host, int port) {
        if (host != null && host.contains(":") && !host.startsWith("[")) {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }

    /** Whether the current device WebView can honor SOCKS proxy overrides. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 21 && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
    }

    /**
     * Open an HTTP(S) connection using the current client SOCKS5 proxy when enabled.
     * Used by Java-side WebView intercept paths that do not go through Chromium.
     */
    public static HttpURLConnection openConnection(URL url) throws IOException {
        ProxySettings settings = socksForJavaConnections;
        if (settings == null && SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null) {
            settings = SharedConfig.currentProxy.settings;
        }
        if (settings != null && settings.isValid() && settings.getType() == ProxySettings.Type.SOCKS5) {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(settings.getAddress(), settings.getPort()));
            return (HttpURLConnection) url.openConnection(proxy);
        }
        return (HttpURLConnection) url.openConnection();
    }

    private static void installSocksAuthenticator(ProxySettings settings) {
        if (settings != null && Socks5AuthForwarder.needsAuth(settings)) {
            final String user = settings.getUser();
            final char[] password = settings.getPassword().toCharArray();
            java.net.Authenticator.setDefault(new java.net.Authenticator() {
                @Override
                protected java.net.PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() != RequestorType.PROXY) {
                        return null;
                    }
                    return new java.net.PasswordAuthentication(user, password);
                }
            });
        } else {
            java.net.Authenticator.setDefault(null);
        }
    }
}
