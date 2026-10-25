package dev.manus.wariumsbwcompat;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classification heuristics with no Minecraft types, so they can be unit-tested on their own.
 * Words are matched as whole tokens (split on '_' and camelCase), never as substrings, so an id such
 * as "planet_core" is no longer mistaken for a plane. The namespace is not used for word matching.
 */
public final class Heuristics {
    public enum Kind { AIR, GROUND, PROJECTILE, UNKNOWN }

    /** Static, per-entity-type facts. Computed once per type and cached by the caller. */
    public record Profile(boolean projectileWord, boolean airWord, boolean groundWord,
                          boolean airId, boolean groundId, boolean guidedWord) {}

    public record Decision(Kind kind, boolean airProjectile) {}

    static final Set<String> AIR_IDS = Set.of(
            "hunter", "raidscout", "reaper", "scout", "aircraft", "plane", "helicopter", "heli", "drone",
            "a_10a", "ac_130h", "ah_6", "j_16", "ju_87", "kirov", "mi_28", "su24m", "rah_66",
            "ac130", "ah1f", "ah64", "av8b", "f14", "f15e", "f16c", "fa18e", "j10", "j11", "j15t",
            "j16", "j20", "j20vtol", "j35", "j8", "jas39e", "jf17", "ka50", "mi24p", "mi24v", "mi8",
            "nh90", "q5", "rafale", "su24", "uh60", "z9", "advanced_drone", "bayraktar_tb2",
            "ukrainian_bayraktar_tb2", "lucas_drone", "heavy_bomber_drone", "geranium_2", "f15c", "mig21");
    static final Set<String> GROUND_IDS = Set.of(
            "worker", "striker", "rifler", "commander", "breacher", "decimator", "eradicator",
            "mortarer", "tank", "apc", "vehicle", "walker", "robot", "turret",
            // Verified Warium ground units whose ids carry no ground word in GROUND_WORDS.
            "prototype_hull", "eradicator_hull", "decimator_hull", "assassin", "ciws");
    // These words identify an airborne platform, not merely an airborne object or munition.
    // Keep missile/rocket/bomb/shell in PROJECTILE_WORDS only: they do not tell us what launched it.
    static final Set<String> AIR_WORDS = Set.of(
            "aircraft", "airplane", "plane", "fighter", "bomber", "jet", "helicopter", "heli",
            "drone", "uav", "ucav", "airborne", "aircraftcarrier");
    static final Set<String> GROUND_WORDS = Set.of(
            "tank", "apc", "ifv", "vehicle", "crawler", "walker", "robot", "striker", "rifler",
            "worker", "commander", "breacher", "decimator", "eradicator", "mortarer", "turret", "cannon");
    static final Set<String> PROJECTILE_WORDS = Set.of(
            "projectile", "bullet", "shell", "rocket", "missile", "bomb", "torpedo", "grenade");

    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    private Heuristics() {}

    /** Splits ids and class names into lower-case word tokens; also adds the singular of plurals. */
    public static Set<String> tokens(String... parts) {
        Set<String> out = new HashSet<>();
        for (String part : parts) {
            if (part == null) continue;
            for (String piece : CAMEL.split(part)) {
                for (String token : piece.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                    if (token.isEmpty()) continue;
                    out.add(token);
                    if (token.length() > 3 && token.endsWith("s")) out.add(token.substring(0, token.length() - 1));
                }
            }
        }
        return out;
    }

    public static Profile profile(String path, String className, Collection<? extends String> guidedKeywords) {
        String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        Set<String> tokens = tokens(lowerPath, className);
        Set<String> guided = new HashSet<>();
        if (guidedKeywords != null) {
            for (String word : guidedKeywords) {
                if (word != null && !word.isBlank()) guided.addAll(tokens(word));
            }
        }
        return new Profile(
                intersects(tokens, PROJECTILE_WORDS),
                intersects(tokens, AIR_WORDS),
                intersects(tokens, GROUND_WORDS),
                AIR_IDS.contains(lowerPath),
                GROUND_IDS.contains(lowerPath),
                intersects(tokens, guided));
    }

    /** Explicit aircraft identifiers/flags win over generic vehicle words; projectile status is separate. */
    public static Decision decide(Profile profile, boolean projectileInstance, boolean aircraftFlag) {
        return decide(profile, projectileInstance, aircraftFlag, false);
    }

    /**
     * @param ownerAir true when the projectile's owner is itself an airborne platform. A bomb or
     *                 missile dropped by an aircraft often has no air word in its own id, so the
     *                 owner is the only reliable signal that it is air-launched. This is what keeps
     *                 air-launched munitions out of the ground-projectile bucket.
     */
    public static Decision decide(Profile profile, boolean projectileInstance, boolean aircraftFlag,
                                  boolean ownerAir) {
        boolean projectile = projectileInstance || profile.projectileWord();
        boolean air = profile.airId() || profile.airWord() || aircraftFlag;
        boolean ground = (profile.groundId() || profile.groundWord()) && !air;
        if (projectile) return new Decision(Kind.PROJECTILE, air || ownerAir);
        if (air) return new Decision(Kind.AIR, false);
        if (ground) return new Decision(Kind.GROUND, false);
        return new Decision(Kind.UNKNOWN, false);
    }

    public static boolean integrationNamespace(String namespace, Collection<? extends String> extra) {
        String n = namespace.toLowerCase(Locale.ROOT);
        if (extra != null) for (String value : extra) {
            if (value != null && n.equals(value.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return n.equals("crusty_chunks") || n.equals("sbwdroneconfig") || n.equals("deepstrike")
                || n.equals("warfare_vehicles") || n.equals("dragonrise_reforge") || n.equals("fcp")
                || n.equals("vvp") || n.equals("mvp") || n.equals("sbw_air_defense")
                || n.contains("warium") || n.equals("tacz") || n.equals("timelessandclassicszero");
    }

    public static boolean wariumNamespace(String namespace) {
        String n = namespace.toLowerCase(Locale.ROOT);
        return n.equals("crusty_chunks") || n.contains("warium");
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String value : a) if (b.contains(value)) return true;
        return false;
    }
}
