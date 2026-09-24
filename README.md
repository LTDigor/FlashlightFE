# Flashlight FE

![Flashlight FE icon](assets/icon.png)

A Minecraft **1.21.1 / NeoForge** flashlight mod by **LTDigor**. Rechargeable handheld lights and reversible headband mounting, with optional Curios support.

[Download on CurseForge](https://www.curseforge.com/minecraft/mc-mods/flashlight-fe) · [GitHub releases](https://github.com/LTDigor/FlashlightFE/releases) · [Report an issue](https://github.com/LTDigor/FlashlightFE/issues)

## Install

Install `flashlight-fe-1.1.1.jar` on both client and server, with NeoForge **21.1.249+** within 21.1. Curios **9.5.1+** within version 9 is optional. GeckoLib is not needed. JEI 19.18+ is optional; Immersive Engineering is supported through the standard FE item capability.

This version uses the new `bestflashlight` namespace. It is **not a drop-in update** for the previous personal-use `flashlight` derivative: old items, helmet upgrades and configuration are not migrated. Test in a new world before changing an existing installation.

## Use

- Craft a flashlight, then charge it in an FE item charger, such as Immersive Engineering's Charging Station. New lamps have no charge.
- Hold the flashlight in either hand. Handheld flashlights cannot be equipped in Curios. Old equipped flashlights return through Curios' inventory handler, preserving their charge, name and components; a full inventory drops them beside the player. Other mods' slots and items are unchanged.
- For a visible forehead mount, craft a headband from any wool and two strings. Combine it with a flashlight, then use it: with Curios it equips the default **head** Curios slot; without Curios it equips vanilla's head slot, replacing a helmet.
- Craft the loaded headband alone to detach the flashlight. The empty headband is returned. Charge, custom names and other components survive both operations; attachment and removal turn the lamp off. A loaded headband can also be charged directly.
- One enabled source emits per player: main hand, then offhand, then headband. Unequipped lamps do not consume power.
- Creative players can use all lamp forms with zero or partial charge without changing stored FE. Switching to survival restores the charge requirement and normal drain on the next server tick; switching to creative stops drain immediately.

JEI lists discharged and charged variants; charge does not interfere with recipe lookup. Tooltips show FE and switch state. A green charge bar appears below full charge; full batteries and empty headbands have no bar.

Two bindings appear under **Flashlight FE** in Minecraft Controls. Both accept keyboard or mouse buttons. The old saved flashlight binding is retained for the headband.

| Input | Result |
|---|---|
| Handheld, default **right mouse button**, flashlight in main hand | Toggle main flashlight; suppress normal use/block interaction |
| Right mouse button, empty main hand, offhand flashlight | Toggle offhand flashlight |
| Right mouse button, another item in main hand | Normal Minecraft use; offhand flashlight stays unchanged |
| Handheld rebound to another key/button | Main flashlight first, otherwise offhand, even with another item in main hand |
| Headband, default **I** | Toggle the equipped headband, independently of both hands |

One physical press toggles once; holding does not repeat. Screens and chat ignore lamp input. Rebinding handheld away from the right mouse button removes its toggle action from that button. Server checks the actual inventory for each request.

The handheld has an octagonal graphite body, ribbed grip, recessed optic and orange mechanical button. Each accepted press animates for about 0.25 seconds, resting partly recessed when on and raised when off. Rapid presses continue from the current position. An empty survival battery still plays the press and release. The native renderer draws the body and moving button separately in first and third person; the server notifies the owner and tracking players. Animation state stays on the client.

The headband uses the same baked geometry in the inventory and on the player: woven strap, side hinges, compact central lamp and rear battery housing. The rear housing is visual only, not a separate battery item. Only the enabled optic is full-bright; the strap and housing retain normal scene lighting.

## Gallery

In-game captures of the 1.0.4 industrial models.

![Curios headband with an armor helmet](assets/gallery/headband-with-helmet.png)

![Flashlight in the left hand](assets/gallery/offhand-flashlight-left-arm.png)

![Native flashlight button animation](assets/gallery/flashlight-button-animation.gif)
## Configuration

NeoForge uses `config/bestflashlight-server.toml` as the default server config. If `<world>/serverconfig/bestflashlight-server.toml` exists, that world-specific file overrides the global default. Server values are synchronized to clients and world-restart settings apply after restarting the world/server.

| Setting | Default | Meaning |
|---|---:|---|
| `energyCapacity` | 10000 | Maximum stored FE |
| `energyPerTick` | 0.025 | FE per emitting tick; fractional values are charged as whole FE on scheduled ticks; 0 disables consumption |
| `worksUnderwater` | true | Allow submerged emitters |
| `beamRange` | 12.0 | Beam length, 1–32 blocks |
| `coneAngleDegrees` | 35.0 | Full cone angle, 1–90 degrees |

At 20 TPS, the default battery lasts about 5 hours 33 minutes. Without LambDynamicLights, vanilla block lighting supplies the illumination, so soft light spreads outside the source-placement cone.

Server fallback lighting is computed as immutable per-source beam frames. Frames are aggregated per dimension using maximum requested brightness and reconciled against the previously applied light state, so only real differences touch the world and a static beam stops mutating blocks while it keeps draining FE. Overlapping beams resolve by maximum requested brightness without one player removing another player's contribution. Transient light carriers do not maintain per-player ownership and do not use periodic self-check ticks; they are passive blocks whose lifecycle is owned by the server lighting manager. Carriers preserve exact source/flowing-water levels, preserve normal source-water bucket pickup, avoid water plants, and persisted orphans are recovered event-driven on chunk load. Near walls, tracing falls back safely instead of losing the whole beam. Headbands place sources at forward ray endpoints to reduce illumination around the player.

If every real player in the current dimension has a compatible, enabled LambDynamicLights bridge, the server uses a two-phase readiness handshake and only then skips its temporary block-light beam for that dimension. Clients render directional cones for all visible players; mixed or older clients automatically keep the server fallback. Static server beam geometry and client occlusion probes are cached between bounded refreshes. FE-only drain uses lightweight owner synchronization instead of resending full held/Curios stacks every tick; enabled state, names, mounting and other component changes still use normal synchronization.

## Source and assets

Flashlight FE uses original industrial models, pixel-art texture atlases and a native NeoForge renderer. Click-sound references resolve to assets supplied by Minecraft; Minecraft assets are not bundled or relicensed. No other mod's models, textures, sounds or animations are included. The checked-in models and textures can be regenerated with Python's standard library; Python is not needed at runtime. See [model authoring](docs/model-redesign.md).

This repository contains the standalone implementation under the [MIT License](LICENSE), copyright 2026 LTDigor. Dependencies retain their own licenses; see [NOTICE](NOTICE). The internal `bestflashlight` IDs are retained so existing standalone items, saved bindings, components and configuration remain compatible. This rename does not migrate items from the older `flashlight` mod.

## Releases

Releases are published from `master` after review. Set `mod_version` in `gradle.properties` and add the matching section to `CHANGELOG.md`, then push. The release workflow builds the exact commit and uploads the same JAR to GitHub, CurseForge and Modrinth. A version already released is never overwritten. Repository variable `PUBLISH_ENABLED` must be `true`; publication is blocked while the repository is private.

If an upload times out, the workflow preserves an upload-intent receipt and stops automatic retries for that platform. Check the provider account, including files awaiting moderation, against the original release artifact before recovery. See the recovery instructions in [`scripts/release.py`](scripts/release.py). Successful uploads and their artifacts must not be replaced.

## Development

Use JDK 21 and import the Gradle project in IntelliJ IDEA.

```sh
python3 -m unittest discover -s scripts -p '*_test.py'
python3 scripts/generate_model_assets.py --check
./gradlew build
./gradlew runGameTestServer
./gradlew runClientSmoke
./gradlew runClientReloadSmoke
python3 scripts/test_multiplayer.py
```

Output: `build/libs/flashlight-fe-1.1.1.jar`.

GameTests exercise real FE charging, the IE station, crafting, Curios slots, beam geometry, water and shared ownership. Client smoke creates an isolated creative world and checks models, synchronization, both bindings, rebinding, held input, block interactions and button screenshots; reload smoke reopens it to check persistence and orphan cleanup. The multiplayer harness uses a creative player and a survival player on a disposable loopback server, checking FE behavior and press synchronization to the owner and observers. Test sources and IE are excluded from the production JAR; run files and screenshots are ignored by Git.
