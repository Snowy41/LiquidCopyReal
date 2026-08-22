package dev.liquidcopy.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

final class TestProfiles {
    private TestProfiles() {
    }

    static JsonObject baseProfile() {
        JsonObject client = new JsonObject();
        client.addProperty("sha1", InstallService.OFFICIAL_NAMED_CLIENT_SHA1);
        client.addProperty("size", 36_734_011);
        client.addProperty("url", "https://example.invalid/client.jar");
        JsonObject downloads = new JsonObject();
        downloads.add("client", client);

        JsonArray jvm = new JsonArray();
        jvm.add("-cp");
        jvm.add("${classpath}");
        JsonObject arguments = new JsonObject();
        arguments.add("jvm", jvm);
        arguments.add("game", new JsonArray());

        JsonObject base = new JsonObject();
        base.addProperty("id", ProfileComposer.BASE_PROFILE_ID);
        base.addProperty("type", "unobfuscated");
        base.addProperty("mainClass", "net.minecraft.client.main.Main");
        base.add("downloads", downloads);
        base.add("libraries", new JsonArray());
        base.add("arguments", arguments);
        return base;
    }
}
