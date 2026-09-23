package com.ltdigor.flashlightfe;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OptionalCuriosClassloadingTest {
    @Test
    void coreEquipmentClassesLoadWithoutCuriosOnTheRuntimeClasspath() {
        URL[] classpathWithoutCurios = Arrays.stream(System.getProperty("java.class.path").split(System.getProperty("path.separator")))
            .filter(entry -> !entry.contains("curios-neoforge"))
            .map(Path::of)
            .map(path -> {
                try {
                    return path.toUri().toURL();
                } catch (java.net.MalformedURLException exception) {
                    throw new IllegalArgumentException(exception);
                }
            })
            .toArray(URL[]::new);

        try (URLClassLoader loader = new URLClassLoader(classpathWithoutCurios, null)) {
            assertThrows(ClassNotFoundException.class,
                () -> Class.forName("top.theillusivec4.curios.api.CuriosApi", false, loader));
            assertDoesNotThrow(() -> Class.forName("com.ltdigor.flashlightfe.CuriosCompatibility", true, loader));
            assertDoesNotThrow(() -> Class.forName("com.ltdigor.flashlightfe.FlashlightItem", true, loader));
            assertDoesNotThrow(() -> Class.forName("com.ltdigor.flashlightfe.HeadbandItem", true, loader));
            assertDoesNotThrow(() -> Class.forName("com.ltdigor.flashlightfe.LampSource", true, loader));
            assertDoesNotThrow(() -> Class.forName("com.ltdigor.flashlightfe.mixin.CuriosCommonEventsMixin", true, loader));
            assertDoesNotThrow(() -> Class.forName("com.ltdigor.flashlightfe.mixin.BestFlashlightMixinPlugin", true, loader));
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
