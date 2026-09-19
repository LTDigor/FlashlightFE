# Flashlight FE

Rechargeable handheld and headband flashlights for **Minecraft 1.21.1 / NeoForge**.

## Features

- **Directional lighting:** aim your flashlight where you look. The headband leaves both hands free.
- **Forge Energy (FE):** charge either lamp form with compatible item chargers, including Immersive Engineering's Charging Station. Freshly crafted lamps start empty.
- **Curios integration:** wear the assembled headband in the standard `head` slot, including alongside an armor helmet.
- **Optional JEI integration:** recipes, headband mounting instructions, and charged/discharged item variants.
- **Mechanical button animation:** a black button moves when pressed, visible in first and third person.
- **Creative support:** empty and partly charged lamps work without consuming or refilling saved energy.
- **Multiplayer:** the server validates switching and energy use; players can see each other's flashlight beams.

## Controls and crafting

The defaults are **right mouse button** for the handheld flashlight and **I** for the headband. Both bindings can be changed in Minecraft Controls, including to mouse buttons. Holding a button does not repeatedly toggle the light.

Right-click toggles a flashlight in your main hand, or an offhand flashlight if your main hand is empty. If your main hand holds another item, normal Minecraft use takes priority. A separately assigned handheld key can toggle the offhand flashlight even while your main hand is occupied.

Craft an empty headband using wool and string. Combine it with a flashlight in any two crafting slots, then equip it in Curios `head`. To detach the flashlight, craft the loaded headband by itself. Charge, custom names and components are preserved.

## Configuration

Server defaults are read from `config/bestflashlight-server.toml`; an existing `<world>/serverconfig/bestflashlight-server.toml` overrides them for that world. Values are synchronized to clients and world-restart settings take effect after restarting the world/server.

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
- Curios **9.5.1 or newer within version 9**

JEI **19.18+ within version 19** is optional. LambDynamicLights is also optional; when present, the local flashlight uses its smoother client-side directional cone while the normal server light remains the compatibility fallback. An FE item charger from another mod is useful for survival play. GeckoLib and a separate dynamic-lighting mod are not required.

**MIT license. Author: LTDigor.** Source and issue tracker: https://github.com/LTDigor/FlashlightFE

---

## На русском

**Flashlight FE** добавляет ручной и налобный фонари с направленным светом, зарядкой **Forge Energy**, поддержкой **Curios** и необязательной интеграцией **JEI**.

Ручной фонарь переключается на **ПКМ**, налобный — на **I**. Обе привязки можно поменять в настройках управления. Механическая кнопка фонаря анимирована от первого и третьего лица. В креативе фонарь работает даже разряженным, сохранённый заряд не меняется.

Для налобного фонаря соедините крепление и фонарик в любых ячейках крафта, затем наденьте результат в слот Curios `head`. Для снятия положите собранное крепление одно в сетку крафта. Заряд, имя и компоненты сохраняются.

По умолчанию настройки читаются из `config/bestflashlight-server.toml`; файл `<world>/serverconfig/bestflashlight-server.toml`, если он существует, переопределяет их для конкретного мира. Там настраиваются емкость, расход FE, дальность и угол луча, работа под водой. Значение `energyPerTick=0` отключает расход. Для world-restart параметров после изменения нужен перезапуск мира или сервера.

Для зарядки подходят совместимые FE-зарядники, например станция Immersive Engineering. Новые фонари создаются без заряда. Мод устанавливается на клиент и сервер вместе с Curios; версии зависимостей указаны выше. Лицензия **MIT**, автор **LTDigor**.
