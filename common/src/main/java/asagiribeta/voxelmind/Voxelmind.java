package asagiribeta.voxelmind;

import asagiribeta.voxelmind.client.ClientInit;
import asagiribeta.voxelmind.config.Config;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;

public final class Voxelmind {
    public static final String MOD_ID = "voxelmind";

    public static void init() {
        // Load config (creates default file on first run)
        Config.load();
        // Client-only setup
        EnvExecutor.runInEnv(Env.CLIENT, () -> () -> ClientInit.init());
    }
}
