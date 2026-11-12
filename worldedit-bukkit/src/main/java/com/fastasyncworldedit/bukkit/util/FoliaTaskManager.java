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
import java.util.concurrent.CancellationException;
import org.bukkit.Location;

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
    private static final long TICK_THREAD_STALL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final Plugin plugin;
    private final AtomicInteger taskIds = new AtomicInteger();
    private final ConcurrentHashMap<Integer, ScheduledTask> repeatingTasks = new ConcurrentHashMap<>();
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
        final boolean global = isGlobalTickThread();
        long lastProgress = System.nanoTime();
        while (!future.isDone()) {
            boolean ran = runPendingOwnedTasks();
            if (global) {
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
                task.run();
                ran = true;
            }
        }
        return ran;
    }

    public void cancelPendingRegionTasks() {
        for (RegionTask<?> task : this.pendingRegionTasks) {
            task.cancel(false);
        }
    }

    public <T> T callAtChunk(final World world, final int chunkX, final int chunkZ, final Supplier<T> supplier) {
        if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            return supplier.get();
        }
        return join(submitAtChunk(world, chunkX, chunkZ, supplier::get));
    }

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

    public <T> T callWithEntity(final Entity entity, final Supplier<T> supplier) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            return supplier.get();
        }
        final Location location = entity.getLocation();
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
        final boolean scheduled = entity.getScheduler().execute(this.plugin, task, () -> task.cancel(false), 1L);
        if (!scheduled) {
            throw new IllegalStateException("Entity " + entity.getUniqueId() + " was removed before the task could run");
        }
        try {
            return join(task);
        } catch (CancellationException e) {
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

    public static FoliaTaskManager get() {
        return (FoliaTaskManager) TaskManager.taskManager();
    }

    private final class RegionTask<T> extends FutureTask<T> implements Comparable<RegionTask<?>> {

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
