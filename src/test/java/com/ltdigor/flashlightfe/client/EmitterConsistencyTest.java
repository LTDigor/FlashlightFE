package com.ltdigor.flashlightfe.client;

import com.ltdigor.flashlightfe.lighting.EmitterTransform;
import java.lang.reflect.Method;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmitterConsistencyTest {
    @Test
    void clientAndServerUseIdenticalEmittersAtEveryPitchAndHand() throws Exception {
        Player player = mock(Player.class);
        Method clientOrigin = OptionalDynamicLights.class.getDeclaredMethod("emitterOrigin", Player.class,
            boolean.class, boolean.class, Vec3.class, Vec3.class);
        clientOrigin.setAccessible(true);
        Vec3 eye = new Vec3(8.5, 64.4, 8.5);
        for (float yaw : new float[]{0, 90, -135}) {
            when(player.getYRot()).thenReturn(yaw);
            for (HumanoidArm arm : HumanoidArm.values()) {
                when(player.getMainArm()).thenReturn(arm);
                for (float pitch : new float[]{-90, -45, 0, 45, 90}) {
                    Vec3 look = Vec3.directionFromRotation(pitch, yaw).normalize();
                    for (boolean headMounted : new boolean[]{false, true}) {
                        for (boolean offHand : new boolean[]{false, true}) {
                            EmitterTransform transform = headMounted ? EmitterTransform.HEADBAND : EmitterTransform.HANDHELD;
                            Vec3 server = transform.origin(eye, look, Math.toRadians(yaw),
                                (arm == HumanoidArm.RIGHT) != offHand, headMounted);
                            Vec3 client = (Vec3) clientOrigin.invoke(null, player, headMounted, offHand, look, eye);
                            assertEquals(0.0, server.distanceToSqr(client), 1e-12,
                                "Emitter mismatch: yaw=" + yaw + ", pitch=" + pitch + ", head=" + headMounted);
                        }
                    }
                }
            }
        }
    }

    @Test
    void downwardHeadbandAgreesAtTheWaterline() throws Exception {
        Player player = mock(Player.class);
        when(player.getMainArm()).thenReturn(HumanoidArm.RIGHT);
        Method clientOrigin = OptionalDynamicLights.class.getDeclaredMethod("emitterOrigin", Player.class,
            boolean.class, boolean.class, Vec3.class, Vec3.class);
        clientOrigin.setAccessible(true);
        Vec3 eye = new Vec3(0.5, 64.4, 0.5);
        Vec3 look = new Vec3(0, -1, 0);
        Vec3 client = (Vec3) clientOrigin.invoke(null, player, true, false, look, eye);
        Vec3 server = EmitterTransform.HEADBAND.origin(eye, look, 0, true, true);
        assertTrue(server.y < 64.0, "Fixture must place the server emitter below water");
        assertTrue(client.y < 64.0, "Client must not light a headlamp which the server considers submerged");
    }
}
