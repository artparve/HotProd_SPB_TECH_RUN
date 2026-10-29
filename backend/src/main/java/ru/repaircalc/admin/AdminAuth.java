package ru.repaircalc.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public final class AdminAuth {
    static final String ACTOR = "repaircalc.admin.actor";
    static final String CSRF = "repaircalc.admin.csrf";
    private static final String ISSUED = "repaircalc.admin.issued";
    private final SecureRandom random = new SecureRandom();
    private final String username;
    private final String hash;
    private final int ttl;
    private final Map<String, Attempts> attempts = new HashMap<>();

    public AdminAuth(@Value("${repaircalc.admin.username:}") String username,
                     @Value("${repaircalc.admin.password-hash:}") String hash,
                     @Value("${repaircalc.admin.session-seconds:1800}") int ttl) {
        this.username = username;
        this.hash = hash;
        this.ttl = Math.max(60, Math.min(86400, ttl));
        if (!hash.isEmpty() && (!validHash(hash) || username.isBlank())) throw new IllegalArgumentException("Invalid administrative authentication configuration");
    }

    public boolean enabled() { return !hash.isEmpty() && !username.isBlank(); }

    public Session session(HttpServletRequest request) {
        var actor = actor(request);
        var session = request.getSession(true);
        session.setMaxInactiveInterval(ttl);
        if (session.getAttribute(CSRF) == null) session.setAttribute(CSRF, token());
        return new Session(actor != null, actor, (String) session.getAttribute(CSRF));
    }

    public String actor(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        if (session.getAttribute(ISSUED) instanceof Long issued && Instant.now().getEpochSecond() - issued >= ttl) {
            session.invalidate();
            return null;
        }
        return (String) session.getAttribute(ACTOR);
    }

    public String requireActor(HttpServletRequest request) {
        var actor = actor(request);
        if (actor == null) throw new AdminException(401, "authentication_required", "Войдите в административную панель.");
        return actor;
    }

    public void csrf(HttpServletRequest request) {
        var session = request.getSession(false);
        var expected = session == null ? null : (String) session.getAttribute(CSRF);
        var actual = request.getHeader("X-CSRF-Token");
        if (expected == null || actual == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw new AdminException(403, "csrf_failed", "Проверка CSRF не пройдена. Обновите сессию.");
        }
    }

    public Session login(HttpServletRequest request, JsonNode body) {
        if (body.size() != 2 || !body.path("username").isString() || !body.path("password").isString()
                || body.path("username").asText().length() > 500 || body.path("password").asText().length() > 1024) {
            throw new AdminException(400, "invalid_login", "Укажите имя и пароль строками.");
        }
        var address = request.getRemoteAddr();
        reserveAttempt(address);
        var password = body.path("password").asText().toCharArray();
        boolean passwordMatches;
        try { passwordMatches = verify(password); } finally { java.util.Arrays.fill(password, '\0'); }
        if (!passwordMatches || !MessageDigest.isEqual(username.getBytes(StandardCharsets.UTF_8), body.path("username").asText().getBytes(StandardCharsets.UTF_8))) {
            throw new AdminException(401, "invalid_credentials", "Неверное имя или пароль.");
        }
        synchronized (attempts) { attempts.remove(address); }
        var previous = request.getSession(false);
        if (previous != null) previous.invalidate();
        var session = request.getSession(true);
        session.setAttribute(ACTOR, username);
        session.setAttribute(ISSUED, Instant.now().getEpochSecond());
        session.setAttribute(CSRF, token());
        return session(request);
    }

    public void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }

    private void reserveAttempt(String address) {
        synchronized (attempts) {
            long now = Instant.now().getEpochSecond();
            attempts.entrySet().removeIf(entry -> now - entry.getValue().since >= 60);
            // Bounded state and total work protect the single-user endpoint too.
            int total = attempts.values().stream().mapToInt(value -> value.count).sum();
            if (total >= 30) throw new AdminException(429, "login_rate_limited", "Слишком много попыток входа. Повторите через минуту.");
            var state = attempts.computeIfAbsent(address, key -> new Attempts(now));
            if (state.count >= 5) throw new AdminException(429, "login_rate_limited", "Слишком много попыток входа. Повторите через минуту.");
            state.count++;
        }
    }
    private boolean verify(char[] password) {
        var parts = hash.split("\\$", -1);
        var spec = new PBEKeySpec(password, Base64.getDecoder().decode(parts[2]), Integer.parseInt(parts[1]), 256);
        try {
            var derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]), derived);
        } catch (java.security.GeneralSecurityException exception) { throw new IllegalStateException("Password verification unavailable"); }
        finally { spec.clearPassword(); }
    }
    private static boolean validHash(String hash) {
        try {
            var parts = hash.split("\\$", -1);
            return parts.length == 4 && parts[0].equals("pbkdf2-sha256") && Integer.parseInt(parts[1]) >= 600000
                    && Integer.parseInt(parts[1]) <= 2000000 && Base64.getDecoder().decode(parts[2]).length >= 16
                    && Base64.getDecoder().decode(parts[3]).length == 32;
        } catch (IllegalArgumentException exception) { return false; }
    }
    private String token() { var bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static final class Attempts { final long since; int count; Attempts(long since) { this.since = since; } }
    public record Session(boolean authenticated, String username, String csrfToken) { }
}
