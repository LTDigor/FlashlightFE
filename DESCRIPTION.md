Rechargeable flashlights for Minecraft 1.21.1 on NeoForge.

- Light your path with a handheld flashlight or a hands-free headlamp.
- Recharge using a compatible Forge Energy item charger.
- Enjoy an animated power button and configurable lighting.

Controls: Right-click for the handheld flashlight; I for the headlamp. Both controls are rebindable.

- **Directional lighting:** aim your flashlight where you look. The headband leaves both hands free.
- **Forge Energy (FE):** charge either lamp form with compatible item chargers, including Immersive Engineering's Charging Station. Freshly crafted lamps start empty.
- **Optional Curios integration:** with Curios, wear the assembled headband in its standard `head` slot alongside an armor helmet. Without Curios, it uses Minecraft's head slot.
- **Optional JEI integration:** recipes, headband mounting instructions, and charged/discharged item variants.
- **Mechanical button animation:** a black button moves when pressed, visible in first and third person.
- **Creative support:** empty and partly charged lamps work without consuming or refilling saved energy.
- **Multiplayer:** the server validates switching and energy use; players can see each other's flashlight beams.

Freshly crafted flashlights are empty—charge them before use.

Source & guide: https://github.com/LTDigor/FlashlightFE

Report an issue: https://github.com/LTDigor/FlashlightFE/issues
Right-click toggles a flashlight in your main hand, or an offhand flashlight if your main hand is empty. If your main hand holds another item, normal Minecraft use takes priority. A separately assigned handheld key can toggle the offhand flashlight even while your main hand is occupied.

Craft an empty headband using wool and string. Combine it with a flashlight in any two crafting slots, then use it. Curios equips it in `head`; without Curios it replaces the vanilla head-slot item. To detach the flashlight, craft the loaded headband by itself. Charge, custom names and components are preserved.

## Configuration

Settings are in `config/bestflashlight-server.toml`, synchronized by the server, and take effect after restarting the world/server.

| Setting | Default | Purpose |
| --- | --- | --- |
| `energyCapacity` | 10000 FE | Battery capacity |
| `energyPerTick` | 1 FE | Energy cost per emitting tick; 0 disables drain |
| `beamRange` | 12 blocks | Beam length, configurable from 1 to 32 |
| `coneAngleDegrees` | 15 degrees | Full beam angle, configurable from 1 to 90 |
| `worksUnderwater` | true | Whether submerged lamps work |

At 20 TPS, the default full battery provides about **8 minutes 20 seconds** of light. The beam uses Minecraft block lighting, so some soft light spreads outside the cone. Switching out of creative restores normal energy requirements.

## Requirements

Install on **both client and server**:

- Minecraft **1.21.1**
- NeoForge **21.1.249 or newer within 21.1**
- Curios **9.5.1 or newer within version 9** — optional

JEI **19.18+ within version 19** is optional. An FE item charger from another mod is useful for survival play. GeckoLib and a separate dynamic-lighting mod are not required.

**MIT license. Author: LTDigor.** Source and issue tracker: https://github.com/LTDigor/FlashlightFE

---

## На русском

**Flashlight FE** добавляет ручной и налобный фонари с направленным светом, зарядкой **Forge Energy**, необязательной поддержкой **Curios** и интеграцией **JEI**.

Ручной фонарь переключается на **ПКМ**, налобный — на **I**. Обе привязки можно поменять в настройках управления. Механическая кнопка фонаря анимирована от первого и третьего лица. В креативе фонарь работает даже разряженным, сохранённый заряд не меняется.

Для налобного фонаря соедините крепление и фонарик в любых ячейках крафта, затем используйте результат: с Curios он встанет в слот `head`, без Curios — в обычный слот головы вместо шлема. Для снятия положите собранное крепление одно в сетку крафта. Заряд, имя и компоненты сохраняются.
