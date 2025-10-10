package land.euko.config;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RabbitConfig {
    private final String wsUrl;
    private final String username;
    private final String password;
    private final String queue;

    public static RabbitConfig fromMainConfig(Config.RABBIT rabbitConfig) {
        return new RabbitConfig(
                rabbitConfig.WS_URL,
                rabbitConfig.USERNAME,
                rabbitConfig.PASSWORD,
                rabbitConfig.QUEUE
        );
    }
}