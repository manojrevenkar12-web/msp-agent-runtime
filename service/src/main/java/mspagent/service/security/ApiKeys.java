package mspagent.service.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import mspagent.service.Settings;
import org.springframework.stereotype.Component;

/** Looks up an API key in the tenants' settings, comparing in constant time. */
@Component
public class ApiKeys {
    private final Settings settings;

    public ApiKeys(Settings settings) {
        this.settings = settings;
    }

    public Optional<Caller> find(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        byte[] given = key.getBytes(StandardCharsets.UTF_8);
        for (Settings.Tenant t : settings.tenants()) {
            for (Settings.Key k : t.keys()) {
                if (MessageDigest.isEqual(given, k.key().getBytes(StandardCharsets.UTF_8))) {
                    return Optional.of(new Caller(t.id(), k.user(), k.role()));
                }
            }
        }
        return Optional.empty();
    }
}
