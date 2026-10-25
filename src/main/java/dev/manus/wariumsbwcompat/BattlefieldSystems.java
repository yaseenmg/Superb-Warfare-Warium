package dev.manus.wariumsbwcompat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side track book. For every classified threat it keeps a track with a state
 * (tracking, track_lost, kill_confirmed), a last known position and a heuristic confidence.
 * The data is published on the entity and, for lost or destroyed targets that no longer have an
 * entity, through {@link #snapshot}, which the CC:Tweaked peripheral reads.
 *
 * Confidence is an estimate for scripts to rank contacts by, not a physical radar model: it counts
 * how many installed add-ons also recognise the target and lowers the value for jamming, decoys,
 * weather and cover.
 */
public final class BattlefieldSystems {
    public static final String STATE_TRACKING = "tracking";
    public static final String STATE_LOST = "track_lost";
    public static final String STATE_KILL = "kill_confirmed";
    private static final String DATA = SbwWariumBridge.PREFIX + "battlefield.";
    private static final String IFF_HOSTILE = "hostile";
    private static final String IFF_FRIENDLY = "friendly";
    private static final String IFF_UNKNOWN = "unknown";
    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    /** Immutable copy of a track, safe to hand to other threads' result conversion. */
    public record TrackView(String id, String dimension, String type, String classification, String iff,
                            String state, double confidence, int priority, double threatScore,
                            int sensorCount, double x, double y, double z,
                            double vx, double vy, double vz, long ageTicks) {}

    private BattlefieldSystems() {}

    // ---------------------------------------------------------------- scan hooks

    /** Called by the bridge's single scan pass for each candidate entity. */
    static void update(ServerLevel level, Entity entity, long tick) {
        if (!relevant(entity) || !CompatConfig.ENABLE_IFF_AND_TRACK_FUSION.get() || !entity.isAlive()) return;
        Track track = TRACKS.computeIfAbsent(entity.getUUID(), ignored -> new Track());
        String iff = iff(entity);
        boolean decoy = isDecoy(entity);
        String classification = entity.getPersistentData().getString(SbwWariumBridge.PREFIX + "class");
        int jam = entity.getPersistentData().getInt(SbwWariumBridge.PREFIX + "optional.jamming.level");
        int sensors = 1;
        if (entity.getPersistentData().getBoolean(SbwWariumBridge.PREFIX + "optional.air_defense.air_target")) sensors++;
        if (entity.getPersistentData().getBoolean(SbwWariumBridge.PREFIX + "optional.deepstrike.target")) sensors++;

        double confidence = Math.min(1.0D, 0.45D + sensors * 0.2D);
        if (jam > 0) confidence -= jam * 0.12D;
        if (decoy) confidence *= 0.25D;
        if (CompatConfig.ENABLE_WEATHER_TERRAIN_EFFECTS.get()) confidence *= environmentFactor(level, entity);
        confidence = Math.round(Math.max(0.05D, Math.min(1.0D, confidence)) * 100.0D) / 100.0D;

        int priority = priority(entity, classification, decoy);
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        Vec3 velocity = entity.getDeltaMovement();
        track.dimension = level.dimension().location().toString();
        track.type = id == null ? "unknown" : id.toString();
        track.lastSeenTick = tick;
        track.iff = iff;
        track.classification = classification.isEmpty() ? "unknown" : classification;
        track.confidence = confidence;
        track.sensorCount = sensors;
        track.priority = priority;
        track.threatScore = Math.round(priority * confidence * 100.0D) / 100.0D;
        track.state = STATE_TRACKING;
        track.x = entity.getX();
        track.y = entity.getY();
        track.z = entity.getZ();
        track.vx = velocity.x;
        track.vy = velocity.y;
        track.vz = velocity.z;

        EntityData.putString(entity, DATA + "iff", iff);
        EntityData.tag(entity, "sbw_iff_hostile", IFF_HOSTILE.equals(iff));
        EntityData.tag(entity, "sbw_iff_friendly", IFF_FRIENDLY.equals(iff));
        EntityData.tag(entity, "sbw_iff_unknown", IFF_UNKNOWN.equals(iff));
        EntityData.putBoolean(entity, DATA + "decoy", decoy);
        EntityData.putInt(entity, DATA + "sensor_count", sensors);
        EntityData.putDouble(entity, DATA + "confidence", confidence);
        EntityData.putInt(entity, DATA + "priority", priority);
        EntityData.putDouble(entity, DATA + "threat_score", track.threatScore);
        EntityData.putString(entity, DATA + "state", STATE_TRACKING);
        // Emission control has two sources: the peripheral's set_emcon on a radar block, and
        // /wariumcompat emcon on any entity. Both write the same key, so a unit silenced by the
        // command stops advertising itself here and in the contact list.
        boolean emitting = !entity.getPersistentData().getBoolean(SbwWariumBridge.PREFIX + "silent_emission");
        EntityData.putBoolean(entity, DATA + "radar_emitting", emitting);
        EntityData.tag(entity, "sbw_emitting", emitting);
    }

    /** Ages tracks that were not refreshed by this scan and deletes expired ones. */
    static void endScan(long tick) {
        Iterator<Map.Entry<UUID, Track>> iterator = TRACKS.entrySet().iterator();
        while (iterator.hasNext()) {
            Track track = iterator.next().getValue();
            if (STATE_TRACKING.equals(track.state)
                    && tick - track.lastSeenTick > CompatConfig.TRACK_TIMEOUT_TICKS.get()) {
                markLost(track, tick);
            } else if (STATE_LOST.equals(track.state)) {
                if (tick - track.lostAtTick > CompatConfig.TRACK_EXPIRE_TICKS.get()) iterator.remove();
                else decay(track);
            } else if (STATE_KILL.equals(track.state)
                    && tick - track.lastSeenTick > CompatConfig.TRACK_EXPIRE_TICKS.get()) {
                iterator.remove();
            }
        }
    }

    // ---------------------------------------------------------------- events

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TRACKS.clear();
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!CompatConfig.ENABLE_BATTLEFIELD_SYSTEMS.get()) return;
        Track track = TRACKS.get(event.getEntity().getUUID());
        if (track != null) markKill(track, event.getEntity());
    }

    /**
     * Covers targets that have no death event: vehicles and other non-living entities killed by
     * damage, and projectiles that are consumed on impact (their track is simply dropped).
     */
    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide || !CompatConfig.ENABLE_BATTLEFIELD_SYSTEMS.get()) return;
        Entity entity = event.getEntity();
        Track track = TRACKS.get(entity.getUUID());
        if (track == null) return;
        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason == Entity.RemovalReason.KILLED) {
            markKill(track, entity);
        } else if (reason == Entity.RemovalReason.DISCARDED) {
            if (entity instanceof Projectile) TRACKS.remove(entity.getUUID());
            else markLost(track, tickOf(entity));
        }
        // Unloaded or moved between dimensions: keep tracking, it becomes track_lost on timeout.
    }

    // ---------------------------------------------------------------- queries

    /**
     * Copy of the track book. A null dimension returns every track. Must be called on the server
     * thread, like everything else that touches the book.
     */
    public static List<TrackView> snapshot(String dimension, long nowTick) {
        List<TrackView> result = new ArrayList<>(TRACKS.size());
        for (Map.Entry<UUID, Track> entry : TRACKS.entrySet()) {
            Track t = entry.getValue();
            if (dimension != null && !dimension.equals(t.dimension)) continue;
            result.add(new TrackView(entry.getKey().toString(), t.dimension, t.type, t.classification,
                    t.iff, t.state, t.confidence, t.priority, t.threatScore, t.sensorCount,
                    t.x, t.y, t.z, t.vx, t.vy, t.vz, Math.max(0L, nowTick - t.lastSeenTick)));
        }
        return result;
    }

    // ---------------------------------------------------------------- internals

    private static void markKill(Track track, Entity entity) {
        track.state = STATE_KILL;
        track.lastSeenTick = tickOf(entity);
        track.x = entity.getX();
        track.y = entity.getY();
        track.z = entity.getZ();
        EntityData.putString(entity, DATA + "assessment", STATE_KILL);
        EntityData.putString(entity, DATA + "state", STATE_KILL);
    }

    private static void markLost(Track track, long tick) {
        track.state = STATE_LOST;
        track.lostAtTick = tick;
        track.confidence = Math.max(0.01D, track.confidence * 0.5D);
        track.threatScore = Math.round(track.priority * track.confidence * 100.0D) / 100.0D;
    }

    /** A lost track fades a little on every scan until it expires. */
    private static void decay(Track track) {
        track.confidence = Math.max(0.01D, Math.round(track.confidence * 0.9D * 100.0D) / 100.0D);
        track.threatScore = Math.round(track.priority * track.confidence * 100.0D) / 100.0D;
    }

    private static long tickOf(Entity entity) {
        MinecraftServer server = entity.getServer();
        return server == null ? entity.level().getGameTime() : server.getTickCount();
    }

    private static boolean relevant(Entity entity) {
        if (entity.getPersistentData().contains(SbwWariumBridge.PREFIX + "class")) return true;
        if (entity.getTags().contains(SbwWariumBridge.WARIUM_TAG)) return true;
        if (entity instanceof Projectile) {
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            return id != null && (id.getNamespace().contains("warium") || id.getNamespace().equals("crusty_chunks"));
        }
        return false;
    }

    /**
     * Friend or foe from what is actually available: the Warium tag, scoreboard team names, the
     * owner's team for projectiles, then the entity namespace. Team matching is by name only.
     */
    private static String iff(Entity entity) {
        if (entity.getTags().contains(SbwWariumBridge.WARIUM_TAG)) return IFF_HOSTILE;
        if (entity.getTeam() != null) {
            String byTeam = teamIff(entity.getTeam().getName());
            if (byTeam != null) return byTeam;
        }
        if (entity instanceof Projectile projectile && projectile.getOwner() != null
                && projectile.getOwner().getTeam() != null) {
            String byOwner = teamIff(projectile.getOwner().getTeam().getName());
            if (byOwner != null) return byOwner;
        }
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (id == null) return IFF_UNKNOWN;
        String namespace = id.getNamespace().toLowerCase(Locale.ROOT);
        if (namespace.equals("crusty_chunks") || namespace.contains("warium")) return IFF_HOSTILE;
        if (namespace.equals("superbwarfare") || namespace.equals("sbw_air_defense")
                || namespace.equals("sbwdroneconfig") || namespace.equals("vvp")) return IFF_FRIENDLY;
        return IFF_UNKNOWN;
    }

    private static String teamIff(String teamName) {
        String team = teamName.toLowerCase(Locale.ROOT);
        if (team.contains("hostile") || team.contains("enemy") || team.contains("warium")) return IFF_HOSTILE;
        if (team.contains("friendly") || team.contains("ally") || team.contains("allied")) return IFF_FRIENDLY;
        return null;
    }

    /**
     * Decoy status comes from the external scoreboard tags only. The previous version also read
     * {@code battlefield.decoy}, which is the key this class writes, so the first decoy scan made
     * the flag permanent: removing {@code sbw_chaff} could never restore confidence.
     */
    private static boolean isDecoy(Entity entity) {
        return entity.getTags().contains("sbw_chaff") || entity.getTags().contains("sbw_flare")
                || entity.getTags().contains("sbw_decoy");
    }

    private static int priority(Entity entity, String classification, boolean decoy) {
        if (decoy) return 5;
        if (entity instanceof Projectile || SbwWariumBridge.PROJECTILE_TAG.equals(classification)) return 100;
        if (SbwWariumBridge.AIR_TAG.equals(classification)) return 80;
        if (SbwWariumBridge.GROUND_TAG.equals(classification)) return 50;
        if (entity instanceof LivingEntity) return 30;
        return 20;
    }

    private static double environmentFactor(ServerLevel level, Entity entity) {
        double factor = 1.0D;
        factor -= level.getRainLevel(1.0F) * 0.08D;
        factor -= level.getThunderLevel(1.0F) * 0.12D;
        if (entity.getY() < 96.0D && !level.canSeeSky(entity.blockPosition())) factor -= 0.12D;
        return Math.max(0.55D, factor);
    }

    private static final class Track {
        private long lastSeenTick;
        private long lostAtTick;
        private String dimension = "";
        private String type = "unknown";
        private String iff = IFF_UNKNOWN;
        private String classification = "unknown";
        private String state = STATE_TRACKING;
        private double confidence;
        private double threatScore;
        private int sensorCount;
        private int priority;
        private double x, y, z, vx, vy, vz;

        private Track() {}
    }
}
