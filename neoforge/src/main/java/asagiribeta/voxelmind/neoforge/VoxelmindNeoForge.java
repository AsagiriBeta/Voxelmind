package asagiribeta.voxelmind.neoforge;

import asagiribeta.voxelmind.Voxelmind;
import asagiribeta.voxelmind.neoforge.client.VMNeoForgeClientCommands;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(Voxelmind.MOD_ID)
public final class VoxelmindNeoForge {
    public VoxelmindNeoForge() {
        // Run our common setup; client init is handled via EnvExecutor in common.
        Voxelmind.init();
        // Register client commands
        NeoForge.EVENT_BUS.addListener(VMNeoForgeClientCommands::onRegisterClientCommands);
    }
}
