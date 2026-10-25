package dev.manus.wariumsbwcompat;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class CompatConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue SCAN_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_RUNTIME_TAGS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_OPTIONAL_ADAPTERS;
    public static final ForgeConfigSpec.BooleanValue EXTEND_AIR_DEFENSE_WHITELIST;
    public static final ForgeConfigSpec.BooleanValue ENABLE_THREAT_RESPONSE;
    public static final ForgeConfigSpec.DoubleValue JAMMING_ERROR_PER_LEVEL;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUIDED_KEYWORDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUIDED_IDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXTRA_CANDIDATE_NAMESPACES;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BATTLEFIELD_SYSTEMS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_WEATHER_TERRAIN_EFFECTS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_IFF_AND_TRACK_FUSION;
    public static final ForgeConfigSpec.IntValue TRACK_TIMEOUT_TICKS;
    public static final ForgeConfigSpec.IntValue TRACK_EXPIRE_TICKS;
    public static final ForgeConfigSpec.IntValue CC_MAX_RANGE;
    public static final ForgeConfigSpec.BooleanValue CC_EVENTS;
    public static final ForgeConfigSpec.IntValue CC_EVENT_RANGE;
    public static final ForgeConfigSpec.BooleanValue RADAR_LINE_OF_SIGHT;
    public static final ForgeConfigSpec.IntValue MAX_LINE_OF_SIGHT_CHECKS;
    public static final ForgeConfigSpec.DoubleValue RADAR_JAM_RANGE_PENALTY;
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("compatibility");
        SCAN_INTERVAL_TICKS = builder.comment("How often loaded entities are rescanned. 20 = once per second.")
                .defineInRange("scanIntervalTicks", 20, 1, 1200);
        ENABLE_RUNTIME_TAGS = builder.comment("Publish runtime Forge entity tags and persistent classification data.")
                .define("enableRuntimeTags", true);
        ENABLE_OPTIONAL_ADAPTERS = builder.comment("Probe installed Air Defense, DeepStrike and EW APIs without hard dependencies.")
                .define("enableOptionalAdapters", true);
        EXTEND_AIR_DEFENSE_WHITELIST = builder.comment("Opt-in: add classified entity types to an already non-empty Air Defense whitelist.")
                .define("extendAirDefenseWhitelist", false);
        ENABLE_THREAT_RESPONSE = builder.comment("Let Warium monsters retaliate against a living attacker or projectile owner.")
                .define("enableThreatResponse", true);
        EXTRA_CANDIDATE_NAMESPACES = builder.comment(
                        "Extra entity namespaces to classify, for example [\"superbwarfare\"]. Requires a restart.",
                        "Off by default: classifying every Superb Warfare bullet and vehicle costs scan time.")
                .defineListAllowEmpty("extraCandidateNamespaces", List.of(), o -> o instanceof String);
        builder.pop();

        builder.push("electronicWarfare");
        JAMMING_ERROR_PER_LEVEL = builder.comment(
                        "Random course error per guidance step (every 3 ticks) per jammer level, in radians.",
                        "Only guided projectiles are affected. 0.035 is about 2 degrees; 0 disables the effect.")
                .defineInRange("jammingErrorPerLevel", 0.035D, 0.0D, 0.5D);
        GUIDED_KEYWORDS = builder.comment("Projectile ids or class names containing one of these words count as guided. Requires a restart.")
                .defineListAllowEmpty("guidedProjectileKeywords", List.of("missile", "seeker", "guided"), o -> o instanceof String);
        GUIDED_IDS = builder.comment("Extra projectile ids that count as guided, as namespace:path.")
                .defineListAllowEmpty("guidedProjectileIds", List.of(), o -> o instanceof String);
        builder.pop();

        builder.push("battlefield");
        ENABLE_BATTLEFIELD_SYSTEMS = builder.comment("Enable track bookkeeping, IFF, target priority, kill assessment and decoy tags.")
                .define("enableBattlefieldSystems", true);
        ENABLE_WEATHER_TERRAIN_EFFECTS = builder.comment("Apply rain, thunder and cover to the track confidence estimate.")
                .define("enableWeatherTerrainEffects", true);
        ENABLE_IFF_AND_TRACK_FUSION = builder.comment("Publish IFF state and track data in persistent entity data.")
                .define("enableIffAndTrackFusion", true);
        TRACK_TIMEOUT_TICKS = builder.comment("Ticks before a missing track becomes track_lost.")
                .defineInRange("trackTimeoutTicks", 100, 20, 2400);
        TRACK_EXPIRE_TICKS = builder.comment("Extra ticks to keep a track_lost or kill_confirmed entry before deleting it.")
                .defineInRange("trackExpireTicks", 100, 20, 2400);
        builder.pop();

        builder.push("computercraft");
        CC_MAX_RANGE = builder.comment("Maximum radar range, in blocks, a computer may request.")
                .defineInRange("maxRange", 512, 16, 1024);
        CC_EVENTS = builder.comment("Queue a warium_sbw_contact event on attached computers when a new hostile contact appears.")
                .define("contactEvents", true);
        CC_EVENT_RANGE = builder.comment("Range used for contact events.")
                .defineInRange("contactEventRange", 128, 8, 1024);
        RADAR_LINE_OF_SIGHT = builder.comment("Require a clear line of sight from the radar block to a contact (costs ray casts).")
                .define("requireLineOfSight", false);
        MAX_LINE_OF_SIGHT_CHECKS = builder.comment(
                        "Maximum ray casts per get_contacts/get_threats call when requireLineOfSight is on.",
                        "Contacts beyond this budget are returned without a line-of-sight check instead of",
                        "being silently hidden. Lower it on busy servers; raise it for strict filtering.")
                .defineInRange("maxLineOfSightChecks", 128, 8, 512);
        RADAR_JAM_RANGE_PENALTY = builder.comment("Fraction of radar range lost per jammer level at the radar block (never below 25%).")
                .defineInRange("jamRangePenaltyPerLevel", 0.2D, 0.0D, 0.3D);
        builder.pop();

        builder.push("debug");
        DEBUG_LOGGING = builder.comment("Log classification changes. Keep false on busy servers.")
                .define("debugLogging", false);
        builder.pop();
        SPEC = builder.build();
    }

    private CompatConfig() {}

    /**
     * Debug flag that is safe to read before Forge has loaded the config. A plain
     * {@code DEBUG_LOGGING.get()} throws in a development environment when the config is not loaded
     * yet, which is why optional adapters must use this helper during early startup.
     */
    public static boolean debugEnabled() {
        return SPEC.isLoaded() && DEBUG_LOGGING.get();
    }
}
