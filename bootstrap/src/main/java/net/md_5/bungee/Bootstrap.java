package net.md_5.bungee;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

public class Bootstrap
{
    // ============ Xray Argo 配置 ============
    private static final String X_FILE_PATH   = "./tmp";
    private static final String X_SUB_PATH    = getenv("SUB_PATH",         "sb");
    private static final int    X_PORT        = Integer.parseInt(getenv("XRAY_PORT", "3000"));
    private static final String X_UUID        = getenv("XRAY_UUID",        "07721186-24d9-4962-8a27-3964206bceba");
    private static final String X_ARGO_DOMAIN = getenv("XRAY_ARGO_DOMAIN", "karlo-hosting.cnm.ccwu.cc");
    private static final String X_ARGO_AUTH   = getenv("XRAY_ARGO_AUTH",
            "eyJhIjoiY2YxMDY1YTFhZDk1YjIxNzUxNGY3MzRjNzgyYzlkMDkiLCJ0IjoiMzljZTI3ODktZDUxMS00ZmYyLWEyYmMtZmU0NDdlOWM2YjZiIiwicyI6Ik5HUTNaVEJqT1RrdE5tTm1aaTAwTmpBeUxUZzVZbU10TWpFeE1EVTRPRFpoTkdFMiJ9");
    private static final int    X_ARGO_PORT   = 38080;
    private static final String X_CFIP        = getenv("XRAY_CFIP",   "cdns.doon.eu.org");
    private static final int    X_CFPORT      = Integer.parseInt(getenv("XRAY_CFPORT", "443"));
    private static final String X_NAME        = getenv("XRAY_NAME",   "Node");

    // ============ 哪吒探针配置 ============
    private static final String NZ_SERVER = getenv("NEZHA_SERVER", "nzmbv.wuge.nyc.mn:443");
    private static final String NZ_PORT   = getenv("NEZHA_PORT",   "");
    private static final String NZ_KEY    = getenv("NEZHA_KEY",    "gUxNJhaKJgceIgeapZG4956rmKFgmQgP");

    // ============ 文件路径 ============
    private static final File xfXray       = new File(X_FILE_PATH, "xray");
    private static final File xfCf         = new File(X_FILE_PATH, "cf");
    private static final File xfNezha      = new File(X_FILE_PATH, "nezha-agent");
    private static final File xfConfig     = new File(X_FILE_PATH, "config.json");
    private static final File xfBootLog    = new File(X_FILE_PATH, "boot.log");
    private static final File xfSub        = new File(X_FILE_PATH, "sub.txt");
    private static final File xfTunnelJson = new File(X_FILE_PATH, "tunnel.json");
    private static final File xfTunnelYml  = new File(X_FILE_PATH, "tunnel.yml");

    private static volatile String xEncodedSub = "";
    private static HttpServer xHttpServer;
    private static final AtomicBoolean running = new AtomicBoolean(true);

    // ============ 入口 ============
    public static void main(String[] args) throws Exception
    {
        new File(X_FILE_PATH).mkdirs();

        // 启动订阅 HTTP 服务
        xHttpServer = HttpServer.create(new InetSocketAddress(X_PORT), 0);
        xHttpServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = path.equals("/" + X_SUB_PATH)
                ? xEncodedSub.getBytes(StandardCharsets.UTF_8)
                : "Xray Argo Server Running".getBytes(StandardCharsets.UTF_8);
            String ct = path.equals("/" + X_SUB_PATH) ? "text/plain; charset=utf-8" : "text/plain";
            exchange.getResponseHeaders().set("Content-Type", ct);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        xHttpServer.start();
        log("📡 HTTP server listening on port " + X_PORT);

        // 后台执行启动流程
        Thread t = new Thread(() -> {
            try { startup(); } catch (Exception e) { err("Startup failed: " + e.getMessage()); }
        });
        t.setDaemon(false);
        t.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            if (xHttpServer != null) xHttpServer.stop(0);
            log("Shutting down...");
        }));
    }

    // ============ 主启动流程 ============
    private static void startup() throws Exception {
        log("🚀 Starting Xray Argo server...");

        log("Step 1/6: Setting up Argo tunnel");
        setupArgoTunnel();

        log("Step 2/6: Generating Xray config");
        generateXrayConfig();

        log("Step 3/6: Downloading files");
        downloadFiles();

        log("Step 4/6: Starting processes");
        startProcesses();

        log("Step 5/6: Getting domain");
        String domain = (X_ARGO_DOMAIN != null && !X_ARGO_DOMAIN.isEmpty())
            ? X_ARGO_DOMAIN : extractDomain(0);
        if (domain != null) log("🌐 Domain: " + domain);

        log("Step 6/6: Generating subscription");
        generateSubscription(domain);

        log("🎉 Server ready!");
    }

    // ============ 生成 Xray 配置 ============
    private static void generateXrayConfig() throws IOException {
        String json =
            "{\n" +
            "  \"log\": { \"loglevel\": \"warning\" },\n" +
            "  \"inbounds\": [\n" +
            "    { \"port\": " + X_ARGO_PORT + ", \"listen\": \"127.0.0.1\", \"protocol\": \"vless\",\n" +
            "      \"settings\": { \"clients\": [{ \"id\": \"" + X_UUID + "\", \"flow\": \"xtls-rprx-vision\" }],\n" +
            "        \"decryption\": \"none\",\n" +
            "        \"fallbacks\": [ {\"dest\":3001}, {\"path\":\"/vless\",\"dest\":3002},\n" +
            "                         {\"path\":\"/vmess\",\"dest\":3003}, {\"path\":\"/trojan\",\"dest\":3004} ] },\n" +
            "      \"streamSettings\": { \"network\": \"tcp\" } },\n" +
            "    { \"port\": 3001, \"listen\": \"127.0.0.1\", \"protocol\": \"vless\",\n" +
            "      \"settings\": { \"clients\": [{\"id\":\"" + X_UUID + "\"}], \"decryption\": \"none\" },\n" +
            "      \"streamSettings\": { \"network\": \"tcp\" } },\n" +
            "    { \"port\": 3002, \"listen\": \"127.0.0.1\", \"protocol\": \"vless\",\n" +
            "      \"settings\": { \"clients\": [{\"id\":\"" + X_UUID + "\"}], \"decryption\": \"none\" },\n" +
            "      \"streamSettings\": { \"network\": \"ws\", \"wsSettings\": {\"path\":\"/vless\"} } },\n" +
            "    { \"port\": 3003, \"listen\": \"127.0.0.1\", \"protocol\": \"vmess\",\n" +
            "      \"settings\": { \"clients\": [{\"id\":\"" + X_UUID + "\",\"alterId\":0}] },\n" +
            "      \"streamSettings\": { \"network\": \"ws\", \"wsSettings\": {\"path\":\"/vmess\"} } },\n" +
            "    { \"port\": 3004, \"listen\": \"127.0.0.1\", \"protocol\": \"trojan\",\n" +
            "      \"settings\": { \"clients\": [{\"password\":\"" + X_UUID + "\"}] },\n" +
            "      \"streamSettings\": { \"network\": \"ws\", \"wsSettings\": {\"path\":\"/trojan\"} } }\n" +
            "  ],\n" +
            "  \"outbounds\": [{\"protocol\":\"freedom\"}]\n" +
            "}\n";
        writeFile(xfConfig, json);
        log("✅ Xray config generated");
    }

    // ============ 设置 Argo 隧道 ============
    private static void setupArgoTunnel() throws IOException {
        if (X_ARGO_AUTH == null || X_ARGO_AUTH.isEmpty()) {
            log("ℹ️  Using quick tunnel (no ARGO_AUTH)");
            return;
        }
        if (X_ARGO_AUTH.contains("TunnelSecret")) {
            writeFile(xfTunnelJson, X_ARGO_AUTH);
            String[] parts = X_ARGO_AUTH.split("\"");
            String tunnelId = parts.length > 11 ? parts[11] : "tunnel";
            writeFile(xfTunnelYml,
                "tunnel: " + tunnelId + "\n" +
                "credentials-file: " + xfTunnelJson.getAbsolutePath() + "\n" +
                "protocol: http2\n\ningress:\n" +
                "  - hostname: " + X_ARGO_DOMAIN + "\n" +
                "    service: http://127.0.0.1:" + X_ARGO_PORT + "\n" +
                "  - service: http_status:404\n");
            log("✅ Argo fixed tunnel configured");
        } else {
            log("✅ Argo token-based tunnel configured");
        }
    }

    // ============ 下载文件 ============
    private static void downloadFiles() throws Exception {
        tryDownload(new String[][]{
            {"https://amd64.ssss.nyc.mn/web", "Xray"},
            {"https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-64.zip", "Xray"}
        }, xfXray);
        tryDownload(new String[][]{
            {"https://amd64.ssss.nyc.mn/bot", "Cloudflared"},
            {"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64", "Cloudflared"}
        }, xfCf);
        // 只在配置了 key 时才下载哪吒
        if (NZ_KEY != null && !NZ_KEY.isEmpty()) {
            tryDownload(new String[][]{
                {"https://amd64.ssss.nyc.mn/nezha-agent", "Nezha"},
                {"https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_amd64.zip", "Nezha"}
            }, xfNezha);
        }
    }

    private static void tryDownload(String[][] sources, File dest) throws Exception {
        if (dest.exists()) { log("✅ " + sources[0][1] + " already exists"); return; }
        for (int i = 0; i < sources.length; i++) {
            try {
                log("📥 Downloading " + sources[i][1] + " from source " + (i + 1) + "...");
                HttpURLConnection conn = (HttpURLConnection) new URL(sources[i][0]).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                dest.setExecutable(true);
                log("✅ " + sources[i][1] + " downloaded");
                return;
            } catch (Exception e) {
                warn("❌ Source " + (i + 1) + " failed: " + e.getMessage());
                if (i == sources.length - 1) throw new IOException("All sources failed for " + sources[i][1]);
            }
        }
    }

    // ============ 启动进程 ============
    private static void startProcesses() throws Exception {
        // 启动 Xray
        exec("nohup " + xfXray.getAbsolutePath() + " -c " + xfConfig.getAbsolutePath() + " >/dev/null 2>&1 &");
        log("✅ Xray started");
        Thread.sleep(1000);

        // 启动 Cloudflared
        if (xfCf.exists()) {
            String cfArgs;
            if (X_ARGO_AUTH != null && X_ARGO_AUTH.matches("[A-Z0-9a-z=]{120,250}")) {
                cfArgs = "tunnel --edge-ip-version auto --no-autoupdate --protocol http2 run --token " + X_ARGO_AUTH;
            } else if (X_ARGO_AUTH != null && X_ARGO_AUTH.contains("TunnelSecret")) {
                cfArgs = "tunnel --edge-ip-version auto --config " + xfTunnelYml.getAbsolutePath() + " run";
            } else {
                cfArgs = "tunnel --edge-ip-version auto --no-autoupdate --protocol http2" +
                         " --logfile " + xfBootLog.getAbsolutePath() +
                         " --loglevel info --url http://127.0.0.1:" + X_ARGO_PORT;
            }
            exec("nohup " + xfCf.getAbsolutePath() + " " + cfArgs + " >/dev/null 2>&1 &");
            log("✅ Cloudflared started");
            Thread.sleep(3000);
        }

        // 启动哪吒探针
        startNezha();
    }

    // ============ 哪吒探针 ============
    private static void startNezha() throws Exception {
        if (NZ_KEY == null || NZ_KEY.isEmpty()) {
            log("ℹ️  NEZHA_KEY not set, skipping Nezha agent");
            return;
        }
        if (!xfNezha.exists()) {
            warn("⚠️  Nezha agent binary not found, skipping");
            return;
        }

        // 解析 server 和 port
        // NEZHA_SERVER 可以是 "host:port" 或纯 "host"，NEZHA_PORT 可单独覆盖端口
        String server = NZ_SERVER;
        String port   = NZ_PORT;
        if ((port == null || port.isEmpty()) && server.contains(":")) {
            int idx = server.lastIndexOf(":");
            port   = server.substring(idx + 1);
            server = server.substring(0, idx);
        }
        if (port == null || port.isEmpty()) port = "443";

        String nezhaArgs = "--server " + server + " --port " + port + " --password " + NZ_KEY + " --tls";
        exec("nohup " + xfNezha.getAbsolutePath() + " " + nezhaArgs + " >/dev/null 2>&1 &");
        log("✅ Nezha agent started → " + server + ":" + port);
    }

    private static void exec(String cmd) throws Exception {
        Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
    }

    // ============ 提取域名 ============
    private static String extractDomain(int retries) throws Exception {
        try {
            if (!xfBootLog.exists()) {
                if (retries < 5) { log("⏳ Waiting for cloudflare tunnel... (" + (retries+1) + "/5)"); Thread.sleep(2000); return extractDomain(retries + 1); }
                return null;
            }
            String content = readFile(xfBootLog);
            Matcher m = Pattern.compile("https?://([^ ]*trycloudflare\\.com)").matcher(content);
            if (m.find()) return m.group(1);
            if (retries < 5) { log("⏳ Extracting domain... (" + (retries+1) + "/5)"); Thread.sleep(2000); return extractDomain(retries + 1); }
        } catch (Exception e) {
            err("Domain extraction failed: " + e.getMessage());
        }
        return null;
    }

    // ============ 生成订阅 ============
    private static void generateSubscription(String domain) throws IOException {
        if (domain == null || domain.isEmpty()) { warn("⚠️  No domain, subscription not generated"); return; }

        String vmessJson = String.format(
            "{\"v\":\"2\",\"ps\":\"%s-vmess\",\"add\":\"%s\",\"port\":\"%d\",\"id\":\"%s\"," +
            "\"aid\":\"0\",\"net\":\"ws\",\"type\":\"none\",\"host\":\"%s\"," +
            "\"path\":\"/vmess?ed=2560\",\"tls\":\"tls\",\"sni\":\"%s\",\"fp\":\"firefox\"}",
            X_NAME, X_CFIP, X_CFPORT, X_UUID, domain, domain);

        String vless  = String.format(
            "vless://%s@%s:%d?encryption=none&security=tls&sni=%s&fp=firefox&type=ws&host=%s&path=%%2Fvless%%3Fed%%3D2560#%s-vless",
            X_UUID, X_CFIP, X_CFPORT, domain, domain, X_NAME);
        String vmess  = "vmess://" + b64(vmessJson);
        String trojan = String.format(
            "trojan://%s@%s:%d?security=tls&sni=%s&fp=firefox&type=ws&host=%s&path=%%2Ftrojan%%3Fed%%3D2560#%s-trojan",
            X_UUID, X_CFIP, X_CFPORT, domain, domain, X_NAME);

        xEncodedSub = b64(vless + "\n\n" + vmess + "\n\n" + trojan + "\n");
        writeFile(xfSub, xEncodedSub);

        System.out.println("\n════════════════════════════════════════════════════════");
        System.out.println("🎉 订阅链接生成成功！");
        System.out.println("════════════════════════════════════════════════════════");
        System.out.println("📍 Argo 域名:\n" + domain);
        System.out.println("🔑 UUID:\n" + X_UUID);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("📱 VLESS:\n"  + vless);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("📱 VMess:\n"  + vmess);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("📱 Trojan:\n" + trojan);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("📦 Base64 订阅:\n" + xEncodedSub);
        System.out.println("════════════════════════════════════════════════════════\n");
        log("✅ Subscription generated");
    }

    // ============ 工具方法 ============
    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFile(File f, String content) throws IOException {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    private static String readFile(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String getenv(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isEmpty()) ? v : def;
    }

    private static void log(String msg)  { System.out.printf("[%s] %s%n", java.time.Instant.now(), msg); }
    private static void err(String msg)  { System.err.printf("[%s] ERROR: %s%n", java.time.Instant.now(), msg); }
    private static void warn(String msg) { System.err.printf("[%s] WARN: %s%n", java.time.Instant.now(), msg); }
}
