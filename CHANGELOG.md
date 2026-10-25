# Changelog

## 1.0.0

First public release.

### Features
- Entity-type tag files that add Warium aircraft, drones and projectiles to the target lists of SBW
  Air Defense, Drone Detector, Pantsir and Superb Warfare. Every entry is optional, so a renamed
  entity never breaks tag loading.
- Runtime classification of Warium and related entities as air, ground or projectile, published as
  scoreboard tags and persistent data. Air-launched munitions are recognised from their owner.
- Track book with IFF, confidence, priority, threat score and `tracking` / `track_lost` /
  `kill_confirmed` states.
- Threat response: Warium units retaliate against attackers, including Superb Warfare vehicles
  (through the driver or gunner), and never against other Warium units or their own team.
- Optional SBW Electronic Warfare integration: jammer ranges are read by reflection and guided Warium
  projectiles drift inside them.
- Optional CC:Tweaked radar console (`warium_sbw` peripheral) with contacts, threats, tracks,
  emission control, power, range limits, optional line of sight and the `warium_sbw_contact` event.
- Optional SBW Air Defense and DeepStrike probes.
- `/wariumcompat` commands and the `warium_sbw_compat-common.toml` configuration.

### Notes
- The Superb Warfare dependency is pinned to the 0.8.9 line (`[0.8.9,0.8.10)`).
- Optional integrations were not run inside a Minecraft instance. See `VALIDATION.md`.
