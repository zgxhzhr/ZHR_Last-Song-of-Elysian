package com.ovo.lastsongofelysian.entity;

import com.ovo.lastsongofelysian.util.ModText;

import com.ovo.lastsongofelysian.menu.SpiralWorkshopMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

public class ElysianHeroNpcEntity extends PathfinderMob {

    public enum HeroVariant {
        APONIA,
        ELYSIA,
        GRISEO,
        VILL_V,
        EDEN,
        MOBIUS;
    }

    private final HeroVariant variant;

    public ElysianHeroNpcEntity(
            EntityType<? extends ElysianHeroNpcEntity> type,
            Level level,
            HeroVariant variant
    ) {
        super(type, level);
        this.variant = variant;
        // 诊断：确认实体确实被构造出来（排查 /summon 失败时用）
        org.slf4j.LoggerFactory.getLogger("lastsongofelysian/hero")
                .info("[HeroNpc] constructed variant={} type={} side={}",
                        variant, type, level.isClientSide() ? "client" : "server");
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 80.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.23D)
                .add(Attributes.FOLLOW_RANGE, 28.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.35D);
    }

    @Override
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
    }

    public HeroVariant getVariant() {
        return this.variant;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.variant != HeroVariant.VILL_V) {
            return super.mobInteract(player, hand);
        }

        if (this.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(
                    serverPlayer,
                    new SimpleMenuProvider(
                            (containerId, inventory, menuPlayer) ->
                                    new SpiralWorkshopMenu(containerId, inventory),
                            ModText.literal("螺旋工坊")
                    )
            );
        }

        return InteractionResult.CONSUME;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }
}
