package dev.manus.wariumsbwcompat;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(WariumSbwCompat.MOD_ID)
public final class WariumSbwCompat {
    public static final String MOD_ID = "warium_sbw_compat";

    public WariumSbwCompat() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CompatConfig.SPEC);
        // Optional adapters read config values, and Forge loads configs in CONFIG_LOAD, which runs
        // after this constructor. Resolving them here would read the defaults in production and
        // throw "Cannot get config value before config is loaded" in a development environment,
        // so everything that touches config is deferred to common setup.
        FMLJavaModLoadingContext.get().getModEventBus().addListener(WariumSbwCompat::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(SbwWariumBridge.class);
        MinecraftForge.EVENT_BUS.register(BattlefieldSystems.class);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ModIntegrationRegistry.initialize();
            OptionalAddonCompat.initialize();
            CcTweakedCompat.initialize();
        });
    }
}
