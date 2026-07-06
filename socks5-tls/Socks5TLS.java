import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import javax.net.ssl.*;

/**
 * Socks5 Proxy + TLS Obfuscation (Pure JDK, Zero External Dependencies)
 *
 * Features:
 *   - Embedded self-signed TLS cert (no runtime generation needed)
 *   - Socks5 protocol over TLS (encrypted traffic looks like HTTPS)
 *   - Process name hiding via /proc/self/comm trick
 *   - Configurable port, auth credentials
 *
 * Usage:
 *   javac Socks5TLS.java
 *   java Socks5TLS
 *
 * Env vars:
 *   SOCKS5_PORT     - listen port (default 25575)
 *   SOCKS5_USER     - username (empty = no auth)
 *   SOCKS5_PASS     - password
 *   NODE_HOST       - displayed hostname
 */
class Socks5TLS
{
    // --- Config ---
    private static final int DEFAULT_PORT = 25575;
    private static final int PIPE_TIMEOUT_MS = 30000;

    // --- Embedded PEM Certificate (RSA 2048, CN=nginx, valid 10 years) ---
    private static final String PEM_CERT = "MIIDczCCAlugAwIBAgIUZNK7gM3jTuDNQYc5tLZn2o4f50AwDQYJKoZIhvcNAQEL\nBQAwSTELMAkGA1UEBhMCVVMxEzARBgNVBAgMClNvbWUtU3RhdGUxFTATBgNVBAoM\nDE9yZ2FuaXphdGlvbjEOMAwGA1UEAwwFbmdpbngwHhcNMjYwNzA2MDkwMjM4WhcN\nMzYwNzAzMDkwMjM4WjBJMQswCQYDVQQGEwJVUzETMBEGA1UECAwKU29tZS1TdGF0\nZTEVMBMGA1UECgwMT3JnYW5pemF0aW9uMQ4wDAYDVQQDDAVuZ2lueDCCASIwDQYJ\nKoZIhvcNAQEBBQADggEPADCCAQoCggEBANNOhIr3E1RUGLXb94lCydpF+rImlxRr\njp+QcJYYdNFzoz8nuiK2bIlH1SgWwOxOHs5q8xdV0Cc04cYa4DQjZaj/3Rvbu2SC\n5drwYxRLFR+YfeSOYkqmGixaKjPiEgVkpyBrdrUhMZ9wtTcGX7z423Hz1zsiQyvY\ngq0v93VuLezV2s9hKslLWoc3ZNQRblmJLmq2i1WukG3ty2uVUDQFML/Hj8nUk5uz\nDuBCNCTYPzYRLSVvOY+LXmXYtd2BQ6IdZuos01PdPx6gA2kS+Pllcsmo55OYhXEi\n9a+rwilRVTuyNX9lqRqLoweWOcpSib2m58Gy82m9H4ci4fqZAHxkkFcCAwEAAaNT\nMFEwHQYDVR0OBBYEFJZX9rSZsEhsJIlBgXWFshbKw66EMB8GA1UdIwQYMBaAFJZX\n9rSZsEhsJIlBgXWFshbKw66EMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQEL\nBQADggEBAATg+CSBM4Suw9eFj/2M8aQfaZlWok8V1oaIhv5tPR7faT7U3Kug8/7+\nTObDY7n2eeWEwRL/WV0o421rDlbXw3U69pkcqNUiOVf84K2J1eiVkAbYYGlKjqzj\nGjmkmUkcWLtZuSn7/DZ4t317pweztkEPnIwlYbKjpLccb2O/muRehXcTEPeTs0mm\nkwcjEgmfL8wl5Wk8bWCSYEQWT5SF8F6d3Eh101QJ5tL5+NOGDoTgyXOCFlsjeIZ0\nxNTyk2kGTLARwgtOKLK7VMIO+Nof/jGJWVMvhHGgKZC2+Xgpfa8DJ1xCRbhp20dO\n3rVQk4FOBOkJW4BC6d51HwO/kkaEeZM=\n";

    private static final String PEM_KEY  = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDTToSK9xNUVBi1\n2/eJQsnaRfqyJpcUa46fkHCWGHTRc6M/J7oitmyJR9UoFsDsTh7OavMXVdAnNOHG\nGuA0I2Wo/90b27tkguXa8GMUSxUfmH3kjmJKphosWioz4hIFZKcga3a1ITGfcLU3\nBl+8+Ntx89c7IkMr2IKtL/d1bi3s1drPYSrJS1qHN2TUEW5ZiS5qtotVrpBt7ctr\nlVA0BTC/x4/J1JObsw7gQjQk2D82ES0lbzmPi15l2LXdgUOiHWbqLNNT3T8eoANp\nEvj5ZXLJqOeTmIVxIvWvq8IpUVU7sjV/Zakai6MHljnKUom9pufBsvNpvR+HIuH6\nmQB8ZJBXAgMBAAECggEADwiQsDYPdRD0nxsXUne+VovQu2+UI2P/T07DFPc/LrNe\nrLOHFJJw/2DIulGAGm/UHkK/OzW2Ph4hQkazpz+tvYvSnUKRUUiyiDAH8kIqHDVt\naBYIXA2uhuFsMITsXARMJaevoSxdwhRFOEdUmWROTIv0a4BBCQYMBXOaix2gRh1B\nzzq8Owo5d7tAHVQv1bchCz0RfohD31iriXt+BU2ILuhgAzyhmMlmjXVA0uA5v7JM\nPeDvzXtbjnLyIwltpat6bTJrNFeHNcN5/iqRZ5Rv25R5FSVFWkVwH6IQHpVlHK5n\nPbouZB+eLzp5vRMpWugKrjQa6zeuzwJ8UEb5cQKBgQDvV5FRF0m/sdGlK1IbLdzN\n5w8y5q0PNhfoqGbDVfI8X37AIOWlDs5gyoRk3S8Yt5L+J+S8kzspVDnDzX5kvkC+\no3i3lSGtAFXvBoHXrcgEnps1omz7OCoZf4AQiY7NR5R3KOgvLc4goyojSLJPT3un\nfafMWLx5VGhxMyNQPwfJRwKBgQDiA297wh2H+vCtXyKhWSLgxkUr2X2bG3eW/mQK\n8X7dvzwO31HxDJHpPRVpQOsMOBpHKxfr8RldCzQ1ifw9+09321e78FnvuFatZ0tN\nDuQkt6wuKJr+tSmOevZLEgKCJTAwQq44U4ubP01oJ2GvngYQn+SFxGEhGIyH9zjk\nNEGIcQKBgQDihCTS81Bn7Vn1kRdnA7PK51haGzlEgTSFjAOd8VSN0O871Kai3W1y\n65f7gd4V7X9frM/trQY76iu1ZWGu5OSPyFTyomC5w+yQiL8QKbd4r8dDLpMn+5LU\niPfiLt4I6CrZz8xXAmnoN6QkuqOPLjFgZisN2hmeVsV2BSjxxIWQ9wKBgFjdUPAw\nGrxkhk0kotEd4wDN9FSRZzmdSyArVdqXqXI2xr5yQB2u+4/hXJHN3J0pUeu5neY/\nHeHfjd+fKXaVYWGW9KAImNQQfsQfYRQjTsDBFwnvHUIYqQZEgqJxqlrRlGjlTusG\nrlWURjM1iMssLuZKd+fAlxAUPu0W31+azEmBAoGAFB6ffjZLOyfJ0pbgW4e0zWfw\nZD9SPhMONkWaca9meaJagxr8A4XflR35cn79aPldxma7W5A0sr/Zd8EX2oEAvXxj\n2psqObFo1lxhBhPJ7zzdWQnX8mLuP1zUHgDPxjh26ZYW05CYJ7U8MrhjrUYlP9\nifUIHTWitye2J3jTCx0=\n";

    // --- Keystore ---
    private static final String KEYSTORE_FILE = "tls_keystore.p12";
    private static final String KEYSTORE_PASS = "changeit";

    private static KeyStore buildKeystore() throws Exception {
        File kf = new File(KEYSTORE_FILE);
        if (kf.exists()) {
            try (FileInputStream fis = new FileInputStream(kf)) {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(fis, KEYSTORE_PASS.toCharArray());
                return ks;
            }
        }

        // Parse PEM cert
        String certBody = PEM_CERT.replaceAll("\\s+", "");
        byte[] certBytes = Base64.getDecoder().decode(certBody);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(
            new ByteArrayInputStream(certBytes));

        // Parse PEM private key
        String keyBody = PEM_KEY.replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(keyBody);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf2 = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = kf2.generatePrivate(keySpec);

        // Create PKCS12 keystore
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("alias", privateKey, KEYSTORE_PASS.toCharArray(),
                       new java.security.cert.Certificate[]{cert});

        // Save for future runs
        try (FileOutputStream fos = new FileOutputStream(kf)) {
            ks.store(fos, KEYSTORE_PASS.toCharArray());
        }
        return ks;
    }

    private static SSLServerSocket createTLSServerSocket(int port) throws Exception {
        KeyStore ks = buildKeystore();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PASS.toCharArray());

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(), null, new SecureRandom());

        SSLServerSocket srv = (SSLServerSocket) sslCtx.getServerSocketFactory().createServerSocket(port);
        srv.setReuseAddress(true);
        srv.setEnabledCipherSuites(new String[]{
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384"
        });
        return srv;
    }

    // --- Process Name Hiding ---
    private static void hideProcessName() {
        try {
            try (FileOutputStream fos = new FileOutputStream("/proc/self/comm")) {
                fos.write("nginx".getBytes());
            }
        } catch (Exception ignored) {}
    }

    // --- Main ---
    public static void main(String[] args) throws Exception {
        hideProcessName();

        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println("ERROR: Need Java 11+!");
            Thread.sleep(3000); System.exit(1);
        }

        Map<String, String> cfg = loadConfig();

        int    port = DEFAULT_PORT;
        String user = "";
        String pass = "";
        String host = "";

        try { port = Integer.parseInt(cfg.getOrDefault("SOCKS5_PORT", String.valueOf(DEFAULT_PORT)).trim()); }
        catch (Exception ignored) {}
        user = cfg.getOrDefault("SOCKS5_USER", "");
        pass = cfg.getOrDefault("SOCKS5_PASS", "");
        host = cfg.getOrDefault("NODE_HOST", "");

        if (host.isEmpty()) host = "YOUR_SERVER_IP";

        System.out.println("[*] Building TLS keystore...");
        KeyStore ks = buildKeystore();
        System.out.println("[*] Keystore ready: " + new File(KEYSTORE_FILE).exists());

        SSLServerSocket server = (SSLServerSocket) createTLSServerSocket(port);
        System.out.println("[+] Socks5+TLS listening on 0.0.0.0:" + port);
        System.out.println("[+] Auth: " + (user.isEmpty() ? "None" : "Enabled (" + user + ")"));
        System.out.println("[+] Traffic looks like HTTPS (TLS 1.3/1.2)");
        System.out.println("[+] Process name hidden (disguised as nginx)");
        System.out.println("===========================================");
        System.out.print("[+] socks5://");
        if (!user.isEmpty()) System.out.print(user + (pass.isEmpty() ? "" : ":" + pass) + "@");
        System.out.println(host + ":" + port);
        System.out.println("===========================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[*] Shutting down...");
            try { server.close(); } catch (Exception ignored) {}
        }));

        while (true) {
            try {
                SSLSocket client = (SSLSocket) server.accept();
                System.out.println("[+] New connection from " + client.getRemoteSocketAddress());
                Thread t = new Thread(() -> handleTLSClient(client));
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (e instanceof java.net.SocketException) break;
                System.err.println("[!] Accept error: " + e.getMessage());
            }
        }
    }

    // --- Handle TLS-connected Socks5 Client ---
    private static void handleTLSClient(SSLSocket client) {
        try {
            client.setSoTimeout(PIPE_TIMEOUT_MS);
            InputStream  cin  = client.getInputStream();
            OutputStream cout = client.getOutputStream();

            int ver = cin.read();
            if (ver != 5) { closeSilently(client); return; }

            int nMethods = cin.read();
            if (nMethods <= 0) { closeSilently(client); return; }
            byte[] methods = new byte[nMethods];
            readFully(cin, methods);

            Map<String, String> cfg = getConfig();
            boolean needAuth       = !cfg.getOrDefault("SOCKS5_USER", "").isEmpty();
            boolean supportsAuth   = contains(methods, (byte) 0x02);
            boolean supportsNoAuth = contains(methods, (byte) 0x00);

            if (needAuth && !supportsAuth) {
                cout.write(new byte[]{0x05, (byte)0xFF}); cout.flush();
                closeSilently(client); return;
            }
            if (!needAuth && !supportsNoAuth && !supportsAuth) {
                cout.write(new byte[]{0x05, (byte)0xFF}); cout.flush();
                closeSilently(client); return;
            }

            if (needAuth) {
                cout.write(new byte[]{0x05, 0x02}); cout.flush();
                if (cin.read() != 1) { closeSilently(client); return; }
                int uLen = cin.read();
                byte[] uBuf = new byte[uLen]; readFully(cin, uBuf);
                String uname = new String(uBuf);
                int pLen = cin.read();
                byte[] pBuf = new byte[pLen]; readFully(cin, pBuf);
                String passwd = new String(pBuf);

                String expUser = cfg.getOrDefault("SOCKS5_USER", "");
                String expPass = cfg.getOrDefault("SOCKS5_PASS", "");
                if (expUser.equals(uname) && expPass.equals(passwd)) {
                    cout.write(new byte[]{0x01, 0x00});
                } else {
                    cout.write(new byte[]{0x01, 0x01});
                    closeSilently(client); return;
                }
            } else {
                cout.write(new byte[]{0x05, 0x00});
            }
            cout.flush();

            if (cin.read() != 5) { closeSilently(client); return; }
            int cmd  = cin.read();
            cin.read();
            int atyp = cin.read();

            if (cmd != 1) {
                byte[] reject = {0x05, 0x07, 0x00, 0x01, 0,0,0,0, 0,0};
                cout.write(reject); cout.flush();
                closeSilently(client); return;
            }

            String destHost;
            if      (atyp == 0x01) { destHost = InetAddress.getByAddress(readNBytes(cin, 4)).getHostAddress(); }
            else if (atyp == 0x03) {
                int len = cin.read();
                destHost = new String(readNBytes(cin, len));
            }
            else if (atyp == 0x04) { destHost = InetAddress.getByAddress(readNBytes(cin, 16)).getHostAddress(); }
            else {
                byte[] reject = {0x05, 0x08, 0x00, 0x01, 0,0,0,0, 0,0};
                cout.write(reject); cout.flush();
                closeSilently(client); return;
            }
            int destPort = ((cin.read() & 0xFF) << 8) | (cin.read() & 0xFF);

            Socket target;
            try {
                target = new Socket();
                target.connect(new InetSocketAddress(destHost, destPort), 10000);
            } catch (Exception e) {
                byte[] reject = {0x05, 0x05, 0x00, 0x01, 0,0,0,0, 0,0};
                cout.write(reject); cout.flush();
                closeSilently(client); return;
            }

            byte[] localIP = ((InetSocketAddress) target.getLocalSocketAddress()).getAddress().getAddress();
            int localPort = target.getLocalPort();
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            reply.write(new byte[]{0x05, 0x00, 0x00, 0x01});
            reply.write(localIP);
            reply.write((localPort >> 8) & 0xFF);
            reply.write(localPort & 0xFF);
            cout.write(reply.toByteArray());
            cout.flush();

            client.setSoTimeout(0);
            target.setSoTimeout(0);
            InputStream  targetIn  = target.getInputStream();
            OutputStream targetOut = target.getOutputStream();

            Thread t1 = new Thread(() -> pipe(cin,      targetOut, client, target));
            Thread t2 = new Thread(() -> pipe(targetIn, cout,      target, client));
            t1.setDaemon(true); t2.setDaemon(true);
            t1.start(); t2.start();
            try { t1.join(); } catch (InterruptedException ignored) {}
            try { t2.join(); } catch (InterruptedException ignored) {}

        } catch (Exception e) {
            closeSilently(client);
        }
    }

    private static void pipe(InputStream in, OutputStream out, Closeable a, Closeable b) {
        try {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); out.flush(); }
        } catch (Exception ignored) {}
        finally {
            closeSilently(a);
            closeSilently(b);
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r == -1) throw new EOFException("Unexpected end of stream");
            off += r;
        }
    }

    private static byte[] readNBytes(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        readFully(in, buf);
        return buf;
    }

    private static boolean contains(byte[] arr, byte val) {
        for (byte b : arr) if (b == val) return true;
        return false;
    }

    private static void closeSilently(Closeable c) {
        try { c.close(); } catch (Exception ignored) {}
    }

    private static volatile Map<String, String> _cfg;
    private static Map<String, String> getConfig() throws IOException {
        if (_cfg == null) _cfg = loadConfig();
        return _cfg;
    }

    private static Map<String, String> loadConfig() throws IOException {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("SOCKS5_PORT",  String.valueOf(DEFAULT_PORT));
        cfg.put("SOCKS5_USER",  "");
        cfg.put("SOCKS5_PASS",  "");
        cfg.put("NODE_HOST",    "");

        for (String v : cfg.keySet()) {
            String ev = System.getenv(v);
            if (ev != null && !ev.trim().isEmpty()) cfg.put(v, ev.trim());
        }

        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) line = line.substring(7).trim();
                String[] p = line.split("=", 2);
                if (p.length == 2 && cfg.containsKey(p[0].trim()))
                    cfg.put(p[0].trim(), p[1].trim());
            }
        }
        return cfg;
    }
}
