package asagiribeta.voxelmind.client.agent;

import java.util.Optional;

/**
 * Interface for an AI agent backend that takes a screenshot and produces structured actions.
 */
public interface AgentClient {
    ActionSchema.Actions decide(byte[] screenshotPngBytes, GameContext context, Optional<String> userGoal);

    record GameContext(String dimension, double x, double y, double z, float yaw, float pitch, String biome) {}
}
