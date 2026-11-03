package com.ovo.lastsongofelysian.client;

import com.ovo.lastsongofelysian.client.model.ElysianHeroModel;
import com.ovo.lastsongofelysian.client.renderer.CorrosionMirrorRenderer;
import com.ovo.lastsongofelysian.client.renderer.EdenBlackHoleRenderer;
import com.ovo.lastsongofelysian.client.renderer.ElysianHeroRenderer;
import com.ovo.lastsongofelysian.client.renderer.MeshHeroRenderer;
import com.ovo.lastsongofelysian.lastsongofelysian;
import com.ovo.lastsongofelysian.entity.PardofelisMerchantEntity;
import com.ovo.lastsongofelysian.registry.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = lastsongofelysian.MODID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public class ModEntityRenderers {

    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(
                ElysianHeroModel.LAYER_LOCATION,
                ElysianHeroModel::createBodyLayer
        );
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(
                ModEntities.CORROSION_MIRROR.get(),
                CorrosionMirrorRenderer::new
        );
        event.registerEntityRenderer(
                ModEntities.EDEN_BLACK_HOLE.get(),
                EdenBlackHoleRenderer::new
        );
        event.registerEntityRenderer(
                ModEntities.APONIA_NPC.get(),
                MeshHeroRenderer.heroProvider()
        );
        event.registerEntityRenderer(
                ModEntities.ELYSIA_NPC.get(),
                MeshHeroRenderer.heroProvider()
        );
        event.registerEntityRenderer(
                ModEntities.MOBIUS_NPC.get(),
                MeshHeroRenderer.heroProvider()
        );
        event.registerEntityRenderer(
                ModEntities.GRISEO_NPC.get(),
                MeshHeroRenderer.heroProvider()
        );
        event.registerEntityRenderer(
                ModEntities.VILL_V_NPC.get(),
                MeshHeroRenderer.heroProvider()
        );
        event.registerEntityRenderer(
                ModEntities.EDEN_NPC.get(),
                ElysianHeroRenderer::new
        );

        // 帕朵菲莉丝商人：实体本身不带变体信息，固定读 pardofelis 模型
        event.registerEntityRenderer(
                ModEntities.PARDOFELIS_MERCHANT.get(),
                MeshHeroRenderer.<PardofelisMerchantEntity>fixedProvider("pardofelis")
        );
    }
}
