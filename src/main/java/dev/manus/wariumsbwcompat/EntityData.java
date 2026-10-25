package dev.manus.wariumsbwcompat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Write-only-if-changed helpers, so scans do not rewrite identical NBT and tags every second. */
final class EntityData {
    private EntityData() {}

    /** Block entities use the same persistent-data compound, so they get the same guard. */
    static void putBoolean(BlockEntity blockEntity, String key, boolean value) {
        CompoundTag data = blockEntity.getPersistentData();
        if (!data.contains(key) || data.getBoolean(key) != value) data.putBoolean(key, value);
    }

    static void putString(Entity entity, String key, String value) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(key) || !value.equals(data.getString(key))) data.putString(key, value);
    }

    static void putBoolean(Entity entity, String key, boolean value) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(key) || data.getBoolean(key) != value) data.putBoolean(key, value);
    }

    static void putInt(Entity entity, String key, int value) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(key) || data.getInt(key) != value) data.putInt(key, value);
    }

    static void putDouble(Entity entity, String key, double value) {
        CompoundTag data = entity.getPersistentData();
        // Double.compare treats NaN as equal to itself; != would rewrite the tag on every scan.
        if (!data.contains(key) || Double.compare(data.getDouble(key), value) != 0) data.putDouble(key, value);
    }

    static void putUUID(Entity entity, String key, java.util.UUID value) {
        CompoundTag data = entity.getPersistentData();
        if (!data.hasUUID(key) || !value.equals(data.getUUID(key))) data.putUUID(key, value);
    }

    static void remove(Entity entity, String key) {
        CompoundTag data = entity.getPersistentData();
        if (data.contains(key)) data.remove(key);
    }

    /** Adds or removes a scoreboard tag through the official API instead of mutating the tag set. */
    static void tag(Entity entity, String tag, boolean on) {
        boolean present = entity.getTags().contains(tag);
        if (on && !present) entity.addTag(tag);
        else if (!on && present) entity.removeTag(tag);
    }
}
