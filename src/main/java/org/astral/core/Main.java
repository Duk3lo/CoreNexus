package org.astral.core;

import org.astral.core.api.curseforge.CurseForgeAPI;
import org.astral.core.command.CommandTerminal;
import org.astral.core.config.nexus.NexusConfig;
import org.astral.core.file.DirectoryWatcher;
import org.astral.core.file.WatcherManager;
import org.astral.core.api.github.GItHubApi;
import org.astral.core.healing.HealthMonitor;
import org.astral.core.process.Server;
import org.astral.core.setup.WorkspaceSetup;


public final class Main {

    static void main() {
        WorkspaceSetup.init();
        CommandTerminal.getInstance().startListening();
        HealthMonitor.getInstance().start();
        CurseForgeAPI.getInstance().syncAll();
        GItHubApi.getInstance().syncAll();
        NexusConfig cfg = WorkspaceSetup.getNexus().getConfig();
        if (cfg == null) return;
        if (cfg.watchers != null) {
            cfg.watchers.values().forEach(w -> WatcherManager.getInstance().addWatcher(w));
            NexusConfig.Watcher defaultW = cfg.watchers.get(WorkspaceSetup.getDefaultWatchPrefix());
            if (defaultW != null) {
                DirectoryWatcher dw = WatcherManager.getInstance().getWatcher(defaultW);
                if (dw != null) {
                    dw.performInitialSync(cfg.clearDefaultDestination, cfg.defaultPaste);
                }
            }
        }
        Server.startServer(cfg.server_path, cfg.jar_name, cfg.args);
    }
}
