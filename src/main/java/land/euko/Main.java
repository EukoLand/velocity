package land.euko;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.GameProfile;
import land.euko.backend.RabbitMQWebSocketClient;
import land.euko.config.Config;
import land.euko.config.MessagesConfig;
import land.euko.handler.PreLoginAuthHandler;
import land.euko.handler.RabbitHandler;
import land.euko.model.OnlinePlayer;
import lombok.Getter;
import org.slf4j.Logger;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "eukovelocity",
        name = "EukoVelocity",
        version = "1.0.0",
        url = "https://euko.land",
        authors = {"Tokishu"}
)
public class Main {

    @Getter
    private final Logger logger;

    @Getter
    private final ProxyServer server;

    private final Path dataDirectory;

    private RabbitMQWebSocketClient wsClient;
    private RabbitHandler rabbitHandler;
    private final Gson gson = new Gson();

    // Активные обработчики авторизации (для связи с RabbitHandler)
    private final Map<String, PreLoginAuthHandler> activeHandlers = new ConcurrentHashMap<>();

    // Готовые результаты авторизации (для GameProfileRequest)
    private final Map<String, PreLoginAuthHandler.AuthResult> authResults = new ConcurrentHashMap<>();

    // Онлайн игроки
    private final Map<String, OnlinePlayer> onlinePlayers = new ConcurrentHashMap<>();

    @Inject
    public Main(Logger logger, ProxyServer server, @DataDirectory Path dataDirectory) {
        this.logger = logger;
        this.server = server;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("Запуск EukoVelocity...");

        try {
            File configFile = new File(dataDirectory.toFile(), "config.yml");
            Config.IMP.reload(configFile);
            logger.info("Конфиг загружен из: {}", configFile.getAbsolutePath());
        } catch (Exception e) {
            logger.error("Ошибка загрузки конфига: {}", e.getMessage(), e);
            return;
        }

        this.rabbitHandler = new RabbitHandler(logger, this);

        if (Config.IMP.RABBIT.ENABLED) {
            try {
                connectToRabbitMQ(Config.IMP.RABBIT.WS_URL,
                        Config.IMP.RABBIT.USERNAME,
                        Config.IMP.RABBIT.PASSWORD,
                        Config.IMP.RABBIT.QUEUE);
            } catch (Exception e) {
                logger.error("Ошибка rabbit: {}", e.getMessage(), e);
            }
        } else {
            logger.info("Rabbit отключен");
        }

        logger.info("╔═══════════════════════════════════════════╗");
        logger.info("║  EukoVelocity успешно запущен!            ║");
        logger.info("║  By Tokishu :3                            ║");
        logger.info("╚═══════════════════════════════════════════╝");
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onPreLogin(PreLoginEvent event) {
        String username = event.getUsername();
        String lowerUsername = username.toLowerCase();

        logger.info("PreLogin для игрока {}, ожидаем авторизацию...", username);

        PreLoginAuthHandler handler = new PreLoginAuthHandler(this, username);
        activeHandlers.put(lowerUsername, handler);

        try {
            PreLoginAuthHandler.AuthResult result = handler.getAuthFuture()
                    .get(15, TimeUnit.SECONDS);

            logger.info("Auth result для {}: success={}, key={}, uuid={}",
                    username, result.isSuccess(), result.getAuthKey(), result.getUuid());

            if (result.isSuccess()) {
                try {
                    UUID.fromString(result.getUuid());

                    authResults.put(lowerUsername, result);
                    logger.info("Авторизация успешна для {}", username);

                } catch (IllegalArgumentException e) {
                    logger.error("Неверный формат UUID для {}: {}", username, result.getUuid());
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                            MessagesConfig.authFailed("Неверный формат UUID")
                    ));
                }
            } else {
                logger.warn("Авторизация провалилась для {}: {}", username, result.getReason());
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        MessagesConfig.authFailed(result.getReason())
                ));
            }

        } catch (java.util.concurrent.TimeoutException e) {
            logger.error("ТАЙМАУТ авторизации для {} (15 сек)", username);
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(MessagesConfig.AUTH_TIMEOUT));

        } catch (Exception e) {
            logger.error("Ошибка авторизации для {}: {}", username, e.getMessage(), e);
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(MessagesConfig.AUTH_TIMEOUT));

        } finally {
            activeHandlers.remove(lowerUsername);
        }
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        String username = event.getUsername();
        String lowerUsername = username.toLowerCase();

        logger.info("GameProfileRequest вызван для {}", username);

        if (authResults.containsKey(lowerUsername + "_applied")) {
            logger.debug("UUID уже применён для {}, пропускаем", username);
            return;
        }

        PreLoginAuthHandler.AuthResult result = authResults.get(lowerUsername);

        if (result == null) {
            logger.error("Результат авторизации не найден для {} (возможно уже удалён)", username);
            return;
        }

        if (result.isSuccess()) {
            try {
                UUID playerUUID = UUID.fromString(result.getUuid());

                GameProfile newProfile = event.getOriginalProfile().withId(playerUUID);
                event.setGameProfile(newProfile);

                addOnlinePlayer(username, result.getAuthKey(), result.getUuid());
                authResults.put(lowerUsername + "_applied", result);

                logger.info("UUID применён для {}: {}", username, playerUUID);

            } catch (Exception e) {
                logger.error("Ошибка применения UUID для {}: {}", username, e.getMessage());
            }
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        String username = event.getPlayer().getUsername();
        String lowerUsername = username.toLowerCase();

        removeOnlinePlayer(username);
        // authResults.remove(lowerUsername);
        authResults.remove(lowerUsername + "_applied");
        activeHandlers.remove(lowerUsername);
    }

    public void completeAuthForPlayer(String nickname, boolean success, String reason, String authKey, String uuid) {
        PreLoginAuthHandler handler = activeHandlers.get(nickname.toLowerCase());
        if (handler != null) {
            handler.completeAuth(success, reason, authKey, uuid);
            logger.debug("completeAuth вызван для {}", nickname);
        } else {
            logger.warn("Handler не найден для {}", nickname);
        }
    }

    public void addOnlinePlayer(String nickname, String authKey, String uuid) {
        String lowerNickname = nickname.toLowerCase();

        if (onlinePlayers.containsKey(lowerNickname)) {
            logger.debug("Игрок {} уже в онлайн, пропускаем", nickname);
            return;
        }

        onlinePlayers.put(lowerNickname, new OnlinePlayer(nickname, authKey, uuid));
        logger.info("Игрок {} добавлен в онлайн (UUID: {})", nickname, uuid);
    }

    public void removeOnlinePlayer(String nickname) {
        String lowerNickname = nickname.toLowerCase();
        OnlinePlayer removed = onlinePlayers.remove(lowerNickname);
        if (removed != null) {
            logger.info("Игрок {} удалён из онлайн", nickname);
        }
    }

    public Optional<OnlinePlayer> getOnlinePlayerByNickname(String nickname) {
        return Optional.ofNullable(onlinePlayers.get(nickname.toLowerCase()));
    }

    public Optional<OnlinePlayer> getOnlinePlayerByAuthKey(String authKey) {
        return onlinePlayers.values().stream()
                .filter(player -> player.getAuthKey().equals(authKey))
                .findFirst();
    }

    public Map<String, OnlinePlayer> getOnlinePlayers() {
        return new ConcurrentHashMap<>(onlinePlayers);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Выключение...");

        if (wsClient != null) {
            wsClient.close();
        }

        activeHandlers.clear();
        authResults.clear();
        onlinePlayers.clear();
        logger.info("EukoVelocity выключен");
    }

    public Config getConfig() {
        return Config.IMP;
    }

    private void connectToRabbitMQ(String wsUrl, String username, String password, String queue) {
        server.getScheduler().buildTask(this, () -> {
            try {
                wsClient = new RabbitMQWebSocketClient(
                        new URI(wsUrl),
                        username,
                        password,
                        queue,
                        this,
                        server,
                        rabbitHandler
                );
                wsClient.connect();
                logger.info("Connecting to RabbitMQ...");
            } catch (Exception e) {
                logger.error("Failed to connect to RabbitMQ", e);
            }
        }).schedule();
    }
}
