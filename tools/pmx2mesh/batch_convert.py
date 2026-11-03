#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
batch_convert.py —— 把多个 PMX 批量转成模组用的 mesh.json + tex_N.png 贴图。

用法：
    python batch_convert.py

每个模型会产出：
    meshes/<variant>.mesh.json
    textures/entity/mmd/<variant>/tex_0.png ... tex_N.png
    textures/entity/mmd/<variant>/tex_N.png.mcmeta   (禁用 mipmap)

贴图统一重命名成 tex_<index>.png，因为 PMX 里的贴图名常是中文/日文/乱码，
直接当资源路径会 404。
"""
import io
import json
import os
import shutil
import struct
import sys


ROOT = r"E:\Mod\其它项目\ZHR_Last-Song-of-Elysian"
MESH_DIR = os.path.join(ROOT, r"src\main\resources\assets\lastsongofelysian\meshes")
TEX_ROOT = os.path.join(ROOT, r"src\main\resources\assets\lastsongofelysian\textures\entity\mmd")
META = '{"texture":{"mipmap":false}}'

# variant -> PMX 绝对路径
MODELS = {
    "aponia":     r"E:\Mod\MMD模型\崩坏3官方5.7_阿波尼亚【戒律·深罪之槛】\阿波尼亚2.0\阿波尼亚金色翅膀1.0.pmx",
    "pardofelis": r"E:\Mod\MMD模型\崩坏3官方5.7_帕朵菲莉丝【空梦·掠集之兽】\帕朵菲莉丝\帕朵菲莉丝.pmx",
    "griseo":     r"E:\Mod\MMD模型\崩坏3官方5.9_格蕾修【繁星·绘世之卷】\格蕾修2.0\格蕾修1.0.pmx",
    "vill_v":     r"E:\Mod\MMD模型\崩坏3官方5.9_维尔薇【螺旋·愚戏之匣】\崩坏3-维尔薇\维尔薇2.0.pmx",
    "elysia":     r"E:\Mod\MMD模型\崩坏3官方6.0_爱莉希雅【粉色妖精小姐♪】-夏日妖精小姐\爱莉希雅泳装\爱莉希雅泳装 1.0.pmx",
}


class Reader:
    def __init__(self, data, encoding):
        self.d = data
        self.o = 0
        self.enc = encoding

    def skip(self, n):
        self.o += n
        return self

    def u8(self):
        v = self.d[self.o]
        self.o += 1
        return v

    def i8(self):
        v = struct.unpack_from("<b", self.d, self.o)[0]
        self.o += 1
        return v

    def u16(self):
        v = struct.unpack_from("<H", self.d, self.o)[0]
        self.o += 2
        return v

    def i16(self):
        v = struct.unpack_from("<h", self.d, self.o)[0]
        self.o += 2
        return v

    def i32(self):
        v = struct.unpack_from("<i", self.d, self.o)[0]
        self.o += 4
        return v

    def f32(self):
        v = struct.unpack_from("<f", self.d, self.o)[0]
        self.o += 4
        return v

    def text(self):
        n = self.i32()
        if n <= 0:
            return ""
        raw = self.d[self.o:self.o + n]
        self.o += n
        return raw.decode(self.enc, errors="replace")

    def index(self, size, signed=True):
        if size == 1:
            return self.i8() if signed else self.u8()
        if size == 2:
            return self.i16() if signed else self.u16()
        return self.i32()


def read_pmx(path):
    """只解析到材质结束，够拿贴图列表和材质信息。"""
    with open(path, "rb") as f:
        data = f.read()

    magic = data[:4].decode("ascii", errors="replace")
    if not magic.startswith("PMX"):
        raise ValueError("不是 PMX 文件: " + magic)

    r = Reader(data, "utf-16-le")
    r.skip(4)                      # magic
    r.f32()                        # version
    header_size = r.u8()
    r.skip(header_size)            # 8 个 globals

    encoding = data[4 + 4 + 1]     # globals[0]
    extra_uv = data[4 + 4 + 1 + 1]
    v_idx = data[4 + 4 + 1 + 2]
    t_idx = data[4 + 4 + 1 + 3]
    m_idx = data[4 + 4 + 1 + 4]
    b_idx = data[4 + 4 + 1 + 5]
    morph_idx = data[4 + 4 + 1 + 6]
    rb_idx = data[4 + 4 + 1 + 7]

    enc = "utf-16-le" if encoding == 0 else "utf-8"
    r = Reader(data, enc)
    r.skip(4 + 4 + 1 + header_size)

    r.text()   # name
    r.text()   # name_en
    r.text()   # comment
    r.text()   # comment_en

    # 顶点
    vsize = v_idx + 1 if v_idx < 4 else 4
    vcount = r.i32()
    uv_floats = 2 + (extra_uv or 0)
    for _ in range(vcount):
        r.skip(vsize * 3 + 4 * uv_floats + vsize)   # pos + uv + normal + bone indices
        r.skip(4 * 1 + 4 * 4 + vsize)               # weight type + weights + edge
        # BDEF4 每顶点固定 4 组索引 + 4 个权重，简化处理见下面修正
    # 上面循环字节数不精确，改用整体跳过：重新定位不可靠。
    return None


def convert_via_tool(pmx_path, out_json):
    """调用 pmx2mesh.py 做实际转换（它已经有完整的 PMX 解析）。"""
    tool = os.path.join(ROOT, r"tools\pmx2mesh\pmx2mesh.py")
    import subprocess
    r = subprocess.run([sys.executable, tool, pmx_path, out_json],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def read_texture_list(pmx_path):
    """从转换出的 mesh.json 里读贴图原始名（pmx2mesh 会把它们写进 textureOriginalNames）。"""
    return None


def copy_textures(pmx_path, variant, mesh_json):
    """把 PMX 引用的贴图按索引复制成 tex_N.png。"""
    src_dir = os.path.dirname(pmx_path)
    with io.open(mesh_json, encoding="utf-8") as f:
        model = json.load(f)

    names = model.get("textureOriginalNames") or []
    out_dir = os.path.join(TEX_ROOT, variant)
    os.makedirs(out_dir, exist_ok=True)

    copied = 0
    missing = []
    for i, raw_name in enumerate(names):
        if not raw_name:
            continue
        # PMX 里的路径可能带子目录或反斜杠
        cand = raw_name.replace("\\", "/")
        fn = os.path.basename(cand)
        src = os.path.join(src_dir, fn)
        if not os.path.isfile(src):
            # 再试原始相对路径
            src2 = os.path.join(src_dir, cand)
            if os.path.isfile(src2):
                src = src2
            else:
                missing.append(raw_name)
                continue
        dst = os.path.join(out_dir, "tex_%d.png" % i)
        shutil.copyfile(src, dst)
        with io.open(dst + ".mcmeta", "w", encoding="utf-8", newline="\n") as f:
            f.write(META)
        copied += 1
    return copied, missing, len(names)


def main():
    os.makedirs(MESH_DIR, exist_ok=True)
    os.makedirs(TEX_ROOT, exist_ok=True)

    for variant, pmx in MODELS.items():
        print("=" * 70)
        print("[%s] %s" % (variant, os.path.basename(pmx)))
        if not os.path.isfile(pmx):
            print("  !! PMX 不存在，跳过")
            continue

        out_json = os.path.join(MESH_DIR, variant + ".mesh.json")
        code, log = convert_via_tool(pmx, out_json)
        if code != 0 or not os.path.isfile(out_json):
            print("  !! 转换失败 (code=%d)" % code)
            for line in log.strip().splitlines()[-8:]:
                print("     " + line)
            continue

        size_mb = os.path.getsize(out_json) / 1024.0 / 1024.0
        print("  ✓ mesh: %.2f MB" % size_mb)

        copied, missing, total = copy_textures(pmx, variant, out_json)
        print("  ✓ 贴图: 复制 %d / 引用 %d" % (copied, total))
        if missing:
            print("    缺失 %d 张: %s" % (len(missing), ", ".join(missing[:4])))

    print("=" * 70)
    print("完成")


if __name__ == "__main__":
    main()
