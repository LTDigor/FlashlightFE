package com.ltdigor.bestflashlight;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server settings are synchronized by NeoForge and take effect on world restart. */
public final class FlashlightConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue ENERGY_CAPACITY;
    public static final ModConfigSpec.IntValue ENERGY_PER_TICK;
    public static final ModConfigSpec.BooleanValue WORKS_UNDERWATER;
    public static final ModConfigSpec.DoubleValue BEAM_RANGE;
    public static final ModConfigSpec.DoubleValue CONE_ANGLE_DEGREES;
    static {
        var b = new ModConfigSpec.Builder();
        b.comment("Настройки фонарика. Изменения применяются после перезапуска мира/сервера.");
        ENERGY_CAPACITY = b.comment("Ёмкость батареи в FE (RF). По умолчанию 10 000 FE.")
            .worldRestart().defineInRange("energyCapacity", 10_000, 1, Integer.MAX_VALUE);
        ENERGY_PER_TICK = b.comment("Расход FE за игровой тик. При 20 TPS: FE/тик × 20 ≈ FE/с.",
            "По умолчанию 1 FE/тик ≈ 20 FE/с; 10 000 FE хватает примерно на 8 мин 20 с.",
            "При низком TPS реальное время работы увеличивается. 0 отключает расход энергии.")
            .worldRestart().defineInRange("energyPerTick", 1, 0, Integer.MAX_VALUE);
        WORKS_UNDERWATER = b.comment("Работает ли погружённый в воду фонарик. Если false, под водой нет света и расхода.")
            .worldRestart().define("worksUnderwater", true);
        BEAM_RANGE = b.comment("Дальность луча в блоках. Мягкое рассеяние света Minecraft выходит за геометрию луча.")
            .worldRestart().defineInRange("beamRange", 12.0, 1.0, 32.0);
        CONE_ANGLE_DEGREES = b.comment("Полный угол конуса в градусах (не половина). Меньше угол — уже луч.")
            .worldRestart().defineInRange("coneAngleDegrees", 15.0, 1.0, 90.0);
        SPEC = b.build();
    }
    private FlashlightConfig() {}
}
