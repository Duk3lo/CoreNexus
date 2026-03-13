package org.astral.core.api;

import org.astral.core.api.curseforge.CurseForgeAPI;
import org.astral.core.api.github.GItHubApi;
import org.astral.core.config.nexus.UpdatesConfig;
import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.astral.core.process.Server;
import org.astral.core.setup.WorkspaceSetup;
import org.astral.core.utility.Parser;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Updater {
    private static Updater instance;
    private ScheduledExecutorService scheduler;

    private Updater() {}

    public static Updater getInstance() {
        if (instance == null) instance = new Updater();
        return instance;
    }

    public void start() {
        if (scheduler != null && !scheduler.isShutdown()) return;

        UpdatesConfig cfg = WorkspaceSetup.getUpdates().getConfig();
        scheduler = Executors.newScheduledThreadPool(3);

        if (cfg.curseforge.enable) {
            long cfTime = Parser.parseTime(cfg.curseforge.check_interval);
            scheduler.scheduleAtFixedRate(() -> {
                Core.atInfo(Log.UPDATER).log("🔄 Auto-Check: CurseForge...");
                CurseForgeAPI.getInstance().syncAll();
            }, 30, cfTime, TimeUnit.MILLISECONDS);
        }

        if (cfg.github.enable) {
            long ghTime = Parser.parseTime(cfg.github.check_interval);
            scheduler.scheduleAtFixedRate(() -> {
                Core.atInfo(Log.UPDATER).log("🔄 Auto-Check: GitHub...");
                GItHubApi.getInstance().syncAll();
            }, 60, ghTime, TimeUnit.MILLISECONDS);
        }

        if (cfg.server.enable) {
            long svTime = Parser.parseTime(cfg.server.check_interval);
            scheduler.scheduleAtFixedRate(() -> {
                Server sv = Server.getInstance();
                if (sv != null && sv.getExecutor().getProcess().isAlive()) {
                    Core.atInfo(Log.UPDATER).log("🔍 Enviando comando de chequeo al servidor...");
                    sv.getExecutor().sendCommand(cfg.server.check_command);
                }
            }, 120, svTime, TimeUnit.MILLISECONDS);
        }
    }

    public void processServerLogForUpdates(String line) {
        UpdatesConfig cfg = WorkspaceSetup.getUpdates().getConfig();
        if (cfg == null || !cfg.server.enable) return;

        if (line.contains(cfg.server.update_trigger_phrase)) {
            Core.atWarning(Log.UPDATER).log("¡Actualización de servidor detectada! Aplicando comando: " + cfg.server.apply_command);
            Server sv = Server.getInstance();
            if (sv != null) {
                sv.getExecutor().sendCommand(cfg.server.apply_command);
            }
        }
    }


}