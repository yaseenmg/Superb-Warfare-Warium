# Electronic Warfare Research Sources

- FAA GPS/GNSS Interference Resource Guide: https://www.faa.gov/about/office_org/headquarters_offices/avs/offices/afx/afs/afs400/GNSS/GPS_GNSS_Interference_Resource_Guide.pdf
- Johns Hopkins APL, Inertial Navigation for Guided Missile Systems: https://www.jhuapl.edu/Content/techdigest/pdf/V28-N04/28-04-Bezick.pdf
- SBW Electronic Warfare Modrinth 1.0.2: https://modrinth.com/mod/sbw-electronic-warfare/version/1.0.2
- SBW Electronic Warfare CurseForge: https://www.curseforge.com/minecraft/mc-mods/sbw-electronic-warfare
- Radar jamming and deception overview: https://en.wikipedia.org/wiki/Radar_jamming_and_deception

Implementation principles drawn for the mod: jamming degrades signal quality and lock gradually, and deception is not just total randomness; jam-resistant systems fall back on alternatives such as inertial guidance or lose accuracy gradually instead of stopping instantly. The current SBW add-on provides jammers, EMP and counter-drone measures, but its present code limits some jam functions to SBW's own `DroneEntity`, so an optional bridge for Warium entities only is added.

Inspecting the local JAR `sbw_ew_addon-1.1.1+1.20.1.jar` confirmed that the real path is `com.example.sbw_ew_addon.JammerBlockEntity`, and that it contains `getActiveJammers(Level)`, `hasOperationalPower()`, `getJamRadius()`, `getModerateRadius()` and `getCriticalRadius()`. The real `MiniJammerBlockEntity` is a subclass of `JammerBlockEntity`, so the bridge picks it up through `isInstance` and needs no separate path.

CC:Tweaked sources: the modem documentation describes channels, the Wireless Modem with a range of roughly 64 blocks, and the Wired Modem, which can call remote peripherals through `callRemote`. This interface was used to provide an optional peripheral of type `warium_sbw`, which also queues the `warium_sbw_contact` event on attached computers.

- CC:Tweaked Modem API: https://tweaked.cc/peripheral/modem.html
- CC:Tweaked Modrinth: https://modrinth.com/mod/cc-tweaked/versions
