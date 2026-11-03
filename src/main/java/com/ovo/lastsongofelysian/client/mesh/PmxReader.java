package com.ovo.lastsongofelysian.client.mesh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行时 PMX 2.0 解析器。
 *
 * <p>为什么要在 Java 里解析，而不是预先转成 mesh.json 打包进 jar：
 * MMD 模型的授权条款普遍写明「禁止二次配布」。把 PMX 的顶点/权重/贴图提取出来
 * 换个容器装进 jar，性质上仍是分发模型内容。所以模组只带解析器，模型由玩家
 * 自己放到 {@code .minecraft/lse-models/<variant>/} 下，运行时读取。
 *
 * <p>只解析渲染需要的部分（顶点/面/贴图名/材质/骨骼），morph、显示枠、
 * 物理刚体与关节都不读——到骨骼段结束即可。
 *
 * <p>几个容易踩的格式细节（都是实测踩过的）：
 * <ul>
 *   <li>回転付与(0x0100) 与 移動付与(0x0200) <b>共享</b>同一个 parent 索引和
 *       rate 浮点；两个位都置位时也只有一组，不是各一组</li>
 *   <li>PMX 2.0 没有 0x0080 / 0x1000 对应的额外字段，误读会让后续整体错位</li>
 *   <li>BDEF4 是「4 个索引之后 4 个权重」，不是索引权重交替</li>
 * </ul>
 */
public final class PmxReader {

    private PmxReader() {
    }

    // 骨骼 flags
    private static final int BONE_TAIL_INDEX = 0x0001;
    private static final int BONE_IK = 0x0020;
    private static final int BONE_GRANT_ROT = 0x0100;
    private static final int BONE_GRANT_MOV = 0x0200;
    private static final int BONE_FIXED_AXIS = 0x0400;
    private static final int BONE_LOCAL_AXIS = 0x0800;
    private static final int BONE_EXTERNAL_PARENT = 0x2000;

    public static MeshModel parse(Path pmxPath) throws IOException {
        byte[] data = Files.readAllBytes(pmxPath);
        return new Cursor(data, pmxPath).parseAll();
    }

    /** 带越界检查的字节游标。 */
    private static final class Cursor {
        private final byte[] d;
        private final Path source;
        private int p;

        Cursor(byte[] data, Path source) {
            this.d = data;
            this.source = source;
        }

        private void need(int n) throws IOException {
            if (p + n > d.length) {
                throw new IOException("PMX 读取越界：" + source.getFileName()
                        + " 需要 " + n + " 字节 @ " + p + "，文件只有 " + d.length);
            }
        }

        int u8() throws IOException {
            need(1);
            return d[p++] & 0xFF;
        }

        int i8() throws IOException {
            need(1);
            return d[p++];
        }

        int u16() throws IOException {
            need(2);
            int v = (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
            p += 2;
            return v;
        }

        int i32() throws IOException {
            need(4);
            int v = (d[p] & 0xFF)
                    | ((d[p + 1] & 0xFF) << 8)
                    | ((d[p + 2] & 0xFF) << 16)
                    | ((d[p + 3] & 0xFF) << 24);
            p += 4;
            return v;
        }

        long u32() throws IOException {
            return i32() & 0xFFFFFFFFL;
        }

        float f32() throws IOException {
            return Float.intBitsToFloat(i32());
        }

        float[] f32x(int n) throws IOException {
            float[] out = new float[n];
            for (int i = 0; i < n; i++) {
                out[i] = f32();
            }
            return out;
        }

        void skip(int n) throws IOException {
            need(n);
            p += n;
        }

        /**
         * 读索引。MMD 用 -1 表示「无」，所以骨骼/贴图/材质索引是有符号的；
         * 顶点索引是纯无符号。
         */
        int index(int size, boolean signed) throws IOException {
            if (size == 1) {
                return signed ? i8() : u8();
            }
            if (size == 2) {
                if (signed) {
                    need(2);
                    int v = (short) ((d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8));
                    p += 2;
                    return v;
                }
                return u16();
            }
            long v = u32();
            return signed ? (int) v : (int) (v & 0xFFFFFFFFL);
        }

        String text(boolean utf16) throws IOException {
            int len = i32();
            if (len < 0 || len > 1 << 20) {
                throw new IOException("PMX 文本长度异常 " + len + " @ " + (p - 4)
                        + "（多为前一段字段错位所致）: " + source.getFileName());
            }
            need(len);
            String s = new String(d, p, len, utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8);
            p += len;
            return s;
        }

        MeshModel parseAll() throws IOException {
            // --- 头部 ---
            need(9);
            String magic = new String(d, 0, 4, StandardCharsets.US_ASCII);
            if (!magic.startsWith("PMX")) {
                throw new IOException("不是 PMX 文件（magic=" + magic.trim() + "）: " + source);
            }
            p = 4;
            f32();                      // version
            int headerSize = u8();
            need(headerSize);
            int encoding = d[p] & 0xFF;
            int addUv = d[p + 1] & 0xFF;
            int vIdx = d[p + 2] & 0xFF;
            int tIdx = d[p + 3] & 0xFF;
            int mIdx = d[p + 4] & 0xFF;
            int bIdx = d[p + 5] & 0xFF;
            p += headerSize;
            boolean utf16 = encoding == 0;

            String name = text(utf16);
            String nameEn = text(utf16);
            text(utf16);                // comment
            text(utf16);                // comment_en

            // --- 顶点 ---
            int vcount = i32();
            if (vcount < 0 || vcount > 4_000_000) {
                throw new IOException("PMX 顶点数异常 " + vcount + ": " + source.getFileName());
            }
            float[] positions = new float[vcount * 3];
            float[] normals = new float[vcount * 3];
            float[] uvs = new float[vcount * 2];
            int[][] boneIndices = new int[vcount][];
            float[][] boneWeights = new float[vcount][];

            for (int i = 0; i < vcount; i++) {
                int po = i * 3;
                int uo = i * 2;
                positions[po] = f32();
                positions[po + 1] = f32();
                positions[po + 2] = f32();
                normals[po] = f32();
                normals[po + 1] = f32();
                normals[po + 2] = f32();
                uvs[uo] = f32();
                uvs[uo + 1] = f32();
                if (addUv > 0) {
                    skip(16 * addUv);
                }

                int wt = u8();
                int[] bi = new int[4];
                float[] bw = new float[4];
                bi[0] = bi[1] = bi[2] = bi[3] = -1;

                switch (wt) {
                    case 0 -> {                       // BDEF1
                        bi[0] = index(bIdx, true);
                        bw[0] = 1.0F;
                    }
                    case 1 -> {                       // BDEF2
                        bi[0] = index(bIdx, true);
                        bi[1] = index(bIdx, true);
                        float w0 = f32();
                        bw[0] = w0;
                        bw[1] = 1.0F - w0;
                    }
                    case 2, 4 -> {                    // BDEF4 / QDEF：先 4 索引再 4 权重
                        bi[0] = index(bIdx, true);
                        bi[1] = index(bIdx, true);
                        bi[2] = index(bIdx, true);
                        bi[3] = index(bIdx, true);
                        bw[0] = f32();
                        bw[1] = f32();
                        bw[2] = f32();
                        bw[3] = f32();
                    }
                    case 3 -> {                       // SDEF：两个骨骼 + 权重 + C/R0/R1
                        bi[0] = index(bIdx, true);
                        bi[1] = index(bIdx, true);
                        float w0 = f32();
                        bw[0] = w0;
                        bw[1] = 1.0F - w0;
                        skip(12 * 3);
                    }
                    default -> throw new IOException("未知顶点权重类型 " + wt
                            + " @ 顶点 " + i + ": " + source.getFileName());
                }
                f32();                            // edge scale

                // 归一化并丢掉零权重，与转换器保持一致，避免蒙皮偏差
                float total = 0.0F;
                for (int k = 0; k < 4; k++) {
                    if (bw[k] > 1e-6F) {
                        total += bw[k];
                    } else {
                        bw[k] = 0.0F;
                    }
                }
                if (total > 0.0F) {
                    for (int k = 0; k < 4; k++) {
                        bw[k] /= total;
                    }
                }
                boneIndices[i] = bi;
                boneWeights[i] = bw;
            }

            // --- 面 ---
            int icount = i32();
            if (icount < 0 || icount > 20_000_000) {
                throw new IOException("PMX 面索引数异常 " + icount + ": " + source.getFileName());
            }
            int[] indices = new int[icount];
            for (int i = 0; i < icount; i++) {
                indices[i] = index(vIdx, false);
            }

            // --- 贴图 ---
            int tcount = i32();
            List<String> textures = new ArrayList<>(Math.max(0, tcount));
            if (tcount < 0 || tcount > 4096) {
                throw new IOException("PMX 贴图数异常 " + tcount + ": " + source.getFileName());
            }
            Path baseDir = source.getParent();
            for (int i = 0; i < tcount; i++) {
                String raw = text(utf16);
                textures.add(resolveTexture(baseDir, raw));
            }

            // --- 材质 ---
            int mcount = i32();
            if (mcount < 0 || mcount > 4096) {
                throw new IOException("PMX 材质数异常 " + mcount + ": " + source.getFileName());
            }
            List<MeshModel.MeshMaterial> materials = new ArrayList<>(mcount);
            int faceOffset = 0;
            for (int i = 0; i < mcount; i++) {
                String mName = text(utf16);
                text(utf16);                     // name_en
                float[] diffuse = f32x(4);
                f32x(3);                         // specular
                f32();                           // shininess
                f32x(3);                         // ambient
                u8();                            // draw flags（双面位在渲染阶段不用，统一双面）
                f32x(4);                         // edge color
                f32();                           // edge size
                int texIndex = index(tIdx, true);
                index(tIdx, true);               // sphere texture
                u8();                            // sphere mode
                int sharedToon = u8();
                if (sharedToon == 1) {
                    u8();                        // 内置 toon 序号
                } else {
                    index(tIdx, true);           // 外部 toon 贴图
                }
                text(utf16);                     // memo
                int faceCount = i32();
                if (faceCount < 0 || faceOffset + faceCount > indices.length) {
                    throw new IOException("PMX 材质 " + i + " 面数越界: " + source.getFileName());
                }
                materials.add(new MeshModel.MeshMaterial(
                        mName,
                        texIndex >= 0 && texIndex < textures.size() ? texIndex : -1,
                        diffuse,
                        diffuse[3],
                        faceOffset,
                        faceCount));
                faceOffset += faceCount;
            }

            // --- 骨骼 ---
            int bcount = i32();
            if (bcount < 0 || bcount > 100_000) {
                throw new IOException("PMX 骨骼数异常 " + bcount + ": " + source.getFileName());
            }
            List<MeshModel.Bone> bones = new ArrayList<>(bcount);
            for (int i = 0; i < bcount; i++) {
                String bName = text(utf16);
                text(utf16);                     // name_en
                float[] bpos = f32x(3);
                int parent = index(bIdx, true);
                i32();                           // layer
                int flags = u16();

                if ((flags & BONE_TAIL_INDEX) != 0) {
                    index(bIdx, true);
                } else {
                    f32x(3);
                }

                // 回転付与与移動付与共享 parent 与 rate（PMX 规范）
                int grantParent = -1;
                float grantRate = 0.0F;
                if ((flags & (BONE_GRANT_ROT | BONE_GRANT_MOV)) != 0) {
                    grantParent = index(bIdx, true);
                    grantRate = f32();
                }
                int grantParentMove = -1;
                float grantWeightMove = 0.0F;
                if ((flags & BONE_GRANT_ROT) != 0) {
                    // grantWeight 在下面构造时用 grantRate
                } else {
                    grantRate = grantRate;       // 仅移动付与时旋转权重保持 0
                }
                if ((flags & BONE_GRANT_MOV) != 0) {
                    grantParentMove = grantParent;
                    grantWeightMove = grantRate;
                }

                if ((flags & BONE_FIXED_AXIS) != 0) {
                    f32x(3);
                }
                if ((flags & BONE_LOCAL_AXIS) != 0) {
                    f32x(3);
                    f32x(3);
                }
                if ((flags & BONE_EXTERNAL_PARENT) != 0) {
                    i32();
                }
                if ((flags & BONE_IK) != 0) {
                    index(bIdx, true);                       // ik target
                    i32();                                   // iterations
                    f32();                                   // limit angle
                    int links = i32();
                    if (links < 0 || links > 4096) {
                        throw new IOException("PMX IK link 数异常 " + links + ": " + source.getFileName());
                    }
                    for (int k = 0; k < links; k++) {
                        index(bIdx, true);
                        if (u8() != 0) {
                            f32x(3);
                            f32x(3);
                        }
                    }
                }

                float rotWeight = (flags & BONE_GRANT_ROT) != 0 ? grantRate : 0.0F;
                int rotParent = (flags & BONE_GRANT_ROT) != 0 ? grantParent : -1;
                bones.add(new MeshModel.Bone(
                        bName, parent, bpos,
                        rotParent, rotWeight,
                        grantParentMove, grantWeightMove));
            }

            return new MeshModel(name, nameEn,
                    positions, normals, uvs,
                    boneIndices, boneWeights, indices,
                    materials, bones, textures);
        }

        /**
         * 把 PMX 里记录的贴图相对路径解析成磁盘上的绝对路径。
         *
         * <p>MMD 贴图名常见问题：反斜杠分隔、大小写与磁盘不一致、带子目录。
         * 找不到时回退到「同目录按文件名忽略大小写再找一遍」。
         */
        private static String resolveTexture(Path baseDir, String raw) {
            if (raw == null || raw.isBlank() || baseDir == null) {
                return "";
            }
            String rel = raw.replace('\\', '/');
            Path direct = baseDir.resolve(rel).normalize();
            if (Files.isRegularFile(direct)) {
                return direct.toString();
            }
            // 只取文件名再找一次（忽略大小写）
            String fn = rel;
            int slash = fn.lastIndexOf('/');
            if (slash >= 0) {
                fn = fn.substring(slash + 1);
            }
            Path byName = baseDir.resolve(fn);
            if (Files.isRegularFile(byName)) {
                return byName.toString();
            }
            String lower = fn.toLowerCase(java.util.Locale.ROOT);
            try (var stream = Files.list(baseDir)) {
                return stream.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).equals(lower))
                        .map(Path::toString)
                        .findFirst()
                        .orElse("");
            } catch (IOException e) {
                return "";
            }
        }
    }
}
