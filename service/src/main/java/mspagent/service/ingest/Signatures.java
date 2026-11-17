package mspagent.service.ingest;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Webhook signatures: {@code X-Msp-Signature: sha256=<hex of HMAC-SHA256(secret, raw body)>}. */
public final class Signatures {
    private Signatures() {}

    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean valid(String secret, byte[] body, String header) {
        if (header == null || secret == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, body).getBytes(StandardCharsets.UTF_8),
                header.trim().getBytes(StandardCharsets.UTF_8));
    }
}
