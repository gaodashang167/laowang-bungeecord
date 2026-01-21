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
            
            // 1. 启动真实的 Minecraft 服务器（如果配置了）
            if (isMcServerEnabled(config)) {
                startMinecraftServer(config);
            }
            
            // 2. 启动哪吒
            if (isNezhaConfigured(config)) {
                runNezhaAgent(config);
            }
            
            // 3. 启动 Hysteria2
            String hy2Url = generateHy2Url(config);
            
            System.out.println(ANSI_GREEN + "\n=== Hysteria2 Configuration ===" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Password: " + config.get("HY2_PASSWORD") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Obfs Password: " + config.getOrDefault("HY2_OBFS_PASSWORD", "(none)") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "UDP Port: " + config.get("UDP_PORT") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Port Range: " + config.getOrDefault("HY2_PORTS", "(single port)") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Domain/IP: " + config.get("DOMAIN") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "SNI: " + config.getOrDefault("HY2_SNI", "www.bing.com") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "ALPN: " + config.getOrDefault("HY2_ALPN", "h3") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "\n=== Hysteria2 Node URL ===" + ANSI_RESET);
            System.out.println(ANSI_GREEN + hy2Url + ANSI_RESET);
            System.out.println(ANSI_GREEN + "================================" + ANSI_RESET);
            
            runHysteria2Service(config);
            
            // 4. 启动真实假玩家（推荐）
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
    
    private static void runHysteria2Service(Map<String, String> config) throws Exception {
        Path hy2Path = downloadHysteria2();
        
        // 先生成证书（在创建配置前）
        String sni = config.getOrDefault("HY2_SNI", "www.bing.com");
        generateSelfSignedCert(sni);
        
        Path configPath = createHysteria2Config(config);
        
        System.out.println(ANSI_GREEN + "Starting Hysteria2 Server..." + ANSI_RESET);
        
        ProcessBuilder pb = new ProcessBuilder(
            hy2Path.toString(), "server", "-c", configPath.toString()
        );
        pb.redirectErrorStream(true);
        
        hy2Process = pb.start();
        
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(hy2Process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 过滤掉客户端连接日志（包含 IP 地址）
                    if (line.contains("client connected") || 
                        line.contains("client disconnected") ||
                        line.contains("addr")) {
                        continue;  // 跳过这些日志
                    }
                    // 只显示错误和警告
                    if (line.contains("ERROR") || line.contains("WARN") || line.contains("FATAL")) {
                        System.out.println(ANSI_YELLOW + "[Hysteria2] " + ANSI_RESET + line);
                    }
                }
            } catch (IOException e) {}
        }).start();
        
        Thread.sleep(1000);
        if (!hy2Process.isAlive()) {
             System.out.println(ANSI_RED + "Hysteria2 Server exited prematurely: " + hy2Process.exitValue() + ANSI_RESET);
        }
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
        
        // 环境变量覆盖
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.isEmpty()) config.put(var, value);
        }
        return config;
    }
    
    private static String generateHy2Url(Map<String, String> config) {
        try {
            // hysteria2://password@domain:port?obfs=salamander&obfs-password=xxx&sni=xxx&insecure=1&mport=xxx#Name
            StringBuilder url = new StringBuilder("hysteria2://");
            
            String password = URLEncoder.encode(config.get("HY2_PASSWORD"), "UTF-8");
            url.append(password).append("@");
            url.append(config.get("DOMAIN")).append(":").append(config.get("UDP_PORT"));
            
            // 参数部分
            List<String> params = new ArrayList<>();
            
            // 混淆密码（如果有）
            String obfsPass = config.get("HY2_OBFS_PASSWORD");
            if (obfsPass != null && !obfsPass.trim().isEmpty()) {
                params.add("obfs=salamander");
                params.add("obfs-password=" + URLEncoder.encode(obfsPass, "UTF-8"));
            }
            
            // SNI
            String sni = config.getOrDefault("HY2_SNI", "www.bing.com");
            params.add("sni=" + sni);
            
            // 跳跃端口
            String ports = config.get("HY2_PORTS");
            if (ports != null && !ports.trim().isEmpty()) {
                params.add("mport=" + URLEncoder.encode(ports, "UTF-8"));
            }
            
            // ALPN
            String alpn = config.getOrDefault("HY2_ALPN", "h3");
            params.add("alpn=" + alpn);
            
            // 跳过证书验证
            params.add("insecure=1");
            
            if (!params.isEmpty()) {
                url.append("?").append(String.join("&", params));
            }
            
            url.append("#US-HostPapa");
            
            return url.toString();
        } catch (Exception e) { 
            e.printStackTrace();
            return ""; 
        }
    }
    
    private static Path createHysteria2Config(Map<String, String> config) throws IOException {
        StringBuilder yaml = new StringBuilder();
        
        // 监听配置 - 支持跳跃端口
        String ports = config.get("HY2_PORTS");
        if (ports != null && !ports.trim().isEmpty()) {
            yaml.append("listen: :").append(config.get("UDP_PORT")).append("\n");
            yaml.append("mport: ").append(ports).append("\n\n");
        } else {
            yaml.append("listen: :").append(config.get("UDP_PORT")).append("\n\n");
        }
        
        // TLS 配置 - 伪装成正常 HTTPS 网站
        String sni = config.getOrDefault("HY2_SNI", "www.bing.com");
        yaml.append("tls:\n");
        yaml.append("  cert: /tmp/cert.pem\n");
        yaml.append("  key: /tmp/key.pem\n");
        yaml.append("  sni: ").append(sni).append("\n\n");
        
        // 认证配置
        yaml.append("auth:\n");
        yaml.append("  type: password\n");
        yaml.append("  password: ").append(config.get("HY2_PASSWORD")).append("\n\n");
        
        // 混淆配置 - 【关键】增强隐蔽性
        String obfsPass = config.get("HY2_OBFS_PASSWORD");
        if (obfsPass != null && !obfsPass.trim().isEmpty()) {
            yaml.append("obfs:\n");
            yaml.append("  type: salamander\n");
            yaml.append("  salamander:\n");
            yaml.append("    password: ").append(obfsPass).append("\n\n");
        }
        
        // 带宽限制 - 【重要】模拟正常用户，避免异常流量
        yaml.append("bandwidth:\n");
        yaml.append("  up: 100 mbps\n");    // 上传限速
        yaml.append("  down: 100 mbps\n\n"); // 下载限速
        
        // QUIC 配置 - 优化性能和隐蔽性
        yaml.append("quic:\n");
        yaml.append("  initStreamReceiveWindow: 8388608\n");
        yaml.append("  maxStreamReceiveWindow: 8388608\n");
        yaml.append("  initConnReceiveWindow: 20971520\n");
        yaml.append("  maxConnReceiveWindow: 20971520\n");
        yaml.append("  maxIdleTimeout: 60s\n");         // 适中的超时
        yaml.append("  maxIncomingStreams: 256\n");     // 限制并发流
        yaml.append("  disablePathMTUDiscovery: false\n\n");
        
        // 【核心】伪装成正常网站 - 非代理流量访问时返回真实网站
        yaml.append("masquerade:\n");
        yaml.append("  type: proxy\n");
        yaml.append("  proxy:\n");
        yaml.append("    url: https://").append(sni).append("\n");
        yaml.append("    rewriteHost: true\n\n");
        
        // ACL 规则 - 可选，限制访问来源
        yaml.append("acl:\n");
        yaml.append("  inline:\n");
        yaml.append("    - reject(all, udp/443)      # 阻止 QUIC 探测\n");
        yaml.append("    - reject(all, udp/80)       # 阻止异常探测\n");
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "hysteria2-config.yaml");
        Files.write(path, yaml.toString().getBytes());
        
        System.out.println(ANSI_GREEN + "Hysteria2 Config created with stealth features enabled" + ANSI_RESET);
        return path;
    }
    
    private static void generateSelfSignedCert(String sni) throws IOException {
        Path certPath = Paths.get("/tmp/cert.pem");
        Path keyPath = Paths.get("/tmp/key.pem");
        
        if (!Files.exists(certPath) || !Files.exists(keyPath)) {
            System.out.println("Generating self-signed certificate for " + sni + "...");
            try {
                new ProcessBuilder(
                    "openssl", "req", "-x509", "-nodes", "-newkey", "rsa:2048",
                    "-keyout", keyPath.toString(),
                    "-out", certPath.toString(),
                    "-days", "365",
                    "-subj", "/CN=" + sni
                ).start().waitFor();
            } catch (InterruptedException e) {
                throw new IOException("Certificate generation interrupted", e);
            }
        }
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
    
    private static Path downloadHysteria2() throws IOException {
        String url = "https://github.com/apernet/hysteria/releases/latest/download/hysteria-linux-amd64";
        if (System.getProperty("os.arch").contains("aarch64")) {
            url = "https://github.com/apernet/hysteria/releases/latest/download/hysteria-linux-arm64";
        }
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "hysteria2");
        if (!Files.exists(path)) {
            System.out.println("Downloading Hysteria2...");
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            path.toFile().setExecutable(true);
        }
        return path;
    }
    
    // ==================== Xray 下载（备用，如果需要切换回 VLESS）====================
    
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
            
            // 使用 Java 解压替代 unzip 命令
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
        if (hy2Process != null) hy2Process.destroy();
        if (keepaliveThread != null) keepaliveThread.interrupt();
    }
    
    // ==================== Minecraft 服务器启动 ====================
    
    private static boolean isMcServerEnabled(Map<String, String> config) {
        String jarName = config.get("MC_JAR");
        return jarName != null && !jarName.trim().isEmpty();
    }
    
    private static void startMinecraftServer(Map<String, String> config) throws Exception {
        String jarName = config.get("MC_JAR");
        String memory = config.getOrDefault("MC_MEMORY", "512M");  // 明确默认值
        String extraArgs = config.getOrDefault("MC_ARGS", "");
        
        // 验证内存格式
        if (!memory.matches("\\d+[MG]")) {
            System.out.println(ANSI_RED + "[MC-Server] Invalid memory format: " + memory + ANSI_RESET);
            System.out.println(ANSI_YELLOW + "[MC-Server] Using default: 512M" + ANSI_RESET);
            memory = "512M";
        }
        
        // 检查 jar 文件是否存在
        Path jarPath = Paths.get(jarName);
        if (!Files.exists(jarPath)) {
            System.out.println(ANSI_RED + "[MC-Server] Error: " + jarName + " not found!" + ANSI_RESET);
            System.out.println(ANSI_YELLOW + "[MC-Server] Skipping Minecraft server startup" + ANSI_RESET);
            return;
        }
        
        // 自动同意 EULA
        Path eulaPath = Paths.get("eula.txt");
        if (!Files.exists(eulaPath)) {
            System.out.println(ANSI_GREEN + "[MC-Server] Creating eula.txt (auto-accepting)" + ANSI_RESET);
            Files.write(eulaPath, "eula=true".getBytes());
        } else {
            // 确保 EULA 已同意
            String eulaContent = new String(Files.readAllBytes(eulaPath));
            if (!eulaContent.contains("eula=true")) {
                System.out.println(ANSI_GREEN + "[MC-Server] Auto-accepting EULA" + ANSI_RESET);
                Files.write(eulaPath, "eula=true".getBytes());
            }
        }
        
        // 修改 server.properties - 关闭正版验证（允许离线模式假玩家）
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
        
        // 构建启动命令
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Xms" + memory);
        cmd.add("-Xmx" + memory);
        
        // 添加额外参数
        if (!extraArgs.trim().isEmpty()) {
            cmd.addAll(Arrays.asList(extraArgs.split("\\s+")));
        }
        
        // 根据内存大小选择优化参数
        int memoryMB = parseMemory(memory);
        
        if (memoryMB >= 2048) {
            // 2GB+ 内存：使用完整优化
            cmd.add("-XX:+UseG1GC");
            cmd.add("-XX:+ParallelRefProcEnabled");
            cmd.add("-XX:MaxGCPauseMillis=200");
            cmd.add("-XX:+UnlockExperimentalVMOptions");
            cmd.add("-XX:+DisableExplicitGC");
            cmd.add("-XX:G1NewSizePercent=30");
            cmd.add("-XX:G1MaxNewSizePercent=40");
            cmd.add("-XX:G1HeapRegionSize=8M");
            cmd.add("-XX:G1ReservePercent=20");
            cmd.add("-XX:G1HeapWastePercent=5");
            cmd.add("-XX:G1MixedGCCountTarget=4");
            cmd.add("-XX:InitiatingHeapOccupancyPercent=15");
            cmd.add("-XX:G1MixedGCLiveThresholdPercent=90");
            cmd.add("-XX:G1RSetUpdatingPauseTimePercent=5");
            cmd.add("-XX:SurvivorRatio=32");
            cmd.add("-XX:+PerfDisableSharedMem");
            cmd.add("-XX:MaxTenuringThreshold=1");
        } else if (memoryMB >= 1024) {
            // 1GB-2GB：基础 G1GC
            cmd.add("-XX:+UseG1GC");
            cmd.add("-XX:MaxGCPauseMillis=200");
            cmd.add("-XX:+DisableExplicitGC");
        } else {
            // <1GB：轻量级配置
            System.out.println(ANSI_YELLOW + "[MC-Server] Low memory mode (< 1GB)" + ANSI_RESET);
            cmd.add("-XX:+UseSerialGC");  // 串行GC，内存占用最小
            cmd.add("-XX:+DisableExplicitGC");
        }
        
        cmd.add("-jar");
        cmd.add(jarName);
        cmd.add("nogui");
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        
        minecraftProcess = pb.start();
        
        // 转发 MC 服务器输出
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(minecraftProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[MC-Server] " + line);
                }
            } catch (IOException e) {}
        }).start();
        
        // 检查是否成功启动
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
        return 1024; // 默认 1GB
    }
    
    // ==================== 真实假玩家机器人 ====================
    
    private static void waitForServerReady() throws InterruptedException {
        // 从配置中读取 MC 端口
        int mcPort = 25835; // 默认值
        try {
            String portEnv = System.getenv("MC_PORT");
            if (portEnv != null && !portEnv.isEmpty()) {
                mcPort = Integer.parseInt(portEnv);
            }
        } catch (Exception e) {}
        
        System.out.println(ANSI_YELLOW + "[FakePlayer] Checking server status on port " + mcPort + " every 5s..." + ANSI_RESET);
        
        for (int i = 0; i < 60; i++) { // 最多等待 5 分钟
            try {
                Thread.sleep(5000); // 每 5 秒检查一次
                
                // 简单的连接测试
                try (Socket testSocket = new Socket()) {
                    testSocket.connect(new InetSocketAddress("127.0.0.1", mcPort), 3000);
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Server port " + mcPort + " is open!" + ANSI_RESET);
                    Thread.sleep(10000); // 端口开了后再等 10 秒让服务器稳定
                    return;
                }
            } catch (Exception e) {
                if (i % 6 == 0) { // 每 30 秒提示一次
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Still waiting... (" + (i * 5) + "s) - " + e.getMessage() + ANSI_RESET);
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
        int mcPort = Integer.parseInt(config.getOrDefault("MC_PORT", "25835"));
        
        System.out.println(ANSI_GREEN + "[FakePlayer] Starting fake player bot: " + playerName + ANSI_RESET);
        System.out.println(ANSI_GREEN + "[FakePlayer] Target: 127.0.0.1:" + mcPort + ANSI_RESET);
        System.out.println(ANSI_YELLOW + "[FakePlayer] NOTE: Server must be in offline mode (online-mode=false)" + ANSI_RESET);
        
        keepaliveThread = new Thread(() -> {
            int failCount = 0;
            while (running.get()) {
                Socket socket = null;
                try {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Attempting to connect to port " + mcPort + "..." + ANSI_RESET);
                    
                    // 连接服务器
                    socket = new Socket();
                    socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                    socket.setSoTimeout(15000);
                    
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ TCP connection established" + ANSI_RESET);
                    
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    
                    // 握手包 - 使用协议版本 774 (MC 1.21.11)
                    ByteArrayOutputStream handshakeBuf = new ByteArrayOutputStream();
                    DataOutputStream handshake = new DataOutputStream(handshakeBuf);
                    writeVarInt(handshake, 0x00);  // 包ID: Handshake
                    writeVarInt(handshake, 774);   // 协议版本 774 for 1.21.11
                    writeString(handshake, "127.0.0.1");
                    handshake.writeShort(mcPort);
                    writeVarInt(handshake, 2);     // 下一个状态: Login
                    
                    byte[] handshakeData = handshakeBuf.toByteArray();
                    writeVarInt(out, handshakeData.length);
                    out.write(handshakeData);
                    out.flush();
                    
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Sent handshake (protocol 774 for 1.21.11)" + ANSI_RESET);
                    
                    // 登录开始包 - 1.19.1+ 需要 UUID
                    ByteArrayOutputStream loginBuf = new ByteArrayOutputStream();
                    DataOutputStream login = new DataOutputStream(loginBuf);
                    writeVarInt(login, 0x00);  // 包ID: Login Start
                    writeString(login, playerName);
                    
                    // 1.19+ 需要 UUID (offline mode 使用名字生成)
                    java.util.UUID playerUUID = java.util.UUID.nameUUIDFromBytes(
                        ("OfflinePlayer:" + playerName).getBytes("UTF-8")
                    );
                    login.writeLong(playerUUID.getMostSignificantBits());
                    login.writeLong(playerUUID.getLeastSignificantBits());
                    
                    byte[] loginData = loginBuf.toByteArray();
                    writeVarInt(out, loginData.length);
                    out.write(loginData);
                    out.flush();
                    
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Sent login request for: " + playerName + " (UUID: " + playerUUID + ")" + ANSI_RESET);
                    
                    // 等待并处理服务器响应
                    boolean configPhase = false;
                    boolean playPhase = false;
                    boolean compressionEnabled = false;
                    int compressionThreshold = -1;
                    long startTime = System.currentTimeMillis();
                    
                    while (!playPhase && System.currentTimeMillis() - startTime < 15000) {
                        if (in.available() > 0) {
                            // 读取包长度
                            int packetLength = readVarInt(in);
                            
                            if (packetLength > 0 && packetLength < 1048576) {
                                byte[] packetData;
                                
                                if (compressionEnabled) {
                                    // 压缩模式：读取原始长度
                                    int dataLength = readVarInt(in);
                                    int compressedLength = packetLength - getVarIntSize(dataLength);
                                    
                                    if (dataLength == 0) {
                                        // 未压缩（小于阈值）
                                        packetData = new byte[compressedLength];
                                        in.readFully(packetData);
                                    } else {
                                        // 压缩的，需要解压
                                        byte[] compressedData = new byte[compressedLength];
                                        in.readFully(compressedData);
                                        
                                        // 解压
                                        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
                                        inflater.setInput(compressedData);
                                        packetData = new byte[dataLength];
                                        try {
                                            inflater.inflate(packetData);
                                            inflater.end();
                                        } catch (Exception e) {
                                            System.out.println(ANSI_RED + "[FakePlayer] Decompression error: " + e.getMessage() + ANSI_RESET);
                                            inflater.end();
                                            continue;
                                        }
                                    }
                                } else {
                                    // 未启用压缩
                                    packetData = new byte[packetLength];
                                    in.readFully(packetData);
                                }
                                
                                // 解析包
                                ByteArrayInputStream packetStream = new ByteArrayInputStream(packetData);
                                DataInputStream packetIn = new DataInputStream(packetStream);
                                int packetId = readVarInt(packetIn);
                                
                                // System.out.println(ANSI_YELLOW + "[FakePlayer] Packet ID: 0x" + Integer.toHexString(packetId) + " (length: " + packetLength + ")" + ANSI_RESET);
                                
                                if (packetId == 0x00) {
                                    // 断开连接包
                                    int remaining = packetData.length - getVarIntSize(packetId);
                                    byte[] reasonData = new byte[remaining];
                                    packetIn.readFully(reasonData);
                                    try {
                                        String reason = new String(reasonData, "UTF-8");
                                        System.out.println(ANSI_RED + "[FakePlayer] Disconnected: " + reason + ANSI_RESET);
                                    } catch (Exception e) {
                                        System.out.println(ANSI_RED + "[FakePlayer] Disconnected (parse error)" + ANSI_RESET);
                                    }
                                    break;
                                    
                                } else if (!configPhase && !playPhase) {
                                    // === 登录阶段 (Login Phase) ===
                                    if (packetId == 0x03) {
                                        // Set Compression (包 0x03)
                                        compressionThreshold = readVarInt(packetIn);
                                        compressionEnabled = compressionThreshold >= 0;
                                        System.out.println(ANSI_YELLOW + "[FakePlayer] Compression enabled, threshold: " + compressionThreshold + ANSI_RESET);
                                        
                                    } else if (packetId == 0x04) {
                                        // 【修复关键点】Login Plugin Request (包 0x04)
                                        // 服务器在询问是否支持某些 Mod/特性，必须回复才能继续
                                        int messageId = readVarInt(packetIn);
                                        String channel = readString(packetIn); // 读取频道名称，如 velocity:player_info
                                        System.out.println(ANSI_YELLOW + "[FakePlayer] Login Plugin Request: " + channel + " (ID=" + messageId + ")" + ANSI_RESET);
                                        
                                        // 回复 Login Plugin Response (0x02) - 告诉服务器我们不支持该特性 (success=false)
                                        ByteArrayOutputStream respBuf = new ByteArrayOutputStream();
                                        DataOutputStream resp = new DataOutputStream(respBuf);
                                        writeVarInt(resp, 0x02); // Login Plugin Response
                                        writeVarInt(resp, messageId);
                                        resp.writeBoolean(false); // success = false
                                        
                                        sendPacket(out, respBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Replied 'No' to plugin request" + ANSI_RESET);
                                        
                                    } else if (packetId == 0x02) {
                                        // Login Success (包 0x02)
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Login Success (Packet 0x02)" + ANSI_RESET);
                                        
                                        // 发送 Login Acknowledged (包 0x03)
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        DataOutputStream ack = new DataOutputStream(ackBuf);
                                        writeVarInt(ack, 0x03); 
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Sent Login Acknowledged (Packet 0x03)" + ANSI_RESET);
                                        
                                        configPhase = true;
                                    }
                                } else if (configPhase) {
                                    // === 配置阶段 (Configuration Phase) ===
                                    if (packetId == 0x03) {
                                        // Finish Configuration (包 0x03)
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Server requests configuration finish (Packet 0x03)" + ANSI_RESET);
                                        
                                        // 发送 Acknowledge Finish Configuration (包 0x03)
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        DataOutputStream ack = new DataOutputStream(ackBuf);
                                        writeVarInt(ack, 0x03); 
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                        
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Configuration finished. Switching to Play." + ANSI_RESET);
                                        playPhase = true;
                                    }
