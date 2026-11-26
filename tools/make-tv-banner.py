"""生成 Android TV 桌面用的 banner（320x180）和 App 图标用的前景图不在此脚本范围。

TV banner 规格：
  - 官方要求 320x180 px（16:9），xhdpi 下正好是 160x90dp；
  - 各家电视桌面会把它放大到 1/3 屏宽左右，所以文字要粗、对比要高；
  - 深色底是因为 Android TV 桌面本身是深色，浅底会显得像贴上去的一块膏药。

画法：先在 4 倍尺寸（1280x720）上画，再 LANCZOS 缩到 320x180 —— Pillow 没有
亚像素抗锯齿，直接在小尺寸上画字边缘全是锯齿。
"""

from PIL import Image, ImageChops, ImageDraw, ImageFont

SS = 4                      # 超采样倍数
W, H = 320 * SS, 180 * SS

BG_TOP = (11, 14, 19)       # #0B0E13 与 App 主背景一致
BG_BOTTOM = (20, 26, 36)
ACCENT = (255, 180, 84)     # #FFB454 暖色，App 的强调色
TEXT = (245, 246, 248)
SUBTLE = (128, 138, 152)


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def main():
    img = Image.new("RGB", (W, H), BG_TOP)
    d = ImageDraw.Draw(img)

    # ① 垂直渐变底
    for y in range(H):
        d.line([(0, y), (W, y)], fill=lerp(BG_TOP, BG_BOTTOM, y / (H - 1)))

    # ② 右上角暖色光晕：用几层同心椭圆模拟径向渐变，避免整体发灰
    glow = Image.new("RGB", (W, H), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    cx, cy = W - 40 * SS, -10 * SS
    for i in range(28, 0, -1):
        t = i / 28.0
        r = int(230 * SS * t)
        gd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=lerp((0, 0, 0), (60, 40, 16), 1 - t))
    img = ImageChops.add(img, glow)

    d = ImageDraw.Draw(img)

    # ③ 左侧播放标记：暖色圆角方 + 深色三角
    box = 92 * SS
    bx, by = 40 * SS, (H - box) // 2
    d.rounded_rectangle([bx, by, bx + box, by + box], radius=24 * SS, fill=ACCENT)
    cx0, cy0 = bx + box / 2, by + box / 2
    tri = box * 0.30
    d.polygon(
        [(cx0 - tri * 0.62, cy0 - tri), (cx0 - tri * 0.62, cy0 + tri), (cx0 + tri * 0.95, cy0)],
        fill=BG_TOP,
    )

    # ④ 文字：主标题 + 一行小字
    #
    # ⚠️ 字号必须**按可用宽度算**，不能写死。第一版写 52dp，"MyEmbyHub" 直接
    #    冲出右边界被裁掉半个字母 —— 电视桌面上就是「MyEmb」。
    tx = bx + box + 24 * SS
    avail = W - tx - 26 * SS
    title = "MyEmbyHub"
    title_font, tb = fit_font(d, title, avail, start=52 * SS, bold=True)
    sub_font = load_font(max(int(9 * SS), int(tb[3] - tb[1]) * 0.20), bold=True)

    th = tb[3] - tb[1]
    title_y = (H - th) / 2 - 12 * SS - tb[1]
    d.text((tx, title_y), title, font=title_font, fill=TEXT)

    sub = "E M B Y   T V"
    sub_y = title_y + th + tb[1] + 10 * SS
    d.text((tx, sub_y), sub, font=sub_font, fill=ACCENT)

    # ⑤ 底部一道暖色细线，缩到 320 宽以后正好是 1~2px 的分隔感
    d.rectangle([0, H - 3 * SS, W, H], fill=ACCENT)

    out = img.resize((320, 180), Image.LANCZOS)
    out.save("app/src/main/res/drawable-xhdpi/tv_banner.png", "PNG")
    print("wrote app/src/main/res/drawable-xhdpi/tv_banner.png", out.size)


def fit_font(d, text, max_width, start, bold):
    """从 start 起二分收缩字号，直到 text 的宽度塞进 max_width。

    返回 (font, textbbox)。
    """
    lo, hi = 8, int(start)
    best_font = load_font(lo, bold=bold)
    best_bb = d.textbbox((0, 0), text, font=best_font)
    while lo <= hi:
        mid = (lo + hi) // 2
        f = load_font(mid, bold=bold)
        bb = d.textbbox((0, 0), text, font=f)
        if bb[2] - bb[0] <= max_width:
            best_font, best_bb = f, bb
            lo = mid + 1
        else:
            hi = mid - 1
    return best_font, best_bb


def load_font(size, bold=False):
    """挑一个本机一定有的无衬线粗体。

    不用 PIL 的 load_default()：那是个位图字体，放大 4 倍再缩回去就糊成一团。
    """
    candidates = [
        r"C:\Windows\Fonts\segoeuib.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf",
        r"C:\Windows\Fonts\arialbd.ttf" if bold else r"C:\Windows\Fonts\arial.ttf",
        r"C:\Windows\Fonts\msyhbd.ttc" if bold else r"C:\Windows\Fonts\msyh.ttc",
    ]
    for path in candidates:
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    raise SystemExit("找不到可用的 TrueType 字体，检查 C:\\Windows\\Fonts")


if __name__ == "__main__":
    main()
