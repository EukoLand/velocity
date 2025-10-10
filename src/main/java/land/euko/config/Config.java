package land.euko.config;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public class Config {

    public static final Config IMP = new Config();

    public RABBIT RABBIT = new RABBIT();
    public AUTH AUTH = new AUTH();

    public void reload(File configFile) throws IOException {
        if (!configFile.exists()) {
            // Создаем конфиг с дефолтными значениями
            saveDefault(configFile);
        }

        // Загружаем конфиг
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(configFile.toPath())) {
            Map<String, Object> data = yaml.load(in);

            if (data != null) {
                loadFromMap(data);
            }
        }
    }

    private void saveDefault(File configFile) throws IOException {
        configFile.getParentFile().mkdirs();

        Map<String, Object> config = new LinkedHashMap<>();

        // RabbitMQ секция
        Map<String, Object> rabbitMap = new LinkedHashMap<>();
        rabbitMap.put("ws-url", RABBIT.WS_URL);
        rabbitMap.put("username", RABBIT.USERNAME);
        rabbitMap.put("password", RABBIT.PASSWORD);
        rabbitMap.put("queue", RABBIT.QUEUE);
        rabbitMap.put("enabled", RABBIT.ENABLED);
        config.put("rabbit", rabbitMap);

        // Auth секция
        Map<String, Object> authMap = new LinkedHashMap<>();
        authMap.put("timeout", AUTH.TIMEOUT);
        authMap.put("welcome-message", AUTH.WELCOME_MESSAGE);
        config.put("auth", authMap);

        Yaml yaml = new Yaml();
        try (Writer writer = new FileWriter(configFile)) {
            writer.write("# Конфигурация EukoVelocity\n\n");
            writer.write("# Настройки RabbitMQ\n");
            yaml.dump(config, writer);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFromMap(Map<String, Object> data) {
        // Загрузка RabbitMQ настроек
        if (data.containsKey("rabbit")) {
            Map<String, Object> rabbitMap = (Map<String, Object>) data.get("rabbit");

            if (rabbitMap.containsKey("ws-url")) {
                RABBIT.WS_URL = (String) rabbitMap.get("ws-url");
            }
            if (rabbitMap.containsKey("username")) {
                RABBIT.USERNAME = (String) rabbitMap.get("username");
            }
            if (rabbitMap.containsKey("password")) {
                RABBIT.PASSWORD = (String) rabbitMap.get("password");
            }
            if (rabbitMap.containsKey("queue")) {
                RABBIT.QUEUE = (String) rabbitMap.get("queue");
            }
            if (rabbitMap.containsKey("enabled")) {
                RABBIT.ENABLED = (Boolean) rabbitMap.get("enabled");
            }
        }

        // Загрузка Auth настроек
        if (data.containsKey("auth")) {
            Map<String, Object> authMap = (Map<String, Object>) data.get("auth");

            if (authMap.containsKey("timeout")) {
                AUTH.TIMEOUT = (Integer) authMap.get("timeout");
            }
            if (authMap.containsKey("welcome-message")) {
                AUTH.WELCOME_MESSAGE = (String) authMap.get("welcome-message");
            }
        }
    }

    public static class RABBIT {
        public String WS_URL = "ws://localhost:15674/ws";
        public String USERNAME = "user";
        public String PASSWORD = "password";
        public String QUEUE = "velocity";
        public boolean ENABLED = true;
    }

    public static class AUTH {
        public int TIMEOUT = 60;
        public String WELCOME_MESSAGE = "§aДобро пожаловать на сервер!";
    }
}