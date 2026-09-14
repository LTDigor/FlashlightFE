package com.ltdigor.bestflashlight.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ltdigor.bestflashlight.LampData;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/** Band above the eyebrows; the centered lamp sits on the forehead, clear of the eyes. */
public final class HeadbandRenderer implements ICurioRenderer {
    private static final ResourceLocation STRAP = ResourceLocation.withDefaultNamespace("textures/block/black_wool.png");
    private static final ResourceLocation HOUSING = ResourceLocation.withDefaultNamespace("textures/block/iron_block.png");
    private static final ResourceLocation LENS = ResourceLocation.withDefaultNamespace("textures/block/sea_lantern.png");
    private final ModelPart root;
    public HeadbandRenderer() {
        var mesh = new MeshDefinition();
        var head = mesh.getRoot().addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        head.addOrReplaceChild("strap", CubeListBuilder.create()
            .addBox(-4.4F, -6.6F, -4.4F, 8.8F, 1.5F, 0.4F)
            .addBox(-4.4F, -6.6F, 4.0F, 8.8F, 1.5F, 0.4F)
            .addBox(-4.4F, -6.6F, -4.0F, 0.4F, 1.5F, 8.0F)
            .addBox(4.0F, -6.6F, -4.0F, 0.4F, 1.5F, 8.0F), PartPose.ZERO);
        head.addOrReplaceChild("lamp", CubeListBuilder.create().addBox(-1.6F, -7.2F, -6.8F, 3.2F, 2.8F, 2.5F), PartPose.ZERO);
        head.addOrReplaceChild("lens", CubeListBuilder.create().addBox(-1.1F, -6.7F, -6.9F, 2.2F, 1.8F, 0.2F), PartPose.ZERO);
        root = LayerDefinition.create(mesh, 16, 16).bakeRoot().getChild("head");
    }
    @Override public <T extends LivingEntity, M extends EntityModel<T>> void render(
        ItemStack stack, SlotContext context, PoseStack pose, RenderLayerParent<T, M> parent,
        MultiBufferSource buffers, int light, float limbSwing, float limbSwingAmount,
        float partialTicks, float age, float headYaw, float headPitch) {
        pose.pushPose();
        ICurioRenderer.translateIfSneaking(pose, context.entity());
        ICurioRenderer.followHeadRotations(context.entity(), root);
        if (!context.entity().getItemBySlot(EquipmentSlot.HEAD).isEmpty()) pose.scale(1.15F, 1.15F, 1.15F);
        root.translateAndRotate(pose);
        root.getChild("strap").render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(STRAP)), light, OverlayTexture.NO_OVERLAY);
        if (!LampData.mounted(stack).isEmpty()) {
            root.getChild("lamp").render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(HOUSING)), light, OverlayTexture.NO_OVERLAY);
            root.getChild("lens").render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(LENS)), LampData.enabled(stack) ? 0xF000F0 : light, OverlayTexture.NO_OVERLAY);
        }
        pose.popPose();
    }
}
