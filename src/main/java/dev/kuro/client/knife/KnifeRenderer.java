package dev.kuro.client.knife;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
 * Public face of the knife package: animation state (deploy / inspect timers, trail history) and the first-person draw
 * call. KnifeModule feeds it settings and key state every tick; ItemInHandRendererMixin calls {@link #render}.
 */
public final class KnifeRenderer {
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("kuro", "textures/misc/knife_white.png");

    /** Names shown to the user, in selector order (1-based in the module). */
    public static final String[] KNIVES = KnifeSkin.NAMES;

    private static final float[] AT = new float[InspectAnimation.CHANNELS];
    private static long inspectStart = -1, deployStart = -1;   // System.nanoTime(), -1 = not running
    private static boolean holding, keyWasDown;
    private static Item lastItem;
    private static int lastSlot = -1;
    private static KnifeSkin lastSkin;
    private static final KnifeTrail TRAIL = new KnifeTrail();
    private static float[] trailEnds = new float[0];

    private KnifeRenderer() {}

    public static boolean isKnifeItem(ItemStack stack) {
        return stack.is(ItemTags.SWORDS);
    }

    /** Clears all animation state (module switched off). */
    public static void reset() {
        inspectStart = deployStart = -1;
        holding = keyWasDown = false;
        lastItem = null;
        lastSlot = -1;
        lastSkin = null;
        TRAIL.clear();
    }

    /**
     * Once per client tick while the module is on.
     * @param inspectDown  is the inspect key down right now
     * @param deployOnEquip play the draw animation when the held item / slot changes
     * @param cancelOnSwing hitting stops an inspect
     */
    public static void tick(boolean inspectDown, boolean deployOnEquip, boolean cancelOnSwing) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { reset(); return; }
        long now = System.nanoTime();
        ItemStack main = mc.player.getMainHandItem();
        int slot = mc.player.getInventory().getSelectedSlot();
        if (slot != lastSlot || main.getItem() != lastItem) {
            lastSlot = slot;
            lastItem = main.getItem();
            inspectStart = -1;
            deployStart = deployOnEquip && isKnifeItem(main) ? now : -1;
            TRAIL.clear();
        }
        holding = inspectDown;
        if (inspectDown && !keyWasDown && isKnifeItem(main) && deployStart < 0 && inspectStart < 0) inspectStart = now;
        keyWasDown = inspectDown;
        if (cancelOnSwing && mc.player.swinging) inspectStart = -1;
    }

    /**
     * Draws the knife + arm instead of the held sword.
     * @param knife        1-based index into {@link #KNIVES}
     * @param trailSeconds how long the motion trail lasts; 0 or less = trail off
     * @return true if it drew (the caller then cancels vanilla's item render)
     */
    public static boolean render(ItemStack stack, ItemDisplayContext context, PoseStack pose, SubmitNodeCollector collector,
                                 int light, float size, boolean matchMaterial, float offX, float offY, float offZ,
                                 int knife, float trailSeconds) {
        boolean right = context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        boolean left = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        if ((!right && !left) || !isKnifeItem(stack)) return false;
        KnifeSkin skin = KnifeSkin.of(KNIVES[Math.max(0, Math.min(KNIVES.length - 1, knife - 1))]);
        if (skin == null) return false;

        long now = System.nanoTime();
        if (skin != lastSkin) {                       // knife changed in the settings: draw it fresh
            lastSkin = skin;
            inspectStart = -1;
            deployStart = now;
            TRAIL.clear();
            trailEnds = new float[skin.streakCount() * 6];
        }

        Minecraft mc = Minecraft.getInstance();
        boolean animated = mc.player != null && stack == mc.player.getMainHandItem();
        if (animated) sample(skin); else Arrays.fill(AT, 0.0f);
        boolean moving = animated && (deployStart >= 0 || inspectStart >= 0);
        boolean trailOn = trailSeconds > 0.0f;

        FirstPersonArm.Arm arm = FirstPersonArm.arm();
        KnifeMaterial material = matchMaterial ? KnifeMaterial.of(stack) : KnifeMaterial.named("iron");
        int[] palette = material.palette;
        RenderType type = RenderTypes.entityCutoutNoCull(WHITE);

        KnifeSkin.Sink sink = new KnifeSkin.Sink() {
            @Override
            public void part(PoseStack p, KnifeMesh mesh, KnifeMesh.Shine shine) {
                collector.submitCustomGeometry(p, type, (last, buffer) -> mesh.emit(last, buffer, palette, light, shine));
            }

            @Override
            public void arm(PoseStack p) {
                if (arm != null) arm.draw(p, left, collector, light);
            }
        };

        pose.pushPose();
        // Vanilla moves the pose out to the hand (about +-0.56, -0.52, -0.72) before it calls renderItem. If the pose we
        // are handed is still sitting at the camera, put the hand offset in ourselves so the knife is not drawn inside the lens.
        if (pose.last().pose().getTranslation(new org.joml.Vector3f()).length() < 0.3f) {
            pose.translate(left ? -0.56f : 0.56f, -0.52f, -0.72f);
        }
        pose.translate(left ? -offX : offX, offY, offZ);
        if (left) pose.scale(-1.0f, 1.0f, 1.0f);

        boolean record = trailOn && moving;
        skin.draw(pose, AT, 1.0f, arm != null && arm.slim(), size, sink, record ? trailEnds : null);
        if (record) TRAIL.add(now, trailEnds);

        if (animated) {
            if (!trailOn) {
                TRAIL.clear();
            } else {
                TRAIL.expire(now, trailSeconds);
                float[] quads = TRAIL.build(now, trailSeconds, skin);
                if (quads.length > 0) {
                    int rgb = material.glow;
                    collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(WHITE),
                            (last, buffer) -> KnifeTrail.emit(last, buffer, quads, rgb));
                }
            }
        }
        pose.popPose();
        return true;
    }

    /** Fills AT for this frame: deploy clip, else inspect clip (held in its loop while the key is down), else rest. */
    private static void sample(KnifeSkin skin) {
        long now = System.nanoTime();
        if (deployStart >= 0) {
            float p = (now - deployStart) / 1.0e9f / skin.deploy.seconds;
            if (p < 1.0f) { skin.deploy.sample(p, AT); return; }
            deployStart = -1;
        }
        if (inspectStart >= 0) {
            InspectAnimation.Clip clip = skin.inspect;
            float p = (now - inspectStart) / 1.0e9f / clip.seconds;
            if (clip.loops() && holding && p >= clip.loopEnter) {
                // key still down: stay inside the loop window. Re-anchoring the start time means that when the key is
                // released the clip carries on from exactly this phase, so there is no jump.
                p = clip.loopEnter + (p - clip.loopEnter) % (clip.loopExit - clip.loopEnter);
                inspectStart = now - (long) (p * clip.seconds * 1.0e9f);
            }
            if (p < 1.0f) { clip.sample(p, AT); return; }
            inspectStart = -1;
        }
        Arrays.fill(AT, 0.0f);
    }
}
