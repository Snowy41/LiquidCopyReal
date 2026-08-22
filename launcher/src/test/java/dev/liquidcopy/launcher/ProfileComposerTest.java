package dev.liquidcopy.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileComposerTest {
    @Test
    void composesStandaloneProfileWithoutMutatingMojangInput() {
        ProfileComposer composer = new ProfileComposer("0.1.0");
        JsonObject base = TestProfiles.baseProfile();
        ProfileComposer.BootstrapArtifact artifact = composer.bootstrapArtifact("agent".getBytes());

        JsonObject custom = composer.compose(base, artifact);

        assertEquals(ProfileComposer.BASE_PROFILE_ID, base.get("id").getAsString());
        assertEquals(ProfileComposer.CUSTOM_VERSION_ID, custom.get("id").getAsString());
        assertEquals("net.minecraft.client.main.Main", custom.get("mainClass").getAsString());
        assertEquals("dev.liquidcopy:liquidcopy-bootstrap:0.1.0",
            custom.getAsJsonArray("libraries").get(0).getAsJsonObject().get("name").getAsString());

        JsonArray jvm = custom.getAsJsonObject("arguments").getAsJsonArray("jvm");
        assertEquals(composer.javaAgentArgument(), jvm.get(0).getAsString());
        assertTrue(composer.javaAgentArgument().startsWith("-javaagent:${library_directory}/"));
        assertFalse(base.getAsJsonObject("arguments").getAsJsonArray("jvm").contains(jvm.get(0)));
    }
}
