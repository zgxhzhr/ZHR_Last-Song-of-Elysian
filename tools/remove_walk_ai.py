# -*- coding: utf-8 -*-
"""删掉英桀 NPC 与帕朵菲莉丝商人的行走 AI，保留交互（商店 / 升级）。

交互与 AI 是两套独立机制：
  - 商店 / 螺旋工坊升级  → mobInteract() 里 openScreen，与 goalSelector 无关
  - 走路                 → WaterAvoidingRandomStrollGoal（本次删除的对象）

FloatGoal 保留：它只在液体里生效，作用是防止 NPC 掉水里沉底淹死，
不会产生任何水平移动，也不影响「站着不动」的表现。
"""
import io
import os

J = r"E:\Mod\其它项目\ZHR_Last-Song-of-Elysian\src\main\java\com\ovo\lastsongofelysian"


def patch(rel, marker_names):
    p = os.path.join(J, rel)
    c = io.open(p, encoding="utf-8").read()
    before = c

    # 删掉所有 addGoal(...) 里的「行走 / 随机游走」以及遗留的注视类 goal
    lines = c.split("\n")
    out = []
    removed = 0
    for ln in lines:
        s = ln.strip()
        if s.startswith("this.goalSelector.addGoal") and any(k in s for k in marker_names):
            removed += 1
            continue
        out.append(ln)
    c = "\n".join(out)

    # 清理不再使用的 import
    for imp in ("import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;\n",
                "import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;\n",
                "import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;\n"):
        c = c.replace(imp, "")

    io.open(p, "w", encoding="utf-8", newline="\n").write(c)
    print("  ✓ %-40s 删除 %d 个移动 goal" % (os.path.basename(rel), removed))
    return c


MOVE_KEYS = ("WaterAvoidingRandomStrollGoal", "LookAtPlayerGoal", "RandomLookAroundGoal")

# ---------- 英桀 NPC ----------
c1 = patch(r"entity\ElysianHeroNpcEntity.java", MOVE_KEYS)
old1 = """    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 刻意不加 LookAtPlayerGoal / RandomLookAroundGoal：
        // 这两个 goal 会把**身体 yaw** 拧向玩家或随机方向，而模型朝向直接取自
        // entity.getYRot()——身体已经自动对着玩家时，HeroAnimator 算出的
        // 「头相对身体的偏角」恒为 0，眼部追踪就完全看不出来。
        // 看向玩家的表现交给 HeroAnimator 的头部+眼球骨骼（有角度上限，超范围不追），
        // 身体只在走位时自然转向。
    }"""
new1 = """    @Override
    protected void registerGoals() {
        // 英桀是固定的立绘式 NPC，不允许走动。
        //
        // 刻意只留 FloatGoal（进水时上浮，防止沉底淹死），不放任何会改变位置的 goal：
        //   - WaterAvoidingRandomStrollGoal → 会到处乱走
        //   - LookAtPlayerGoal / RandomLookAroundGoal → 会拧身体 yaw，
        //     而模型朝向直接取自 entity.getYRot()，身体一旦自动对着玩家，
        //     HeroAnimator 算出的头部偏角恒为 0，眼部追踪就看不出来了。
        // 看向玩家的表现完全交给 HeroAnimator 的头部+眼球骨骼。
        //
        // 注意：交互（聊天/右键）走的是 mobInteract()，与这里无关，
        // 所以维尔薇的螺旋工坊升级、帕朵菲莉丝的商店都不受影响。
        this.goalSelector.addGoal(0, new FloatGoal(this));
    }"""
if old1 in c1:
    c1 = c1.replace(old1, new1, 1)
    print("  ✓ 英桀 registerGoals 重写")
else:
    print("  !! 英桀 registerGoals 锚点未命中")

old1b = """    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 刻意不加"""
if old1b in c1:
    # 兜底：直接替换整个方法体（保留注释行）
    start = c1.find("    protected void registerGoals() {")
    end = c1.find("    }", start) + len("    }")
    c1 = c1[:start] + new1.strip(" ") + c1[end:]
    print("  ✓ 英桀 registerGoals 兜底替换")

io.open(os.path.join(J, r"entity\ElysianHeroNpcEntity.java"), "w", encoding="utf-8", newline="\n").write(c1)

# ---------- 帕朵菲莉丝商人 ----------
c2 = patch(r"entity\PardofelisMerchantEntity.java", MOVE_KEYS)
start = c2.find("    protected void registerGoals() {")
if start >= 0:
    end = c2.find("\n    }", start) + len("\n    }")
    new2 = """    protected void registerGoals() {
        // 与英桀 NPC 一致：站桩，不走路。
        // 商店交互在 mobInteract() 里（PardofelisShopMenu），与 AI goal 无关，不受影响。
        this.goalSelector.addGoal(0, new FloatGoal(this));
    }"""
    c2 = c2[:start] + new2 + c2[end:]
    print("  ✓ 商人 registerGoals 重写")
else:
    print("  !! 商人 registerGoals 未找到")

io.open(os.path.join(J, r"entity\PardofelisMerchantEntity.java"), "w", encoding="utf-8", newline="\n").write(c2)

print("完成")
