package dev.liquidcopy.client;

/** Called by the bootstrap's injected 1.21.11 client-tick hook. */
public final class ClientEntrypoint {
    private static volatile ClientKernel kernel;

    private ClientEntrypoint() {
    }

    public static void onTick(Object minecraft) {
        ClientKernel current = kernel;
        if (current == null) {
            synchronized (ClientEntrypoint.class) {
                current = kernel;
                if (current == null) {
                    current = ClientKernel.bootstrap(minecraft);
                    kernel = current;
                }
            }
        }
        current.tick(minecraft);
    }

    public static void onHudRender(Object minecraft, Object graphics, Object deltaTracker) {
        ClientKernel current = kernel;
        if (current != null) {
            current.renderHud(minecraft, graphics, deltaTracker);
        }
    }

    /** Called by the optional block-entity extraction hook in the named 1.21.11 client. */
    public static void onBlockEntityExtract(Object blockEntity, Object renderState) {
        ClientKernel current = kernel;
        if (current != null) {
            current.blockEntityExtracted(blockEntity, renderState);
        }
    }

    /** Optional entity-state hook; direct state coloring avoids changing synchronized entity data. */
    public static void onEntityExtract(Object entity, Object renderState) {
        ClientKernel current = kernel;
        if (current != null) {
            current.entityExtracted(entity, renderState);
        }
    }
}
