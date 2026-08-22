package dev.liquidcopy.api.module;

import java.util.Optional;

public interface ModuleContext {
    ModuleContext EMPTY = new ModuleContext() {
        @Override
        public long tick() {
            return 0L;
        }

        @Override
        public <T> Optional<T> service(Class<T> serviceType) {
            return Optional.empty();
        }
    };

    long tick();

    <T> Optional<T> service(Class<T> serviceType);

    default <T> T require(Class<T> serviceType) {
        return service(serviceType).orElseThrow(
            () -> new IllegalStateException("Missing module service: " + serviceType.getName())
        );
    }
}

