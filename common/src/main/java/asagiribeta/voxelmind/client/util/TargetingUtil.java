package asagiribeta.voxelmind.client.util;

import asagiribeta.voxelmind.client.agent.ActionSchema;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public final class TargetingUtil {
    private TargetingUtil() {}

    public static Optional<BlockPos> resolveTargetPos(Minecraft mc, ActionSchema.Target target, int radius) {
        if (mc.level == null) return Optional.empty();
        Level level = mc.level;
        // Priority 1: explicit coordinates
        if (target.hasPos()) {
            BlockPos pos = new BlockPos(target.x().orElse(0), target.y().orElse(0), target.z().orElse(0));
            return Optional.of(pos);
        }
        // Priority 2: by blockId or blockTag - find nearest within radius
        Block targetBlock = null;
        TagKey<Block> tagKey = null;
        if (target.blockId().isPresent()) {
            ResourceLocation id = ResourceLocation.tryParse(target.blockId().get());
            if (id != null) {
                var blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
                var key = ResourceKey.create(Registries.BLOCK, id);
                targetBlock = blocks.get(key).map(ref -> ref.value()).orElse(null);
            }
        }
        if (targetBlock == null && target.blockTag().isPresent()) {
            ResourceLocation tagId = ResourceLocation.tryParse(target.blockTag().get());
            if (tagId != null) tagKey = TagKey.create(Registries.BLOCK, tagId);
        }
        if (targetBlock == null && tagKey == null) return Optional.empty();

        BlockPos playerPos = mc.player.blockPosition();
        int r = Math.max(1, radius);
        double bestDist2 = Double.MAX_VALUE;
        BlockPos best = null;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = playerPos.offset(dx, dy, dz);
                    BlockState st = level.getBlockState(p);
                    if (stateMatches(st, targetBlock, tagKey)) {
                        double d2 = p.distToCenterSqr(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                        if (d2 < bestDist2) { bestDist2 = d2; best = p; }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    public static Optional<Entity> resolveTargetEntity(Minecraft mc, ActionSchema.Target target, int radius) {
        if (mc.level == null) return Optional.empty();
        if (!target.hasEntity()) return Optional.empty();
        Level level = mc.level;
        ResourceLocation typeId = null;
        EntityType<?> desiredType = null;
        if (target.entityType().isPresent()) {
            typeId = ResourceLocation.tryParse(target.entityType().get());
            if (typeId != null) {
                var entities = level.registryAccess().lookupOrThrow(Registries.ENTITY_TYPE);
                var key = ResourceKey.create(Registries.ENTITY_TYPE, typeId);
                desiredType = entities.get(key).map(ref -> ref.value()).orElse(null);
            }
        }
        String nameSub = target.entityName().map(s -> s.toLowerCase(Locale.ROOT)).orElse(null);
        int r = Math.max(1, radius);
        AABB box = new AABB(mc.player.blockPosition()).inflate(r);
        double best = Double.MAX_VALUE;
        Entity bestEnt = null;
        for (Entity e : level.getEntities(mc.player, box)) {
            if (e == mc.player) continue;
            if (desiredType != null && e.getType() != desiredType) continue;
            if (nameSub != null) {
                String en = e.getName().getString().toLowerCase(Locale.ROOT);
                if (!en.contains(nameSub)) continue;
            }
            double d2 = e.distanceToSqr(mc.player);
            if (d2 < best) { best = d2; bestEnt = e; }
        }
        return Optional.ofNullable(bestEnt);
    }

    public static boolean crosshairMatchesTarget(Minecraft mc, ActionSchema.Target target, CrosshairUtil.CrosshairInfo info) {
        if (!info.isBlock()) return false;
        if (mc.level == null) return false;
        BlockState st = mc.level.getBlockState(info.pos());
        // pos match (within epsilon)
        if (target.hasPos()) {
            BlockPos p = new BlockPos(target.x().orElse(0), target.y().orElse(0), target.z().orElse(0));
            if (p.equals(info.pos())) return true;
        }
        // id or tag match
        Block targetBlock = null; TagKey<Block> tagKey = null;
        if (target.blockId().isPresent()) {
            ResourceLocation id = ResourceLocation.tryParse(target.blockId().get());
            if (id != null) {
                var blocks = mc.level.registryAccess().lookupOrThrow(Registries.BLOCK);
                var key = ResourceKey.create(Registries.BLOCK, id);
                targetBlock = blocks.get(key).map(ref -> ref.value()).orElse(null);
            }
        }
        if (targetBlock == null && target.blockTag().isPresent()) {
            ResourceLocation tagId = ResourceLocation.tryParse(target.blockTag().get());
            if (tagId != null) tagKey = TagKey.create(Registries.BLOCK, tagId);
        }
        return stateMatches(st, targetBlock, tagKey);
    }

    private static boolean stateMatches(BlockState st, Block targetBlock, TagKey<Block> tagKey) {
        if (targetBlock != null && st.is(targetBlock)) return true;
        if (tagKey != null && st.is(tagKey)) return true;
        return false;
    }

    public static Optional<ActionSchema.View> computeViewToPos(Minecraft mc, BlockPos pos) {
        if (mc.player == null) return Optional.empty();
        double tx = pos.getX() + 0.5;
        double ty = pos.getY() + 0.5;
        double tz = pos.getZ() + 0.5;
        return computeViewToPoint(mc, tx, ty, tz);
    }

    public static Optional<ActionSchema.View> computeViewToEntity(Minecraft mc, Entity e) {
        if (mc.player == null || e == null) return Optional.empty();
        var bb = e.getBoundingBox();
        double tx = bb.getCenter().x;
        double ty = bb.minY + e.getBbHeight() * 0.85; // bias toward head
        double tz = bb.getCenter().z;
        return computeViewToPoint(mc, tx, ty, tz);
    }

    private static Optional<ActionSchema.View> computeViewToPoint(Minecraft mc, double tx, double ty, double tz) {
        double eyeX = mc.player.getX();
        double eyeY = mc.player.getEyeY();
        double eyeZ = mc.player.getZ();
        double dx = tx - eyeX;
        double dy = ty - eyeY;
        double dz = tz - eyeZ;
        double horiz = Math.sqrt(dx*dx + dz*dz);
        float desiredYaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float desiredPitch = (float)(-Math.toDegrees(Math.atan2(dy, horiz)));
        return Optional.of(new ActionSchema.View(
                Optional.of(wrapYaw(desiredYaw)), Optional.of(clampPitch(desiredPitch)),
                Optional.empty(), Optional.empty()
        ));
    }

    private static float wrapYaw(float f) { f = f % 360.0f; if (f >= 180.0f) f -= 360.0f; if (f < -180.0f) f += 360.0f; return f; }
    private static float clampPitch(float v) { return Math.max(-90f, Math.min(90f, v)); }

    /** Visibility-aware resolution: prefer nearest visible matching block; fallback to nearest (even if not visible) if none visible. */
    public static Optional<BlockPos> resolveTargetBlockPreferVisible(Minecraft mc, ActionSchema.Target target, int radius) {
        if (mc.level == null || mc.player == null) return Optional.empty();
        if (!target.hasBlock() || target.hasPos()) {
            return resolveTargetPos(mc, target, radius); // coordinate or original method for pos
        }
        // Gather all matching blocks within radius; classify visible/invisible
        Level level = mc.level;
        Block targetBlock = null; TagKey<Block> tagKey = null;
        if (target.blockId().isPresent()) {
            ResourceLocation id = ResourceLocation.tryParse(target.blockId().get());
            if (id != null) {
                var blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
                var key = ResourceKey.create(Registries.BLOCK, id);
                targetBlock = blocks.get(key).map(ref -> ref.value()).orElse(null);
            }
        }
        if (targetBlock == null && target.blockTag().isPresent()) {
            ResourceLocation tagId = ResourceLocation.tryParse(target.blockTag().get());
            if (tagId != null) tagKey = TagKey.create(Registries.BLOCK, tagId);
        }
        if (targetBlock == null && tagKey == null) return Optional.empty();
        BlockPos playerPos = mc.player.blockPosition();
        int r = Math.max(1, radius);
        double bestVisible = Double.MAX_VALUE; BlockPos bestVisiblePos = null;
        double bestAny = Double.MAX_VALUE; BlockPos bestAnyPos = null;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = playerPos.offset(dx, dy, dz);
                    BlockState st = level.getBlockState(p);
                    if (!stateMatches(st, targetBlock, tagKey)) continue;
                    double d2 = p.distToCenterSqr(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                    if (d2 < bestAny) { bestAny = d2; bestAnyPos = p; }
                    if (isBlockVisible(mc, p)) {
                        if (d2 < bestVisible) { bestVisible = d2; bestVisiblePos = p; }
                    }
                }
            }
        }
        return Optional.ofNullable(bestVisiblePos != null ? bestVisiblePos : bestAnyPos);
    }

    /** Raycast from player's eye to block center; returns true if first collision is the target block. */
    public static boolean isBlockVisible(Minecraft mc, BlockPos pos) {
        if (mc.player == null || mc.level == null) return false;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 target = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3 delta = target.subtract(eye);
        double dist = delta.length();
        if (dist < 0.2) return true;
        Vec3 dir = delta.scale(1.0 / dist);
        double stepLen = 0.2; // sample every 0.2 blocks
        int steps = (int) Math.ceil(dist / stepLen);
        for (int i = 0; i <= steps; i++) {
            double t = Math.min(dist, i * stepLen);
            Vec3 sample = eye.add(dir.scale(t));
            BlockPos bp = new BlockPos((int)Math.floor(sample.x), (int)Math.floor(sample.y), (int)Math.floor(sample.z));
            if (bp.equals(pos)) return true; // reached target without obstruction
            if (!mc.level.getBlockState(bp).isAir()) return false; // obstructed
        }
        return true; // no obstruction encountered
    }
}
