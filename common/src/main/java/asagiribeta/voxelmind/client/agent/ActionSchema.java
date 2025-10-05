package asagiribeta.voxelmind.client.agent;

import java.util.Objects;
import java.util.Optional;

/**
 * Structured actions produced by the AI agent.
 */
public final class ActionSchema {
    public record Chat(Optional<String> message) {
        public static Chat none() { return new Chat(Optional.empty()); }
    }

    public enum PressType { NONE, TAP, HOLD, RELEASE }

    public record Mouse(PressType left, PressType right) {
        public static Mouse none() { return new Mouse(PressType.NONE, PressType.NONE); }
    }

    /**
     * Old low-level key movement (retained internally for path follower).
     */
    public record Movement(boolean forward, boolean back, boolean left, boolean right,
                           boolean jump, boolean sneak, boolean sprint) {
        public static Movement none() { return new Movement(false, false, false, false, false, false, false); }
    }

    /**
     * High-level navigation request from AI: move relative dx/dy/dz blocks (integers) from current player block position.
     * dy omitted or 0 => stay on same Y; positive = up, negative = down. Pathfinding currently supports step +/-1 increments.
     */
    public record Navigation(Optional<Integer> dx, Optional<Integer> dy, Optional<Integer> dz) {
        public static Navigation none() { return new Navigation(Optional.empty(), Optional.empty(), Optional.empty()); }
        public boolean hasRequest() {
            return (dx.isPresent() && dx.get() != 0) || (dy.isPresent() && dy.get() != 0) || (dz.isPresent() && dz.get() != 0);
        }
        public int dxOrZero() { return dx.orElse(0); }
        public int dyOrZero() { return dy.orElse(0); }
        public int dzOrZero() { return dz.orElse(0); }
    }

    /**
     * View control: prefer absolute yaw/pitch for reliable aiming under latency.
     * yawAbs: -180..180 degrees, pitchAbs: -90..90 degrees. Optionally, yawDelta/pitchDelta as one-shot adjustments.
     */
    public record View(Optional<Float> yawAbs, Optional<Float> pitchAbs,
                       Optional<Float> yawDelta, Optional<Float> pitchDelta) {
        public static View none() {
            return new View(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    public record Target(Optional<Integer> x, Optional<Integer> y, Optional<Integer> z,
                         Optional<String> blockId, Optional<String> blockTag,
                         Optional<String> entityType, Optional<String> entityName) {
        public static Target none() { return new Target(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()); }
        public boolean hasPos() { return x.isPresent() && y.isPresent() && z.isPresent(); }
        public boolean hasEntity() { return entityType.isPresent() || entityName.isPresent(); }
        public boolean hasBlock() { return blockId.isPresent() || blockTag.isPresent() || hasPos(); }
    }

    public record Actions(Chat chat, Navigation navigation, View view, Mouse mouse, Optional<Target> target) {
        public static Actions none() { return new Actions(Chat.none(), Navigation.none(), View.none(), Mouse.none(), Optional.empty()); }

        public Actions {
            Objects.requireNonNull(chat);
            Objects.requireNonNull(navigation);
            Objects.requireNonNull(view);
            Objects.requireNonNull(mouse);
            Objects.requireNonNull(target);
        }
    }
}
