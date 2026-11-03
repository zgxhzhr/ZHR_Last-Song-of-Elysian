#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
vmd2anim —— 把 MMD 的 VMD 动作文件解析成乐土终曲可用的 JSON 关键帧。

输出结构:
{
  "format": "lse-anim",
  "version": 1,
  "model": "动作对应的模型名",
  "bones": {
      "センター": {
          "pos": [[frame, x, y, z], ...],
          "rot": [[frame, qx, qy, qz, qw], ...]
      },
      ...
  },
  "morphs": {
      "まばたき": [[frame, weight], ...]
  },
  "stats": {"boneTracks": n, "boneKeys": n, "morphTracks": n, "morphKeys": n, "maxFrame": n}
}

MMD 的帧率固定 30 fps。
"""
import struct
import json
import os
import sys

VMD_MAGIC = b"Vocaloid Motion Data 0002"


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def u32(self):
        v = struct.unpack_from("<I", self.data, self.pos)[0]
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

    def fixed(self, n):
        raw = self.data[self.pos:self.pos + n]
        self.pos += n
        return raw.split(b"\x00")[0].decode("shift_jis", errors="replace")

    def skip(self, n):
        self.pos += n


def parse_vmd(path):
    with open(path, "rb") as f:
        data = f.read()

    r = Reader(data)
    magic = data[0:30]
    if not magic.startswith(b"Vocaloid Motion Data"):
        raise ValueError("不是 VMD 文件：%r" % magic)

    version = magic[25:30].decode("ascii", errors="replace").strip()
    r.pos = 30
    model_name = r.fixed(20) if version == "0001" else r.fixed(20)

    # ---------- 骨骼关键帧 ----------
    bone_count = r.u32()
    bones = {}
    bone_keys = 0
    max_frame = 0

    for _ in range(bone_count):
        name = r.fixed(15)
        frame = r.u32()
        px, py, pz = r.f32x(3)
        qx, qy, qz, qw = r.f32x(4)
        r.skip(64)  # 插值曲线，暂不使用

        max_frame = max(max_frame, frame)
        entry = bones.setdefault(name, {"pos": [], "rot": []})
        entry["pos"].append([frame, round(px, 5), round(py, 5), round(pz, 5)])
        entry["rot"].append([frame, round(qx, 6), round(qy, 6), round(qz, 6), round(qw, 6)])
        bone_keys += 1

    # ---------- 表情关键帧 ----------
    morph_count = r.u32()
    morphs = {}
    morph_keys = 0
    for _ in range(morph_count):
        name = r.fixed(15)
        frame = r.u32()
        weight = r.f32()
        max_frame = max(max_frame, frame)
        morphs.setdefault(name, []).append([frame, round(weight, 5)])
        morph_keys += 1

    return {
        "format": "lse-anim",
        "version": 1,
        "model": model_name,
        "vmdVersion": version,
        "fps": 30,
        "bones": bones,
        "morphs": morphs,
        "stats": {
            "boneTracks": len(bones),
            "boneKeys": bone_keys,
            "morphTracks": len(morphs),
            "morphKeys": morph_keys,
            "maxFrame": max_frame,
        },
    }


def main():
    if len(sys.argv) < 2:
        print("用法: vmd2anim.py <motion.vmd> [out.json]")
        return 1
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else os.path.splitext(src)[0] + ".anim.json"

    anim = parse_vmd(src)
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        json.dump(anim, f, ensure_ascii=False, separators=(",", ":"))

    s = anim["stats"]
    print("动作对应模型:", anim["model"])
    print("骨骼轨道 %d / 关键帧 %d" % (s["boneTracks"], s["boneKeys"]))
    print("表情轨道 %d / 关键帧 %d" % (s["morphTracks"], s["morphKeys"]))
    print("最长帧: %d（约 %.1f 秒 @30fps）" % (s["maxFrame"], s["maxFrame"] / 30.0))
    print("输出:", dst, "(%.2f MB)" % (os.path.getsize(dst) / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main())
