import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.*;

/**
 * Xray Argo Server - Single File Java Version
 * Compile: javac XrayArgo.java
 * Run:     java XrayArgo
 */
public class XrayArgo {

    // ============ 配置 ============
    static final String FILE_PATH   = "./tmp";
    static final String SUB_PATH    = getEnv("SUB_PATH",    "sb");
    static final int    PORT        = Integer.parseInt(getEnv("PORT", "3000"));
    static final String UUID        = getEnv("UUID",        "07721186-24d9-4962-8a27-3964206bceba");
    static final String ARGO_DOMAIN = getEnv("ARGO_DOMAIN", "karlo-hosting.cnm.ccwu.cc");
    static final String ARGO_AUTH   = getEnv("ARGO_AUTH",
            "eyJhIjoiY2YxMDY1YTFhZDk1YjIxNzUxNGY3MzRjNzgyYzlkMDkiLCJ0IjoiMzljZTI3ODktZDUxMS00ZmYyLWEyYmMtZmU0NDdlOWM2YjZiIiwicyI6Ik5HUTNaVEJqT1RrdE5tTm1aaTAwTmpBeUxUZzVZbU10TWpFeE1EVTRPRFpoTkdFMiJ9");
    static final int    ARGO_PORT   = 38080;
    static final String CFIP        = getEnv("CFIP",   "cdns.doon.eu.org");
    static final int    CFPORT      = Integer.parseInt(getEnv("CFPORT", "443"));
    static final String NAME        = getEnv("NAME",   "Node");

    // ============ 路径 ============
    static final Path pXray        = Path.of(FILE_PATH, "xray");
    static final Path pCf          = Path.of(FILE_PATH, "cf");
    static final Path pConfig      = Path.of(FILE_PATH, "config.json");
    static final Path pBootLog     = Path.of(FILE_PATH, "boot.log");
    static final Path pSub         = Path.of(FILE_PATH, "sub.txt");
    static final Path pTunnelJson  = Path.of(FILE_PATH, "tunnel.json");
    static final Path pTunnelYml   = Path.of(FILE_PATH, "tunnel.yml");

    static volatile String encodedSub = "";

    // ============ 入口 ============
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of(FILE_PATH));

        // 启动 HTTP 服务器
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            String ct;
            if (path.equals("/" + SUB_PATH)) {
                body = encodedSub.getBytes(StandardCharsets.UTF_8);
                ct = "text/plain; charset=utf-8";
            } else {
                body = "Xray Argo Server Running".getBytes(StandardCharsets.UTF_8);
                ct = "text/plain";
            }
            exchange.getResponseHeaders().set("Content-Type", ct);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();
        info("📡 HTTP server listening on port " + PORT);

        // 后台执行启动流程
        Thread.ofVirtual().start(XrayArgo::startup);
    }

    static void startup() {
        try {
            info("🚀 Starting Xray Argo server...");

            info("Step 1/6: Setting up Argo tunnel");
            setupArgoTunnel();

            info("Step 2/6: Generating Xray config");
            generateXrayConfig();

            info("Step 3/6: Downloading files");
            downloadFiles();

            info("Step 4/6: Starting processes");
            startProcesses();

            info("Step 5/6: Getting domain");
            String domain = (ARGO_DOMAIN != null && !ARGO_DOMAIN.isEmpty()) ? ARGO_DOMAIN : extractDomain(0);
            if (domain != null) info("🌐 Domain: " + domain);

            info("Step 6/6: Generating subscription");
            generateSubscription(domain);

            info("🎉 Server ready!");
        } catch (Exception e) {
            error("Startup failed: " + e.getMessage());
        }
    }

    // ============ 生成 Xray 配置 ============
    static void generateXrayConfig() throws IOException {
        String json =
            "{\n" +
            "  \"log\": { \"loglevel\": \"warning\" },\n" +
            "  \"inbounds\": [\n" +
            "    { \"port\": " + ARGO_PORT + ", \"listen\": \"127.0.0.1\", \"protocol\": \"vless\",\n" +
            "      \"settings\": { \"clients\": [{ \"id\": \"" + UUID + "\", \"flow\": \"xtls-rprx-vision\" }], \"decryption\": \"none\",\n" +
            "        \"fallbacks\": [ {\"dest\":3001}, {\"path\":\"/vless\",\"dest\":3002}, {\"path\":\"/vmess\",\"dest\":3003}, {\"path\":\"/trojan\",\"dest\":3004} ] },\n" +
            "      \"streamSettings\": { \"network\": \"tcp\" } },\n" +
            "    { \"port\": 3001, \"listen\": \"127.0.0.1\", \"protocol\": \"vless\",\n" +
            "      \"settings\": { \"clients\": [{\"id\":\"" + UUID + "\"}], \"decryption\": \"none\" },\n" +
            "      \"streamSettings\": { \"network\": \"tcp\" } },\n" +
            "    { \"port\": 3002, \"listen\": \"127.0.0.1\", \"protocol\": \"vless\",\n" +
            "      \"settings\": { \"clients\": [{\"id\":\"" + UUID + "\"}], \"decryption\": \"none\" },\n" +
            "      \"streamSettings\": { \"network\": \"ws\", \"wsSettings\": {\"path\":\"/vless\"} } },\n" +
            "    { \"port\": 3003, \"listen\": \"127.0.0.1\", \"protocol\": \"vmess\",\n" +
            "      \"settings\": { \"clients\": [{\"id\":\"" + UUID + "\",\"alterId\":0}] },\n" +
            "      \"streamSettings\": { \"network\": \"ws\", \"wsSettings\": {\"path\":\"/vmess\"} } },\n" +
            "    { \"port\": 3004, \"listen\": \"127.0.0.1\", \"protocol\": \"trojan\",\n" +
            "      \"settings\": { \"clients\": [{\"password\":\"" + UUID + "\"}] },\n" +
            "      \"streamSettings\": { \"network\": \"ws\", \"wsSettings\": {\"path\":\"/trojan\"} } }\n" +
            "  ],\n" +
            "  \"outbounds\": [{\"protocol\":\"freedom\"}]\n" +
            "}\n";
        Files.writeString(pConfig, json);
        info("✅ Xray config generated");
    }

    // ============ 设置 Argo 隧道 ============
    static void setupArgoTunnel() throws IOException {
        if (ARGO_AUTH == null || ARGO_AUTH.isEmpty()) {
            info("ℹ️  Using quick tunnel (no ARGO_AUTH)");
            return;
        }
        if (ARGO_AUTH.contains("TunnelSecret")) {
            Files.writeString(pTunnelJson, ARGO_AUTH);
            String[] parts = ARGO_AUTH.split("\"");
            String tunnelId = parts.length > 11 ? parts[11] : "tunnel";
            String yaml =
                "tunnel: " + tunnelId + "\n" +
                "credentials-file: " + pTunnelJson.toAbsolutePath() + "\n" +
                "protocol: http2\n\ningress:\n" +
                "  - hostname: " + ARGO_DOMAIN + "\n" +
                "    service: http://127.0.0.1:" + ARGO_PORT + "\n" +
                "  - service: http_status:404\n";
            Files.writeString(pTunnelYml, yaml);
            info("✅ Argo fixed tunnel configured");
        } else {
            info("✅ Argo token-based tunnel configured");
        }
    }

    // ============ 下载文件 ============
    static void downloadFiles() throws Exception {
        String[][] xrayUrls = {
            {"https://amd64.ssss.nyc.mn/web", pXray.toString(), "Xray"},
            {"https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-64.zip", pXray.toString(), "Xray"},
        };
        String[][] cfUrls = {
            {"https://amd64.ssss.nyc.mn/bot", pCf.toString(), "Cloudflared"},
            {"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64", pCf.toString(), "Cloudflared"},
        };

        tryDownload(xrayUrls, pXray);
        tryDownload(cfUrls, pCf);
    }

    static void tryDownload(String[][] sources, Path dest) throws Exception {
        if (Files.exists(dest)) {
            info("✅ " + sources[0][2] + " already exists");
            return;
        }
        for (int i = 0; i < sources.length; i++) {
            try {
                info("📥 Downloading " + sources[i][2] + " from source " + (i + 1) + "...");
                downloadFile(sources[i][0], dest);
                dest.toFile().setExecutable(true);
                info("✅ " + sources[i][2] + " downloaded");
                return;
            } catch (Exception e) {
                warn("❌ Source " + (i + 1) + " failed: " + e.getMessage());
                if (i == sources.length - 1) throw new IOException("All sources failed for " + sources[i][2]);
            }
        }
    }

    static void downloadFile(String urlStr, Path dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ============ 启动进程 ============
    static void startProcesses() throws Exception {
        exec("nohup " + pXray.toAbsolutePath() + " -c " + pConfig.toAbsolutePath() + " >/dev/null 2>&1 &");
        info("✅ Xray started");
        Thread.sleep(1000);

        if (Files.exists(pCf)) {
            String args;
            if (ARGO_AUTH != null && ARGO_AUTH.matches("[A-Z0-9a-z=]{120,250}")) {
                args = "tunnel --edge-ip-version auto --no-autoupdate --protocol http2 run --token " + ARGO_AUTH;
            } else if (ARGO_AUTH != null && ARGO_AUTH.contains("TunnelSecret")) {
                args = "tunnel --edge-ip-version auto --config " + pTunnelYml.toAbsolutePath() + " run";
            } else {
                args = "tunnel --edge-ip-version auto --no-autoupdate --protocol http2" +
                       " --logfile " + pBootLog.toAbsolutePath() +
                       " --loglevel info --url http://127.0.0.1:" + ARGO_PORT;
            }
            exec("nohup " + pCf.toAbsolutePath() + " " + args + " >/dev/null 2>&1 &");
            info("✅ Cloudflared started");
            Thread.sleep(3000);
        }
    }

    static void exec(String cmd) throws Exception {
        Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
    }

    // ============ 提取域名 ============
    static String extractDomain(int retries) throws Exception {
        try {
            if (!Files.exists(pBootLog)) {
                if (retries < 5) {
                    info("⏳ Waiting for cloudflare tunnel... (" + (retries + 1) + "/5)");
                    Thread.sleep(2000);
                    return extractDomain(retries + 1);
                }
                throw new IOException("Boot log not found");
            }
            String content = Files.readString(pBootLog);
            Matcher m = Pattern.compile("https?://([^ ]*trycloudflare\\.com)").matcher(content);
            if (m.find()) return m.group(1);
            if (retries < 5) {
                info("⏳ Extracting domain... (" + (retries + 1) + "/5)");
                Thread.sleep(2000);
                return extractDomain(retries + 1);
            }
            throw new IOException("Domain not found in logs");
        } catch (Exception e) {
            error("Domain extraction failed: " + e.getMessage());
            return null;
        }
    }

    // ============ 生成订阅 ============
    static void generateSubscription(String domain) throws IOException {
        if (domain == null || domain.isEmpty()) {
            warn("⚠️  No domain, subscription not generated");
            return;
        }

        String vmessJson = String.format(
            "{\"v\":\"2\",\"ps\":\"%s-vmess\",\"add\":\"%s\",\"port\":\"%d\"," +
            "\"id\":\"%s\",\"aid\":\"0\",\"net\":\"ws\",\"type\":\"none\"," +
            "\"host\":\"%s\",\"path\":\"/vmess?ed=2560\",\"tls\":\"tls\",\"sni\":\"%s\",\"fp\":\"firefox\"}",
            NAME, CFIP, CFPORT, UUID, domain, domain);

        String vless  = String.format(
            "vless://%s@%s:%d?encryption=none&security=tls&sni=%s&fp=firefox&type=ws&host=%s&path=%%2Fvless%%3Fed%%3D2560#%s-vless",
            UUID, CFIP, CFPORT, domain, domain, NAME);
        String vmess  = "vmess://" + b64(vmessJson);
        String trojan = String.format(
            "trojan://%s@%s:%d?security=tls&sni=%s&fp=firefox&type=ws&host=%s&path=%%2Ftrojan%%3Fed%%3D2560#%s-trojan",
            UUID, CFIP, CFPORT, domain, domain, NAME);

        String sub = vless + "\n\n" + vmess + "\n\n" + trojan + "\n";
        encodedSub = b64(sub);
        Files.writeString(pSub, encodedSub);

        System.out.println("\n════════════════════════════════════════════════════════");
        System.out.println("🎉 订阅链接生成成功！");
        System.out.println("════════════════════════════════════════════════════════");
        System.out.println("📍 Argo 域名:\n" + domain);
        System.out.println("🔑 UUID:\n" + UUID);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("📱 VLESS:\n" + vless);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("📱 VMess:\n" + vmess);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("📱 Trojan:\n" + trojan);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("📦 Base64 订阅:\n" + encodedSub);
        System.out.println("════════════════════════════════════════════════════════\n");
        info("✅ Subscription generated");
    }

    // ============ 工具方法 ============
    static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static String getEnv(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isEmpty()) ? v : def;
    }

    static void info(String msg)  { System.out.printf("[%s] %s%n", Instant.now(), msg); }
    static void error(String msg) { System.err.printf("[%s] ERROR: %s%n", Instant.now(), msg); }
    static void warn(String msg)  { System.err.printf("[%s] WARN: %s%n", Instant.now(), msg); }
}
