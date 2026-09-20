#!/usr/bin/env python3
"""Authoring source for the shipped industrial lamp models (Python stdlib only).

Run normally to regenerate JSON/PNG, or --check to detect stale checked-in assets.
The game loads ordinary baked JSON; this script is never needed at runtime.
Octagons use eight side quads and alpha-cutout end caps, not intersecting cubes.
"""
import argparse
import copy
import json
import math
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/bestflashlight'
ATLAS = 'bestflashlight:item/industrial'
# Each tile is 32x32 pixels in a 128x128 atlas; UV coordinates stay in [0,16].
TILES = {'grip': (0, 0), 'housing': (1, 0), 'steel': (2, 0), 'rubber': (3, 0),
         'orange': (0, 1), 'strap': (1, 1), 'label': (2, 1), 'power': (3, 1),
         'cap': (0, 2), 'steel_cap': (1, 2), 'bezel': (2, 2), 'lens': (3, 2),
         'battery': (0, 3), 'ribs': (1, 3)}
DIRECTIONS = ('north', 'south', 'east', 'west', 'up', 'down')


def face(material, light=False):
    u, v = TILES[material]
    value = {'uv': [u * 4, v * 4, (u + 1) * 4, (v + 1) * 4],
             'texture': '#lens' if material == 'lens' else '#atlas'}
    if light:
        value['neoforge_data'] = {'block_light': 15, 'sky_light': 15, 'ambient_occlusion': False}
    return value


def box(name, start, end, material, directions=DIRECTIONS):
    return {'name': name, 'from': list(start), 'to': list(end),
            'faces': {direction: face(material) for direction in directions}}


def cap(name, cx, cy, radius, z, material, direction='north'):
    return box(name, (cx - radius, cy - radius, z), (cx + radius, cy + radius, z),
               material, (direction,))


def shell(name, cx, cy, radius, z0, z1, material, inside=False):
    """Eight joined side quads. All rotations are native +/-45 degree Z rotations."""
    a = round(radius * (math.sqrt(2) - 1), 6)
    templates = [((cx - a, cy + radius, z0), (cx + a, cy + radius, z1), 'up'),
                 ((cx - a, cy - radius, z0), (cx + a, cy - radius, z1), 'down'),
                 ((cx + radius, cy - a, z0), (cx + radius, cy + a, z1), 'east'),
                 ((cx - radius, cy - a, z0), (cx - radius, cy + a, z1), 'west')]
    opposite = {'up': 'down', 'down': 'up', 'east': 'west', 'west': 'east'}
    elements = []
    for index, (start, end, direction) in enumerate(templates + [templates[2], templates[2], templates[3], templates[3]]):
        element = box(f'{name}_side_{index}', start, end, material,
                      (opposite[direction] if inside else direction,))
        if index >= 4:
            element['rotation'] = {'origin': [cx, cy, 8], 'axis': 'z',
                                   'angle': 45 if index % 2 == 0 else -45, 'rescale': False}
        elements.append(element)
    return elements


def optic(cx, cy, radius, front, back):
    inner = radius * .75
    return (shell('bezel', cx, cy, radius, front, front + .35, 'steel')
            + shell('reflector', cx, cy, inner, front, back, 'steel', inside=True)
            + [cap('bezel_front', cx, cy, radius, front, 'bezel'),
               cap('bezel_back', cx, cy, radius, front + .35, 'bezel', 'south'),
               cap('lens', cx, cy, inner, back, 'lens')])


def transform(rotation=(0, 0, 0), translation=(0, 0, 0), scale=1):
    return {'rotation': list(rotation), 'translation': list(translation), 'scale': [scale] * 3}


def model(elements, display=None):
    result = {'parent': 'minecraft:block/block', 'ambientocclusion': False,
              'gui_light': 'side', 'render_type': 'minecraft:cutout',
              'textures': {'particle': ATLAS, 'atlas': ATLAS, 'lens': ATLAS}, 'elements': elements}
    if display is not None:
        result['display'] = display
    return result


def lit(parent, elements):
    elements = copy.deepcopy(elements)
    for element in elements:
        for value in element['faces'].values():
            if value['texture'] == '#lens':
                value['neoforge_data'] = {'block_light': 15, 'sky_light': 15, 'ambient_occlusion': False}
    return {'parent': f'bestflashlight:item/{parent}', 'textures': {'lens': ATLAS + '_on'},
            'elements': elements}


def models():
    hand_display = {
        'gui': transform((28, 145, -12), (0, 0, 0), 1.18),
        # Keep the emitter facing -Z. Minecraft mirrors X automatically for left hands.
        'firstperson_righthand': transform((0, 0, 0), (-1.25, 1.65, -1.0), .78),
        'firstperson_lefthand': transform((0, 0, 0), (-1.25, 1.65, -1.0), .78),
        'thirdperson_righthand': transform((90, 0, 0), (0, 1.5, 1), .72),
        'thirdperson_lefthand': transform((90, 0, 0), (0, 1.5, 1), .72),
        'ground': transform((0, 0, 0), (0, 2.25, 0), .65),
        'fixed': transform((0, 90, -35), (0, 0, 0), 1),
        'head': transform((0, 0, 0), (0, 0, 0), .65)}
    hand = (shell('grip', 8, 8, 1.65, 7.0, 13.4, 'grip')
            + shell('tail', 8, 8, 1.78, 13.4, 14.2, 'rubber')
            + [cap('tail_end', 8, 8, 1.78, 14.2, 'cap', 'south'),
               cap('tail_front', 8, 8, 1.78, 13.4, 'cap')]
            + shell('tail_trim', 8, 8, 1.72, 13.05, 13.32, 'steel')
            + [cap('tail_trim_front', 8, 8, 1.72, 13.05, 'steel_cap'),
               cap('tail_trim_back', 8, 8, 1.72, 13.32, 'steel_cap', 'south')]
            + shell('neck', 8, 8, 1.85, 6.05, 7.0, 'ribs')
            + [cap('neck_back', 8, 8, 1.85, 7.0, 'cap', 'south')]
            + shell('housing', 8, 8, 2.4, 3.25, 6.05, 'housing')
            + [cap('housing_back', 8, 8, 2.4, 6.05, 'cap', 'south')]
            + optic(8, 8, 2.6, 2.9, 3.65)
            + [box('button_gasket', (7.1, 9.57, 8.25), (8.9, 9.76, 10.25), 'rubber'),
               box('clip_anchor', (7.55, 6.1, 12.8), (8.45, 6.45, 13.2), 'steel'),
               box('pocket_clip', (7.6, 5.98, 9.9), (8.4, 6.13, 13.1), 'steel'),
               box('serial_plate', (9.651, 7.65, 10.5), (9.651, 8.35, 12.25), 'label', ('east',))])
    button = box('button', (7.25, 9.55, 8.4), (8.75, 10.5, 10.1), 'orange',
                 ('north', 'south', 'east', 'west', 'up'))
    button['faces']['up'] = face('power')
    band_display = {
        'gui': transform((24, 145, 0), (0, -5.5, 0), 1.12),
        'ground': transform((0, 0, 0), (0, -1.5, 0), .55),
        'fixed': transform((0, 180, 0), (0, -5.5, 0), 1),
        'firstperson_righthand': transform((0, 30, 0), (0, -3.5, 0), .6),
        'firstperson_lefthand': transform((0, 30, 0), (0, -3.5, 0), .6),
        'thirdperson_righthand': transform((75, 0, 0), (0, 1, -3), .6),
        'thirdperson_lefthand': transform((75, 0, 0), (0, 1, -3), .6),
        'head': transform((0, 180, 0), (0, -1, 0), 1)}
    band = [box('strap_front', (3.25, 13, 3.25), (12.75, 14.5, 3.45), 'strap'),
            box('strap_back', (3.25, 13, 12.55), (12.75, 14.5, 12.75), 'strap'),
            box('strap_left', (3.25, 13, 3.45), (3.45, 14.5, 12.55), 'strap', ('up', 'down', 'west', 'east')),
            box('strap_right', (12.55, 13, 3.45), (12.75, 14.5, 12.55), 'strap', ('up', 'down', 'west', 'east')),
            box('front_mount', (6.05, 12.55, 2.95), (9.95, 15.15, 3.3), 'rubber'),
            box('hinge_left', (5.85, 13.2, 2.65), (6.35, 14.5, 3.15), 'steel'),
            box('hinge_right', (9.65, 13.2, 2.65), (10.15, 14.5, 3.15), 'steel'),
            box('strap_buckle', (12.77, 12.85, 10.1), (13.05, 14.65, 11.3), 'rubber')]
    loaded = (copy.deepcopy(band) + shell('housing', 8, 13.9, 1.65, 1.55, 2.97, 'housing')
              + [cap('housing_back', 8, 13.9, 1.65, 2.97, 'cap', 'south')]
              + optic(8, 13.9, 1.8, 1.25, 1.9)
              + [box('head_switch', (7.55, 15.45, 2.05), (8.45, 15.7, 2.7), 'orange',
                     ('north', 'south', 'east', 'west', 'up')),
                 box('battery', (6.2, 12.6, 12.7), (9.8, 14.8, 14.0), 'battery'),
                 box('battery_latch', (7.55, 12.4, 14.0), (8.45, 13.0, 14.15), 'steel',
                     ('south', 'east', 'west', 'up', 'down')),
                 box('battery_marker', (6.55, 13.3, 14.01), (7.15, 14.25, 14.01), 'orange', ('south',))])
    return {
        'flashlight': {'parent': 'bestflashlight:item/flashlight_off', 'overrides': [
            {'predicate': {'bestflashlight:enabled': 1}, 'model': 'bestflashlight:item/flashlight_on'}]},
        'flashlight_off': model(hand, hand_display),
        'flashlight_on': lit('flashlight_off', hand),
        'flashlight_button': model([button]),
        'headband': {'parent': 'bestflashlight:item/headband_empty', 'overrides': [
            {'predicate': {'bestflashlight:mounted': 1}, 'model': 'bestflashlight:item/headband_loaded'},
            {'predicate': {'bestflashlight:mounted': 1, 'bestflashlight:enabled': 1},
             'model': 'bestflashlight:item/headband_loaded_on'}]},
        'headband_empty': model(band, band_display),
        'headband_loaded': {**model(loaded), 'parent': 'bestflashlight:item/headband_empty'},
        'headband_loaded_on': lit('headband_loaded', loaded)}


def octagon(x, y, radius):
    return max(abs(x), abs(y)) <= radius and abs(x) + abs(y) <= radius * math.sqrt(2)


def atlas(enabled):
    """Original pixel-art surfaces; no borrowed vanilla block textures or PNG dependencies."""
    palette = [(42, 47, 51), (65, 73, 79), (153, 168, 176), (26, 31, 35),
               (195, 98, 36), (44, 52, 57), (34, 42, 47), (195, 98, 36),
               (44, 50, 55), (153, 168, 176), (165, 179, 186), (62, 80, 91),
               (48, 57, 62), (46, 54, 59), (48, 57, 62), (26, 31, 35)]
    pixels = bytearray()
    for y in range(128):
        pixels.append(0)  # PNG filter: None. Easy to validate without Pillow.
        for x in range(128):
            tile = (y // 32) * 4 + x // 32
            u, v = x % 32, y % 32
            dx, dy = u - 15.5, v - 15.5
            noise = ((u * 13 + v * 7 + u * v * 3) % 7) - 3
            color, alpha = palette[tile], 255
            delta = noise
            if tile == 0:  # rubberized longitudinal grip ribs
                delta += -11 if v % 7 in (0, 1) else 2
                delta += 6 if u in (3, 4) else 0
            elif tile in (1, 12, 13):
                delta += -10 if v in (0, 1, 28, 29, 30, 31) else 0
                delta += 7 if u in (2, 3) else 0
                if tile == 13:
                    delta -= 13 if v % 5 <= 1 else 0
            elif tile in (2, 9, 10):
                delta = (v % 3 - 1) * 6 + noise
            elif tile == 5:  # woven strap and a restrained edge stitch
                delta = (4 if (u + v) % 2 == 0 else -3)
                if v in (4, 27) and u % 5 < 3:
                    delta += 30
            elif tile == 6:  # small equipment identification bars
                if 5 <= u <= 25 and v in (8, 9, 15, 16, 22, 23):
                    color = (163, 175, 177) if v < 20 else (188, 94, 35)
            elif tile == 7:  # mechanical orange switch with a power symbol
                power = (7.5 <= math.hypot(dx, dy) <= 9.5 and not (dy < -3 and abs(dx) < 5))
                power |= abs(dx) <= 1.5 and -11 <= dy <= 0
                if power:
                    color, delta = (39, 43, 43), 0
            if tile in (8, 9, 10, 11):
                alpha = 255 if octagon(dx, dy, 16) else 0
                if tile == 10 and octagon(dx, dy, 12):
                    alpha = 0
                if tile == 11:
                    radius = math.hypot(dx, dy)
                    if enabled:
                        color = (251, 244, 208) if radius < 10 else (190, 206, 210)
                    else:
                        color = (47, 66, 78) if radius < 10 else (119, 141, 153)
                        if -10 <= dx <= -4 and -10 <= dy <= -7:
                            color = (190, 211, 218)
                        if abs(dx) < 3 and abs(dy) < 3:
                            color = (161, 131, 72)
                    delta = 0
            pixels.extend((*[max(0, min(255, component + delta)) for component in color], alpha))

    def chunk(kind, value):
        return struct.pack('>I', len(value)) + kind + value + struct.pack('>I', zlib.crc32(kind + value) & 0xffffffff)

    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 128, 128, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(bytes(pixels), 9)) + chunk(b'IEND', b''))


def model_json(value):
    # One element per line keeps the geometry reviewable without thousands of scalar lines.
    lines = []
    for key, field in value.items():
        if key == 'elements':
            elements = ',\n'.join('    ' + json.dumps(e, separators=(',', ':')) for e in field)
            lines.append('  "elements": [\n' + elements + '\n  ]')
        else:
            lines.append('  ' + json.dumps(key) + ': ' + json.dumps(field, separators=(',', ':')))
    return ('{\n' + ',\n'.join(lines) + '\n}\n').encode()


def outputs():
    result = {ASSETS / 'models/item' / (name + '.json'): model_json(value) for name, value in models().items()}
    result[ASSETS / 'textures/item/industrial.png'] = atlas(False)
    result[ASSETS / 'textures/item/industrial_on.png'] = atlas(True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    stale = []
    for path, expected in outputs().items():
        if args.check:
            if not path.is_file() or path.read_bytes() != expected:
                stale.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(expected)
    if stale:
        raise SystemExit('Stale model assets; run scripts/generate_model_assets.py:\n' + '\n'.join(stale))
    print('Industrial model assets ' + ('verified.' if args.check else 'generated.'))


if __name__ == '__main__':
    main()
