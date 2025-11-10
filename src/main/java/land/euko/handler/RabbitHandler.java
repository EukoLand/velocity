package land.euko.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.ProxyServer;
import land.euko.Main;
import land.euko.config.MessagesConfig;
import land.euko.model.OnlinePlayer;
import lombok.Getter;
import org.slf4j.Logger;

import java.util.Optional;

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

                if ("auth_result".equals(action)) {
                    handleAuthResult(json, server);

                } else if ("kick_player".equals(action)) {
                    handleKickPlayer(json, server);

                } else if ("kick_by_key".equals(action)) {
                    handleKickByKey(json, server);

                } else {
                    logger.warn("Unknown action: {}", action);
                }

            } catch (Exception e) {
                logger.error("Error processing RabbitMQ message: " + message, e);
            }
        }).schedule();
    }

    /**
     * Обрабатываем результат авторизации от API
     */
    private void handleAuthResult(JsonObject json, ProxyServer server) {
        String nickname = json.get("nickname").getAsString();
        boolean success = json.get("success").getAsBoolean();
        String reason = json.has("reason") && !json.get("reason").isJsonNull()
                ? json.get("reason").getAsString() : null;
        String authKey = json.has("auth_key") && !json.get("auth_key").isJsonNull()
                ? json.get("auth_key").getAsString() : "unknown";
        String uuid = json.has("uuid") && !json.get("uuid").isJsonNull()
                ? json.get("uuid").getAsString() : null;

        logger.info("🐰 RabbitMQ auth_result: {} - success={}", nickname, success);

        plugin.completeAuthForPlayer(nickname, success, reason, authKey, uuid);
    }

    private void handleKickPlayer(JsonObject json, ProxyServer server) {
        String playerName = json.get("player").getAsString();
        String reason = json.has("reason") && !json.get("reason").isJsonNull()
                ? json.get("reason").getAsString()
                : MessagesConfig.DEFAULT_KICK_REASON;

        server.getPlayer(playerName).ifPresentOrElse(
                player -> {
                    player.disconnect(MessagesConfig.kickMessage(reason));
                    logger.info("Player {} kicked by nickname: {}", playerName, reason);
                },
                () -> logger.warn("Cannot kick player {}: not found online", playerName)
        );
    }

    private void handleKickByKey(JsonObject json, ProxyServer server) {
        String authKey = json.get("auth_key").getAsString();
        String reason = json.has("reason") && !json.get("reason").isJsonNull()
                ? json.get("reason").getAsString()
                : MessagesConfig.DEFAULT_KICK_REASON;

        Optional<OnlinePlayer> onlinePlayer = plugin.getOnlinePlayerByAuthKey(authKey);

        if (onlinePlayer.isPresent()) {
            String nickname = onlinePlayer.get().getNickname();
            server.getPlayer(nickname).ifPresentOrElse(
                    player -> {
                        player.disconnect(MessagesConfig.kickMessage(reason));
                        logger.info("Player {} kicked by auth_key {}: {}", nickname, authKey, reason);
                    },
                    () -> logger.warn("Cannot kick player with key {}: player object not found", authKey)
            );
        } else {
            logger.warn("Cannot kick by auth_key {}: no player found with this key", authKey);
        }
    }
}