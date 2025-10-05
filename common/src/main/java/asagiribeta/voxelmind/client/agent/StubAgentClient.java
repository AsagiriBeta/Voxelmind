package asagiribeta.voxelmind.client.agent;

import java.time.LocalTime;
import java.util.Optional;

/**
 * Minimal stub that echoes a message and does nothing else.
 */
public final class StubAgentClient implements AgentClient {
    @Override
    public ActionSchema.Actions decide(byte[] screenshotPngBytes, GameContext context, Optional<String> userGoal) {
        String info = "StubAgent tick at " + LocalTime.now().withNano(0) +
                " pos=(" + (int)context.x() + "," + (int)context.y() + "," + (int)context.z() + ")" +
                userGoal.map(g -> " goal=\"" + g + "\"").orElse("");
        return new ActionSchema.Actions(
                new ActionSchema.Chat(Optional.of(info)),
                ActionSchema.Navigation.none(),
                ActionSchema.View.none(),
                ActionSchema.Mouse.none(),
                Optional.empty()
        );
    }
}
