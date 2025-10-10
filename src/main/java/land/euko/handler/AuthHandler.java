package land.euko.handler;

import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import land.euko.Main;
import land.euko.auth.ChallengeAuthManager;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class AuthHandler implements LimboSessionHandler {

    private final ProxyServer server;
    private final Player proxyPlayer;
    private final Main plugin;
    private final ChallengeAuthManager challengeManager;

    @MonotonicNonNull
    private LimboPlayer limboPlayer;

    @MonotonicNonNull
    private ScheduledTask timeoutTask;

    private boolean isAuthenticated = false;

    public AuthHandler(ProxyServer server, Player proxyPlayer, Main plugin) {
        this.server = server;
        this.proxyPlayer = proxyPlayer;
        this.plugin = plugin;
        this.challengeManager = plugin.getChallengeManager();
    }

    @Override
    public void onSpawn(Limbo limbo, LimboPlayer player) {
        this.limboPlayer = player;
        player.disableFalling();

        // Показываем приветствие
        Title title = Title.title(
                Component.text("Добро пожаловать!", NamedTextColor.GOLD),
                Component.text("Авторизация...", NamedTextColor.YELLOW),
                Title.Times.times(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(30),
                        Duration.ofMillis(500)
                )
        );
        this.proxyPlayer.showTitle(title);

        // Генерируем challenge и отправляем клиенту
        String challenge = challengeManager.createChallenge(proxyPlayer.getUniqueId());

        // Отправляем challenge в специальном формате (клиент должен его перехватить)
        this.proxyPlayer.sendMessage(
                Component.text("§8[AUTH_CHALLENGE]§7" + challenge)
        );

        plugin.getLogger().info("Отправлен challenge игроку {}: {}",
                proxyPlayer.getUsername(), challenge);

        this.timeoutTask = server.getScheduler()
                .buildTask(plugin, () -> {
                    if (!isAuthenticated) {
                        this.proxyPlayer.disconnect(
                                Component.text("Время авторизации истекло!", NamedTextColor.RED)
                        );
                        challengeManager.cancelChallenge(proxyPlayer.getUniqueId());
                        plugin.getLogger().warn("Игрок {} не прошел авторизацию вовремя",
                                proxyPlayer.getUsername());
                    }
                })
                .delay(10, TimeUnit.SECONDS)
                .schedule();
    }

    @Override
    public void onChat(String chat) {
        // Проверяем что это команда /auth
        if (!chat.startsWith("/auth ")) {
            return;
        }

        // Извлекаем зашифрованные данные
        String encryptedData = chat.substring(6).trim();

        if (encryptedData.isEmpty()) {
            this.proxyPlayer.sendMessage(
                    Component.text("❌ Неверный формат команды", NamedTextColor.RED)
            );
            return;
        }

        plugin.getLogger().info("Получен auth response от {}", proxyPlayer.getUsername());

        JsonObject authData = challengeManager.verifyResponse(
                proxyPlayer.getUniqueId(),
                encryptedData
        );

        if (authData == null) {
            this.proxyPlayer.disconnect(
                    Component.text("❌ Ошибка авторизации!", NamedTextColor.RED)
            );
            plugin.getLogger().warn("Неверный auth response от {}", proxyPlayer.getUsername());
            return;
        }

        // Извлекаем данные
        String authToken = authData.get("authToken").getAsString();
        String version = authData.get("version").getAsString();
        String username = authData.get("username").getAsString();

        plugin.getLogger().info("Данные авторизации от {}: token={}, version={}",
                username, authToken, version);

        // Валидируем данные (здесь можно добавить проверку через API/БД)
        if (validateAuthData(authToken, version, username)) {
            isAuthenticated = true;

            if (timeoutTask != null) {
                timeoutTask.cancel();
            }

            this.proxyPlayer.sendMessage(
                    Component.text("Авторизация успешна!", NamedTextColor.GREEN)
            );

            plugin.getLogger().info("Игрок {} успешно авторизован", proxyPlayer.getUsername());

            limboPlayer.disconnect();

        } else {
            this.proxyPlayer.disconnect(
                    Component.text("Неверные данные авторизации!", NamedTextColor.RED)
            );
            plugin.getLogger().warn("Игрок {} не прошел валидацию", proxyPlayer.getUsername());
        }
    }

    private boolean validateAuthData(String token, String version, String username) {
        if (!username.equals(proxyPlayer.getUsername())) {
            plugin.getLogger().warn("Username не совпадает: ожидается {}, получен {}",
                    proxyPlayer.getUsername(), username);
            return false;
        }

        // Здесь можно добавить проверку токена через:
        // - REST API
        // - RabbitMQ
        // - База данных
        // - Любой другой способ

        plugin.getLogger().info("Валидация токена для {}: {}", username, token);

        // Пока что просто проверяем что токен не пустой
        return token != null && !token.isEmpty();
    }

    @Override
    public void onDisconnect() {
        if (this.timeoutTask != null) {
            this.timeoutTask.cancel();
        }

        challengeManager.cancelChallenge(proxyPlayer.getUniqueId());

        plugin.removeAuthHandler(proxyPlayer.getUniqueId());

        this.limboPlayer = null;
    }
}