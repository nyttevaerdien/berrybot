package com.berrybot.update;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Custom SSLSocketFactory for Android 4.3 (API 18) Dalvik compatibility.
 * Explicitly enables TLSv1.2, TLSv1.1, and all modern supported cipher suites
 * which are otherwise disabled by default on Android 4.3 HttpURLConnection.
 */
public class TLSSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public TLSSocketFactory() throws KeyManagementException, NoSuchAlgorithmException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);
        this.delegate = context.getSocketFactory();
    }

    public TLSSocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return patchSocket(delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return patchSocket(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return patchSocket(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return patchSocket(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return patchSocket(delegate.createSocket(address, port, localAddress, localPort));
    }

    private Socket patchSocket(Socket socket) {
        if (socket instanceof SSLSocket) {
            SSLSocket sslSocket = (SSLSocket) socket;
            try {
                // Enable modern TLS protocols
                String[] supportedProtocols = sslSocket.getSupportedProtocols();
                List<String> enabledProtocols = new ArrayList<>();
                for (String p : supportedProtocols) {
                    if ("TLSv1.2".equals(p) || "TLSv1.1".equals(p) || "TLSv1".equals(p)) {
                        enabledProtocols.add(p);
                    }
                }
                if (!enabledProtocols.isEmpty()) {
                    sslSocket.setEnabledProtocols(enabledProtocols.toArray(new String[0]));
                }
            } catch (Throwable ignored) {}

            try {
                // Enable all supported cipher suites (enables ECDHE/AES-GCM suites on Android 4.3)
                String[] supportedCiphers = sslSocket.getSupportedCipherSuites();
                if (supportedCiphers != null && supportedCiphers.length > 0) {
                    sslSocket.setEnabledCipherSuites(supportedCiphers);
                }
            } catch (Throwable ignored) {}
        }
        return socket;
    }
}
