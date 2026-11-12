package com.fastasyncworldedit.core.util;

/**
 * Detection helpers for Folia (regionised multithreading).
 * <p>
 * On Folia there is no single main thread. Every chunk and entity is owned by one region thread, and anything that touches
 * them has to run on that thread. Use the location-aware methods of {@link TaskManager} ({@code syncAt}, {@code syncWith},
 * {@code taskAt}) rather than {@link TaskManager#sync(java.util.function.Supplier)} for anything that touches the world.
 */
public final class FoliaSupport {

    private static final boolean IS_FOLIA;
    private static final Class<?> TICK_THREAD_CLASS;

    static {
        boolean isFolia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
        } catch (ClassNotFoundException | LinkageError ignored) {
        }
        IS_FOLIA = isFolia;
        Class<?> tickThreadClass = null;
        if (isFolia) {
            try {
                tickThreadClass = Class.forName("ca.spottedleaf.moonrise.common.util.TickThread");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Unsupported Folia version: cannot find the tick thread class", e);
            }
        }
        TICK_THREAD_CLASS = tickThreadClass;
    }

    private FoliaSupport() {
    }

    /**
     * {@return whether the server is Folia}
     */
    public static boolean isFolia() {
        return IS_FOLIA;
    }

    /**
     * {@return whether the current thread is a Folia tick thread (a region thread or the global region thread)}
     * Always false when not running on Folia.
     */
    public static boolean isTickThread() {
        return IS_FOLIA && TICK_THREAD_CLASS.isInstance(Thread.currentThread());
    }

}
