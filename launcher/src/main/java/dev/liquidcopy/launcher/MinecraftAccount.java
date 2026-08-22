package dev.liquidcopy.launcher;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A launch-ready Minecraft account plus the refresh credentials owned by this launcher. */
public record MinecraftAccount(
    String username,
    String uuid,
    String xuid,
    String clientId,
    String minecraftAccessToken,
    Instant minecraftAccessTokenExpiresAt,
    String microsoftAccessToken,
    Instant microsoftAccessTokenExpiresAt,
    String microsoftRefreshToken,
    String microsoftScope,
    Instant authenticatedAt,
    List<String> entitlements,
    List<Skin> skins,
    List<Cape> capes
) {
    public MinecraftAccount {
        username = text(username, "username", 1, 64);
        uuid = text(uuid, "uuid", 32, 36);
        String compactUuid = uuid.replace("-", "");
        if (!compactUuid.matches("(?i)[0-9a-f]{32}")) {
            throw new IllegalArgumentException("uuid is invalid");
        }
        uuid = compactUuid.toLowerCase(java.util.Locale.ROOT);
        xuid = optionalText(xuid, "xuid", 64);
        clientId = text(clientId, "clientId", 1, 512);
        minecraftAccessToken = secret(minecraftAccessToken, "minecraftAccessToken");
        Objects.requireNonNull(minecraftAccessTokenExpiresAt, "minecraftAccessTokenExpiresAt");
        microsoftAccessToken = secret(microsoftAccessToken, "microsoftAccessToken");
        Objects.requireNonNull(microsoftAccessTokenExpiresAt, "microsoftAccessTokenExpiresAt");
        microsoftRefreshToken = secret(microsoftRefreshToken, "microsoftRefreshToken");
        microsoftScope = text(microsoftScope, "microsoftScope", 1, 2048);
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        entitlements = List.copyOf(entitlements);
        skins = List.copyOf(skins);
        capes = List.copyOf(capes);
        entitlements.forEach(value -> text(value, "entitlement", 1, 256));
        skins.forEach(Objects::requireNonNull);
        capes.forEach(Objects::requireNonNull);
    }

    /** Returns true when the launch token remains usable beyond the requested safety margin. */
    public boolean hasUsableMinecraftToken(Clock clock, Duration safetyMargin) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(safetyMargin, "safetyMargin");
        if (safetyMargin.isNegative()) {
            throw new IllegalArgumentException("safetyMargin must not be negative");
        }
        return minecraftAccessTokenExpiresAt.isAfter(clock.instant().plus(safetyMargin));
    }

    /** Alias matching the direct launcher's account terminology. */
    public String playerName() {
        return username;
    }

    @Override
    public String toString() {
        return "MinecraftAccount[username=" + username + ", uuid=" + uuid + ", xuid=" + xuid
            + ", clientId=" + clientId + ", minecraftAccessToken=<redacted>, microsoftAccessToken=<redacted>,"
            + " microsoftRefreshToken=<redacted>, minecraftAccessTokenExpiresAt="
            + minecraftAccessTokenExpiresAt + "]";
    }

    public record Skin(String id, String state, URI url, String variant, String alias) {
        public Skin {
            id = text(id, "skin.id", 1, 256);
            state = optionalText(state, "skin.state", 64);
            Objects.requireNonNull(url, "skin.url");
            variant = optionalText(variant, "skin.variant", 64);
            alias = optionalText(alias, "skin.alias", 256);
        }
    }

    public record Cape(String id, String state, URI url, String alias) {
        public Cape {
            id = text(id, "cape.id", 1, 256);
            state = optionalText(state, "cape.state", 64);
            Objects.requireNonNull(url, "cape.url");
            alias = optionalText(alias, "cape.alias", 256);
        }
    }

    private static String secret(String value, String name) {
        return text(value, name, 1, 32_768);
    }

    private static String optionalText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        if (value.length() > maximum || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String text(String value, String name, int minimum, int maximum) {
        Objects.requireNonNull(value, name);
        if (value.length() < minimum || value.length() > maximum || value.indexOf('\r') >= 0
            || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
