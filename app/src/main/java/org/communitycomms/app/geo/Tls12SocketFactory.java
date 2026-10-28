package org.communitycomms.app.geo;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Android 4.1–4.4 support TLS 1.2 but only switch on TLS 1.0 by default, which today's tile
 * servers refuse ("SSL handshake aborted"). This wraps the default factory and turns 1.1 and 1.2
 * on for every socket. Android 5+ does this already, so it is only used below API 20.
 */
public final class Tls12SocketFactory extends SSLSocketFactory {
    private static SSLSocketFactory instance;
    private final SSLSocketFactory base;

    private Tls12SocketFactory(SSLSocketFactory base) {
        this.base = base;
    }

    /** Applies to an HTTPS connection on old Android; does nothing on 5.0+. */
    public static void apply(java.net.HttpURLConnection c) {
        if (android.os.Build.VERSION.SDK_INT >= 20 || !(c instanceof javax.net.ssl.HttpsURLConnection)) return;
        try {
            synchronized (Tls12SocketFactory.class) {
                if (instance == null) {
                    SSLContext ctx = SSLContext.getInstance("TLSv1.2");
                    ctx.init(null, null, null);
                    instance = new Tls12SocketFactory(ctx.getSocketFactory());
                }
            }
            ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(instance);
        } catch (Exception e) {
            // no TLS 1.2 on this build: fall back to the default
            android.util.Log.w("Tls12", "TLS 1.2 not available: " + e);
        }
    }

    private static Socket enable(Socket s) {
        if (s instanceof SSLSocket) {
            SSLSocket ss = (SSLSocket) s;
            ss.setEnabledProtocols(new String[]{"TLSv1", "TLSv1.1", "TLSv1.2"});

        }
        return s;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return base.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return base.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return enable(base.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return enable(base.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return enable(base.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return enable(base.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return enable(base.createSocket(address, port, localAddress, localPort));
    }
}
