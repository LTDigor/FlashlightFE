package com.ltdigor.flashlightfe.client;

import com.ltdigor.flashlightfe.FlashlightMod;
import com.ltdigor.flashlightfe.LampData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
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

/** Curios renderer implementation with no Curios type in its class signature. */
@EventBusSubscriber(modid = FlashlightMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
final class HeadbandRenderer {
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

    static Object createProxy() {
        try {
            Class<?> renderer = Class.forName("top.theillusivec4.curios.api.client.ICurioRenderer");
            HeadbandRenderer headband = new HeadbandRenderer();
            InvocationHandler handler = (proxy, method, arguments) -> {
                if (method.getName().equals("render")) {
                    headband.render(arguments);
                    return null;
                }
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments == null ? new Object[0] : arguments);
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == arguments[0];
                return null;
            };
            return Proxy.newProxyInstance(renderer.getClassLoader(), new Class<?>[]{renderer}, handler);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Curios renderer is unavailable", exception);
        }
    }

    private void render(Object[] arguments) {
        ItemStack stack = (ItemStack) arguments[0];
        LivingEntity entity = (LivingEntity) invoke(arguments[1], "entity");
        PoseStack pose = (PoseStack) arguments[2];
        MultiBufferSource buffers = (MultiBufferSource) arguments[4];
        int light = (int) arguments[5];
        ModelResourceLocation id = LampData.mounted(stack).isEmpty() ? EMPTY : LampData.enabled(stack) ? ON : OFF;
        Minecraft client = Minecraft.getInstance();
        BakedModel geometry = client.getModelManager().getModel(id);
        pose.pushPose();
        try {
            callCuriosRenderer("followHeadRotations", new Class<?>[]{LivingEntity.class, ModelPart[].class}, entity, new ModelPart[]{head});
            if (!entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) pose.scale(1.15F, 1.15F, 1.15F);
            head.translateAndRotate(pose);
            // The authored emitter faces -Z while the head mount faces +Z; a Z roll kept the lamp on the nape.
            pose.mulPose(Axis.YP.rotationDegrees(180));
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

    private static void callCuriosRenderer(String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Class<?> renderer = Class.forName("top.theillusivec4.curios.api.client.ICurioRenderer");
            renderer.getMethod(name, parameterTypes).invoke(null, arguments);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Curios renderer call failed: " + name, exception);
        }
    }

    private static Object invoke(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Curios renderer context failed: " + name, exception);
        }
    }
}
