package dev.liquidcopy.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Standalone Microsoft OAuth (authorization-code + PKCE), Xbox, and Minecraft authentication.
 * The operating-system browser owns the Microsoft sign-in UI and this service only receives the
 * short-lived authorization callback on an IPv4 loopback socket.
 */
public final class MicrosoftAuthService {
    // Register http://localhost as the desktop/mobile redirect URI. Microsoft ignores the dynamic
    // loopback port during redirect matching but still requires the path to match exactly.
    private static final String CALLBACK_PATH = "/";
    private static final Duration LAUNCH_TOKEN_MARGIN = Duration.ofMinutes(2);
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_QUERY_BYTES = 16 * 1024;
    private static final Consumer<AuthProgress> NO_PROGRESS = ignored -> { };

    private final MicrosoftAuthConfig config;
    private final AccountStore accountStore;
    private final AuthHttpTransport transport;
    private final SystemBrowser browser;
    private final Clock clock;
    private final SecureRandom random;
    private final AtomicBoolean authenticationInProgress = new AtomicBoolean();

    public MicrosoftAuthService(MicrosoftAuthConfig config, AccountStore accountStore) {
        this(config, accountStore, AuthHttpTransport.jdk(), SystemBrowser.desktop(), Clock.systemUTC(),
            new SecureRandom());
    }

    /** Full dependency-injection constructor used by tests and alternate front ends. */
    public MicrosoftAuthService(
        MicrosoftAuthConfig config,
        AccountStore accountStore,
        AuthHttpTransport transport,
        SystemBrowser browser,
        Clock clock,
        SecureRandom random
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.accountStore = Objects.requireNonNull(accountStore, "accountStore");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.browser = Objects.requireNonNull(browser, "browser");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Uses the configured system property/environment public-client ID. */
    public static MicrosoftAuthService createDefault(java.nio.file.Path dataDirectory) {
        return create(dataDirectory, MicrosoftAuthConfig.defaultConfig());
    }

    /** Uses an explicit registered public-client ID/configuration and persists below dataDirectory. */
    public static MicrosoftAuthService create(java.nio.file.Path dataDirectory, MicrosoftAuthConfig config) {
        return new MicrosoftAuthService(config, AccountStore.inDirectory(dataDirectory));
    }

    public AccountStore accountStore() {
        return accountStore;
    }

    public Optional<MinecraftAccount> savedAccount() throws IOException {
        return accountStore.load();
    }

    /**
     * Opens Microsoft authorization in the system browser and blocks the calling worker thread until
     * the loopback callback completes. GUI callers should invoke this method off the event thread.
     */
    public MinecraftAccount loginWithBrowser(Consumer<AuthProgress> progress)
        throws IOException, InterruptedException {
        Consumer<AuthProgress> listener = progress == null ? NO_PROGRESS : progress;
        beginExclusiveAuthentication();
        try {
            Pkce pkce = newPkce();
            String state = randomToken(32);
            try (LoopbackCallback callback = LoopbackCallback.start(state, config.callbackTimeout())) {
                URI authorizationUri = authorizationUri(callback.redirectUri(), pkce.challenge(), state);
                emit(listener, new AuthProgress(AuthStage.OPENING_BROWSER,
                    "Opening Microsoft sign-in in your default browser…", authorizationUri));
                try {
                    browser.open(authorizationUri);
                } catch (IOException | RuntimeException exception) {
                    throw new MicrosoftAuthException("browser_open_failed",
                        "The system browser could not be opened", exception);
                }
                emit(listener, AuthProgress.of(AuthStage.WAITING_FOR_CALLBACK,
                    "Complete Microsoft sign-in in the browser…"));
                String code = callback.awaitCode();
                emit(listener, AuthProgress.of(AuthStage.EXCHANGING_MICROSOFT_TOKEN,
                    "Completing Microsoft authorization…"));
                MicrosoftTokens tokens = exchangeAuthorizationCode(code, callback.redirectUri(), pkce.verifier());
                MinecraftAccount account = authenticateMinecraft(tokens, listener);
                accountStore.save(account);
                emit(listener, AuthProgress.of(AuthStage.COMPLETE,
                    "Signed in as " + account.username()));
                return account;
            }
        } finally {
            authenticationInProgress.set(false);
        }
    }

    /** Refreshes the persisted Microsoft token and builds a new Xbox/Minecraft session. */
    public MinecraftAccount refreshSavedAccount(Consumer<AuthProgress> progress)
        throws IOException, InterruptedException {
        Consumer<AuthProgress> listener = progress == null ? NO_PROGRESS : progress;
        beginExclusiveAuthentication();
        try {
            MinecraftAccount saved = accountStore.load().orElseThrow(() ->
                new MicrosoftAuthException("no_saved_account", "No Microsoft account is saved"));
            if (!config.clientId().equals(saved.clientId())) {
                throw new MicrosoftAuthException("client_id_changed",
                    "The Microsoft OAuth client ID changed; sign in again");
            }
            emit(listener, AuthProgress.of(AuthStage.EXCHANGING_MICROSOFT_TOKEN,
                "Refreshing Microsoft authorization…"));
            MicrosoftTokens tokens = refreshMicrosoftToken(saved.microsoftRefreshToken());
            MinecraftAccount account = authenticateMinecraft(tokens, listener);
            accountStore.save(account);
            emit(listener, AuthProgress.of(AuthStage.COMPLETE,
                "Session refreshed for " + account.username()));
            return account;
        } finally {
            authenticationInProgress.set(false);
        }
    }

    /** Returns a saved launch session, refreshing it only when it is near expiry. */
    public Optional<MinecraftAccount> accountForLaunch(Consumer<AuthProgress> progress)
        throws IOException, InterruptedException {
        Optional<MinecraftAccount> saved = accountStore.load();
        if (saved.isEmpty() || saved.get().hasUsableMinecraftToken(clock, LAUNCH_TOKEN_MARGIN)) {
            return saved;
        }
        return Optional.of(refreshSavedAccount(progress));
    }

    /** Removes all locally persisted access and refresh data. */
    public void logout() throws IOException {
        if (!authenticationInProgress.compareAndSet(false, true)) {
            throw new MicrosoftAuthException("authentication_in_progress",
                "A Microsoft authentication operation is already running");
        }
        try {
            accountStore.clear();
        } finally {
            authenticationInProgress.set(false);
        }
    }

    private void beginExclusiveAuthentication() throws MicrosoftAuthException {
        if (!authenticationInProgress.compareAndSet(false, true)) {
            throw new MicrosoftAuthException("authentication_in_progress",
                "A Microsoft authentication operation is already running");
        }
    }

    private URI authorizationUri(URI redirectUri, String challenge, String state) {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("client_id", config.clientId());
        query.put("response_type", "code");
        query.put("redirect_uri", redirectUri.toASCIIString());
        query.put("response_mode", "query");
        query.put("scope", config.scopes());
        query.put("code_challenge", challenge);
        query.put("code_challenge_method", "S256");
        // Use the account chooser from the real system browser. Microsoft can reuse that browser's
        // existing login.live.com / microsoftonline.com session cookies without exposing them here.
        query.put("prompt", "select_account");
        query.put("state", state);
        return URI.create(config.authorizationEndpoint().toASCIIString() + "?" + formEncode(query));
    }

    private MicrosoftTokens exchangeAuthorizationCode(String code, URI redirectUri, String verifier)
        throws IOException, InterruptedException {
        LinkedHashMap<String, String> form = new LinkedHashMap<>();
        form.put("client_id", config.clientId());
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri.toASCIIString());
        form.put("code_verifier", verifier);
        form.put("scope", config.scopes());
        return parseMicrosoftTokens(postForm(config.tokenEndpoint(), form), null);
    }

    private MicrosoftTokens refreshMicrosoftToken(String refreshToken) throws IOException, InterruptedException {
        LinkedHashMap<String, String> form = new LinkedHashMap<>();
        form.put("client_id", config.clientId());
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("scope", config.scopes());
        return parseMicrosoftTokens(postForm(config.tokenEndpoint(), form), refreshToken);
    }

    private MicrosoftTokens parseMicrosoftTokens(JsonResponse response, String previousRefreshToken)
        throws MicrosoftAuthException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String serviceCode = optionalString(response.json(), "error");
            String description = optionalString(response.json(), "error_description");
            if ("invalid_client".equals(serviceCode) || "unauthorized_client".equals(serviceCode)
                || description.contains("AADSTS700016") || description.contains("AADSTS7000218")) {
                throw new MicrosoftAuthException("invalid_app_registration",
                    "Invalid app registration: Microsoft rejected this public desktop client ID or redirect URI. "
                        + "Use the distributor's own registration enabled for Xbox Live/Minecraft Services.",
                    response.statusCode());
            }
        }
        requireSuccess(response, "microsoft_oauth_failed", "Microsoft authorization failed");
        JsonObject json = response.json();
        String accessToken = requiredString(json, "access_token", "Microsoft access token");
        String refreshToken = optionalString(json, "refresh_token");
        if (refreshToken.isEmpty()) {
            refreshToken = previousRefreshToken == null ? "" : previousRefreshToken;
        }
        if (refreshToken.isEmpty()) {
            throw new MicrosoftAuthException("missing_refresh_token",
                "Microsoft did not return offline access; verify the offline_access scope");
        }
        String scope = optionalString(json, "scope");
        if (scope.isEmpty()) {
            scope = config.scopes();
        }
        return new MicrosoftTokens(accessToken, refreshToken, expiresAt(json, "expires_in"), scope);
    }

    private MinecraftAccount authenticateMinecraft(MicrosoftTokens microsoft, Consumer<AuthProgress> listener)
        throws IOException, InterruptedException {
        emit(listener, AuthProgress.of(AuthStage.AUTHENTICATING_XBOX, "Authenticating with Xbox Live…"));
        XboxToken userToken = authenticateXboxUser(microsoft.accessToken());
        emit(listener, AuthProgress.of(AuthStage.AUTHENTICATING_XBOX,
            "Xbox Live identity accepted; requesting the Minecraft security token…"));
        XboxToken xsts = authorizeXsts(userToken.token());
        if (!userToken.userHash().equals(xsts.userHash())) {
            throw new MicrosoftAuthException("xbox_identity_mismatch", "Xbox authentication identity mismatch");
        }

        emit(listener, AuthProgress.of(AuthStage.AUTHENTICATING_MINECRAFT,
            "Creating a Minecraft session…"));
        JsonObject minecraftLogin = new JsonObject();
        minecraftLogin.addProperty("identityToken", "XBL3.0 x=" + xsts.userHash() + ';' + xsts.token());
        JsonResponse minecraftResponse = postJson(config.minecraftAuthenticationEndpoint(), minecraftLogin);
        requireSuccess(minecraftResponse, "minecraft_authentication_failed", "Minecraft authentication failed");
        String minecraftAccessToken = requiredString(minecraftResponse.json(), "access_token",
            "Minecraft access token");
        Instant minecraftExpiresAt = expiresAt(minecraftResponse.json(), "expires_in");
        String xuid = xsts.xuid();

        emit(listener, AuthProgress.of(AuthStage.CHECKING_OWNERSHIP,
            "Checking Minecraft ownership…"));
        JsonResponse entitlementsResponse = getBearer(config.minecraftEntitlementsEndpoint(), minecraftAccessToken);
        requireSuccess(entitlementsResponse, "minecraft_entitlements_failed",
            "Minecraft ownership could not be checked");
        List<String> entitlements = entitlementNames(entitlementsResponse.json());
        if (!entitlements.contains("game_minecraft")) {
            throw new MicrosoftAuthException("minecraft_not_owned",
                "This Microsoft account is missing the required game_minecraft Java entitlement");
        }

        emit(listener, AuthProgress.of(AuthStage.FETCHING_PROFILE, "Loading the Minecraft profile…"));
        JsonResponse profileResponse = getBearer(config.minecraftProfileEndpoint(), minecraftAccessToken);
        if (profileResponse.statusCode() == 404) {
            throw new MicrosoftAuthException("minecraft_profile_missing",
                "This account owns Minecraft but has no Java profile", 404);
        }
        requireSuccess(profileResponse, "minecraft_profile_failed", "Minecraft profile loading failed");
        JsonObject profile = profileResponse.json();
        String uuid = requiredString(profile, "id", "Minecraft profile ID");
        String username = requiredString(profile, "name", "Minecraft profile name");
        List<MinecraftAccount.Skin> skins = parseSkins(profile);
        List<MinecraftAccount.Cape> capes = parseCapes(profile);
        return new MinecraftAccount(
            username,
            uuid,
            xuid,
            config.clientId(),
            minecraftAccessToken,
            minecraftExpiresAt,
            microsoft.accessToken(),
            microsoft.expiresAt(),
            microsoft.refreshToken(),
            microsoft.scope(),
            clock.instant(),
            entitlements,
            skins,
            capes
        );
    }

    private XboxToken authenticateXboxUser(String microsoftAccessToken) throws IOException, InterruptedException {
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", "d=" + microsoftAccessToken);
        JsonObject body = new JsonObject();
        body.add("Properties", properties);
        body.addProperty("RelyingParty", "http://auth.xboxlive.com");
        body.addProperty("TokenType", "JWT");
        JsonResponse response = postXboxJson(config.xboxUserAuthenticationEndpoint(), body);
        requireSuccess(response, "xbox_authentication_failed", "Xbox Live authentication failed");
        return parseXboxToken(response.json());
    }

    private XboxToken authorizeXsts(String userToken) throws IOException, InterruptedException {
        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");
        JsonArray tokens = new JsonArray();
        tokens.add(userToken);
        properties.add("UserTokens", tokens);
        JsonObject body = new JsonObject();
        body.add("Properties", properties);
        body.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        body.addProperty("TokenType", "JWT");
        JsonResponse response = postXboxJson(config.xstsAuthorizationEndpoint(), body);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            long xerr = optionalLong(response.json(), "XErr", -1L);
            String explanation;
            if (xerr == 2148916233L) {
                explanation = "This Microsoft account needs an Xbox profile and gamertag before it can play Minecraft";
            } else if (xerr == 2148916235L) {
                explanation = "Xbox Live is unavailable for this account's region";
            } else if (xerr == 2148916236L || xerr == 2148916237L) {
                explanation = "This Xbox account requires adult verification";
            } else if (xerr == 2148916238L) {
                explanation = "This child account must be added to a Microsoft family by an adult";
            } else {
                explanation = "Xbox security-token authorization failed";
            }
            StringBuilder message = new StringBuilder(explanation)
                .append(" (HTTP ").append(response.statusCode());
            if (xerr >= 0) {
                message.append(", XErr=").append(xerr);
            }
            message.append(')');
            String serviceMessage = optionalString(response.json(), "Message");
            if (!serviceMessage.isBlank()) {
                message.append(". Xbox: ").append(sanitizeMessage(serviceMessage, "No service detail"));
            }
            String redirect = optionalString(response.json(), "Redirect");
            if (!redirect.isBlank()) {
                message.append(". Account action: ").append(sanitizeMessage(redirect, "Open Xbox account settings"));
            }
            throw new MicrosoftAuthException("xsts_authorization_failed", message.toString(), response.statusCode());
        }
        return parseXboxToken(response.json());
    }

    private static XboxToken parseXboxToken(JsonObject json) throws MicrosoftAuthException {
        String token = requiredString(json, "Token", "Xbox token");
        JsonElement claimsElement = json.get("DisplayClaims");
        if (claimsElement == null || !claimsElement.isJsonObject()) {
            throw new MicrosoftAuthException("xbox_claims_missing", "Xbox identity claims are missing");
        }
        JsonElement xuiElement = claimsElement.getAsJsonObject().get("xui");
        if (xuiElement == null || !xuiElement.isJsonArray() || xuiElement.getAsJsonArray().isEmpty()
            || !xuiElement.getAsJsonArray().get(0).isJsonObject()) {
            throw new MicrosoftAuthException("xbox_claims_missing", "Xbox identity claims are missing");
        }
        JsonObject xui = xuiElement.getAsJsonArray().get(0).getAsJsonObject();
        String userHash = requiredString(xui, "uhs", "Xbox user hash");
        String xuid = optionalString(xui, "xid");
        if (xuid.isEmpty()) {
            xuid = optionalString(xui, "xuid");
        }
        if (!xuid.isEmpty() && !xuid.matches("[0-9]{1,20}")) {
            throw new MicrosoftAuthException("invalid_xbox_claims", "Xbox xid account claim is invalid");
        }
        return new XboxToken(token, userHash, xuid);
    }

    private JsonResponse postForm(URI uri, Map<String, String> form) throws IOException, InterruptedException {
        byte[] body = formEncode(form).getBytes(StandardCharsets.UTF_8);
        return request(new AuthHttpTransport.Request("POST", uri, Map.of(
            "Accept", "application/json",
            "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8",
            "User-Agent", "LiquidCopy-Launcher/1.21.11"
        ), body, config.requestTimeout()));
    }

    private JsonResponse postJson(URI uri, JsonObject json) throws IOException, InterruptedException {
        return request(new AuthHttpTransport.Request("POST", uri, Map.of(
            "Accept", "application/json",
            "Content-Type", "application/json; charset=UTF-8",
            "User-Agent", "LiquidCopy-Launcher/1.21.11"
        ), json.toString().getBytes(StandardCharsets.UTF_8), config.requestTimeout()));
    }

    private JsonResponse postXboxJson(URI uri, JsonObject json) throws IOException, InterruptedException {
        return request(new AuthHttpTransport.Request("POST", uri, Map.of(
            "Accept", "application/json",
            "Content-Type", "application/json; charset=UTF-8",
            "User-Agent", "LiquidCopy-Launcher/1.21.11",
            "x-xbl-contract-version", "1"
        ), json.toString().getBytes(StandardCharsets.UTF_8), config.requestTimeout()));
    }

    private JsonResponse getBearer(URI uri, String accessToken) throws IOException, InterruptedException {
        return request(new AuthHttpTransport.Request("GET", uri, Map.of(
            "Accept", "application/json",
            "Authorization", "Bearer " + accessToken,
            "User-Agent", "LiquidCopy-Launcher/1.21.11"
        ), new byte[0], config.requestTimeout()));
    }

    private JsonResponse request(AuthHttpTransport.Request request) throws IOException, InterruptedException {
        AuthHttpTransport.Response response = transport.send(request);
        byte[] bytes = response.body();
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new MicrosoftAuthException("response_too_large", "Authentication service response is too large",
                response.statusCode());
        }
        JsonObject json;
        if (bytes.length == 0) {
            json = new JsonObject();
        } else {
            try {
                JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) {
                    throw new IllegalArgumentException("not an object");
                }
                json = parsed.getAsJsonObject();
            } catch (RuntimeException exception) {
                throw new MicrosoftAuthException("invalid_service_response",
                    "Authentication service returned invalid JSON", response.statusCode());
            }
        }
        return new JsonResponse(response.statusCode(), json);
    }

    private static void requireSuccess(JsonResponse response, String code, String fallback)
        throws MicrosoftAuthException {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        String description = optionalString(response.json(), "error_description");
        if (description.isEmpty()) {
            JsonElement error = response.json().get("error");
            if (error != null && error.isJsonPrimitive()) {
                description = error.getAsString();
            } else if (error != null && error.isJsonObject()) {
                description = optionalString(error.getAsJsonObject(), "message");
            }
        }
        String message = description.isEmpty() ? fallback : sanitizeMessage(description, fallback);
        throw new MicrosoftAuthException(code, message, response.statusCode());
    }

    private Instant expiresAt(JsonObject json, String field) throws MicrosoftAuthException {
        long seconds = optionalLong(json, field, -1L);
        if (seconds <= 0 || seconds > Duration.ofDays(31).toSeconds()) {
            throw new MicrosoftAuthException("invalid_token_expiry", "Authentication token expiry is invalid");
        }
        return clock.instant().plusSeconds(seconds);
    }

    private static List<String> entitlementNames(JsonObject json) throws MicrosoftAuthException {
        JsonElement items = json.get("items");
        if (items == null || !items.isJsonArray()) {
            throw new MicrosoftAuthException("invalid_entitlements", "Minecraft entitlements response is invalid");
        }
        List<String> names = new ArrayList<>();
        for (JsonElement item : items.getAsJsonArray()) {
            if (item.isJsonObject()) {
                String name = optionalString(item.getAsJsonObject(), "name");
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return List.copyOf(names);
    }

    private static List<MinecraftAccount.Skin> parseSkins(JsonObject profile) throws MicrosoftAuthException {
        JsonArray array = optionalArray(profile, "skins");
        List<MinecraftAccount.Skin> result = new ArrayList<>();
        try {
            for (JsonElement element : array) {
                JsonObject skin = element.getAsJsonObject();
                result.add(new MinecraftAccount.Skin(requiredString(skin, "id", "skin ID"),
                    optionalString(skin, "state"), URI.create(requiredString(skin, "url", "skin URL")),
                    optionalString(skin, "variant"), optionalString(skin, "alias")));
            }
        } catch (RuntimeException exception) {
            throw new MicrosoftAuthException("invalid_minecraft_profile", "Minecraft skin data is invalid", exception);
        }
        return List.copyOf(result);
    }

    private static List<MinecraftAccount.Cape> parseCapes(JsonObject profile) throws MicrosoftAuthException {
        JsonArray array = optionalArray(profile, "capes");
        List<MinecraftAccount.Cape> result = new ArrayList<>();
        try {
            for (JsonElement element : array) {
                JsonObject cape = element.getAsJsonObject();
                result.add(new MinecraftAccount.Cape(requiredString(cape, "id", "cape ID"),
                    optionalString(cape, "state"), URI.create(requiredString(cape, "url", "cape URL")),
                    optionalString(cape, "alias")));
            }
        } catch (RuntimeException exception) {
            throw new MicrosoftAuthException("invalid_minecraft_profile", "Minecraft cape data is invalid", exception);
        }
        return List.copyOf(result);
    }

    private Pkce newPkce() {
        String verifier = randomToken(64);
        return new Pkce(verifier, base64Url(sha256(verifier.getBytes(StandardCharsets.US_ASCII))));
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return base64Url(value);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String formEncode(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        values.forEach((key, value) -> {
            if (!result.isEmpty()) {
                result.append('&');
            }
            result.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
            result.append('=');
            result.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return result.toString();
    }

    private static void emit(Consumer<AuthProgress> progress, AuthProgress update) {
        progress.accept(update);
    }

    private static String requiredString(JsonObject json, String field, String label)
        throws MicrosoftAuthException {
        String value = optionalString(json, field);
        if (value.isBlank()) {
            throw new MicrosoftAuthException("missing_service_field", label + " is missing");
        }
        if (value.length() > 65_536 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new MicrosoftAuthException("invalid_service_field", label + " is invalid");
        }
        return value;
    }

    private static String optionalString(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return "";
        }
        try {
            return value.getAsString();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static long optionalLong(JsonObject json, String field, long fallback) {
        JsonElement value = json.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static JsonArray optionalArray(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String sanitizeMessage(String value, String fallback) {
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (clean.isEmpty()) {
            return fallback;
        }
        return clean.length() <= 500 ? clean : clean.substring(0, 500);
    }

    private record Pkce(String verifier, String challenge) { }

    private record MicrosoftTokens(String accessToken, String refreshToken, Instant expiresAt, String scope) { }

    private record XboxToken(String token, String userHash, String xuid) { }

    private record JsonResponse(int statusCode, JsonObject json) { }

    private static final class LoopbackCallback implements AutoCloseable {
        private static final byte[] SUCCESS_PAGE = ("<!doctype html><meta charset=utf-8>"
            + "<title>LiquidCopy sign-in complete</title>"
            + "<h1>Signed in to LiquidCopy</h1><p>You can close this browser tab and return to the launcher.</p>")
            .getBytes(StandardCharsets.UTF_8);
        private static final byte[] FAILURE_PAGE = ("<!doctype html><meta charset=utf-8>"
            + "<title>LiquidCopy sign-in failed</title>"
            + "<h1>Sign-in was not completed</h1><p>Return to LiquidCopy and try again.</p>")
            .getBytes(StandardCharsets.UTF_8);

        private final HttpServer server;
        private final URI redirectUri;
        private final String expectedState;
        private final Duration timeout;
        private final CompletableFuture<String> authorizationCode = new CompletableFuture<>();

        private LoopbackCallback(HttpServer server, URI redirectUri, String expectedState, Duration timeout) {
            this.server = server;
            this.redirectUri = redirectUri;
            this.expectedState = expectedState;
            this.timeout = timeout;
        }

        static LoopbackCallback start(String state, Duration timeout) throws IOException {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            HttpServer server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
            int port = server.getAddress().getPort();
            URI redirect = URI.create("http://localhost:" + port + CALLBACK_PATH);
            LoopbackCallback callback = new LoopbackCallback(server, redirect, state, timeout);
            server.createContext(CALLBACK_PATH, callback::handle);
            server.start();
            return callback;
        }

        URI redirectUri() {
            return redirectUri;
        }

        String awaitCode() throws IOException, InterruptedException {
            try {
                return authorizationCode.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                throw new MicrosoftAuthException("browser_callback_timeout",
                    "Microsoft sign-in timed out; start the sign-in again", exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                throw new MicrosoftAuthException("browser_callback_failed",
                    "Microsoft sign-in callback failed", cause);
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] page = FAILURE_PAGE;
            int status = 400;
            try {
                if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                    status = 403;
                    return;
                }
                if (!"GET".equals(exchange.getRequestMethod())) {
                    status = 405;
                    return;
                }
                if (!CALLBACK_PATH.equals(exchange.getRequestURI().getPath())) {
                    status = 404;
                    return;
                }
                String rawQuery = exchange.getRequestURI().getRawQuery();
                if (rawQuery == null || rawQuery.length() > MAX_QUERY_BYTES) {
                    return;
                }
                Map<String, String> query = decodeQuery(rawQuery);
                if (!constantTimeEquals(expectedState, query.getOrDefault("state", ""))) {
                    return;
                }
                String oauthError = query.getOrDefault("error", "");
                if (!oauthError.isBlank()) {
                    String description = sanitizeMessage(query.getOrDefault("error_description", ""),
                        "Microsoft sign-in was cancelled");
                    if ("invalid_client".equals(oauthError) || "unauthorized_client".equals(oauthError)) {
                        authorizationCode.completeExceptionally(new MicrosoftAuthException(
                            "invalid_app_registration",
                            "Invalid app registration: " + description));
                    } else {
                        authorizationCode.completeExceptionally(new MicrosoftAuthException(
                            "oauth_" + safeCode(oauthError), description));
                    }
                    return;
                }
                String code = query.getOrDefault("code", "");
                if (code.isBlank() || code.length() > 16_384) {
                    authorizationCode.completeExceptionally(new MicrosoftAuthException("authorization_code_missing",
                        "Microsoft did not return an authorization code"));
                    return;
                }
                page = SUCCESS_PAGE;
                status = 200;
                authorizationCode.complete(code);
            } catch (RuntimeException exception) {
                authorizationCode.completeExceptionally(new MicrosoftAuthException("invalid_browser_callback",
                    "Microsoft returned an invalid sign-in callback", exception));
            } finally {
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
                exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
                exchange.sendResponseHeaders(status, page.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(page);
                }
            }
        }

        private static Map<String, String> decodeQuery(String raw) {
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            for (String pair : raw.split("&")) {
                int separator = pair.indexOf('=');
                String key = URLDecoder.decode(separator < 0 ? pair : pair.substring(0, separator),
                    StandardCharsets.UTF_8);
                String value = URLDecoder.decode(separator < 0 ? "" : pair.substring(separator + 1),
                    StandardCharsets.UTF_8);
                if (result.putIfAbsent(key, value) != null) {
                    throw new IllegalArgumentException("duplicate callback parameter");
                }
            }
            return result;
        }

        private static boolean constantTimeEquals(String left, String right) {
            return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
        }

        private static String safeCode(String value) {
            String clean = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            return clean.isEmpty() ? "failure" : clean.substring(0, Math.min(clean.length(), 48));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
