import unittest
import numpy as np
from test_shader_beam import measure_spot


class SpotAcceptanceTest(unittest.TestCase):
    def image(self, square=False, shift=0):
        y, x = np.mgrid[:256, :256]
        x, y = x - 127.5 - shift, y - 127.5
        r = np.maximum(abs(x), abs(y)) if square else np.hypot(x, y)
        intensity = np.clip(1 - (r / 60) ** 2, 0, 1)
        return np.repeat(intensity[:, :, None], 3, axis=2)

    def test_round_spot_passes(self):
        result = measure_spot(np.zeros((256, 256, 3)), self.image(), np.ones((256, 256, 3)))
        self.assertLess(result['max_radial_error_px'], 2)
        self.assertLess(result['center_error_px'], 1)

    def test_square_spot_fails_even_when_its_width_equals_its_height(self):
        result = measure_spot(np.zeros((256, 256, 3)), self.image(square=True), np.ones((256, 256, 3)))
        self.assertGreater(result['max_radial_error_px'], 2)

    def test_off_center_circle_is_rejected(self):
        result = measure_spot(np.zeros((256, 256, 3)), self.image(shift=8), np.ones((256, 256, 3)))
        self.assertGreater(result['center_error_px'], 2)

    def test_dark_frame_does_not_pass_as_a_circle(self):
        with self.assertRaises(ValueError):
            measure_spot(np.zeros((256, 256, 3)), np.zeros((256, 256, 3)), np.ones((256, 256, 3)))


if __name__ == '__main__':
    unittest.main()
