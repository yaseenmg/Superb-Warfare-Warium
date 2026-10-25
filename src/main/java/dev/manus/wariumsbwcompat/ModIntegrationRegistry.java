package dev.manus.wariumsbwcompat;

import net.minecraftforge.fml.ModList;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional-mod presence registry, shown by /wariumcompat integrations and get_status.
 * advancedperipherals is only detected: it is built on CC:Tweaked, so its computers use the
 * warium_sbw peripheral like any other, but this mod has no Advanced Peripherals specific code.
 */
public final class ModIntegrationRegistry {
    private static boolean initialized;
    private static final Map<String, Boolean> LOADED = new LinkedHashMap<>();
    private static final String[] OPTIONAL_IDS = {
            "computercraft", "advancedperipherals", "tacz", "sbw_air_defense", "deepstrike",
            "sbw_ew_addon", "drone_detector", "vvp"
    };

    private ModIntegrationRegistry() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        for (String id : OPTIONAL_IDS) LOADED.put(id, ModList.get().isLoaded(id));
    }

    public static boolean isLoaded(String modId) {
        initialize();
        return LOADED.computeIfAbsent(modId, id -> ModList.get().isLoaded(id));
    }

    public static Map<String, Boolean> status() {
        initialize();
        return new LinkedHashMap<>(LOADED);
    }
}
