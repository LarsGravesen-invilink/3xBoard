"""Create non-destructive xBoard icon concepts for visual selection."""
from pathlib import Path
from datetime import datetime
import math
import shutil
import sys

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT.parent / "Варианты иконки xBoard"
SIZE = 1536
S = SIZE / 1024
FONTS = ["/tmp/opencode/Thrennic-Bold.ttf", "/tmp/opencode/Dutech-Regular.ttf"]


def px(v):
    return round(v * S)


def coords(values):
    return [(px(x), px(y)) for x, y in values]


def background():
    return Image.new("RGBA", (SIZE, SIZE), "#263b2b")


def bar_mask(a, b, width, rounded=False, bevel=0):
    mask = Image.new("L", (SIZE, SIZE))
    draw = ImageDraw.Draw(mask)
    ax, ay = a; bx, by = b
    dx, dy = bx - ax, by - ay
    length = math.hypot(dx, dy)
    nx, ny = -dy / length * width / 2, dx / length * width / 2
    ux, uy = dx / length * bevel, dy / length * bevel
    polygon = [(ax + nx + ux, ay + ny + uy), (bx + nx - ux, by + ny - uy),
               (bx - nx - ux, by - ny - uy), (ax - nx + ux, ay - ny + uy)]
    draw.polygon(coords(polygon), fill=255)
    if rounded:
        r = width / 2
        for x, y in (a, b):
            draw.ellipse((px(x-r), px(y-r), px(x+r), px(y+r)), fill=255)
    return mask


def apply_metal(image, mask):
    y, x = np.mgrid[0:SIZE, 0:SIZE].astype(np.float32) / S
    value = np.clip((y - 180) / 510, 0, 1)
    arr = np.empty((SIZE, SIZE, 3), dtype=np.uint8)
    for i, (top, bottom) in enumerate(zip((97, 119, 60), (42, 62, 26))):
        arr[:, :, i] = top + (bottom - top) * value
    layer = Image.fromarray(arr, "RGB").convert("RGBA")
    layer.putalpha(mask)
    image.alpha_composite(layer)


def draw_logo(image, index):
    styles = [
        (150, False, 0), (162, False, 2), (150, False, 7),
        (175, False, 5), (142, False, 0), (155, False, 8),
        (170, False, 0), (150, False, 4), (160, False, 0),
        (170, False, 7),
    ]
    width, rounded, bevel = styles[index]
    a = ((350, 260), (676, 565))
    b = ((676, 260), (350, 565))
    mask = ImageChops.lighter(bar_mask(*a, width, rounded, bevel), bar_mask(*b, width, rounded, bevel))
    apply_metal(image, mask)

    # An inset angular 3 follows the upper-left arm, clipped to the X silhouette.
    three = Image.new("L", (SIZE, SIZE))
    draw = ImageDraw.Draw(three)
    draw.polygon(coords([
        (366, 249), (414, 249), (424, 259), (424, 278), (414, 288),
        (424, 298), (424, 317), (414, 327), (366, 327), (366, 313),
        (407, 313), (410, 310), (410, 300), (407, 297), (378, 297),
        (378, 283), (407, 283), (410, 280), (410, 266), (407, 263),
        (366, 263),
    ]), fill=255)
    three = three.rotate(-43, resample=Image.Resampling.BICUBIC, center=(px(395), px(288)))
    three = ImageChops.multiply(three, mask).point(lambda alpha: alpha * 38 // 255)
    inset = Image.new("RGBA", (SIZE, SIZE), (170, 188, 128, 0))
    inset.putalpha(three)
    image.alpha_composite(inset)

def draw_word(image, index):
    font = ImageFont.truetype(FONTS[0 if index < 5 else 1], px([178, 170, 174, 174, 178, 170, 174, 178, 170, 174][index]))
    text = "Board"
    draw = ImageDraw.Draw(image)
    box = draw.textbbox((0, 0), text, font=font, stroke_width=0)
    x = (SIZE - (box[2] - box[0])) // 2 - box[0]
    y = px(752) - (box[1] + box[3]) // 2
    draw.text((x, y + px(3)), text, font=font, fill=(0, 0, 0, 130), stroke_width=px(1))
    draw.text((x, y), text, font=font, fill=(242, 244, 239, 255))


def sheet(images):
    tile = 280
    result = Image.new("RGB", (tile * 5, 360 * 2), "#e7e9e5")
    draw = ImageDraw.Draw(result)
    font = ImageFont.truetype("/usr/share/fonts/truetype/lato/Lato-Bold.ttf", 19)
    for idx, original in enumerate(images):
        col, row = idx % 5, idx // 5
        x, y = col * tile, row * 360
        draw.rectangle((x, y, x+tile-1, y+359), fill="#e9ebe7" if row == 0 else "#171a19")
        icon = original.resize((230, 230), Image.Resampling.LANCZOS)
        mask = Image.new("L", icon.size)
        ImageDraw.Draw(mask).ellipse((0, 0, 229, 229), fill=255)
        result.paste(icon.convert("RGB"), (x+25, y+40), mask)
        color = "#202520" if row == 0 else "#f1f3ef"
        draw.text((x+tile//2, y+289), f"{idx+1:02d}", font=font, fill=color, anchor="mm")
    result.save(OUTPUT / "00-сравнение.png", optimize=True)


def size_sheet(images):
    scale = 2
    width, height = 1120, 10 * 260 + 60
    result = Image.new("RGB", (width, height), "#e9ebe7")
    draw = ImageDraw.Draw(result)
    font = ImageFont.truetype("/usr/share/fonts/truetype/lato/Lato-Bold.ttf", 20)
    draw.text((25, 16), "Вариант", font=font, fill="#202520")
    for col, label in enumerate(("34 dp", "42 dp", "48 dp", "120 dp")):
        draw.text((160 + col * 205, 16), label, font=font, fill="#202520")
    for idx, image in enumerate(images):
        y = 60 + idx * 260
        dark = idx % 2 == 1
        draw.rectangle((0, y, width, y + 259), fill="#171a19" if dark else "#e9ebe7")
        draw.text((25, y + 48), f"{idx+1:02d}", font=font, fill="#f1f3ef" if dark else "#202520")
        for col, size in enumerate((34, 42, 48, 120)):
            edge = size * scale
            icon = image.resize((edge, edge), Image.Resampling.LANCZOS)
            mask = Image.new("L", (edge, edge))
            md = ImageDraw.Draw(mask)
            if col == 2:
                md.ellipse((0, 0, edge - 1, edge - 1), fill=255)
            else:
                md.rounded_rectangle((0, 0, edge - 1, edge - 1), radius=round(edge * 0.22), fill=255)
            result.paste(icon, (160 + col * 205, y + (260 - icon.height) // 2), mask)
    result.save(OUTPUT / "00-размеры-и-формы.png", optimize=True)


def install_variant_01():
    source = OUTPUT / "xBoard-01.png"
    with Image.open(source) as selected:
        selected = selected.convert("RGBA")
    if selected.size != (1024, 1024) or selected.getpixel((0, 0))[:3] != (38, 59, 43):
        raise ValueError("Unexpected variant 01 dimensions or background")

    res = ROOT / "app/src/main/res"
    originals = [ROOT / "app/src/main/AndroidManifest.xml", res / "drawable/ic_xboard.xml",
                 res / "drawable-nodpi/xboard_brand.png"]
    originals += sorted(res.glob("mipmap-*/ic_launcher*"))
    if len(originals) != 25 or any(not path.is_file() for path in originals):
        raise RuntimeError("Unexpected original icon resource set; nothing was changed")
    backup = ROOT.parent / ("Резервная копия иконки xBoard " + datetime.now().strftime("%Y-%m-%d_%H-%M-%S"))
    backup.mkdir(exist_ok=False)
    for original in originals:
        destination = backup / original.relative_to(ROOT)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(original, destination)
    print(f"Original resources backed up to {backup}", flush=True)

    def rounded(image, circular=False):
        mask = Image.new("L", image.size)
        painter = ImageDraw.Draw(mask)
        bounds = (0, 0, image.width - 1, image.height - 1)
        if circular:
            painter.ellipse(bounds, fill=255)
        else:
            painter.rounded_rectangle(bounds, radius=round(image.width * 0.22), fill=255)
        result = image.copy()
        result.putalpha(mask)
        return result

    # Keep the selected composition intact in-app; use a smaller composition
    # on the adaptive layer so the artwork remains inside Android's safe zone.
    rounded(selected).save(res / "drawable-nodpi/xboard_icon.png", optimize=True)
    selected.save(res / "drawable-nodpi/xboard_brand.png", optimize=True)
    safe_size = round(selected.width * 0.75)
    safe = selected.resize((safe_size, safe_size), Image.Resampling.LANCZOS)
    offset = (selected.width - safe_size) // 2
    foreground = Image.new("RGBA", selected.size, (0, 0, 0, 0))
    foreground.alpha_composite(safe, (offset, offset))
    legacy = Image.new("RGBA", selected.size, "#263b2b")
    legacy.alpha_composite(foreground)

    for directory in sorted(res.glob("mipmap-*dpi")):
        for name in ("ic_launcher_background", "ic_launcher_foreground", "ic_launcher", "ic_launcher_round"):
            path = directory / (name + ".png")
            with Image.open(path) as old:
                size = old.size
            if name == "ic_launcher_background":
                image = Image.new("RGBA", size, "#263b2b")
            elif name == "ic_launcher_foreground":
                image = foreground.resize(size, Image.Resampling.LANCZOS)
            else:
                image = rounded(legacy, circular=name.endswith("_round")).resize(size, Image.Resampling.LANCZOS)
            image.save(path, optimize=True)
    print("Prepared variant 01 for in-app and launcher resources.")


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    images = []
    for index in range(10):
        image = background()
        draw_logo(image, index)
        draw_word(image, index)
        image = image.convert("RGB").resize((1024, 1024), Image.Resampling.LANCZOS)
        image.save(OUTPUT / f"xBoard-{index+1:02d}.png", optimize=True)
        images.append(image)
    sheet(images)
    size_sheet(images)
    print(f"Created {len(images)} concepts and a comparison sheet in {OUTPUT}")


if __name__ == "__main__":
    if sys.argv[1:] == ["--install-01"]:
        install_variant_01()
    elif not sys.argv[1:]:
        main()
    else:
        raise SystemExit("Usage: generate_xboard_variants.py [--install-01]")
