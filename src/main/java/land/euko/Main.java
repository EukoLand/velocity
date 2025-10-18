package land.euko;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import land.euko.backend.RabbitMQWebSocketClient;
import land.euko.config.Config;
import land.euko.handler.AuthHandler;
import land.euko.handler.RabbitHandler;
import lombok.Getter;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.elytrium.limboapi.api.player.GameMode;
import org.slf4j.Logger;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
        id = "eukovelocity",
        name = "EukoVelocity",
        version = "1.0.0",
        url = "https://euko.land",
        authors = {"Tokishu"},
        dependencies = {
                @Dependency(id = "limboapi")
        }
)
public class Main {

    @Getter
    private final Logger logger;

    @Getter
    private final ProxyServer server;

    private final LimboFactory factory;
    private final Path dataDirectory;

    @Getter
    private Limbo authLimbo;

    private RabbitMQWebSocketClient wsClient;
    private RabbitHandler rabbitHandler;
    private final Gson gson = new Gson();

    private final Map<String, AuthHandler> authHandlers = new ConcurrentHashMap<>();

    @Inject
    public Main(Logger logger, ProxyServer server, @DataDirectory Path dataDirectory) {
        this.logger = logger;
        this.server = server;
        this.dataDirectory = dataDirectory;
        this.factory = (LimboFactory) server.getPluginManager()
                .getPlugin("limboapi")
                .flatMap(PluginContainer::getInstance)
                .orElseThrow(() -> new RuntimeException("LimboAPI не найден!"));
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

        try {
            VirtualWorld authWorld = this.factory.createVirtualWorld(
                    Dimension.THE_END,
                    0, 100, 0,
                    90F, 0F
            );

            this.authLimbo = this.factory.createLimbo(authWorld)
                    .setName("AuthLimbo")
                    .setWorldTime(1000L)
                    .setGameMode(GameMode.ADVENTURE);

            logger.info("LimboAPI инициализирован");
        } catch (Exception e) {
            logger.error("Ошибка LimboAPI: {}", e.getMessage(), e);
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

    @Subscribe(order = PostOrder.LATE)
    public void onLogin(LoginLimboRegisterEvent event) {
        event.addCallback(() -> {
            try {
                AuthHandler handler = new AuthHandler(this.server, event.getPlayer(), this);
                authHandlers.put(event.getPlayer().getUsername().toLowerCase(), handler);

                this.authLimbo.spawnPlayer(
                        event.getPlayer(),
                        handler
                );
            } catch (Exception e) {
                logger.error("Ошибка спавна в лимбо: {}", e.getMessage(), e);
            }
        });
    }

    public AuthHandler getAuthHandler(String nickname) {
        return authHandlers.get(nickname.toLowerCase());
    }

    public void removeAuthHandler(String nickname) {
        authHandlers.remove(nickname.toLowerCase());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Выключение...");

        if (wsClient != null) {
            wsClient.close();
        }

        authHandlers.clear();
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