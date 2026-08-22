package dev.liquidcopy.client;

import dev.liquidcopy.api.module.Module;
import dev.liquidcopy.client.module.ChamsModule;
import dev.liquidcopy.client.module.CooldownSyncModule;
import dev.liquidcopy.client.module.CrosshairModule;
import dev.liquidcopy.client.module.FullBrightModule;
import dev.liquidcopy.client.module.HudModule;
import dev.liquidcopy.client.module.ItemEspModule;
import dev.liquidcopy.client.module.NametagsModule;
import dev.liquidcopy.client.module.NoJumpDelayModule;
import dev.liquidcopy.client.module.PlayerEspModule;
import dev.liquidcopy.client.module.SprintModule;
import dev.liquidcopy.client.module.StorageEspModule;
import dev.liquidcopy.client.module.TriggerbotModule;
import java.util.List;

final class BuiltInModules {
    private BuiltInModules() {
    }

    static List<Module> create() {
        return List.of(
            new PlayerEspModule(),
            new StorageEspModule(),
            new ItemEspModule(),
            new NametagsModule(),
            new SprintModule(),
            new TriggerbotModule(),
            new CooldownSyncModule(),
            new ChamsModule(),
            new NoJumpDelayModule(),
            new FullBrightModule(),
            new CrosshairModule(),
            new HudModule()
        );
    }
}
