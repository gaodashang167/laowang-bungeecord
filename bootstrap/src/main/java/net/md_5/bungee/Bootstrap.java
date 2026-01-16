package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
    
    // 支持的环境变量
    private static final String[] ALL_ENV_VARS = {
        "UUID", "WS_PATH", "PORT", "DOMAIN",
        "NEZHA_SERVER", "NEZHA_PORT", "NEZHA_KEY", "NEZHA_TLS"
    };

    public static void main(String[] args) throws Exception
    {
        // 1. 简单的 Java 版本检查
        try {
            String version = System.getProperty("java.specification.version");
            if (Double.parseDouble(version) < 1.8) {
                 System.err.println(ANSI_RED + "ERROR: Java 8 or higher is required!" + ANSI_RESET);
            }
        } catch (Exception ignored) {}

        try {
            // 2. 加载配置
            Map<String, String> config = loadConfig();
            
            // 3. 启动哪吒监控 (优先启动，方便调试)
            if (isNezhaConfigured(config)) {
                runNezhaAgent(config);
            } else {
                System.out.println(ANSI_YELLOW + "Nezha monitoring is not configured (skipped)" + ANSI_RESET);
            }
            
            // 4. 生成 VLESS 配置信息
            String vlessUrl = generateVlessUrl(config);
            
            System.out.println(ANSI_GREEN + "\n=== VLESS+WS Configuration ===" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "UUID: " + config.get("UUID") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "WebSocket Path: " + config.get("WS_PATH") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Port: " + config.get("PORT") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Domain: " + config.get("DOMAIN") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "\n=== VLESS Node URL ===" + ANSI_RESET);
            System.out.println(ANSI_GREEN + vlessUrl + ANSI_RESET);
            System.out.println(ANSI_GREEN + "=============================" + ANSI_RESET);
            
            // 5. 启动 VLESS (Xray)
            runVlessService(config);
            
            // 6. 注册关闭钩子 (优雅退出)
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));
            
            Thread.sleep(3000); 
            System.out.println(ANSI_GREEN + "\nServices are initializing..." + ANSI_RESET);
            
            // 7. 延时清屏 (30秒后)
            new Thread(() -> {
                try {
                    Thread.sleep(30000); 
                    clearConsole();
                } catch (InterruptedException e) {}
            }).start();

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing services: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }

        // 8. 启动 BungeeCord 主程序
        BungeeCordLauncher.main(args);
    }
    
    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
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
        
        // 【核心修复1】: 强制清理哪吒工作目录。这能确保每次重启都是"全新安装"，从而强制读取配置中的 UUID。
        Path nezhaDir = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-work");
        if (Files.exists(nezhaDir)) {
            System.out.println(ANSI_YELLOW + "Cleaning up old Nezha state..." + ANSI_RESET);
            try (Stream<Path> walk = Files.walk(nezhaDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (IOException e) {
                System.err.println("Warning: Failed to clean nezha dir: " + e.getMessage());
            }
        }
        Files.createDirectories(nezhaDir);
        
        System.out.println(ANSI_GREEN + "Starting Nezha Agent..." + ANSI_RESET);
        
        // 构建启动命令
        List<String> command = new ArrayList<>();
        command.add(nezhaPath.toString());
        command.add("-c");
        command.add(nezhaConfigPath.toString());
        
        // 【核心修复2】: 尝试通过命令行参数强制指定 UUID (双重保险)
        // 注意：某些旧版 Agent 可能不支持此参数，但加上通常无害或仅报错忽略
        // command.add("--uuid"); 
        // command.add(config.get("UUID")); 
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(nezhaDir.toFile());
        pb.redirectErrorStream(true);
        
        nezhaProcess = pb.start();
        
        // 开启日志读取线程
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(nezhaProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(ANSI_GREEN + "[Nezha] " + ANSI_RESET + line);
                }
            } catch (IOException e) {
            }
        }).start();
        
        Thread.sleep(2000);
        if (nezhaProcess.isAlive()) {
             System.out.println(ANSI_GREEN + "Nezha Agent started successfully!" + ANSI_RESET);
        } else {
             System.out.println(ANSI_RED + "Nezha Agent failed to start immediately." + ANSI_RESET);
        }
    }
    
    private static void runVlessService(Map<String, String> config) throws Exception {
        Path xrayPath = downloadXray();
        Path configPath = createXrayConfig(config);
        
        ProcessBuilder pb = new ProcessBuilder(
            xrayPath.toString(),
            "run",
            "-c",
            configPath.toString()
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        
        vlessProcess = pb.start();
        System.out.println(ANSI_GREEN + "Xray process started." + ANSI_RESET);
    }
    
    private static Map<String, String> loadConfig() throws IOException {
        Map<String, String> config = new HashMap<>();
        
        // 默认配置
        config.put("UUID", "99756805-1247-4b6a-9d3b-dad6206bd137");
        config.put("WS_PATH", "/vless");
        config.put("PORT", "19173");
        config.put("DOMAIN", "shx-1.sherixx.xyz");
        config.put("NEZHA_SERVER", "mbb.svip888.us.kg:53100");
        config.put("NEZHA_PORT", "");
        config.put("NEZHA_KEY", "VnrTnhgoack6PhnRH6lyshe4OVkHmPyM");
        config.put("NEZHA_TLS", ""); 
        
        // 从环境变量覆盖
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                config.put(var, value);  
            }
        }
        
        // 读取 .env 文件
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) {
                        config.put(key, value);
                    }
                }
            }
        }
        return config;
    }
    
    private static String generateVlessUrl(Map<String, String> config) {
        try {
            String uuid = config.get("UUID");
            String wsPath = config.get("WS_PATH");
            String port = config.get("PORT");
            String domain = config.get("DOMAIN");
            if (domain == null || domain.trim().isEmpty()) domain = "localhost";
            
            return "vless://" + uuid + "@" + domain + ":" + port + 
                   "?type=ws&path=" + URLEncoder.encode(wsPath, "UTF-8") + 
                   "&host=" + domain + "#VLESS-WS";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private static Path createXrayConfig(Map<String, String> config) throws IOException {
        String configJson = String.format(
            "{\n" +
            "  \"log\": { \"loglevel\": \"warning\" },\n" +
            "  \"inbounds\": [{\n" +
            "    \"port\": %s,\n" +
            "    \"protocol\": \"vless\",\n" +
            "    \"settings\": {\n" +
            "      \"clients\": [{ \"id\": \"%s\" }],\n" +
            "      \"decryption\": \"none\"\n" +
            "    },\n" +
            "    \"streamSettings\": {\n" +
            "      \"network\": \"ws\",\n" +
            "      \"wsSettings\": { \"path\": \"%s\" }\n" +
            "    }\n" +
            "  }],\n" +
            "  \"outbounds\": [{ \"protocol\": \"freedom\" }]\n" +
            "}\n",
            config.get("PORT"), config.get("UUID"), config.get("WS_PATH")
        );
        
        Path configPath = Paths.get(System.getProperty("java.io.tmpdir"), "xray-config.json");
        Files.write(configPath, configJson.getBytes());
        return configPath;
    }
    
    private static Path createNezhaConfig(Map<String, String> config) throws IOException {
        String server = config.get("NEZHA_SERVER");
        String port = config.getOrDefault("NEZHA_PORT", "5555");
        
        // 【核心修复3】: 智能 TLS 判断
        // 如果端口不是 5555 或 80，默认开启 TLS (解决 53100 端口超时问题)
        boolean tls = true; 
        if ("5555".equals(port) || "80".equals(port)) {
            tls = false;
        }
        // 允许环境变量强制覆盖
        if (config.containsKey("NEZHA_TLS") && !config.get("NEZHA_TLS").isEmpty()) {
            tls = Boolean.parseBoolean(config.get("NEZHA_TLS"));
        }

        if (!server.contains(":")) {
            server = server + ":" + port;
        }
        
        String secret = config.get("NEZHA_KEY");
        String uuid = config.get("UUID");
        
        // 【核心修复4】: 
        // 1. 使用 `uuid` 字段。
        // 2. 开启 `insecure_tls: true`，跳过证书验证，防止自签证书或域名不匹配导致的连接断开。
        String nezhaConfig = String.format(
            "uuid: %s\n" +
            "client_secret: %s\n" +
            "debug: true\n" +
            "disable_auto_update: true\n" +
            "disable_command_execute: false\n" +
            "disable_force_update: true\n" +
            "disable_nat: false\n" +
            "disable_send_query: false\n" +
            "gpu: false\n" +
            "insecure_tls: true\n" + 
            "ip_report_period: 1800\n" +
            "report_delay: 3\n" +
            "server: %s\n" +
            "skip_connection_count: false\n" +
            "skip_procs_count: false\n" +
            "temperature: false\n" +
            "tls: %b\n",
            uuid,
            secret,
            server,
            tls
        );
        
        Path nezhaConfigPath = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-config.yml");
        Files.write(nezhaConfigPath, nezhaConfig.getBytes());
        System.out.println(ANSI_GREEN + "Nezha config created (UUID: " + uuid + ", TLS: " + tls + ", Insecure: true)" + ANSI_RESET);
        return nezhaConfigPath;
    }
    
    private static Path downloadNezhaAgent() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String downloadUrl;
        
        // 默认下载
        downloadUrl = "https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_amd64.zip";
        
        if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            downloadUrl = "https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_arm64.zip";
        }
        
        Path nezhaPath = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-agent");
        
        if (!Files.exists(nezhaPath)) {
            System.out.println(ANSI_GREEN + "Downloading Nezha Agent..." + ANSI_RESET);
            Path zipPath = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-agent.zip");
            try (InputStream in = new URL(downloadUrl).openStream()) {
                Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
            }
            extractNezhaAgent(zipPath, nezhaPath);
            Files.delete(zipPath);
            
            nezhaPath.toFile().setExecutable(true);
            System.out.println(ANSI_GREEN + "Nezha Agent downloaded." + ANSI_RESET);
        }
        return nezhaPath;
    }
    
    private static Path downloadXray() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String downloadUrl;
        String fileName = "xray-linux-64"; 
        
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            downloadUrl = "https://github.com/XTLS/Xray-core/releases/download/v1.8.4/Xray-linux-64.zip";
            fileName = "xray"; 
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            downloadUrl = "https://github.com/XTLS/Xray-core/releases/download/v1.8.4/Xray-linux-arm64-v8a.zip";
            fileName = "xray";
        } else {
             downloadUrl = "https://github.com/XTLS/Xray-core/releases/download/v1.8.4/Xray-linux-64.zip";
             fileName = "xray";
        }
        
        Path xrayPath = Paths.get(System.getProperty("java.io.tmpdir"), fileName);
        
        if (!Files.exists(xrayPath)) {
            System.out.println(ANSI_GREEN + "Downloading Xray-core..." + ANSI_RESET);
            Path zipPath = Paths.get(System.getProperty("java.io.tmpdir"), "xray.zip");
            try (InputStream in = new URL(downloadUrl).openStream()) {
                Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
            }
            extractXray(zipPath, xrayPath);
            Files.delete(zipPath);
            
            xrayPath.toFile().setExecutable(true);
            System.out.println(ANSI_GREEN + "Xray-core downloaded." + ANSI_RESET);
        }
        return xrayPath;
    }
    
    private static void extractNezhaAgent(Path zipPath, Path outputPath) throws IOException {
        try {
            new ProcessBuilder("unzip", "-o", zipPath.toString(), "nezha-agent", "-d", System.getProperty("java.io.tmpdir"))
                .start().waitFor();
            Path extracted = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-agent");
            if (Files.exists(extracted)) {
                 Files.move(extracted, outputPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IOException("Failed to extract nezha: " + e.getMessage());
        }
    }
    
    private static void extractXray(Path zipPath, Path outputPath) throws IOException {
        try {
            new ProcessBuilder("unzip", "-o", zipPath.toString(), "xray", "-d", System.getProperty("java.io.tmpdir"))
                .start().waitFor();
             Path extracted = Paths.get(System.getProperty("java.io.tmpdir"), "xray");
             if (Files.exists(extracted)) {
                 Files.move(extracted, outputPath, StandardCopyOption.REPLACE_EXISTING);
             }
        } catch (Exception e) {
            throw new IOException("Failed to extract xray: " + e.getMessage());
        }
    }
    
    private static void stopServices() {
        if (nezhaProcess != null && nezhaProcess.isAlive()) {
            nezhaProcess.destroy();
            System.out.println(ANSI_RED + "Nezha Agent terminated" + ANSI_RESET);
        }
        if (vlessProcess != null && vlessProcess.isAlive()) {
            vlessProcess.destroy();
            System.out.println(ANSI_RED + "VLESS process terminated" + ANSI_RESET);
        }
    }
}
