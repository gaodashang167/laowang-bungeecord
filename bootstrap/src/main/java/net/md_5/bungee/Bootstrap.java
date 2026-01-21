package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Bootstrap
{
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";

    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process hy2Process;
    private static Process nezhaProcess;
    private static Process minecraftProcess;
    private static Thread keepaliveThread;
    
    private static final String[] ALL_ENV_VARS = {
        "UUID", "UDP_PORT", "DOMAIN", "HY2_PASSWORD", "HY2_OBFS_PASSWORD",
        "HY2_PORTS", "HY2_SNI", "HY2_ALPN",
        "NEZHA_SERVER", "NEZHA_PORT", "NEZHA_KEY", "NEZHA_TLS",
        "MC_JAR", "MC_MEMORY", "MC_ARGS", "MC_PORT",
        "MC_KEEPALIVE_HOST", "MC_KEEPALIVE_PORT",
        "FAKE_PLAYER_ENABLED", "FAKE_PLAYER_NAME"
    };

    public static void main(String[] args) throws Exception
    {
        try {
            Map<String, String> config = loadConfig();
            
            if (isMcServerEnabled(config)) startMinecraftServer(config);
            if (isNezhaConfigured(config)) runNezhaAgent(config);
            
            String hy2Url = generateHy2Url(config);
            System.out.println(ANSI_GREEN + "\n=== Hysteria2 Configuration ===" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Password: " + config.get("HY2_PASSWORD") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Obfs Password: " + config.getOrDefault("HY2_OBFS_PASSWORD", "(none)") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "UDP Port: " + config.get("UDP_PORT") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Domain/IP: " + config.get("DOMAIN") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "\n=== Hysteria2 Node URL ===" + ANSI_RESET);
            System.out.println(ANSI_GREEN + hy2Url + ANSI_RESET);
            System.out.println(ANSI_GREEN + "================================" + ANSI_RESET);
            
            runHysteria2Service(config);
            
            if (isFakePlayerBotEnabled(config)) {
                System.out.println(ANSI_YELLOW + "[FakePlayer] Waiting for MC server to fully start..." + ANSI_RESET);
                waitForServerReady();
                startFakePlayerBot(config);
            } else if (isMcKeepaliveEnabled(config)) {
                System.out.println(ANSI_YELLOW + "[MC-Keepalive] Waiting 60s for MC server to start..." + ANSI_RESET);
                Thread.sleep(60000);
                startMcKeepalive(config);
            }
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));
            
            // 保持主线程活跃
            while (running.get()) {
                try { Thread.sleep(10000); } catch (InterruptedException e) { break; }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void clearConsole() {
        try { System.out.print("\033[H\033[2J"); System.out.flush(); } catch (Exception ignored) {}
    }
    
    private static boolean isNezhaConfigured(Map<String, String> config) {
        String server = config.get("NEZHA_SERVER");
        String key = config.get("NEZHA_KEY");
        return server != null && !server.trim().isEmpty() && key != null && !key.trim().isEmpty();
    }
    
    private static void runNezhaAgent(Map<String, String> config) throws Exception {
        Path nezhaPath = downloadNezhaAgent();
        Path nezhaConfigPath = createNezhaConfig(config);
        
        Path nezhaDir = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-work");
        if (Files.exists(nezhaDir)) {
            try (Stream<Path> walk = Files.walk(nezhaDir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            } catch (Exception e) {}
        }
        Files.createDirectories(nezhaDir);
        
        System.out.println(ANSI_GREEN + "Starting Nezha Agent..." + ANSI_RESET);
        List<String> cmd = new ArrayList<>();
        cmd.add(nezhaPath.toString());
        cmd.add("-c");
        cmd.add(nezhaConfigPath.toString());
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(nezhaDir.toFile());
        pb.redirectErrorStream(true);
        nezhaProcess = pb.start();
        
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(nezhaProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("NEZHA") || line.contains("error") || line.contains("Error")) {
                        System.out.println(ANSI_GREEN + "[Nezha] " + ANSI_RESET + line);
                    }
                }
            } catch (IOException e) {}
        }).start();
    }
    
    private static void runHysteria2Service(Map<String, String> config) throws Exception {
        Path hy2Path = downloadHysteria2();
        String sni = config.getOrDefault("HY2_SNI", "www.bing.com");
        generateSelfSignedCert(sni);
        Path configPath = createHysteria2Config(config);
        
        System.out.println(ANSI_GREEN + "Starting Hysteria2 Server..." + ANSI_RESET);
        ProcessBuilder pb = new ProcessBuilder(hy2Path.toString(), "server", "-c", configPath.toString());
        pb.redirectErrorStream(true);
        hy2Process = pb.start();
        
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(hy2Process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("client connected") || line.contains("client disconnected") || line.contains("addr")) continue;
                    if (line.contains("ERROR") || line.contains("WARN") || line.contains("FATAL")) {
                        System.out.println(ANSI_YELLOW + "[Hysteria2] " + ANSI_RESET + line);
                    }
                }
            } catch (IOException e) {}
        }).start();
    }
    
    private static Map<String, String> loadConfig() {
        Map<String, String> config = new HashMap<>();
        // 默认配置
        config.put("UUID", "60cfb1d3-db11-4eae-9fa3-f04fba55576d");
        config.put("HY2_PASSWORD", "1f6b80fe-023a-4735-bafd-4c8512bf7e58");  
        config.put("HY2_OBFS_PASSWORD", "gfw-cant-see-me-2026");  // 【重要】启用混淆增强隐蔽性
        config.put("UDP_PORT", "25835");  // 单端口
        config.put("HY2_PORTS", "");  // 跳跃端口范围（可选）
        config.put("DOMAIN", "luminus.kingsnetwork.uk");
        config.put("HY2_SNI", "www.bing.com");  // TLS SNI - 伪装成访问必应
        config.put("HY2_ALPN", "h3");  // ALPN 协议
        config.put("NEZHA_SERVER", "mbb.svip888.us.kg:53100");
        config.put("NEZHA_PORT", "");
        config.put("NEZHA_KEY", "VnrTnhgoack6PhnRH6lyshe4OVkHmPyM");
        config.put("NEZHA_TLS", "false");
        // Minecraft 服务器配置
        config.put("MC_JAR", "server99.jar");  // MC 服务器 jar 文件名，如 "paper-1.19.4.jar"，留空则不启动
        config.put("MC_MEMORY", "512M");  // 默认分配 512MB 内存
        config.put("MC_ARGS", "");  // 额外 JVM 参数，如 "-XX:+UseG1GC"
        config.put("MC_PORT", "25835");  // MC 服务器端口（从环境变量读取）
        // Minecraft 保活配置 - 模拟玩家连接
        config.put("MC_KEEPALIVE_HOST", "");  // 留空禁用简单 ping
        config.put("MC_KEEPALIVE_PORT", "25835");
        // 真实假玩家配置（推荐）
        config.put("FAKE_PLAYER_ENABLED", "true");  // 启用真实假玩家
        config.put("FAKE_PLAYER_NAME", "labubu");  // 假玩家名称
        
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.isEmpty()) config.put(var, value);
        }
        return config;
    }
    
    private static String generateHy2Url(Map<String, String> config) {
        try {
            StringBuilder url = new StringBuilder("hysteria2://");
            String password = URLEncoder.encode(config.get("HY2_PASSWORD"), "UTF-8");
            url.append(password).append("@").append(config.get("DOMAIN")).append(":").append(config.get("UDP_PORT"));
            List<String> params = new ArrayList<>();
            String obfsPass = config.get("HY2_OBFS_PASSWORD");
            if (obfsPass != null && !obfsPass.trim().isEmpty()) {
                params.add("obfs=salamander");
                params.add("obfs-password=" + URLEncoder.encode(obfsPass, "UTF-8"));
            }
            params.add("sni=" + config.getOrDefault("HY2_SNI", "www.bing.com"));
            String ports = config.get("HY2_PORTS");
            if (ports != null && !ports.trim().isEmpty()) params.add("mport=" + URLEncoder.encode(ports, "UTF-8"));
            params.add("alpn=" + config.getOrDefault("HY2_ALPN", "h3"));
            params.add("insecure=1");
            if (!params.isEmpty()) url.append("?").append(String.join("&", params));
            url.append("#US-HostPapa");
            return url.toString();
        } catch (Exception e) { return ""; }
    }
    
    private static Path createHysteria2Config(Map<String, String> config) throws IOException {
        StringBuilder yaml = new StringBuilder();
        String ports = config.get("HY2_PORTS");
        if (ports != null && !ports.trim().isEmpty()) {
            yaml.append("listen: :").append(config.get("UDP_PORT")).append("\n");
            yaml.append("mport: ").append(ports).append("\n\n");
        } else {
            yaml.append("listen: :").append(config.get("UDP_PORT")).append("\n\n");
        }
        String sni = config.getOrDefault("HY2_SNI", "www.bing.com");
        yaml.append("tls:\n  cert: /tmp/cert.pem\n  key: /tmp/key.pem\n  sni: ").append(sni).append("\n\n");
        yaml.append("auth:\n  type: password\n  password: ").append(config.get("HY2_PASSWORD")).append("\n\n");
        String obfsPass = config.get("HY2_OBFS_PASSWORD");
        if (obfsPass != null && !obfsPass.trim().isEmpty()) {
            yaml.append("obfs:\n  type: salamander\n  salamander:\n    password: ").append(obfsPass).append("\n\n");
        }
        yaml.append("bandwidth:\n  up: 100 mbps\n  down: 100 mbps\n\n");
        yaml.append("quic:\n  initStreamReceiveWindow: 8388608\n  maxStreamReceiveWindow: 8388608\n  initConnReceiveWindow: 20971520\n  maxConnReceiveWindow: 20971520\n  maxIdleTimeout: 60s\n  maxIncomingStreams: 256\n  disablePathMTUDiscovery: false\n\n");
        yaml.append("masquerade:\n  type: proxy\n  proxy:\n    url: https://").append(sni).append("\n    rewriteHost: true\n\n");
        yaml.append("acl:\n  inline:\n    - reject(all, udp/443)\n    - reject(all, udp/80)\n");
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "hysteria2-config.yaml");
        Files.write(path, yaml.toString().getBytes());
        return path;
    }
    
    private static void generateSelfSignedCert(String sni) throws IOException {
        Path certPath = Paths.get("/tmp/cert.pem");
        Path keyPath = Paths.get("/tmp/key.pem");
        if (!Files.exists(certPath) || !Files.exists(keyPath)) {
            System.out.println("Generating self-signed certificate...");
            try {
                new ProcessBuilder("openssl", "req", "-x509", "-nodes", "-newkey", "rsa:2048", "-keyout", keyPath.toString(), "-out", certPath.toString(), "-days", "365", "-subj", "/CN=" + sni).start().waitFor();
            } catch (InterruptedException e) {}
        }
    }
    
    private static Path createNezhaConfig(Map<String, String> config) throws IOException {
        String server = config.get("NEZHA_SERVER");
        String port = config.getOrDefault("NEZHA_PORT", "5555");
        if (!server.contains(":")) server += ":" + port;
        String uuid = config.get("UUID");
        String yml = String.format("client_id: \"%s\"\nuuid: \"%s\"\nclient_secret: \"%s\"\ndebug: true\nserver: \"%s\"\ntls: %s\nreport_delay: 4\nskip_connection_count: true\nskip_procs_count: true\ndisable_auto_update: true\ndisable_force_update: true\n",
            uuid, uuid, config.get("NEZHA_KEY"), server, config.getOrDefault("NEZHA_TLS", "false"));
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-config.yml");
        Files.write(path, yml.getBytes());
        return path;
    }
    
    private static Path downloadHysteria2() throws IOException {
        String url = System.getProperty("os.arch").contains("aarch64") ? "https://github.com/apernet/hysteria/releases/latest/download/hysteria-linux-arm64" : "https://github.com/apernet/hysteria/releases/latest/download/hysteria-linux-amd64";
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "hysteria2");
        if (!Files.exists(path)) {
            System.out.println("Downloading Hysteria2...");
            try (InputStream in = new URL(url).openStream()) { Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING); }
            path.toFile().setExecutable(true);
        }
        return path;
    }
    
    private static Path downloadNezhaAgent() throws IOException {
        String url = System.getProperty("os.arch").contains("aarch64") ? "https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_arm64.zip" : "https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_amd64.zip";
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-agent");
        if (!Files.exists(path)) {
            System.out.println("Downloading Nezha...");
            Path zip = Paths.get(System.getProperty("java.io.tmpdir"), "nezha.zip");
            try (InputStream in = new URL(url).openStream()) { Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING); }
            unzipFile(zip, Paths.get(System.getProperty("java.io.tmpdir")), "nezha-agent");
            Files.delete(zip);
        }
        return path;
    }
    
    private static void stopServices() {
        if (minecraftProcess != null) minecraftProcess.destroy();
        if (nezhaProcess != null) nezhaProcess.destroy();
        if (hy2Process != null) hy2Process.destroy();
    }
    
    private static boolean isMcServerEnabled(Map<String, String> config) {
        String jarName = config.get("MC_JAR");
        return jarName != null && !jarName.trim().isEmpty();
    }
    
    private static void startMinecraftServer(Map<String, String> config) throws Exception {
        String jarName = config.get("MC_JAR");
        String memory = config.getOrDefault("MC_MEMORY", "512M");
        String extraArgs = config.getOrDefault("MC_ARGS", "");
        if (!memory.matches("\\d+[MG]")) memory = "512M";
        
        Path jarPath = Paths.get(jarName);
        if (!Files.exists(jarPath)) { System.out.println(ANSI_RED + "[MC-Server] JAR not found!" + ANSI_RESET); return; }
        
        Path eulaPath = Paths.get("eula.txt");
        if (!Files.exists(eulaPath)) Files.write(eulaPath, "eula=true".getBytes());
        
        Path propPath = Paths.get("server.properties");
        if (Files.exists(propPath)) {
            String props = new String(Files.readAllBytes(propPath));
            if (props.contains("online-mode=true")) Files.write(propPath, props.replace("online-mode=true", "online-mode=false").getBytes());
        }
        
        System.out.println(ANSI_GREEN + "=== Starting Minecraft Server ===" + ANSI_RESET);
        List<String> cmd = new ArrayList<>();
        cmd.add("java"); cmd.add("-Xms" + memory); cmd.add("-Xmx" + memory);
        if (!extraArgs.trim().isEmpty()) cmd.addAll(Arrays.asList(extraArgs.split("\\s+")));
        cmd.add("-jar"); cmd.add(jarName); cmd.add("nogui");
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        minecraftProcess = pb.start();
        
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(minecraftProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) System.out.println("[MC-Server] " + line);
            } catch (IOException e) {}
        }).start();
        
        Thread.sleep(3000);
    }
    
    // ==================== 真实假玩家机器人 ====================
    
    private static void waitForServerReady() throws InterruptedException {
        int mcPort = 25389;
        try { String p = System.getenv("MC_PORT"); if(p!=null) mcPort=Integer.parseInt(p); } catch(Exception e){}
        
        System.out.println(ANSI_YELLOW + "[FakePlayer] Checking server status on port " + mcPort + "..." + ANSI_RESET);
        for (int i = 0; i < 60; i++) {
            try {
                Thread.sleep(5000);
                try (Socket testSocket = new Socket()) {
                    testSocket.connect(new InetSocketAddress("127.0.0.1", mcPort), 3000);
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Server port " + mcPort + " is open!" + ANSI_RESET);
                    Thread.sleep(5000);
                    return;
                }
            } catch (Exception e) {}
        }
    }
    
    private static boolean isFakePlayerBotEnabled(Map<String, String> config) {
        return "true".equalsIgnoreCase(config.get("FAKE_PLAYER_ENABLED"));
    }
    
    private static void startFakePlayerBot(Map<String, String> config) {
        String playerName = config.getOrDefault("FAKE_PLAYER_NAME", "labubu");
        int mcPort = Integer.parseInt(config.getOrDefault("MC_PORT", "25389"));
        
        System.out.println(ANSI_GREEN + "[FakePlayer] Starting fake player bot: " + playerName + ANSI_RESET);
        
        keepaliveThread = new Thread(() -> {
            int failCount = 0;
            while (running.get()) {
                Socket socket = null;
                try {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Attempting to connect..." + ANSI_RESET);
                    socket = new Socket();
                    socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                    socket.setSoTimeout(30000); // 30s timeout
                    
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ TCP connection established" + ANSI_RESET);
                    
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    
                    // Handshake (774 = 1.21.11)
                    ByteArrayOutputStream handshakeBuf = new ByteArrayOutputStream();
                    DataOutputStream handshake = new DataOutputStream(handshakeBuf);
                    writeVarInt(handshake, 0x00);
                    writeVarInt(handshake, 774);
                    writeString(handshake, "127.0.0.1");
                    handshake.writeShort(mcPort);
                    writeVarInt(handshake, 2);
                    sendPacket(out, handshakeBuf.toByteArray(), false, -1);
                    
                    // Login Start
                    ByteArrayOutputStream loginBuf = new ByteArrayOutputStream();
                    DataOutputStream login = new DataOutputStream(loginBuf);
                    writeVarInt(login, 0x00);
                    writeString(login, playerName);
                    java.util.UUID playerUUID = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes("UTF-8"));
                    login.writeLong(playerUUID.getMostSignificantBits());
                    login.writeLong(playerUUID.getLeastSignificantBits());
                    sendPacket(out, loginBuf.toByteArray(), false, -1);
                    
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Sent login request for: " + playerName + ANSI_RESET);
                    
                    boolean configPhase = false;
                    boolean playPhase = false;
                    boolean compressionEnabled = false;
                    int compressionThreshold = -1;
                    long startTime = System.currentTimeMillis();
                    
                    while (!playPhase && System.currentTimeMillis() - startTime < 60000) {
                        if (in.available() > 0) {
                            int packetLength = readVarInt(in);
                            byte[] packetData;
                            
                            if (compressionEnabled) {
                                int dataLength = readVarInt(in);
                                int compressedLength = packetLength - getVarIntSize(dataLength);
                                if (dataLength == 0) {
                                    packetData = new byte[compressedLength];
                                    in.readFully(packetData);
                                } else {
                                    byte[] compressedData = new byte[compressedLength];
                                    in.readFully(compressedData);
                                    java.util.zip.Inflater inflater = new java.util.zip.Inflater();
                                    inflater.setInput(compressedData);
                                    packetData = new byte[dataLength];
                                    inflater.inflate(packetData);
                                    inflater.end();
                                }
                            } else {
                                packetData = new byte[packetLength];
                                in.readFully(packetData);
                            }
                            
                            ByteArrayInputStream packetStream = new ByteArrayInputStream(packetData);
                            DataInputStream packetIn = new DataInputStream(packetStream);
                            int packetId = readVarInt(packetIn);
                            
                            if (packetId == 0x00 && !configPhase) { // Disconnect in Login
                                System.out.println(ANSI_RED + "[FakePlayer] Login Disconnected!" + ANSI_RESET);
                                break;
                            }
                            
                            if (!configPhase) {
                                if (packetId == 0x03) { // Set Compression
                                    compressionThreshold = readVarInt(packetIn);
                                    compressionEnabled = compressionThreshold >= 0;
                                    System.out.println(ANSI_YELLOW + "[FakePlayer] Compression enabled: " + compressionThreshold + ANSI_RESET);
                                } else if (packetId == 0x04) { // Login Plugin Request
                                    int msgId = readVarInt(packetIn);
                                    System.out.println(ANSI_YELLOW + "[FakePlayer] Login Plugin Request (ID=" + msgId + ")" + ANSI_RESET);
                                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                    DataOutputStream bufOut = new DataOutputStream(buf);
                                    writeVarInt(bufOut, 0x02); writeVarInt(bufOut, msgId); bufOut.writeBoolean(false);
                                    sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                } else if (packetId == 0x02) { // Login Success
                                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Login Success" + ANSI_RESET);
                                    
                                    // Ack
                                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                    DataOutputStream bufOut = new DataOutputStream(buf);
                                    writeVarInt(bufOut, 0x03); 
                                    sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Sent Login Ack" + ANSI_RESET);
                                    
                                    configPhase = true;
                                    
                                    // Client Information
                                    ByteArrayOutputStream settings = new ByteArrayOutputStream();
                                    DataOutputStream settingsOut = new DataOutputStream(settings);
                                    writeVarInt(settingsOut, 0x00);
                                    writeString(settingsOut, "en_US");
                                    settingsOut.writeByte(2);
                                    writeVarInt(settingsOut, 0);
                                    settingsOut.writeBoolean(true);
                                    settingsOut.writeByte(127);
                                    writeVarInt(settingsOut, 1);
                                    settingsOut.writeBoolean(false);
                                    settingsOut.writeBoolean(true);
                                    sendPacket(out, settings.toByteArray(), compressionEnabled, compressionThreshold);
                                }
                            } else { // Config Phase
                                if (packetId == 0x03) { // Finish Config
                                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Finish Config received" + ANSI_RESET);
                                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                    DataOutputStream bufOut = new DataOutputStream(buf);
                                    writeVarInt(bufOut, 0x03); 
                                    sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Sent Finish Ack. Entering Play." + ANSI_RESET);
                                    playPhase = true;
                                } else if (packetId == 0x04) { // Keep Alive
                                    long id = packetIn.readLong();
                                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                    DataOutputStream bufOut = new DataOutputStream(buf);
                                    writeVarInt(bufOut, 0x04); bufOut.writeLong(id);
                                    sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                } else if (packetId == 0x0E) { // Known Packs
                                    System.out.println(ANSI_GREEN + "[FakePlayer] Known Packs received, replying empty" + ANSI_RESET);
                                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                    DataOutputStream bufOut = new DataOutputStream(buf);
                                    writeVarInt(bufOut, 0x07); writeVarInt(bufOut, 0);
                                    sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                }
                            }
                        }
                        Thread.sleep(20);
                    }
                    
                    if (!playPhase) throw new IOException("Config timeout");
                    
                    System.out.println(ANSI_GREEN + "[FakePlayer] Play Phase Loop Started" + ANSI_RESET);
                    long lastTime = System.currentTimeMillis();
                    
                    // ============ Play Phase Loop (Buffered) ============
                    while (running.get() && !socket.isClosed()) {
                        try {
                            // 1. Read Length (VarInt) - Manual read to handle EOF
                            int length = 0;
                            int numRead = 0;
                            while (true) {
                                int b = in.read();
                                if (b == -1) throw new EOFException();
                                length |= (b & 0x7F) << (numRead++ * 7);
                                if ((b & 0x80) == 0) break;
                                if (numRead > 5) throw new IOException("VarInt too big");
                            }
                            
                            // 2. Read Body
                            byte[] body = new byte[length];
                            in.readFully(body);
                            lastTime = System.currentTimeMillis();
                            
                            // 3. Decompress if needed
                            ByteArrayInputStream bodyStream = new ByteArrayInputStream(body);
                            DataInputStream bodyIn = new DataInputStream(bodyStream);
                            
                            int packetId;
                            DataInputStream packetDataIn; // Stream for reading packet content
                            
                            if (compressionEnabled) {
                                int dataLength = readVarInt(bodyIn);
                                if (dataLength == 0) {
                                    // Uncompressed
                                    packetId = readVarInt(bodyIn);
                                    packetDataIn = bodyIn;
                                } else {
                                    // Compressed
                                    byte[] compressed = new byte[bodyIn.available()];
                                    bodyIn.readFully(compressed);
                                    java.util.zip.Inflater inflater = new java.util.zip.Inflater();
                                    inflater.setInput(compressed);
                                    byte[] decompressed = new byte[dataLength];
                                    inflater.inflate(decompressed);
                                    inflater.end();
                                    
                                    ByteArrayInputStream decompStream = new ByteArrayInputStream(decompressed);
                                    packetDataIn = new DataInputStream(decompStream);
                                    packetId = readVarInt(packetDataIn);
                                }
                            } else {
                                packetId = readVarInt(bodyIn);
                                packetDataIn = bodyIn;
                            }
                            
                            // 4. Handle Packets
                            if (packetId == 0x24 || packetId == 0x26) {
                                long id = packetDataIn.readLong();
                                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                DataOutputStream bufOut = new DataOutputStream(buf);
                                writeVarInt(bufOut, (packetId == 0x24) ? 0x15 : 0x18);
                                bufOut.writeLong(id);
                                sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                            } else if (packetId == 0x1B || packetId == 0x1D || packetId == 0x1F) {
                                String reason = readString(packetDataIn);
                                System.out.println(ANSI_RED + "[FakePlayer] KICKED by Server! Reason: " + reason + ANSI_RESET);
                            }
                            
                        } catch (SocketTimeoutException e) {
                            if (System.currentTimeMillis() - lastTime > 40000) throw new IOException("Timeout");
                        }
                    }
                    
                } catch (EOFException e) {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Server closed connection (EOF)" + ANSI_RESET);
                } catch (Exception e) {
                    failCount++;
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Error: " + e.getMessage() + ANSI_RESET);
                    if (failCount > 5) {
                        try { Thread.sleep(30000); } catch (InterruptedException ie) { break; }
                    }
                } finally {
                    try { if(socket!=null) socket.close(); } catch(Exception e){}
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                }
            }
        });
        keepaliveThread.setDaemon(true);
        keepaliveThread.start();
    }
    
    private static void sendPacket(DataOutputStream out, byte[] data, boolean compress, int threshold) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream bufOut = new DataOutputStream(buf);
        
        if (compress) {
            // [Length] [DataLength] [Data]
            if (data.length >= threshold) {
                byte[] compressed = compressData(data);
                writeVarInt(bufOut, data.length); // Original Length
                bufOut.write(compressed);
            } else {
                writeVarInt(bufOut, 0); // DataLength=0 (Uncompressed)
                bufOut.write(data);
            }
        } else {
            // [Length] [Data]
            bufOut.write(data);
        }
        
        byte[] frame = buf.toByteArray();
        writeVarInt(out, frame.length);
        out.write(frame);
        out.flush();
    }
    
    private static byte[] compressData(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(data);
        deflater.finish();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            out.write(buffer, 0, count);
        }
        deflater.end();
        return out.toByteArray();
    }
    
    private static boolean isMcKeepaliveEnabled(Map<String, String> config) {
        String host = config.get("MC_KEEPALIVE_HOST");
        return host != null && !host.trim().isEmpty();
    }
    
    private static void startMcKeepalive(Map<String, String> config) {
        String host = config.get("MC_KEEPALIVE_HOST");
        int port = Integer.parseInt(config.getOrDefault("MC_KEEPALIVE_PORT", "25835"));
        System.out.println(ANSI_GREEN + "[MC-Keepalive] Starting simple ping..." + ANSI_RESET);
        
        keepaliveThread = new Thread(() -> {
            while (running.get()) {
                try {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(host, port), 5000);
                        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        DataOutputStream p = new DataOutputStream(buf);
                        writeVarInt(p, 0x00); writeVarInt(p, 47); writeString(p, host); p.writeShort(port); writeVarInt(p, 1);
                        byte[] hs = buf.toByteArray();
                        writeVarInt(out, hs.length); out.write(hs);
                        
                        buf.reset(); writeVarInt(p, 0x00);
                        byte[] req = buf.toByteArray();
                        writeVarInt(out, req.length); out.write(req);
                        System.out.println(ANSI_GREEN + "[MC-Keepalive] Ping success" + ANSI_RESET);
                    }
                    Thread.sleep(300000);
                } catch (Exception e) {
                    System.out.println(ANSI_YELLOW + "[MC-Keepalive] Ping failed" + ANSI_RESET);
                    try { Thread.sleep(60000); } catch (Exception ex) {}
                }
            }
        });
        keepaliveThread.setDaemon(true);
        keepaliveThread.start();
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) { out.writeByte((value & 0x7F) | 0x80); value >>>= 7; }
        out.writeByte(value & 0x7F);
    }
    
    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0; int length = 0; byte currentByte;
        do {
            currentByte = in.readByte();
            value |= (currentByte & 0x7F) << (length * 7);
            length++;
            if (length > 5) throw new IOException("VarInt too big");
        } while ((currentByte & 0x80) == 0x80);
        return value;
    }
    
    private static void writeString(DataOutputStream out, String str) throws IOException {
        byte[] bytes = str.getBytes("UTF-8");
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
    
    private static String readString(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, "UTF-8");
    }
    
    private static int getVarIntSize(int value) {
        int size = 0;
        do { size++; value >>>= 7; } while (value != 0);
        return size;
    }
    
    private static void unzipFile(Path zipPath, Path destDir, String targetFile) throws IOException {
        Files.createDirectories(destDir);
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath.toFile()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(targetFile) || entry.getName().endsWith("/" + targetFile)) {
                    Path outPath = destDir.resolve(targetFile);
                    try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                    }
                    outPath.toFile().setExecutable(true);
                    break;
                }
                zis.closeEntry();
            }
        }
    }
}
