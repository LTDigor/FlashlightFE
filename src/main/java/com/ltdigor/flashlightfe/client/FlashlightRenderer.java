package com.ltdigor.flashlightfe.client;

import com.ltdigor.flashlightfe.FlashlightMod;
import com.ltdigor.flashlightfe.LampData;
import com.mojang.blaze3d.vertex.PoseStack;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

/** Native baked geometry with a separately translated mechanical button. */
@EventBusSubscriber(modid = FlashlightMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FlashlightRenderer extends BlockEntityWithoutLevelRenderer {
    private static final ModelResourceLocation INVENTORY =
        ModelResourceLocation.inventory(FlashlightMod.resource("flashlight"));
    private static final ModelResourceLocation BUTTON =
        ModelResourceLocation.standalone(FlashlightMod.resource("item/flashlight_button"));
    private static final ThreadLocal<PendingRender> PENDING = new ThreadLocal<>();

    private FlashlightRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @SubscribeEvent
    public static void extensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            private FlashlightRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new FlashlightRenderer();
                return renderer;
            }
        }, FlashlightMod.FLASHLIGHT.get());
    }

    @SubscribeEvent
    public static void additionalModels(ModelEvent.RegisterAdditional event) {
        event.register(BUTTON);
    }

    @SubscribeEvent
    public static void wrapModel(ModelEvent.ModifyBakingResult event) {
        BakedModel body = event.getModels().get(INVENTORY);
        BakedModel button = event.getModels().get(BUTTON);
        if (body != null && button != null) event.getModels().put(INVENTORY, new Model(body, button));
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poses,
                             MultiBufferSource buffers, int light, int overlay) {
        PendingRender pending = PENDING.get();
        PENDING.remove();
        if (pending == null) return;
        draw(pending.body(), stack, poses, buffers, light, overlay);
        double offset = ButtonMotion.rest(LampData.enabled(stack));
        LivingEntity owner = pending.owner();
        InteractionHand hand = heldHand(owner, context);
        if (owner != null && hand != null) {
            offset = ButtonAnimation.offset(owner.getUUID(), hand, LampData.enabled(stack));
        }
        poses.pushPose();
        poses.translate(0, offset / 16.0, 0);
        draw(pending.model().button, stack, poses, buffers, light, overlay);
        poses.popPose();
    }

    @Nullable
    private static InteractionHand heldHand(@Nullable LivingEntity owner, ItemDisplayContext context) {
        if (owner == null) return null;
        HumanoidArm renderedArm = switch (context) {
            case FIRST_PERSON_RIGHT_HAND, THIRD_PERSON_RIGHT_HAND -> HumanoidArm.RIGHT;
            case FIRST_PERSON_LEFT_HAND, THIRD_PERSON_LEFT_HAND -> HumanoidArm.LEFT;
            default -> null;
        };
        if (renderedArm == null) return null;
        return renderedArm == owner.getMainArm() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }

    private static void draw(BakedModel model, ItemStack stack, PoseStack poses,
                             MultiBufferSource buffers, int light, int overlay) {
        ItemRenderer renderer = Minecraft.getInstance().getItemRenderer();
        for (BakedModel pass : model.getRenderPasses(stack, true)) {
            for (RenderType type : pass.getRenderTypes(stack, true)) {
                renderer.renderModelLists(pass, stack, light, overlay, poses,
                    ItemRenderer.getFoilBufferDirect(buffers, type, true, stack.hasFoil()));
            }
        }
    }

    private record PendingRender(Model model, BakedModel body, @Nullable LivingEntity owner) {}

    private static final class Model extends BakedModelWrapper<BakedModel> {
        private final BakedModel button;
        private final ItemOverrides overrides = new ItemOverrides() {
            @Override
            public BakedModel resolve(BakedModel model, ItemStack stack, @Nullable ClientLevel level,
                                      @Nullable LivingEntity entity, int seed) {
                BakedModel body = originalModel.getOverrides().resolve(originalModel, stack, level, entity, seed);
                PENDING.set(new PendingRender(Model.this, body == null ? originalModel : body, entity));
                // Returning the override body here would bypass this custom renderer for enabled items.
                return Model.this;
            }
        };

        private Model(BakedModel body, BakedModel button) {
            super(body);
            this.button = button;
        }

        @Override public boolean isCustomRenderer() { return true; }
        @Override public ItemOverrides getOverrides() { return overrides; }

        @Override
        public BakedModel applyTransform(ItemDisplayContext context, PoseStack poses, boolean leftHand) {
            PendingRender pending = PENDING.get();
            if (pending == null || pending.model() != this) pending = new PendingRender(this, originalModel, null);
            BakedModel transformed = pending.body().applyTransform(context, poses, leftHand);
            PENDING.set(new PendingRender(this, transformed, pending.owner()));
            return this;
        }
    }
}
