package dev.liquidcopy.api.module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

public final class ModuleRegistry {
    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final BiConsumer<Module, Throwable> failureHandler;

    public ModuleRegistry(BiConsumer<Module, Throwable> failureHandler) {
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    public void register(Module module) {
        Objects.requireNonNull(module, "module");
        if (modules.putIfAbsent(module.id(), module) != null) {
            throw new IllegalArgumentException("Duplicate module id: " + module.id());
        }
    }

    public void registerAll(Collection<? extends Module> newModules) {
        Objects.requireNonNull(newModules, "newModules").forEach(this::register);
    }

    public List<Module> all() {
        return modules.values().stream()
            .sorted(Comparator.comparing((Module module) -> module.metadata().category())
                .thenComparing(module -> module.metadata().name(), String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    public List<Module> enabled() {
        return all().stream().filter(Module::isEnabled).toList();
    }

    public List<Module> category(ModuleCategory category) {
        return all().stream().filter(module -> module.metadata().category() == category).toList();
    }

    public Optional<Module> find(String idOrName) {
        if (idOrName == null) {
            return Optional.empty();
        }
        String needle = idOrName.toLowerCase(Locale.ROOT);
        Module byId = modules.get(needle);
        if (byId != null) {
            return Optional.of(byId);
        }
        return modules.values().stream()
            .filter(module -> module.metadata().name().equalsIgnoreCase(idOrName))
            .findFirst();
    }

    public boolean setEnabled(String idOrName, boolean enabled, ModuleContext context) {
        Optional<Module> selected = find(idOrName);
        selected.ifPresent(module -> transition(module, enabled, context));
        return selected.isPresent();
    }

    public boolean toggle(String idOrName, ModuleContext context) {
        Optional<Module> selected = find(idOrName);
        selected.ifPresent(module -> transition(module, !module.isEnabled(), context));
        return selected.isPresent();
    }

    public void enableDefaults(ModuleContext context) {
        all().stream()
            .filter(module -> module.metadata().defaultEnabled())
            .forEach(module -> transition(module, true, context));
    }

    public void tick(ModuleContext context) {
        for (Module module : new ArrayList<>(enabled())) {
            try {
                module.tickSafely(context);
            } catch (RuntimeException | Error failure) {
                failureHandler.accept(module, failure);
            }
        }
    }

    public void disableAll(ModuleContext context) {
        for (Module module : new ArrayList<>(enabled())) {
            transition(module, false, context);
        }
    }

    private void transition(Module module, boolean target, ModuleContext context) {
        try {
            module.transition(target, Objects.requireNonNullElse(context, ModuleContext.EMPTY));
        } catch (RuntimeException | Error failure) {
            failureHandler.accept(module, failure);
        }
    }
}

