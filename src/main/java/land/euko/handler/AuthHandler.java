package land.euko.handler;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import land.euko.Main;
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

    @MonotonicNonNull
    private LimboPlayer limboPlayer;

    @MonotonicNonNull
    private ScheduledTask timeoutTask;

    private boolean isAuthenticated = false;

    public AuthHandler(ProxyServer server, Player proxyPlayer, Main plugin) {
        this.server = server;
        this.proxyPlayer = proxyPlayer;
        this.plugin = plugin;
    }

    @Override
    public void onSpawn(Limbo limbo, LimboPlayer player) {
        this.limboPlayer = player;
        player.disableFalling();

        Title title = Title.title(
                Component.text("Добро пожаловать!", NamedTextColor.GOLD),
                Component.text("Проверка авторизации...", NamedTextColor.YELLOW),
                Title.Times.times(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(30),
                        Duration.ofMillis(500)
                )
        );
        this.proxyPlayer.showTitle(title);

        plugin.getLogger().info("Игрок {} в лимбо, ожидаем данные от API...",
                proxyPlayer.getUsername());

        // Таймаут на случай если мод не установлен или API не ответит
        this.timeoutTask = server.getScheduler()
                .buildTask(plugin, () -> {
                    if (!isAuthenticated) {
                        this.proxyPlayer.disconnect(
                                Component.text("§c✗ Авторизация не удалась\n\n" +
                                                "§7Время ожидания истекло!\n" +
                                                "§7Возможно у вас не установлен мод EukoAuth\n" +
                                                "§7Скачайте его на §eeuko.land",
                                        NamedTextColor.RED)
                        );
                        plugin.removeAuthHandler(proxyPlayer.getUsername());
                        plugin.getLogger().warn("Игрок {} не прошел авторизацию (таймаут)",
                                proxyPlayer.getUsername());
                    }
                })
                .delay(10, TimeUnit.SECONDS)
                .schedule();
    }

    /**
     * Вызывается из RabbitHandler когда приходит решение от API
     */
    public void handleAuthResult(boolean success, String reason, String authKey) {
        if (isAuthenticated) {
            return; // Уже обработано
        }

        isAuthenticated = true;

        if (timeoutTask != null) {
            timeoutTask.cancel();
        }

        if (success) {
            this.proxyPlayer.sendMessage(
                    Component.text("✓ Авторизация успешна!", NamedTextColor.GREEN)
            );

            plugin.getLogger().info("Игрок {} успешно авторизован с ключом {}",
                    proxyPlayer.getUsername(), authKey);

            plugin.addOnlinePlayer(proxyPlayer.getUsername(), authKey);
            limboPlayer.disconnect();

        } else {
            String kickMessage = "§c✗ Авторизация не удалась\n\n";
            if (reason != null && !reason.isEmpty()) {
                kickMessage += "§7Причина: §f" + reason;
            } else {
                kickMessage += "§7Неизвестная ошибка";
            }

            this.proxyPlayer.disconnect(
                    Component.text(kickMessage, NamedTextColor.RED)
            );

            plugin.getLogger().warn("Игрок {} не прошел авторизацию: {}",
                    proxyPlayer.getUsername(), reason != null ? reason : "неизвестная ошибка");
        }

        plugin.removeAuthHandler(proxyPlayer.getUsername());
    }

    @Override
    public void onChat(String chat) {

    }

    @Override
    public void onDisconnect() {
        if (this.timeoutTask != null) {
            this.timeoutTask.cancel();
        }

        plugin.removeAuthHandler(proxyPlayer.getUsername());
        plugin.removeOnlinePlayer(proxyPlayer.getUsername());
        this.limboPlayer = null;
    }
}