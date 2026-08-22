package dev.liquidcopy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

class OfficialApiContractTest {
    @Test
    void noJumpDelayUsesTheOfficialLivingEntityField() throws Exception {
        ClassNode livingEntity = officialClass("net/minecraft/world/entity/LivingEntity");
        var fields = livingEntity.fields.stream()
            .filter(field -> field.name.equals("noJumpDelay") && field.desc.equals("I"))
            .toList();

        assertEquals(1, fields.size());
        assertEquals(0, fields.getFirst().access & Opcodes.ACC_STATIC);
        assertTrue((fields.getFirst().access & Opcodes.ACC_PRIVATE) != 0);
    }

    @Test
    void triggerbotDelegatesToTheVanillaAttackRoutine() throws Exception {
        ClassNode minecraft = officialClass("net/minecraft/client/Minecraft");
        var methods = minecraft.methods.stream()
            .filter(method -> method.name.equals("startAttack") && method.desc.equals("()Z"))
            .toList();

        assertEquals(1, methods.size());
        assertEquals(0, methods.getFirst().access & Opcodes.ACC_STATIC);
        assertTrue((methods.getFirst().access & Opcodes.ACC_PRIVATE) != 0);
    }

    private static ClassNode officialClass(String internalName) throws Exception {
        String configured = System.getProperty("liquidcopy.officialClientJar");
        assertNotNull(configured, "client test task must configure liquidcopy.officialClientJar");
        try (JarFile jar = new JarFile(Path.of(configured).toFile())) {
            var entry = jar.getJarEntry(internalName + ".class");
            assertNotNull(entry, "Missing official class " + internalName);
            try (var input = jar.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                return node;
            }
        }
    }
}
