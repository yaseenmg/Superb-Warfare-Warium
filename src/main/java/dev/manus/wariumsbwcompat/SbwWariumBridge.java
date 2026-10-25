package dev.manus.wariumsbwcompat;

import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime-only compatibility layer. Warium and Superb Warfare are hard dependencies declared in
 * {@code mods.toml}; every Superb Warfare add-on is optional and reached reflectively. The bridge
 * publishes scoreboard tags and persistent data keys that radar, air-defense and scripting add-ons
 * can read without linking against Warium classes.
 *
 * One server-tick pass does all the per-entity work: classify, then jamming, then track update.
 * Per-type facts (namespace, id words, guided flag) are computed once and cached.
 */
public final class SbwWariumBridge {
    private static final Logger LOGGER = LogManager.getLogger();
    public static final String PREFIX = "warium_sbw_bridge.";
    public static final String AIR_TAG = "sbw_air_target";
    public static final String GROUND_TAG = "sbw_ground_target";
    public static final String PROJECTILE_TAG = "sbw_projectile_target";
    public static final String WARIUM_TAG = "warium_threat";

    private static final Map<EntityType<?>, TypeInfo> TYPE_INFO = new ConcurrentHashMap<>();

    private record TypeInfo(String fullId, boolean candidate, boolean warium,
                            Heuristics.Profile profile) {}

    private SbwWariumBridge() {}

    private static TypeInfo info(Entity entity) {
        EntityType<?> type = entity.getType();
        TypeInfo cached = TYPE_INFO.get(type);
        if (cached != null) return cached;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
        String className = entity.getClass().getSimpleName();
        TypeInfo created;
        if (id == null) {
            created = new TypeInfo("", false, false,
                    Heuristics.profile("", className, CompatConfig.GUIDED_KEYWORDS.get()));
        } else {
            String namespace = id.getNamespace().toLowerCase(Locale.ROOT);
            created = new TypeInfo(namespace + ":" + id.getPath().toLowerCase(Locale.ROOT),
                    Heuristics.integrationNamespace(namespace, CompatConfig.EXTRA_CANDIDATE_NAMESPACES.get()),
                    Heuristics.wariumNamespace(namespace),
                    Heuristics.profile(id.getPath(), className, CompatConfig.GUIDED_KEYWORDS.get()));
        }
        TYPE_INFO.put(type, created);
        return created;
    }

    // ---------------------------------------------------------------- commands

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wariumcompat")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("classify").executes(context -> {
                    int scanned = 0;
                    for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
                        for (Entity entity : level.getAllEntities()) {
                            if (isCandidate(entity)) {
                                classify(entity);
                                scanned++;
                            }
                        }
                    }
                    int result = scanned;
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Warium-SBW: classified " + result + " candidate entities."), true);
                    return result;
                }))
                .then(Commands.literal("list").executes(context -> {
                    int air = 0, ground = 0, projectiles = 0, unknown = 0;
                    for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
                        for (Entity entity : level.getAllEntities()) {
                            String kind = entity.getPersistentData().getString(PREFIX + "class");
                            if (AIR_TAG.equals(kind)) air++;
                            else if (GROUND_TAG.equals(kind)) ground++;
                            else if (PROJECTILE_TAG.equals(kind)) projectiles++;
                            else if ("unknown".equals(kind)) unknown++;
                        }
                    }
                    int finalAir = air;
                    int finalGround = ground;
                    int finalProjectiles = projectiles;
                    int finalUnknown = unknown;
                    int total = finalAir + finalGround + finalProjectiles + finalUnknown;
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Warium-SBW: air=" + finalAir + ", ground=" + finalGround
                                    + ", projectiles=" + finalProjectiles + ", unknown=" + finalUnknown
                                    + ", total=" + total), false);
                    return total;
                }))
                .then(Commands.literal("tracks").executes(context -> {
                    int tracking = 0, lost = 0, killed = 0;
                    int now = context.getSource().getServer().getTickCount();
                    for (BattlefieldSystems.TrackView view : BattlefieldSystems.snapshot(null, now)) {
                        if (BattlefieldSystems.STATE_LOST.equals(view.state())) lost++;
                        else if (BattlefieldSystems.STATE_KILL.equals(view.state())) killed++;
                        else tracking++;
                    }
                    int finalTracking = tracking;
                    int finalLost = lost;
                    int finalKilled = killed;
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Warium-SBW tracks: tracking=" + finalTracking + ", track_lost=" + finalLost
                                    + ", kill_confirmed=" + finalKilled), false);
                    return finalTracking + finalLost + finalKilled;
                }))
                .then(Commands.literal("emcon")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("silent", BoolArgumentType.bool()).executes(context -> {
                                    boolean silent = BoolArgumentType.getBool(context, "silent");
                                    int count = 0;
                                    for (Entity entity : EntityArgument.getEntities(context, "targets")) {
                                        EntityData.putBoolean(entity, PREFIX + "silent_emission", silent);
                                        count++;
                                    }
                                    int changed = count;
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Warium-SBW: emission control " + (silent ? "ON (silent)" : "OFF")
                                                    + " for " + changed + " entities."), true);
                                    return changed;
                                }))))
                .then(Commands.literal("integrations").executes(context -> {
                    StringBuilder report = new StringBuilder("Warium-SBW integrations: ");
                    ModIntegrationRegistry.status().forEach((id, loaded) ->
                            report.append(id).append('=').append(loaded).append(' '));
                    context.getSource().sendSuccess(() -> Component.literal(report.toString()), false);
                    return 1;
                })));
    }

    // ---------------------------------------------------------------- events

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        classify(event.getEntity());
    }

    @SubscribeEvent
    public static void onWariumAttacked(LivingAttackEvent event) {
        if (!CompatConfig.ENABLE_THREAT_RESPONSE.get()) return;
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !isWarium(victim)) return;
        Entity attacker = resolveAttacker(event.getSource());
        if (attacker == null || attacker == victim) return;
        if (attacker instanceof LivingEntity living && isWarium(living)) return;
        if (victim.getTeam() != null && attacker.getTeam() != null
                && victim.getTeam().getName().equals(attacker.getTeam().getName())) return;

        EntityData.putBoolean(victim, PREFIX + "engaged", true);
        EntityData.putUUID(victim, PREFIX + "attacker", attacker.getUUID());

        LivingEntity proxy = livingProxy(attacker);
        if (proxy == null || proxy == victim || isWarium(proxy)) {
            // Mob#setTarget and Warium's NearestAttackableTargetGoal are LivingEntity-only, so an
            // unmanned vehicle cannot become a target. The attacker UUID and the engaged flag stay
            // published for other systems; there is no living entity to record or target.
            if (CompatConfig.debugEnabled()) {
                LOGGER.info("Warium unit hit by unmanned {}; no living entity to retaliate against",
                        attacker.getType());
            }
            return;
        }
        if (victim instanceof Mob mob) mob.setTarget(proxy);
    }

    /**
     * The {@code engaged} flag and the {@code attacker} UUID are published for other systems, but
     * nothing in this mod reads them. They used to be written once and never cleared, so a unit
     * stayed "engaged" forever after its attacker died or left the level. This pass clears both as
     * soon as the recorded attacker is gone; it costs one lookup and only for engaged entities.
     */
    private static void refreshEngagement(ServerLevel level, Entity entity) {
        net.minecraft.nbt.CompoundTag data = entity.getPersistentData();
        String engagedKey = PREFIX + "engaged";
        String attackerKey = PREFIX + "attacker";
        if (!data.getBoolean(engagedKey)) {
            if (data.contains(attackerKey)) EntityData.remove(entity, attackerKey);
            return;
        }
        if (!data.hasUUID(attackerKey)) {
            EntityData.putBoolean(entity, engagedKey, false);
            return;
        }
        Entity attacker = level.getEntity(data.getUUID(attackerKey));
        if (attacker == null || !attacker.isAlive()) {
            EntityData.putBoolean(entity, engagedKey, false);
            EntityData.remove(entity, attackerKey);
        }
    }

    /**
     * The single per-entity pass. Order matters: classification feeds jamming, and both feed the
     * track update, so the three steps run back to back for each entity.
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        OptionalAddonCompat.tickGuidance();
        int tick = server.getTickCount();
        if (tick % CompatConfig.SCAN_INTERVAL_TICKS.get() != 0) return;
        boolean battlefield = CompatConfig.ENABLE_BATTLEFIELD_SYSTEMS.get();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!isCandidate(entity)) continue;
                classify(entity);
                refreshEngagement(level, entity);
                OptionalAddonCompat.updateJamming(level, entity);
                if (battlefield) BattlefieldSystems.update(level, entity, tick);
            }
        }
        if (battlefield) BattlefieldSystems.endScan(tick);
        CcTweakedCompat.pumpEvents(server, tick);
    }

    // ---------------------------------------------------------------- queries

    public static boolean isAirTarget(Entity entity) {
        return AIR_TAG.equals(entity.getPersistentData().getString(PREFIX + "class"));
    }

    public static boolean isGroundTarget(Entity entity) {
        return GROUND_TAG.equals(entity.getPersistentData().getString(PREFIX + "class"));
    }

    public static boolean isProjectile(Entity entity) {
        return entity instanceof Projectile || entity.getTags().contains(PROJECTILE_TAG);
    }

    public static boolean isWarium(Entity entity) {
        return info(entity).warium() || entity.getTags().contains(WARIUM_TAG);
    }

    /** Guided means the id or class name has a configured keyword, or the id is listed in the config. */
    public static boolean isGuidedProjectile(Entity entity) {
        TypeInfo info = info(entity);
        if (info.profile().guidedWord()) return true;
        String id = info.fullId();
        for (String configured : CompatConfig.GUIDED_IDS.get()) {
            if (configured != null && id.equals(configured.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean isCandidate(Entity entity) {
        return info(entity).candidate() || entity.getTags().contains(WARIUM_TAG);
    }

    // ---------------------------------------------------------------- classification

    private static void classify(Entity entity) {
        if (!isCandidate(entity)) return;
        TypeInfo info = info(entity);
        boolean knownWarium = info.warium() || entity.getTags().contains(WARIUM_TAG);
        // Do not classify every entity from the TaCZ namespace as a projectile;
        // use the actual projectile type or the entity id/class-name heuristics instead.
        boolean projectileInstance = entity instanceof Projectile;
        boolean aircraftFlag = isAircraftFlagged(entity);
        boolean ownerAir = projectileInstance && ownerIsAirPlatform(entity);
        Heuristics.Decision decision = Heuristics.decide(info.profile(), projectileInstance, aircraftFlag, ownerAir);

        boolean projectile = decision.kind() == Heuristics.Kind.PROJECTILE;
        boolean air = decision.kind() == Heuristics.Kind.AIR || decision.airProjectile();
        switch (decision.kind()) {
            case PROJECTILE -> publish(entity, PROJECTILE_TAG, knownWarium,
                    decision.airProjectile() ? "air_projectile" : "ground_projectile");
            case AIR -> publish(entity, AIR_TAG, knownWarium, "air");
            case GROUND -> publish(entity, GROUND_TAG, knownWarium, "ground");
            default -> {
                // Unknown Warium units are deliberately marked unknown, not air, so a ground
                // CIWS will not waste ammunition on them.
                if (knownWarium && entity instanceof LivingEntity && CompatConfig.ENABLE_RUNTIME_TAGS.get()) {
                    publishUnknown(entity, info);
                }
            }
        }
        if (entity.level() instanceof ServerLevel serverLevel) {
            OptionalAddonCompat.inspect(serverLevel, entity, air, projectile);
        }
    }

    private static void publish(Entity entity, String categoryTag, boolean knownWarium, String kind) {
        if (!CompatConfig.ENABLE_RUNTIME_TAGS.get()) return;
        String previous = entity.getPersistentData().getString(PREFIX + "class");
        EntityData.putString(entity, PREFIX + "class", categoryTag);
        EntityData.putString(entity, PREFIX + "kind", kind);
        EntityData.putBoolean(entity, PREFIX + "classified", true);
        if (knownWarium) EntityData.tag(entity, WARIUM_TAG, true);
        EntityData.tag(entity, AIR_TAG, AIR_TAG.equals(categoryTag));
        EntityData.tag(entity, GROUND_TAG, GROUND_TAG.equals(categoryTag));
        EntityData.tag(entity, PROJECTILE_TAG, PROJECTILE_TAG.equals(categoryTag));
        EntityData.putBoolean(entity, PREFIX + "airborne_projectile",
                PROJECTILE_TAG.equals(categoryTag) && kind.equals("air_projectile"));
        if (CompatConfig.debugEnabled() && !categoryTag.equals(previous)) {
            LOGGER.info("Warium-SBW classified {} as {}", info(entity).fullId(), kind);
        }
    }

    private static void publishUnknown(Entity entity, TypeInfo info) {
        boolean changed = !"unknown".equals(entity.getPersistentData().getString(PREFIX + "class"));
        EntityData.tag(entity, AIR_TAG, false);
        EntityData.tag(entity, GROUND_TAG, false);
        EntityData.tag(entity, PROJECTILE_TAG, false);
        EntityData.tag(entity, WARIUM_TAG, true);
        EntityData.putString(entity, PREFIX + "class", "unknown");
        EntityData.putString(entity, PREFIX + "kind", "unknown");
        EntityData.remove(entity, PREFIX + "airborne_projectile");
        EntityData.putBoolean(entity, PREFIX + "classified", true);
        if (changed && CompatConfig.debugEnabled()) {
            LOGGER.info("Warium-SBW classified {} as unknown", info.fullId());
        }
    }

    private static boolean isAircraftFlagged(Entity entity) {
        return entity.getPersistentData().getBoolean(PREFIX + "aircraft")
                || entity.getPersistentData().getBoolean("warium_invasion_v2.aircraft");
    }

    /**
     * True when a projectile's owner is an airborne platform. This is checked from the owner's
     * type as well as its published tag, so it does not depend on scan order: a bomb dropped by an
     * aircraft is air-launched even if the aircraft has not been scanned yet this tick.
     */
    private static boolean ownerIsAirPlatform(Entity entity) {
        if (!(entity instanceof Projectile projectile)) return false;
        Entity owner = projectile.getOwner();
        if (owner == null) return false;
        if (AIR_TAG.equals(owner.getPersistentData().getString(PREFIX + "class"))) return true;
        TypeInfo ownerInfo = info(owner);
        return Heuristics.decide(ownerInfo.profile(), false, isAircraftFlagged(owner), false)
                .kind() == Heuristics.Kind.AIR;
    }

    /**
     * The entity that caused the damage. Superb Warfare vehicles extend {@code Entity}, not
     * {@code LivingEntity}, and {@code ProjectileEntity.shooter(Entity)} accepts any entity, so the
     * owner of a vehicle-fired projectile is the vehicle itself. The old code required a
     * {@code LivingEntity} here and therefore returned null for every vehicle shot, which is why
     * Warium units never retaliated.
     */
    private static Entity resolveAttacker(DamageSource source) {
        Entity direct = source.getDirectEntity();
        if (direct instanceof Projectile projectile && projectile.getOwner() != null) {
            return projectile.getOwner();
        }
        return source.getEntity();
    }

    /**
     * A living stand-in for the attacker. Vanilla {@code Mob#setTarget} and Warium's
     * {@code NearestAttackableTargetGoal} accept only living entities, so for a vehicle the best
     * available target is its driver or gunner. Returns null when the vehicle is unmanned.
     */
    private static LivingEntity livingProxy(Entity attacker) {
        if (attacker instanceof LivingEntity living) return living;
        Entity passenger = attacker.getFirstPassenger();
        return passenger instanceof LivingEntity living ? living : null;
    }
}
