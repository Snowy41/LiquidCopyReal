package dev.liquidcopy.launcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountStoreTest {
    @TempDir
    Path directory;

    @Test
    void atomicallyRoundTripsAllLaunchAndRefreshData() throws Exception {
        AccountStore store = AccountStore.inDirectory(directory.resolve("account-data"));
        MinecraftAccount account = account();

        store.save(account);

        assertEquals(account, store.load().orElseThrow());
        assertEquals(directory.resolve("account-data").resolve(AccountStore.FILE_NAME).toAbsolutePath(), store.path());
        String serialized = Files.readString(store.path());
        assertFalse(serialized.contains("msa-refresh-secret"));
        assertFalse(serialized.contains("msa-access-secret"));
        assertFalse(serialized.contains("minecraft-access-secret"));
        JsonObject envelope = JsonParser.parseString(serialized).getAsJsonObject();
        assertEquals(2, envelope.get("schema").getAsInt());
        assertEquals(AccountProtector.system().id(), envelope.get("protection").getAsString());
        assertFalse(account.toString().contains("msa-refresh-secret"));
        assertFalse(account.toString().contains("minecraft-access-secret"));
        assertTrue(account.hasUsableMinecraftToken(
            Clock.fixed(Instant.parse("2026-08-22T10:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(2)));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsDpapiEnvelopeContainsNoTokenStringsAndRoundTripsForCurrentUser() throws Exception {
        AccountStore store = AccountStore.inDirectory(directory.resolve("dpapi"));
        MinecraftAccount account = account();

        store.save(account);

        String envelope = Files.readString(store.path());
        assertTrue(envelope.contains(AccountProtector.WINDOWS_DPAPI));
        assertFalse(envelope.contains(account.minecraftAccessToken()));
        assertFalse(envelope.contains(account.microsoftAccessToken()));
        assertFalse(envelope.contains(account.microsoftRefreshToken()));
        assertEquals(account, store.load().orElseThrow());
    }

    @Test
    void migratesLegacyPlaintextSchemaOnSuccessfulLoad() throws Exception {
        AccountStore store = AccountStore.inDirectory(directory.resolve("migration"));
        MinecraftAccount account = account();
        JsonObject legacy = new JsonObject();
        legacy.addProperty("schema", 1);
        legacy.add("account", AccountStore.toJson(account));
        Files.createDirectories(store.path().getParent());
        Files.writeString(store.path(), legacy.toString());

        assertEquals(account, store.load().orElseThrow());

        String migrated = Files.readString(store.path());
        assertEquals(2, JsonParser.parseString(migrated).getAsJsonObject().get("schema").getAsInt());
        assertFalse(migrated.contains(account.minecraftAccessToken()));
        assertFalse(migrated.contains(account.microsoftRefreshToken()));
    }

    @Test
    void rejectsMalformedDataAndLogoutClearsIt() throws Exception {
        AccountStore store = AccountStore.inDirectory(directory);
        Files.writeString(store.path(), "{\"schema\":99}");
        assertThrows(java.io.IOException.class, store::load);

        store.save(account());
        store.clear();

        assertTrue(store.load().isEmpty());
    }

    private static MinecraftAccount account() {
        return new MinecraftAccount(
            "TestPlayer",
            "12345678123456781234567812345678",
            "281474900000001",
            "test-client-id",
            "minecraft-access-secret",
            Instant.parse("2026-08-23T10:00:00Z"),
            "msa-access-secret",
            Instant.parse("2026-08-22T11:00:00Z"),
            "msa-refresh-secret",
            "XboxLive.signin offline_access",
            Instant.parse("2026-08-22T10:00:00Z"),
            List.of("product_minecraft", "game_minecraft"),
            List.of(new MinecraftAccount.Skin("skin-id", "ACTIVE", URI.create("https://textures.test/skin"),
                "CLASSIC", "default")),
            List.of(new MinecraftAccount.Cape("cape-id", "ACTIVE", URI.create("https://textures.test/cape"),
                "Migrator"))
        );
    }
}
