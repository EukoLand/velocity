package land.euko.handler;

import land.euko.Main;
import lombok.Getter;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class PreLoginAuthHandler {

    @Getter
    private final CompletableFuture<AuthResult> authFuture;
    private volatile boolean completed = false;

    public PreLoginAuthHandler(Main plugin, String username) {
        this.authFuture = new CompletableFuture<>();

        // Таймаут на уровне handler
        plugin.getServer().getScheduler()
                .buildTask(plugin, () -> {
                    if (!completed) {
                        completeAuth(false, "Время ожидания истекло", null, null);
                    }
                })
                .delay(20, TimeUnit.SECONDS)
                .schedule();
    }

    public void completeAuth(boolean success, String reason, String authKey, String uuid) {
        if (completed) return;
        completed = true;

        authFuture.complete(new AuthResult(success, reason, authKey, uuid));
    }

    @Getter
    public static class AuthResult {
        private final boolean success;
        private final String reason;
        private final String authKey;
        private final String uuid;

        public AuthResult(boolean success, String reason, String authKey, String uuid) {
            this.success = success;
            this.reason = reason;
            this.authKey = authKey;
            this.uuid = uuid;
        }
    }
}