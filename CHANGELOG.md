# Changelog

## 1.0.3

- Add optional LambDynamicLights integration with a client-side directional cone and smoothed aiming while keeping the vanilla server-light fallback.
- Fix flashlight lighting when standing close to walls and partial collision blocks without allowing fallback light to tunnel through solids.
- Make beam smoothing frame-rate independent and stable across exact or near-180-degree turns.
- Align third-person dynamic beam origin with interpolated player motion and the actual handheld/headlamp emitter.
- Respect FE and underwater settings in the optional dynamic-light path, fall through unusable higher-priority lamps in the same tick, and clean up dynamic sources safely across world changes and failures.
- Add regression coverage for cone geometry, wall clipping, optional LDL API compatibility and close-wall server behavior.

## 1.0.2

- First public release as Flashlight FE, licensed under MIT.
- Separate rebindable controls: right mouse button for handheld, I for headband.
- Native animated mechanical button with first- and third-person synchronization.
- Creative players can use empty lamps without consuming or changing FE.
- Rechargeable handheld and removable Curios headband; optional JEI integration.
- Preserve item components and charge when mounting or removing the headband lamp.
- Keep beams working near walls and reduce headband light around the player.
- Configurable battery capacity, FE use, beam range, cone angle and underwater operation.
