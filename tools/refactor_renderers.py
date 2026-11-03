# -*- coding: utf-8 -*-
"""把网格渲染器泛化成「任意 LivingEntity + 指定变体」，并让帕朵菲莉丝商人实体用上它。"""
import io
import os
import json

ROOT = r"E:\Mod\其它项目\ZHR_Last-Song-of-Elysian"
J = os.path.join(ROOT, "src", "main", "java", "com", "ovo", "lastsongofelysian")


def rw(rel, fn, label):
    p = os.path.join(J, rel)
    c = io.open(p, encoding="utf-8").read()
    n = fn(c)
    if n == c:
        print("  !! %s 无变化" % label)
    else:
        io.open(p, "w", encoding="utf-8", newline="\n").write(n)
        print("  ✓ %s" % label)


# ---------------- 1) MeshHeroRenderer 泛型化 ----------------
def f_renderer(c):
    c = c.replace(
        "import net.minecraft.resources.ResourceLocation;",
        "import net.minecraft.resources.ResourceLocation;\n"
        "import net.minecraft.world.entity.LivingEntity;", 1)
    c = c.replace(
        "import java.util.Map;\nimport java.util.WeakHashMap;",
        "import java.util.Map;\nimport java.util.WeakHashMap;\nimport java.util.function.Function;", 1)

    c = c.replace(
        "public class MeshHeroRenderer extends EntityRenderer<ElysianHeroNpcEntity> {",
        "public class MeshHeroRenderer<T extends LivingEntity> extends EntityRenderer<T> {", 1)

    c = c.replace(
        "    private final Map<ElysianHeroNpcEntity, MeshSkeleton> skeletons = new WeakHashMap<>();",
        "    private final Map<T, MeshSkeleton> skeletons = new WeakHashMap<>();\n"
        "    /** 实体 → 模型变体名（即 lse-models 下的目录名）。 */\n"
        "    private final Function<T, String> variantResolver;", 1)

    c = c.replace(
        """    public MeshHeroRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.5F;
    }""",
        """    public MeshHeroRenderer(EntityRendererProvider.Context context, Function<T, String> variantResolver) {
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
    }""", 1)

    c = c.replace(
        """    public void render(ElysianHeroNpcEntity entity, float entityYaw, float partialTick,""",
        """    public void render(T entity, float entityYaw, float partialTick,""", 1)

    c = c.replace("        String key = key(entity.getVariant());",
                  "        String key = variantResolver.apply(entity);", 1)

    c = c.replace(
        "    private MeshSkeleton skeletonFor(ElysianHeroNpcEntity entity, MeshModel model, String key) {",
        "    private MeshSkeleton skeletonFor(T entity, MeshModel model, String key) {", 1)

    c = c.replace(
        """    private static String key(ElysianHeroNpcEntity.HeroVariant variant) {
        return variant.name().toLowerCase(Locale.ROOT);
    }

""", "", 1)

    c = c.replace(
        "    public ResourceLocation getTextureLocation(ElysianHeroNpcEntity entity) {",
        "    public ResourceLocation getTextureLocation(T entity) {", 1)
    return c


rw(r"client\renderer\MeshHeroRenderer.java", f_renderer, "MeshHeroRenderer 泛型化")


# ---------------- 2) HeroAnimator 接受任意 LivingEntity ----------------
def f_anim(c):
    c = c.replace("import com.ovo.lastsongofelysian.entity.ElysianHeroNpcEntity;\n", "")
    c = c.replace(
        "public static void pose(MeshSkeleton skeleton, ElysianHeroNpcEntity entity, float partialTick) {",
        "public static void pose(MeshSkeleton skeleton, LivingEntity entity, float partialTick) {", 1)
    c = c.replace(
        "    private static float smooth(float target, ElysianHeroNpcEntity entity, int slot) {",
        "    private static float smooth(float target, LivingEntity entity, int slot) {", 1)
    return c


rw(r"client\mesh\HeroAnimator.java", f_anim, "HeroAnimator 接受 LivingEntity")


# ---------------- 3) 商人实体：去掉会拧身体的两个 goal ----------------
def f_merchant(c):
    c = c.replace("import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;\n", "")
    c = c.replace("import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;\n", "")
    lines = c.split("\n")
    out = []
    for ln in lines:
        s = ln.strip()
        if s.startswith("this.goalSelector.addGoal") and (
                "LookAtPlayerGoal" in s or "RandomLookAroundGoal" in s):
            out.append("        // 刻意不加 LookAtPlayerGoal / RandomLookAroundGoal：它们会拧身体的 yaw，")
            out.append("        // 而模型朝向直接取自 entity.getYRot()，身体一旦自动对着玩家，")
            out.append("        // HeroAnimator 算出的头部偏角恒为 0，眼部追踪就看不出来了。")
            out.append("        // 看向玩家改由头部+眼球骨骼表现（有角度上限）。")
            continue
        out.append(ln)
    return "\n".join(out)


rw(r"entity\PardofelisMerchantEntity.java", f_merchant, "商人实体去掉 look goal")


# ---------------- 4) 删掉重复的 PARD0FELIS_NPC ----------------
def f_entities(c):
    c = c.replace("""

    public static final RegistryObject<EntityType<ElysianHeroNpcEntity>> PARD0FELIS_NPC =
            registerHero("pardofelis_npc", ElysianHeroNpcEntity.HeroVariant.PARD0FELIS);""", "")
    return c


rw(r"registry\ModEntities.java", f_entities, "ModEntities 去掉重复 NPC")


def f_events(c):
    c = c.replace("""        event.put(
                ModEntities.PARD0FELIS_NPC.get(),
                ElysianHeroNpcEntity.createAttributes().build()
        );
""", "")
    return c


rw(r"event\ModEntityEvents.java", f_events, "ModEntityEvents 去掉重复 NPC")


def f_variant(c):
    c = c.replace("        MOBIUS,\n        PARD0FELIS;\n", "        MOBIUS;\n", 1)
    return c


rw(r"entity\ElysianHeroNpcEntity.java", f_variant, "HeroVariant 去掉 PARD0FELIS")


def f_hero_renderer(c):
    c = c.replace(
        """            case EDEN -> EDEN;
      case MOBIUS -> ELYSIA;
            case PARD0FELIS -> ELYSIA;
""",
        """            case EDEN -> EDEN;
            case MOBIUS -> ELYSIA;
""", 1)
    return c


rw(r"client\renderer\ElysianHeroRenderer.java", f_hero_renderer, "ElysianHeroRenderer switch 收敛")


def f_hero_model(c):
    c = c.replace("""
            case PARD0FELIS -> {
                this.leftArm.zRot =
                        -0.020F
                                - idleFast * 0.008F;

                this.rightArm.zRot =
                        0.020F
                                + idleFast * 0.008F;
            }
""", "", 1)
    return c


rw(r"client\model\ElysianHeroModel.java", f_hero_model, "ElysianHeroModel switch 收敛")


# ---------------- 5) ModEntityRenderers 重写注册 ----------------
def f_mod_renderers(c):
    c = c.replace("import com.ovo.lastsongofelysian.client.model.PardofelisMerchantModel;\n", "")
    c = c.replace("import com.ovo.lastsongofelysian.client.renderer.PardofelisMerchantRenderer;\n", "")
    c = c.replace("""          event.registerLayerDefinition(
                  PardofelisMerchantModel.LAYER_LOCATION,
                  PardofelisMerchantModel::createBodyLayer
          );
""", "")
    c = c.replace("""          event.registerLayerDefinition(
                  PardofelisMerchantModel.LAYER_LOCATION,
                  PardofelisMerchantModel::createBodyLayer
          );
""", "")

    # 所有英桀 NPC 改成 heroProvider()
    for npc in ("APONIA_NPC", "ELYSIA_NPC", "MOBIUS_NPC", "GRISEO_NPC", "VILL_V_NPC"):
        c = c.replace(
            "                ModEntities.%s.get(),\n                MeshHeroRenderer::new\n        );" % npc,
            "                ModEntities.%s.get(),\n                MeshHeroRenderer.heroProvider()\n        );" % npc)

    # 去掉重复 NPC 注册
    c = c.replace("""          event.registerEntityRenderer(
                  ModEntities.PARD0FELIS_NPC.get(),
                  MeshHeroRenderer::new
          );
""", "")
    c = c.replace("""
        event.registerEntityRenderer(
                ModEntities.PARD0FELIS_NPC.get(),
                MeshHeroRenderer::new
        );""", "")

    # 商人改成读 pardofelis 的网格模型
    c = c.replace("""          event.registerEntityRenderer(
                  ModEntities.PARDOFELIS_MERCHANT.get(),
                  PardofelisMerchantRenderer::new
          );""",
                  """          // 帕朵菲莉丝商人：实体本身不带变体信息，固定读 pardofelis 模型
          event.registerEntityRenderer(
                  ModEntities.PARDOFELIS_MERCHANT.get(),
                  MeshHeroRenderer.<PardofelisMerchantEntity>fixedProvider("pardofelis")
          );""")
    c = c.replace("""        event.registerEntityRenderer(
                ModEntities.PARDOFELIS_MERCHANT.get(),
                PardofelisMerchantRenderer::new
        );""",
                  """        // 帕朵菲莉丝商人：实体本身不带变体信息，固定读 pardofelis 模型
        event.registerEntityRenderer(
                ModEntities.PARDOFELIS_MERCHANT.get(),
                MeshHeroRenderer.<PardofelisMerchantEntity>fixedProvider("pardofelis")
        );""")

    if "PardofelisMerchantEntity" not in c.split("class ")[0]:
        c = c.replace("import com.ovo.lastsongofelysian.registry.ModEntities;",
                      "import com.ovo.lastsongofelysian.entity.PardofelisMerchantEntity;\n"
                      "import com.ovo.lastsongofelysian.registry.ModEntities;", 1)
    return c


rw(r"client\ModEntityRenderers.java", f_mod_renderers, "ModEntityRenderers 重写注册")


# ---------------- 6) 删掉不再使用的方块模型 ----------------
for rel in (r"client\renderer\PardofelisMerchantRenderer.java",
            r"client\model\PardofelisMerchantModel.java"):
    p = os.path.join(J, rel)
    if os.path.isfile(p):
        os.remove(p)
        print("  ✓ 删除 " + rel)

# ---------------- 7) 语言文件去掉重复 NPC 的名字 ----------------
for lang in ("zh_cn.json", "en_us.json"):
    p = os.path.join(ROOT, r"src\main\resources\assets\lastsongofelysian\lang", lang)
    d = json.loads(io.open(p, encoding="utf-8").read())
    if d.pop("entity.lastsongofelysian.pardofelis_npc", None) is not None:
        io.open(p, "w", encoding="utf-8", newline="\n").write(
            json.dumps(d, ensure_ascii=False, indent=2))
        print("  ✓ %s 去掉 pardofelis_npc" % lang)

print("完成")
