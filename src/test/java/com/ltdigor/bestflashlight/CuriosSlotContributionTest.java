package com.ltdigor.bestflashlight;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class CuriosSlotContributionTest {
    @Test
    void shippedResourcesDefineNoCuriosSlotsOrEntitySlotAssignments() throws IOException {
        String projectDir = System.getProperty("bestflashlight.projectDir");
        Assumptions.assumeTrue(projectDir != null, "Run through Gradle so the project directory is known");
        Path shippedData = Path.of(projectDir, "src", "main", "resources", "data");
        List<Path> offenders;
        try (Stream<Path> paths = Files.walk(shippedData)) {
            offenders = paths.filter(path -> {
                String normalized = path.toString().replace('\\', '/');
                return normalized.contains("/curios/slots/") || normalized.contains("/curios/entities/");
            }).toList();
        }
        assertTrue(offenders.isEmpty(),
            "The mod must not contribute Curios slots or entity slot assignments, the dev head slot lives in src/smoke: " + offenders);
    }
}
