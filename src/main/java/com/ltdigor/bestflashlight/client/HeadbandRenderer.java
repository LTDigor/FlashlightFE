package com.ltdigor.bestflashlight.client;

import com.ltdigor.bestflashlight.FlashlightMod;
import com.ltdigor.bestflashlight.LampData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/** The same baked geometry is used in the inventory and on the wearer's forehead. */
@EventBusSubscriber(modid = FlashlightMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class HeadbandRenderer implements ICurioRenderer {
    private static final ModelResourceLocation EMPTY = model("headband_empty");
    private static final ModelResourceLocation OFF = model("headband_loaded");
    private static final ModelResourceLocation ON = model("headband_loaded_on");
    // Only carries the wearer's head pose; all visible geometry lives in the JSON assets.
    private final ModelPart head = LayerDefinition.create(new MeshDefinition(), 16, 16).bakeRoot();

    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(FlashlightMod.resource("item/" + name));
    }

    @SubscribeEvent
    public static void additionalModels(ModelEvent.RegisterAdditional event) {
        event.register(EMPTY);
        event.register(OFF);
        event.register(ON);
    }

    @Override
    public <T extends LivingEntity, M extends EntityModel<T>> void render(
        ItemStack stack, SlotContext context, PoseStack pose, RenderLayerParent<T, M> parent,
        MultiBufferSource buffers, int light, float limbSwing, float limbSwingAmount,
        float partialTicks, float age, float headYaw, float headPitch) {
        ModelResourceLocation id = LampData.mounted(stack).isEmpty() ? EMPTY : LampData.enabled(stack) ? ON : OFF;
        Minecraft client = Minecraft.getInstance();
        // Resolve from the current ModelManager, never cache a BakedModel across F3+T/reloads.
        BakedModel geometry = client.getModelManager().getModel(id);
        pose.pushPose();
        try {
            ICurioRenderer.translateIfSneaking(pose, context.entity());
            ICurioRenderer.followHeadRotations(context.entity(), head);
            if (!context.entity().getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                pose.scale(1.15F, 1.15F, 1.15F);
            }
            head.translateAndRotate(pose);
            // Item Y points up; entity-model Y points down. A proper rotation (not a
            // negative scale) preserves winding/normals and leaves the lens facing -Z.
            pose.mulPose(Axis.ZP.rotationDegrees(180));
            pose.translate(-0.5, -0.5, -0.5);
            for (BakedModel pass : geometry.getRenderPasses(stack, true)) {
                for (RenderType type : pass.getRenderTypes(stack, true)) {
                    client.getItemRenderer().renderModelLists(pass, stack, light, OverlayTexture.NO_OVERLAY,
                        pose, ItemRenderer.getFoilBufferDirect(buffers, type, true, stack.hasFoil()));
                }
            }
        } finally {
            pose.popPose();
        }
    }
}
