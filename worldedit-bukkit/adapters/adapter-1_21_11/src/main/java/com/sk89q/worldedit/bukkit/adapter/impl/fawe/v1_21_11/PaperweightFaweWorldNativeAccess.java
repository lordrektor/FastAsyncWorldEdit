package com.sk89q.worldedit.bukkit.adapter.impl.fawe.v1_21_11;

import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.Collection;
import java.util.ArrayList;
import org.bukkit.Bukkit;
import com.fastasyncworldedit.bukkit.util.FoliaTaskManager;
import com.fastasyncworldedit.core.util.FoliaSupport;
import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.math.IntPair;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.core.util.task.RunnableVal;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.internal.block.BlockStateIdAccess;
import com.sk89q.worldedit.internal.wna.WorldNativeAccess;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.storage.ValueInput;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.enginehub.linbus.tree.LinCompoundTag;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sk89q.worldedit.bukkit.adapter.impl.fawe.v1_21_11.PaperweightPlatformAdapter.createInput;

public class PaperweightFaweWorldNativeAccess implements WorldNativeAccess<LevelChunk,
        net.minecraft.world.level.block.state.BlockState, BlockPos> {

    private static final int UPDATE = 1;
    private static final int NOTIFY = 2;
    private static final Direction[] NEIGHBOUR_ORDER = {
            Direction.EAST,
            Direction.WEST,
            Direction.DOWN,
            Direction.UP,
            Direction.NORTH,
            Direction.SOUTH
    };
    private final PaperweightFaweAdapter paperweightFaweAdapter;
    private final WeakReference<Level> level;
    private final AtomicInteger lastTick;
    private final Set<CachedChange> cachedChanges = new HashSet<>();
    private final Set<IntPair> cachedChunksToSend = new HashSet<>();
    private SideEffectSet sideEffectSet;

    public PaperweightFaweWorldNativeAccess(PaperweightFaweAdapter paperweightFaweAdapter, WeakReference<Level> level) {
        this.paperweightFaweAdapter = paperweightFaweAdapter;
        this.level = level;
        // Use the actual tick as minecraft-defined so we don't try to force blocks into the world when the server's already lagging.
        //  - With the caveat that we don't want to have too many cached changed (1024) so we'd flush those at 1024 anyway.
        this.lastTick = new AtomicInteger(currentTick());
    }

    //FAWE start - Folia
    /**
     * Folia has no global tick counter (the field does not exist there), wall-clock ticks are close enough to decide
     * when to flush cached changes.
     */
    private static int currentTick() {
        if (FoliaSupport.isFolia()) {
            return (int) (System.nanoTime() / 50_000_000L);
        }
        return MinecraftServer.currentTick;
    }

    private boolean ownsChunk(int chunkX, int chunkZ) {
        return Bukkit.isOwnedByCurrentRegion(getLevel().getWorld(), chunkX, chunkZ);
    }

    /**
     * Apply cached changes on Folia: every chunk is changed and sent by the region thread owning it.
     */
    private void applyFolia(Collection<CachedChange> changes, Collection<IntPair> toSend, boolean wait) {
        if (changes.isEmpty() && toSend.isEmpty()) {
            return;
        }
        final CraftWorld world = getLevel().getWorld();
        final boolean update = sideEffectSet != null && sideEffectSet.shouldApply(SideEffect.UPDATE);
        final Map<IntPair, List<CachedChange>> byChunk = new HashMap<>();
        for (CachedChange change : changes) {
            byChunk.computeIfAbsent(
                    new IntPair(change.levelChunk.locX, change.levelChunk.locZ),
                    k -> new ArrayList<>()
            ).add(change);
        }
        for (IntPair chunk : toSend) {
            byChunk.computeIfAbsent(chunk, k -> new ArrayList<>());
        }
        final FoliaTaskManager taskManager = FoliaTaskManager.get();
        final List<java.util.concurrent.Future<Object>> futures = new ArrayList<>();
        for (Map.Entry<IntPair, List<CachedChange>> entry : byChunk.entrySet()) {
            final IntPair chunk = entry.getKey();
            final List<CachedChange> chunkChanges = entry.getValue();
            futures.add(taskManager.submitAtChunk(world, chunk.x(), chunk.z(), () -> {
                for (CachedChange cc : chunkChanges) {
                    cc.levelChunk.setBlockState(cc.blockPos, cc.blockState, update ? 0 : 512);
                }
                if (toSend.contains(chunk)) {
                    PaperweightPlatformAdapter.sendChunk(chunk, world.getHandle(), chunk.x(), chunk.z());
                }
                return null;
            }));
        }
        if (!wait) {
            return;
        }
        for (java.util.concurrent.Future<Object> future : futures) {
            try {
                taskManager.await(future);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException(e.getCause());
            }
        }
    }
    //FAWE end

    private Level getLevel() {
        return Objects.requireNonNull(level.get(), "The reference to the world was lost");
    }

    @Override
    public void setCurrentSideEffectSet(SideEffectSet sideEffectSet) {
        this.sideEffectSet = sideEffectSet;
    }

    @Override
    public LevelChunk getChunk(int x, int z) {
        return getLevel().getChunk(x, z);
    }

    @Override
    public net.minecraft.world.level.block.state.BlockState toNative(BlockState blockState) {
        int stateId = paperweightFaweAdapter.ordinalToIbdID(blockState.getOrdinalChar());
        return BlockStateIdAccess.isValidInternalId(stateId)
                ? Block.stateById(stateId)
                : ((CraftBlockData) BukkitAdapter.adapt(blockState)).getState();
    }

    @Override
    public net.minecraft.world.level.block.state.BlockState getBlockState(LevelChunk levelChunk, BlockPos blockPos) {
        return levelChunk.getBlockState(blockPos);
    }

    @Nullable
    @Override
    public synchronized net.minecraft.world.level.block.state.BlockState setBlockState(
            LevelChunk levelChunk, BlockPos blockPos,
            net.minecraft.world.level.block.state.BlockState blockState
    ) {
        int currentTick = currentTick();
        //FAWE - Folia: only the region thread owning the chunk may change it directly
        if (FoliaSupport.isFolia() ? ownsChunk(levelChunk.locX, levelChunk.locZ) : Fawe.isMainThread()) {
            return levelChunk.setBlockState(blockPos, blockState,
                    this.sideEffectSet.shouldApply(SideEffect.UPDATE) ? 0 : 512
            );
        }
        // Since FAWE is.. Async we need to do it on the main thread (wooooo.. :( )
        cachedChanges.add(new CachedChange(levelChunk, blockPos, blockState));
        cachedChunksToSend.add(new IntPair(levelChunk.locX, levelChunk.locZ));
        boolean nextTick = lastTick.get() > currentTick;
        if (nextTick || cachedChanges.size() >= 1024) {
            if (nextTick) {
                lastTick.set(currentTick);
            }
            flushAsync(nextTick);
        }
        return blockState;
    }

    @Override
    public net.minecraft.world.level.block.state.BlockState getValidBlockForPosition(
            net.minecraft.world.level.block.state.BlockState blockState,
            BlockPos blockPos
    ) {
        return Block.updateFromNeighbourShapes(blockState, getLevel(), blockPos);
    }

    @Override
    public BlockPos getPosition(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    @Override
    public void updateLightingForBlock(BlockPos blockPos) {
        getLevel().getChunkSource().getLightEngine().checkBlock(blockPos);
    }

    @Override
    public boolean updateTileEntity(BlockPos blockPos, LinCompoundTag tag) {
        // We will assume that the tile entity was created for us,
        // though we do not do this on the other versions
        BlockEntity blockEntity = getLevel().getBlockEntity(blockPos);
        if (blockEntity == null) {
            return false;
        }
        ValueInput input = createInput(tag);
        blockEntity.loadWithComponents(input);
        return true;
    }


    @Override
    public void notifyBlockUpdate(
            LevelChunk levelChunk, BlockPos blockPos,
            net.minecraft.world.level.block.state.BlockState oldState,
            net.minecraft.world.level.block.state.BlockState newState
    ) {
        if (levelChunk.getSections()[level.get().getSectionIndex(blockPos.getY())] != null) {
            getLevel().sendBlockUpdated(blockPos, oldState, newState, UPDATE | NOTIFY);
        }
    }

    @Override
    public boolean isChunkTicking(LevelChunk levelChunk) {
        return levelChunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING);
    }

    @Override
    public void markBlockChanged(LevelChunk levelChunk, BlockPos blockPos) {
        if (levelChunk.getSections()[level.get().getSectionIndex(blockPos.getY())] != null) {
            ((ServerChunkCache) getLevel().getChunkSource()).blockChanged(blockPos);
        }
    }

    @Override
    public void notifyNeighbors(
            BlockPos blockPos,
            net.minecraft.world.level.block.state.BlockState oldState,
            net.minecraft.world.level.block.state.BlockState newState
    ) {
        Level level = getLevel();
        if (sideEffectSet.shouldApply(SideEffect.EVENTS)) {
            level.updateNeighborsAt(blockPos, oldState.getBlock());
        } else {
            // When we don't want events, manually run the physics without them.
            // Un-nest neighbour updating
            for (Direction direction : NEIGHBOUR_ORDER) {
                BlockPos shifted = blockPos.relative(direction);
                level.getBlockState(shifted).handleNeighborChanged(level, shifted, oldState.getBlock(), ExperimentalRedstoneUtils.initialOrientation(level, null, null), false);
            }
        }
        if (newState.hasAnalogOutputSignal()) {
            level.updateNeighbourForOutputSignal(blockPos, newState.getBlock());
        }
    }

    @Override
    public void updateNeighbors(
            BlockPos blockPos,
            net.minecraft.world.level.block.state.BlockState oldState,
            net.minecraft.world.level.block.state.BlockState newState,
            int recursionLimit
    ) {
        Level level = getLevel();
        // a == updateNeighbors
        // b == updateDiagonalNeighbors
        oldState.updateIndirectNeighbourShapes(level, blockPos, NOTIFY, recursionLimit);
        if (sideEffectSet.shouldApply(SideEffect.EVENTS)) {
            CraftWorld craftWorld = level.getWorld();
            if (craftWorld != null) {
                BlockPhysicsEvent event = new BlockPhysicsEvent(
                        craftWorld.getBlockAt(blockPos.getX(), blockPos.getY(), blockPos.getZ()),
                        CraftBlockData.fromData(newState)
                );
                level.getCraftServer().getPluginManager().callEvent(event);
                if (event.isCancelled()) {
                    return;
                }
            }
        }
        newState.triggerEvent(level, blockPos, NOTIFY, recursionLimit);
        newState.updateIndirectNeighbourShapes(level, blockPos, NOTIFY, recursionLimit);
    }

    @Override
    public void updateBlock(BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState, net.minecraft.world.level.block.state.BlockState newState) {
        Level world = getLevel();
        newState.onPlace(world, pos, oldState, false);
    }

    @Override
    public void onBlockStateChange(
            BlockPos blockPos,
            net.minecraft.world.level.block.state.BlockState oldState,
            net.minecraft.world.level.block.state.BlockState newState
    ) {
        getLevel().updatePOIOnBlockStateChange(blockPos, oldState, newState);
    }

    private synchronized void flushAsync(final boolean sendChunks) {
        final Set<CachedChange> changes = Set.copyOf(cachedChanges);
        cachedChanges.clear();
        final Set<IntPair> toSend;
        if (sendChunks) {
            toSend = Set.copyOf(cachedChunksToSend);
            cachedChunksToSend.clear();
        } else {
            toSend = Collections.emptySet();
        }
        RunnableVal<Object> runnableVal = new RunnableVal<>() {
            @Override
            public void run(Object value) {
                changes.forEach(cc -> cc.levelChunk.setBlockState(cc.blockPos, cc.blockState,
                        sideEffectSet.shouldApply(SideEffect.UPDATE) ? 0 : 512
                ));
                if (!sendChunks) {
                    return;
                }
                for (IntPair chunk : toSend) {
                    PaperweightPlatformAdapter.sendChunk(chunk, getLevel().getWorld().getHandle(), chunk.x(), chunk.z());
                }
            }
        };
        if (FoliaSupport.isFolia()) {
            applyFolia(changes, toSend, false);
            return;
        }
        TaskManager.taskManager().async(() -> TaskManager.taskManager().sync(runnableVal));
    }

    @Override
    public synchronized void flush() {
        RunnableVal<Object> runnableVal = new RunnableVal<>() {
            @Override
            public void run(Object value) {
                cachedChanges.forEach(cc -> cc.levelChunk.setBlockState(cc.blockPos, cc.blockState,
                        sideEffectSet.shouldApply(SideEffect.UPDATE) ? 0 : 512
                ));
                for (IntPair chunk : cachedChunksToSend) {
                    PaperweightPlatformAdapter.sendChunk(chunk, getLevel().getWorld().getHandle(), chunk.x(), chunk.z());
                }
            }
        };
        if (FoliaSupport.isFolia()) {
            applyFolia(Set.copyOf(cachedChanges), Set.copyOf(cachedChunksToSend), true);
        } else if (Fawe.isMainThread()) {
            runnableVal.run();
        } else {
            TaskManager.taskManager().sync(runnableVal);
        }
        cachedChanges.clear();
        cachedChunksToSend.clear();
    }

    private record CachedChange(
            LevelChunk levelChunk,
            BlockPos blockPos,
            net.minecraft.world.level.block.state.BlockState blockState
    ) {

    }

}
