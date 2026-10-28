"""生成 apps/hackernews/icon.png 示例图标（一次性工具，结果已提交到仓库）。

512x512 橙底白色 "Y"，四角不透明，用来演示 IconRenderer 的 FULL_BLEED 模式。
用法：python .dev/tools/make_demo_icon.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "apps" / "hackernews" / "icon.png"
SIZE = 512

img = Image.new("RGBA", (SIZE, SIZE), (255, 102, 0, 255))
draw = ImageDraw.Draw(img)
# 方框描边，模仿 HN 左上角 logo
draw.rectangle([96, 96, SIZE - 97, SIZE - 97], outline=(255, 255, 255, 255), width=18)
try:
    font = ImageFont.truetype("arialbd.ttf", 260)
except OSError:
    font = ImageFont.load_default(size=260)
bbox = draw.textbbox((0, 0), "Y", font=font)
w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
draw.text(((SIZE - w) / 2 - bbox[0], (SIZE - h) / 2 - bbox[1]), "Y", font=font, fill=(255, 255, 255, 255))

OUT.parent.mkdir(parents=True, exist_ok=True)
img.save(OUT)
print(f"wrote {OUT}")
