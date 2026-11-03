#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
simplify_mesh.py —— 顶点聚类式减面。

按「材质 + 空间网格」做顶点聚类：同一材质、落在同一格里的顶点合并成一个
（坐标、法线、UV、骨骼权重取加权平均），然后重建三角形并丢弃退化面。

为什么按材质分组：不同材质用不同贴图，跨材质合并会把 UV 拉到无关区域，
贴图直接炸开。按材质分组后，每个材质内部各自简化，UV 邻域保持连续。

用法:
    simplify_mesh.py <in.mesh.json> <out.mesh.json> [--grid 0.5]

grid 越小越精细（保留更多三角形），越大越激进。MMD 模型人物高约 21 单位。
建议值：
    0.30 → 约保留 60~70%
    0.50 → 约保留 35~45%
    0.80 → 约保留 15~25%
"""
import json
import io
import os
import sys
import math


def simplify(model, grid):
    verts = model["vertices"]
    idx = model["indices"]
    mats = model["materials"]
    bones = model["bones"]

    n = len(verts)
    # 每个顶点归到 (材质下标, 网格坐标) 桶里；材质由它所属的面决定，
    # 一个顶点可能被多个材质共用，这里以首次出现的面为准。
    vert_mat = [-1] * n

    off = 0
    for mi, mt in enumerate(mats):
        cnt = mt["face_count"]
        if cnt <= 0:
            off += cnt
            continue
        for t in range(off, off + cnt):
            for k in range(3):
                vi = idx[t]
                if vert_mat[vi] < 0:
                    vert_mat[vi] = mi
        off += cnt

    # 聚类键
    key_to_new = {}
    new_verts = []
    old_to_new = [0] * n

    for vi in range(n):
        v = verts[vi]
        mi = vert_mat[vi]
        px, py, pz = v["p"]
        gx = int(math.floor(px / grid))
        gy = int(math.floor(py / grid))
        gz = int(math.floor(pz / grid))
        key = (mi, gx, gy, gz)

        if key in key_to_new:
            ni = key_to_new[key]
            acc = new_verts[ni]
            acc["_n"] += 1
            for a in range(3):
                acc["p"][a] += v["p"][a]
                acc["n"][a] += v["n"][a]
            for a in range(2):
                acc["uv"][a] += v["uv"][a]
            for bi, w in v["b"]:
                acc["bw"][bi] = acc["bw"].get(bi, 0.0) + w
            old_to_new[vi] = ni
        else:
            ni = len(new_verts)
            key_to_new[key] = ni
            new_verts.append({
                "p": list(v["p"]),
                "n": list(v["n"]),
                "uv": list(v["uv"]),
                "bw": {bi: w for bi, w in v["b"]},
                "_n": 1,
            })
            old_to_new[vi] = ni

    # 求平均
    out_verts = []
    for acc in new_verts:
        c = acc["_n"]
        p = [x / c for x in acc["p"]]
        nn = [x / c for x in acc["n"]]
        ln = math.sqrt(sum(x * x for x in nn)) or 1.0
        nn = [x / ln for x in nn]
        uv = [x / c for x in acc["uv"]]
        # 权重：取前 4 个最大的，重新归一化
        items = sorted(acc["bw"].items(), key=lambda kv: -kv[1])[:4]
        total = sum(w for _, w in items) or 1.0
        bb = [[int(bi), round(w / total, 6)] for bi, w in items]
        out_verts.append({
            "p": [round(x, 5) for x in p],
            "n": [round(x, 5) for x in nn],
            "uv": [round(uv[0], 6), round(uv[1], 6)],
            "b": bb,
        })

    # 重建三角形，按材质分组
    out_indices = []
    out_mats = []
    off = 0
    dropped = 0
    for mt in mats:
        cnt = mt["face_count"]
        if cnt <= 0:
            off += cnt
            continue
        start = len(out_indices)
        seen = set()
        for t in range(off, off + cnt, 3):
            a = old_to_new[idx[t]]
            b = old_to_new[idx[t + 1]]
            c = old_to_new[idx[t + 2]]
            if a == b or b == c or a == c:
                dropped += 1
                continue
            # 去掉完全重复的三角形（聚类后常见）
            tri_key = tuple(sorted((a, b, c)))
            if tri_key in seen:
                dropped += 1
                continue
            seen.add(tri_key)
            out_indices.extend([a, b, c])
        face_count = len(out_indices) - start
        if face_count > 0:
            newmt = dict(mt)
            newmt["face_count"] = face_count
            out_mats.append(newmt)
        off += cnt

    out = dict(model)
    out["vertices"] = out_verts
    out["indices"] = out_indices
    out["materials"] = out_mats

    total_faces = sum(m["face_count"] for m in out_mats)
    out["stats"] = {
        "vertices": len(out_verts),
        "triangles": total_faces // 3,
        "textures": len(model["textures"]),
        "materials": len(out_mats),
        "bones": len(bones),
    }
    return out, dropped


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    src = sys.argv[1]
    dst = sys.argv[2]
    grid = 0.5
    if "--grid" in sys.argv:
        grid = float(sys.argv[sys.argv.index("--grid") + 1])

    model = json.load(io.open(src, encoding="utf-8"))
    before_v = model["stats"]["vertices"]
    before_t = model["stats"]["triangles"]

    out, dropped = simplify(model, grid)

    io.open(dst, "w", encoding="utf-8", newline="\n").write(
        json.dumps(out, ensure_ascii=False, separators=(",", ":")))

    after_v = out["stats"]["vertices"]
    after_t = out["stats"]["triangles"]
    print("grid = %.2f" % grid)
    print("顶点 %d → %d  (%.0f%%)" % (before_v, after_v, after_v * 100.0 / before_v))
    print("三角形 %d → %d  (%.0f%%)" % (before_t, after_t, after_t * 100.0 / before_t))
    print("丢弃退化/重复面: %d" % dropped)
    print("材质 %d → %d" % (len(model["materials"]), len(out["materials"])))
    print("输出:", dst, "(%.2f MB)" % (os.path.getsize(dst) / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main())
