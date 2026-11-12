package com.fastasyncworldedit.core.util;

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

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    public static boolean isTickThread() {
        return IS_FOLIA && TICK_THREAD_CLASS.isInstance(Thread.currentThread());
    }

}
