package dev.liquidcopy.launcher;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Endpoints and public-client identity used by the standalone launcher login. */
public record MicrosoftAuthConfig(
    String clientId,
    String scopes,
    URI authorizationEndpoint,
    URI tokenEndpoint,
    URI xboxUserAuthenticationEndpoint,
    URI xstsAuthorizationEndpoint,
    URI minecraftAuthenticationEndpoint,
    URI minecraftEntitlementsEndpoint,
    URI minecraftProfileEndpoint,
    Duration callbackTimeout,
    Duration requestTimeout
) {
    /** Public desktop OAuth application owned by the LiquidCopy distributor. */
    public static final String CLIENT_ID = "cd7fcf88-1560-4508-9827-eaeecf644c7c";
    public static final String DEFAULT_SCOPES = "XboxLive.signin offline_access";

    public MicrosoftAuthConfig {
        clientId = requireText(clientId, "clientId");
        scopes = requireText(scopes, "scopes");
        authorizationEndpoint = requireHttps(authorizationEndpoint, "authorizationEndpoint");
        tokenEndpoint = requireHttps(tokenEndpoint, "tokenEndpoint");
        xboxUserAuthenticationEndpoint = requireHttps(xboxUserAuthenticationEndpoint,
            "xboxUserAuthenticationEndpoint");
        xstsAuthorizationEndpoint = requireHttps(xstsAuthorizationEndpoint, "xstsAuthorizationEndpoint");
        minecraftAuthenticationEndpoint = requireHttps(minecraftAuthenticationEndpoint,
            "minecraftAuthenticationEndpoint");
        minecraftEntitlementsEndpoint = requireHttps(minecraftEntitlementsEndpoint,
            "minecraftEntitlementsEndpoint");
        minecraftProfileEndpoint = requireHttps(minecraftProfileEndpoint, "minecraftProfileEndpoint");
        callbackTimeout = bounded(callbackTimeout, Duration.ofSeconds(30), Duration.ofMinutes(15),
            "callbackTimeout");
        requestTimeout = bounded(requestTimeout, Duration.ofSeconds(2), Duration.ofMinutes(2),
            "requestTimeout");
        if (!scopes.contains("XboxLive.signin") || !scopes.contains("offline_access")) {
            throw new IllegalArgumentException("scopes must contain XboxLive.signin and offline_access");
        }
    }

    public MicrosoftAuthConfig(String clientId) {
        this(
            clientId,
            DEFAULT_SCOPES,
            URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"),
            URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/token"),
            URI.create("https://user.auth.xboxlive.com/user/authenticate"),
            URI.create("https://xsts.auth.xboxlive.com/xsts/authorize"),
            URI.create("https://api.minecraftservices.com/authentication/login_with_xbox"),
            URI.create("https://api.minecraftservices.com/entitlements/mcstore"),
            URI.create("https://api.minecraftservices.com/minecraft/profile"),
            Duration.ofMinutes(5),
            Duration.ofSeconds(30)
        );
    }

    public static MicrosoftAuthConfig defaultConfig() {
        return new MicrosoftAuthConfig(CLIENT_ID);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 512 || trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return trimmed;
    }

    private static URI requireHttps(URI value, String name) {
        Objects.requireNonNull(value, name);
        if (!"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null || value.getUserInfo() != null
            || value.getFragment() != null) {
            throw new IllegalArgumentException(name + " must be an HTTPS URI without user info or fragment");
        }
        return value;
    }

    private static Duration bounded(Duration value, Duration minimum, Duration maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }
}
