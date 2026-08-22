package dev.liquidcopy.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallServiceTest {
    @TempDir
    Path minecraftDirectory;

    @Test
    void installsStandaloneMetadataAndVerifiesPayload() throws Exception {
        byte[] zip = profileZip();
        byte[] payload = "embedded-agent-payload".getBytes(StandardCharsets.UTF_8);
        String unrelatedLauncherProfiles = "{\"profiles\":{\"keep\":{\"name\":\"untouched\"}}}";
        Files.writeString(minecraftDirectory.resolve("launcher_profiles.json"), unrelatedLauncherProfiles);
        InstallService service = service(zip, payload, Hashing.sha1(zip));

        InstallService.InstallReport report = service.install(minecraftDirectory);

        assertTrue(Files.isRegularFile(report.baseProfile()));
        assertTrue(Files.isRegularFile(report.customProfile()));
        assertArrayEquals(payload, Files.readAllBytes(report.bootstrap()));
        assertEquals(InstallService.instanceDirectory(minecraftDirectory), report.instanceDirectory());
        assertTrue(Files.isDirectory(report.instanceDirectory()));
        assertEquals(unrelatedLauncherProfiles,
            Files.readString(minecraftDirectory.resolve("launcher_profiles.json")));

        InstallService.VerificationReport verification = service.verify(minecraftDirectory);
        assertTrue(verification.valid(), () -> String.join("; ", verification.messages()));
    }

    @Test
    void verificationDetectsBootstrapTampering() throws Exception {
        byte[] zip = profileZip();
        InstallService service = service(zip, "agent".getBytes(StandardCharsets.UTF_8), Hashing.sha1(zip));
        InstallService.InstallReport report = service.install(minecraftDirectory);
        Files.writeString(report.bootstrap(), "tampered");

        InstallService.VerificationReport verification = service.verify(minecraftDirectory);

        assertFalse(verification.valid());
        assertTrue(verification.messages().stream().anyMatch(message -> message.contains("SHA-1 mismatch")));
    }

    @Test
    void rejectsProfileZipWhosePinnedHashDoesNotMatch() throws Exception {
        byte[] zip = profileZip();
        InstallService service = service(zip, "agent".getBytes(StandardCharsets.UTF_8), "0000000000000000000000000000000000000000");

        IOException exception = assertThrows(IOException.class, () -> service.install(minecraftDirectory));

        assertTrue(exception.getMessage().contains("SHA-1 mismatch"));
        assertFalse(Files.exists(minecraftDirectory.resolve("versions")));
    }

    private static InstallService service(byte[] zip, byte[] payload, String expectedHash) {
        return new InstallService(
            uri -> zip,
            () -> payload,
            "0.1.0",
            URI.create("https://example.invalid/profile.zip"),
            expectedHash
        );
    }

    private static byte[] profileZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(ProfileComposer.BASE_PROFILE_DIRECTORY + "/"
                + ProfileComposer.BASE_PROFILE_DIRECTORY + ".json"));
            zip.write(TestProfiles.baseProfile().toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
