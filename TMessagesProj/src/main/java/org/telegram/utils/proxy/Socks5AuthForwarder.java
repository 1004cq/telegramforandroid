package org.telegram.utils.proxy;

import android.text.TextUtils;

import org.telegram.messenger.FileLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local SOCKS5 server (no auth) that forwards to an upstream SOCKS5 server
 * with username/password. Used so Chromium WebView ProxyController can talk
 * to authenticated SOCKS5 proxies (ProxyConfig has no credential fields).
 */
final class Socks5AuthForwarder {
    private static final int METHOD_NO_AUTH = 0x00;
    private static final int METHOD_USER_PASS = 0x02;
    private static final int CMD_CONNECT = 0x01;
    private static final int ATYP_IPV4 = 0x01;
    private static final int ATYP_DOMAIN = 0x03;
    private static final int ATYP_IPV6 = 0x04;
    private static final int REPLY_SUCCESS = 0x00;
    private static final int REPLY_FAILURE = 0x01;
    private static final int MAX_STREAMS = 64;

    private final String upstreamHost;
    private final int upstreamPort;
    private final String username;
    private final String password;
    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    Socks5AuthForwarder(String upstreamHost, int upstreamPort, String username, String password) throws IOException {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.username = username != null ? username : "";
        this.password = password != null ? password : "";
        this.serverSocket = new ServerSocket(0, MAX_STREAMS, InetAddress.getByName("127.0.0.1"));
    }

    int getLocalPort() {
        return serverSocket.getLocalPort();
    }

    void start() {
        executor.execute(this::acceptLoop);
    }

    void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            serverSocket.close();
        } catch (Exception ignore) {
        }
        executor.shutdownNow();
    }

    private void acceptLoop() {
        while (!stopped.get()) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                executor.execute(() -> handleClient(client));
            } catch (Exception e) {
                if (!stopped.get()) {
                    FileLog.e(e);
                }
                return;
            }
        }
    }

    private void handleClient(Socket client) {
        Socket upstream = null;
        try {
            InputStream clientIn = client.getInputStream();
            OutputStream clientOut = client.getOutputStream();

            if (!handshakeNoAuth(clientIn, clientOut)) {
                return;
            }

            byte[] requestHeader = readFully(clientIn, 4);
            if (requestHeader[0] != 0x05 || requestHeader[1] != CMD_CONNECT) {
                writeConnectReply(clientOut, REPLY_FAILURE);
                return;
            }
            Destination destination = readDestination(clientIn, requestHeader[3] & 0xFF);
            if (destination == null) {
                writeConnectReply(clientOut, REPLY_FAILURE);
                return;
            }

            upstream = connectUpstream(destination);
            if (upstream == null) {
                writeConnectReply(clientOut, REPLY_FAILURE);
                return;
            }
            writeConnectReply(clientOut, REPLY_SUCCESS);

            Socket up = upstream;
            executor.execute(() -> pipe(client, up));
            pipe(up, client);
        } catch (Exception e) {
            if (!stopped.get()) {
                FileLog.e(e);
            }
        } finally {
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    private boolean handshakeNoAuth(InputStream in, OutputStream out) throws IOException {
        int version = in.read();
        int nMethods = in.read();
        if (version != 0x05 || nMethods <= 0) {
            return false;
        }
        readFully(in, nMethods);
        out.write(new byte[]{0x05, METHOD_NO_AUTH});
        out.flush();
        return true;
    }

    private Socket connectUpstream(Destination destination) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(upstreamHost, upstreamPort), 15000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            out.write(new byte[]{0x05, 0x01, METHOD_USER_PASS});
            out.flush();
            byte[] methodReply = readFully(in, 2);
            if (methodReply[0] != 0x05 || (methodReply[1] & 0xFF) != METHOD_USER_PASS) {
                closeQuietly(socket);
                return null;
            }

            byte[] userBytes = username.getBytes(StandardCharsets.UTF_8);
            byte[] passBytes = password.getBytes(StandardCharsets.UTF_8);
            if (userBytes.length > 255 || passBytes.length > 255) {
                closeQuietly(socket);
                return null;
            }
            byte[] auth = new byte[3 + userBytes.length + passBytes.length];
            auth[0] = 0x01;
            auth[1] = (byte) userBytes.length;
            System.arraycopy(userBytes, 0, auth, 2, userBytes.length);
            auth[2 + userBytes.length] = (byte) passBytes.length;
            System.arraycopy(passBytes, 0, auth, 3 + userBytes.length, passBytes.length);
            out.write(auth);
            out.flush();
            byte[] authReply = readFully(in, 2);
            if ((authReply[1] & 0xFF) != 0x00) {
                closeQuietly(socket);
                return null;
            }

            writeConnectRequest(out, destination);
            byte[] connectReply = readFully(in, 4);
            if (connectReply[0] != 0x05 || (connectReply[1] & 0xFF) != REPLY_SUCCESS) {
                closeQuietly(socket);
                return null;
            }
            skipAddress(in, connectReply[3] & 0xFF);
            return socket;
        } catch (Exception e) {
            FileLog.e(e);
            closeQuietly(socket);
            return null;
        }
    }

    private static void writeConnectRequest(OutputStream out, Destination destination) throws IOException {
        if (destination.atyp == ATYP_DOMAIN) {
            byte[] host = destination.host.getBytes(StandardCharsets.UTF_8);
            byte[] packet = new byte[4 + 1 + host.length + 2];
            packet[0] = 0x05;
            packet[1] = CMD_CONNECT;
            packet[2] = 0x00;
            packet[3] = ATYP_DOMAIN;
            packet[4] = (byte) host.length;
            System.arraycopy(host, 0, packet, 5, host.length);
            packet[5 + host.length] = (byte) ((destination.port >> 8) & 0xFF);
            packet[6 + host.length] = (byte) (destination.port & 0xFF);
            out.write(packet);
        } else if (destination.atyp == ATYP_IPV4) {
            byte[] packet = new byte[10];
            packet[0] = 0x05;
            packet[1] = CMD_CONNECT;
            packet[2] = 0x00;
            packet[3] = ATYP_IPV4;
            System.arraycopy(destination.address, 0, packet, 4, 4);
            packet[8] = (byte) ((destination.port >> 8) & 0xFF);
            packet[9] = (byte) (destination.port & 0xFF);
            out.write(packet);
        } else if (destination.atyp == ATYP_IPV6) {
            byte[] packet = new byte[22];
            packet[0] = 0x05;
            packet[1] = CMD_CONNECT;
            packet[2] = 0x00;
            packet[3] = ATYP_IPV6;
            System.arraycopy(destination.address, 0, packet, 4, 16);
            packet[20] = (byte) ((destination.port >> 8) & 0xFF);
            packet[21] = (byte) (destination.port & 0xFF);
            out.write(packet);
        } else {
            throw new IOException("unsupported atyp");
        }
        out.flush();
    }

    private static Destination readDestination(InputStream in, int atyp) throws IOException {
        Destination destination = new Destination();
        destination.atyp = atyp;
        if (atyp == ATYP_IPV4) {
            destination.address = readFully(in, 4);
            destination.host = InetAddress.getByAddress(destination.address).getHostAddress();
        } else if (atyp == ATYP_DOMAIN) {
            int len = in.read();
            if (len <= 0) {
                return null;
            }
            byte[] host = readFully(in, len);
            destination.host = new String(host, StandardCharsets.UTF_8);
        } else if (atyp == ATYP_IPV6) {
            destination.address = readFully(in, 16);
            destination.host = InetAddress.getByAddress(destination.address).getHostAddress();
        } else {
            return null;
        }
        byte[] portBytes = readFully(in, 2);
        destination.port = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);
        return destination;
    }

    private static void skipAddress(InputStream in, int atyp) throws IOException {
        if (atyp == ATYP_IPV4) {
            readFully(in, 4 + 2);
        } else if (atyp == ATYP_DOMAIN) {
            int len = in.read();
            if (len > 0) {
                readFully(in, len + 2);
            }
        } else if (atyp == ATYP_IPV6) {
            readFully(in, 16 + 2);
        }
    }

    private static void writeConnectReply(OutputStream out, int reply) throws IOException {
        out.write(new byte[]{
                0x05, (byte) reply, 0x00, ATYP_IPV4,
                0, 0, 0, 0,
                0, 0
        });
        out.flush();
    }

    private static void pipe(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                out.write(buffer, 0, count);
                out.flush();
            }
        } catch (Exception ignore) {
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(buffer, offset, length - offset);
            if (count < 0) {
                throw new IOException("unexpected eof");
            }
            offset += count;
        }
        return buffer;
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception ignore) {
        }
    }

    private static final class Destination {
        int atyp;
        String host;
        byte[] address;
        int port;
    }

    static boolean needsAuth(ProxySettings settings) {
        return settings != null
                && settings.getType() == ProxySettings.Type.SOCKS5
                && (!TextUtils.isEmpty(settings.getUser()) || !TextUtils.isEmpty(settings.getPassword()));
    }
}
