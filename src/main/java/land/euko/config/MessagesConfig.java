package land.euko.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class MessagesConfig {

    private MessagesConfig() {}

    // Успешная авторизация
    public static final Component AUTH_SUCCESS =
            Component.text("✓ Авторизация успешна!", NamedTextColor.GREEN);

    // Таймаут авторизации
    public static final Component AUTH_TIMEOUT =
            Component.text("§c✗ Авторизация не удалась\n\n" +
                            "§7Время ожидания истекло!\n" +
                            "§7Возможно у вас не установлен мод EukoAuth\n" +
                            "§7Скачайте его на §eeuko.land",
                    NamedTextColor.RED);

    // UUID не получен
    public static final Component AUTH_NO_UUID =
            Component.text("§c✗ Авторизация не удалась\n\n" +
                            "§7Причина: §fНе могу получить ваш UUID, обратитесь к администрации",
                    NamedTextColor.RED);

    // Заголовок при входе в лимбо
    public static final Component WELCOME_TITLE =
            Component.text("Добро пожаловать!", NamedTextColor.GOLD);

    public static final Component WELCOME_SUBTITLE =
            Component.text("Проверка авторизации...", NamedTextColor.YELLOW);

    // Кик игрока
    public static Component kickMessage(String reason) {
        return Component.text(
                "§c✗ Вы были отключены от сервера\n\n§7Причина: §f" + reason,
                NamedTextColor.RED
        );
    }

    // Ошибка авторизации
    public static Component authFailed(String reason) {
        String message = "§c✗ Авторизация не удалась\n\n";
        if (reason != null && !reason.isEmpty()) {
            message += "§7Причина: §f" + reason;
        } else {
            message += "§7Неизвестная ошибка";
        }
        return Component.text(message, NamedTextColor.RED);
    }

    // Дефолтная причина кика
    public static final String DEFAULT_KICK_REASON = "Вы были кикнуты";
}