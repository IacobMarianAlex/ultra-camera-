"""Simulates a hand-held phone burst (shake, noise, -1EV, moving object, clipped window)
and writes frames in UltraCam's .yuv frame format, plus the ground truth.
usage: simulate_burst.py OUTDIR N BRIGHTNESS(1=day, 0.06=night) [W H]"""
import sys, os, struct
import numpy as np
from scipy import ndimage
from skimage import data, transform
from PIL import Image

out, N, bright = sys.argv[1], int(sys.argv[2]), float(sys.argv[3])
W, H = (int(sys.argv[4]), int(sys.argv[5])) if len(sys.argv) > 5 else (2048, 1536)
os.makedirs(out, exist_ok=True)
rng = np.random.default_rng(7)

def lin(x): return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)
def enc(x): x = np.clip(x, 0, 1); return np.where(x <= 0.0031308, x * 12.92, 1.055 * x ** (1 / 2.4) - 0.055)

# --- scene: mosaic of real photos + fine detail, with a bright "window" (HDR)
tiles = [data.astronaut(), data.coffee(), data.chelsea(), data.rocket()]
scene = np.zeros((H, W, 3))
cw, ch = W // 2, H // 2
for k, t in enumerate(tiles):
    t = transform.resize(t, (ch, cw), anti_aliasing=True, order=3)
    y, x = (k // 2) * ch, (k % 2) * cw
    scene[y:y + ch, x:x + cw] = t
txt = transform.resize(data.text() / 255.0, (H // 5, W // 3), order=1)
scene[H - H // 5 - 20:H - 20, 40:40 + W // 3] = txt[..., None] * np.array([0.9, 0.85, 0.7])
scene = lin(scene)
scene[60:60 + H // 4, W - W // 4 - 60:W - 60] *= 7.0   # very bright region -> clips in a single shot

def frame(i):
    ang = rng.normal(0, 0.15) if i else 0.0
    dx, dy = (rng.normal(0, 6), rng.normal(0, 6)) if i else (0.0, 0.0)
    c, s = np.cos(np.radians(ang)), np.sin(np.radians(ang))
    m = np.array([[c, -s], [s, c]])
    ctr = np.array([H / 2, W / 2])
    off = ctr - m @ ctr + np.array([dy, dx])
    img = np.stack([ndimage.affine_transform(scene[..., k], m, offset=off, order=1, mode='nearest') for k in range(3)], -1)
    # moving object (a red ball crossing the frame)
    yy, xx = np.mgrid[0:H, 0:W]
    bx, by = W * 0.2 + i * W * 0.035, H * 0.55
    img[(xx - bx) ** 2 + (yy - by) ** 2 < (H * 0.06) ** 2] = [0.6, 0.05, 0.04]
    img = ndimage.gaussian_filter(img, sigma=(0.9, 0.9, 0))  # cheap lens
    img *= 0.5 * bright  # -1 EV capture
    # sensor noise (shot + read), stronger at night (high ISO)
    gainn = 1.0 / max(bright, 0.05)
    var = 0.00035 * gainn * np.clip(img, 0, None) + 0.000012 * gainn ** 1.5
    img = img + rng.normal(0, 1, img.shape) * np.sqrt(var)
    img = img / (0.5 * bright) * 0.5   # ISP auto-gain back to -1EV brightness
    e = enc(img) * 255
    r, g, b = e[..., 0], e[..., 1], e[..., 2]
    Y = 0.299 * r + 0.587 * g + 0.114 * b
    U = -0.168736 * r - 0.331264 * g + 0.5 * b + 128
    V = 0.5 * r - 0.418688 * g - 0.081312 * b + 128
    U = U.reshape(H // 2, 2, W // 2, 2).mean((1, 3)); V = V.reshape(H // 2, 2, W // 2, 2).mean((1, 3))
    q = lambda a: np.clip(np.round(a), 0, 255).astype(np.uint8).tobytes()
    with open(os.path.join(out, f"frame_{i}.yuv"), "wb") as f:
        f.write(struct.pack(">iii", 0x55435946, W, H)); f.write(q(Y)); f.write(q(U)); f.write(q(V))
    if i == 0:
        Image.fromarray(np.clip(e, 0, 255).astype(np.uint8)).save(os.path.join(out, "single_shot.png"))

for i in range(N):
    frame(i)
Image.fromarray((enc(scene * 0.6) * 255).astype(np.uint8)).save(os.path.join(out, "ground_truth.png"))
print("ok", W, H, N)
