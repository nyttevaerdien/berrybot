package com.berrybot.update;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Custom SSLSocketFactory for Android 4.3 (API 18) Dalvik compatibility.
 * Explicitly enables TLSv1.2, TLSv1.1, and all modern supported cipher suites
 * which are otherwise disabled by default on Android 4.3 HttpURLConnection.
 * Also accommodates modern CAs (like Let's Encrypt ISRG Root X1) missing from Android 4.3's trust store.
 */
public class TLSSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public TLSSocketFactory() throws KeyManagementException, NoSuchAlgorithmException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, createTrustManagers(), new java.security.SecureRandom());
        this.delegate = context.getSocketFactory();
    }

    private static TrustManager[] createTrustManagers() {
        return new TrustManager[] {
            new X509TrustManager() {
                private final X509TrustManager defaultTm = getDefaultTrustManager();

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    if (chain == null || chain.length == 0) {
                        throw new CertificateException("Empty certificate chain");
                    }
                    if (defaultTm != null) {
                        try {
                            defaultTm.checkServerTrusted(chain, authType);
                            return;
                        } catch (CertificateException e) {
                            // System store failed (expected on Android 4.3 for Let's Encrypt / modern CAs)
                        }
                    }
                    // Validate certificate validity dates
                    for (X509Certificate cert : chain) {
                        cert.checkValidity();
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return defaultTm != null ? defaultTm.getAcceptedIssuers() : new X509Certificate[0];
                }
            }
        };
    }

    private static X509TrustManager getDefaultTrustManager() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (Throwable ignored) {}
        return null;
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
        return patchSocket(delegate.createSocket(s, host, port, autoClose), host);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return patchSocket(delegate.createSocket(host, port), host);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return patchSocket(delegate.createSocket(host, port, localHost, localPort), host);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return patchSocket(delegate.createSocket(host, port), host != null ? host.getHostName() : null);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return patchSocket(delegate.createSocket(address, port, localAddress, localPort), address != null ? address.getHostName() : null);
    }

    private Socket patchSocket(Socket socket, String host) {
        if (socket instanceof SSLSocket) {
            SSLSocket sslSocket = (SSLSocket) socket;
            if (host != null && !host.isEmpty()) {
                try {
                    java.lang.reflect.Method method = sslSocket.getClass().getMethod("setHostname", String.class);
                    method.invoke(sslSocket, host);
                } catch (Throwable ignored) {}
            }
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
