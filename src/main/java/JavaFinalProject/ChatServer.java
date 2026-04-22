package JavaFinalProject;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.framing.CloseFrame;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ChatServer extends WebSocketServer {

    private final Map<WebSocket, String> userBySocket = new ConcurrentHashMap<>();
    private final Map<String, String> displayNameByUser = new ConcurrentHashMap<>();

    private static final String DB_URL = "jdbc:mysql://localhost:3306/chat_app?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "Shorya@11@sql";

    public ChatServer(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String localIp = getLocalIp();
        conn.send("WELCOME_IP " + localIp);
        conn.send("WELCOME Please authenticate with: AUTH username password");
        System.out.println("New connection from " + conn.getRemoteSocketAddress());
    }

    private String getLocalIp() {
        String bestIp = "localhost";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                String name = iface.getDisplayName().toLowerCase();
                
                // Prioritize Wi-Fi / Wireless adapters
                if (iface.isLoopback() || !iface.isUp()) continue;
                
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        String ip = addr.getHostAddress();
                        // If it's a Wi-Fi adapter, return it immediately as the "Exact" one
                        if (name.contains("wi-fi") || name.contains("wireless") || name.contains("wlan")) {
                            return ip;
                        }
                        // Otherwise, save it as a backup
                        if (bestIp.equals("localhost")) bestIp = ip;
                    }
                }
            }
        } catch (Exception e) {}
        return bestIp;
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String username = userBySocket.remove(conn);
        if (username != null) {
            broadcastSystem(username + " has left the chat.");
            System.out.println("User " + username + " disconnected.");
        } else {
            System.out.println("An unauthenticated client disconnected.");
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            if (!userBySocket.containsKey(conn)) {

                if (message != null && message.startsWith("AUTH ")) {
                    String[] parts = message.split("\\s+", 3);
                    if (parts.length < 3) {
                        conn.send("AUTH_FAIL Invalid auth format. Use: AUTH username password");
                        conn.close(CloseFrame.NORMAL, "Invalid auth");
                        return;
                    }
                    String username = parts[1];
                    String password = parts[2];
                    if (authenticate(username, password)) {
                        userBySocket.put(conn, username);
                        String disp = getDisplayName(username);
                        displayNameByUser.put(username, disp);
                        conn.send("AUTH_OK " + disp);
                        sendRecentMessages(conn, 50);
                        broadcastSystem(disp + " has joined the chat.");
                        System.out.println("User authenticated: " + username);
                    } else {
                        conn.send("AUTH_FAIL Invalid credentials");
                        conn.close(CloseFrame.NORMAL, "Auth failed");
                    }
                } else if (message != null && message.startsWith("REG ")) {
                    String[] parts = message.split("\\s+", 4);
                    if (parts.length < 4) {
                        conn.send("REG_ERR Invalid registration format. Use: REG username password display_name");
                        return;
                    }
                    String username = parts[1];
                    String password = parts[2];
                    String displayName = parts[3];

                    if (register(username, password, displayName)) {
                        conn.send("REG_OK Registration successful! You can now log in.");
                        System.out.println("New user registered: " + username);
                    } else {
                        conn.send("REG_ERR Username already exists or database error.");
                    }
                } else {
                    conn.send("AUTH_FAIL You must authenticate first. Send: AUTH username password");
                    conn.close(CloseFrame.NORMAL, "Auth required");
                }
            } else {
                String username = userBySocket.get(conn);
                String displayName = displayNameByUser.getOrDefault(username, username);
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());

                if (message != null && message.startsWith("DEL ")) {
                    handleDelete(conn, message.substring(4).trim());
                } else if (message != null && message.equals("CLEAR_ALL")) {
                    handleClearAll(conn);
                } else {
                    long id = saveMessage(username, message);
                    String outgoing = String.format("MSG %d %s %s %s %s", id, timestamp, username, escape(displayName),
                            escape(message));
                    broadcast(outgoing);
                    System.out.println("[" + timestamp + "] " + username + " (ID:" + id + "): " + message);
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            conn.send("ERROR " + ex.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("Server error: " + ex.getMessage());
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("ChatServer started on " + getAddress());
        setConnectionLostTimeout(0);
        setConnectionLostTimeout(100);
    }

    private void broadcastSystem(String text) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
        String out = String.format("SYS %s %s", timestamp, escape(text));
        broadcast(out);
    }

    private long saveMessage(String username, String content) {
        String sql = "INSERT INTO messages (sender_username, content) VALUES (?, ?)";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, content);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException ex) {
            System.err.println("Error saving message: " + ex.getMessage());
        }
        return -1;
    }

    private void sendRecentMessages(WebSocket conn, int limit) {
        String sql = "SELECT sender_username, content, timestamp FROM messages ORDER BY id DESC LIMIT ?";
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> messages = new ArrayList<>();
                while (rs.next()) {
                    long id = rs.getLong("id");
                    Timestamp ts = rs.getTimestamp("timestamp");
                    String sender = rs.getString("sender_username");
                    String content = rs.getString("content");
                    String line = String.format("HIST %d %s %s %s", id,
                            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(ts), sender, escape(content));
                    messages.add(line);
                }

                Collections.reverse(messages);
                for (String m : messages)
                    conn.send(m);
            }
        } catch (SQLException ex) {
            System.err.println("Error retrieving history: " + ex.getMessage());
        }
    }

    private boolean authenticate(String username, String password) {
        String sql = "SELECT id FROM users WHERE username=? AND password=?";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, password);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            System.err.println("Auth error: " + ex.getMessage());
            return false;
        }
    }

    private boolean register(String username, String password, String displayName) {
        String sql = "INSERT INTO users (username, password, display_name) VALUES (?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, password);
            ps.setString(3, displayName);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException ex) {
            System.err.println("Registration error: " + ex.getMessage());
            return false;
        }
    }

    private void handleDelete(WebSocket conn, String idStr) {
        try {
            long id = Long.parseLong(idStr);
            String currentUser = userBySocket.get(conn);
            String owner = "";

            // Check ownership
            try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                    PreparedStatement ps = c.prepareStatement("SELECT sender_username FROM messages WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) owner = rs.getString("sender_username");
                }
            }

            if (currentUser.equals("admin") || currentUser.equals(owner)) {
                try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                        PreparedStatement ps = c.prepareStatement("DELETE FROM messages WHERE id=?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                    broadcast("DEL_UI " + id);
                    System.out.println("Message deleted by " + currentUser + ": ID " + id);
                }
            } else {
                conn.send("ERROR You don't have permission to delete this message.");
            }
        } catch (Exception e) {
            conn.send("ERROR Invalid delete request.");
        }
    }

    private void handleClearAll(WebSocket conn) {
        String currentUser = userBySocket.get(conn);
        if ("admin".equals(currentUser)) {
            try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                    Statement st = c.createStatement()) {
                st.executeUpdate("TRUNCATE TABLE messages");
                broadcast("CLEAR_UI");
                broadcastSystem("Chat history has been cleared by admin.");
                System.out.println("Chat history cleared by admin.");
            } catch (SQLException e) {
                conn.send("ERROR Failed to clear chat: " + e.getMessage());
            }
        } else {
            conn.send("ERROR Only admin can clear all chats.");
        }
    }

    private String getDisplayName(String username) {
        String sql = "SELECT display_name FROM users WHERE username=?";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String d = rs.getString("display_name");
                    return d != null && !d.trim().isEmpty() ? d : username;
                }
            }
        } catch (SQLException ex) {
            System.err.println("Display name lookup error: " + ex.getMessage());
        }
        return username;
    }

    private String escape(String s) {
        return s.replace("\n", " ").replace("\r", " ");
    }

    public static void main(String[] args) {

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("MySQL JDBC driver not found on classpath.");
        }

        int port = 8080;
        ChatServer server = new ChatServer(port);
        server.setReuseAddr(true);
        server.start();
        System.out.println("WebSocket Server listening on port: " + port);

        // Start HTTP Server on port 8081
        startHttpServer(8081);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop();
                System.out.println("Server stopped.");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }));
    }

    private static void startHttpServer(int port) {
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            httpServer.createContext("/", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    // Corrected path to your index.html
                    File file = new File("chat_application/client/index.html");
                    if (!file.exists()) {
                        String error = "Error: chat_application/client/index.html not found!";
                        exchange.sendResponseHeaders(404, error.length());
                        OutputStream os = exchange.getResponseBody();
                        os.write(error.getBytes());
                        os.close();
                        return;
                    }
                    byte[] response = Files.readAllBytes(Paths.get("chat_application/client/index.html"));
                    exchange.sendResponseHeaders(200, response.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(response);
                    os.close();
                }
            });
            httpServer.setExecutor(null);
            httpServer.start();
            System.out.println("Web Server (HTTP) started on port: " + port);
        } catch (IOException e) {
            System.err.println("Failed to start HTTP server: " + e.getMessage());
        }
    }
}
