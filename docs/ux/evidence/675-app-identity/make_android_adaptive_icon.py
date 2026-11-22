"""Build the Android adaptive and themed icon layers for #675.

Usage: python make_android_adaptive_icon.py <source raster> <mobile/assets/images>
Needs Pillow, NumPy and SciPy. Reads icon.png from the output folder.

The icon artwork is a single raster (690 px master, exported as the 1024 px icon.png),
so the layers are derived from it:
- foreground: the launcher artwork scaled to 68 % (arrow tip inside the 66 dp safe circle)
  over a blurred copy of itself; the art edge lies just outside the 72 dp viewport.
- monochrome: the glyph (ring, T, W and arrow) thresholded from the source raster.
Replace both with designed layers once design delivers them.
"""
import sys

import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage

raster_path, out = sys.argv[1:3]
C = 1024
ART = round(C * 0.68)
OFF = (C - ART) // 2

icon = Image.open(f'{out}/icon.png').convert('RGB')
fg = icon.filter(ImageFilter.GaussianBlur(48))
fg.paste(icon.resize((ART, ART), Image.LANCZOS), (OFF, OFF))
fg.convert('RGBA').save(f'{out}/android-icon-foreground.png', optimize=True)

rgb = np.asarray(Image.open(raster_path).convert('RGB')).astype(float) / 255
h, w, _ = rgb.shape
yy, xx = np.mgrid[0:h, 0:w]
glyph = (rgb.max(2) > 0.6) & (np.hypot(xx - w / 2, yy - h / 2) < w * 0.48)
# Filter sizes were tuned on the 512 px raster; scale them with the input resolution.
scale = w / 512
glyph = ndimage.binary_opening(glyph, iterations=max(1, round(2 * scale)))
labels, _ = ndimage.label(glyph)
keep = []
for i, sl in enumerate(ndimage.find_objects(labels), start=1):
    area = (labels[sl] == i).sum()
    # Drop specks and the background glow above the ring (it ends above 22 % height).
    if area > 300 * scale**2 and sl[0].stop > h * 0.22:
        keep.append(i)
mask = Image.fromarray((np.isin(labels, keep) * 255).astype('uint8'))
mask = mask.resize((ART, ART), Image.LANCZOS).filter(ImageFilter.GaussianBlur(0.8))
layer = Image.new('RGBA', (ART, ART), (255, 255, 255, 255))
layer.putalpha(mask)
mono = Image.new('RGBA', (C, C), (255, 255, 255, 0))
mono.paste(layer, (OFF, OFF), layer)
mono.save(f'{out}/android-icon-monochrome.png', optimize=True)
