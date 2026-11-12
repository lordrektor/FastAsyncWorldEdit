package com.fastasyncworldedit.bukkit.util;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.util.FoliaSupport;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * {@link TaskManager} for Folia.
 * <p>
 * There is no main thread on Folia. "Sync" tasks without a location run on the global region thread, which must not touch
 * chunks or entities; everything that does has to use the location-aware methods ({@link #syncAt}, {@link #submitAt},
 * {@link #taskAt}, {@link #syncWith}), which run on the region thread owning the chunk or entity.
 */
public class FoliaTaskManager extends TaskManager {

    private static final MethodHandle IS_GLOBAL_TICK_THREAD;

    static {
        MethodHandle handle = null;
        try {
            handle = MethodHandles.lookup().findStatic(Bukkit.class, "isGlobalTickThread", MethodType.methodType(boolean.class));
        } catch (ReflectiveOperationException ignored) {
        }
        IS_GLOBAL_TICK_THREAD = handle;
    }

    private static final Object NOT_OWNED = new Object();
    /**
     * How long a tick thread may wait for work that only another region can do, before the wait is given up. A region
     * created next to the waiting one is only started once the waiting thread finishes its tick, which it never would.
     */
    private static final long TICK_THREAD_STALL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final Plugin plugin;
    private final AtomicInteger taskIds = new AtomicInteger();
    private final ConcurrentHashMap<Integer, ScheduledTask> repeatingTasks = new ConcurrentHashMap<>();
    /**
     * Region tasks that were scheduled but have not run yet. A tick thread that has to wait for a future (an edit flushed
     * from a region thread) runs the ones it owns itself, as the region scheduler cannot run them while the thread waits.
     */
    private final Set<RegionTask<?>> pendingRegionTasks = new ConcurrentSkipListSet<>();
    private final AtomicLong regionTaskIds = new AtomicLong();

    public FoliaTaskManager(final Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int repeat(@Nonnull final Runnable runnable, final int interval) {
        final long period = Math.max(1, interval);
        return track(Bukkit.getGlobalRegionScheduler().runAtFixedRate(this.plugin, task -> runnable.run(), period, period));
    }

    @Override
    public int repeatAsync(@Nonnull final Runnable runnable, final int interval) {
        final long period = Math.max(1, interval) * 50L;
        return track(Bukkit.getAsyncScheduler().runAtFixedRate(
                this.plugin,
                task -> runnable.run(),
                period,
                period,
                TimeUnit.MILLISECONDS
        ));
    }

    private int track(ScheduledTask task) {
        final int id = this.taskIds.incrementAndGet();
        this.repeatingTasks.put(id, task);
        return id;
    }

    @Override
    public void async(@Nonnull final Runnable runnable) {
        Bukkit.getAsyncScheduler().runNow(this.plugin, task -> runnable.run());
    }

    @Override
    public void task(@Nonnull final Runnable runnable) {
        Bukkit.getGlobalRegionScheduler().execute(this.plugin, runnable);
    }

    /**
     * Run a task on the global region thread, inline if this is the global region thread.
     */
    public void runGlobal(final Runnable runnable) {
        if (isGlobalTickThread()) {
            runnable.run();
        } else {
            task(runnable);
        }
    }

    @Override
    public void later(@Nonnull final Runnable runnable, final int delay) {
        if (delay <= 0) {
            task(runnable);
            return;
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(this.plugin, task -> runnable.run(), delay);
    }

    @Override
    public void laterAsync(@Nonnull final Runnable runnable, final int delay) {
        if (delay <= 0) {
            async(runnable);
            return;
        }
        Bukkit.getAsyncScheduler().runDelayed(this.plugin, task -> runnable.run(), delay * 50L, TimeUnit.MILLISECONDS);
    }

    /**
     * There is no main thread to move a task to, and the global region thread may not touch chunks or entities. Code
     * that touches the world hops to the owning thread itself, so the task simply runs on the calling thread.
     */
    @Override
    public <T> T sync(final Supplier<T> function) {
        return function.get();
    }

    @Override
    public void cancel(final int task) {
        final ScheduledTask scheduled = this.repeatingTasks.remove(task);
        if (scheduled != null) {
            scheduled.cancel();
        }
    }

    @Override
    public <T> T syncAt(
            @Nonnull final Supplier<T> supplier,
            @Nonnull final com.sk89q.worldedit.world.World world,
            final int chunkX,
            final int chunkZ
    ) {
        return callAtChunk(BukkitAdapter.adapt(world), chunkX, chunkZ, supplier);
    }

    @Override
    public void taskAt(
            @Nonnull final Runnable runnable,
            @Nonnull final com.sk89q.worldedit.world.World world,
            final int chunkX,
            final int chunkZ
    ) {
        Bukkit.getRegionScheduler().execute(this.plugin, BukkitAdapter.adapt(world), chunkX, chunkZ, runnable);
    }

    @Override
    public <T> Future<T> submitAt(
            @Nonnull final Callable<T> callable,
            @Nonnull final com.sk89q.worldedit.world.World world,
            final int chunkX,
            final int chunkZ
    ) {
        return submitAtChunk(BukkitAdapter.adapt(world), chunkX, chunkZ, callable);
    }

    @Override
    public <T> T syncWith(@Nonnull final Supplier<T> supplier, @Nonnull final Player player) {
        final org.bukkit.entity.Player bukkitPlayer = BukkitAdapter.adapt(player);
        if (bukkitPlayer == null) {
            throw new IllegalStateException("Player " + player.getName() + " is not a Bukkit player");
        }
        return callWithEntity(bukkitPlayer, supplier);
    }

    @Override
    public boolean isOwnedByCurrentThread(@Nonnull final com.sk89q.worldedit.world.World world, final int chunkX, final int chunkZ) {
        return Bukkit.isOwnedByCurrentRegion(BukkitAdapter.adapt(world), chunkX, chunkZ);
    }

    @Override
    public <T> T await(@Nonnull final Future<T> future) throws InterruptedException, ExecutionException {
        if (!FoliaSupport.isTickThread()) {
            return future.get();
        }
        // We are blocking a tick thread. Tasks aimed at this thread's own region would never run (and whoever waits
        // for them would never finish), so run them here whilst waiting.
        final boolean global = isGlobalTickThread();
        long lastProgress = System.nanoTime();
        while (!future.isDone()) {
            boolean ran = runPendingOwnedTasks();
            if (global) {
                // tasks submitted through TaskManager#sync are drained by the queue handler on the global region thread
                Fawe.instance().getQueueHandler().run();
            }
            if (ran) {
                lastProgress = System.nanoTime();
            } else {
                if (!this.plugin.isEnabled() || System.nanoTime() - lastProgress > TICK_THREAD_STALL_NANOS) {
                    throw new IllegalStateException("A tick thread waited for work in a region it does not own and that "
                            + "work did not finish. Edits reaching outside the current region have to be started from an "
                            + "asynchronous thread on Folia.");
                }
                try {
                    return future.get(1, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                }
            }
        }
        return future.get();
    }

    private boolean runPendingOwnedTasks() {
        boolean ran = false;
        for (RegionTask<?> task : this.pendingRegionTasks) {
            if (!task.isDone() && Bukkit.isOwnedByCurrentRegion(task.world, task.chunkX, task.chunkZ)) {
                // FutureTask only ever runs once, the copy handed to the region scheduler becomes a no-op
                task.run();
                ran = true;
            }
        }
        return ran;
    }

    /**
     * Cancel the region tasks that have not run, so that threads waiting for them wake up. Used when the plugin is
     * disabled: the region threads do not run tasks any more at that point.
     */
    public void cancelPendingRegionTasks() {
        for (RegionTask<?> task : this.pendingRegionTasks) {
            task.cancel(false);
        }
    }

    /**
     * Run a task on the region thread owning the given chunk and wait for the result. Runs inline if the current thread
     * already owns the chunk.
     */
    public <T> T callAtChunk(final World world, final int chunkX, final int chunkZ, final Supplier<T> supplier) {
        if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            return supplier.get();
        }
        return join(submitAtChunk(world, chunkX, chunkZ, supplier::get));
    }

    /**
     * Submit a task to the region thread owning the given chunk. Runs inline if the current thread already owns the chunk.
     */
    public <T> Future<T> submitAtChunk(final World world, final int chunkX, final int chunkZ, final Callable<T> callable) {
        final RegionTask<T> task = new RegionTask<>(callable, world, chunkX, chunkZ);
        if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            task.run();
            return task;
        }
        this.pendingRegionTasks.add(task);
        try {
            Bukkit.getRegionScheduler().execute(this.plugin, world, chunkX, chunkZ, task);
        } catch (RuntimeException | Error e) {
            this.pendingRegionTasks.remove(task);
            throw e;
        }
        return task;
    }

    /**
     * Run a task on the thread owning the given entity and wait for the result. Runs inline if the current thread
     * already owns the entity.
     *
     * @throws IllegalStateException if the entity was removed before the task could run
     */
    public <T> T callWithEntity(final Entity entity, final Supplier<T> supplier) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            return supplier.get();
        }
        // The region scheduler runs tasks between ticks, the entity scheduler only once per tick. Go to the region the
        // entity was last seen in first, and only use the entity scheduler if it has left that region meanwhile.
        final org.bukkit.Location location = entity.getLocation();
        if (location.getWorld() != null) {
            final Object result = callAtChunk(
                    location.getWorld(),
                    location.getBlockX() >> 4,
                    location.getBlockZ() >> 4,
                    () -> Bukkit.isOwnedByCurrentRegion(entity) ? supplier.get() : NOT_OWNED
            );
            if (result != NOT_OWNED) {
                @SuppressWarnings("unchecked") final T value = (T) result;
                return value;
            }
        }
        final FutureTask<T> task = new FutureTask<>(supplier::get);
        // the "retired" callback runs if the entity is removed first: cancel so the waiting side wakes up
        final boolean scheduled = entity.getScheduler().execute(this.plugin, task, () -> task.cancel(false), 1L);
        if (!scheduled) {
            throw new IllegalStateException("Entity " + entity.getUniqueId() + " was removed before the task could run");
        }
        try {
            return join(task);
        } catch (java.util.concurrent.CancellationException e) {
            throw new IllegalStateException("Entity " + entity.getUniqueId() + " was removed before the task could run", e);
        }
    }

    private <T> T join(final Future<T> future) {
        try {
            return await(future);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    public static boolean isGlobalTickThread() {
        if (IS_GLOBAL_TICK_THREAD == null) {
            return false;
        }
        try {
            return (boolean) IS_GLOBAL_TICK_THREAD.invokeExact();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Get the Folia task manager. Only call on Folia.
     */
    public static FoliaTaskManager get() {
        return (FoliaTaskManager) TaskManager.taskManager();
    }

    private final class RegionTask<T> extends FutureTask<T> implements Comparable<RegionTask<?>> {

        // tasks picked up by a waiting tick thread have to run in the order they were submitted in
        private final long id = FoliaTaskManager.this.regionTaskIds.incrementAndGet();
        private final World world;
        private final int chunkX;
        private final int chunkZ;

        private RegionTask(final Callable<T> callable, final World world, final int chunkX, final int chunkZ) {
            super(callable);
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override
        protected void done() {
            FoliaTaskManager.this.pendingRegionTasks.remove(this);
        }

        @Override
        public int compareTo(final RegionTask<?> other) {
            return Long.compare(this.id, other.id);
        }

    }

}
