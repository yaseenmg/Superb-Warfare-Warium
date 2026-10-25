# Validation notes for 1.0.0

These notes describe what was checked for this release and what was not.

## Passed locally

- `./gradlew build` completed against Forge 47.2.0 / Minecraft 1.20.1 with Java 17
  (`BUILD SUCCESSFUL`, 9 tasks executed) and produced `build/libs/warium_sbw_compat-1.0.0.jar`.
- `compileTestJava` and `test` ran as part of that build.
- `sh tests-run-heuristics.sh` passes 22 dependency-free regression checks, including:
  - owner-air bomb -> `PROJECTILE` / `airProjectile=true`;
  - the same bomb with a ground owner -> `PROJECTILE` / `airProjectile=false`;
  - owner-air does not turn a tank into a projectile;
  - the three-argument overload defaults `ownerAir=false`;
  - `sbwdroneconfig` is admitted and `sbw_drone_warfare` is rejected;
  - verified Warium aerial ids (`hunter`, `scout`) classify as `AIR`;
  - verified Warium ground ids (`prototype_hull`, `assassin`, `ciws`) classify as `GROUND`.
- All 10 resource JSON files parse, have no duplicate keys and no duplicate ids.
- Every `crusty_chunks:*` id used by those tag files exists in Warium 1.3.5's own entity-type tags
  (41 ids used, 0 missing).
- The built JAR contains `META-INF/LICENSE` and a `mods.toml` with
  `displayTest="IGNORE_ALL_VERSION"` and `version="1.0.0"`.

## Artifact verification

Read from the published JARs, not from documentation:
- Warium 1.3.5: `modId=crusty_chunks`, 127 registered entity types.
- Superb Warfare 0.8.9.2: `modId=superbwarfare`, `destroyable_projectile` tag exists.
- SBW Electronic Warfare 1.1.1: `modId=sbw_ew_addon`; reflective signatures for
  `getActiveJammers(Level)`, `hasOperationalPower()`, `getJamRadius()`, `getModerateRadius()` and
  `getCriticalRadius()` match.
- SBW Drone Warfare 1.0.6: `modId=sbwdroneconfig`.

## Not verified

- SBW Air Defense and DeepStrike mod ids and class names. Neither is published on Modrinth, so the
  reflection adapters remain best-effort and are skipped if the names do not resolve.
- Loading the JAR in a real Minecraft instance, dedicated-server startup, and in-game behaviour of
  every integration.
- Whether a Warium missile re-steers itself every tick, which would mask the jamming drift.
- Confidence, priority and threat-score values are heuristic weights, not calibrated numbers.

## Second intensive pass

- Re-read all 10 Java files, all 10 tag JSONs, `mods.toml`, `build.gradle`, `gradle.properties`
  and the docs.
- Cross-checked every persistent-data key that is written against every key that is read. Two
  write-only keys were found (`attacker_living`, `battlefield.assessment` is intentional output);
  `attacker_living` was removed.
- Verified Forge's load order in `net/minecraftforge/fml/core/ModStateProvider.java`:
  `CONSTRUCT -> CONFIG_LOAD -> COMMON_SETUP`. Adapter setup moved to `FMLCommonSetupEvent`, so no
  config value is read before it is loaded.
- Downloaded `drone_detector-1.0.1.jar` from Modrinth and confirmed `modId="drone_detector"` and
  that it reads `data/drone_detector/tags/entity_types/drone_targets.json`; the tag file is valid.
- Re-ran `./gradlew build --offline` (BUILD SUCCESSFUL) and `sh tests-run-heuristics.sh`
  (PASS: 22 checks) after the changes.
- Re-verified that all 41 `crusty_chunks:*` ids in the tag files exist in Warium 1.3.5 (0 missing).

## Third pass: key-by-key audit

- Built a write/read matrix for every persistent-data key across all 10 Java files ). No key is read without ever being written except `aircraft`, which
  is a documented inbound key set by an external mod.
- Confirmed `/wariumcompat emcon` writes `silent_emission` on entities, so the per-entity
  `battlefield.radar_emitting`, `sbw_emitting` and contact `emitting` fields read a key that is
  actually set. An earlier draft removed them as "always true"; that change was reverted after
  reading the command.
- `javap` on `sbw_ew_addon` 1.1.1: `getActiveJammers(Level)`, `hasOperationalPower()`,
  `getJamRadius()`, `getModerateRadius()`, `getCriticalRadius()` all match the reflective lookups.
- `javap` on `sbwdroneconfig` 1.0.6: `DroneJammerSystem.isJammed` is
  `FiberOpticLinkSystem.isImmuneToNormalJammers` + `DroneJamStateManager.hasInterference`; the
  jammers only feed a per-entity drone map and expose no active-jammer registry, so excluding them
  from EW jam strength is correct.
- `javap` over Warium 1.3.5: exactly `HunterEntity`, `RaidscoutEntity`, `ReaperEntity` and
  `ScoutEntity` use `FlyingPathNavigation`; all four are `Monster` and all four are the ids in every
  air-target tag file.
- `javap` on the official-mapped 1.20.1 Forge artifact: `ServerLevel#getEntity(UUID)`,
  `Entity#addTag(String)`, `Entity#removeTag(String)`, `Entity#getPersistentData()` exist with the
  expected signatures.
- After the five fixes: `./gradlew build --offline` -> EXIT 0, JAR 83,868 bytes;
  `sh tests-run-heuristics.sh` -> PASS: 22 checks.
