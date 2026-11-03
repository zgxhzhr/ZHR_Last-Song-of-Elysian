/*
 * ============================================================================
 * 本文件包含改编自第三方开源项目的代码/架构
 * ============================================================================
 *
 *   MC-MMD-rust
 *   Copyright (c) 2026 shiroha
 *   https://github.com/zgxhzhr/ZHR_MC-MMD-rust
 *   MIT License
 *
 * VAO/VBO 渲染架构（自建 VAO、按材质 glDrawElements、复用 RenderSystem
 * 的 MC 主 shader）参考自该项目的 render/backend/opengl/OpenGlModelRenderer。
 * 代码为针对「乐土终曲」重写，非直接复制，但关键 API 调用序列来自该项目。
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * 许可证全文见项目根目录 THIRD_PARTY_LICENSES.md。
 * ============================================================================
 */
package com.ovo.lastsongofelysian.client.mesh;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * 直接用 VAO/VBO 绘制三角网格，绕开 {@code VertexConsumer} / {@code MultiBufferSource}。
 *
 * <p>为什么要绕开：MC 的顶点管线是为方块几何设计的——{@code MultiBufferSource} 会按
 * RenderType 合并批次并改变绘制顺序，{@code RenderType} 自带的 shader 也带着 MC 自己的
 * 假设。对 MMD 这种「上万三角形 + 23 个层叠材质」的模型，那条路会产出碎块（实测过 10 种
 * RenderType / 深度 / 采样组合都无效）。
 *
 * <p>这套做法参考了开源的 MC-MMD-rust（MIT）：自己建 VAO，按材质 {@code glDrawElements}，
 * 复用 {@code RenderSystem.getShader()} 的 MC 主 shader。
 */
public final class MeshVaoRenderer {

    private static final String[] ATTR_POS = {"Position", "position", "I_Position"};
    private static final String[] ATTR_NRM = {"Normal", "normal", "I_Normal"};
    private static final String[] ATTR_UV0 = {"UV0", "uv0", "I_UV0", "Texture"};
    private static final String[] ATTR_COLOR = {"Color", "color", "I_Color"};
    private static final String[] ATTR_UV2 = {"UV2", "uv2", "I_UV2", "Light"};

    private int vao = -1;
    private int posVbo = -1;
    private int nrmVbo = -1;
    private int uvVbo = -1;
    private int ibo = -1;
    private int indexCount;

    private int locPos = -1;
    private int locNrm = -1;
    private int locUv0 = -1;
    private int locColor = -1;
    private int locUv2 = -1;
    private int locProgram = -1;
    private int locModelView = -1;
    private int locProj = -1;
    private final FloatBuffer matrixBuf = MemoryUtil.memAllocFloat(16);

    private FloatBuffer posBuf;
    private FloatBuffer nrmBuf;
    private FloatBuffer uvBuf;
    private java.nio.IntBuffer uv2Buf;

    private boolean initialized;

    /** 首次使用前必须调用一次（需要 GL 上下文，必须在渲染线程）。 */
    public void init(MeshModel model) {
        if (initialized) {
            return;
        }
        indexCount = model.indices.length;

        vao = GL46C.glGenVertexArrays();
        GL46C.glBindVertexArray(vao);

        posVbo = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, posVbo);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,
                (long) model.vertexCount * 3 * Float.BYTES, GL46C.GL_DYNAMIC_DRAW);

        nrmVbo = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, nrmVbo);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,
                (long) model.vertexCount * 3 * Float.BYTES, GL46C.GL_DYNAMIC_DRAW);

        uvVbo = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, uvVbo);
        GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER,
                (long) model.vertexCount * 2 * Float.BYTES, GL46C.GL_STATIC_DRAW);
        // UV 是静态的，一次上传
        uvBuf = MemoryUtil.memAllocFloat(model.vertexCount * 2);
        uvBuf.put(model.uvs).flip();
        GL46C.glBufferSubData(GL46C.GL_ARRAY_BUFFER, 0, uvBuf);

        ibo = GL46C.glGenBuffers();
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ibo);
        java.nio.IntBuffer idxBuf = MemoryUtil.memAllocInt(indexCount);
        idxBuf.put(model.indices).flip();
        GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, idxBuf, GL46C.GL_STATIC_DRAW);
        MemoryUtil.memFree(idxBuf);

        // 顶点光照坐标：全亮
        uv2Buf = MemoryUtil.memAllocInt(model.vertexCount * 2);
        for (int i = 0; i < model.vertexCount; i++) {
            uv2Buf.put(0x00F000F0);
            uv2Buf.put(0x00F000F0);
        }
        uv2Buf.flip();

        posBuf = MemoryUtil.memAllocFloat(model.vertexCount * 3);
        nrmBuf = MemoryUtil.memAllocFloat(model.vertexCount * 3);

        GL46C.glBindVertexArray(0);
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, 0);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, 0);

        initialized = true;
    }

    /**
     * 绘制整个模型。顶点来自 CPU 蒙皮结果。
     *
     * @param texResolver 材质下标 → 贴图 GL id
     */
    public void draw(MeshModel model, MeshSkeleton skeleton, PoseStack poseStack,
                     java.util.function.IntUnaryOperator texResolver) {
        if (!initialized) {
            init(model);
        }
        Minecraft mc = Minecraft.getInstance();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) {
            return;
        }

        int program = shader.getId();
        if (program != locProgram) {
            locProgram = program;
            locPos = attrib(program, ATTR_POS);
            locNrm = attrib(program, ATTR_NRM);
            locUv0 = attrib(program, ATTR_UV0);
            locColor = attrib(program, ATTR_COLOR);
            locUv2 = attrib(program, ATTR_UV2);
            locModelView = GL46C.glGetUniformLocation(program, "ModelViewMat");
            if (locModelView == -1) {
                locModelView = GL46C.glGetUniformLocation(program, "modelViewMatrix");
            }
            locProj = GL46C.glGetUniformLocation(program, "ProjMat");
            if (locProj == -1) {
                locProj = GL46C.glGetUniformLocation(program, "projectionMatrix");
            }
        }

        // 上传蒙皮后的位置与法线
        posBuf.clear();
        posBuf.put(skeleton.positions()).flip();
        nrmBuf.clear();
        nrmBuf.put(skeleton.normals()).flip();

        GL46C.glBindVertexArray(vao);

        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, posVbo);
        GL46C.glBufferSubData(GL46C.GL_ARRAY_BUFFER, 0, posBuf);
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, nrmVbo);
        GL46C.glBufferSubData(GL46C.GL_ARRAY_BUFFER, 0, nrmBuf);

        shader.apply();

        // MC 的主 shader 只带相机矩阵，实体的 PoseStack 变换要自己乘进去，
        // 否则模型会画在相机原点（参考 MC-MMD-rust 的 uploadMatrixUniforms 做法）。
        if (locModelView != -1) {
            matrixBuf.clear();
            new Matrix4f(RenderSystem.getModelViewMatrix())
                    .mul(poseStack.last().pose())
                    .get(matrixBuf);
            GL46C.glUniformMatrix4fv(locModelView, false, matrixBuf);
        }
        if (locProj != -1) {
            matrixBuf.clear();
            RenderSystem.getProjectionMatrix().get(matrixBuf);
            GL46C.glUniformMatrix4fv(locProj, false, matrixBuf);
        }

        if (locPos != -1) {
            GL46C.glEnableVertexAttribArray(locPos);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, posVbo);
            GL46C.glVertexAttribPointer(locPos, 3, GL46C.GL_FLOAT, false, 0, 0);
        }
        if (locNrm != -1) {
            GL46C.glEnableVertexAttribArray(locNrm);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, nrmVbo);
            GL46C.glVertexAttribPointer(locNrm, 3, GL46C.GL_FLOAT, false, 0, 0);
        }
        if (locUv0 != -1) {
            GL46C.glEnableVertexAttribArray(locUv0);
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, uvVbo);
            GL46C.glVertexAttribPointer(locUv0, 2, GL46C.GL_FLOAT, false, 0, 0);
        }
        if (locColor != -1) {
            // 没有单独的颜色 VBO，用常量属性代替
            GL46C.glDisableVertexAttribArray(locColor);
            GL46C.glVertexAttrib4f(locColor, 1f, 1f, 1f, 1f);
        }
        if (locUv2 != -1) {
            GL46C.glDisableVertexAttribArray(locUv2);
            GL46C.glVertexAttribI2i(locUv2, 0x00F000F0, 0x00F000F0);
        }

        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, ibo);

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.blendEquation(GL46C.GL_FUNC_ADD);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.activeTexture(GL46C.GL_TEXTURE0);

        int lastTex = -1;
        for (MeshModel.MeshMaterial mat : model.materials) {
            if (mat.faceVertexCount <= 0) {
                continue;
            }
            int texId = texResolver.applyAsInt(mat.textureIndex);
            // texId <= 0 表示这个材质槽没有贴图，保持上一次绑定即可（PMX 里极少见）
            if (texId > 0 && texId != lastTex) {
                GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texId);
                lastTex = texId;
            }
            GL46C.glDrawElements(GL46C.GL_TRIANGLES, mat.faceVertexCount,
                    GL46C.GL_UNSIGNED_INT, (long) mat.faceVertexOffset * Integer.BYTES);
        }

        // 还原状态
        if (locPos != -1) GL46C.glDisableVertexAttribArray(locPos);
        if (locNrm != -1) GL46C.glDisableVertexAttribArray(locNrm);
        if (locUv0 != -1) GL46C.glDisableVertexAttribArray(locUv0);
        GL46C.glBindVertexArray(0);
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, 0);
        GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, 0);
        shader.clear();
        BufferUploader.reset();
    }

    private static int attrib(int program, String[] candidates) {
        for (String n : candidates) {
            int loc = GL46C.glGetAttribLocation(program, n);
            if (loc != -1) {
                return loc;
            }
        }
        return -1;
    }
}
