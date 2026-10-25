# [SBW] Superb Warfare&Warium

A Forge 1.20.1 compatibility mod for **Warium** (`crusty_chunks`) and **Superb Warfare** (`superbwarfare`). It classifies Warium units and projectiles, publishes tags and data other add-ons can read, and offers an optional CC:Tweaked radar console. It does not contain or replace any code from Warium or Superb Warfare.

Both Warium and Superb Warfare are required. Everything else is optional and skipped silently if absent or if its API differs.

## What it really does

**1. Data-pack tags (the most useful part).** Entity-type tag files add Warium aircraft, drones and projectiles to the lists used by SBW Air Defense (`air_defense_*`, `phalanx_*`), Drone Detector (`drone_targets`), Pantsir and Superb Warfare's `destroyable_projectile`. Torpedoes and hand-thrown grenades and bottles are deliberately left out, so air defense does not waste ammunition on them. All entries are optional, so a renamed entity never breaks loading.

**2. Runtime classification.** One scan per `scanIntervalTicks` (default 20) classifies candidate entities as air, ground or projectile, and tags them `sbw_air_target`, `sbw_ground_target`, `sbw_projectile_target` and `warium_threat`. Results are also stored under `warium_sbw_bridge.*` in the entity's persistent data. Matching uses whole words in the entity id and class name (not substrings, and not the namespace), and per-type results are cached.

A projectile is published as `air_projectile` when either its own id/class contains an airborne word, or its **owner** is an airborne platform. The owner is resolved from the owner's type and published tag, so a bomb dropped by an aircraft is air-launched even if the aircraft has not been scanned yet this tick; a shell fired from the ground stays `ground_projectile`. This keeps air-launched munitions out of the ground-projectile bucket. Note that Air Defense reads the tag files, not this key, so the key mainly helps data consumers and the radar console.

Candidate namespaces are an explicit list: `crusty_chunks`, `sbwdroneconfig`, `deepstrike`, `warfare_vehicles`, `dragonrise_reforge`, `fcp`, `vvp`, `mvp`, `sbw_air_defense`, `tacz`, `timelessandclassicszero`, and any namespace containing `warium`, plus `extraCandidateNamespaces` from the config. There is no "anything containing `sbw`" rule. Superb Warfare's own entities are not scanned by default; add `"superbwarfare"` to that list to include them (costs scan time).

**3. Track book and IFF.** Each classified threat gets a track: state `tracking`, `track_lost` or `kill_confirmed`, last known position, confidence, priority, threat score and an IFF value, plus the tags `sbw_iff_hostile`, `sbw_iff_friendly`, `sbw_iff_unknown` and `sbw_emitting`.
- A track becomes `track_lost` after `trackTimeoutTicks`, fades, and is deleted after `trackExpireTicks`. Lost and destroyed tracks stay readable through the CC peripheral (`get_tracks`).
- `kill_confirmed` is set when a living target dies or a non-living target is removed as killed. A projectile consumed on impact is simply dropped.
- Confidence is a heuristic for ranking contacts, not a physical radar model: it counts how many installed add-ons also recognise the target and lowers for jamming, decoys and weather/cover. Decoy status is read from the scoreboard tags `sbw_chaff`, `sbw_flare` and `sbw_decoy` only, so removing the tag restores confidence; the computed result is published as `battlefield.decoy` for other add-ons.
- IFF comes from the Warium tag, scoreboard team names (matched by name only: hostile/enemy/warium or friendly/ally/allied), the projectile owner's team, then the namespace. There is no `alpha` shortcut. It does not use FTB Teams.

**4. Threat response.** With `enableThreatResponse`, a Warium mob that is hit by a living attacker (or a projectile they own) is marked `engaged`, records the attacker UUID, and targets the attacker. Superb Warfare vehicles extend `Entity`, not `LivingEntity`, so a vehicle shot resolves to the vehicle and the mob targets its driver or gunner instead; an unmanned vehicle is still recorded in `engaged`/`attacker` but cannot become a vanilla target. Both keys are cleared on the next scan once the recorded attacker is dead or gone from the level. Warium units never retaliate against other Warium units, and never against a member of the same scoreboard team.

**5. Electronic warfare (needs SBW Electronic Warfare).** The jammer ranges (normal, moderate, critical) are read through reflection. Inside a jammer's range, **guided** Warium projectiles (id or class containing `missile`, `seeker` or `guided`, or listed in `guidedProjectileIds`) receive a small random course error every 3 ticks, scaled by jammer level and `jammingErrorPerLevel`; speed is preserved. Unguided shells and bombs are never touched. If a projectile re-steers itself every tick, the effect can be hidden; this is untested in game.

**6. Commands (permission level 2).** `/wariumcompat classify | list | tracks | integrations` and `/wariumcompat emcon <targets> <true|false>`, which sets or clears silent emission on entities.

## CC:Tweaked radar console (optional)

Requires CC:Tweaked. Advanced Peripherals computers are CC:Tweaked computers, so they use the same peripheral; there is no Advanced Peripherals specific code. The mod adds no blocks: the peripheral `warium_sbw` is offered on radar and jammer block entities of other add-ons (class names containing `JammerBlockEntity`, `TacticalRadar` or `RadarBlockEntity`).

It behaves like a radar console:
- A **silent** radar (`set_emcon(true)`) or an **unpowered** one (`hasOperationalPower()` false, or an empty Forge Energy buffer) returns nothing, with a reason as the second value.
- Jamming at the radar block shortens its range by `jamRangePenaltyPerLevel` per level (never below 25%).
- Optional `requireLineOfSight` ray-casts from just above the radar block. The number of ray casts per `get_contacts`/`get_threats` call is capped by `maxLineOfSightChecks` (default 128, range 8-512). Candidates are examined nearest-first; once the budget is used up, the remaining contacts are returned **without** a line-of-sight check rather than being silently hidden. If a single ray cast throws, that contact is treated as not visible (fail closed).

| Method | Returns |
|---|---|
| `get_status()` | block, emcon, emitting, powered, jam level, effective range, integrations, energy |
| `get_contacts([range] [, limit] [, options])` | live contacts, nearest first |
| `get_threats([range] [, count] [, options])` | same, sorted by threat score, default top 5 |
| `get_tracks([range] [, options])` | track book entries, including `track_lost` and `kill_confirmed` with last known position |
| `get_contact(uuid)` | one contact or track, or nil |
| `get_jamming()` | jammer level at the block (a jammer block does not jam itself) |
| `set_emcon(bool)` / `get_emcon()` | silent emission flag on the radar block |
| `get_power()` | Forge Energy status and `powered` |
| `get_config()` / `get_version()` | active limits / mod version |

`options` is a table: `range`, `limit`, `class` (`"air"`, `"ground"`, `"projectile"` or a full tag), `iff` (`"hostile"`, `"friendly"`, `"unknown"`), `state` (tracks only), `min_confidence`, `sort` (`"distance"`, `"threat"`, `"priority"`, `"confidence"`). Range is clamped to 8 to `maxRange` (default 512). Results are cached for 5 ticks per radar and capped at 512 entries.

```lua
local radar = peripheral.find("warium_sbw")
for _, c in ipairs(radar.get_threats(256, 3, { iff = "hostile" })) do
  print(c.type, math.floor(c.distance), c.threat_score)
end
while true do
  local _, uuid, class, x, y, z, dist = os.pullEvent("warium_sbw_contact")
  print("new hostile", class, dist)
end
```

With `contactEvents` on, every attached computer receives `warium_sbw_contact` (uuid, class, x, y, z, distance) when a new hostile, non-decoy contact appears within `contactEventRange`, at most 8 per scan per radar.

## Other optional probes

SBW Air Defense and DeepStrike are probed through cached reflection and their answers are stored as data. `extendAirDefenseWhitelist` (off by default) adds classified types to an already non-empty Air Defense whitelist. TaCZ entities are only classified as projectiles when they are actually `Projectile` instances or when their id/class contains a projectile word; belonging to the TaCZ namespace alone is not enough. Advanced Peripherals is only detected and shown in `/wariumcompat integrations`.

## Configuration

`config/warium_sbw_compat-common.toml`: scan interval, runtime tags, optional adapters, threat response, `extraCandidateNamespaces`, jamming and guided-projectile settings, track timeouts, CC range / events / line of sight (`requireLineOfSight`, `maxLineOfSightChecks`), debug logging.

## Installation and building

Put the JAR in `mods` next to Warium and Superb Warfare (Forge 47.x, Minecraft 1.20.1, Java 17). The declared Superb Warfare range is pinned to the 0.8.9 line (`[0.8.9,0.8.10)`), so the mod loads with 0.8.9.2 and other 0.8.9.x patches but refuses 0.8.10 or later rather than running untested. To build from source run `./gradlew build`; the JAR appears in `build/libs`. A GitHub Actions workflow is included.

## What has actually been verified

Verified by reading the real published artifacts:
- **Warium 1.3.5**: `modId=crusty_chunks` and 127 registered entity types (`CrustyChunksModEntities`). Every `crusty_chunks:*` id used by the ten tag files in this mod (41 ids) exists in Warium's own entity-type tags; 0 are missing.
- **Superb Warfare 0.8.9.2**: `modId=superbwarfare`, and `data/superbwarfare/tags/entity_types/destroyable_projectile.json` exists, so merging into it is valid.
- **SBW Electronic Warfare 1.1.1**: `modId=sbw_ew_addon`; `getActiveJammers(Level)` returns `Set<BlockPos>`, and `hasOperationalPower()`, `getJamRadius()`, `getModerateRadius()`, `getCriticalRadius()` match the reflection signatures.
- **SBW Drone Warfare 1.0.6**: `modId=sbwdroneconfig` (there is no `sbw_drone_warfare` mod id).
- **CC:Tweaked API** (read from the `mc-1.20.x` source, commit `d5da2f7`): `IPeripheralProvider#getPeripheral` returns `LazyOptional<IPeripheral>`; `ForgeComputerCraftAPI.registerPeripheralProvider` registers it; `IDynamicPeripheral#callMethod(IComputerAccess, ILuaContext, int, IArguments)` returns `MethodResult`; `MethodResult.of(Object...)`, `IArguments.count()/get(int)` and `IComputerAccess#queueEvent(String, Object...)` exist with the signatures the reflection uses.
- **Build**: `./gradlew build` completes against Forge 47.2.0 / Minecraft 1.20.1 and produces the JAR in `build/libs`.
- **Heuristics**: `sh tests-run-heuristics.sh` passes the dependency-free regression checks.

Not verified:
- SBW Air Defense and DeepStrike mod ids and class names. They are not published on Modrinth, so the reflection adapters are best-effort and are skipped if the names do not resolve.
- In-game behaviour of any integration: nothing here has been run inside a Minecraft instance.
- Whether a Warium missile re-steers itself every tick, which would mask the jamming drift.
- The confidence, priority and threat-score numbers are heuristic weights, not calibrated values.

## Known limits
- Optional reflective integrations (Air Defense, DeepStrike, Electronic Warfare) must be tested in a Minecraft instance; they are skipped silently if a class or method is missing.
- Warium entity ids in the tag files were checked against Warium 1.3.5 and should be re-checked after Warium updates.
- No flares, chaff or ammunition changes are added; the mod publishes tags and data that other add-ons can use.

## License

MIT. See `LICENSE` (also inside the JAR as `META-INF/LICENSE`).
