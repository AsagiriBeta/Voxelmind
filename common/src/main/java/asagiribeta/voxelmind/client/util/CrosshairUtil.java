package asagiribeta.voxelmind.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Optional;

public final class CrosshairUtil {
    public record CrosshairInfo(boolean isBlock, boolean isLog, BlockPos pos, double distance) {}

    private CrosshairUtil() {}

    public static Optional<CrosshairInfo> getCrosshairInfo(Minecraft mc) {
        HitResult hr = mc.hitResult;
        if (!(hr instanceof BlockHitResult bhr)) return Optional.empty();
        BlockPos pos = bhr.getBlockPos();
        boolean isLog = mc.level != null && mc.level.getBlockState(pos).is(BlockTags.LOGS);
        double dist = bhr.getLocation().distanceTo(mc.player.getEyePosition());
        return Optional.of(new CrosshairInfo(true, isLog, pos, dist));
    }
}

