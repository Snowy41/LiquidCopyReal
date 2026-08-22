package dev.liquidcopy.client;

import dev.liquidcopy.api.event.EventBus;
import dev.liquidcopy.api.event.TickEvent;
import dev.liquidcopy.api.module.Module;
import dev.liquidcopy.api.module.ModuleContext;
import dev.liquidcopy.api.module.ModuleProvider;
import dev.liquidcopy.api.module.ModuleRegistry;
import dev.liquidcopy.core.config.ConfigStore;
import dev.liquidcopy.client.service.GlowController;
import dev.liquidcopy.client.service.StorageTracker;
import dev.liquidcopy.client.render.EntityOutlineProvider;
import dev.liquidcopy.client.render.EntityRenderStateMutator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

public final class ClientKernel {
    private final AtomicLong ticks = new AtomicLong();
    private final EventBus events;
    private final ModuleRegistry modules;
    private final GlowController glowController;
    private final StorageTracker storageTracker;
    private final Minecraft minecraft;
    private final ConfigStore configStore;
    private final AtomicBoolean shutdown = new AtomicBoolean();

    private ClientKernel(
        EventBus events,
        ModuleRegistry modules,
        GlowController glowController,
        StorageTracker storageTracker,
        Minecraft minecraft,
        ConfigStore configStore
    ) {
        this.events = events;
        this.modules = modules;
        this.glowController = glowController;
        this.storageTracker = storageTracker;
        this.minecraft = minecraft;
        this.configStore = configStore;
    }

    public static ClientKernel bootstrap(Object minecraft) {
        EventBus events = new EventBus();
        ModuleRegistry modules = new ModuleRegistry((module, failure) ->
            System.err.println("[LiquidCopy] Disabled " + module.id() + ": " + failure)
        );
        BuiltInModules.create().forEach(modules::register);
        ServiceLoader.load(ModuleProvider.class).forEach(provider -> modules.registerAll(provider.createModules()));
        Minecraft resolvedMinecraft = resolveMinecraft(minecraft);
        ConfigStore configStore = new ConfigStore(profilePath(resolvedMinecraft.gameDirectory.toPath()));
        ClientKernel kernel = new ClientKernel(
            events,
            modules,
            new GlowController(),
            new StorageTracker(),
            resolvedMinecraft,
            configStore
        );
        kernel.loadConfiguration();
        kernel.installShutdownHook();
        System.out.println("[LiquidCopy] 1.21.11 standalone client initialized with "
            + modules.all().size() + " modules; profile=" + configStore.path());
        return kernel;
    }

    public void tick(Object minecraft) {
        Object resolvedMinecraft = resolveMinecraft(minecraft);
        long tick = ticks.incrementAndGet();
        RuntimeContext context = new RuntimeContext(tick, resolvedMinecraft, this);
        events.post(new TickEvent(tick));
        modules.tick(context);
        InputController.poll(context);
        storageTracker.prune(tick, 80L);
    }

    public void renderHud(Object minecraft, Object graphics, Object deltaTracker) {
        HudController.render(
            new RuntimeContext(ticks.get(), resolveMinecraft(minecraft), this),
            graphics,
            deltaTracker
        );
    }

    public void blockEntityExtracted(Object blockEntity, Object renderState) {
        storageTracker.observe(blockEntity, ticks.get());
    }

    public void entityExtracted(Object entity, Object renderState) {
        if (!(entity instanceof Entity target) || !(renderState instanceof EntityRenderState state)) {
            return;
        }
        java.util.OptionalInt selectedColor = java.util.OptionalInt.empty();
        for (Module module : modules.enabled()) {
            if (module instanceof EntityRenderStateMutator mutator) {
                mutator.mutateRenderState(target, state);
            }
            if (module instanceof EntityOutlineProvider provider) {
                java.util.OptionalInt color = provider.outlineColor(target);
                if (color.isPresent()) {
                    selectedColor = color;
                }
            }
        }
        selectedColor.ifPresent(color -> state.outlineColor = color);
    }

    public ModuleRegistry modules() {
        return modules;
    }

    public GlowController glowController() {
        return glowController;
    }

    public StorageTracker storageTracker() {
        return storageTracker;
    }

    public Path configurationPath() {
        return configStore.path();
    }

    public RuntimeContext context(Object minecraft) {
        return new RuntimeContext(ticks.get(), resolveMinecraft(minecraft), this);
    }

    public boolean toggleModule(String idOrName, ModuleContext context) {
        boolean found = modules.toggle(idOrName, context);
        if (found) {
            saveConfiguration();
        }
        return found;
    }

    public boolean setModuleEnabled(String idOrName, boolean enabled, ModuleContext context) {
        boolean found = modules.setEnabled(idOrName, enabled, context);
        if (found) {
            saveConfiguration();
        }
        return found;
    }

    public synchronized boolean saveConfiguration() {
        try {
            configStore.save(modules);
            return true;
        } catch (IOException | RuntimeException failure) {
            System.err.println("[LiquidCopy] Could not save profile " + configStore.path() + ": " + failure);
            return false;
        }
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        saveConfiguration();
        RuntimeContext context = new RuntimeContext(ticks.get(), minecraft, this);
        modules.disableAll(context);
        glowController.clearAll();
    }

    static Path profilePath(Path gameDirectory) {
        return gameDirectory.toAbsolutePath().normalize()
            .resolve("config")
            .resolve("liquidcopy")
            .resolve("profile.json");
    }

    private void loadConfiguration() {
        RuntimeContext context = new RuntimeContext(0L, minecraft, this);
        try {
            ConfigStore.LoadResult result = configStore.load(modules, context);
            System.out.println("[LiquidCopy] Profile "
                + (result.existingProfile() ? "loaded" : "created")
                + ": " + result.modulesLoaded() + " modules, "
                + result.settingsLoaded() + " settings");
        } catch (IOException | RuntimeException failure) {
            System.err.println("[LiquidCopy] Could not load profile " + configStore.path()
                + "; using defaults: " + failure);
            modules.disableAll(context);
            modules.all().forEach(module -> module.settings().forEach(setting -> setting.reset()));
            modules.enableDefaults(context);
        }
    }

    private void installShutdownHook() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "LiquidCopy-config-save"));
        } catch (IllegalStateException | SecurityException failure) {
            System.err.println("[LiquidCopy] Could not install shutdown hook: " + failure);
        }
    }

    private static Minecraft resolveMinecraft(Object minecraft) {
        return minecraft instanceof Minecraft instance ? instance : Minecraft.getInstance();
    }

    public record RuntimeContext(long tick, Object minecraft, ClientKernel kernel) implements ModuleContext {
        @Override
        public <T> java.util.Optional<T> service(Class<T> serviceType) {
            if (serviceType.isInstance(minecraft)) {
                return java.util.Optional.of(serviceType.cast(minecraft));
            }
            if (serviceType.isInstance(kernel)) {
                return java.util.Optional.of(serviceType.cast(kernel));
            }
            if (serviceType.isInstance(kernel.glowController)) {
                return java.util.Optional.of(serviceType.cast(kernel.glowController));
            }
            if (serviceType.isInstance(kernel.storageTracker)) {
                return java.util.Optional.of(serviceType.cast(kernel.storageTracker));
            }
            return java.util.Optional.empty();
        }
    }
}
