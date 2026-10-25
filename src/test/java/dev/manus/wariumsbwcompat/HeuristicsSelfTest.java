package dev.manus.wariumsbwcompat;

import java.util.List;

/** Dependency-free regression tests; run with javac/java against Heuristics.java. */
public final class HeuristicsSelfTest {
    private static int checks;

    private static void expect(String label, Heuristics.Kind kind, boolean airProjectile,
                               Heuristics.Decision actual) {
        checks++;
        if (actual.kind() != kind || actual.airProjectile() != airProjectile) {
            throw new AssertionError(label + ": expected " + kind + "/" + airProjectile
                    + " but got " + actual);
        }
    }

    public static void main(String[] args) {
        // --- projectiles: own id/class words ------------------------------------------------
        expect("tank shell stays non-air", Heuristics.Kind.PROJECTILE, false,
                Heuristics.decide(Heuristics.profile("tank_fire_projectile", "TankFireProjectile", List.of("missile", "guided")), true, false));
        expect("artillery shell is not presumed air-launched", Heuristics.Kind.PROJECTILE, false,
                Heuristics.decide(Heuristics.profile("artillery_shell", "ArtilleryShell", List.of("missile", "guided")), true, false));
        expect("aircraft bomb remains air projectile", Heuristics.Kind.PROJECTILE, true,
                Heuristics.decide(Heuristics.profile("airplane_bomb", "AirplaneBomb", List.of("missile", "guided")), true, false));

        // --- projectiles: owner-based air launch --------------------------------------------
        // A bomb whose own id carries no air word, but whose owner is an aircraft.
        expect("owner-air bomb is air-launched", Heuristics.Kind.PROJECTILE, true,
                Heuristics.decide(Heuristics.profile("mk_82", "Mk82Entity", List.of()), true, false, true));
        // The same bomb fired from the ground must stay a ground projectile.
        expect("owner-ground bomb stays ground projectile", Heuristics.Kind.PROJECTILE, false,
                Heuristics.decide(Heuristics.profile("mk_82", "Mk82Entity", List.of()), true, false, false));
        // Owner-air must not turn a non-projectile into a projectile.
        expect("owner-air does not reclassify a tank", Heuristics.Kind.GROUND, false,
                Heuristics.decide(Heuristics.profile("main_battle_tank", "MainBattleTank", List.of()), false, false, true));
        // Backwards-compatible three-argument overload still behaves as before.
        expect("three-arg overload defaults ownerAir=false", Heuristics.Kind.PROJECTILE, false,
                Heuristics.decide(Heuristics.profile("mk_84", "Mk84Entity", List.of()), true, false));

        // --- platforms ----------------------------------------------------------------------
        expect("aircraft ID classified air", Heuristics.Kind.AIR, false,
                Heuristics.decide(Heuristics.profile("f16c", "VehicleEntity", List.of("missile", "guided")), false, false));
        expect("explicit aircraft flag wins", Heuristics.Kind.AIR, false,
                Heuristics.decide(Heuristics.profile("custom_vehicle", "CustomVehicle", List.of()), false, true));
        expect("ground vehicle remains ground", Heuristics.Kind.GROUND, false,
                Heuristics.decide(Heuristics.profile("main_battle_tank", "MainBattleTank", List.of()), false, false));
        expect("unknown entity remains unknown", Heuristics.Kind.UNKNOWN, false,
                Heuristics.decide(Heuristics.profile("planet_core", "PlanetCore", List.of()), false, false));
        // Verified Warium ids: aerial units use FlyingMoveControl, ground units do not.
        expect("warium hunter is air", Heuristics.Kind.AIR, false,
                Heuristics.decide(Heuristics.profile("hunter", "HunterEntity", List.of()), false, false));
        expect("warium scout is air", Heuristics.Kind.AIR, false,
                Heuristics.decide(Heuristics.profile("scout", "ScoutEntity", List.of()), false, false));
        expect("warium prototype hull is ground", Heuristics.Kind.GROUND, false,
                Heuristics.decide(Heuristics.profile("prototype_hull", "PrototypeHullEntity", List.of()), false, false));
        expect("warium assassin is ground", Heuristics.Kind.GROUND, false,
                Heuristics.decide(Heuristics.profile("assassin", "AssassinEntity", List.of()), false, false));
        expect("warium ciws is ground", Heuristics.Kind.GROUND, false,
                Heuristics.decide(Heuristics.profile("ciws", "CIWSEntity", List.of()), false, false));

        // --- guided keyword parsing ---------------------------------------------------------
        checks++;
        if (!Heuristics.profile("guided_missile", "GuidedMissile", java.util.Arrays.asList("guided", null)).guidedWord())
            throw new AssertionError("guided keyword matching failed");
        checks++;
        if (Heuristics.profile("plain_projectile", "PlainProjectile", null).guidedWord())
            throw new AssertionError("null guided keyword list should be safe");

        // --- namespace admission ------------------------------------------------------------
        checks++;
        if (Heuristics.integrationNamespace("my-sbw-unrelated-mod", List.of()))
            throw new AssertionError("broad sbw namespace matching must not admit unrelated mods");
        checks++;
        if (!Heuristics.integrationNamespace("sbw_air_defense", List.of()))
            throw new AssertionError("known SBW integration namespace should be admitted");
        checks++;
        if (!Heuristics.integrationNamespace("sbwdroneconfig", List.of()))
            throw new AssertionError("real SBW Drone Warfare mod id should be admitted");
        checks++;
        if (Heuristics.integrationNamespace("sbw_drone_warfare", List.of()))
            throw new AssertionError("the non-existent sbw_drone_warfare namespace must not be admitted");

        System.out.println("PASS: " + checks + " Heuristics regression checks");
    }
}
