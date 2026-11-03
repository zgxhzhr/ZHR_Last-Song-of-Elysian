#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
pmx2mesh —— 把 MMD 的 PMX 模型解析成乐土终曲可用的 JSON 网格数据。

输出结构（v1）:
{
  "format": "lse-mesh",
  "version": 1,
  "name": "模型名",
  "encoding": "utf-8",
  "textures": ["脸.png", ...],
  "vertices": [
      {"p": [x,y,z], "n": [x,y,z], "uv": [u,v],
       "b": [[boneIndex, weight], ...]}          # 最多 4 个骨骼影响，权重和=1
  ],
  "indices": [i0, i1, i2, ...],                  # 三角形列表，顶点索引
  "materials": [
      {"name": "...", "texture": 0, "diffuse": [r,g,b,a],
       "specular": [r,g,b], "shininess": f, "ambient": [r,g,b],
       "flags": int, "edge_color": [r,g,b,a], "edge_size": f,
       "sphere_mode": int, "sphere_texture": -1, "toon_texture": -1,
       "memo": "...", "face_count": n}
  ],
  "bones": [
      {"name": "...", "name_en": "...", "parent": -1,
       "p": [x,y,z], "layer": int, "flags": int,
       "tail_index": -1, "tail_offset": [x,y,z],
       "inherit_parent": -1, "inherit_weight": 0,
       "fixed_axis": [x,y,z], "local_axis_x": [x,y,z], "local_axis_z": [x,y,z],
       "external_key": int}
  ],
  "stats": {"vertices": n, "triangles": n, "textures": n, "materials": n, "bones": n}
}
"""
import struct
import json
import os
import sys


class Reader:
    def __init__(self, data, encoding):
        self.data = data
        self.pos = 0
        self.enc = encoding

    def u8(self):
        v = self.data[self.pos]
        self.pos += 1
        return v

    def i32(self):
        v = struct.unpack_from("<i", self.data, self.pos)[0]
        self.pos += 4
        return v

    def f32(self):
        v = struct.unpack_from("<f", self.data, self.pos)[0]
        self.pos += 4
        return v

    def f32x(self, n):
        v = list(struct.unpack_from("<%df" % n, self.data, self.pos))
        self.pos += 4 * n
        return v

    def text(self):
        ln = self.i32()
        if ln <= 0:
            return ""
        raw = self.data[self.pos:self.pos + ln]
        self.pos += ln
        # PMX: encoding 0 = UTF-16LE, 1 = UTF-8
        if self.enc == 0:
            return raw.decode("utf-16-le", errors="replace")
        return raw.decode("utf-8", errors="replace")

    def index(self, size, signed=True):
        """PMX 的索引字段：1/2/4 字节，-1 表示无。"""
        if size == 1:
            v = struct.unpack_from("<b" if signed else "<B", self.data, self.pos)[0]
            self.pos += 1
        elif size == 2:
            v = struct.unpack_from("<h" if signed else "<H", self.data, self.pos)[0]
            self.pos += 2
        else:
            v = struct.unpack_from("<i" if signed else "<I", self.data, self.pos)[0]
            self.pos += 4
        return v

    def skip(self, n):
        self.pos += n


def parse_pmx(path):
    with open(path, "rb") as f:
        data = f.read()

    r = Reader(data, 0)
    magic = data[0:4]
    if magic not in (b"PMX ", b"PMX\x00"):
        raise ValueError("不是 PMX 文件：magic=%r" % magic)
    r.pos = 4
    version = r.f32()
    header_size = r.u8()

    # header 固定 8 字节
    enc = data[r.pos]
    add_uv = data[r.pos + 1]
    v_idx = data[r.pos + 2]
    t_idx = data[r.pos + 3]
    m_idx = data[r.pos + 4]
    b_idx = data[r.pos + 5]
    mo_idx = data[r.pos + 6]
    rb_idx = data[r.pos + 7]
    r.pos += 8
    r.enc = enc

    name = r.text()
    name_en = r.text()
    comment = r.text()
    comment_en = r.text()

    out = {
        "format": "lse-mesh",
        "version": 1,
        "name": name or name_en,
        "name_en": name_en,
        "encoding": "utf-16le" if enc == 0 else "utf-8",
        "add_uv": add_uv,
        "textures": [],
        "vertices": [],
        "indices": [],
        "materials": [],
        "bones": [],
    }

    # ---------- 顶点 ----------
    vcount = r.i32()
    verts = []
    for _ in range(vcount):
        p = r.f32x(3)
        n = r.f32x(3)
        uv = r.f32x(2)
        if add_uv:
            r.skip(16 * add_uv)
        wt = r.u8()
        influences = []
        if wt == 0:  # BDEF1
            influences.append([r.index(b_idx), 1.0])
        elif wt == 1:  # BDEF2
            b0 = r.index(b_idx)
            b1 = r.index(b_idx)
            w0 = r.f32()
            influences.append([b0, w0])
            influences.append([b1, 1.0 - w0])
        elif wt == 2:  # BDEF4: 先 4 个索引，再 4 个权重
            idxs = [r.index(b_idx) for _k in range(4)]
            ws = [r.f32() for _k in range(4)]
            for _k in range(4):
                influences.append([idxs[_k], ws[_k]])
        elif wt == 3:  # SDEF
            b0 = r.index(b_idx)
            b1 = r.index(b_idx)
            w0 = r.f32()
            r.skip(12 * 3)  # C, R0, R1
            influences.append([b0, w0])
            influences.append([b1, 1.0 - w0])
        elif wt == 4:  # QDEF: 同 BDEF4，先 4 个索引再 4 个权重
            idxs = [r.index(b_idx) for _k in range(4)]
            ws = [r.f32() for _k in range(4)]
            for _k in range(4):
                influences.append([idxs[_k], ws[_k]])
        else:
            raise ValueError("未知权重类型 %d @ 顶点 %d" % (wt, len(verts)))
        r.f32()  # edge scale
        # 归一化 + 裁掉 0 权重
        influences = [[b, w] for b, w in influences if w > 1e-6]
        total = sum(w for _, w in influences)
        if total > 0:
            influences = [[b, w / total] for b, w in influences]
        verts.append({
            "p": [round(x, 6) for x in p],
            "n": [round(x, 6) for x in n],
            "uv": [round(uv[0], 6), round(uv[1], 6)],
            "b": influences[:4],
        })
    out["vertices"] = verts

    # ---------- 面 ----------
    icount = r.i32()
    indices = []
    for _ in range(icount):
        indices.append(r.index(v_idx, signed=False))
    out["indices"] = indices

    # ---------- 贴图 ----------
    # 贴图名常有中日文，直接当资源路径会出问题；统一改成 tex_<索引>.png，
    # 原始名保留在 textureOriginalNames 里供人工对照。
    tcount = r.i32()
    out["textureOriginalNames"] = []
    for ti in range(tcount):
        original = r.text()
        out["textureOriginalNames"].append(original)
        out["textures"].append("tex_%d.png" % ti)

    # ---------- 材质 ----------
    mcount = r.i32()
    for _ in range(mcount):
        m = {}
        m["name"] = r.text()
        m["name_en"] = r.text()
        m["diffuse"] = [round(x, 6) for x in r.f32x(4)]
        m["specular"] = [round(x, 6) for x in r.f32x(3)]
        m["shininess"] = round(r.f32(), 6)
        m["ambient"] = [round(x, 6) for x in r.f32x(3)]
        m["flags"] = r.u8()
        m["edge_color"] = [round(x, 6) for x in r.f32x(4)]
        m["edge_size"] = round(r.f32(), 6)
        m["texture"] = r.index(t_idx)
        m["sphere_texture"] = r.index(t_idx)
        m["sphere_mode"] = r.u8()
        shared_toon = r.u8()
        if shared_toon == 1:
            m["toon_texture"] = r.u8()          # 内置 toon，用序号
        else:
            m["toon_texture"] = r.index(t_idx)  # 外部 toon 贴图
        m["memo"] = r.text()
        m["face_count"] = r.i32()
        out["materials"].append(m)

    # ---------- 骨骼 ----------
    bcount = r.i32()
    for _ in range(bcount):
        b = {}
        b["name"] = r.text()
        b["name_en"] = r.text()
        b["p"] = [round(x, 6) for x in r.f32x(3)]
        b["parent"] = r.index(b_idx)
        b["layer"] = r.i32()
        b["flags"] = struct.unpack_from("<H", data, r.pos)[0]
        r.pos += 2
        flags = b["flags"]
        if flags & 0x0001:  # 可连接
            b["tail_index"] = r.index(b_idx)
        else:
            b["tail_offset"] = [round(x, 6) for x in r.f32x(3)]
        # PMX 2.0 规范里「回転付与(0x0100)」和「移動付与(0x0200)」共用同一组
        # 字段：1 个 parent 索引 + 1 个 rate 浮点（两个位都置位时也只有一个 rate）。
        # 逐位各读一个 index/float 会让遇到 0x03xx 骨骼后的所有骨骼整体错位，
        # 阿波尼亚和维尔薇正是因此转换失败。写法对齐 mmd-rs 官方解析器。
        if flags & 0x0300:
            grant_parent = r.index(b_idx)
            grant_rate = round(r.f32(), 6)
            b["grant_parent"] = grant_parent
            b["grant_weight"] = grant_rate
            b["grant_weight_move"] = grant_rate
        if flags & 0x0400:  # 軸固定
            b["fixed_axis"] = [round(x, 6) for x in r.f32x(3)]
        if flags & 0x0800:  # ローカル軸
            b["local_axis_x"] = [round(x, 6) for x in r.f32x(3)]
            b["local_axis_z"] = [round(x, 6) for x in r.f32x(3)]
        if flags & 0x2000:  # 外部親変形
            b["external_key"] = r.i32()
        if flags & 0x0020:  # IK
            ik_target = r.index(b_idx)
            ik_loop = r.i32()
            ik_limit = r.f32()
            ik_links = r.i32()
            for _k in range(ik_links):
                r.index(b_idx)
                limits = r.u8()
                if limits == 1:
                    r.skip(4 * 6)
            b["ik"] = {"target": ik_target, "loop": ik_loop, "limit": ik_limit, "links": ik_links}
        out["bones"].append(b)

    out["stats"] = {
        "vertices": len(out["vertices"]),
        "triangles": len(out["indices"]) // 3,
        "textures": len(out["textures"]),
        "materials": len(out["materials"]),
        "bones": len(out["bones"]),
    }
    return out


def main():
    if len(sys.argv) < 2:
        print("用法: pmx2mesh.py <model.pmx> [out.json]")
        return 1
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else os.path.splitext(src)[0] + ".mesh.json"

    model = parse_pmx(src)
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        json.dump(model, f, ensure_ascii=False, separators=(",", ":"))

    s = model["stats"]
    print("模型:", model["name"])
    print("顶点 %d / 三角形 %d / 贴图 %d / 材质 %d / 骨骼 %d" % (
        s["vertices"], s["triangles"], s["textures"], s["materials"], s["bones"]))
    print("输出:", dst, "(%.2f MB)" % (os.path.getsize(dst) / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main())
