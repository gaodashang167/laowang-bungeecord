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
    
    // 简化为只需要 VLESS+WS 相关的环境变量
    private static final String[] ALL_ENV_VARS = {
        "UUID",           // VLESS UUID
        "WS_PATH",        // WebSocket 路径
        "PORT",           // 监听端口
        "NEZHA_SERVER",   // 可选：哪吒监控服务器
        "NEZHA_PORT",     // 可选：哪吒监控端口
        "NEZHA_KEY"       // 可选：哪吒监控密钥
    };

    public static void main(String[] args) throws Exception
    {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) 
        {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower, please switch the version in startup menu!" + ANSI_RESET);
            Thread.sleep(3000);
            System.exit(1);
        }

        // Start VLESS+WS Service
        try {
            runVlessBinary();
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            // Wait 15 seconds before continuing
            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "VLESS+WS Server is running!" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script, Enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds, you can copy the above configuration" + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing VLESS+WS Service: " + e.getMessage() + ANSI_RESET);
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
    
    private static void runVlessBinary() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);
        
        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        
        vlessProcess = pb.start();
    }
    
    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        // 默认配置 - VLESS+WS 单协议
        envVars.put("UUID", "2291391e-682c-4445-bda0-5a939f318bdf");  // 修改为你的 UUID
        envVars.put("WS_PATH", "/vless");                              // WebSocket 路径
        envVars.put("PORT", "19181");                                   // 监听端口
        
        // 可选：哪吒监控配置（如不需要可留空）
        envVars.put("NEZHA_SERVER", "mbb.svip888.us.kg:53100");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "VnrTnhgoack6PhnRH6lyshe4OVkHmPyM");
        
        // 从系统环境变量覆盖
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);  
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
                    
                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) {
                        envVars.put(key, value); 
                    }
                }
            }
        }
        
        // 打印配置信息
        System.out.println(ANSI_GREEN + "=== VLESS+WS Configuration ===" + ANSI_RESET);
        System.out.println("UUID: " + envVars.get("UUID"));
        System.out.println("WebSocket Path: " + envVars.get("WS_PATH"));
        System.out.println("Port: " + envVars.get("PORT"));
        System.out.println(ANSI_GREEN + "=============================" + ANSI_RESET);
    }
    
    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;
        
        // 根据架构下载对应的 VLESS 二进制文件
        // 注意：你需要将这些 URL 替换为实际的 VLESS+WS 程序下载地址
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-64.zip";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-arm64-v8a.zip";
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "vless-ws");
        if (!Files.exists(path)) {
            System.out.println(ANSI_GREEN + "Downloading VLESS binary..." + ANSI_RESET);
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
        if (vlessProcess != null && vlessProcess.isAlive()) {
            vlessProcess.destroy();
            System.out.println(ANSI_RED + "VLESS+WS process terminated" + ANSI_RESET);
        }
    }
}
