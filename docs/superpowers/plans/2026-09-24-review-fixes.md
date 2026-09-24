# Review corrections implementation plan

> **For agentic workers:** Use superpowers:executing-plans and test-driven-development. Work in the PR branch, not master. Record failing and passing verification runs.

**Goal:** Correct the concrete defects found in the e9b72f0 code review without replacing aggregate beam frames or publishing anything from this PR.

**Architecture:** Keep geometry, aggregation, reconciliation and world mutation separate. Repair lifecycle and world-state drift at the manager boundary. Use one emitter transform for server and optional client lighting; keep Curios optional and do not contribute slots.

**Tech Stack:** Minecraft 1.21.1, NeoForge 21.1.249, Java 21, Gradle, JUnit 5, NeoForge GameTests, Python unittest, GitHub Actions.

**Spec:** The repository review and the user's request to prepare its fixes as a tested pull request (24 September 2026).

## Global constraints

- Keep the bestflashlight namespace and existing persisted item components.
- Keep passive light carriers; no per-block ownership or periodic block ticks.
- Do not load chunks just to validate or clean up light.
- Never overwrite foreign blocks or published release artifacts.
- Do not create Curios slots or entity-slot assignments in production resources.
- Do not merge the PR, push to master, or publish to CurseForge/Modrinth.
- Distinguish executed checks from manual visual/performance checks still required.

## Review focus

- Externally removed or modified carriers must converge to the desired frame without repeated writes for unchanged worlds.
- Cleanup must precede world saving; stopped/unloaded cleanup must remain idempotent.
- Piston extension/retraction must not be obstructed by light or transport untracked carriers; compare water behavior with vanilla.
- An installed Curios with no functional head slot must not disable vanilla head equipment; occupied Curios slots must not unexpectedly swap helmets.
- Reused release versions must fail clearly for a different source commit, without overwriting the release.

## Tasks

- [ ] Add regression GameTests for external carrier replacement, brightness drift, real tick cache refresh and piston behavior. Run them against the original implementation and record expected failures.
- [ ] Reconcile recorded carrier state against loaded-world state at bounded intervals; preserve the static zero-mutation invariant. Repeat affected tests and overlap/water/orphan tests.
- [ ] Restore loaded carriers on ServerStoppingEvent before saving; clear bookkeeping only on ServerStoppedEvent. Test cleanup ordering and repeatability.
- [ ] Route both lighting paths through EmitterTransform. Test pitched headband coordinates, waterline positions and left/right hand geometry.
- [ ] Make carriers piston-compatible without moving them; test dry/source/flowing-water cells against their vanilla counterparts.
- [ ] Resolve head-slot availability independently of Curios installation. Use vanilla HEAD only when no usable Curios head slot exists; keep existing slot contents untouched. Test empty/occupied/absent Curios head slots and item synchronization.
- [ ] Add release tests before changing scripts: optional Curios metadata on both platforms, different-commit version reuse rejected, identical-commit recovery retained. Bump the new release version and changelog together.
- [ ] Run the complete Python suite, asset regeneration check, Java unit tests, GameTests and production-JAR validation. Add CI report artifacts and a clean runtime compatibility check where practical.
- [ ] Review the complete diff; document uncovered LDL/Complementary visual and multi-player performance checks rather than claiming an unmeasured FPS improvement.
- [ ] Update the PR description with exact commits, verification evidence, scope and remaining limitations.
