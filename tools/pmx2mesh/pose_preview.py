#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
pose_preview.py —— 复现 Java 侧 MeshSkeleton 的蒙皮算法，把带姿势的模型渲染出来。

用途：在进游戏之前验证
  - 骨骼层级矩阵的累乘顺序对不对
  - 顶点蒙皮公式对不对
  - 动画参数（旋转轴、幅度）合不合理

算法与 Java 一一对应：
  M_bone = T(pos + offset) × R(quat) × T(-pos)
  M_world = M_parent × M_bone
  v' = Σ weight_i × M_world_i × v
"""
import json
import io
import os
import sys
import math

import numpy as np
from PIL import Image


# ---------- 基础矩阵（4x4，行主序，与 JOML 的列主序数值等价，这里只求结果） ----------

def m_identity():
    return np.eye(4, dtype=np.float64)


def m_translate(x, y, z):
    m = np.eye(4)
    m[0, 3] = x
    m[1, 3] = y
    m[2, 3] = z
    return m


def m_quat(qx, qy, qz, qw):
    """四元数 → 旋转矩阵。"""
    n = math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw) or 1.0
    x, y, z, w = qx / n, qy / n, qz / n, qw / n
    xx, yy, zz = x * x, y * y, z * z
    xy, xz, yz = x * y, x * z, y * z
    wx, wy, wz = w * x, w * y, w * z
    m = np.eye(4)
    m[0, 0] = 1 - 2 * (yy + zz)
    m[0, 1] = 2 * (xy - wz)
    m[0, 2] = 2 * (xz + wy)
    m[1, 0] = 2 * (xy + wz)
    m[1, 1] = 1 - 2 * (xx + zz)
    m[1, 2] = 2 * (yz - wx)
    m[2, 0] = 2 * (xz - wy)
    m[2, 1] = 2 * (yz + wx)
    m[2, 2] = 1 - 2 * (xx + yy)
    return m


def m_euler(rx, ry, rz):
    """XYZ 欧拉角 → 旋转矩阵（与 JOML rotateXYZ 一致：先 X 后 Y 后 Z，右乘）。"""
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    Rx = np.array([[1, 0, 0, 0], [0, cx, -sx, 0], [0, sx, cx, 0], [0, 0, 0, 1]], dtype=np.float64)
    Ry = np.array([[cy, 0, sy, 0], [0, 1, 0, 0], [-sy, 0, cy, 0], [0, 0, 0, 1]], dtype=np.float64)
    Rz = np.array([[cz, -sz, 0, 0], [sz, cz, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]], dtype=np.float64)
    return Rz @ Ry @ Rx


# ---------- 骨骼 ----------

class Skeleton:
    def __init__(self, model):
        self.bones = model["bones"]
        self.n = len(self.bones)
        self.offset = np.zeros((self.n, 3), dtype=np.float64)
        self.rot = np.zeros((self.n, 3), dtype=np.float64)   # 欧拉角

    def find(self, *names):
        for nm in names:
            for i, b in enumerate(self.bones):
                if b["name"] == nm:
                    return i
        return -1

    def reset(self):
        self.offset[:] = 0
        self.rot[:] = 0

    def world_matrices(self):
        # 先算「付与」：把 grant_parent 的局部旋转/位移按权重混进来
        eff_rot = np.zeros_like(self.rot)
        eff_off = np.zeros_like(self.offset)
        for i, b in enumerate(self.bones):
            gp = b.get("grant_parent", -1)
            gw = b.get("grant_weight", 0.0) or 0.0
            # 权重可以是负数（抵消型，如「腰キャンセル」w=-1）
            if gp >= 0 and abs(gw) > 0.001:
                eff_rot[i] = self.rot[i] + self.rot[gp] * gw
            else:
                eff_rot[i] = self.rot[i]

            gpm = b.get("grant_parent_move", -1)
            gwm = b.get("grant_weight_move", 0.0) or 0.0
            if gpm >= 0 and gwm > 0:
                eff_off[i] = self.offset[i] + self.offset[gpm] * min(1.0, gwm)
            else:
                eff_off[i] = self.offset[i]

        words = [None] * self.n
        for i, b in enumerate(self.bones):
            base = np.array(b["p"], dtype=np.float64)
            pos = base + eff_off[i]
            local = m_translate(*pos) @ m_euler(*eff_rot[i]) @ m_translate(*(-base))
            parent = b["parent"]
            words[i] = local if parent < 0 else words[parent] @ local
        return words


def skin(model, mats):
    V = np.array([v["p"] for v in model["vertices"]], dtype=np.float64)
    N = np.array([v["n"] for v in model["vertices"]], dtype=np.float64)
    nv = len(V)
    out_p = np.zeros((nv, 3), dtype=np.float64)
    out_n = np.zeros((nv, 3), dtype=np.float64)

    for vi in range(nv):
        v = model["vertices"][vi]
        p4 = np.array([V[vi][0], V[vi][1], V[vi][2], 1.0])
        n4 = np.array([N[vi][0], N[vi][1], N[vi][2], 0.0])
        ap = np.zeros(4)
        an = np.zeros(4)
        total = 0.0
        for bi, w in v["b"]:
            if bi < 0 or bi >= len(mats) or w <= 0:
                continue
            ap += w * (mats[bi] @ p4)
            an += w * (mats[bi] @ n4)
            total += w
        if total <= 1e-6:
            out_p[vi] = V[vi]
            out_n[vi] = N[vi]
        else:
            out_p[vi] = ap[:3]
            ln = np.linalg.norm(an[:3])
            out_n[vi] = an[:3] / ln if ln > 1e-9 else N[vi]
    return out_p, out_n


# ---------- 渲染（沿用 preview_mesh 的思路，但顶点用蒙皮结果） ----------

def render(model, tex_root, out_path, pos, nrm, size=(900, 1200), yaw_deg=0.0):
    idx = np.array(model["indices"], dtype=np.int32).reshape(-1, 3)
    uvs = np.array([v["uv"] for v in model["vertices"]], dtype=np.float32)

    lo = pos.min(axis=0)
    hi = pos.max(axis=0)
    center = (lo + hi) * 0.5
    extent = max(hi[0] - lo[0], hi[1] - lo[1]) * 0.55
    p = (pos - center) / extent

    a = math.radians(yaw_deg)
    ca, sa = math.cos(a), math.sin(a)
    x = p[:, 0] * ca + p[:, 2] * sa
    z = -p[:, 0] * sa + p[:, 2] * ca
    p = np.stack([x, p[:, 1], z], axis=1)

    W, H = size
    sx = (p[:, 0] * 0.5 + 0.5) * W
    sy = (0.5 - p[:, 1] * 0.5) * H
    sz = p[:, 2]

    color = np.zeros((H, W, 3), dtype=np.uint8)
    color[:, :] = (18, 20, 26)
    zbuf = np.full((H, W), -np.inf, dtype=np.float32)

    texs = []
    for name in model["textures"]:
        path = os.path.join(tex_root, name)
        texs.append(np.asarray(Image.open(path).convert("RGB"), dtype=np.uint8) if os.path.isfile(path) else None)

    if not hasattr(render, "_warned"):
        missing = [n for n, t in zip(model["textures"], texs) if t is None]
        if missing:
            print("  [缺贴图]", missing)
        render._warned = True

    off = 0
    for mat in model["materials"]:
        cnt = mat["face_count"]
        if cnt <= 0:
            continue
        ti = mat["texture"]
        diff = mat["diffuse"]
        tex = texs[ti] if 0 <= ti < len(texs) else None
        th = tw = 0
        if tex is not None:
            th, tw = tex.shape[:2]

        for fi in range(off // 3, (off + cnt) // 3):
            i0, i1, i2 = idx[fi]
            x0, y0 = sx[i0], sy[i0]
            x1, y1 = sx[i1], sy[i1]
            x2, y2 = sx[i2], sy[i2]
            area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if abs(area) < 1e-6:
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
                    w2 = 1 - w0 - w1
                    if w0 < 0 or w1 < 0 or w2 < 0:
                        continue
                    zz = w0 * sz[i0] + w1 * sz[i1] + w2 * sz[i2]
                    if zz <= zbuf[py, px]:
                        continue
                    zbuf[py, px] = zz
                    if tex is None:
                        color[py, px] = (255, 0, 255)
                        continue
                    u = w0 * u0 + w1 * u1 + w2 * u2
                    v = w0 * v0 + w1 * v1 + w2 * v2
                    uu = int(np.clip(u, 0, 0.9999) * tw)
                    vv = int(np.clip(v, 0, 0.9999) * th)
                    px_rgb = tex[vv, uu]
                    color[py, px] = np.clip(px_rgb * np.array(diff[:3]), 0, 255).astype(np.uint8)
        off += cnt

    Image.fromarray(color).save(out_path)
    print("  输出:", out_path)


def main():
    if len(sys.argv) < 3:
        print("用法: pose_preview.py <mesh.json> <tex_dir> <out.png> [pose] [yaw]")
        return 1
    mesh = sys.argv[1]
    tex = sys.argv[2]
    out = sys.argv[3]
    pose = sys.argv[4] if len(sys.argv) > 4 else "rest"
    yaw = float(sys.argv[5]) if len(sys.argv) > 5 else 0.0

    model = json.load(io.open(mesh, encoding="utf-8"))
    sk = Skeleton(model)
    sk.reset()

    if pose == "walk":
        ph = math.pi * 0.5
        sw = 0.55
        for nm, sgn in (("右足D", 1), ("左足D", -1)):
            i = sk.find(nm)
            if i >= 0:
                sk.rot[i, 0] = math.sin(ph) * sw * sgn
        for nm, sgn in (("右腕", -1), ("左腕", 1)):
            i = sk.find(nm)
            if i >= 0:
                sk.rot[i, 0] = math.sin(ph) * sw * 0.7 * sgn
        c = sk.find("センター")
        if c >= 0:
            sk.offset[c, 1] = 0.3
    elif pose == "tpose":
        pass
    elif pose == "wave":
        i = sk.find("右腕")
        if i >= 0:
            sk.rot[i, 0] = -2.0
        h = sk.find("頭")
        if h >= 0:
            sk.rot[h, 1] = 0.3

    mats = sk.world_matrices()
    p, n = skin(model, mats)

    moved = np.abs(p - np.array([v["p"] for v in model["vertices"]])).max()
    print("  姿势: %s   顶点最大位移: %.4f MMD 单位" % (pose, moved))
    render(model, tex, out, p, n, yaw_deg=yaw)
    return 0


if __name__ == "__main__":
    sys.exit(main())
