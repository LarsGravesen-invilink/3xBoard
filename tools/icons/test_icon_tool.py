import tempfile
import unittest
from pathlib import Path

from PIL import Image

from icon_tool import DENSITIES, adjust, generate, load_image


class IconToolsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (Path(__file__).parent / "smoke").mkdir(exist_ok=True)

    def test_full_bleed_density_exports(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent / "smoke") as directory:
            output = Path(directory) / "generated"
            generate(Image.new("RGBA", (400, 300), "#246345"), output)
            for density, ratio in DENSITIES.items():
                destination = output / "res" / f"mipmap-{density}"
                with Image.open(destination / "ic_launcher.png") as image:
                    self.assertEqual(image.size, (round(48 * ratio), round(48 * ratio)))
                    self.assertEqual(image.getpixel((0, 0)), (36, 99, 69, 255))
                with Image.open(destination / "ic_launcher_background.png") as image:
                    self.assertEqual(image.size, (round(108 * ratio), round(108 * ratio)))
                    self.assertEqual(image.getextrema()[3], (255, 255))
            self.assertTrue((output / "res/mipmap-anydpi-v26/ic_launcher.xml").exists())
            self.assertTrue((output / "artwork/launcher-previews.png").exists())
            with self.assertRaises(ValueError):
                generate(Image.new("RGBA", (20, 20)), output)

    def test_recolor_keeps_transparency(self):
        image = Image.new("RGBA", (8, 8), (140, 30, 40, 120))
        result = adjust(image, hue=60, recolor="#DBF0A3")
        self.assertEqual(result.getpixel((0, 0)), (219, 240, 163, 120))

    def test_svg_rasterization(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent / "smoke") as directory:
            source = Path(directory) / "input.svg"
            source.write_text('<svg xmlns="http://www.w3.org/2000/svg" width="24" height="12"><rect width="24" height="12" fill="#DBF0A3"/></svg>', encoding="utf-8")
            image = load_image(source)
            self.assertEqual(image.size, (2048, 1024))
            self.assertEqual(image.getpixel((0, 0)), (219, 240, 163, 255))


if __name__ == "__main__":
    unittest.main()
