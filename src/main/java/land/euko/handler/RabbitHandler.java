package land.euko.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.ProxyServer;
import land.euko.Main;
import lombok.Getter;
import org.slf4j.Logger;

public class RabbitHandler {

    @Getter
    private final Logger logger;
    private final Main plugin;
    private final Gson gson = new Gson();

    public RabbitHandler(Logger logger, Main plugin) {
        this.logger = logger;
        this.plugin = plugin;
    }

    public void handleRabbitMessage(String message, ProxyServer server) {
        server.getScheduler().buildTask(plugin, () -> {
            try {
                JsonObject json = gson.fromJson(message, JsonObject.class);
                String action = json.has("action") ? json.get("action").getAsString() : null;

                if ("broadcast".equals(action)) {
                    String text = json.get("message").getAsString();
                    server.sendMessage(net.kyori.adventure.text.Component.text(text));
                    logger.info("Broadcast message sent: {}", text);

                } else if ("execute_command".equals(action)) {
                    String command = json.get("command").getAsString();
                    server.getCommandManager().executeAsync(server.getConsoleCommandSource(), command);
                    logger.info("Command executed: {}", command);

                } else if ("kick_player".equals(action)) {
                    String playerName = json.get("player").getAsString();
                    server.getPlayer(playerName).ifPresent(player -> {
                        String reason = json.has("reason") ? json.get("reason").getAsString() : "Kicked";
                        player.disconnect(net.kyori.adventure.text.Component.text(reason));
                        logger.info("Player {} kicked: {}", playerName, reason);
                    });

                } else {
                    logger.warn("Unknown action received: {}", action);
                }

            } catch (Exception e) {
                logger.error("Error processing RabbitMQ message: " + message, e);
            }
        }).schedule();
    }
}