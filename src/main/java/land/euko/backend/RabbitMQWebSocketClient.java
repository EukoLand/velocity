package land.euko.backend;

import com.velocitypowered.api.proxy.ProxyServer;
import land.euko.Main;
import land.euko.handler.RabbitHandler;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.velocitypowered.api.scheduler.ScheduledTask;

import java.net.URI;
import java.util.concurrent.TimeUnit;

public class RabbitMQWebSocketClient extends WebSocketClient {
    private final String username;
    private final String password;
    private final String queue;
    private final Main plugin;
    private final ProxyServer server;
    private final RabbitHandler rabbitHandler;
    private boolean connected = false;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    // Heartbeat
    private ScheduledTask heartbeatTask;
    private static final long HEARTBEAT_INTERVAL = 5000;

    public RabbitMQWebSocketClient(URI serverUri, String username, String password,
                                   String queue, Main plugin, ProxyServer server, RabbitHandler rabbitHandler) {
        super(serverUri);
        this.username = username;
        this.password = password;
        this.queue = queue;
        this.plugin = plugin;
        this.server = server;
        this.rabbitHandler = rabbitHandler;
        setConnectionLostTimeout(60);
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        plugin.getLogger().info("WebSocket connection opened");

        String connectFrame = "CONNECT\n" +
                "accept-version:1.0,1.1,1.2\n" +
                "host:/\n" +
                "login:" + username + "\n" +
                "passcode:" + password + "\n" +
                "heart-beat:5000,5000\n\n\u0000";

        send(connectFrame);
    }

    @Override
    public void onMessage(String message) {
        if (message.startsWith("CONNECTED")) {
            plugin.getLogger().info("Connected to RabbitMQ STOMP");
            connected = true;
            reconnectAttempts = 0;

            startHeartbeat();

            String subscribeFrame = "SUBSCRIBE\n" +
                    "id:sub-0\n" +
                    "destination:/queue/" + queue + "\n" +
                    "ack:auto\n\n\u0000";
            send(subscribeFrame);

        } else if (message.startsWith("MESSAGE")) {
            String[] parts = message.split("\n\n", 2);
            if (parts.length > 1) {
                String body = parts[1].replace("\u0000", "").trim();
                rabbitHandler.handleRabbitMessage(body, server);
            }

        } else if (message.startsWith("ERROR")) {
            plugin.getLogger().warn("STOMP Error: {}", message);
        } else if (message.startsWith("RECEIPT")) {
            plugin.getLogger().debug("Received RECEIPT");
        } else if (message.equals("\n")) {
            // Получили heartbeat от сервера - это нормально
            plugin.getLogger().debug("Received heartbeat from server");
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        plugin.getLogger().warn("WebSocket closed: {} (code: {})", reason, code);
        connected = false;

        // Останавливаем heartbeat
        stopHeartbeat();

        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++;
            long delaySeconds = Math.min(reconnectAttempts * 5L, 30L);

            plugin.getLogger().info("Reconnecting in {} seconds... (attempt {}/{})",
                    delaySeconds, reconnectAttempts, MAX_RECONNECT_ATTEMPTS);

            server.getScheduler().buildTask(plugin, () -> {
                try {
                    reconnect();
                } catch (Exception e) {
                    plugin.getLogger().error("Reconnection failed", e);
                }
            }).delay(delaySeconds, TimeUnit.SECONDS).schedule();
        } else {
            plugin.getLogger().error("Max reconnection attempts reached");
        }
    }

    @Override
    public void onError(Exception ex) {
        plugin.getLogger().error("WebSocket error", ex);
    }

    private void startHeartbeat() {
        stopHeartbeat();

        heartbeatTask = server.getScheduler()
                .buildTask(plugin, () -> {
                    if (isOpen() && connected) {
                        try {
                            send("\n");
                            plugin.getLogger().debug("Sent heartbeat to RabbitMQ");
                        } catch (Exception e) {
                            plugin.getLogger().warn("Failed to send heartbeat: {}", e.getMessage());
                        }
                    }
                })
                .repeat(HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS)
                .schedule();

        plugin.getLogger().info("Heartbeat started (interval: {}ms)", HEARTBEAT_INTERVAL);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            plugin.getLogger().debug("Heartbeat stopped");
        }
    }

    public void sendStompMessage(String destination, String message) {
        if (!connected) {
            plugin.getLogger().warn("Cannot send message: not connected to RabbitMQ");
            return;
        }

        String sendFrame = "SEND\n" +
                "destination:" + destination + "\n" +
                "content-type:application/json\n" +
                "content-length:" + message.length() + "\n\n" +
                message + "\u0000";

        send(sendFrame);
    }

    public boolean isConnected() {
        return connected && isOpen();
    }

    @Override
    public void close() {
        stopHeartbeat();
        super.close();
    }
}