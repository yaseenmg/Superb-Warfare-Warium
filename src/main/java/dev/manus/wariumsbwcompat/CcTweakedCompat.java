package dev.manus.wariumsbwcompat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Optional CC:Tweaked bridge using the 1.20.x IDynamicPeripheral contract. Everything is
 * reflective, so CC:Tweaked is never a hard dependency and any API mismatch leaves the integration
 * disabled instead of crashing.
 *
 * The peripheral is named "warium_sbw" and is offered on radar and jammer block entities of other
 * add-ons. It behaves like a radar console: a silent (EMCON) or unpowered radar returns nothing,
 * jamming at the radar block shortens its range, and an optional line-of-sight check applies.
 * Advanced Peripherals computers are CC:Tweaked computers, so they use this peripheral unchanged.
 *
 * Lua methods: get_status, get_contacts, get_jamming, set_emcon, get_power, get_emcon,
 * get_tracks, get_threats, get_contact, get_config, get_version. The event warium_sbw_contact is
 * queued on attached computers when a new hostile contact appears.
 */
public final class CcTweakedCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("warium_sbw_compat/computercraft");
    private static final String[] METHOD_NAMES = {
            "get_status", "get_contacts", "get_jamming", "set_emcon", "get_power",
            "get_emcon", "get_tracks", "get_threats", "get_contact", "get_config", "get_version"};
    private static final String[] API_CLASSES = {
            "dan200.computercraft.api.ForgeComputerCraftAPI",
            "dan200.computercraft.api.ComputerCraftAPI"
    };
    private static final String EVENT_CONTACT = "warium_sbw_contact";
    private static final int MAX_CONTACTS = 512;
    private static final int EVENTS_PER_PULSE = 8;
    private static final long CONTACT_CACHE_TICKS = 5L;
    private static final String SILENT_KEY = SbwWariumBridge.PREFIX + "silent_emission";

    private static final Map<ServerLevel, Map<BlockPos, ContactCache>> CONTACT_CACHE = new WeakHashMap<>();
    private static final Map<Class<?>, Optional<Method>> POWER_METHODS = new ConcurrentHashMap<>();
    private static final Map<BlockEntity, Set<Object>> ATTACHED = new WeakHashMap<>();
    private static final Map<BlockEntity, Set<String>> ANNOUNCED = new WeakHashMap<>();
    private static ClassLoader ccLoader;
    private static Method queueEventMethod;
    private static boolean initialized;
    private static boolean available;

    private CcTweakedCompat() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        if (!ModList.get().isLoaded("computercraft")) return;
        try {
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            final ClassLoader loader = contextLoader == null ? CcTweakedCompat.class.getClassLoader() : contextLoader;
            Class<?> providerType = Class.forName("dan200.computercraft.api.peripheral.IPeripheralProvider", false, loader);
            Object provider = Proxy.newProxyInstance(providerType.getClassLoader(), new Class<?>[]{providerType},
                    (proxy, method, args) -> handleProvider(proxy, method, args, loader));
            findRegisterMethod(providerType, loader).invoke(null, provider);
            ccLoader = loader;
            available = true;
        } catch (Throwable error) {
            // CC:Tweaked is present but its API did not match. This is not fatal, but it must not
            // be silent: a version bump would otherwise disable the peripheral with no trace.
            available = false;
            LOGGER.warn("CC:Tweaked is installed but the warium_sbw peripheral provider could not be registered; the peripheral is disabled.", error);
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    private static Method findRegisterMethod(Class<?> providerType, ClassLoader loader) throws NoSuchMethodException {
        for (String name : API_CLASSES) {
            try {
                Class<?> api = Class.forName(name, false, loader);
                return api.getMethod("registerPeripheralProvider", providerType);
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                // Try the next known location of the registration method.
            }
        }
        throw new NoSuchMethodException("registerPeripheralProvider");
    }

    /**
     * IPeripheralProvider#getPeripheral returns a LazyOptional on Forge 1.20.1, so the proxy
     * must never return a bare peripheral (or null) for that return type.
     */
    private static Object handleProvider(Object proxy, Method method, Object[] args, ClassLoader loader) throws Exception {
        switch (method.getName()) {
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return args != null && args.length == 1 && proxy == args[0];
            case "toString":
                return "WariumSbwPeripheralProvider";
            default:
                break;
        }
        Object peripheral = null;
        if ("getPeripheral".equals(method.getName()) && args != null && args.length >= 2
                && args[0] instanceof Level level && args[1] instanceof BlockPos pos) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (isSupported(blockEntity)) peripheral = createPeripheral(blockEntity, level, pos, loader);
        }
        if (method.getReturnType() == LazyOptional.class) {
            if (peripheral == null) return LazyOptional.empty();
            final Object value = peripheral;
            return LazyOptional.of(() -> value);
        }
        return peripheral;
    }

    /** Radar and jammer block entities of other add-ons; this mod adds no blocks of its own. */
    private static boolean isSupported(BlockEntity blockEntity) {
        if (blockEntity == null) return false;
        String name = blockEntity.getClass().getName();
        return name.contains("JammerBlockEntity") || name.contains("RadarJammer")
                || name.contains("TacticalRadar") || name.contains("RadarBlockEntity");
    }

    private static Object createPeripheral(BlockEntity blockEntity, Level level, BlockPos pos, ClassLoader loader) throws ClassNotFoundException {
        Class<?> peripheralType = Class.forName("dan200.computercraft.api.peripheral.IPeripheral", false, loader);
        Class<?> dynamicType = Class.forName("dan200.computercraft.api.peripheral.IDynamicPeripheral", false, loader);
        return Proxy.newProxyInstance(peripheralType.getClassLoader(), new Class<?>[]{peripheralType, dynamicType},
                new PeripheralHandler(blockEntity, level, pos, loader));
    }

    /** Handler with a stable equals(), so CC does not detach/re-attach the peripheral every tick. */
    private static final class PeripheralHandler implements InvocationHandler {
        private final BlockEntity blockEntity;
        private final Level level;
        private final BlockPos pos;
        private final ClassLoader loader;

        private PeripheralHandler(BlockEntity blockEntity, Level level, BlockPos pos, ClassLoader loader) {
            this.blockEntity = blockEntity;
            this.level = level;
            this.pos = pos;
            this.loader = loader;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "getType":
                    return "warium_sbw";
                case "getTarget":
                    return blockEntity;
                case "getMethodNames":
                    return METHOD_NAMES.clone();
                case "getAdditionalTypes":
                    return Set.of();
                case "attach":
                    if (args != null && args.length > 0 && args[0] != null) attachComputer(blockEntity, args[0]);
                    return null;
                case "detach":
                    if (args != null && args.length > 0 && args[0] != null) detachComputer(blockEntity, args[0]);
                    return null;
                case "equals":
                    return args != null && args.length == 1 && sameTarget(args[0]);
                case "hashCode":
                    return System.identityHashCode(blockEntity);
                case "toString":
                    return "warium_sbw peripheral @ " + pos;
                case "callMethod":
                    return callMethod(args);
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private boolean sameTarget(Object other) {
            if (other == null || !Proxy.isProxyClass(other.getClass())) return false;
            return Proxy.getInvocationHandler(other) instanceof PeripheralHandler handler
                    && handler.blockEntity == blockEntity;
        }

        /** callMethod(IComputerAccess, ILuaContext, int, IArguments) must return a MethodResult. */
        private Object callMethod(Object[] args) throws Exception {
            if (args == null || args.length < 4 || !(args[2] instanceof Number index) || args[3] == null) {
                return methodResult(loader, false, "invalid peripheral call");
            }
            final Object arguments = args[3];
            final int methodIndex = index.intValue();
            Object[] values = runOnServerThread(
                    () -> perform(blockEntity, level, pos, methodIndex, arguments, loader));
            return methodResult(loader, values);
        }

        /** World and block entity reads must happen on the server thread, never on a computer thread. */
        private Object[] runOnServerThread(Supplier<Object[]> task) throws InterruptedException {
            MinecraftServer server = level.getServer();
            if (server == null || server.isSameThread()) return task.get();
            try {
                return server.submit(task).get(5L, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException error) {
                return new Object[]{false, "server thread did not answer in time"};
            }
        }
    }

    // ---------------------------------------------------------------- methods

    private static Object[] perform(BlockEntity blockEntity, Level level, BlockPos pos,
                                    int index, Object arguments, ClassLoader loader) {
        try {
            return switch (index) {
                case 0 -> new Object[]{status(blockEntity, level, pos)};
                case 1 -> contactsCall(blockEntity, level, pos, arguments, loader, "distance", MAX_CONTACTS);
                case 2 -> new Object[]{jammingAt(level, pos, blockEntity)};
                case 3 -> setEmcon(blockEntity, argument(arguments, 0, loader));
                case 4 -> new Object[]{power(blockEntity)};
                case 5 -> new Object[]{blockEntity.getPersistentData().getBoolean(SILENT_KEY)};
                case 6 -> tracksCall(blockEntity, level, pos, arguments, loader);
                case 7 -> contactsCall(blockEntity, level, pos, arguments, loader, "threat", 5);
                case 8 -> contactCall(blockEntity, level, pos, arguments, loader);
                case 9 -> new Object[]{config()};
                case 10 -> new Object[]{modVersion()};
                default -> new Object[]{false, "unknown method"};
            };
        } catch (Throwable error) {
            return new Object[]{false, error.getMessage() == null ? "peripheral operation failed" : error.getMessage()};
        }
    }

    private static Object[] setEmcon(BlockEntity blockEntity, Object value) {
        if (!(value instanceof Boolean silent)) return new Object[]{false, "expected boolean"};
        EntityData.putBoolean(blockEntity, SILENT_KEY, silent);
        blockEntity.setChanged();
        return new Object[]{true, silent};
    }

    private static Object[] contactsCall(BlockEntity be, Level level, BlockPos pos, Object arguments,
                                         ClassLoader loader, String defaultSort, int defaultLimit) throws Exception {
        Options options = parseOptions(arguments, loader, defaultSort, defaultLimit);
        Radar radar = radar(level, pos, be, options.range());
        if (!(level instanceof ServerLevel serverLevel)) return new Object[]{new ArrayList<>()};
        if (!radar.active()) return new Object[]{new ArrayList<>(), radar.reason()};
        List<Contact> selected = select(rawContacts(serverLevel, pos, radar.baseRange()), options, radar);
        List<Map<String, Object>> out = new ArrayList<>(selected.size());
        for (Contact contact : selected) out.add(contact.toMap());
        return new Object[]{out};
    }

    private static Object[] contactCall(BlockEntity be, Level level, BlockPos pos, Object arguments,
                                        ClassLoader loader) throws Exception {
        Object id = argument(arguments, 0, loader);
        if (!(id instanceof String uuid)) return new Object[]{false, "expected uuid string"};
        Radar radar = radar(level, pos, be, CompatConfig.CC_MAX_RANGE.get());
        if (!(level instanceof ServerLevel serverLevel)) return new Object[]{null};
        if (!radar.active()) return new Object[]{null, radar.reason()};
        for (Contact contact : rawContacts(serverLevel, pos, radar.baseRange())) {
            if (contact.uuid().equals(uuid) && contact.distance() <= radar.range()) return new Object[]{contact.toMap()};
        }
        long now = serverLevel.getServer().getTickCount();
        for (BattlefieldSystems.TrackView view : BattlefieldSystems.snapshot(dimension(serverLevel), now)) {
            Map<String, Object> map = trackMap(view, pos);
            if (view.id().equals(uuid)
                    && ((Number) map.get("distance")).doubleValue() <= radar.range()) {
                return new Object[]{map};
            }
        }
        return new Object[]{null};
    }

    private static Object[] tracksCall(BlockEntity be, Level level, BlockPos pos, Object arguments,
                                       ClassLoader loader) throws Exception {
        Options options = parseOptions(arguments, loader, "distance", MAX_CONTACTS);
        Radar radar = radar(level, pos, be, options.range());
        if (!(level instanceof ServerLevel serverLevel)) return new Object[]{new ArrayList<>()};
        if (!radar.active()) return new Object[]{new ArrayList<>(), radar.reason()};
        long now = serverLevel.getServer().getTickCount();
        List<Map<String, Object>> out = new ArrayList<>();
        for (BattlefieldSystems.TrackView view : BattlefieldSystems.snapshot(dimension(serverLevel), now)) {
            Map<String, Object> map = trackMap(view, pos);
            if (((Number) map.get("distance")).doubleValue() > radar.range()) continue;
            if (!options.matches(view.classification(), view.iff(), view.confidence(), view.state())) continue;
            out.add(map);
        }
        Comparator<Map<String, Object>> comparator = switch (options.sort()) {
            case "threat" -> Comparator.comparingDouble(m -> ((Number) m.get("threat_score")).doubleValue());
            case "priority" -> Comparator.comparingInt(m -> ((Number) m.get("priority")).intValue());
            case "confidence" -> Comparator.comparingDouble(m -> ((Number) m.get("confidence")).doubleValue());
            default -> Comparator.comparingDouble(m -> ((Number) m.get("distance")).doubleValue());
        };
        out.sort(options.sort().equals("distance")
                ? comparator
                : comparator.reversed().thenComparingDouble(m -> ((Number) m.get("distance")).doubleValue()));
        if (out.size() > options.limit()) out = new ArrayList<>(out.subList(0, options.limit()));
        return new Object[]{out};
    }

    private static Map<String, Object> trackMap(BattlefieldSystems.TrackView view, BlockPos origin) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uuid", view.id());
        map.put("type", view.type());
        map.put("class", view.classification());
        map.put("iff", view.iff());
        map.put("state", view.state());
        map.put("confidence", view.confidence());
        map.put("priority", view.priority());
        map.put("threat_score", view.threatScore());
        map.put("sensors", view.sensorCount());
        map.put("x", view.x());
        map.put("y", view.y());
        map.put("z", view.z());
        map.put("vx", view.vx());
        map.put("vy", view.vy());
        map.put("vz", view.vz());
        map.put("age_ticks", view.ageTicks());
        double dx = view.x() - (origin.getX() + 0.5D);
        double dy = view.y() - (origin.getY() + 0.5D);
        double dz = view.z() - (origin.getZ() + 0.5D);
        map.put("distance", Math.sqrt(dx * dx + dy * dy + dz * dz));
        return map;
    }

    private static Map<String, Object> status(BlockEntity blockEntity, Level level, BlockPos pos) {
        Radar radar = radar(level, pos, blockEntity, CompatConfig.CC_MAX_RANGE.get());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", blockEntity.getClass().getName());
        result.put("x", pos.getX());
        result.put("y", pos.getY());
        result.put("z", pos.getZ());
        result.put("emcon", blockEntity.getPersistentData().getBoolean(SILENT_KEY));
        result.put("emitting", radar.emitting());
        result.put("powered", radar.powered());
        result.put("jam_level", radar.jamLevel());
        result.put("effective_range", radar.range());
        result.put("line_of_sight", CompatConfig.RADAR_LINE_OF_SIGHT.get());
        result.put("cc_tweaked", true);
        result.put("version", modVersion());
        result.put("integrations", ModIntegrationRegistry.status());
        result.put("energy", ForgeEnergyCompat.status(blockEntity));
        return result;
    }

    private static Map<String, Object> power(BlockEntity blockEntity) {
        Map<String, Object> result = new LinkedHashMap<>(ForgeEnergyCompat.status(blockEntity));
        result.put("powered", isPowered(blockEntity));
        return result;
    }

    private static Map<String, Object> config() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("max_range", CompatConfig.CC_MAX_RANGE.get());
        result.put("scan_interval_ticks", CompatConfig.SCAN_INTERVAL_TICKS.get());
        result.put("contact_events", CompatConfig.CC_EVENTS.get());
        result.put("contact_event_range", CompatConfig.CC_EVENT_RANGE.get());
        result.put("line_of_sight", CompatConfig.RADAR_LINE_OF_SIGHT.get());
        result.put("max_line_of_sight_checks", CompatConfig.MAX_LINE_OF_SIGHT_CHECKS.get());
        result.put("jam_range_penalty_per_level", CompatConfig.RADAR_JAM_RANGE_PENALTY.get());
        result.put("track_timeout_ticks", CompatConfig.TRACK_TIMEOUT_TICKS.get());
        result.put("track_expire_ticks", CompatConfig.TRACK_EXPIRE_TICKS.get());
        return result;
    }

    private static String modVersion() {
        return ModList.get().getModContainerById(WariumSbwCompat.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    // ---------------------------------------------------------------- radar model

    /** State of the radar block for one call. Jamming at the block shortens its range. */
    private record Radar(boolean powered, boolean emitting, int jamLevel, double baseRange, double range) {
        boolean active() {
            return powered && emitting;
        }

        String reason() {
            return !powered ? "radar has no power" : "radar is silent (emcon)";
        }
    }

    private static Radar radar(Level level, BlockPos pos, BlockEntity blockEntity, double requested) {
        boolean emitting = !blockEntity.getPersistentData().getBoolean(SILENT_KEY);
        boolean powered = isPowered(blockEntity);
        int jam = jammingAt(level, pos, blockEntity);
        double base = Math.max(8.0D, Math.min(requested, CompatConfig.CC_MAX_RANGE.get()));
        double factor = Math.max(0.25D, 1.0D - CompatConfig.RADAR_JAM_RANGE_PENALTY.get() * jam);
        return new Radar(powered, emitting, jam, base, base * factor);
    }

    /**
     * Jammer level covering this block. A jammer block never jams its own radar, so it always reads
     * zero for itself. Returns 0 when the EW addon is absent or the level is not a server level.
     */
    private static int jammingAt(Level level, BlockPos pos, BlockEntity blockEntity) {
        if (!(level instanceof ServerLevel serverLevel)) return 0;
        if (OptionalAddonCompat.isJammer(blockEntity)) return 0;
        return OptionalAddonCompat.jammerStrengthAt(serverLevel,
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    /** Uses hasOperationalPower() when the block has it, otherwise an empty Forge Energy buffer. */
    private static boolean isPowered(BlockEntity blockEntity) {
        Method method = POWER_METHODS.computeIfAbsent(blockEntity.getClass(), CcTweakedCompat::findPowerMethod).orElse(null);
        if (method != null) {
            try {
                Object value = method.invoke(blockEntity);
                if (value instanceof Boolean powered) return powered;
            } catch (Throwable ignored) {
                // Fall back to the energy capability.
            }
        }
        return ForgeEnergyCompat.isPowered(blockEntity);
    }

    private static Optional<Method> findPowerMethod(Class<?> type) {
        try {
            return Optional.of(type.getMethod("hasOperationalPower"));
        } catch (NoSuchMethodException error) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- contacts

    private record Contact(String uuid, String type, String cls, String iff, double confidence, int priority,
                           double threat, double x, double y, double z, double distance,
                           double vx, double vy, double vz, int jam, boolean decoy, boolean emitting) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("uuid", uuid);
            map.put("type", type);
            map.put("class", cls);
            map.put("iff", iff);
            map.put("confidence", confidence);
            map.put("priority", priority);
            map.put("threat_score", threat);
            map.put("x", x);
            map.put("y", y);
            map.put("z", z);
            map.put("distance", distance);
            map.put("vx", vx);
            map.put("vy", vy);
            map.put("vz", vz);
            map.put("jam_level", jam);
            map.put("decoy", decoy);
            map.put("emitting", emitting);
            return map;
        }
    }

    private record ContactCache(long tick, double range, List<Contact> contacts) {}

    /** Classified entities around the radar, nearest first, cached for a few ticks per radar block. */
    private static List<Contact> rawContacts(ServerLevel level, BlockPos origin, double range) {
        long now = level.getGameTime();
        synchronized (CONTACT_CACHE) {
            Map<BlockPos, ContactCache> levelCache = CONTACT_CACHE.computeIfAbsent(level, ignored -> new HashMap<>());
            ContactCache cached = levelCache.get(origin);
            if (cached != null && Math.abs(cached.range() - range) < 0.01D
                    && now - cached.tick() < CONTACT_CACHE_TICKS) return cached.contacts();
            List<Contact> fresh = collectContacts(level, origin, range);
            levelCache.put(origin, new ContactCache(now, range, fresh));
            if (levelCache.size() > 256) levelCache.entrySet().removeIf(entry -> now - entry.getValue().tick() > CONTACT_CACHE_TICKS * 4L);
            return fresh;
        }
    }

    private static List<Contact> collectContacts(ServerLevel level, BlockPos origin, double range) {
        double cx = origin.getX() + 0.5D;
        double cy = origin.getY() + 0.5D;
        double cz = origin.getZ() + 0.5D;
        List<Contact> found = new ArrayList<>();
        List<Entity> entities = new ArrayList<>();
        for (Entity entity : level.getEntitiesOfClass(Entity.class, new AABB(origin).inflate(range))) {
            if (!entity.isAlive()) continue;
            String cls = entity.getPersistentData().getString(SbwWariumBridge.PREFIX + "class");
            String iff = entity.getPersistentData().getString(SbwWariumBridge.PREFIX + "battlefield.iff");
            if (cls.isEmpty() && iff.isEmpty()) continue;
            double dx = entity.getX() - cx;
            double dy = entity.getY() - cy;
            double dz = entity.getZ() - cz;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > range) continue;
            var data = entity.getPersistentData();
            String prefix = SbwWariumBridge.PREFIX;
            Vec3 velocity = entity.getDeltaMovement();
            var typeKey = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            found.add(new Contact(entity.getUUID().toString(), typeKey == null ? "unknown" : typeKey.toString(),
                    cls.isEmpty() ? "unknown" : cls, iff.isEmpty() ? "unknown" : iff,
                    data.getDouble(prefix + "battlefield.confidence"), data.getInt(prefix + "battlefield.priority"),
                    data.getDouble(prefix + "battlefield.threat_score"),
                    entity.getX(), entity.getY(), entity.getZ(), distance, velocity.x, velocity.y, velocity.z,
                    data.getInt(prefix + "optional.jamming.level"), data.getBoolean(prefix + "battlefield.decoy"),
                    !data.getBoolean(prefix + "silent_emission")));
            entities.add(entity);
        }
        // Sort contacts and entities together, nearest first, so the line-of-sight pass is nearest first.
        Integer[] order = new Integer[found.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, Comparator.comparingDouble(i -> found.get(i).distance()));
        List<Contact> sorted = new ArrayList<>(found.size());
        boolean lineOfSight = CompatConfig.RADAR_LINE_OF_SIGHT.get();
        int losBudget = CompatConfig.MAX_LINE_OF_SIGHT_CHECKS.get();
        int losChecks = 0;
        Vec3 from = new Vec3(cx, origin.getY() + 1.05D, cz);
        for (int index : order) {
            if (sorted.size() >= MAX_CONTACTS) break;
            // Examine candidates nearest-first. The ray-cast budget caps the cost of one call; once
            // it is used up the remaining contacts are returned unchecked (fail open), so a large
            // contact list is never silently truncated by the LOS filter.
            if (lineOfSight && losChecks < losBudget) {
                losChecks++;
                if (!visible(level, from, entities.get(index))) continue;
            }
            sorted.add(found.get(index));
        }
        return Collections.unmodifiableList(sorted);
    }

    /** The ray starts just above the radar block so the block itself does not hide everything. */
    private static boolean visible(ServerLevel level, Vec3 from, Entity target) {
        try {
            Vec3 to = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
            return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target))
                    .getType() == HitResult.Type.MISS;
        } catch (Throwable error) {
            // LOS is an explicit filtering option: fail closed if the ray cast cannot be evaluated.
            return false;
        }
    }

    private static List<Contact> select(List<Contact> raw, Options options, Radar radar) {
        List<Contact> out = new ArrayList<>();
        for (Contact contact : raw) {
            if (contact.distance() > radar.range()) continue;
            // Contacts are live entities, so their track state is always tracking; passing it makes a
            // {state="track_lost"} filter return nothing instead of silently returning everything.
            if (!options.matches(contact.cls(), contact.iff(), contact.confidence(), BattlefieldSystems.STATE_TRACKING)) continue;
            out.add(contact);
        }
        switch (options.sort()) {
            case "threat" -> out.sort(Comparator.comparingDouble(Contact::threat).reversed());
            case "priority" -> out.sort(Comparator.comparingInt(Contact::priority).reversed());
            case "confidence" -> out.sort(Comparator.comparingDouble(Contact::confidence).reversed());
            default -> { /* already nearest first */ }
        }
        return out.size() > options.limit() ? new ArrayList<>(out.subList(0, options.limit())) : out;
    }

    // ---------------------------------------------------------------- arguments

    /**
     * Options from Lua: a number is the range, a second number the limit, and a table may hold
     * range, class (air, ground, projectile or a full tag), iff, state, min_confidence, limit, sort.
     */
    private record Options(double range, String cls, String iff, String state, double minConfidence,
                           int limit, String sort) {
        boolean matches(String entryClass, String entryIff, double confidence, String entryState) {
            if (cls != null && !cls.equals(entryClass)) return false;
            if (iff != null && !iff.equals(entryIff)) return false;
            if (state != null && entryState != null && !state.equals(entryState)) return false;
            return confidence >= minConfidence;
        }
    }

    private static Options parseOptions(Object arguments, ClassLoader loader, String defaultSort, int defaultLimit) throws Exception {
        double range = 128.0D;
        int limit = defaultLimit;
        String cls = null, iff = null, state = null, sort = defaultSort;
        double minConfidence = 0.0D;
        int count = argumentCount(arguments, loader);
        for (int i = 0; i < Math.min(count, 3); i++) {
            Object value = argument(arguments, i, loader);
            if (value instanceof Number number) {
                if (i == 0) range = number.doubleValue();
                else if (i == 1) limit = (int) number.doubleValue();
            } else if (value instanceof Map<?, ?> table) {
                if (table.get("range") instanceof Number n) range = n.doubleValue();
                if (table.get("limit") instanceof Number n) limit = (int) n.doubleValue();
                if (table.get("class") instanceof String s) cls = classAlias(s);
                if (table.get("iff") instanceof String s) iff = s.toLowerCase(Locale.ROOT);
                if (table.get("state") instanceof String s) state = s.toLowerCase(Locale.ROOT);
                if (table.get("min_confidence") instanceof Number n) minConfidence = n.doubleValue();
                if (table.get("sort") instanceof String s) sort = s.toLowerCase(Locale.ROOT);
            }
        }
        if (!sort.equals("distance") && !sort.equals("threat") && !sort.equals("priority") && !sort.equals("confidence")) {
            sort = defaultSort;
        }
        range = Math.max(8.0D, Math.min(range, CompatConfig.CC_MAX_RANGE.get()));
        limit = Math.max(1, Math.min(limit, MAX_CONTACTS));
        return new Options(range, cls, iff, state, minConfidence, limit, sort);
    }

    private static String classAlias(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "air" -> SbwWariumBridge.AIR_TAG;
            case "ground" -> SbwWariumBridge.GROUND_TAG;
            case "projectile" -> SbwWariumBridge.PROJECTILE_TAG;
            default -> value;
        };
    }

    private static int argumentCount(Object arguments, ClassLoader loader) throws Exception {
        Class<?> argumentsType = Class.forName("dan200.computercraft.api.lua.IArguments", false, loader);
        return ((Number) argumentsType.getMethod("count").invoke(arguments)).intValue();
    }

    private static Object argument(Object arguments, int index, ClassLoader loader) throws Exception {
        Class<?> argumentsType = Class.forName("dan200.computercraft.api.lua.IArguments", false, loader);
        if (((Number) argumentsType.getMethod("count").invoke(arguments)).intValue() <= index) return null;
        return argumentsType.getMethod("get", int.class).invoke(arguments, index);
    }

    private static Object methodResult(ClassLoader loader, Object... values) throws Exception {
        Class<?> resultType = Class.forName("dan200.computercraft.api.lua.MethodResult", false, loader);
        for (Method method : resultType.getMethods()) {
            if (!method.getName().equals("of") || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            if (method.getParameterCount() == 1 && method.getParameterTypes()[0].isArray()) return method.invoke(null, new Object[]{values});
            if (method.getParameterCount() == 0 && values.length == 0) return method.invoke(null);
        }
        throw new IllegalStateException("CC:Tweaked MethodResult.of not found");
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().location().toString();
    }

    // ---------------------------------------------------------------- events

    private static void attachComputer(BlockEntity blockEntity, Object computer) {
        synchronized (ATTACHED) {
            ATTACHED.computeIfAbsent(blockEntity, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(computer);
        }
    }

    private static void detachComputer(BlockEntity blockEntity, Object computer) {
        synchronized (ATTACHED) {
            Set<Object> computers = ATTACHED.get(blockEntity);
            if (computers == null) return;
            computers.remove(computer);
            if (computers.isEmpty()) {
                ATTACHED.remove(blockEntity);
                ANNOUNCED.remove(blockEntity);
            }
        }
    }

    /**
     * Queues warium_sbw_contact (uuid, class, x, y, z, distance) on the computers attached to each
     * active radar for every hostile contact that was not announced before, at most a few per scan.
     */
    static void pumpEvents(MinecraftServer server, long tick) {
        if (!available || !CompatConfig.CC_EVENTS.get()) return;
        List<Map.Entry<BlockEntity, List<Object>>> work = new ArrayList<>();
        synchronized (ATTACHED) {
            ATTACHED.entrySet().removeIf(entry -> entry.getKey().isRemoved());
            ANNOUNCED.keySet().removeIf(BlockEntity::isRemoved);
            for (Map.Entry<BlockEntity, Set<Object>> entry : ATTACHED.entrySet()) {
                work.add(Map.entry(entry.getKey(), new ArrayList<>(entry.getValue())));
            }
        }
        for (Map.Entry<BlockEntity, List<Object>> entry : work) {
            BlockEntity blockEntity = entry.getKey();
            if (!(blockEntity.getLevel() instanceof ServerLevel level)) continue;
            BlockPos pos = blockEntity.getBlockPos();
            Radar radar = radar(level, pos, blockEntity, CompatConfig.CC_EVENT_RANGE.get());
            if (!radar.active()) continue;
            // ANNOUNCED is also touched from detachComputer on the computer thread, so every access
            // to it must hold the ATTACHED monitor. Copy the known set out before queueing events.
            Set<String> known;
            synchronized (ATTACHED) {
                known = new HashSet<>(ANNOUNCED.computeIfAbsent(blockEntity, ignored -> new HashSet<>()));
            }
            Set<String> present = new HashSet<>();
            int sent = 0;
            for (Contact contact : rawContacts(level, pos, radar.baseRange())) {
                if (contact.distance() > radar.range() || contact.decoy() || !"hostile".equals(contact.iff())) continue;
                present.add(contact.uuid());
                if (known.contains(contact.uuid()) || sent >= EVENTS_PER_PULSE) continue;
                known.add(contact.uuid());
                sent++;
                Object[] eventArgs = {contact.uuid(), contact.cls(), contact.x(), contact.y(), contact.z(), contact.distance()};
                for (Object computer : entry.getValue()) queueEvent(computer, eventArgs);
            }
            synchronized (ATTACHED) {
                Set<String> announced = ANNOUNCED.get(blockEntity);
                if (announced != null) {
                    known.retainAll(present);
                    announced.clear();
                    announced.addAll(known);
                }
            }
        }
    }

    private static void queueEvent(Object computer, Object[] eventArgs) {
        try {
            if (queueEventMethod == null) {
                Class<?> access = Class.forName("dan200.computercraft.api.peripheral.IComputerAccess", false, ccLoader);
                queueEventMethod = access.getMethod("queueEvent", String.class, Object[].class);
            }
            queueEventMethod.invoke(computer, EVENT_CONTACT, eventArgs);
        } catch (Throwable ignored) {
            // A computer that was shut down mid-scan is not an error.
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        return null;
    }
}
