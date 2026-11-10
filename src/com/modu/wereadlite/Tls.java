package com.modu.wereadlite;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Android 4.2 专用 TLS 层：
 * 1) 强制 TLSv1.2（4.2 默认不开，服务器只认 1.2）；
 * 2) 内置 DigiCert Global Root G2（4.2 的 CA 库里没有），与系统 TrustManager 合并校验；
 * 3) 兼容模式：跳过证书校验（连不上时的兜底开关）。
 */
public final class Tls {

    /** DigiCert Global Root G2 (2013-08-01 ~ 2038-01-15)，DER 经 Base64 编码。 */
    private static final String ROOT_B64 =
            "MIIDjjCCAnagAwIBAgIQAzrx5qcRqaC7KGSxHQn65TANBgkqhkiG9w0BAQsFADBhMQswCQYDVQQG"
            + "EwJVUzEVMBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3d3cuZGlnaWNlcnQuY29tMSAw"
            + "HgYDVQQDExdEaWdpQ2VydCBHbG9iYWwgUm9vdCBHMjAeFw0xMzA4MDExMjAwMDBaFw0zODAxMTUx"
            + "MjAwMDBaMGExCzAJBgNVBAYTAlVTMRUwEwYDVQQKEwxEaWdpQ2VydCBJbmMxGTAXBgNVBAsTEHd3"
            + "dy5kaWdpY2VydC5jb20xIDAeBgNVBAMTF0RpZ2lDZXJ0IEdsb2JhbCBSb290IEcyMIIBIjANBgkq"
            + "hkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuzfNNNx7a8myaJCtSnX/RrohCgiN9RlUyfuI2/Ou8jqJ"
            + "kTx65qsGGmvPrC3oXgkkRLpimn7Wo6h+4FR1IAWsULecYxpsMNzaHxmx1x7e/dfgy5SDN67sH0NO"
            + "3Xss0r0upS/kqbitOtSZpLYl6ZtrAGCSYP9PIUkY92eQq2EGnI/yuum06ZIya7XzV+hdG82MHauV"
            + "BJVJ8zUtluNJbd134/tJS7SsVQepj5WztCO7TG1F8PapspUwtP1MVYwnSlcUfIKdzXOS0xZKBgyM"
            + "UNGPHgm+F6HmIcr9g+UQvIOlCsRnKPZzFBQ9RnbDhxSJITRNrw9FDKZJobq7nMWxM4MphQIDAQAB"
            + "o0IwQDAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBhjAdBgNVHQ4EFgQUTiJUIBiV5uNu"
            + "5g/6+rkS7QYXjzkwDQYJKoZIhvcNAQELBQADggEBAGBnKJRvDkhj6zHd6mcY1Yl9PMWLSn/pvtsr"
            + "F9+wX3N3KjITOYFnQoQj8kVnNeyIv/iPsGEMNKSuIEyExtv4NeF22d+mQrvHRAiGfzZ0JFrabA0U"
            + "WTW98kndth/Jsw1HKj2ZL7tcu7XUIOGZX1NGFdtom/DzMNU+MeKNhJ7jitralj41E6Vf8PlwUHBH"
            + "QRFXGU7Aj64GxJUTFy8bJZ918rGOmaFvE7FBcf6IKshPECBV1/MUReXgRPTqh5Uykw7+U0b6LJ3/"
            + "iyK5S9kJRaTepLiaWN0bfVKfjllDiIGknibVb63dDcY3fe0Dkhvld1927jyNxF1WW6LZZm6zNTfl"
            + "MrY=";

    private static final String[] CIPHERS = {
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_CBC_SHA",
    };

    private static SSLSocketFactory strict;
    private static SSLSocketFactory loose;

    private Tls() {
    }

    public static SSLSocketFactory get(boolean compat) {
        try {
            if (compat) {
                if (loose == null) loose = build(true);
                return loose;
            }
            if (strict == null) strict = build(false);
            return strict;
        } catch (Exception e) {
            throw new RuntimeException("TLS init failed: " + e);
        }
    }

    private static SSLSocketFactory build(boolean compat) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        TrustManager tm = compat ? new TrustAll() : new Merged(systemManager(), bundledManager());
        ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
        final SSLSocketFactory base = ctx.getSocketFactory();
        return new SSLSocketFactory() {
            @Override
            public String[] getDefaultCipherSuites() {
                return base.getDefaultCipherSuites();
            }

            @Override
            public String[] getSupportedCipherSuites() {
                return base.getSupportedCipherSuites();
            }

            @Override
            public Socket createSocket() throws IOException {
                return tune((SSLSocket) base.createSocket());
            }

            @Override
            public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
                return tune((SSLSocket) base.createSocket(s, host, port, autoClose));
            }

            @Override
            public Socket createSocket(String host, int port) throws IOException {
                return tune((SSLSocket) base.createSocket(host, port));
            }

            @Override
            public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                    throws IOException {
                return tune((SSLSocket) base.createSocket(host, port, localHost, localPort));
            }

            @Override
            public Socket createSocket(InetAddress host, int port) throws IOException {
                return tune((SSLSocket) base.createSocket(host, port));
            }

            @Override
            public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                    throws IOException {
                return tune((SSLSocket) base.createSocket(address, port, localAddress, localPort));
            }
        };
    }

    private static SSLSocket tune(SSLSocket socket) {
        try {
            List<String> protocols = Arrays.asList(socket.getSupportedProtocols());
            if (protocols.contains("TLSv1.2")) {
                socket.setEnabledProtocols(new String[]{"TLSv1.2"});
            }
        } catch (Exception ignore) {
        }
        try {
            Set<String> supported = new HashSet<String>(Arrays.asList(socket.getSupportedCipherSuites()));
            List<String> enable = new ArrayList<String>();
            for (String c : socket.getEnabledCipherSuites()) enable.add(c);
            for (String c : CIPHERS) {
                if (supported.contains(c) && !enable.contains(c)) enable.add(c);
            }
            socket.setEnabledCipherSuites(enable.toArray(new String[enable.size()]));
        } catch (Exception ignore) {
        }
        return socket;
    }

    private static X509TrustManager systemManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new IllegalStateException("no system trust manager");
    }

    private static X509TrustManager bundledManager() throws Exception {
        byte[] der = android.util.Base64.decode(ROOT_B64, android.util.Base64.DEFAULT);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        final X509Certificate root =
                (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new CertificateException("client cert not supported");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("empty chain");
                try {
                    PKIXParameters params =
                            new PKIXParameters(Collections.singleton(new TrustAnchor(root, null)));
                    params.setRevocationEnabled(false);
                    CertPath path = CertificateFactory.getInstance("X.509")
                            .generateCertPath(new ArrayList<X509Certificate>(Arrays.asList(chain)));
                    CertPathValidator.getInstance("PKIX").validate(path, params);
                } catch (Exception e) {
                    throw new CertificateException("bundled root rejected: " + e);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[]{root};
            }
        };
    }

    /** 先走系统 CA，失败再走内置根证书。 */
    private static final class Merged implements X509TrustManager {
        private final X509TrustManager system;
        private final X509TrustManager bundled;

        Merged(X509TrustManager system, X509TrustManager bundled) {
            this.system = system;
            this.bundled = bundled;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            system.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException first) {
                bundled.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] a = system.getAcceptedIssuers();
            X509Certificate[] b = bundled.getAcceptedIssuers();
            X509Certificate[] out = new X509Certificate[a.length + b.length];
            System.arraycopy(a, 0, out, 0, a.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return out;
        }
    }

    /** 兼容模式：不校验证书（仅用户手动打开时生效）。 */
    private static final class TrustAll implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
