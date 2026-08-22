package dev.liquidcopy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClientKernelPathTest {
    @Test
    void resolvesProfileUnderMinecraftGameDirectory() {
        Path gameDirectory = Path.of("build", "fake-minecraft").toAbsolutePath();

        assertEquals(
            gameDirectory.resolve("config").resolve("liquidcopy").resolve("profile.json").normalize(),
            ClientKernel.profilePath(gameDirectory)
        );
    }
}
