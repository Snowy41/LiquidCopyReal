package dev.liquidcopy.api.module;

import java.util.Objects;

public record ModuleMetadata(
    String id,
    String name,
    String description,
    ModuleCategory category,
    boolean defaultEnabled
) {
    public ModuleMetadata {
        if (id == null || !id.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid module id: " + id);
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(category, "category");
    }
}

