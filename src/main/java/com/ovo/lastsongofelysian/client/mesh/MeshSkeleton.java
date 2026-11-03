package com.ovo.lastsongofelysian.client.mesh;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * 骨骼层级与蒙皮计算。
 *
 * <p>MMD 的骨骼是「位置 + 四元数旋转」构成的层级树；每个顶点带 1~4 组骨骼权重。
 * 蒙皮分两步：
 * <ol>
 *   <li>{@link #computeWorldMatrices} 自顶向下把局部变换累乘成世界矩阵</li>
 *   <li>{@link #skin} 用每顶点权重把世界矩阵混合，得到最终顶点位置与法线</li>
 * </ol>
 *
 * <p>性能提示：18k 顶点 × 每帧重算在 JVM 里偏重。当前实现按需调用，
 * 静止时结果缓存不重算；后续如果动画开销明显，再换 GPU 蒙皮或减少顶点数。
 */
public final class MeshSkeleton {

    private final MeshModel model;
    private final int boneCount;

    /** 每根骨骼的局部旋转（相对父骨骼），默认单位四元数。 */
    private final Quaternionf[] localRotations;
    /** 每根骨骼的局部位移偏移（相对绑定姿势），默认零。 */
    private final Vector3f[] localOffsets;

    /** 每根骨骼的「付与」结果（局部空间），避免每帧重复算。 */
    private final Quaternionf[] grantedRotations;
    private final Vector3f[] grantedOffsets;

    private final Matrix4f[] worldMatrices;
    /** 顶点蒙皮结果：pos 与 normal 各 3 × 顶点数。 */
    private final float[] skinnedPositions;
    private final float[] skinnedNormals;

    private boolean dirty = true;

    public MeshSkeleton(MeshModel model) {
        this.model = model;
        this.boneCount = model.bones.size();
        this.localRotations = new Quaternionf[boneCount];
        this.localOffsets = new Vector3f[boneCount];
        for (int i = 0; i < boneCount; i++) {
            this.localRotations[i] = new Quaternionf();
            this.localOffsets[i] = new Vector3f();
        }
        this.grantedRotations = new Quaternionf[boneCount];
        this.grantedOffsets = new Vector3f[boneCount];
        for (int i = 0; i < boneCount; i++) {
            this.grantedRotations[i] = new Quaternionf();
            this.grantedOffsets[i] = new Vector3f();
        }
        this.worldMatrices = new Matrix4f[boneCount];
        for (int i = 0; i < boneCount; i++) {
            this.worldMatrices[i] = new Matrix4f();
        }
        this.skinnedPositions = new float[model.positions.length];
        this.skinnedNormals = new float[model.normals.length];
    }

    public void setRotation(int bone, float x, float y, float z) {
        if (bone < 0 || bone >= boneCount) {
            return;
        }
        localRotations[bone].set(new Quaternionf().rotateXYZ(x, y, z));
        dirty = true;
    }

    public void setOffset(int bone, float x, float y, float z) {
        if (bone < 0 || bone >= boneCount) {
            return;
        }
        localOffsets[bone].set(x, y, z);
        dirty = true;
    }

    public void reset() {
        for (int i = 0; i < boneCount; i++) {
            localRotations[i].identity();
            localOffsets[i].zero();
            grantedRotations[i].identity();
            grantedOffsets[i].zero();
        }
        dirty = true;
    }

    /** 按名称查骨骼下标，找不到返回 -1。 */
    public int findBone(String name) {
        List<MeshModel.Bone> bones = model.bones;
        for (int i = 0; i < bones.size(); i++) {
            if (bones.get(i).name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 计算每个顶点蒙皮后的位置与法线。静止且无改动时直接复用上次结果。
     */
    public void update() {
        if (!dirty) {
            return;
        }
        applyGrants();
        computeWorldMatrices();
        skin();
        dirty = false;
    }

    /**
     * 处理「回転付与 / 移動付与」：把 grant_parent 的局部旋转/位移按权重混进本骨骼。
     *
     * <p>这是 MMD 骨架的关键机制——比如「腰キャンセル右」正是靠回転付与抵消腰部旋转，
     * 让腿在腰扭动时保持朝前。不实现这段，走路/转身时四肢会跟着躯干一起拧。
     */
    private void applyGrants() {
        List<MeshModel.Bone> bones = model.bones;
        for (int i = 0; i < boneCount; i++) {
            MeshModel.Bone bone = bones.get(i);

            int gp = bone.grantParent;
            float gw = bone.grantWeight;
            // 注意：权重范围是 -1 ~ 1。负权重表示「反向抵消」——MMD 里
            // 「腰キャンセル」「肩C」「目戻」这些骨骼就是 w=-1 / -0.7 的抵消骨骼，
            // 漏掉它们会导致腰一转腿就跟着扭。
            if (gp >= 0 && gp < boneCount && Math.abs(gw) > 0.001f) {
                Quaternionf target = gw < 0f
                        ? new Quaternionf(localRotations[gp]).conjugate()
                        : new Quaternionf(localRotations[gp]);
                Quaternionf mixed = new Quaternionf().identity().slerp(target, Math.min(1f, Math.abs(gw)));
                grantedRotations[i].set(localRotations[i]).mul(mixed);
            } else {
                grantedRotations[i].set(localRotations[i]);
            }

            int gpm = bone.grantParentMove;
            float gwm = bone.grantWeightMove;
            if (gpm >= 0 && gpm < boneCount && Math.abs(gwm) > 0.001f) {
                grantedOffsets[i].set(localOffsets[i]).fma(gwm, localOffsets[gpm]);
            } else {
                grantedOffsets[i].set(localOffsets[i]);
            }
        }
    }

    private void computeWorldMatrices() {
        List<MeshModel.Bone> bones = model.bones;
        for (int i = 0; i < boneCount; i++) {
            MeshModel.Bone bone = bones.get(i);
            Matrix4f m = worldMatrices[i].identity();
            // 绑定姿势的局部位置
            m.translate(bone.position[0] + grantedOffsets[i].x,
                    bone.position[1] + grantedOffsets[i].y,
                    bone.position[2] + grantedOffsets[i].z);
            m.rotate(grantedRotations[i]);
            // 抵消绑定姿势：把骨骼自己的位置减回去，让旋转绕关节而非原点
            m.translate(-bone.position[0], -bone.position[1], -bone.position[2]);

            // 自顶向下累乘：PMX 保证父骨骼下标小于子骨骼（实际不保证，因此做两遍保护）
            int parent = bone.parent;
            if (parent >= 0 && parent < boneCount && parent < i) {
                m.set(new Matrix4f(worldMatrices[parent]).mul(m));
            } else if (parent >= 0 && parent < boneCount) {
                // 父骨骼下标更大：直接递归求解（本模型的 494 根骨骼绝大多数满足前者）
                m.set(new Matrix4f(worldOf(parent)).mul(m));
            }
        }
    }

    private Matrix4f worldOf(int index) {
        MeshModel.Bone bone = model.bones.get(index);
        Matrix4f m = new Matrix4f();
        m.translate(bone.position[0] + grantedOffsets[index].x,
                bone.position[1] + grantedOffsets[index].y,
                bone.position[2] + grantedOffsets[index].z);
        m.rotate(grantedRotations[index]);
        m.translate(-bone.position[0], -bone.position[1], -bone.position[2]);
        int parent = bone.parent;
        if (parent >= 0 && parent < boneCount) {
            m.set(new Matrix4f(worldOf(parent)).mul(m));
        }
        return m;
    }

    private void skin() {
        float[] srcPos = model.positions;
        float[] srcNrm = model.normals;
        int[][] bidx = model.boneIndices;
        float[][] bwt = model.boneWeights;
        int n = model.vertexCount;

        Vector3f v = new Vector3f();
        Vector3f accPos = new Vector3f();
        Vector3f accNrm = new Vector3f();
        Vector3f tmp = new Vector3f();

        for (int i = 0; i < n; i++) {
            int p = i * 3;
            accPos.zero();
            accNrm.zero();
            float total = 0f;
            for (int k = 0; k < 4; k++) {
                int bi = bidx[i][k];
                float w = bwt[i][k];
                if (bi < 0 || bi >= boneCount || w <= 0f) {
                    continue;
                }
                Matrix4f m = worldMatrices[bi];
                v.set(srcPos[p], srcPos[p + 1], srcPos[p + 2]);
                m.transformPosition(v);
                accPos.fma(w, v);

                tmp.set(srcNrm[p], srcNrm[p + 1], srcNrm[p + 2]);
                m.transformDirection(tmp);
                accNrm.fma(w, tmp);
                total += w;
            }
            if (total <= 1e-6f) {
                // 没有有效骨骼权重：保持原样
                skinnedPositions[p] = srcPos[p];
                skinnedPositions[p + 1] = srcPos[p + 1];
                skinnedPositions[p + 2] = srcPos[p + 2];
                skinnedNormals[p] = srcNrm[p];
                skinnedNormals[p + 1] = srcNrm[p + 1];
                skinnedNormals[p + 2] = srcNrm[p + 2];
            } else {
                skinnedPositions[p] = accPos.x;
                skinnedPositions[p + 1] = accPos.y;
                skinnedPositions[p + 2] = accPos.z;
                if (accNrm.lengthSquared() > 1e-9f) {
                    accNrm.normalize();
                }
                skinnedNormals[p] = accNrm.x;
                skinnedNormals[p + 1] = accNrm.y;
                skinnedNormals[p + 2] = accNrm.z;
            }
        }
    }

    public float[] positions() {
        return skinnedPositions;
    }

    public float[] normals() {
        return skinnedNormals;
    }

    public int boneCount() {
        return boneCount;
    }
}
