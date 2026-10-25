package dev.manus.wariumsbwcompat;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Optional runtime adapters. No add-on class is loaded until its mod is present, reflective
 * lookups are resolved once and cached, and every adapter is failure-isolated so an add-on update
 * cannot crash this mod.
 */
public final class OptionalAddonCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("warium_sbw_compat/optional");
    private static final String DATA_PREFIX = SbwWariumBridge.PREFIX + "optional.";
    /** Guided projectiles are nudged every this many ticks while inside a jammer's range. */
    private static final int GUIDANCE_PERIOD = 3;

    private static boolean initialized;
    private static boolean airDefense;
    private static boolean deepStrike;
    private static boolean electronicWarfare;

    private static Class<?> jammerClass;
    private static Method activeJammersMethod;
    private static Method operationalMethod;
    private static Method jamRadiusMethod;
    private static Method moderateRadiusMethod;
    private static Method criticalRadiusMethod;

    private static boolean airDefenseResolved;
    private static boolean airDefenseUsable;
    private static Method adIsAirTarget;
    private static Method adIsInterceptable;
    private static Class<?> adRulesClass;
    private static Method adRulesGet;
    private static Method adGetWhitelist;
    private static Method adAddWhitelist;

    private static boolean deepStrikeResolved;
    private static boolean deepStrikeUsable;
    private static Method dsAircraft;
    private static Method dsTarget;
    private static Method dsKind;

    private static final Map<ServerLevel, JammerSnapshot> JAMMER_CACHE = new WeakHashMap<>();
    private static final Map<ServerLevel, Set<ResourceLocation>> WHITELISTED = new WeakHashMap<>();
    /** Guided projectiles currently inside a jammer's range; weak so removed entities are never held. */
    private static final Map<Projectile, Boolean> GUIDED = new WeakHashMap<>();

    private OptionalAddonCompat() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        airDefense = ModList.get().isLoaded("sbw_air_defense");
        deepStrike = ModList.get().isLoaded("deepstrike");
        electronicWarfare = ModList.get().isLoaded("sbw_ew_addon");
        if (electronicWarfare) initializeJammerReflection();
    }

    public static void inspect(ServerLevel level, Entity entity, boolean air, boolean projectile) {
        if (!CompatConfig.ENABLE_OPTIONAL_ADAPTERS.get() || !CompatConfig.ENABLE_RUNTIME_TAGS.get()) return;
        initialize();
        long refreshBucket = level.getGameTime() / 20L;
        String signature = (air ? "air" : "not_air") + ":" + (projectile ? "projectile" : "not_projectile")
                + ":" + refreshBucket;
        String signatureKey = DATA_PREFIX + "signature";
        if (signature.equals(entity.getPersistentData().getString(signatureKey))) return;
        EntityData.putString(entity, signatureKey, signature);
        if (airDefense) inspectAirDefense(level, entity, air, projectile);
        if (deepStrike) inspectDeepStrike(entity, air, projectile);
        if (electronicWarfare) markElectronicWarfareTarget(entity, air, projectile);
    }

    // ---------------------------------------------------------------- jamming

    /** Updates the jamming data of one loaded entity and registers guided projectiles for drift. */
    public static void updateJamming(ServerLevel level, Entity entity) {
        if (!CompatConfig.ENABLE_OPTIONAL_ADAPTERS.get() || !CompatConfig.ENABLE_RUNTIME_TAGS.get()) return;
        initialize();
        if (!electronicWarfare || activeJammersMethod == null) return;

        int strength = jammerStrength(level, entity.getX(), entity.getY(), entity.getZ());
        if (entity instanceof Projectile projectile && projectile.getOwner() != null) {
            strength = Math.max(strength, entityJamStrength(projectile.getOwner()));
        }
        if (strength <= 0) {
            EntityData.remove(entity, DATA_PREFIX + "jamming.level");
            EntityData.remove(entity, DATA_PREFIX + "jamming.guidance_error");
            EntityData.putBoolean(entity, DATA_PREFIX + "jamming.control_degraded", false);
            EntityData.putBoolean(entity, DATA_PREFIX + "jamming.lock_degraded", false);
            EntityData.putBoolean(entity, DATA_PREFIX + "jamming.guided", false);
            if (entity instanceof Projectile projectile) GUIDED.remove(projectile);
            return;
        }

        EntityData.putInt(entity, DATA_PREFIX + "jamming.level", strength);
        EntityData.putBoolean(entity, DATA_PREFIX + "jamming.control_degraded", strength >= 2);
        EntityData.putBoolean(entity, DATA_PREFIX + "jamming.lock_degraded", true);
        EntityData.putDouble(entity, DATA_PREFIX + "jamming.guidance_error",
                strength * CompatConfig.JAMMING_ERROR_PER_LEVEL.get());
        boolean guided = false;
        if (entity instanceof Projectile projectile) {
            Entity owner = projectile.getOwner();
            boolean wariumShot = SbwWariumBridge.isWarium(projectile)
                    || (owner != null && SbwWariumBridge.isWarium(owner));
            guided = wariumShot && SbwWariumBridge.isGuidedProjectile(projectile);
            if (guided) GUIDED.put(projectile, Boolean.TRUE);
            else GUIDED.remove(projectile);
        }
        EntityData.putBoolean(entity, DATA_PREFIX + "jamming.guided", guided);
    }

    /**
     * Runs every server tick. Guided projectiles inside a jammer's range get a small random course
     * error every few ticks; unguided shells and bombs are never touched. The speed is preserved.
     * Whether the effect holds depends on the projectile not re-steering itself every tick.
     */
    public static void tickGuidance() {
        if (GUIDED.isEmpty()) return;
        // If the adapters were switched off after a missile was registered, stop steering it and
        // drop the registry instead of leaving a stale course error on a live projectile.
        if (!CompatConfig.ENABLE_OPTIONAL_ADAPTERS.get() || !CompatConfig.ENABLE_RUNTIME_TAGS.get()) {
            GUIDED.clear();
            return;
        }
        for (Projectile projectile : new ArrayList<>(GUIDED.keySet())) {
            if (projectile == null || projectile.isRemoved() || !projectile.isAlive()) {
                if (projectile != null) GUIDED.remove(projectile);
                continue;
            }
            int strength = projectile.getPersistentData().getInt(DATA_PREFIX + "jamming.level");
            if (strength <= 0) {
                GUIDED.remove(projectile);
                continue;
            }
            if (projectile.tickCount % GUIDANCE_PERIOD == 0) applyGuidanceDrift(projectile, strength);
        }
    }

    private static void applyGuidanceDrift(Projectile projectile, int strength) {
        double error = CompatConfig.JAMMING_ERROR_PER_LEVEL.get();
        if (error <= 0.0D) return;
        Vec3 velocity = projectile.getDeltaMovement();
        double speed = velocity.length();
        if (speed < 0.05D) return;
        RandomSource random = projectile.level().getRandom();
        double amplitude = error * strength * 0.25D;
        float yaw = (float) ((random.nextDouble() - 0.5D) * 2.0D * amplitude);
        double vertical = (random.nextDouble() - 0.5D) * 2.0D * amplitude * speed;
        Vec3 steered = velocity.yRot(yaw).add(0.0D, vertical, 0.0D);
        double length = steered.length();
        if (length < 1.0E-6D) return;
        projectile.setDeltaMovement(steered.scale(speed / length));
        projectile.hasImpulse = true;
    }

    public static boolean isJammer(BlockEntity blockEntity) {
        initialize();
        return jammerClass != null && jammerClass.isInstance(blockEntity);
    }

    /** Strength 0 to 3 of the strongest active jammer covering a point; 0 when EW is not installed. */
    public static int jammerStrengthAt(ServerLevel level, double x, double y, double z) {
        initialize();
        if (!electronicWarfare || activeJammersMethod == null) return 0;
        return jammerStrength(level, x, y, z);
    }

    /**
     * Known class names for the EW jammer block entity, most likely first. The published 1.1.1 build
     * really does use the {@code com.example} package, but a future release may rename it, and a
     * silent rename would disable jamming with no error. Every candidate is tried before giving up.
     */
    private static final String[] JAMMER_CLASS_CANDIDATES = {
            "com.example.sbw_ew_addon.JammerBlockEntity",
            "net.sbw.ew.JammerBlockEntity",
            "com.sbw.electronicwarfare.JammerBlockEntity",
            "sbw.electronicwarfare.JammerBlockEntity",
            "com.sbw_ew_addon.JammerBlockEntity"
    };

    private static void initializeJammerReflection() {
        for (String candidate : JAMMER_CLASS_CANDIDATES) {
            try {
                Class<?> type = loadClass(candidate);
                Method active = type.getMethod("getActiveJammers", net.minecraft.world.level.Level.class);
                Method operational = type.getMethod("hasOperationalPower");
                Method jamRadius = type.getMethod("getJamRadius");
                Method moderateRadius = type.getMethod("getModerateRadius");
                Method criticalRadius = type.getMethod("getCriticalRadius");
                jammerClass = type;
                activeJammersMethod = active;
                operationalMethod = operational;
                jamRadiusMethod = jamRadius;
                moderateRadiusMethod = moderateRadius;
                criticalRadiusMethod = criticalRadius;
                if (CompatConfig.debugEnabled()) {
                    LOGGER.info("Electronic Warfare jammer adapter bound to {}", candidate);
                }
                return;
            } catch (Throwable ignored) {
                // Try the next candidate; only give up when every name fails.
            }
        }
        jammerClass = null;
        activeJammersMethod = null;
        operationalMethod = null;
        jamRadiusMethod = null;
        moderateRadiusMethod = null;
        criticalRadiusMethod = null;
        if (CompatConfig.debugEnabled()) {
            LOGGER.warn("Electronic Warfare is loaded but no known jammer class resolved; jamming is inactive.");
        }
    }

    private static int jammerStrength(ServerLevel level, double x, double y, double z) {
        try {
            List<JammerInfo> jammers = activeJammers(level);
            int best = 0;
            for (JammerInfo jammer : jammers) {
                double dx = x - jammer.x();
                double dy = y - jammer.y();
                double dz = z - jammer.z();
                double distanceSquared = dx * dx + dy * dy + dz * dz;
                if (distanceSquared <= jammer.criticalSquared()) best = Math.max(best, 3);
                else if (distanceSquared <= jammer.moderateSquared()) best = Math.max(best, 2);
                else if (distanceSquared <= jammer.normalSquared()) best = Math.max(best, 1);
            }
            return best;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int entityJamStrength(Entity entity) {
        return entity.getPersistentData().getInt(DATA_PREFIX + "jamming.level");
    }

    /** Builds the expensive reflective jammer data once per level per tick, not once per entity. */
    private static List<JammerInfo> activeJammers(ServerLevel level) throws Exception {
        long tick = level.getGameTime();
        synchronized (JAMMER_CACHE) {
            JammerSnapshot cached = JAMMER_CACHE.get(level);
            if (cached != null && cached.tick == tick) return cached.jammers;
            Object result = activeJammersMethod.invoke(null, level);
            List<JammerInfo> jammers = new ArrayList<>();
            if (result instanceof Set<?> raw) {
                for (Object value : raw) {
                    if (!(value instanceof BlockPos pos)) continue;
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity == null || !jammerClass.isInstance(blockEntity)) continue;
                    if (!Boolean.TRUE.equals(operationalMethod.invoke(blockEntity))) continue;
                    double normal = Math.max(0.0D, ((Number) jamRadiusMethod.invoke(blockEntity)).doubleValue());
                    double moderate = Math.max(0.0D, ((Number) moderateRadiusMethod.invoke(blockEntity)).doubleValue());
                    double critical = Math.max(0.0D, ((Number) criticalRadiusMethod.invoke(blockEntity)).doubleValue());
                    jammers.add(new JammerInfo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                            normal * normal, moderate * moderate, critical * critical));
                }
            }
            List<JammerInfo> immutable = List.copyOf(jammers);
            JAMMER_CACHE.put(level, new JammerSnapshot(tick, immutable));
            return immutable;
        }
    }

    private record JammerInfo(double x, double y, double z, double normalSquared,
                              double moderateSquared, double criticalSquared) {}
    private record JammerSnapshot(long tick, List<JammerInfo> jammers) {}

    // ---------------------------------------------------------------- air defense

    private static synchronized void resolveAirDefense(Entity sample) {
        if (airDefenseResolved) return;
        airDefenseResolved = true;
        try {
            Class<?> targeting = loadOptionalClass("com.sbwairdefense.core.targeting.AirDefenseTargeting", sample);
            adIsAirTarget = targeting.getMethod("isAirTarget", Entity.class);
            adIsInterceptable = targeting.getMethod("isInterceptableMissileOrRocket", Entity.class);
            airDefenseUsable = true;
        } catch (Throwable ignored) {
            airDefenseUsable = false;
        }
        try {
            adRulesClass = loadOptionalClass("com.sbwairdefense.core.targeting.AirDefenseEntityTargetRules", sample);
            adRulesGet = adRulesClass.getMethod("get", ServerLevel.class);
            adGetWhitelist = adRulesClass.getMethod("getWhitelist");
            adAddWhitelist = adRulesClass.getMethod("addWhitelist", ResourceLocation.class);
        } catch (Throwable ignored) {
            adRulesGet = null;
        }
    }

    private static void inspectAirDefense(ServerLevel level, Entity entity, boolean air, boolean projectile) {
        try {
            resolveAirDefense(entity);
            if (!airDefenseUsable) return;
            boolean addonAir = (Boolean) adIsAirTarget.invoke(null, entity);
            boolean addonProjectile = (Boolean) adIsInterceptable.invoke(null, entity);
            EntityData.putBoolean(entity, DATA_PREFIX + "air_defense.air_target", addonAir);
            EntityData.putBoolean(entity, DATA_PREFIX + "air_defense.interceptable", addonProjectile);

            if (CompatConfig.EXTEND_AIR_DEFENSE_WHITELIST.get() && (air || projectile) && adRulesGet != null) {
                Object rules = adRulesGet.invoke(null, level);
                if (rules != null) {
                    Collection<?> whitelist = (Collection<?>) adGetWhitelist.invoke(rules);
                    ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
                    if (id != null && whitelist != null && !whitelist.isEmpty()) {
                        synchronized (WHITELISTED) {
                            Set<ResourceLocation> done = WHITELISTED.computeIfAbsent(level, ignored -> new HashSet<>());
                            if (!done.contains(id)) {
                                if (!whitelist.contains(id)) adAddWhitelist.invoke(rules, id);
                                done.add(id);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Optional add-on versions are allowed to change independently.
        }
    }

    // ---------------------------------------------------------------- deepstrike

    private static synchronized void resolveDeepStrike(Entity sample) {
        if (deepStrikeResolved) return;
        deepStrikeResolved = true;
        try {
            Class<?> targets = loadOptionalClass("dev.sbw.deepstrike.game.AirDefenseTargets", sample);
            dsAircraft = targets.getMethod("aircraft", Entity.class);
            dsTarget = targets.getMethod("target", Entity.class);
            dsKind = targets.getMethod("kind", Entity.class);
            deepStrikeUsable = true;
        } catch (Throwable ignored) {
            deepStrikeUsable = false;
        }
    }

    private static void inspectDeepStrike(Entity entity, boolean air, boolean projectile) {
        try {
            resolveDeepStrike(entity);
            if (!deepStrikeUsable) return;
            EntityData.putBoolean(entity, DATA_PREFIX + "deepstrike.aircraft", (Boolean) dsAircraft.invoke(null, entity));
            EntityData.putBoolean(entity, DATA_PREFIX + "deepstrike.target", (Boolean) dsTarget.invoke(null, entity));
            EntityData.putInt(entity, DATA_PREFIX + "deepstrike.kind", ((Number) dsKind.invoke(null, entity)).intValue());
            EntityData.putBoolean(entity, DATA_PREFIX + "deepstrike.compat_candidate", air || projectile);
        } catch (Throwable ignored) {
            // DeepStrike has no public registration API; this probe is best effort.
        }
    }

    private static void markElectronicWarfareTarget(Entity entity, boolean air, boolean projectile) {
        if (air || projectile) {
            EntityData.putBoolean(entity, DATA_PREFIX + "electronic_warfare.candidate", true);
            EntityData.putString(entity, DATA_PREFIX + "electronic_warfare.mode", "progressive_jam_bridge");
        }
    }

    // ---------------------------------------------------------------- class loading

    private static Class<?> loadOptionalClass(String name, Entity entity) throws ClassNotFoundException {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            try {
                return Class.forName(name, false, context);
            } catch (ClassNotFoundException ignored) {
                // Try the loader that loaded the active entity next.
            }
        }
        ClassLoader entityLoader = entity.getClass().getClassLoader();
        if (entityLoader != null && entityLoader != context) {
            try {
                return Class.forName(name, false, entityLoader);
            } catch (ClassNotFoundException ignored) {
                // Fall through to this mod's loader.
            }
        }
        return Class.forName(name, false, OptionalAddonCompat.class.getClassLoader());
    }

    private static Class<?> loadClass(String name) throws ClassNotFoundException {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            try {
                return Class.forName(name, false, context);
            } catch (ClassNotFoundException ignored) {
                // Fall through to this mod's loader.
            }
        }
        return Class.forName(name, false, OptionalAddonCompat.class.getClassLoader());
    }
}
