#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
preview_mesh.py —— 用软件光栅化把 .mesh.json 渲染成 PNG，用于离屏验证模型数据。

不做光照、不做骨骼（可选静态姿势），只做：
  - 顶点投影（正交，按 Y 轴向上）
  - 背面剔除
  - Z-buffer
  - 按材质分组、最近邻采样贴图

用途：在没有进游戏的情况下确认「顶点位置、三角形连接、UV、贴图」是否都正确。
"""
import json
import io
import os
import sys

import numpy as np
from PIL import Image


def load_textures(mesh_dir, model, tex_root):
    """按 model['textures'] 顺序加载贴图，返回 PIL Image 列表（缺失用洋红）。"""
    imgs = []
    for name in model["textures"]:
        base = os.path.splitext(name)[0]
        path = os.path.join(tex_root, base + ".png")
        if os.path.isfile(path):
            imgs.append(Image.open(path).convert("RGBA"))
        else:
            print("  [缺贴图]", base + ".png")
            imgs.append(None)
    return imgs


def render(model, tex_root, out_path, size=(900, 1200), yaw_deg=0.0):
    verts = np.array([v["p"] for v in model["vertices"]], dtype=np.float32)
    uvs = np.array([v["uv"] for v in model["vertices"]], dtype=np.float32)
    idx = np.array(model["indices"], dtype=np.int32).reshape(-1, 3)

    # 归一化到 [-1, 1]，Y 向上
    lo = verts.min(axis=0)
    hi = verts.max(axis=0)
    center = (lo + hi) * 0.5
    extent = max(hi[0] - lo[0], hi[1] - lo[1]) * 0.55
    p = (verts - center) / extent

    # 绕 Y 旋转
    a = np.radians(yaw_deg)
    ca, sa = np.cos(a), np.sin(a)
    x = p[:, 0] * ca + p[:, 2] * sa
    z = -p[:, 0] * sa + p[:, 2] * ca
    p = np.stack([x, p[:, 1], z], axis=1)

    W, H = size
    # 正交投影到屏幕（Y 翻转，屏幕 Y 向下）
    sx = (p[:, 0] * 0.5 + 0.5) * W
    sy = (0.5 - p[:, 1] * 0.5) * H
    sz = p[:, 2]

    color = np.zeros((H, W, 3), dtype=np.uint8)
    color[:, :] = (18, 20, 26)
    zbuf = np.full((H, W), -np.inf, dtype=np.float32)

    texs = load_textures(None, model, tex_root)
    tex_cache = {}

    def sample_tex(ti, u, v):
        """最近邻采样，返回 (r,g,b)。"""
        if ti < 0 or ti >= len(texs) or texs[ti] is None:
            return (255, 0, 255)
        img = texs[ti]
        key = (ti, img.size)
        if key not in tex_cache:
            arr = np.asarray(img, dtype=np.uint8)
            tex_cache[key] = arr
        arr = tex_cache[key]
        th, tw = arr.shape[:2]
        uu = int(np.clip(u, 0.0, 0.9999) * tw)
        vv = int(np.clip(v, 0.0, 0.9999) * th)
        px = arr[vv, uu]
        return (int(px[0]), int(px[1]), int(px[2]))

    face_offset = 0
    drawn = 0
    skipped = 0
    for mat in model["materials"]:
        count = mat["face_count"]
        if count <= 0:
            continue
        ti = mat["texture"]
        diff = mat["diffuse"]
        r0, g0, b0 = diff[0], diff[1], diff[2]
        f0 = face_offset // 3
        f1 = (face_offset + count) // 3

        for fi in range(f0, f1):
            i0, i1, i2 = idx[fi]
            x0, y0, z0 = sx[i0], sy[i0], sz[i0]
            x1, y1, z1 = sx[i1], sy[i1], sz[i1]
            x2, y2, z2 = sx[i2], sy[i2], sz[i2]

            # 不做背面剔除：MMD 的绕序与 OpenGL 默认相反，且 mod 侧用的是
            # entityCutoutNoCull（双面），这里保持一致，避免"看到模型内部"。
            area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if abs(area) < 1e-6:
                skipped += 1
                continue

            minx = max(0, int(min(x0, x1, x2)))
            maxx = min(W - 1, int(max(x0, x1, x2)) + 1)
            miny = max(0, int(min(y0, y1, y2)))
            maxy = min(H - 1, int(max(y0, y1, y2)) + 1)
            if minx > maxx or miny > maxy:
                continue

            denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            if abs(denom) < 1e-9:
                continue

            u0, v0 = uvs[i0]
            u1, v1 = uvs[i1]
            u2, v2 = uvs[i2]

            for py in range(miny, maxy + 1):
                fy = py + 0.5
                for px in range(minx, maxx + 1):
                    fx = px + 0.5
                    w0 = ((y1 - y2) * (fx - x2) + (x2 - x1) * (fy - y2)) / denom
                    w1 = ((y2 - y0) * (fx - x2) + (x0 - x2) * (fy - y2)) / denom
                    w2 = 1.0 - w0 - w1
                    if w0 < 0 or w1 < 0 or w2 < 0:
                        continue
                    z = w0 * z0 + w1 * z1 + w2 * z2
                    if z <= zbuf[py, px]:
                        continue
                    zbuf[py, px] = z
                    u = w0 * u0 + w1 * u1 + w2 * u2
                    v = w0 * v0 + w1 * v1 + w2 * v2
                    cr, cg, cb = sample_tex(ti, u, v)
                    color[py, px] = (
                        int(min(255, cr * r0)),
                        int(min(255, cg * g0)),
                        int(min(255, cb * b0)),
                    )
            drawn += 1

    print("  绘制三角形: %d, 背面剔除: %d, 三角形总数: %d" % (drawn, skipped, len(idx)))
    Image.fromarray(color).save(out_path)
    print("  输出:", out_path)


def main():
    if len(sys.argv) < 3:
        print("用法: preview_mesh.py <mesh.json> <贴图目录> [out.png] [yaw]")
        return 1
    mesh_path = sys.argv[1]
    tex_root = sys.argv[2]
    out = sys.argv[3] if len(sys.argv) > 3 else os.path.splitext(mesh_path)[0] + "_preview.png"
    yaw = float(sys.argv[4]) if len(sys.argv) > 4 else 0.0

    model = json.load(io.open(mesh_path, encoding="utf-8"))
    print("模型:", model["name"], model["stats"])
    print("贴图目录:", tex_root)
    render(model, tex_root, out, yaw_deg=yaw)
    return 0


if __name__ == "__main__":
    sys.exit(main())
