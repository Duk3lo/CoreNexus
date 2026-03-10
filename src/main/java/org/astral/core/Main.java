package org.astral.core;

import org.astral.core.command.CommandTerminal;

import org.astral.core.config.nexus.NexusConfig;
import org.astral.core.file.WatcherManager;
import org.astral.core.process.Server;
import org.astral.core.setup.WorkspaceSetup;

public final class Main {

    static void main() {
        WorkspaceSetup.init();
        CommandTerminal.getInstance().startListening();
        NexusConfig cfg = WorkspaceSetup.getNexus().getConfig();
        if (cfg == null) return;
        if (cfg.watchers != null) cfg.watchers.values().forEach(watcher -> WatcherManager.getInstance().addWatcher(watcher));
        Server.startServer(cfg.server_path, cfg.jar_name, cfg.args);
    }
}
