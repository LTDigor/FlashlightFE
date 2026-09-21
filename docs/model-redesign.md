# Industrial lamp models

This is a client-only visual redesign. Lamp IDs, components, recipes, charging,
server light placement, optional LambDynamicLights and network behavior are unchanged.
The rear headband housing is visual only, not a new battery item or charger.

## Authoring

The committed JSON and PNG files are normal Minecraft/NeoForge resources. They are
loaded directly and require neither Python nor a custom model loader at runtime.

```sh
python3 scripts/generate_model_assets.py
python3 scripts/generate_model_assets.py --check
python3 -m unittest discover -s scripts -p '*_test.py'
```

The generator uses only Python's standard library. Both 128x128 RGBA atlases are
original pixel art. The on atlas differs only in its lens tile. Eight joined side
quads form each octagonal shell; cutout end caps close the silhouette. The optic
is recessed behind a steel annulus. Only the enabled lens face uses full-bright
NeoForge face data. No emissive entity or additional dynamic light was added.

The handheld body and moving button remain separate. The existing 0/-0.30 pixel
rest positions and -0.72 pixel press remain unchanged. Display transforms apply
to both parts through the existing renderer. The headband's empty, loaded/off and
loaded/on resources are also registered as standalone models for Curios. The
renderer resolves them from the current ModelManager rather than holding stale
BakedModel references after a resource reload.

Geometry budgets: handheld body 84 quads plus 5 moving-button quads; loaded
headband 89 quads; empty headband 44 quads. These are geometry counts, not measured
frame-time results. Lighting, raycasts and FE code are unchanged.

## Visual acceptance checks

Offline checks cover native rotations/bounds, local texture references, PNG CRCs,
UVs, on/off geometry parity, empty/mounted predicates, lens recess, skin-layer
clearance, full button travel and exact generator/asset agreement.

Actual Minecraft appearance still requires a client run. A software asset preview
is not an in-game screenshot and does not validate pose integration or shaders.
Check these before approving the visual result:

- Main/off hand, right/left primary arm, first/third person, GUI, item frame and
  dropped item; button OFF, ON, deepest press and rapid presses.
- Empty and mounted Curios headband, enabled/disabled, normal/slim skin, outer
  skin layer, sneaking and armor helmet. Verify that the lamp stays above the eyes.
- F3+T with each form equipped. The strap/body must remain normally lit, while
  only the enabled optic is full-bright. Repeat without shaders and with the
  pack's shader preset. Beam behavior should be unchanged.

Repository GameTests remain the regression gate for FE, mounting and light
behavior; the existing client smoke harness covers rendering/input paths. Do not
report manual visual checks as passed merely because asset tests or compilation
succeed.

### Recorded vanilla client smoke

`runClientSmoke` now starts with first-run accessibility onboarding disabled in
its isolated run directory, then uses the actual client to create a disposable
creative world. The recorded run covered loaded/on headband rendering with a
helmet and without it, a sneaking 45-degree head rotation, first- and
third-person native button movement, left-hand flashlight placement, and the
existing real input assertions. It also invoked `Minecraft.reloadResourcePacks()`
and re-checked the native item and Curios renderer registrations after completion.

The captures are evidence for those vanilla states only. Empty/off headband,
slim-arm skin, GUI, item-frame, dropped-item, manual F3+T, and shader-preset
appearance remain deferred visual checks. The checked-in gallery images and GIF
come directly from that client-smoke run; they are not generated previews.
