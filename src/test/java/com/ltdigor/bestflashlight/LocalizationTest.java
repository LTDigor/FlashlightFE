package com.ltdigor.bestflashlight;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class LocalizationTest {
    @Test void russianEnglishAndSimplifiedChineseHaveTheSameKeys() {
        JsonObject english = language("en_us");
        assertEquals(english.keySet(), language("ru_ru").keySet());
        assertEquals(english.keySet(), language("zh_cn").keySet());
    }

    private static JsonObject language(String code) {
        String path = "/assets/bestflashlight/lang/" + code + ".json";
        try (var stream = LocalizationTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing language file: " + path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot read language file: " + path, exception);
        }
    }
}
