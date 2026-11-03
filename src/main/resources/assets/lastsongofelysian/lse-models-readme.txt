乐土终曲 —— MMD 模型放置说明
================================

本模组**不包含任何 MMD 模型文件**。

MMD 模型（.pmx 与贴图）的版权归 miHoYo 所有，由模型改造者制作，
其使用条款普遍禁止二次配布。因此模组只提供 PMX 解析与渲染代码，
模型需要你自己放置。

放置位置
--------
游戏目录下的 lse-models 文件夹（与 mods 同级）：

    <游戏目录>/lse-models/<变体名>/model.pmx
    <游戏目录>/lse-models/<变体名>/<贴图>.png 等

变体名（小写）：
    mobius      梅比乌斯
    aponia      阿波尼亚
    elysia      爱莉希雅
    griseo      格蕾修
    vill_v      维尔薇
    pardofelis  帕朵菲莉丝

说明
----
* 文件名不必是 model.pmx。目录里没有 model.pmx 时，模组会取该目录下
  **体积最大的 .pmx**，并去找它引用的贴图（同目录，忽略大小写）。
* 贴图格式支持 PNG / JPG / BMP / TGA 等常见格式。
* 一个目录里最好只放一个角色的模型。连带体/道具等额外 .pmx 放在同目录
  也可以，只要主模型体积更大即可。
* 首次加载会在日志里打印顶点数、材质数、骨骼数与耗时，用于确认读取成功。

许可
----
渲染架构参考 MC-MMD-rust (MIT, Copyright (c) 2026 shiroha)，
详见 jar 内 THIRD_PARTY_LICENSES.md。
