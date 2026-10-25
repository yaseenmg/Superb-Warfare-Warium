package dev.manus.wariumsbwcompat;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reads the standard Forge Energy capability without depending on any power mod. */
public final class ForgeEnergyCompat {
    private ForgeEnergyCompat() {}

    public static Map<String, Object> status(BlockEntity blockEntity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supported", false);
        result.put("energy", 0);
        result.put("capacity", 0);
        result.put("can_extract", false);
        result.put("can_receive", false);
        blockEntity.getCapability(ForgeCapabilities.ENERGY).ifPresent(storage -> fill(result, storage));
        return result;
    }

    /** True unless the block reports an energy buffer that is completely empty. */
    public static boolean isPowered(BlockEntity blockEntity) {
        boolean[] powered = {true};
        blockEntity.getCapability(ForgeCapabilities.ENERGY).ifPresent(storage -> {
            if (storage.getMaxEnergyStored() > 0 && storage.getEnergyStored() <= 0) powered[0] = false;
        });
        return powered[0];
    }

    private static void fill(Map<String, Object> result, IEnergyStorage storage) {
        result.put("supported", true);
        result.put("energy", storage.getEnergyStored());
        result.put("capacity", storage.getMaxEnergyStored());
        result.put("can_extract", storage.canExtract());
        result.put("can_receive", storage.canReceive());
    }
}
