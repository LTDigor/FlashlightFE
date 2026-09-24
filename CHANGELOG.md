# Changelog

## [1.1.2] - 2026-09-24

- Reconcile externally removed or modified transient lights without rewriting unchanged beams.
- Restore server fallback lights before normal world saving during shutdown.
- Align client LDL and server headlamp emitter transforms, including underwater selection.
- Let pistons destroy temporary light carriers instead of blocking or transporting them.
- Use vanilla head equipment when Curios is installed without a functional head slot; do not create slots or replace helmets when an existing Curios slot is occupied.
- Mark Curios optional on both distribution platforms.
- Reject accidental cross-commit reuse of a release version; retain explicit manual recovery of original immutable artifacts.
- Add regression tests for real-tick world-state recovery, emitter parity, piston/water behavior, optional Curios and release safeguards.

- Rebuild the optional LambDynamicLights beam as an immutable snapshot: cone luminance, per-block occlusion traces and published bounds are computed together on the client thread and handed to LDL atomically, so fresh frame geometry can no longer be combined with stale obstacle distances, which made the beam flicker while flying.
- Replace the sub-block angular cone edge with a torch-rate radial penumbra (15 light levels over 7.75 blocks, the same falloff LDL applies to native held lights) so the lit spot fades smoothly across block centres instead of showing hard block squares.
- Keep a non-zero near-field cone radius so a beam aimed straight down still reaches floor block centres and lights the ground at the player's feet.
- Trace exact per-block visibility with unloaded chunks treated as opaque, keeping occluded cells dark without letting light tunnel through solids, and publish bounds together with the light values they cover.
- Add a real-LambDynamicLights client smoke harness (`prepareBeamSmoke` / `runBeamClientSmoke` with `-PbeamSmokeModDir`) that captures staged screenshots and per-build timings outside Git, plus unit, publication and GameTest coverage for the snapshot math, bounds and occlusion.
- Stop contributing Curios slots: the shipped data no longer defines the `head` slot or assigns it to players, so the mod cannot overwrite another mod's head slot size; the headband still joins a `head` slot through the item tag when some mod provides one, and the slot fixture now lives in the smoke source set so worn-path tests keep running.
- Fix the worn headband rendering on the nape: the head mount was rolled about Z instead of turned about Y, leaving the emitter facing backwards.
- Close the cutout-aliasing sawteeth on the octagon barrel and headlamp: side quads now overlap at the octagon corners and sleeves reach slightly past their end caps instead of meeting them edge to edge.
- Add a `clientValidate` run configuration for manual checks with real Curios and the dev head slot, without the scripted smoke stages.
- Keep the no-LambDynamicLights server fallback directional: handheld beam emitters now start two blocks ahead of the eyes instead of at the player's own cell, so the temporary block-light beam no longer washes the player from every side.
- Rewrite the server fallback lighting around immutable per-source beam frames: frames are aggregated per dimension by maximum requested brightness and reconciled against the previously applied carrier state, so unchanged beams perform zero world mutations. Transient carriers are now passive (no per-player ownership, no self-scheduled validation ticks) and persisted orphans are recovered event-driven on chunk load.
- Move the mod Java package to `com.ltdigor.flashlightfe`; the mod id, registry namespace, config keys and item/block ids are unchanged.

## 1.1.1

- Reduce the default energy consumption to 0.5 FE per second at 20 TPS, while retaining compatible fractional `energyPerTick` configuration and charging only on whole-FE ticks.
- Set the default flashlight cone angle to 35 degrees.
- Add Simplified Chinese game localization and enforce matching Russian, English and Chinese translation keys.

## 1.1.0

- Make Curios optional: loaded headbands equip to Curios' standard `head` slot when available, or Minecraft's vanilla head slot otherwise.
- Keep Curios rendering, equipment synchronization and legacy-item recovery dormant when Curios is absent.
- Remove obsolete gallery screenshots and the unused repository description file.

## 1.0.4

- Redesign the handheld flashlight with an octagonal graphite body, ribbed grip, steel bezel, recessed optic, pocket clip and orange mechanical switch.
- Redesign the headband with a woven strap, side hinges, compact central lamp and rear battery housing. Empty and mounted forms remain distinct.
- Share the same baked headband geometry between inventory and Curios rendering; resolve models from the current resource manager after reloads.
- Add original pixel-art atlases and lens-only full-bright faces for enabled lamps, including the headband inventory model. No new runtime dependencies.
- Preserve handheld button animation, mounting recipes, FE behavior, networking and both lighting backends. Adjust item display transforms without changing beam calculations.
- Add reproducible model authoring and offline regression checks for geometry, texture references, state overrides, button travel and resource consistency.

## 1.0.3

- Add optional LambDynamicLights integration with directional cones for all visible players; a two-phase per-dimension handoff removes the vanilla server-light fallback only after every compatible client reports its dynamic renderer ready, while mixed/older clients keep fallback automatically.
- Fix flashlight lighting when standing close to walls and partial collision blocks without allowing fallback light to tunnel through solids.
- Make beam smoothing frame-rate independent and stable across exact or near-180-degree turns.
- Align third-person dynamic beam origin with interpolated player motion and the actual handheld/headlamp emitter.
- Respect FE and underwater settings in the optional dynamic-light path, fall through unusable higher-priority lamps in the same tick, and clean up dynamic sources safely across world changes and failures.
- Preserve exact source/flowing-water state in temporary light carriers, keep vanilla source-water bucket pickup semantics, let vanilla water simulation continue without replacing the active light carrier, and rearm orphan cleanup after chunk reloads.
- Cache static server beam geometry and client occlusion probes between bounded refreshes, reduce fallback sampling from 49 to 29 rays, and avoid per-tick full ItemStack sync for FE-only drain while preserving authoritative owner charge updates.
- Switch survival lamps off immediately after their final affordable FE tick and fall through unusable/multiple Curios headband sources correctly.
- Add regression coverage for cone geometry, wall clipping, water lifecycle/buckets, FE synchronization, cache invalidation, optional LDL API compatibility and close-wall server behavior.

## 1.0.2

- First public release as Flashlight FE, licensed under MIT.
- Separate rebindable controls: right mouse button for handheld, I for headband.
- Native animated mechanical button with first- and third-person synchronization.
- Creative players can use empty lamps without consuming or changing FE.
- Rechargeable handheld and removable Curios headband; optional JEI integration.
- Preserve item components and charge when mounting or removing the headband lamp.
- Keep beams working near walls and reduce headband light around the player.
- Configurable battery capacity, FE use, beam range, cone angle and underwater operation.
