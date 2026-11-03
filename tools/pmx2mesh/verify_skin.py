#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
verify_skin.py —— 用精确四元数复现 Java 的 MeshSkeleton（含付与），验证蒙皮正确性。

和 pose_preview.py 的区别：那个用欧拉角近似，这个完全按 Java 的公式算：
  grantedRot = ownRot * slerp(identity, conj(grantParentRot), |w|)   (w<0)
  grantedRot = ownRot * slerp(identity,      grantParentRot,  w)    (w>0)
  world      = parentWorld * [T(pos+off) * R(grantedRot) * T(-pos)]

输出对比：静态姿势下顶点位移应严格为 0；动画姿势下不应出现孤立飞散的顶点。
"""
import json
import io
import sys
import math

import numpy as np
from PIL import Image


# ---------- 四元数 ----------

def q_identity():
    return np.array([0.0, 0.0, 0.0, 1.0])   # x, y, z, w


def q_from_euler(rx, ry, rz):
    """XYZ 顺序，与 JOML rotateXYZ 一致。"""
    cx, sx = math.cos(rx * 0.5), math.sin(rx * 0.5)
    cy, sy = math.cos(ry * 0.5), math.sin(ry * 0.5)
    cz, sz = math.cos(rz * 0.5), math.sin(rz * 0.5)

    qx = np.array([sx, 0, 0, cx])
    qy = np.array([0, sy, 0, cy])
    qz = np.array([0, 0, sz, cz])
    return q_mul(q_mul(qx, qy), qz)


def q_mul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return np.array([
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
        aw * bw - ax * bx - ay * by - az * bz,
    ])


def q_conj(q):
    return np.array([-q[0], -q[1], -q[2], q[3]])


def q_slerp(a, b, t):
    dot = float(np.dot(a, b))
    if dot < 0:
        b = -b
        dot = -dot
    if dot > 0.9995:
        r = a + t * (b - a)
        n = np.linalg.norm(r)
        return r / n if n > 0 else q_identity()
    theta = math.acos(max(-1.0, min(1.0, dot)))
    sin_t = math.sin(theta)
    return (math.sin((1 - t) * theta) / sin_t) * a + (math.sin(t * theta) / sin_t) * b


def q_to_matrix(q):
    """四元数 → 4x4 旋转矩阵（列向量约定，与 OpenGL/JOML 一致）。"""
    x, y, z, w = q / (np.linalg.norm(q) or 1.0)
    xx, yy, zz = x * x, y * y, z * z
    xy, xz, yz = x * y, x * z, y * z
    wx, wy, wz = w * x, w * y, w * z
    m = np.eye(4)
    m[0, 0] = 1 - 2 * (yy + zz); m[0, 1] = 2 * (xy - wz);     m[0, 2] = 2 * (xz + wy)
    m[1, 0] = 2 * (xy + wz);     m[1, 1] = 1 - 2 * (xx + zz); m[1, 2] = 2 * (yz - wx)
    m[2, 0] = 2 * (xz - wy);     m[2, 1] = 2 * (yz + wx);     m[2, 2] = 1 - 2 * (xx + yy)
    return m


def m_translate(x, y, z):
    m = np.eye(4)
    m[0, 3] = x; m[1, 3] = y; m[2, 3] = z
    return m


# ---------- 骨架 ----------

class Skeleton:
    def __init__(self, model):
        self.bones = model["bones"]
        self.n = len(self.bones)
        self.rot = [q_identity() for _ in range(self.n)]
        self.off = np.zeros((self.n, 3))

    def find(self, *names):
        for nm in names:
            for i, b in enumerate(self.bones):
                if b["name"] == nm:
                    return i
        return -1

    def set_euler(self, i, rx, ry, rz):
        if 0 <= i < self.n:
            self.rot[i] = q_from_euler(rx, ry, rz)

    def world(self):
        """完全按 Java 的 applyGrants + computeWorldMatrices。"""
        granted_rot = [q_identity() for _ in range(self.n)]
        granted_off = np.zeros((self.n, 3))

        for i, b in enumerate(self.bones):
            gp = b.get("grant_parent", -1)
            gw = float(b.get("grant_weight") or 0.0)
            if 0 <= gp < self.n and abs(gw) > 0.001:
                target = q_conj(self.rot[gp]) if gw < 0 else self.rot[gp]
                mixed = q_slerp(q_identity(), target, min(1.0, abs(gw)))
                granted_rot[i] = q_mul(self.rot[i], mixed)
            else:
                granted_rot[i] = self.rot[i]

            gpm = b.get("grant_parent_move", -1)
            gwm = float(b.get("grant_weight_move") or 0.0)
            if 0 <= gpm < self.n and abs(gwm) > 0.001:
                granted_off[i] = self.off[i] + gwm * self.off[gpm]
            else:
                granted_off[i] = self.off[i]

        worlds = [None] * self.n
        for i, b in enumerate(self.bones):
            base = np.array(b["p"], dtype=np.float64)
            pos = base + granted_off[i]
            local = m_translate(*pos) @ q_to_matrix(granted_rot[i]) @ m_translate(*(-base))
            parent = b["parent"]
            worlds[i] = local if parent < 0 else worlds[parent] @ local
        return worlds


def skin(model, worlds):
    V = np.array([v["p"] for v in model["vertices"]], dtype=np.float64)
    nv = len(V)
    out = np.zeros((nv, 3))
    for vi in range(nv):
        p4 = np.array([V[vi][0], V[vi][1], V[vi][2], 1.0])
        acc = np.zeros(4)
        total = 0.0
        for bi, w in model["vertices"][vi]["b"]:
            if bi < 0 or bi >= len(worlds) or w <= 0:
                continue
            acc += w * (worlds[bi] @ p4)
            total += w
        out[vi] = V[vi] if total <= 1e-6 else acc[:3]
    return out


def render(model, tex_root, out_path, pos, size=(700, 900)):
    idx = np.array(model["indices"], dtype=np.int32).reshape(-1, 3)
    UV = np.array([v["uv"] for v in model["vertices"]])

    lo, hi = pos.min(axis=0), pos.max(axis=0)
    c = (lo + hi) * 0.5
    ext = max(hi[0] - lo[0], hi[1] - lo[1]) * 0.55
    p = (pos - c) / ext

    W, H = size
    sx = (p[:, 0] * 0.5 + 0.5) * W
    sy = (0.5 - p[:, 1] * 0.5) * H
    sz = p[:, 2]

    color = np.zeros((H, W, 3), np.uint8); color[:, :] = (18, 20, 26)
    zbuf = np.full((H, W), -np.inf, np.float32)

    texs = []
    for nm in model["textures"]:
        fp = __import__("os").path.join(tex_root, nm)
        texs.append(np.asarray(Image.open(fp).convert("RGB"), np.uint8) if __import__("os").path.isfile(fp) else None)

    off = 0
    for mat in model["materials"]:
        cnt = mat["face_count"]
        if cnt <= 0:
            continue
        ti = mat["texture"]; tex = texs[ti] if 0 <= ti < len(texs) else None
        d = mat["diffuse"]
        for fi in range(off // 3, (off + cnt) // 3):
            i0, i1, i2 = idx[fi]
            x0, y0 = sx[i0], sy[i0]; x1, y1 = sx[i1], sy[i1]; x2, y2 = sx[i2], sy[i2]
            area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if abs(area) < 1e-6:
                continue
            minx = max(0, int(min(x0, x1, x2))); maxx = min(W - 1, int(max(x0, x1, x2)) + 1)
            miny = max(0, int(min(y0, y1, y2))); maxy = min(H - 1, int(max(y0, y1, y2)) + 1)
            if minx > maxx or miny > maxy:
                continue
            den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            if abs(den) < 1e-9:
                continue
            u0, v0 = UV[i0]; u1, v1 = UV[i1]; u2, v2 = UV[i2]
            for py in range(miny, maxy + 1):
                fy = py + 0.5
                for px in range(minx, maxx + 1):
                    fx = px + 0.5
                    w0 = ((y1 - y2) * (fx - x2) + (x2 - x1) * (fy - y2)) / den
                    w1 = ((y2 - y0) * (fx - x2) + (x0 - x2) * (fy - y2)) / den
                    w2 = 1 - w0 - w1
                    if w0 < 0 or w1 < 0 or w2 < 0:
                        continue
                    z = w0 * sz[i0] + w1 * sz[i1] + w2 * sz[i2]
                    if z <= zbuf[py, px]:
                        continue
                    zbuf[py, px] = z
                    if tex is None:
                        color[py, px] = (255, 0, 255); continue
                    th, tw = tex.shape[:2]
                    u = w0 * u0 + w1 * u1 + w2 * u2
                    v = w0 * v0 + w1 * v1 + w2 * v2
                    rgb = tex[int(np.clip(v, 0, .9999) * th), int(np.clip(u, 0, .9999) * tw)]
                    color[py, px] = np.clip(rgb * np.array(d[:3]), 0, 255).astype(np.uint8)
        off += cnt
    Image.fromarray(color).save(out_path)
    print("  输出:", out_path)


def main():
    mesh = r"E:\Mod\其它项目\ZHR_Last-Song-of-Elysian\src\main\resources\assets\lastsongofelysian\meshes\mobius.mesh.json"
    tex = r"E:\Mod\其它项目\ZHR_Last-Song-of-Elysian\src\main\resources\assets\lastsongofelysian\textures\entity\mmd\mobius"
    outdir = r"E:\Mod\其它项目\ZHR_Last-Song-of-Elysian\tools\pmx2mesh"

    model = json.load(io.open(mesh, encoding="utf-8"))
    V = np.array([v["p"] for v in model["vertices"]])

    # --- 静态：验证顶点不变 ---
    sk = Skeleton(model)
    w = sk.world()
    p = skin(model, w)
    d = np.abs(p - V).max()
    print("静态姿势最大位移: %.8f  → %s" % (d, "✓ 精确为0" if d < 1e-6 else "✗ 有偏移"))

    # --- 待机动画（复现 HeroAnimator）---
    sk2 = Skeleton(model)
    t = 100 * 0.05
    center = sk2.find("センター")
    upper = sk2.find("上半身")
    upper2 = sk2.find("上半身2")
    head = sk2.find("頭")
    if center >= 0:
        sk2.off[center, 1] = math.sin(t) * 0.10
        sk2.set_euler(center, 0.0, math.sin(t * 0.6) * 0.012, 0.0)
    if upper >= 0:
        sk2.set_euler(upper, math.sin(t * 0.08) * 0.008, 0, 0)
    if upper2 >= 0:
        sk2.set_euler(upper2, math.sin(t * 0.08) * 0.0048, 0, 0)
    if head >= 0:
        sk2.set_euler(head, math.sin(t * 0.031) * 0.04, math.sin(t * 0.045) * 0.09, 0)

    w2 = sk2.world()
    p2 = skin(model, w2)
    moved = np.linalg.norm(p2 - V, axis=1)
    print("待机姿势: 最大位移 %.4f, 平均 %.5f" % (moved.max(), moved.mean()))
    # 检查是否有孤立飞散顶点（位移远超平均）
    outlier = (moved > moved.mean() * 20 + 0.5).sum()
    print("  疑似飞散顶点（位移 > 平均*20+0.5）: %d" % outlier)
    if outlier:
        bad = np.argsort(-moved)[:8]
        print("  位移最大的 8 个顶点:")
        for bi in bad:
            print("    v%-6d 位移 %.3f  原位置 (%.2f, %.2f, %.2f)  权重 %s" % (
                bi, moved[bi], V[bi][0], V[bi][1], V[bi][2],
                [(b, round(w_, 2)) for b, w_ in model["vertices"][bi]["b"]]))

    render(model, tex, outdir + r"\_exact_idle.png", p2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
