package ca.bazlur.threadcity.ai;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Produces short-lived pseudonymous rate-limit keys without retaining IP addresses.
 */
final class AiClientIdentityResolver {

    AiClientIdentity resolve() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return new AiClientIdentity("internal", "internal");
        }

        HttpServletRequest request = attributes.getRequest();
        HttpSession session = request.getSession(false);
        String sessionId = session == null ? "no-session" : session.getId();
        return new AiClientIdentity(hash(sessionId), hash(request.getRemoteAddr()));
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record AiClientIdentity(String sessionKey, String networkKey) {
    }
}
