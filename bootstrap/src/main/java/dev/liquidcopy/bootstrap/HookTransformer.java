package dev.liquidcopy.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Fixed, auditable hooks for Mojang's official unobfuscated 1.21.11 client.
 *
 * <p>The class hashes deliberately make this transformer version-locked. A
 * changed class is rejected instead of being patched using a best-effort name
 * match.</p>
 */
final class HookTransformer implements ClassFileTransformer {
    static final String ENTRYPOINT = "dev/liquidcopy/client/ClientEntrypoint";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String GUI = "net/minecraft/client/gui/Gui";
    static final String ENTITY_RENDERER = "net/minecraft/client/renderer/entity/EntityRenderer";
    static final String BLOCK_ENTITY_DISPATCHER =
        "net/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher";

    private static final List<HookContract> CONTRACTS = List.of(
        new HookContract(
            MINECRAFT,
            "f6512fded6f01bb93ce8cea4c30a2496623fbf4f67cb34c03ec36a27ad749521",
            HookKind.MINECRAFT_TICK
        ),
        new HookContract(
            GUI,
            "11fd105f0479befc646fc5303db1d3813573e80857a9c767030bf3c452933666",
            HookKind.HUD_RENDER
        ),
        new HookContract(
            ENTITY_RENDERER,
            "d2e829a50881dd4029e4ca9939883a1bb4b833ae10207ebdf5517d71c2da07a0",
            HookKind.ENTITY_EXTRACT
        ),
        new HookContract(
            BLOCK_ENTITY_DISPATCHER,
            "db8db2505272d57ad52ed0bdfa7359c92707347064eb70febf971fde52e07f7b",
            HookKind.BLOCK_ENTITY_EXTRACT
        )
    );

    /** Verifies every hooked class before the agent is registered. */
    static void verifyOfficialClasses(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        for (HookContract contract : CONTRACTS) {
            String resourceName = contract.className() + ".class";
            try (InputStream input = loader.getResourceAsStream(resourceName)) {
                if (input == null) {
                    throw new HookContractException("Missing official class resource: " + resourceName);
                }
                contract.verify(input.readAllBytes());
            } catch (IOException failure) {
                throw new HookContractException("Could not read official class resource: " + resourceName, failure);
            }
        }
    }

    @Override
    public byte[] transform(
        Module module,
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer
    ) {
        HookContract contract = contractFor(className);
        if (contract == null) {
            return null;
        }

        try {
            return transformChecked(contract, classfileBuffer).bytecode();
        } catch (RuntimeException failure) {
            // Instrumentation treats a thrown transformer exception like an unchanged class.
            // Returning an invalid class instead guarantees that a missed required hook cannot
            // silently start an uninstrumented client.
            System.err.println("[LiquidCopy] Required 1.21.11 hook rejected " + className + ": " + failure);
            return poison(classfileBuffer);
        }
    }

    static TransformationResult transformChecked(String className, byte[] source) {
        HookContract contract = contractFor(className);
        if (contract == null) {
            throw new HookContractException("No hook contract for class: " + className);
        }
        return transformChecked(contract, source);
    }

    static String expectedSha256(String className) {
        HookContract contract = contractFor(className);
        if (contract == null) {
            throw new HookContractException("No hook contract for class: " + className);
        }
        return contract.sha256();
    }

    static String sha256(byte[] source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("The Java runtime does not provide SHA-256", impossible);
        }
    }

    private static TransformationResult transformChecked(HookContract contract, byte[] source) {
        Objects.requireNonNull(source, "source");
        contract.verify(source);

        ClassNode node = read(source);
        if (!contract.className().equals(node.name)) {
            throw new HookContractException(
                "Class identity mismatch: expected " + contract.className() + ", got " + node.name
            );
        }

        int injectionPoints = switch (contract.kind()) {
            case MINECRAFT_TICK -> injectMinecraftTick(node);
            case HUD_RENDER -> injectHudRender(node);
            case ENTITY_EXTRACT -> injectEntityExtract(node);
            case BLOCK_ENTITY_EXTRACT -> injectBlockEntityExtract(node);
        };
        return new TransformationResult(write(node), 1, injectionPoints);
    }

    private static int injectMinecraftTick(ClassNode node) {
        MethodNode method = findUnique(node, "tick", "()V");
        return injectBeforeEveryReturn(method, Opcodes.RETURN, () -> {
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, ENTRYPOINT, "onTick", "(Ljava/lang/Object;)V", false
            ));
            return hook;
        });
    }

    private static int injectHudRender(ClassNode node) {
        MethodNode method = findUnique(
            node,
            "render",
            "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"
        );
        // Gui.render has a deliberate early return while LevelLoadingScreen is active. Hook only
        // the terminal return so the client HUD never runs on that incomplete render path.
        return injectBeforeLastReturn(method, Opcodes.RETURN, () -> {
            InsnList hook = new InsnList();
            hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                MINECRAFT,
                "getInstance",
                "()Lnet/minecraft/client/Minecraft;",
                false
            ));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
            hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                ENTRYPOINT,
                "onHudRender",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                false
            ));
            return hook;
        });
    }

    private static int injectEntityExtract(ClassNode node) {
        MethodNode method = findUnique(
            node,
            "extractRenderState",
            "(Lnet/minecraft/world/entity/Entity;"
                + "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V"
        );
        return injectBeforeEveryReturn(method, Opcodes.RETURN, () -> {
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
            hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                ENTRYPOINT,
                "onEntityExtract",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                false
            ));
            return hook;
        });
    }

    private static int injectBlockEntityExtract(ClassNode node) {
        MethodNode method = findUnique(
            node,
            "tryExtractRenderState",
            "(Lnet/minecraft/world/level/block/entity/BlockEntity;F"
                + "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)"
                + "Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"
        );
        return injectBeforeEveryReturn(method, Opcodes.ARETURN, () -> {
            InsnList hook = new InsnList();
            // Preserve the state already on the operand stack for ARETURN while also passing it
            // to the callback: [state] -> [state, entity, state] -> [state].
            hook.add(new InsnNode(Opcodes.DUP));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new InsnNode(Opcodes.SWAP));
            hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                ENTRYPOINT,
                "onBlockEntityExtract",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                false
            ));
            return hook;
        });
    }

    private static ClassNode read(byte[] source) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(source).accept(node, 0);
            return node;
        } catch (RuntimeException failure) {
            throw new HookContractException("Invalid class bytes", failure);
        }
    }

    private static MethodNode findUnique(ClassNode node, String name, String descriptor) {
        List<MethodNode> matches = node.methods.stream()
            .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
            .toList();
        if (matches.size() != 1) {
            throw new HookContractException(
                "Expected exactly one hook method in " + node.name + ": " + name + descriptor
                    + "; found " + matches.size()
            );
        }
        return matches.getFirst();
    }

    private static int injectBeforeEveryReturn(
        MethodNode method,
        int returnOpcode,
        Supplier<InsnList> hookFactory
    ) {
        int injected = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == returnOpcode) {
                method.instructions.insertBefore(instruction, hookFactory.get());
                injected++;
            }
        }
        if (injected == 0) {
            throw new HookContractException(
                "Hook target has no expected return opcode " + returnOpcode + ": "
                    + method.name + method.desc
            );
        }
        return injected;
    }

    private static int injectBeforeLastReturn(
        MethodNode method,
        int returnOpcode,
        Supplier<InsnList> hookFactory
    ) {
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (int index = instructions.length - 1; index >= 0; index--) {
            if (instructions[index].getOpcode() == returnOpcode) {
                method.instructions.insertBefore(instructions[index], hookFactory.get());
                return 1;
            }
        }
        throw new HookContractException(
            "Hook target has no expected return opcode " + returnOpcode + ": "
                + method.name + method.desc
        );
    }

    private static byte[] write(ClassNode node) {
        try {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException failure) {
            throw new HookContractException("Could not write transformed class: " + node.name, failure);
        }
    }

    private static HookContract contractFor(String className) {
        if (className == null) {
            return null;
        }
        for (HookContract contract : CONTRACTS) {
            if (contract.className().equals(className)) {
                return contract;
            }
        }
        return null;
    }

    private static byte[] poison(byte[] source) {
        byte[] invalid = source == null ? new byte[4] : source.clone();
        if (invalid.length < 4) {
            return new byte[4];
        }
        invalid[0] = 0;
        invalid[1] = 0;
        invalid[2] = 0;
        invalid[3] = 0;
        return invalid;
    }

    private enum HookKind {
        MINECRAFT_TICK,
        HUD_RENDER,
        ENTITY_EXTRACT,
        BLOCK_ENTITY_EXTRACT
    }

    private record HookContract(String className, String sha256, HookKind kind) {
        private HookContract {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(kind, "kind");
        }

        private void verify(byte[] source) {
            Objects.requireNonNull(source, "source");
            String actual = HookTransformer.sha256(source);
            if (!sha256.equals(actual)) {
                throw new HookContractException(
                    "Official 1.21.11 class hash mismatch for " + className
                        + ": expected " + sha256 + ", got " + actual
                );
            }
        }
    }

    record TransformationResult(byte[] bytecode, int matchedMethods, int injectionPoints) {
        TransformationResult {
            bytecode = bytecode.clone();
        }

        @Override
        public byte[] bytecode() {
            return bytecode.clone();
        }
    }

    static final class HookContractException extends IllegalStateException {
        HookContractException(String message) {
            super(message);
        }

        HookContractException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
