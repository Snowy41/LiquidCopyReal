package dev.liquidcopy.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrosoftAuthServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    @TempDir
    Path directory;

    @Test
    void browserPkceLoginExchangesEveryTokenChecksOwnershipAndPersistsAccount() throws Exception {
        MicrosoftAuthConfig config = testConfig();
        MockTransport transport = new MockTransport(config);
        AtomicReference<Map<String, String>> authorization = new AtomicReference<>();
        SystemBrowser browser = uri -> {
            Map<String, String> query = decode(uri.getRawQuery());
            authorization.set(query);
            URI redirect = URI.create(query.get("redirect_uri") + "?code=browser-code&state="
                + encode(query.get("state")));
            try {
                HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(redirect).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("Interrupted while simulating browser callback", exception);
            }
        };
        AccountStore store = AccountStore.inDirectory(directory);
        MicrosoftAuthService service = new MicrosoftAuthService(config, store, transport, browser,
            Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
        List<AuthProgress> progress = new ArrayList<>();

        MinecraftAccount account = service.loginWithBrowser(progress::add);

        assertEquals("TestPlayer", account.username());
        assertEquals("12345678123456781234567812345678", account.uuid());
        assertEquals("281474900000001", account.xuid());
        assertEquals("registered-client-id", account.clientId());
        assertEquals("minecraft-access-1", account.minecraftAccessToken());
        assertEquals(List.of("product_minecraft", "game_minecraft"), account.entitlements());
        assertEquals(account, store.load().orElseThrow());
        assertEquals(AuthStage.OPENING_BROWSER, progress.getFirst().stage());
        assertEquals(AuthStage.COMPLETE, progress.getLast().stage());
        assertTrue(progress.stream().anyMatch(update -> update.stage() == AuthStage.CHECKING_OWNERSHIP));

        Map<String, String> authorize = authorization.get();
        assertEquals("code", authorize.get("response_type"));
        assertEquals("query", authorize.get("response_mode"));
        assertEquals("S256", authorize.get("code_challenge_method"));
        assertEquals("localhost", URI.create(authorize.get("redirect_uri")).getHost());
        assertTrue(URI.create(authorize.get("redirect_uri")).getPort() > 0);
        Map<String, String> tokenForm = transport.authorizationCodeForm.get();
        assertEquals("browser-code", tokenForm.get("code"));
        assertEquals(authorize.get("redirect_uri"), tokenForm.get("redirect_uri"));
        assertEquals(authorize.get("code_challenge"), challenge(tokenForm.get("code_verifier")));
        assertFalse(tokenForm.containsKey("client_secret"));
        JsonArray optionalDisplayClaims = transport.xstsRequest.get().getAsJsonObject("Properties")
            .getAsJsonArray("OptionalDisplayClaims");
        assertEquals(List.of("xid"), List.of(optionalDisplayClaims.get(0).getAsString()));

        int requestsBeforeReuse = transport.requests.size();
        assertEquals(account, service.accountForLaunch(progress::add).orElseThrow());
        assertEquals(requestsBeforeReuse, transport.requests.size());

        MinecraftAccount refreshed = service.refreshSavedAccount(progress::add);
        assertEquals("minecraft-access-2", refreshed.minecraftAccessToken());
        assertEquals("msa-refresh-2", refreshed.microsoftRefreshToken());
        assertEquals("msa-refresh-1", transport.refreshForm.get().get("refresh_token"));
        assertNotEquals(account.minecraftAccessToken(), refreshed.minecraftAccessToken());

        service.logout();
        assertTrue(service.savedAccount().isEmpty());
    }

    @Test
    void browserCancellationReturnsStableErrorWithoutCallingTokenServices() throws Exception {
        MicrosoftAuthConfig config = testConfig();
        MockTransport transport = new MockTransport(config);
        SystemBrowser browser = uri -> {
            Map<String, String> query = decode(uri.getRawQuery());
            URI redirect = URI.create(query.get("redirect_uri") + "?error=access_denied&error_description="
                + encode("The user cancelled sign-in") + "&state=" + encode(query.get("state")));
            try {
                HttpClient.newHttpClient().send(HttpRequest.newBuilder(redirect).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("Interrupted while simulating browser callback", exception);
            }
        };
        MicrosoftAuthService service = new MicrosoftAuthService(config, AccountStore.inDirectory(directory),
            transport, browser, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());

        MicrosoftAuthException failure = assertThrows(MicrosoftAuthException.class,
            () -> service.loginWithBrowser(update -> { }));

        assertEquals("oauth_access_denied", failure.code());
        assertEquals("The user cancelled sign-in", failure.getMessage());
        assertTrue(transport.requests.isEmpty());
        assertTrue(service.savedAccount().isEmpty());
    }

    @Test
    void ownershipRequiresTheExactGameMinecraftEntitlement() throws Exception {
        assertNotOwned(List.of(), "empty");
        assertNotOwned(List.of("product_minecraft"), "product-only");

        MicrosoftAuthConfig config = testConfig();
        MockTransport transport = new MockTransport(config, List.of("game_minecraft"), true);
        MicrosoftAuthService service = service(config, transport, completingBrowser(), directory.resolve("game"));

        MinecraftAccount account = service.loginWithBrowser(update -> { });

        assertEquals(List.of("game_minecraft"), account.entitlements());
    }

    @Test
    void uhsOnlyXstsResponseStillCreatesAValidSessionWithEmptyXuid() throws Exception {
        MicrosoftAuthConfig config = testConfig();
        MockTransport transport = new MockTransport(config, List.of("game_minecraft"), false);
        MicrosoftAuthService service = service(config, transport, completingBrowser(), directory.resolve("no-xid"));

        MinecraftAccount account = service.loginWithBrowser(update -> { });

        assertEquals("", account.xuid());
        JsonArray requested = transport.xstsRequest.get().getAsJsonObject("Properties")
            .getAsJsonArray("OptionalDisplayClaims");
        assertEquals("xid", requested.get(0).getAsString());
        assertTrue(transport.requests.stream()
            .anyMatch(request -> request.uri().equals(config.minecraftAuthenticationEndpoint())));
    }

    @Test
    void reportsInvalidPublicClientRegistrationClearly() throws Exception {
        MicrosoftAuthConfig config = testConfig();
        AuthHttpTransport transport = request -> {
            JsonObject error = new JsonObject();
            error.addProperty("error", "invalid_client");
            error.addProperty("error_description", "AADSTS700016 application was not found");
            return MockTransport.json(401, error);
        };
        MicrosoftAuthService service = service(config, transport, completingBrowser(), directory.resolve("invalid-app"));

        MicrosoftAuthException failure = assertThrows(MicrosoftAuthException.class,
            () -> service.loginWithBrowser(update -> { }));

        assertEquals("invalid_app_registration", failure.code());
        assertTrue(failure.getMessage().startsWith("Invalid app registration"));
    }

    @Test
    void interruptCancelsLoopbackWaitAndReleasesAuthenticationLock() throws Exception {
        MicrosoftAuthConfig config = testConfig();
        MockTransport transport = new MockTransport(config);
        MicrosoftAuthService service = service(config, transport, uri -> { }, directory.resolve("interrupt"));
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread login = Thread.ofPlatform().name("test-auth-cancel").unstarted(() -> {
            try {
                service.loginWithBrowser(progress -> {
                    if (progress.stage() == AuthStage.WAITING_FOR_CALLBACK) {
                        waiting.countDown();
                    }
                });
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        });

        login.start();
        assertTrue(waiting.await(3, TimeUnit.SECONDS));
        login.interrupt();
        login.join(3_000);

        assertFalse(login.isAlive());
        assertTrue(outcome.get() instanceof InterruptedException);
        assertTrue(transport.requests.isEmpty());
        service.logout();
        assertTrue(service.savedAccount().isEmpty());
    }

    @Test
    void browserOpenFailureStillReportsTheCopyableAuthorizationUri() throws Exception {
        MicrosoftAuthConfig config = testConfig();
        List<AuthProgress> progress = new ArrayList<>();
        MicrosoftAuthService service = service(config, new MockTransport(config),
            uri -> { throw new java.io.IOException("no browser association"); }, directory.resolve("browser-fail"));

        MicrosoftAuthException failure = assertThrows(MicrosoftAuthException.class,
            () -> service.loginWithBrowser(progress::add));

        assertEquals("browser_open_failed", failure.code());
        assertEquals(AuthStage.OPENING_BROWSER, progress.getFirst().stage());
        assertEquals("https", progress.getFirst().browserUri().getScheme());
        assertEquals("auth.test", progress.getFirst().browserUri().getHost());
        assertTrue(progress.getFirst().browserUri().getRawQuery().contains("redirect_uri="));
    }

    private void assertNotOwned(List<String> entitlements, String name) throws Exception {
        MicrosoftAuthConfig config = testConfig();
        MockTransport transport = new MockTransport(config, entitlements, true);
        MicrosoftAuthService service = service(config, transport, completingBrowser(), directory.resolve(name));

        MicrosoftAuthException failure = assertThrows(MicrosoftAuthException.class,
            () -> service.loginWithBrowser(update -> { }));

        assertEquals("minecraft_not_owned", failure.code());
        assertTrue(failure.getMessage().contains("game_minecraft"));
    }

    private static MicrosoftAuthService service(
        MicrosoftAuthConfig config,
        AuthHttpTransport transport,
        SystemBrowser browser,
        Path accountDirectory
    ) {
        return new MicrosoftAuthService(config, AccountStore.inDirectory(accountDirectory), transport, browser,
            Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
    }

    private static SystemBrowser completingBrowser() {
        return uri -> {
            Map<String, String> query = decode(uri.getRawQuery());
            URI redirect = URI.create(query.get("redirect_uri") + "?code=browser-code&state="
                + encode(query.get("state")));
            try {
                HttpClient.newHttpClient().send(HttpRequest.newBuilder(redirect).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("Interrupted while simulating browser callback", exception);
            }
        };
    }

    private static MicrosoftAuthConfig testConfig() {
        return new MicrosoftAuthConfig(
            "registered-client-id",
            MicrosoftAuthConfig.DEFAULT_SCOPES,
            URI.create("https://auth.test/consumers/authorize"),
            URI.create("https://auth.test/consumers/token"),
            URI.create("https://xbox.test/user/authenticate"),
            URI.create("https://xbox.test/xsts/authorize"),
            URI.create("https://minecraft.test/authentication/login_with_xbox"),
            URI.create("https://minecraft.test/entitlements/mcstore"),
            URI.create("https://minecraft.test/minecraft/profile"),
            Duration.ofSeconds(30),
            Duration.ofSeconds(2)
        );
    }

    private static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, String> decode(String query) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            result.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return result;
    }

    private static final class MockTransport implements AuthHttpTransport {
        private final MicrosoftAuthConfig config;
        private final List<Request> requests = new ArrayList<>();
        private final AtomicReference<Map<String, String>> authorizationCodeForm = new AtomicReference<>();
        private final AtomicReference<Map<String, String>> refreshForm = new AtomicReference<>();
        private final AtomicReference<JsonObject> xstsRequest = new AtomicReference<>();
        private final AtomicInteger minecraftLogins = new AtomicInteger();
        private final List<String> entitlements;
        private final boolean includeXid;

        private MockTransport(MicrosoftAuthConfig config) {
            this(config, List.of("product_minecraft", "game_minecraft"), true);
        }

        private MockTransport(MicrosoftAuthConfig config, List<String> entitlements, boolean includeXid) {
            this.config = config;
            this.entitlements = List.copyOf(entitlements);
            this.includeXid = includeXid;
        }

        @Override
        public Response send(Request request) {
            requests.add(request);
            if (request.uri().equals(config.tokenEndpoint())) {
                Map<String, String> form = decode(new String(request.body(), StandardCharsets.UTF_8));
                JsonObject response = new JsonObject();
                if ("authorization_code".equals(form.get("grant_type"))) {
                    authorizationCodeForm.set(form);
                    response.addProperty("access_token", "msa-access-1");
                    response.addProperty("refresh_token", "msa-refresh-1");
                } else {
                    refreshForm.set(form);
                    response.addProperty("access_token", "msa-access-2");
                    response.addProperty("refresh_token", "msa-refresh-2");
                }
                response.addProperty("expires_in", 3600);
                response.addProperty("scope", MicrosoftAuthConfig.DEFAULT_SCOPES);
                return json(200, response);
            }
            if (request.uri().equals(config.xboxUserAuthenticationEndpoint())) {
                return json(200, xbox("xbox-user-token", false));
            }
            if (request.uri().equals(config.xstsAuthorizationEndpoint())) {
                xstsRequest.set(JsonParser.parseString(new String(request.body(), StandardCharsets.UTF_8))
                    .getAsJsonObject());
                return json(200, xbox("xsts-token", includeXid));
            }
            if (request.uri().equals(config.minecraftAuthenticationEndpoint())) {
                JsonObject response = new JsonObject();
                response.addProperty("access_token", "minecraft-access-" + minecraftLogins.incrementAndGet());
                response.addProperty("expires_in", 86_400);
                return json(200, response);
            }
            if (request.uri().equals(config.minecraftEntitlementsEndpoint())) {
                assertTrue(request.headers().get("Authorization").startsWith("Bearer minecraft-access-"));
                JsonObject response = new JsonObject();
                JsonArray items = new JsonArray();
                for (String name : entitlements) {
                    JsonObject item = new JsonObject();
                    item.addProperty("name", name);
                    items.add(item);
                }
                response.add("items", items);
                return json(200, response);
            }
            if (request.uri().equals(config.minecraftProfileEndpoint())) {
                JsonObject response = new JsonObject();
                response.addProperty("id", "12345678123456781234567812345678");
                response.addProperty("name", "TestPlayer");
                JsonArray skins = new JsonArray();
                JsonObject skin = new JsonObject();
                skin.addProperty("id", "skin-id");
                skin.addProperty("state", "ACTIVE");
                skin.addProperty("url", "https://textures.test/skin");
                skin.addProperty("variant", "CLASSIC");
                skins.add(skin);
                response.add("skins", skins);
                response.add("capes", new JsonArray());
                return json(200, response);
            }
            throw new AssertionError("Unexpected authentication request: " + request.uri());
        }

        private static JsonObject xbox(String token, boolean includeXuid) {
            JsonObject response = new JsonObject();
            response.addProperty("Token", token);
            JsonObject xui = new JsonObject();
            xui.addProperty("uhs", "user-hash");
            if (includeXuid) {
                xui.addProperty("xid", "281474900000001");
            }
            JsonArray values = new JsonArray();
            values.add(xui);
            JsonObject claims = new JsonObject();
            claims.add("xui", values);
            response.add("DisplayClaims", claims);
            return response;
        }

        static Response json(int status, JsonObject body) {
            return new Response(status, Map.of("content-type", List.of("application/json")),
                body.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
