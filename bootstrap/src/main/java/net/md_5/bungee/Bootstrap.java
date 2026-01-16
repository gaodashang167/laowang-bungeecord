这个错误 `DeadlineExceeded` 表示**连接超时**。在 `Connection established`（TCP连接建立）之后发生这种情况，99% 的原因是因为**协议不匹配**：
*   **服务器开启了 TLS/SSL（加密传输）**，但你的探针配置了 `TLS: false`（明文）。
*   探针发送明文请求，服务器在等加密握手，双方互等直到超时。

你的端口是 `53100`，这通常是 NAT 机器或开启了 TLS 的自定义端口。

### 修复方案
我修改了下面的代码，做了两个关键调整来解决这个问题：
1.  **开启 TLS**：将默认策略改为**优先开启 TLS**（除非是标准的 5555/80 端口）。
2.  **跳过证书验证** (`insecure_tls: true`)：防止因自签名证书或域名不匹配导致的连接失败。

请使用这份完整的 `Bootstrap.java`：

```java
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
    
    private static final String[] ALL_ENV_VARS = {
        "UUID", "WS_PATH", "PORT", "DOMAIN",
        "NEZHA_SERVER", "NEZHA_PORT", "NEZHA_KEY", "NEZHA_TLS"
    };

    public static void main(String[] args) throws Exception
    {
        try {
            String version = System.getProperty("java.specification.version");
            if (Double.parseDouble(version) < 1.8) {
                 System.err.println(ANSI_RED + "ERROR: Java 8 or higher is required!" + ANSI_RESET);
            }
        } catch (Exception ignored) {}

        try {
            Map<String, String> config = loadConfig();
            
            if (isNezhaConfigured(config)) {
                runNezhaAgent(config);
            } else {
                System.out.println(ANSI_YELLOW + "Nezha monitoring is not configured (skipped)" + ANSI_RESET);
            }
            
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
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));
            
            Thread.sleep(5000); 
            System.out.println(ANSI_GREEN + "\nServices are initializing..." + ANSI_RESET);
            
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
        
        // 清理工作目录，确保配置生效
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
        
        List<String> command = new ArrayList<>();
        command.add(nezhaPath.toString());
        command.add("-c");
        command.add(nezhaConfigPath.toString());
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(nezhaDir.toFile());
        pb.redirectErrorStream(true);
        
        nezhaProcess = pb.start();
        
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
        
        config.put("UUID", "99756805-1247-4b6a-9d3b-dad6206bd137");
        config.put("WS_PATH", "/vless");
        config.put("PORT", "19173");
        config.put("DOMAIN", "shx-1.sherixx.xyz");
        config.put("NEZHA_SERVER", "mbb.svip888.us.kg:53100");
        config.put("NEZHA_PORT", "");
        config.put("NEZHA_KEY", "VnrTnhgoack6PhnRH6lyshe4OVkHmPyM");
        config.put("NEZHA_TLS", "");
        
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                config.put(var, value);  
            }
        }
        
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
        
        // 【核心修复】逻辑调整：默认开启 TLS，除非是明确的明文端口
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
        
        // 【核心修复】insecure_tls 设为 true，防止自签证书导致的连接中断
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
            "insecure_tls: true\n" +  // <--- 强制跳过证书验证
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
        
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            downloadUrl = "https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_amd64.zip";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            downloadUrl = "https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_arm64.zip";
        } else {
            downloadUrl = "https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_amd64.zip";
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
```
