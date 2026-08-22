package dev.liquidcopy.api.module;

import dev.liquidcopy.api.setting.KeybindSetting;
import dev.liquidcopy.api.setting.Setting;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public abstract class Module {
    private final ModuleMetadata metadata;
    private final Map<String, Setting<?>> settings = new LinkedHashMap<>();
    private final KeybindSetting keybind;
    private boolean enabled;
    private Throwable lastFailure;

    protected Module(ModuleMetadata metadata, int defaultKeyCode) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.keybind = setting(new KeybindSetting(defaultKeyCode));
    }

    public final ModuleMetadata metadata() {
        return metadata;
    }

    public final String id() {
        return metadata.id();
    }

    public final boolean isEnabled() {
        return enabled;
    }

    public final KeybindSetting keybind() {
        return keybind;
    }

    public final List<Setting<?>> settings() {
        return List.copyOf(settings.values());
    }

    public final Optional<Setting<?>> setting(String key) {
        return Optional.ofNullable(settings.get(key));
    }

    public final Optional<Throwable> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    protected final <S extends Setting<?>> S setting(S setting) {
        Objects.requireNonNull(setting, "setting");
        if (settings.putIfAbsent(setting.key(), setting) != null) {
            throw new IllegalArgumentException("Duplicate setting " + setting.key() + " in " + id());
        }
        return setting;
    }

    final void transition(boolean target, ModuleContext context) {
        if (enabled == target) {
            return;
        }
        try {
            if (target) {
                onEnable(context);
            } else {
                onDisable(context);
            }
            enabled = target;
            lastFailure = null;
        } catch (RuntimeException | Error failure) {
            lastFailure = failure;
            enabled = false;
            throw failure;
        }
    }

    final void tickSafely(ModuleContext context) {
        try {
            onTick(context);
            lastFailure = null;
        } catch (RuntimeException | Error failure) {
            lastFailure = failure;
            enabled = false;
            try {
                onDisable(context);
            } catch (RuntimeException | Error suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        }
    }

    protected void onEnable(ModuleContext context) {
    }

    protected void onDisable(ModuleContext context) {
    }

    protected void onTick(ModuleContext context) {
    }
}

