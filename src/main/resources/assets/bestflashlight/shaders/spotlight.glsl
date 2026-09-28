// Camera-relative coordinates, in the same world axes as Complementary's playerPos.
uniform vec3 bestflashlightBeamOrigin;
uniform vec3 bestflashlightBeamDirection;
// Range, tan(half angle), brightness / 15, softness. Zero range disables the beam.
uniform vec4 bestflashlightBeamParams;

float bestflashlightSurfaceLight(vec3 surfacePos) {
    vec3 delta = surfacePos - bestflashlightBeamOrigin;
    float forward = dot(delta, bestflashlightBeamDirection);
    float range = bestflashlightBeamParams.x;
    if (range <= 0.0 || forward <= 0.0 || forward >= range) return 0.0;
    vec3 radialVector = delta - forward * bestflashlightBeamDirection;
    float radius = forward * bestflashlightBeamParams.y;
    float radial = length(radialVector) / max(radius, 0.000001);
    float aa = max(fwidth(radial), 0.00001);
    float edge = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, radial);
    float softness = bestflashlightBeamParams.w;
    float profile = edge;
    if (softness > 0.0) {
        profile *= pow(max(0.0, 1.0 - radial * radial), 2.0 * softness)
            * smoothstep(0.0, softness, 1.0 - radial);
    }
    float endWidth = min(range, max(1.0, range * 0.2));
    float axial = smoothstep(0.0, endWidth, range - forward);
    // Level-15 blocklight with the reference wall's 0.8^2 diffuse calibration.
    // Keep this orientation-independent: ceilings must not acquire voxel shading seams.
    // Applied before tone mapping, preserving material detail without clipping the core.
    return 0.64 * pow(5.6, 2.25) * bestflashlightBeamParams.z * profile * axial;
}
