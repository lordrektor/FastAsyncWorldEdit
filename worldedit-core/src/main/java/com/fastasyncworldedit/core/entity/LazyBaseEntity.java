package com.fastasyncworldedit.core.entity;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.util.FoliaSupport;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.world.entity.EntityType;
import org.enginehub.linbus.tree.LinCompoundTag;

import javax.annotation.Nullable;
import java.util.function.Supplier;

public class LazyBaseEntity extends BaseEntity {

    private Supplier<LinCompoundTag> saveTag;

    public LazyBaseEntity(EntityType type, Supplier<LinCompoundTag> saveTag) {
        super(type);
        this.saveTag = saveTag;
    }

    @Nullable
    @Override
    public LinCompoundTag getNbt() {
        Supplier<LinCompoundTag> tmp = saveTag;
        if (tmp != null) {
            saveTag = null;
            // On Folia the supplier itself hops to the thread owning the entity
            if (Fawe.isMainThread() || FoliaSupport.isFolia()) {
                setNbt(tmp.get());
            } else {
                setNbt(TaskManager.taskManager().sync(tmp));
            }
        }
        return super.getNbt();
    }

}
