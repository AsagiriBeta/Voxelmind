package asagiribeta.voxelmind.client.input;

import asagiribeta.voxelmind.client.agent.ActionSchema;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;

import java.util.EnumSet;

public final class InputApplier {
    // Track holds to maintain while enabled
    private final EnumSet<Key> held = EnumSet.noneOf(Key.class);

    private enum Key { FORWARD, BACK, LEFT, RIGHT, ATTACK, USE, SNEAK, SPRINT }

    public void resetAll() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            Options o = mc.options;
            set(o.keyUp, false);
            set(o.keyDown, false);
            set(o.keyLeft, false);
            set(o.keyRight, false);
            set(o.keyAttack, false);
            set(o.keyUse, false);
            set(o.keyShift, false);
            set(o.keySprint, false);
        }
        held.clear();
    }

    public void tick(Minecraft mc) {
        // no-op; absolute view set in applyView
    }

    public void applyMovement(Minecraft mc, ActionSchema.Movement mv) {
        Options o = mc.options;
        setHold(o.keyUp, mv.forward(), Key.FORWARD);
        setHold(o.keyDown, mv.back(), Key.BACK);
        setHold(o.keyLeft, mv.left(), Key.LEFT);
        setHold(o.keyRight, mv.right(), Key.RIGHT);
        // Jump as a tap
        if (mv.jump()) tap(o.keyJump);
        setHold(o.keyShift, mv.sneak(), Key.SNEAK);
        setHold(o.keySprint, mv.sprint(), Key.SPRINT);
    }

    public void applyMouse(Minecraft mc, ActionSchema.Mouse mouse) {
        Options o = mc.options;
        applyPressType(o.keyAttack, mouse.left(), Key.ATTACK);
        applyPressType(o.keyUse, mouse.right(), Key.USE);
    }

    public void applyView(Minecraft mc, ActionSchema.View view) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        // Absolute set first
        view.yawAbs().ifPresent(abs -> p.setYRot(wrapYaw(abs)));
        view.pitchAbs().ifPresent(abs -> p.setXRot(clampPitch(abs)));
        // Then optional deltas
        view.yawDelta().ifPresent(delta -> p.setYRot(wrapYaw(p.getYRot() + delta)));
        view.pitchDelta().ifPresent(delta -> p.setXRot(clampPitch(p.getXRot() + delta)));
    }

    private static float clampPitch(float v) { return Math.max(-90f, Math.min(90f, v)); }
    private static float wrapYaw(float v) {
        float f = v % 360f;
        if (f >= 180f) f -= 360f; if (f < -180f) f += 360f; return f;
    }

    private void applyPressType(KeyMapping key, ActionSchema.PressType type, Key tag) {
        switch (type) {
            case NONE -> { /* do nothing */ }
            case TAP -> { tap(key); held.remove(tag); }
            case HOLD -> { set(key, true); held.add(tag); }
            case RELEASE -> { set(key, false); held.remove(tag); }
        }
    }

    private void setHold(KeyMapping key, boolean state, Key tag) {
        set(key, state);
        if (state) held.add(tag); else held.remove(tag);
    }

    private static void tap(KeyMapping key) { set(key, true); set(key, false); }

    private static void set(KeyMapping key, boolean down) { if (key != null) key.setDown(down); }
}
