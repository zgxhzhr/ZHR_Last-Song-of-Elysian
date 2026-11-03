# 第三方许可声明

本项目包含或参考了以下第三方开源作品。按各自许可证要求，版权与许可声明保留于此。

---

## MC-MMD-rust

- **仓库**：https://github.com/zgxhzhr/ZHR_MC-MMD-rust
- **作者**：shiroha (shiroha233)
- **许可证**：MIT
- **本项目使用范围**：
  `client/mesh/MeshVaoRenderer.java` 的渲染架构参考该项目的
  `render/backend/opengl/OpenGlModelRenderer`——用自建 VAO/VBO + 按材质
  `glDrawElements` 的方式绕开 Minecraft 的 `VertexConsumer` 管线。
  代码为针对本项目重写，非直接复制，但关键 API 调用序列来自该项目。
- **同时参考**：其 `render/material/SubMeshDrawHelper`（按材质控制双面/单面剔除）
  与 `render/backend/gpu/GpuSkinningModelRenderer`（GPU 蒙皮思路）。

### MIT License

```
MIT License

Copyright (c) 2026 shiroha

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## MMD 模型素材

本项目**不随模组分发**任何 MMD 模型文件或贴图。

`.pmx` 模型与贴图版权归 **miHoYo** 所有，由模型改造者（如「神帝宇」）制作，
使用条款见各模型包内的「使用规则.txt」——允许改造，**禁止二次配布与商业用途**。

因此模型资源需玩家自行获取并放置，模组只提供渲染器。
