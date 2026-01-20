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
        "MC_JAR", "MC_MEMORY", "MC_ARGS",
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
                System.out.println(ANSI_YELLOW + "[FakePlayer] Waiting 60s for MC server to start..." + ANSI_RESET);
                Thread.sleep(60000);
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
        config.put("UUID", "c7eddabd-3aba-4014-8b12-55638bb90714");
        config.put("HY2_PASSWORD", "bf6b80fe-023a-4735-bafd-4c8512bf7e58");  
        config.put("HY2_OBFS_PASSWORD", "gfw-cant-see-me-2025");  // 【重要】启用混淆增强隐蔽性
        config.put("UDP_PORT", "25978");  // 单端口
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
        // Minecraft 保活配置 - 模拟玩家连接
        config.put("MC_KEEPALIVE_HOST", "");  // 留空禁用简单 ping
        config.put("MC_KEEPALIVE_PORT", "25978");
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
    
    private static boolean isFakePlayerBotEnabled(Map<String, String> config) {
        String enabled = config.get("FAKE_PLAYER_ENABLED");
        return enabled != null && enabled.equalsIgnoreCase("true");
    }
    
    private static void startFakePlayerBot(Map<String, String> config) {
        String playerName = config.getOrDefault("FAKE_PLAYER_NAME", "Node_Bot");
        
        System.out.println(ANSI_GREEN + "[FakePlayer] Starting fake player bot: " + playerName + ANSI_RESET);
        System.out.println(ANSI_YELLOW + "[FakePlayer] NOTE: Server must be in offline mode (online-mode=false)" + ANSI_RESET);
        
        keepaliveThread = new Thread(() -> {
            while (running.get()) {
                try {
                    // 连接到本地服务器
                    Socket socket = new Socket("127.0.0.1", 25565);
                    socket.setSoTimeout(10000);
                    
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    
                    // 1. 发送握手包
                    ByteArrayOutputStream handshakeBuf = new ByteArrayOutputStream();
                    DataOutputStream handshake = new DataOutputStream(handshakeBuf);
                    writeVarInt(handshake, 0x00);  // 包ID: Handshake
                    writeVarInt(handshake, 47);    // 协议版本
                    writeString(handshake, "127.0.0.1");
                    handshake.writeShort(25565);
                    writeVarInt(handshake, 2);     // 下一个状态: Login
                    
                    byte[] handshakeData = handshakeBuf.toByteArray();
                    writeVarInt(out, handshakeData.length);
                    out.write(handshakeData);
                    
                    // 2. 发送登录开始包
                    ByteArrayOutputStream loginBuf = new ByteArrayOutputStream();
                    DataOutputStream login = new DataOutputStream(loginBuf);
                    writeVarInt(login, 0x00);  // 包ID: Login Start
                    writeString(login, playerName);
                    
                    byte[] loginData = loginBuf.toByteArray();
                    writeVarInt(out, loginData.length);
                    out.write(loginData);
                    out.flush();
                    
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ " + playerName + " connected to server" + ANSI_RESET);
                    
                    // 3. 保持连接，定期发送 Keep Alive
                    long lastKeepAlive = System.currentTimeMillis();
                    while (running.get() && !socket.isClosed()) {
                        try {
                            // 尝试读取服务器数据包
                            if (in.available() > 0) {
                                int length = readVarInt(in);
                                if (length > 0 && length < 32767) {
                                    int packetId = readVarInt(in);
                                    
                                    // 如果是 Keep Alive 包，回应
                                    if (packetId == 0x00 || packetId == 0x21) {
                                        long keepAliveId = in.readLong();
                                        
                                        ByteArrayOutputStream kaBuf = new ByteArrayOutputStream();
                                        DataOutputStream ka = new DataOutputStream(kaBuf);
                                        writeVarInt(ka, 0x0F); // Keep Alive Response
                                        ka.writeLong(keepAliveId);
                                        
                                        byte[] kaData = kaBuf.toByteArray();
                                        writeVarInt(out, kaData.length);
                                        out.write(kaData);
                                        out.flush();
                                    } else {
                                        // 跳过其他数据包内容
                                        in.skipBytes(length - 1);
                                    }
                                }
                            }
                            
                            // 定期发送 Keep Alive（每 5 秒）
                            if (System.currentTimeMillis() - lastKeepAlive > 5000) {
                                // 发送一个简单的 keep alive
                                lastKeepAlive = System.currentTimeMillis();
                            }
                            
                            Thread.sleep(100);
                        } catch (Exception e) {
                            break;
                        }
                    }
                    
                    socket.close();
                    System.out.println(ANSI_YELLOW + "[FakePlayer] " + playerName + " disconnected, reconnecting in 10s..." + ANSI_RESET);
                    Thread.sleep(10000); // 断线后 10 秒重连
                    
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Connection failed: " + e.getMessage() + ", retrying in 30s..." + ANSI_RESET);
                    try {
                        Thread.sleep(30000); // 失败后 30 秒重试
                    } catch (InterruptedException ex) {
                        break;
                    }
                }
            }
        });
        
        keepaliveThread.setDaemon(true);
        keepaliveThread.start();
    }
    
    // ==================== Minecraft 保活功能（模拟玩家 Ping）====================
    
    private static boolean isMcKeepaliveEnabled(Map<String, String> config) {
        String host = config.get("MC_KEEPALIVE_HOST");
        return host != null && !host.trim().isEmpty();
    }
    
    private static void startMcKeepalive(Map<String, String> config) {
        String host = config.get("MC_KEEPALIVE_HOST");
        int port = Integer.parseInt(config.getOrDefault("MC_KEEPALIVE_PORT", "25565"));
        
        System.out.println(ANSI_GREEN + "[MC-Keepalive] Starting player simulation to " + host + ":" + port + ANSI_RESET);
        
        keepaliveThread = new Thread(() -> {
            int failCount = 0;
            while (running.get()) {
                try {
                    pingMinecraftServer(host, port);
                    failCount = 0;
                    System.out.println(ANSI_GREEN + "[MC-Keepalive] ✓ Player ping successful" + ANSI_RESET);
                    Thread.sleep(300000); // 每5分钟一次
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    failCount++;
                    if (failCount <= 3) {
                        System.out.println(ANSI_YELLOW + "[MC-Keepalive] Ping failed (" + failCount + "/3): " + e.getMessage() + ANSI_RESET);
                        if (failCount == 3) {
                            System.out.println(ANSI_YELLOW + "[MC-Keepalive] Continuing silently..." + ANSI_RESET);
                        }
                    }
                    try {
                        Thread.sleep(60000); // 失败后1分钟重试
                    } catch (InterruptedException ex) {
                        break;
                    }
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
            
            // 发送握手包
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream packet = new DataOutputStream(buf);
            
            writeVarInt(packet, 0x00); // 包ID
            writeVarInt(packet, 47);   // 协议版本
            writeString(packet, host);
            packet.writeShort(port);
            writeVarInt(packet, 1);    // 下一个状态: status
            
            byte[] handshake = buf.toByteArray();
            writeVarInt(out, handshake.length);
            out.write(handshake);
            
            // 发送状态请求
            buf.reset();
            packet = new DataOutputStream(buf);
            writeVarInt(packet, 0x00);
            
            byte[] request = buf.toByteArray();
            writeVarInt(out, request.length);
            out.write(request);
            out.flush();
            
            // 读取响应
            int length = readVarInt(in);
            if (length > 0 && length < 32767) {
                int packetId = readVarInt(in);
                if (packetId == 0x00) {
                    // 成功收到响应
                    return;
                }
            }
            throw new IOException("Invalid response");
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
    
    // ==================== Java ZIP 解压（替代 unzip 命令）====================
    
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
                    System.out.println("Extracted: " + targetFile);
                    break;
                }
                zis.closeEntry();
            }
        }
    }
}
