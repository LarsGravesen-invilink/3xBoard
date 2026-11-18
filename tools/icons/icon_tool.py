import argparse
import io
import json
import math
import warnings
from pathlib import Path

import numpy as np
from PIL import Image, ImageCms, ImageColor, ImageDraw, ImageEnhance, ImageOps

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
Image.MAX_IMAGE_PIXELS = 40_000_000
warnings.simplefilter("error", Image.DecompressionBombWarning)


def load_image(path):
    path = Path(path)
    if path.suffix.lower() == ".svg":
        if path.stat().st_size > 4_000_000:
            raise ValueError("SVG превышает допустимый размер")
        from cairosvg.parser import Tree
        from cairosvg.surface import PNGSurface
        from defusedxml import ElementTree

        data = path.read_bytes()
        ElementTree.fromstring(data)

        def deny_external(url, resource_type):
            raise ValueError("Внешние ресурсы SVG запрещены")

        output = io.BytesIO()
        tree = Tree(bytestring=data, url_fetcher=deny_external, unsafe=False)
        PNGSurface(tree, output, 96, output_width=2048).finish()
        source = Image.open(io.BytesIO(output.getvalue()))
    else:
        source = Image.open(path)
    with source:
        source.load()
        source = ImageOps.exif_transpose(source)
        profile = source.info.get("icc_profile")
        if profile:
            alpha = source.getchannel("A") if "A" in source.getbands() else None
            source = ImageCms.profileToProfile(
                source.convert("RGB"),
                ImageCms.ImageCmsProfile(io.BytesIO(profile)),
                ImageCms.createProfile("sRGB"),
                outputMode="RGB",
            )
            if alpha is not None:
                source.putalpha(alpha)
        return source.convert("RGBA")


def adjust(image, brightness=1.0, contrast=1.0, saturation=1.0, hue=0.0, recolor=None):
    alpha = image.getchannel("A")
    rgb = image.convert("RGB")
    if hue:
        hsv = np.asarray(rgb.convert("HSV")).copy()
        hsv[..., 0] = ((hsv[..., 0].astype(np.int32) + round(hue * 256 / 360)) % 256).astype(np.uint8)
        rgb = Image.frombytes("HSV", rgb.size, hsv.tobytes()).convert("RGB")
    rgb = ImageEnhance.Brightness(rgb).enhance(brightness)
    rgb = ImageEnhance.Contrast(rgb).enhance(contrast)
    rgb = ImageEnhance.Color(rgb).enhance(saturation)
    if recolor:
        rgb = Image.new("RGB", rgb.size, ImageColor.getrgb(recolor))
    rgb.putalpha(alpha)
    return rgb


def cover(image, size):
    return ImageOps.fit(image, (size, size), method=Image.Resampling.LANCZOS)


def solid(image, color):
    result = Image.new("RGBA", image.size, color)
    result.alpha_composite(image)
    return result


def mask(size, shape):
    scale = 3
    high = size * scale
    result = Image.new("L", (high, high), 0)
    draw = ImageDraw.Draw(result)
    box = (0, 0, high - 1, high - 1)
    if shape == "circle":
        draw.ellipse(box, fill=255)
    elif shape == "rounded":
        draw.rounded_rectangle(box, radius=high * 0.23, fill=255)
    elif shape == "squircle":
        center = (high - 1) / 2
        points = []
        for index in range(1024):
            angle = 2 * math.pi * index / 1024
            x = math.cos(angle)
            y = math.sin(angle)
            points.append((center + center * math.copysign(abs(x) ** 0.5, x), center + center * math.copysign(abs(y) ** 0.5, y)))
        draw.polygon(points, fill=255)
    else:
        draw.rectangle(box, fill=255)
    return result.resize((size, size), Image.Resampling.LANCZOS)


def save_png(image, path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True)


def adaptive_xml(monochrome=False):
    extra = '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n' if monochrome else ""
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        f'{extra}</adaptive-icon>\n'
    )


def generate(image, output, background="#111411", foreground=None, monochrome=None, flat_background=False):
    output = Path(output)
    if output.exists() and any(output.iterdir()):
        raise ValueError("Каталог результата должен быть пустым: исходники и существующие ресурсы не перезаписываются")
    output.mkdir(parents=True, exist_ok=True)
    base = solid(image, background)
    if monochrome is not None and monochrome.getchannel("A").getextrema()[0] == 255:
        raise ValueError("Монохромный слой должен иметь прозрачный фон и отдельный силуэт")
    for density, ratio in DENSITIES.items():
        destination = output / "res" / f"mipmap-{density}"
        adaptive_size = round(108 * ratio)
        backdrop = Image.new("RGBA", (adaptive_size, adaptive_size), background) if flat_background else cover(base, adaptive_size)
        front = Image.new("RGBA", backdrop.size)
        if foreground is not None:
            subject = ImageOps.contain(foreground, (round(60 * ratio), round(60 * ratio)), Image.Resampling.LANCZOS)
            front.alpha_composite(subject, ((adaptive_size - subject.width) // 2, (adaptive_size - subject.height) // 2))
        save_png(backdrop, destination / "ic_launcher_background.png")
        save_png(front, destination / "ic_launcher_foreground.png")
        if monochrome is not None:
            mono = Image.new("RGBA", backdrop.size)
            subject = ImageOps.contain(monochrome, (round(60 * ratio), round(60 * ratio)), Image.Resampling.LANCZOS)
            mono.alpha_composite(subject, ((adaptive_size - subject.width) // 2, (adaptive_size - subject.height) // 2))
            save_png(mono, destination / "ic_launcher_monochrome.png")
        composed = Image.alpha_composite(backdrop, front)
        visible = round(72 * ratio)
        offset = (adaptive_size - visible) // 2
        legacy = composed.crop((offset, offset, offset + visible, offset + visible)).resize((round(48 * ratio), round(48 * ratio)), Image.Resampling.LANCZOS)
        save_png(legacy, destination / "ic_launcher.png")
        rounded = legacy.copy()
        rounded.putalpha(mask(legacy.width, "circle"))
        save_png(rounded, destination / "ic_launcher_round.png")
    for version in ([26, 33] if monochrome is not None else [26]):
        destination = output / "res" / f"mipmap-anydpi-v{version}"
        destination.mkdir(parents=True, exist_ok=True)
        xml = adaptive_xml(monochrome=version >= 33)
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            (destination / name).write_text(xml, encoding="utf-8")
    save_png(image, output / "artwork" / "color-corrected.png")
    save_png(cover(base, 512).convert("RGB"), output / "artwork" / "store-512.png")
    artwork = cover(base, 768)
    if foreground is not None:
        subject = ImageOps.contain(foreground, (469, 469), Image.Resampling.LANCZOS)
        artwork.alpha_composite(subject, ((768 - subject.width) // 2, (768 - subject.height) // 2))
    artwork = artwork.crop((128, 128, 640, 640))
    previews = Image.new("RGB", (512 * 3, 512 * 2), "#E7E7E2")
    for row, color in enumerate(("#E7E7E2", "#151915")):
        for column, shape in enumerate(("circle", "rounded", "squircle")):
            tile = Image.new("RGBA", (512, 512), color)
            icon = artwork.resize((384, 384), Image.Resampling.LANCZOS)
            icon.putalpha(mask(384, shape))
            tile.alpha_composite(icon, (64, 64))
            previews.paste(tile.convert("RGB"), (column * 512, row * 512))
    save_png(previews, output / "artwork" / "launcher-previews.png")
    (output / "metadata.json").write_text(json.dumps({
        "source_size": list(image.size),
        "densities": DENSITIES,
        "legacy_dp": 48,
        "adaptive_dp": 108,
        "foreground_safe_dp": 66,
        "has_separate_foreground": foreground is not None,
        "has_monochrome": monochrome is not None,
        "background": background,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="Подготовка полноформатных Android-иконок и цветокоррекция")
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--background", default="#111411")
    parser.add_argument("--foreground", type=Path)
    parser.add_argument("--monochrome", type=Path)
    parser.add_argument("--crop", type=int, nargs=4, metavar=("LEFT", "TOP", "RIGHT", "BOTTOM"))
    parser.add_argument("--trim-alpha", action="store_true")
    parser.add_argument("--brightness", type=float, default=1.0)
    parser.add_argument("--contrast", type=float, default=1.0)
    parser.add_argument("--saturation", type=float, default=1.0)
    parser.add_argument("--hue", type=float, default=0.0)
    parser.add_argument("--recolor")
    parser.add_argument("--flat-background", action="store_true")
    arguments = parser.parse_args()
    try:
        for value in (arguments.brightness, arguments.contrast, arguments.saturation):
            if not math.isfinite(value) or not 0 <= value <= 4:
                raise ValueError("Параметры коррекции должны находиться в пределах от 0 до 4")
        if not math.isfinite(arguments.hue):
            raise ValueError("Некорректный сдвиг оттенка")
        image = load_image(arguments.source)
        if arguments.crop:
            left, top, right, bottom = arguments.crop
            if not (0 <= left < right <= image.width and 0 <= top < bottom <= image.height):
                raise ValueError("Область кадрирования выходит за границы исходника")
            image = image.crop(tuple(arguments.crop))
        if arguments.trim_alpha:
            bounds = image.getchannel("A").getbbox()
            if bounds is None:
                raise ValueError("Изображение полностью прозрачное")
            image = image.crop(bounds)
        image = adjust(image, arguments.brightness, arguments.contrast, arguments.saturation, arguments.hue, arguments.recolor)
        foreground = load_image(arguments.foreground) if arguments.foreground else None
        monochrome = load_image(arguments.monochrome) if arguments.monochrome else None
        generate(image, arguments.output, arguments.background, foreground, monochrome, arguments.flat_background)
    except Exception as error:
        parser.exit(1, f"Ошибка: {error}\n")
    print(f"Ресурсы и предпросмотр сохранены: {arguments.output.resolve()}")


if __name__ == "__main__":
    main()
