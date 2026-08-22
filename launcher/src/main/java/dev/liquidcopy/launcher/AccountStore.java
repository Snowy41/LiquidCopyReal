package dev.liquidcopy.launcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Atomic, launcher-owned persistence for one Microsoft/Minecraft account. */
public final class AccountStore {
    public static final String FILE_NAME = "microsoft-account.json";
    private static final int LEGACY_PLAINTEXT_SCHEMA = 1;
    private static final int PROTECTED_ENVELOPE_SCHEMA = 2;
    private static final long MAX_BYTES = 512L * 1024L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private final Path accountFile;
    private final AccountProtector protector;

    /** Creates a store at an explicit JSON file path. */
    public AccountStore(Path accountFile) {
        this(accountFile, AccountProtector.system());
    }

    AccountStore(Path accountFile, AccountProtector protector) {
        this.accountFile = accountFile.toAbsolutePath().normalize();
        this.protector = Objects.requireNonNull(protector, "protector");
    }

    /** Creates a store named {@value #FILE_NAME} below a launcher data directory. */
    public static AccountStore inDirectory(Path directory) {
        return new AccountStore(directory.resolve(FILE_NAME));
    }

    public Path path() {
        return accountFile;
    }

    public Optional<MinecraftAccount> load() throws IOException {
        if (!Files.exists(accountFile, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(accountFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Account data is not a regular file: " + accountFile);
        }
        long size = Files.size(accountFile);
        if (size <= 0 || size > MAX_BYTES) {
            throw new IOException("Account data has an invalid size");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(accountFile, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Account data is malformed", exception);
        }
        try {
            int schema = integer(root, "schema");
            if (schema == LEGACY_PLAINTEXT_SCHEMA) {
                MinecraftAccount migrated = fromJson(object(root, "account"));
                save(migrated);
                return Optional.of(migrated);
            }
            if (schema != PROTECTED_ENVELOPE_SCHEMA) {
                throw new IOException("Unsupported account data schema");
            }
            MinecraftAccount account = readProtectedEnvelope(root);
            return Optional.of(account);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Account data is invalid", exception);
        }
    }

    public void save(MinecraftAccount account) throws IOException {
        Objects.requireNonNull(account, "account");
        byte[] plaintext = GSON.toJson(toJson(account)).getBytes(StandardCharsets.UTF_8);
        byte[] protectedBytes = null;
        JsonObject root = new JsonObject();
        try {
            protectedBytes = protector.protect(plaintext);
            root.addProperty("schema", PROTECTED_ENVELOPE_SCHEMA);
            root.addProperty("protection", protector.id());
            root.addProperty("payload", Base64.getEncoder().encodeToString(protectedBytes));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            if (protectedBytes != null) {
                Arrays.fill(protectedBytes, (byte) 0);
            }
        }
        byte[] bytes = (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Account data is too large");
        }

        Path parent = accountFile.getParent();
        if (parent == null) {
            throw new IOException("Account file has no parent directory");
        }
        Files.createDirectories(parent);
        setPrivatePermissions(parent, PRIVATE_DIRECTORY_PERMISSIONS);
        if (Files.exists(accountFile, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(accountFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to replace non-file account data: " + accountFile);
        }

        Path temporary = Files.createTempFile(parent, ".liquidcopy-account-", ".tmp");
        try {
            setPrivatePermissions(temporary, PRIVATE_FILE_PERMISSIONS);
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, accountFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, accountFile, StandardCopyOption.REPLACE_EXISTING);
            }
            setPrivatePermissions(accountFile, PRIVATE_FILE_PERMISSIONS);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void clear() throws IOException {
        if (Files.exists(accountFile, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(accountFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to delete non-file account data: " + accountFile);
        }
        Files.deleteIfExists(accountFile);
    }

    private MinecraftAccount readProtectedEnvelope(JsonObject envelope) throws IOException {
        String protection = string(envelope, "protection");
        byte[] protectedBytes;
        try {
            protectedBytes = Base64.getDecoder().decode(string(envelope, "payload"));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Account payload is not valid Base64", exception);
        }
        if (protectedBytes.length == 0 || protectedBytes.length > MAX_BYTES) {
            throw new IOException("Account payload has an invalid size");
        }

        AccountProtector envelopeProtector;
        if (protector.id().equals(protection)) {
            envelopeProtector = protector;
        } else if (AccountProtector.POSIX_OWNER_ONLY.equals(protection)) {
            envelopeProtector = AccountProtector.PosixOwnerOnly.INSTANCE;
        } else if (AccountProtector.WINDOWS_DPAPI.equals(protection)) {
            throw new IOException("This Microsoft account session is protected for a Windows user and cannot be "
                + "opened on this platform");
        } else {
            throw new IOException("Unsupported account protection " + protection);
        }

        byte[] plaintext = null;
        try {
            plaintext = envelopeProtector.unprotect(protectedBytes);
            JsonElement parsed = JsonParser.parseString(new String(plaintext, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("Protected account payload is not a JSON object");
            }
            MinecraftAccount account = fromJson(parsed.getAsJsonObject());
            if (!protector.id().equals(protection)) {
                save(account);
            }
            return account;
        } catch (RuntimeException exception) {
            throw new IOException("Protected account payload is invalid", exception);
        } finally {
            Arrays.fill(protectedBytes, (byte) 0);
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    static JsonObject toJson(MinecraftAccount account) {
        JsonObject json = new JsonObject();
        json.addProperty("username", account.username());
        json.addProperty("uuid", account.uuid());
        json.addProperty("xuid", account.xuid());
        json.addProperty("clientId", account.clientId());
        json.addProperty("minecraftAccessToken", account.minecraftAccessToken());
        json.addProperty("minecraftAccessTokenExpiresAt", account.minecraftAccessTokenExpiresAt().toString());
        json.addProperty("microsoftAccessToken", account.microsoftAccessToken());
        json.addProperty("microsoftAccessTokenExpiresAt", account.microsoftAccessTokenExpiresAt().toString());
        json.addProperty("microsoftRefreshToken", account.microsoftRefreshToken());
        json.addProperty("microsoftScope", account.microsoftScope());
        json.addProperty("authenticatedAt", account.authenticatedAt().toString());
        JsonArray entitlements = new JsonArray();
        account.entitlements().forEach(entitlements::add);
        json.add("entitlements", entitlements);
        JsonArray skins = new JsonArray();
        for (MinecraftAccount.Skin skin : account.skins()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", skin.id());
            value.addProperty("state", skin.state());
            value.addProperty("url", skin.url().toASCIIString());
            value.addProperty("variant", skin.variant());
            value.addProperty("alias", skin.alias());
            skins.add(value);
        }
        json.add("skins", skins);
        JsonArray capes = new JsonArray();
        for (MinecraftAccount.Cape cape : account.capes()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", cape.id());
            value.addProperty("state", cape.state());
            value.addProperty("url", cape.url().toASCIIString());
            value.addProperty("alias", cape.alias());
            capes.add(value);
        }
        json.add("capes", capes);
        return json;
    }

    private static MinecraftAccount fromJson(JsonObject json) {
        List<String> entitlements = new ArrayList<>();
        array(json, "entitlements").forEach(element -> entitlements.add(element.getAsString()));
        List<MinecraftAccount.Skin> skins = new ArrayList<>();
        array(json, "skins").forEach(element -> {
            JsonObject value = element.getAsJsonObject();
            skins.add(new MinecraftAccount.Skin(string(value, "id"), optional(value, "state"),
                URI.create(string(value, "url")), optional(value, "variant"), optional(value, "alias")));
        });
        List<MinecraftAccount.Cape> capes = new ArrayList<>();
        array(json, "capes").forEach(element -> {
            JsonObject value = element.getAsJsonObject();
            capes.add(new MinecraftAccount.Cape(string(value, "id"), optional(value, "state"),
                URI.create(string(value, "url")), optional(value, "alias")));
        });
        return new MinecraftAccount(
            string(json, "username"), string(json, "uuid"), optional(json, "xuid"),
            string(json, "clientId"), string(json, "minecraftAccessToken"),
            Instant.parse(string(json, "minecraftAccessTokenExpiresAt")),
            string(json, "microsoftAccessToken"), Instant.parse(string(json, "microsoftAccessTokenExpiresAt")),
            string(json, "microsoftRefreshToken"), string(json, "microsoftScope"),
            Instant.parse(string(json, "authenticatedAt")), entitlements, skins, capes
        );
    }

    private static void setPrivatePermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows applies the current user's inherited ACL; POSIX hosts receive explicit owner-only modes.
        }
    }

    private static JsonObject object(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("Missing object " + key);
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("Missing array " + key);
        }
        return value.getAsJsonArray();
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing string " + key);
        }
        return value.getAsString();
    }

    private static String optional(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static int integer(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing integer " + key);
        }
        return value.getAsInt();
    }
}
