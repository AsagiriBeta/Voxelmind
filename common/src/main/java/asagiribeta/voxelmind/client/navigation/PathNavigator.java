package asagiribeta.voxelmind.client.navigation;

import asagiribeta.voxelmind.client.agent.ActionSchema;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * PathNavigator (advanced): A* pathfinder supporting:
 *  - Walking & horizontal movement
 *  - 1-block step up (auto jump) / 1-block step down (sneak descent)
 *  - Single gap jump across a 1-block hole
 *  - Swimming (6-directional in water) & exiting water to land
 *  - Pure vertical handling inside water via ascend/descend
 *
 * Limitations (future work): multi-block climbs, ladders, diagonal heuristics, better passable blocks, cached planning.
 */
public final class PathNavigator {

    private enum Mode { WALK, SWIM }
    private record Node(BlockPos pos, Mode mode) {}

    private static final class AStarNode implements Comparable<AStarNode> {
        final Node node; final AStarNode parent; final double g; final double f; final StepType stepType;
        AStarNode(Node node, AStarNode parent, double g, double f, StepType type) { this.node=node; this.parent=parent; this.g=g; this.f=f; this.stepType=type; }
        @Override public int compareTo(AStarNode o) { return Double.compare(this.f, o.f); }
    }

    private enum StepType { WALK, STEP_UP, STEP_DOWN, JUMP_GAP, SWIM, SWIM_ASCEND, SWIM_DESCEND }

    public record StepOutput(ActionSchema.Movement movement, Optional<ActionSchema.View> view) {}

    private List<AStarNode> planned = null;
    private int execIndex = 0;
    private int jumpTicksRemaining = 0;

    public boolean isActive() { return planned != null && execIndex < planned.size(); }
    public void cancel() { planned = null; execIndex = 0; jumpTicksRemaining = 0; }

    public void start(LocalPlayer player, ActionSchema.Navigation nav) {
        cancel();
        if (!nav.hasRequest()) return;
        BlockPos start = player.blockPosition();
        BlockPos goal = start.offset(nav.dxOrZero(), nav.dyOrZero(), nav.dzOrZero());
        planned = plan(player.level(), start, goal, 64);
        execIndex = 0;
    }

    public StepOutput produceStep(LocalPlayer player) {
        if (!isActive()) return null;
        if (execIndex >= planned.size()) { cancel(); return null; }
        AStarNode current = planned.get(execIndex);
        if (atNode(player, current.node.pos())) {
            execIndex++; jumpTicksRemaining = 0;
            if (execIndex >= planned.size()) { cancel(); return null; }
            current = planned.get(execIndex);
        }
        BlockPos target = current.node.pos();
        double cx = target.getX() + 0.5, cz = target.getZ() + 0.5;
        double dx = cx - player.getX(); double dz = cz - player.getZ();
        float yaw = (float)(Math.atan2(-dx, dz) * 180.0 / Math.PI);
        Optional<ActionSchema.View> view = Optional.of(new ActionSchema.View(Optional.of(yaw), Optional.empty(), Optional.empty(), Optional.empty()));

        boolean forward = true; boolean jump = false; boolean sneak = false;
        switch (current.stepType) {
            case STEP_UP, JUMP_GAP -> {
                if (jumpTicksRemaining == 0) { jump = true; jumpTicksRemaining = 5; } else { jumpTicksRemaining--; }
            }
            case STEP_DOWN -> sneak = true;
            case SWIM -> { /* forward only */ }
            case SWIM_ASCEND -> { jump = true; }
            case SWIM_DESCEND -> { sneak = true; }
            case WALK -> { /* default forward */ }
        }
        ActionSchema.Movement mv = new ActionSchema.Movement(forward, false, false, false, jump, sneak, false);
        return new StepOutput(mv, view);
    }

    private boolean atNode(LocalPlayer player, BlockPos pos) {
        double r2 = 0.35 * 0.35;
        double dx = (pos.getX() + 0.5) - player.getX();
        double dz = (pos.getZ() + 0.5) - player.getZ();
        double dy = pos.getY() - Math.floor(player.getY());
        return (dx*dx + dz*dz) < r2 && Math.abs(dy) <= 1.1;
    }

    // ---------------- Planning (A*) ----------------
    private List<AStarNode> plan(Level level, BlockPos start, BlockPos goal, int limitRadius) {
        if (start.equals(goal)) return List.of();
        int maxNodes = 15000;
        PriorityQueue<AStarNode> open = new PriorityQueue<>();
        Map<BlockPos, AStarNode> best = new HashMap<>();
        Node startNode = new Node(start, modeFor(level, start));
        AStarNode startA = new AStarNode(startNode, null, 0, heuristic(start, goal), StepType.WALK);
        open.add(startA); best.put(start, startA);

        int minX = Math.min(start.getX(), goal.getX()) - limitRadius;
        int maxX = Math.max(start.getX(), goal.getX()) + limitRadius;
        int minY = Math.min(start.getY(), goal.getY()) - 8;
        int maxY = Math.max(start.getY(), goal.getY()) + 8;
        int minZ = Math.min(start.getZ(), goal.getZ()) - limitRadius;
        int maxZ = Math.max(start.getZ(), goal.getZ()) + limitRadius;

        while (!open.isEmpty() && best.size() < maxNodes) {
            AStarNode cur = open.poll();
            if (cur.node.pos.equals(goal)) return reconstruct(cur);
            for (AStarNode nxt : expand(level, cur, goal, minX, maxX, minY, maxY, minZ, maxZ)) {
                AStarNode existing = best.get(nxt.node.pos());
                if (existing == null || nxt.g < existing.g) { best.put(nxt.node.pos(), nxt); open.add(nxt); }
            }
        }
        return Collections.emptyList();
    }

    private List<AStarNode> reconstruct(AStarNode goal) {
        LinkedList<AStarNode> list = new LinkedList<>();
        AStarNode cur = goal;
        while (cur != null && cur.parent != null) { list.addFirst(cur); cur = cur.parent; }
        return list;
    }

    private double heuristic(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX());
        double dy = Math.abs(a.getY() - b.getY());
        double dz = Math.abs(a.getZ() - b.getZ());
        return dx + dz + dy * 1.2; // slight vertical penalty
    }

    private List<AStarNode> expand(Level level, AStarNode cur, BlockPos goal,
                                   int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        if (cur.node.mode == Mode.SWIM) return expandSwim(level, cur, goal, minX, maxX, minY, maxY, minZ, maxZ);
        return expandWalk(level, cur, goal, minX, maxX, minY, maxY, minZ, maxZ);
    }

    private List<AStarNode> expandWalk(Level level, AStarNode cur, BlockPos goal,
                                       int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        List<AStarNode> out = new ArrayList<>(16);
        BlockPos pos = cur.node.pos();
        for (int[] d : CARDINAL) {
            BlockPos same = pos.offset(d[0], 0, d[1]);
            if (!within(same, minX, maxX, minY, maxY, minZ, maxZ)) continue;
            if (isWalkable(level, same)) {
                add(out, cur, same, Mode.WALK, StepType.WALK, costWalk(pos, same), goal);
            } else {
                BlockPos up = pos.offset(d[0], 1, d[1]);
                if (isWalkable(level, up) && isPassable(level, up) && isPassable(level, up.above()))
                    add(out, cur, up, Mode.WALK, StepType.STEP_UP, costStepUp(pos, up), goal);
            }
            BlockPos down = pos.offset(d[0], -1, d[1]);
            if (within(down, minX, maxX, minY, maxY, minZ, maxZ) && isWalkable(level, down))
                add(out, cur, down, Mode.WALK, StepType.STEP_DOWN, costStepDown(pos, down), goal);
            BlockPos gapMid = pos.offset(d[0], 0, d[1]);
            BlockPos gapLand = pos.offset(d[0]*2, 0, d[1]*2);
            if (within(gapLand, minX, maxX, minY, maxY, minZ, maxZ) && isAirColumn(level, gapMid) && isWalkable(level, gapLand))
                add(out, cur, gapLand, Mode.WALK, StepType.JUMP_GAP, costJump(pos, gapLand), goal);
            if (isWater(level, same)) add(out, cur, same, Mode.SWIM, StepType.SWIM, costSwim(pos, same), goal);
        }
        return out;
    }

    private List<AStarNode> expandSwim(Level level, AStarNode cur, BlockPos goal,
                                       int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        List<AStarNode> out = new ArrayList<>(16);
        BlockPos pos = cur.node.pos();
        for (int[] d : SWIM_DIRS) {
            BlockPos np = pos.offset(d[0], d[1], d[2]);
            if (!within(np, minX, maxX, minY, maxY, minZ, maxZ)) continue;
            if (isWater(level, np)) {
                StepType t = d[1] > 0 ? StepType.SWIM_ASCEND : d[1] < 0 ? StepType.SWIM_DESCEND : StepType.SWIM;
                add(out, cur, np, Mode.SWIM, t, costSwim(pos, np), goal);
            } else if (isWalkable(level, np)) {
                add(out, cur, np, Mode.WALK, StepType.WALK, costExitWater(pos, np), goal);
            }
        }
        return out;
    }

    // Cost model
    private double costWalk(BlockPos a, BlockPos b) { return 1.0; }
    private double costStepUp(BlockPos a, BlockPos b) { return 1.3; }
    private double costStepDown(BlockPos a, BlockPos b) { return 1.05; }
    private double costJump(BlockPos a, BlockPos b) { return 1.8; }
    private double costSwim(BlockPos a, BlockPos b) { return 1.4; }
    private double costExitWater(BlockPos a, BlockPos b) { return 1.6; }

    private void add(List<AStarNode> out, AStarNode parent, BlockPos pos, Mode mode, StepType type, double stepCost, BlockPos goal) {
        double g = parent.g + stepCost;
        double f = g + heuristic(pos, goal);
        out.add(new AStarNode(new Node(pos, mode), parent, g, f, type));
    }

    // Environment predicates
    private boolean within(BlockPos p, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        return p.getX()>=minX && p.getX()<=maxX && p.getY()>=minY && p.getY()<=maxY && p.getZ()>=minZ && p.getZ()<=maxZ;
    }
    private boolean isWalkable(Level level, BlockPos feet) {
        if (!isPassable(level, feet) || !isPassable(level, feet.above())) return false;
        BlockState below = level.getBlockState(feet.below());
        return !below.isAir() && !below.getCollisionShape(level, feet.below()).isEmpty();
    }
    private boolean isPassable(Level level, BlockPos p) { return level.getBlockState(p).isAir(); }
    private boolean isAirColumn(Level level, BlockPos p) { return isPassable(level, p) && isPassable(level, p.above()); }
    private boolean isWater(Level level, BlockPos p) { return level.getFluidState(p).is(FluidTags.WATER); }
    private Mode modeFor(Level level, BlockPos p) { return isWater(level, p) ? Mode.SWIM : Mode.WALK; }

    private static final int[][] CARDINAL = { {1,0}, {-1,0}, {0,1}, {0,-1} };
    private static final int[][] SWIM_DIRS = { {1,0,0}, {-1,0,0}, {0,0,1}, {0,0,-1}, {0,1,0}, {0,-1,0} };
}
