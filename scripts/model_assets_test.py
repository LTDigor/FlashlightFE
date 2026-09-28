"""Offline regression checks for the shipped models, not just the generator."""
import json
import math
from pathlib import Path
import struct
import subprocess
import sys
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/bestflashlight'
MODELS = ASSETS / 'models/item'
NAMES = ('flashlight', 'flashlight_off', 'flashlight_on', 'flashlight_button',
         'headlamp', 'headlamp_off', 'headlamp_on')
CONTEXTS = ('gui', 'ground', 'fixed', 'firstperson_righthand', 'firstperson_lefthand',
            'thirdperson_righthand', 'thirdperson_lefthand', 'head')


def load(name):
    return json.loads((MODELS / (name + '.json')).read_text())


def resolve(name, seen=()):
    if name in seen:
        raise AssertionError('Cyclic model parent: ' + name)
    model = load(name)
    parent = model.get('parent', '')
    result = resolve(parent.split('/')[-1], (*seen, name)) if parent.startswith('bestflashlight:') else {}
    for key, value in model.items():
        if key in ('textures', 'display'):
            result[key] = {**result.get(key, {}), **value}
        else:
            result[key] = value
    return result


def png(path):
    data = path.read_bytes()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise AssertionError('Invalid PNG signature')
    offset, compressed, header, ended = 8, bytearray(), None, False
    while offset < len(data):
        size = struct.unpack_from('>I', data, offset)[0]
        kind = data[offset + 4:offset + 8]
        chunk = data[offset + 8:offset + 8 + size]
        crc = struct.unpack_from('>I', data, offset + 8 + size)[0]
        if crc != zlib.crc32(kind + chunk) & 0xffffffff:
            raise AssertionError('PNG CRC mismatch')
        if kind == b'IHDR':
            header = struct.unpack('>IIBBBBB', chunk)
        elif kind == b'IDAT':
            compressed.extend(chunk)
        elif kind == b'IEND':
            ended = True
        offset += size + 12
    if header is None or not ended or offset != len(data):
        raise AssertionError('Incomplete PNG')
    w, h, depth, mode, compression, filtering, interlace = header
    if (depth, mode, compression, filtering, interlace) != (8, 6, 0, 0, 0):
        raise AssertionError('Expected non-interlaced RGBA8 PNG')
    raw = zlib.decompress(compressed)
    if len(raw) != h * (w * 4 + 1):
        raise AssertionError('Incorrect PNG row length')
    rows = []
    for y in range(h):
        row = raw[y * (w * 4 + 1):(y + 1) * (w * 4 + 1)]
        if row[0] != 0:
            raise AssertionError('Generator must use unfiltered PNG rows')
        rows.append(row[1:])
    return w, h, rows


class ModelAssetsTest(unittest.TestCase):
    def test_headlamp_uses_own_materials(self):
        for texture in resolve('headlamp_off')['textures'].values():
            self.assertTrue(texture.startswith('bestflashlight:'), texture)

    def test_all_models_use_local_textures_and_valid_uvs(self):
        for name in NAMES:
            model = resolve(name)
            self.assertNotIn('loader', model)
            self.assertEqual('minecraft:cutout', model['render_type'])
            for texture in model['textures'].values():
                self.assertTrue(texture.startswith('bestflashlight:'), (name, texture))
                self.assertTrue((ASSETS / 'textures' / (texture.split(':')[1] + '.png')).is_file())
            for element in model['elements']:
                for direction, face in element['faces'].items():
                    self.assertIn(direction, ('north', 'south', 'east', 'west', 'up', 'down'))
                    self.assertIn(face['texture'][1:], model['textures'])
                    self.assertNotIn('cullface', face, 'Item/Curios quads must never be neighbor-culled')
                    u1, v1, u2, v2 = face['uv']
                    self.assertTrue(0 <= u1 < u2 <= 16 and 0 <= v1 < v2 <= 16, (name, face))

    def test_geometry_is_finite_bounded_and_native(self):
        for name in NAMES:
            for element in resolve(name)['elements']:
                for lo, hi in zip(element['from'], element['to']):
                    self.assertTrue(math.isfinite(lo) and math.isfinite(hi))
                    self.assertTrue(0 <= lo <= hi <= 16, (name, element['name']))
                rotation = element.get('rotation')
                if rotation:
                    self.assertIn(rotation['axis'], ('x', 'y', 'z'))
                    self.assertIn(rotation['angle'], (-45, -22.5, 0, 22.5, 45))
                    self.assertFalse(rotation.get('rescale', False))

    def test_flashlight_keeps_enabled_override(self):
        self.assertEqual([{'predicate': {'bestflashlight:enabled': 1},
                           'model': 'bestflashlight:item/flashlight_on'}], load('flashlight')['overrides'])

    def test_headlamp_has_only_enabled_override(self):
        self.assertEqual('bestflashlight:item/headlamp_off', load('headlamp')['parent'])
        self.assertEqual([{'predicate': {'bestflashlight:enabled': 1},
                           'model': 'bestflashlight:item/headlamp_on'}], load('headlamp')['overrides'])
        for old in ('headband', 'headband_empty', 'headband_loaded', 'headband_loaded_on'):
            self.assertFalse((MODELS / (old + '.json')).exists(), old)

    def test_switching_changes_only_lens_material_and_face_light(self):
        for off, on in (('flashlight_off', 'flashlight_on'), ('headlamp_off', 'headlamp_on')):
            a, b = resolve(off), resolve(on)
            self.assertEqual(a['display'], b['display'])
            self.assertNotEqual(a['textures']['lens'], b['textures']['lens'])
            b['textures']['lens'] = a['textures']['lens']
            lit_faces = 0
            for element in b['elements']:
                for face in element['faces'].values():
                    extra = face.pop('neoforge_data', None)
                    if extra:
                        self.assertEqual('#lens', face['texture'])
                        self.assertEqual(15, extra['block_light'])
                        self.assertEqual(15, extra['sky_light'])
                        lit_faces += 1
            self.assertEqual(1, lit_faces, on)
            self.assertEqual(a['elements'], b['elements'])
            self.assertEqual(a['textures'], b['textures'])

    def test_lenses_are_recessed_and_face_forward(self):
        for name in ('flashlight_off', 'headlamp_off'):
            elements = resolve(name)['elements']
            lens = next(e for e in elements if e['name'] == 'lens')
            rim = next(e for e in elements if e['name'] == 'bezel_front')
            self.assertEqual({'north'}, set(lens['faces']))
            self.assertGreater(lens['from'][2] - rim['from'][2], .35)
            self.assertLess(lens['to'][2], 8)

    def test_octagons_have_eight_side_faces_not_intersecting_square_solids(self):
        for name, prefix in (('flashlight_off', 'grip_side_'), ('headlamp_off', 'housing_side_')):
            sides = [e for e in resolve(name)['elements'] if e['name'].startswith(prefix)]
            self.assertEqual(8, len(sides))
            self.assertTrue(all(len(e['faces']) == 1 for e in sides))
            self.assertEqual(4, sum('rotation' in e for e in sides))

    def test_wider_tail_and_trim_close_their_exposed_shoulders(self):
        elements = {e['name']: e for e in resolve('flashlight_off')['elements']}
        for name, direction, z in (('tail_front', 'north', 13.4),
                                    ('tail_trim_front', 'north', 13.05),
                                    ('tail_trim_back', 'south', 13.32)):
            self.assertIn(name, elements, 'An open sleeve exposes the inside of the shaft')
            self.assertEqual({direction}, set(elements[name]['faces']))
            self.assertEqual(z, elements[name]['from'][2])
            self.assertEqual(z, elements[name]['to'][2])

    def test_headlamp_closes_its_back_above_and_below_the_mount(self):
        elements = {e['name']: e for e in resolve('headlamp_off')['elements']}
        self.assertIn('housing_back', elements)
        back = elements['housing_back']
        self.assertEqual({'south'}, set(back['faces']))
        self.assertEqual(2.97, back['from'][2])
        self.assertEqual(back['from'][2], back['to'][2])
        self.assertEqual(12.25, back['from'][1])
        self.assertEqual(15.55, back['to'][1])

    def test_button_stays_visible_at_deepest_existing_press(self):
        button = resolve('flashlight_button')['elements'][0]
        body = resolve('flashlight_off')['elements']
        gasket = next(e for e in body if e['name'] == 'button_gasket')
        self.assertGreater(button['to'][1] - .72, gasket['to'][1])
        for axis in (0, 2):
            self.assertGreater(button['from'][axis], gasket['from'][axis])
            self.assertLess(button['to'][axis], gasket['to'][axis])
        self.assertLessEqual(button['to'][0] - button['from'][0], 1.6)
        self.assertFalse(any(e['name'] == 'button' for e in body), 'Animated button must not be duplicated')

    def test_serial_plate_clears_the_grip_surface(self):
        elements = {e['name']: e for e in resolve('flashlight_off')['elements']}
        plate = elements['serial_plate']
        grip = elements['grip_side_2']
        self.assertEqual(plate['from'][0], plate['to'][0])
        self.assertGreaterEqual(plate['from'][0] - grip['from'][0], .01 - 1e-9)

    def test_headlamp_fits_outer_skin_layer_and_sits_above_eyes(self):
        elements = resolve('headlamp_off')['elements']
        strap = {e['name']: e for e in elements if e['name'].startswith('strap_')}
        self.assertLess(strap['strap_front']['to'][2], 3.5)
        self.assertGreater(strap['strap_back']['from'][2], 12.5)
        self.assertLess(strap['strap_left']['to'][0], 3.5)
        self.assertGreater(strap['strap_right']['from'][0], 12.5)
        for element in elements:
            self.assertGreaterEqual(element['from'][1], 12, element['name'])
        self.assertTrue(any(e['name'] == 'battery' for e in elements))
        self.assertEqual([0, 0, 0], resolve('headlamp_off')['display']['head']['rotation'])

    def test_headlamp_head_transform_clears_vanilla_head(self):
        elements = {e['name']: e for e in resolve('headlamp_off')['elements']}
        display = resolve('headlamp_off')['display']['head']
        self.assertEqual([0, 0, 0], display['rotation'])

        # CustomHeadLayer: translate Y -.25, rotate Y 180, scale (.625, -.625, -.625).
        # Item coordinates are centered on 8 and display translations use sixteenths.
        def world(axis, value):
            index = 'xyz'.index(axis)
            item = (value - 8) * display['scale'][index] / 16 + display['translation'][index] / 16
            return (-.625 if axis == 'x' else .625 if axis == 'z' else -.625) * item \
                + (-.25 if axis == 'y' else 0)

        sides = (world('x', elements['strap_left']['from'][0]),
                 world('x', elements['strap_right']['to'][0]))
        self.assertLess(min(sides), -.25)
        self.assertGreater(max(sides), .25)
        self.assertLess(world('z', elements['strap_front']['from'][2]), -.25)
        self.assertGreater(world('z', elements['strap_back']['to'][2]), .25)
        self.assertLess(world('z', elements['lens']['from'][2]), -.35)
        self.assertTrue(-.5 < world('y', 13.9) < -.25, 'Lamp should sit on forehead')

    def test_models_have_all_display_contexts_and_small_face_budgets(self):
        for name in ('flashlight_off', 'headlamp_off'):
            display = resolve(name)['display']
            self.assertEqual(set(CONTEXTS), set(display))
            for transform in display.values():
                self.assertTrue(all(math.isfinite(x) for key in transform for x in transform[key]))
                self.assertTrue(all(0 < x <= 2 for x in transform['scale']))
        for body, extra in (('flashlight_off', 'flashlight_button'), ('headlamp_off', None)):
            count = sum(len(e['faces']) for e in resolve(body)['elements'])
            if extra:
                count += sum(len(e['faces']) for e in resolve(extra)['elements'])
            self.assertLessEqual(count, 128, (body, count))

    def test_atlases_are_valid_and_on_only_changes_lens_tile(self):
        off = png(ASSETS / 'textures/item/industrial.png')
        on = png(ASSETS / 'textures/item/industrial_on.png')
        self.assertEqual((128, 128), off[:2])
        self.assertEqual(off[:2], on[:2])
        differences = 0
        for y, (a, b) in enumerate(zip(off[2], on[2])):
            for x in range(128):
                pa, pb = a[x * 4:x * 4 + 4], b[x * 4:x * 4 + 4]
                self.assertIn(pa[3], (0, 255))
                self.assertEqual(pa[3], pb[3])
                if pa != pb:
                    self.assertTrue(96 <= x < 128 and 64 <= y < 96)
                    differences += 1
        self.assertGreater(differences, 100)

    def test_committed_assets_match_generator(self):
        process = subprocess.run([sys.executable, str(ROOT / 'scripts/generate_model_assets.py'), '--check'],
                                 cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(0, process.returncode, process.stdout + process.stderr)

    def test_headlamp_recipe_and_curios_tag(self):
        data = ROOT / 'src/main/resources/data'
        recipe = json.loads((data / 'bestflashlight/recipe/headlamp.json').read_text())
        self.assertEqual('minecraft:crafting_shaped', recipe['type'])
        self.assertEqual(['SWS', 'IGI', 'CRC'], recipe['pattern'])
        self.assertEqual({'S': {'item': 'minecraft:string'}, 'W': {'tag': 'minecraft:wool'},
                          'I': {'item': 'minecraft:iron_ingot'}, 'G': {'item': 'minecraft:glass'},
                          'C': {'item': 'minecraft:copper_ingot'}, 'R': {'item': 'minecraft:redstone'}}, recipe['key'])
        self.assertEqual({'id': 'bestflashlight:headlamp', 'count': 1}, recipe['result'])
        self.assertEqual({'replace': False, 'values': ['bestflashlight:headlamp']},
                         json.loads((data / 'curios/tags/item/head.json').read_text()))
        for old in ('bestflashlight/recipe/headband.json', 'bestflashlight/recipe/headband_mount.json',
                    'bestflashlight/tags/item/headband.json'):
            self.assertFalse((data / old).exists(), old)

    def test_translations_have_standalone_headlamp(self):
        for locale in ('en_us', 'ru_ru', 'zh_cn'):
            values = json.loads((ASSETS / 'lang' / (locale + '.json')).read_text())
            self.assertIn('item.bestflashlight.headlamp', values)
            self.assertTrue(values['item.bestflashlight.headlamp'])
            self.assertIn('key.bestflashlight.toggle', values)
            self.assertFalse(any('headband' in key or 'mount' in key for key in values), locale)


if __name__ == '__main__':
    unittest.main()
