package com.ltdigor.flashlightfe.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/** Narrow, fail-closed adapter for the tested Complementary program structure. */
public final class ComplementaryBeamPatch {
    private static final Pattern FUNCTION = Pattern.compile("\\bvoid\\s+DoLighting\\s*\\(");
    private static final Pattern DIFFUSE = Pattern.compile("\\bfinalDiffuse\\s*=\\s*sqrt\\s*\\(\\s*max\\s*\\(\\s*finalDiffuse\\s*,\\s*vec3\\s*\\(\\s*0\\.0\\s*\\)\\s*\\)\\s*\\)\\s*;");
    private static final String MARKER = "uniform vec4 bestflashlightBeamParams;";
    private static final String GLSL = readShader();

    private ComplementaryBeamPatch() {}

    public static boolean supportedPack(String name) {
        return "ComplementaryReimagined_r5.9.3.zip".equals(name)
            || "ComplementaryReimagined_r5.9.3".equals(name);
    }

    public static boolean surfaceProgram(String name) {
        return name.startsWith("gbuffers_") && !name.startsWith("gbuffers_hand")
            && !name.contains("sky") && !name.contains("cloud") && !name.contains("weather");
    }

    public static Optional<String> patch(String pack, String program, String source) {
        if (!supportedPack(pack) || !surfaceProgram(program) || source == null) return Optional.empty();
        if (source.contains(MARKER)) return Optional.of(source);
        var function = FUNCTION.matcher(source);
        var diffuse = DIFFUSE.matcher(source);
        if (!function.find() || !diffuse.find()) return Optional.empty();
        int functionStart = function.start(), diffuseStart = diffuse.start();
        if (function.find() || diffuse.find() || functionStart >= diffuseStart
            || !source.contains("blockLighting") || !source.contains("vanillaAO")
            || !source.contains("directionShade")) return Optional.empty();
        String result = source.substring(0, diffuseStart)
            // LDL mesh lighting may survive briefly while its chunk rebuild completes.
            // Raise block illumination to the beam level without adding it twice.
            + "finalDiffuse += max(vec3(0.0), bestflashlightSurfaceLight(playerPos) * blocklightCol"
            + " - pow2(directionShade * vanillaAO) * blockLighting);\n"
            + source.substring(diffuseStart);
        return Optional.of(result.substring(0, functionStart) + GLSL + "\n" + result.substring(functionStart));
    }

    private static String readShader() {
        try (var stream = ComplementaryBeamPatch.class.getResourceAsStream("/assets/bestflashlight/shaders/spotlight.glsl")) {
            if (stream == null) throw new IllegalStateException("Missing spotlight shader");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read spotlight shader", exception);
        }
    }
}
