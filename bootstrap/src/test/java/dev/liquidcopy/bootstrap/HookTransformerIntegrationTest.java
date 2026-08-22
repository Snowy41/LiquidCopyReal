package dev.liquidcopy.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

/** Integration tests against the SHA-pinned official 1.21.11 client JAR. */
final class HookTransformerIntegrationTest {
    private static Path officialJarPath;
    private static JarFile officialJar;

    @BeforeAll
    static void openOfficialJar() throws IOException {
        String configured = System.getProperty("liquidcopy.officialClientJar");
        assertNotNull(configured, "bootstrap test task must configure liquidcopy.officialClientJar");
        officialJarPath = Path.of(configured).toAbsolutePath().normalize();
        officialJar = new JarFile(officialJarPath.toFile(), true);
    }

    @AfterAll
    static void closeOfficialJar() throws IOException {
        if (officialJar != null) {
            officialJar.close();
        }
    }

    static Stream<Arguments> hookedClasses() {
        return Stream.of(
            Arguments.of(HookTransformer.MINECRAFT, "onTick", 1),
            Arguments.of(HookTransformer.GUI, "onHudRender", 1),
            Arguments.of(HookTransformer.ENTITY_RENDERER, "onEntityExtract", 1),
            Arguments.of(HookTransformer.BLOCK_ENTITY_DISPATCHER, "onBlockEntityExtract", 4)
        );
    }

    @ParameterizedTest(name = "{0} -> {1} ({2} injection points)")
    @MethodSource("hookedClasses")
    void transformsPinnedOfficialClassAndPassesAsmVerification(
        String className,
        String callback,
        int expectedInjectionPoints
    ) throws IOException {
        byte[] officialBytes = officialClass(className);
        assertEquals(HookTransformer.expectedSha256(className), HookTransformer.sha256(officialBytes));

        HookTransformer.TransformationResult result =
            HookTransformer.transformChecked(className, officialBytes);

        assertEquals(1, result.matchedMethods(), "exact target method match count");
        assertEquals(expectedInjectionPoints, result.injectionPoints(), "return-site match count");
        assertEquals(
            expectedInjectionPoints,
            countCalls(result.bytecode(), HookTransformer.ENTRYPOINT, callback),
            "injected callback count"
        );
        assertAsmValid(result.bytecode());
    }

    @Test
    void verifiesAllPinnedClassesFromOfficialJarBeforeRegistration() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
            new java.net.URL[] {officialJarPath.toUri().toURL()},
            null
        )) {
            HookTransformer.verifyOfficialClasses(loader);
        }
    }

    @Test
    void hudHookUsesMinecraftSingletonAndSkipsLoadingScreenEarlyReturn() throws IOException {
        byte[] transformed = HookTransformer.transformChecked(
            HookTransformer.GUI,
            officialClass(HookTransformer.GUI)
        ).bytecode();

        MethodNode render = findMethod(
            transformed,
            "render",
            "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"
        );
        List<AbstractInsnNode> returns = Stream.of(render.instructions.toArray())
            .filter(instruction -> instruction.getOpcode() == Opcodes.RETURN)
            .toList();
        assertEquals(2, returns.size(), "official Gui.render control-flow contract");

        MethodInsnNode singletonCall = onlyCall(
            render,
            HookTransformer.MINECRAFT,
            "getInstance",
            "()Lnet/minecraft/client/Minecraft;"
        );
        MethodInsnNode hudCall = onlyCall(
            render,
            HookTransformer.ENTRYPOINT,
            "onHudRender",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
        );
        assertTrue(isBefore(singletonCall, hudCall), "Minecraft.getInstance must supply the callback receiver");
        assertTrue(isBefore(returns.getFirst(), singletonCall), "the loading-screen early return must remain unhooked");
        assertTrue(isBefore(hudCall, returns.getLast()), "the HUD callback must precede the terminal return");
    }

    @Test
    void rejectsChangedOfficialClassAndPoisonsInstrumentationResult() throws IOException {
        byte[] changed = officialClass(HookTransformer.MINECRAFT);
        changed[changed.length - 1] ^= 0x01;

        HookTransformer.HookContractException rejected = assertThrows(
            HookTransformer.HookContractException.class,
            () -> HookTransformer.transformChecked(HookTransformer.MINECRAFT, changed)
        );
        assertTrue(rejected.getMessage().contains("hash mismatch"));

        byte[] poisoned = new HookTransformer().transform(
            null,
            getClass().getClassLoader(),
            HookTransformer.MINECRAFT,
            null,
            null,
            changed
        );
        assertNotNull(poisoned);
        assertArrayEquals(new byte[] {0, 0, 0, 0}, java.util.Arrays.copyOf(poisoned, 4));
    }

    private static byte[] officialClass(String className) throws IOException {
        JarEntry entry = officialJar.getJarEntry(className + ".class");
        assertNotNull(entry, () -> "missing official class entry " + className);
        try (var input = officialJar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static int countCalls(byte[] bytecode, String owner, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(bytecode).accept(node, 0);
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static MethodNode findMethod(byte[] bytecode, String name, String descriptor) {
        ClassNode node = new ClassNode();
        new ClassReader(bytecode).accept(node, 0);
        return node.methods.stream()
            .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
            .findFirst()
            .orElseThrow();
    }

    private static MethodInsnNode onlyCall(
        MethodNode method,
        String owner,
        String name,
        String descriptor
    ) {
        List<MethodInsnNode> matches = Stream.of(method.instructions.toArray())
            .filter(MethodInsnNode.class::isInstance)
            .map(MethodInsnNode.class::cast)
            .filter(call -> owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc))
            .toList();
        assertEquals(1, matches.size(), () -> "expected one call to " + owner + '.' + name + descriptor);
        return matches.getFirst();
    }

    private static boolean isBefore(AbstractInsnNode first, AbstractInsnNode second) {
        for (AbstractInsnNode cursor = first; cursor != null; cursor = cursor.getNext()) {
            if (cursor == second) {
                return true;
            }
        }
        return false;
    }

    private static void assertAsmValid(byte[] bytecode) {
        // The visitor's data-flow mode runs ASM's Analyzer/BasicVerifier without loading the
        // transformed Minecraft class, so this validates stack and frame compatibility in CI.
        new ClassReader(bytecode).accept(
            new CheckClassAdapter(new ClassWriter(0), true),
            ClassReader.EXPAND_FRAMES
        );

    }
}
