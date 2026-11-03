package com.ovo.lastsongofelysian.client.mesh;

import java.util.List;

/**
 * 从 {@code .mesh.json}（由 tools/pmx2mesh/pmx2mesh.py 生成）反序列化出来的模型数据。
 *
 * <p>数据布局刻意用扁平数组，避免 18188 个顶点 × 每顶点一个对象带来的 GC 压力：
 * <ul>
 *   <li>{@code positions}：3 × 顶点数，单位是 MMD 单位（人物约 21 高）</li>
 *   <li>{@code uvs}：2 × 顶点数，V 轴已在转换阶段翻转好</li>
 *   <li>{@code boneIndices} / {@code boneWeights}：每顶点最多 4 组，未使用的位置填 -1 / 0</li>
 *   <li>{@code indices}：三角形顶点索引</li>
 * </ul>
 */
public final class MeshModel {

    public final String name;
    public final String nameEn;

    public final float[] positions;
    public final float[] normals;
    public final float[] uvs;
    public final int[][] boneIndices;
    public final float[][] boneWeights;
    public final int[] indices;

    public final List<MeshMaterial> materials;
    public final List<Bone> bones;
    public final List<String> textures;

    public final int vertexCount;
    public final int triangleCount;

    /** 只有一个三角形、一张贴图、一个材质范围的绘制批次。 */
    public static final class MeshMaterial {
        public final String name;
        /** textures 列表里的下标，-1 表示无贴图。 */
        public final int textureIndex;
        public final float[] diffuse;
        public final float alpha;
        public final int faceVertexOffset;
        public final int faceVertexCount;

        public MeshMaterial(String name, int textureIndex, float[] diffuse, float alpha,
                            int faceVertexOffset, int faceVertexCount) {
            this.name = name;
            this.textureIndex = textureIndex;
            this.diffuse = diffuse;
            this.alpha = alpha;
            this.faceVertexOffset = faceVertexOffset;
            this.faceVertexCount = faceVertexCount;
        }
    }

    public static final class Bone {
        public final String name;
        public final int parent;
        public final float[] position;

        /** 回転付与：把 grantParent 的局部旋转按 grantWeight 混进来（-1 表示无）。 */
        public final int grantParent;
        public final float grantWeight;
        /** 移動付与：把 grantParentMove 的局部位移按 grantWeightMove 混进来（-1 表示无）。 */
        public final int grantParentMove;
        public final float grantWeightMove;

        public Bone(String name, int parent, float[] position,
                    int grantParent, float grantWeight,
                    int grantParentMove, float grantWeightMove) {
            this.name = name;
            this.parent = parent;
            this.position = position;
            this.grantParent = grantParent;
            this.grantWeight = grantWeight;
            this.grantParentMove = grantParentMove;
            this.grantWeightMove = grantWeightMove;
        }
    }

    public MeshModel(String name, String nameEn,
                     float[] positions, float[] normals, float[] uvs,
                     int[][] boneIndices, float[][] boneWeights, int[] indices,
                     List<MeshMaterial> materials, List<Bone> bones, List<String> textures) {
        this.name = name;
        this.nameEn = nameEn;
        this.positions = positions;
        this.normals = normals;
        this.uvs = uvs;
        this.boneIndices = boneIndices;
        this.boneWeights = boneWeights;
        this.indices = indices;
        this.materials = materials;
        this.bones = bones;
        this.textures = textures;
        this.vertexCount = positions.length / 3;
        this.triangleCount = indices.length / 3;
    }
}
