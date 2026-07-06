import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.security.cert.*;
import javax.net.ssl.*;
import java.security.SecureRandom;
import java.security.KeyStore;
import java.security.KeyPair;
import java.math.BigInteger;
import java.security.cert.Certificate;
import java.util.Date;

/**
 * Socks5 Proxy + TLS Obfuscation (Pure JDK, Zero External Dependencies)
 *
 * Features:
 *   - Built-in self-signed TLS cert (generated at compile time or first run)
 *   - Socks5 protocol on top of TLS (encrypted traffic looks like HTTPS)
 *   - Process name hiding via /proc/self comm trick
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
public class Bootstrap
{
    // ─── Config ───
    private static final int DEFAULT_PORT = 25575;
    private static final int PIPE_TIMEOUT_MS = 30000;

    // ─── Self-Signed TLS Certificate ───
    private static final String CERT_SUBJECT = "CN=nginx,O=Self-Cert,C=US";
    private static final String CERT_STORE_PASS = "changeit";
    private static final String CERT_FILE = "tls_keystore.jks";

    private static SSLServerSocket createTLSServerSocket(int port) throws Exception {
        KeyStore ks = loadOrCreateKeystore();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, CERT_STORE_PASS.toCharArray());

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(), null, new SecureRandom());

        SSLServerSocket srv = (SSLServerSocket) sslCtx.getServerSocketFactory().createServerSocket(port);
        srv.setReuseAddress(true);
        // Prefer cipher suites that look like standard HTTPS
        srv.setEnabledCipherSuites(new String[]{
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384"
        });
        return srv;
    }

    private static KeyStore loadOrCreateKeystore() throws Exception {
        File f = new File(CERT_FILE);
        if (f.exists()) {
            try (FileInputStream fis = new FileInputStream(f)) {
                KeyStore ks = KeyStore.getInstance("JKS");
                ks.load(fis, CERT_STORE_PASS.toCharArray());
                return ks;
            }
        }
        // Generate self-signed cert
        KeyPair kp = generateKeyPair();
        X509Certificate cert = generateSelfSignedCert(kp);
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, CERT_STORE_PASS.toCharArray());
        ks.setKeyEntry("alias", kp.getPrivate(), CERT_STORE_PASS.toCharArray(),
                       new Certificate[]{cert});
        try (FileOutputStream fos = new FileOutputStream(f)) {
            ks.store(fos, CERT_STORE_PASS.toCharArray());
        }
        return ks;
    }

    private static KeyPair generateKeyPair() throws Exception {
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static X509Certificate generateSelfSignedCert(KeyPair kp) throws Exception {
        // Build a minimal X509 cert manually (avoids BouncyCastle dependency)
        // This is a simplified approach using Sun's internal classes
        sun.security.x509.X500Name owner = new sun.security.x509.X500Name(CERT_SUBJECT);
        sun.security.x509.X509CertInfo info = new sun.security.x509.X509CertInfo();
        info.set("version", new sun.security.x509.CertificateVersion(
            sun.security.x509.CertificateVersion.V3));
        info.set("serialNumber", new sun.security.x509.CertificateSerialNumber(
            new BigInteger(String.valueOf(System.currentTimeMillis()), 16)));
        info.set("validity", new sun.security.x509.CertificateValidity(
            new Date(System.currentTimeMillis() - 86400000L),
            new Date(System.currentTimeMillis() + 86400000L * 365)));
        info.set("key", new sun.security.x509.CertificateX509Key(kp.getPublic()));
        info.set("subject", new sun.security.x509.CertificateSubjectName(owner));
        info.set("issuer", new sun.security.x509.CertificateIssuerName(owner));
        info.set("algorithmID", new sun.security.x509.CertificateAlgorithmId(
            sun.security.x509.AlgorithmId.get("SHA256withRSA")));

        sun.security.x509.X509CertImpl cert = new sun.security.x509.X509CertImpl(info);
        cert.sign(kp.getPrivate(), "SHA256withRSA");

        sun.security.x509.AlgorithmId algo = (sun.security.x509.AlgorithmId) cert.get("x509.algorithm");
        info.set("algorithmID.algorithm", algo);
        cert = new sun.security.x509.X509CertImpl(info);
        cert.sign(kp.getPrivate(), "SHA256withRSA");
        return cert;
    }

    // ─── Process Name Hiding ───
    private static void hideProcessName() {
        try {
            // Try to rename /proc/self/comm (works on Linux)
            // Common disguises: nginx, sshd, systemd, crond, java_update
            String disguise = "nginx";
            try (FileOutputStream fos = new FileOutputStream("/proc/self/comm")) {
                fos.write(disguise.getBytes());
            }
            // Also rename the executable symlink
            try {
                String exe = "/proc/self/exe";
                File f = new File(exe);
                if (f.exists() && f.canWrite()) {
                    File backup = new File("/tmp/.java_update_" + System.currentTimeMillis());
                    f.renameTo(backup);
                    f.createNewFile();
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            System.err.println("[WARN] Could not hide process name: " + e.getMessage());
        }
    }

    // ─── Main ───
    public static void main(String[] args) throws Exception
    {
        // Hide process name first
        hideProcessName();

        // Check Java version
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

        System.out.println("[*] Generating/loading TLS keystore...");
        // Trigger keystore creation/load
        SSLServerSocket srv = (SSLServerSocket) createTLSServerSocket(port);
        srv.close();
        System.out.println("[*] TLS keystore ready: " + new File(CERT_FILE).exists());

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
                // Client hello reveals the server name
                // Print for debugging
                System.out.println("[+] New connection from " + client.getRemoteSocketAddress());
                Thread t = new Thread(() -> handleTLSClient(client));
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (e instanceof java.net.SocketException) break; // closed
                System.err.println("[!] Accept error: " + e.getMessage());
            }
        }
    }

    // ─── Handle TLS-connected Socks5 Client ───
    private static void handleTLSClient(SSLSocket client) {
        try {
            client.setSoTimeout(PIPE_TIMEOUT_MS);
            InputStream  cin  = client.getInputStream();
            OutputStream cout = client.getOutputStream();

            // --- Socks5 Version ---
            int ver = cin.read();
            if (ver != 5) { closeSilently(client); return; }

            // --- Methods ---
            int nMethods = cin.read();
            if (nMethods <= 0) { closeSilently(client); return; }
            byte[] methods = new byte[nMethods];
            readFully(cin, methods);

            boolean needAuth       = !getConfig().getOrDefault("SOCKS5_USER", "").isEmpty();
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

            // --- Auth Handshake ---
            if (needAuth) {
                cout.write(new byte[]{0x05, 0x02}); cout.flush();
                if (cin.read() != 1) { closeSilently(client); return; }
                int uLen = cin.read();
                byte[] uBuf = new byte[uLen]; readFully(cin, uBuf);
                String uname = new String(uBuf);
                int pLen = cin.read();
                byte[] pBuf = new byte[pLen]; readFully(cin, pBuf);
                String passwd = new String(pBuf);

                String expUser = getConfig().getOrDefault("SOCKS5_USER", "");
                String expPass = getConfig().getOrDefault("SOCKS5_PASS", "");
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

            // --- Request ---
            if (cin.read() != 5) { closeSilently(client); return; }
            int cmd  = cin.read();
            cin.read(); // reserved
            int atyp = cin.read();

            if (cmd != 1) { // only CONNECT
                byte[] reject = {0x05, 0x07, 0x00, 0x01, 0,0,0,0, 0,0};
                cout.write(reject); cout.flush();
                closeSilently(client); return;
            }

            // --- Destination ---
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

            // --- Connect to target ---
            Socket target;
            try {
                target = new Socket();
                target.connect(new InetSocketAddress(destHost, destPort), 10000);
            } catch (Exception e) {
                byte[] reject = {0x05, 0x05, 0x00, 0x01, 0,0,0,0, 0,0};
                cout.write(reject); cout.flush();
                closeSilently(client); return;
            }

            // --- Reply ---
            byte[] localIP = ((InetSocketAddress) target.getLocalSocketAddress()).getAddress().getAddress();
            int localPort = target.getLocalPort();
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            reply.write(new byte[]{0x05, 0x00, 0x00, 0x01});
            reply.write(localIP);
            reply.write((localPort >> 8) & 0xFF);
            reply.write(localPort & 0xFF);
            cout.write(reply.toByteArray());
            cout.flush();

            // --- Pipe both directions ---
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

    // ─── Pipe Helper ───
    private static void pipe(InputStream in, OutputStream out, Closeable a, Closeable b) {
        try {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); out.flush(); }
        } catch (Exception ignored) {}
        finally {
            closeSilply(a);
            closeSilply(b);
        }
    }

    // ─── Utility ───
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

    private static void closeSilply(Closeable c) {
        try { c.close(); } catch (Exception ignored) {}
    }

    private static volatile Map<String, String> _cfg;
    private static Map<String, String> getConfig() {
        if (_cfg == null) _cfg = loadConfig();
        return _cfg;
    }

    // ─── Config Loader ───
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
                    cfg.put(p[0].trim(), p[1].trim().replaceAll("^['\"]|['\"]$", ""));
            }
        }
        return cfg;
    }
}
