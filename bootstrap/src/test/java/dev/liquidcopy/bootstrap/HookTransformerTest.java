package dev.liquidcopy.bootstrap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

final class HookTransformerTest {
    @Test
    void transformerIgnoresUnrelatedClasses() throws Exception {
        HookTransformer transformer = new HookTransformer();
        try (InputStream input = getClass().getResourceAsStream("/dev/liquidcopy/bootstrap/HookTransformer.class")) {
            assertNotNull(input);
            byte[] transformed = transformer.transform(
                null, getClass().getClassLoader(), "example/Unrelated", null, null, input.readAllBytes()
            );
            assertNull(transformed);
        }
    }
}

