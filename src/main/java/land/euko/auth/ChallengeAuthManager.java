package land.euko.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Менеджер для challenge-response авторизации с RSA подписью
 */
public class ChallengeAuthManager {

    private final Logger logger;
    private final Gson gson = new Gson();
    private final SecureRandom random = new SecureRandom();
    private final Path privateKeyPath;

    private PrivateKey privateKey;
    private String keyId = "v1-2025-01"; // ID ключа для идентификации

    private final Map<UUID, ChallengeData> activeChallenges = new ConcurrentHashMap<>();
    private static final long CHALLENGE_TIMEOUT_SECONDS = 30;

    public ChallengeAuthManager(Logger logger, Path dataDirectory) {
        this.logger = logger;
        this.privateKeyPath = dataDirectory.resolve("private-key");

        loadPrivateKey();
    }

    /**
     * Загружает приватный ключ из файла
     */
    private void loadPrivateKey() {
        try {
            if (!Files.exists(privateKeyPath)) {
                logger.error("╔═════════════════════════════════════════════════════════════╗");
                logger.error("║  ОШИБКА: Файл private-key не найден!                       ║");
                logger.error("║  Путь: {}                                                   ║", privateKeyPath);
                logger.error("║                                                             ║");
                logger.error("║  Создайте ключи командами:                                  ║");
                logger.error("║  openssl genrsa -out private_key.pem 2048                   ║");
                logger.error("║  openssl pkcs8 -topk8 -inform PEM -outform DER \\           ║");
                logger.error("║          -in private_key.pem -out private-key -nocrypt      ║");
                logger.error("║                                                             ║");
                logger.error("║  И поместите файл 'private-key' в папку плагина             ║");
                logger.error("╚═════════════════════════════════════════════════════════════╝");
                return;
            }

            byte[] keyBytes = Files.readAllBytes(privateKeyPath);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            privateKey = kf.generatePrivate(spec);

            logger.info("✓ Приватный ключ загружен успешно (KeyID: {})", keyId);

        } catch (Exception e) {
            logger.error("Ошибка загрузки приватного ключа: {}", e.getMessage(), e);
        }
    }

    /**
     * Создает новый challenge с подписью
     */
    public String createChallenge(UUID playerId) {
        if (privateKey == null) {
            logger.error("Приватный ключ не загружен! Challenge не может быть создан.");
            return null;
        }

        try {
            // Генерируем AES ключ
            byte[] keyBytes = new byte[16];
            random.nextBytes(keyBytes);
            String challengeKey = Base64.getEncoder().encodeToString(keyBytes);

            // Подписываем challenge
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(challengeKey.getBytes(StandardCharsets.UTF_8));
            byte[] signature = sig.sign();
            String signatureBase64 = Base64.getEncoder().encodeToString(signature);

            // Формат: keyId:challenge:signature
            String signedChallenge = keyId + ":" + challengeKey + ":" + signatureBase64;

            long expiresAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(CHALLENGE_TIMEOUT_SECONDS);
            ChallengeData data = new ChallengeData(challengeKey, keyBytes, expiresAt);
            activeChallenges.put(playerId, data);

            logger.debug("Challenge создан для {}: {}", playerId, challengeKey);
            return signedChallenge;

        } catch (Exception e) {
            logger.error("Ошибка создания challenge: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Проверяет и расшифровывает ответ клиента
     */
    public JsonObject verifyResponse(UUID playerId, String encryptedResponse) {
        ChallengeData challenge = activeChallenges.get(playerId);

        if (challenge == null) {
            logger.warn("Challenge не найден для {}", playerId);
            return null;
        }

        if (System.currentTimeMillis() > challenge.expiresAt) {
            logger.warn("Challenge истек для {}", playerId);
            activeChallenges.remove(playerId);
            return null;
        }

        try {
            String decrypted = decrypt(encryptedResponse, challenge.keyBytes);
            JsonObject authData = gson.fromJson(decrypted, JsonObject.class);

            logger.info("✓ Данные расшифрованы для {}", playerId);
            activeChallenges.remove(playerId);

            return authData;

        } catch (Exception e) {
            logger.error("Ошибка расшифровки для {}: {}", playerId, e.getMessage());
            activeChallenges.remove(playerId);
            return null;
        }
    }

    public void cancelChallenge(UUID playerId) {
        activeChallenges.remove(playerId);
    }

    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        activeChallenges.entrySet().removeIf(entry -> {
            if (now > entry.getValue().expiresAt) {
                logger.debug("Challenge истек для {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    private String decrypt(String encryptedBase64, byte[] keyBytes) throws Exception {
        byte[] encryptedBytes = Base64.getDecoder().decode(encryptedBase64);

        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec);

        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    private static class ChallengeData {
        final String challengeKey;
        final byte[] keyBytes;
        final long expiresAt;

        ChallengeData(String challengeKey, byte[] keyBytes, long expiresAt) {
            this.challengeKey = challengeKey;
            this.keyBytes = keyBytes;
            this.expiresAt = expiresAt;
        }
    }
}