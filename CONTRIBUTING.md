# Contributing

Contributions are welcome: bug reports, classification tweaks, translations and pull requests.

## Development setup
- JDK 17
- `./gradlew build` produces the jar in `build/libs/`
- `sh tests-run-heuristics.sh` runs the dependency-free heuristics checks
- Warium (`crusty_chunks`), Superb Warfare and the SBW add-ons are runtime dependencies and are
  **not** bundled. Place their jars in `run/mods` to test in-game.

## Guidelines
- Keep the mod ID `warium_sbw_compat` stable. Changing it breaks existing configs and worlds.
- Do not add assets or code from other mods to this repository.
- Keep every integration with another mod optional and guarded, so a missing or changed mod never
  crashes this one.
- Add a case to `HeuristicsSelfTest` when you change classification logic.
- Open an issue before large changes.

## Pull requests
1. Fork the repo and create a branch.
2. Make your change and test it in-game on Minecraft 1.20.1 / Forge 47.
3. Describe what changed and why in the PR.
