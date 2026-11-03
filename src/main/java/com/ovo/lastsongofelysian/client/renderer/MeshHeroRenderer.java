package com.ovo.lastsongofelysian.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.ovo.lastsongofelysian.client.mesh.MeshModel;
import com.ovo.lastsongofelysian.client.mesh.HeroAnimator;
import com.ovo.lastsongofelysian.client.mesh.MeshDebug;
import com.ovo.lastsongofelysian.client.mesh.ExternalModelRepository;
import com.ovo.lastsongofelysian.client.mesh.MeshSkeleton;
import com.ovo.lastsongofelysian.client.mesh.MeshVaoRenderer;
import com.ovo.lastsongofelysian.entity.ElysianHeroNpcEntity;
import com.ovo.lastsongofelysian.lastsongofelysian;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * 三角网格版英桀渲染器。
 *
 * <p>与原来的 {@code HumanoidMobRenderer} 不同，这里完全不使用 {@code EntityModel} / {@code ModelPart}：
 * 直接从 {@link MeshModel} 取顶点，经 {@link MeshSkeleton} 蒙皮后按材质切 batch，
 * 用 {@link VertexConsumer} 画三角形。
 *
 * <p>骨骼姿势按实体缓存在 {@link WeakHashMap} 里，实体被回收后自动释放。
 */
public class MeshHeroRenderer<T extends LivingEntity> extends EntityRenderer<T> {

    /** MMD 单位 → MC 方块。梅比乌斯约 21 MMD 单位高，MC 人形约 1.8 格。 */
    private static final float MMD_TO_MC = 1.8F / 21.0F;

    /** 脚底在 MMD 里略低于 0（实测 -0.50），抬起来贴合地面。 */
    private static final float GROUND_OFFSET = 0.50F;

    /** 纯白不透明贴图，只给调试用的「全白」模式。用 elysia.png 当兜底会被它的
     *  透明像素按 alpha 测试丢掉一半，看起来像噪点，误导判断。 */
    private static final ResourceLocation DEBUG_WHITE =
            new ResourceLocation(lastsongofelysian.MODID, "textures/debug_white.png");

    private static final ResourceLocation FALLBACK_TEXTURE =
            new ResourceLocation(lastsongofelysian.MODID, "textures/entity/elysian_heroes/elysia.png");

    private final Map<String, MeshVaoRenderer> vaoCache = new HashMap<>();
    private final Map<T, MeshSkeleton> skeletons = new WeakHashMap<>();
    /** 实体 → 模型变体名（即 lse-models 下的目录名）。 */
    private final Function<T, String> variantResolver;

    public MeshHeroRenderer(EntityRendererProvider.Context context, Function<T, String> variantResolver) {
        super(context);
        this.variantResolver = variantResolver;
        this.shadowRadius = 0.5F;
    }

    /** 英桀 NPC：变体名取自实体的 {@code HeroVariant}。 */
    public static EntityRendererProvider<ElysianHeroNpcEntity> heroProvider() {
        return ctx -> new MeshHeroRenderer<>(ctx,
                e -> e.getVariant().name().toLowerCase(Locale.ROOT));
    }

    /**
     * 固定变体：实体类型本身不携带变体信息时用（例如帕朵菲莉丝商人实体）。
     */
    public static <E extends LivingEntity> EntityRendererProvider<E> fixedProvider(String variant) {
        return ctx -> new MeshHeroRenderer<>(ctx, e -> variant);
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        String key = variantResolver.apply(entity);
        ExternalModelRepository.Loaded loaded = ExternalModelRepository.get(key);
        if (loaded == null) {
            return;
        }
        MeshModel model = loaded.model();

        MeshSkeleton skeleton = skeletonFor(entity, model, key);
        // 目前只做一个轻微的待机摆动，用来验证蒙皮链路；后续替换成 VMD 动画
        HeroAnimator.pose(skeleton, entity, partialTick);
        skeleton.update();

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - entityYaw));
        poseStack.scale(MMD_TO_MC, MMD_TO_MC, MMD_TO_MC);
        poseStack.translate(0.0F, GROUND_OFFSET, 0.0F);

        MeshDebug.Mode mode = MeshDebug.mode();
        switch (mode) {
            case WHITE -> drawWhite(model, skeleton, poseStack, buffer);
            case WIREFRAME -> drawWireframe(model, skeleton, poseStack, buffer);
            case NORMALS -> drawNormals(model, skeleton, poseStack, buffer);
            default -> drawVao(key, loaded, skeleton, poseStack);
        }

        poseStack.popPose();

        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }


    /** 用 VAO/VBO 直接绘制，绕开 MC 的 VertexConsumer 管线。 */
    private void drawVao(String key, ExternalModelRepository.Loaded loaded,
                         MeshSkeleton skeleton, PoseStack poseStack) {
        MeshVaoRenderer vao = vaoCache.computeIfAbsent(key, k -> new MeshVaoRenderer());
        int[] texIds = loaded.textureIds();
        vao.draw(loaded.model(), skeleton, poseStack,
                texIndex -> texIndex >= 0 && texIndex < texIds.length ? texIds[texIndex] : -1);
    }

    /** 全白：不查贴图、只用顶点色，用来判断噪点是否来自贴图/UV。 */
    private void drawWhite(MeshModel model, MeshSkeleton skeleton, PoseStack poseStack, MultiBufferSource buffer) {
        Matrix4f pose = poseStack.last().pose();
        float[] pos = skeleton.positions();
        float[] nrm = skeleton.normals();
        float[] uv = model.uvs;
        int[] idx = model.indices;
        // 注意：这里必须用固定 UV，否则会采样兜底贴图的图案，看到的就不是"纯色"了。
        // 之前用真实 UV 导致全白模式显示成 elysia 贴图的紫粉花纹，误导了判断。
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(DEBUG_WHITE));
        for (int i = 0; i + 2 < idx.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                int vi = idx[i + k];
                int p = vi * 3;
                vc.vertex(pose, pos[p], pos[p + 1], pos[p + 2])
                        .color(1.0F, 1.0F, 1.0F, 1.0F)
                        .uv(0.5F, 0.5F)
                        .overlayCoords(OverlayTexture.NO_OVERLAY)
                        .uv2(LightTexture.FULL_BRIGHT)
                        .normal(nrm[p], nrm[p + 1], nrm[p + 2])
                        .endVertex();
            }
        }
    }

    /** 线框：每个三角形画三条边，看连接关系与碎裂位置。 */
    private void drawWireframe(MeshModel model, MeshSkeleton skeleton, PoseStack poseStack, MultiBufferSource buffer) {
        Matrix4f pose = poseStack.last().pose();
        float[] pos = skeleton.positions();
        int[] idx = model.indices;
        VertexConsumer vc = buffer.getBuffer(RenderType.lines());
        for (int i = 0; i + 2 < idx.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                int a = idx[i + k] * 3;
                int b = idx[i + (k + 1) % 3] * 3;
                line(vc, pose, pos[a], pos[a + 1], pos[a + 2], pos[b], pos[b + 1], pos[b + 2]);
            }
        }
    }

    /** 法线：每隔 24 个顶点画一条短法线，检查朝向是否一致。 */
    private void drawNormals(MeshModel model, MeshSkeleton skeleton, PoseStack poseStack, MultiBufferSource buffer) {
        Matrix4f pose = poseStack.last().pose();
        float[] pos = skeleton.positions();
        float[] nrm = skeleton.normals();
        VertexConsumer vc = buffer.getBuffer(RenderType.lines());
        float len = 0.5F;
        for (int vi = 0; vi < model.vertexCount; vi += 24) {
            int p = vi * 3;
            line(vc, pose,
                    pos[p], pos[p + 1], pos[p + 2],
                    pos[p] + nrm[p] * len, pos[p + 1] + nrm[p + 1] * len, pos[p + 2] + nrm[p + 2] * len);
        }
    }

    private static void line(VertexConsumer vc, Matrix4f pose,
                             float x1, float y1, float z1, float x2, float y2, float z2) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-6F) {
            return;
        }
        float nx = dx / l, ny = dy / l, nz = dz / l;
        vc.vertex(pose, x1, y1, z1).color(255, 200, 60, 255).normal(nx, ny, nz).endVertex();
        vc.vertex(pose, x2, y2, z2).color(255, 200, 60, 255).normal(nx, ny, nz).endVertex();
    }

    private void drawMesh(MeshModel model, Map<Integer, ResourceLocation> texMap,
                          MeshSkeleton skeleton, PoseStack poseStack,
                          MultiBufferSource buffer, int packedLight) {
        Matrix4f pose = poseStack.last().pose();
        float[] pos = skeleton.positions();
        float[] nrm = skeleton.normals();
        float[] uv = model.uvs;
        int[] idx = model.indices;

        int materialIndex = 0;
        for (MeshModel.MeshMaterial mat : model.materials) {
            if (mat.faceVertexCount <= 0) {
                materialIndex++;
                continue;
            }
            ResourceLocation tex = texMap != null && mat.textureIndex >= 0
                    ? texMap.getOrDefault(mat.textureIndex, FALLBACK_TEXTURE)
                    : FALLBACK_TEXTURE;

            // MMD 模型是单面建模（靠材质顺序做正反面），必须用 NoCull 双面渲染。
            // entitySolid 会开背面剔除，把模型一半三角形剔掉，看起来就是碎片。
            //
            // 「表情」贴图（tex_8）87.6% 是全透明、3.6% 半透明，属于叠在脸上的
            // 半透明图层。用 cutout 会把这些像素按 alpha<0.1 直接丢弃，剩下的
            // 半透明边缘和下面的脸共面打架 → 脸上出现噪点。改用 translucent
            // 让它正常混合。
            // 全部用半透明混合：不写深度，彻底绕开共面三角形的深度冲突。
            // 代价是 MC 按距离排序透明批次，单实体内部可能穿模，但对多层叠合的
            // MMD 模型来说，不写深度比深度打架更不容易出现碎块。
            RenderType renderType = RenderType.entityTranslucent(tex);

            // MMD 的层叠材质（脸/眉毛/眼白/眼睛/表情、衣服多层）三角形几乎共面，
            // 写深度会 Z-fighting 成噪点。按材质顺序给一点深度偏移把它们分开。
            // 矩阵必须复制：MultiBufferSource 是延迟绘制，popPose 之后原矩阵会被复用。
            poseStack.pushPose();
            poseStack.translate(0.0F, 0.0F, 0.004F * materialIndex);
            Matrix4f layerPose = new Matrix4f(poseStack.last().pose());

            VertexConsumer vc = buffer.getBuffer(renderType);

            float r = clamp01(mat.diffuse[0]);
            float g = clamp01(mat.diffuse[1]);
            float b = clamp01(mat.diffuse[2]);
            float a = clamp01(mat.alpha);

            int start = mat.faceVertexOffset;
            int end = Math.min(start + mat.faceVertexCount, idx.length);
            for (int i = start; i + 2 < end; i += 3) {
                emitVertex(vc, layerPose, idx[i], pos, nrm, uv, r, g, b, a, packedLight);
                emitVertex(vc, layerPose, idx[i + 1], pos, nrm, uv, r, g, b, a, packedLight);
                emitVertex(vc, layerPose, idx[i + 2], pos, nrm, uv, r, g, b, a, packedLight);
            }
            poseStack.popPose();
            materialIndex++;
        }
    }

    private static void emitVertex(VertexConsumer vc, Matrix4f pose, int vi,
                                   float[] pos, float[] nrm, float[] uv,
                                   float r, float g, float b, float a, int light) {
        int p = vi * 3;
        int t = vi * 2;
        vc.vertex(pose, pos[p], pos[p + 1], pos[p + 2])
                .color(r, g, b, a)
                .uv(uv[t], uv[t + 1])
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(nrm[p], nrm[p + 1], nrm[p + 2])
                .endVertex();
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private MeshSkeleton skeletonFor(T entity, MeshModel model, String key) {
        MeshSkeleton skeleton = skeletons.get(entity);
        if (skeleton == null) {
            skeleton = new MeshSkeleton(model);
            skeletons.put(entity, skeleton);
        }
        return skeleton;
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return FALLBACK_TEXTURE;
    }
}
