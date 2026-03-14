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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Updater {
    private static Updater instance;
    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> curseForgeTask;
    private ScheduledFuture<?> githubTask;
    private ScheduledFuture<?> serverTask;

    private boolean isDownloadingUpdate = false;

    private Updater() {
        this.scheduler = Executors.newScheduledThreadPool(3);
    }

    public static Updater getInstance() {
        if (instance == null) instance = new Updater();
        return instance;
    }

    public void start() {
        UpdatesConfig cfg = WorkspaceSetup.getUpdates().getConfig();
        if (cfg == null) return;

        updateCurseForgeTask(cfg.curseforge.enable);
        updateGitHubTask(cfg.github.enable);
        updateServerTask(cfg.server.enable_periodic_check);
    }

    public void updateCurseForgeTask(boolean enable) {
        if (enable) {
            if (curseForgeTask == null || curseForgeTask.isDone()) {
                UpdatesConfig cfg = WorkspaceSetup.getUpdates().getConfig();
                long timeMs = Parser.parseTime(cfg.curseforge.check_interval);

                curseForgeTask = scheduler.scheduleAtFixedRate(() -> {
                    Core.atInfo(Log.UPDATER).log("Auto-Check: CurseForge...");
                    CurseForgeAPI.getInstance().syncAll();
                }, 10000, timeMs, TimeUnit.MILLISECONDS);
            }
        } else if (curseForgeTask != null) {
            curseForgeTask.cancel(false);
            curseForgeTask = null;
            Core.atInfo(Log.UPDATER).log("Auto-Check CurseForge detenido.");
        }
    }

    public void updateGitHubTask(boolean enable) {
        if (enable) {
            if (githubTask == null || githubTask.isDone()) {
                UpdatesConfig cfg = WorkspaceSetup.getUpdates().getConfig();
                long timeMs = Parser.parseTime(cfg.github.check_interval);

                githubTask = scheduler.scheduleAtFixedRate(() -> {
                    Core.atInfo(Log.UPDATER).log("Auto-Check: GitHub...");
                    GItHubApi.getInstance().syncAll();
                }, 12000, timeMs, TimeUnit.MILLISECONDS);
            }
        } else if (githubTask != null) {
            githubTask.cancel(false);
            githubTask = null;
            Core.atInfo(Log.UPDATER).log("Auto-Check GitHub detenido.");
        }
    }

    public void updateServerTask(boolean enable) {
        if (enable) {
            if (serverTask == null || serverTask.isDone()) {
                UpdatesConfig cfg = WorkspaceSetup.getUpdates().getConfig();
                long timeMs = Parser.parseTime(cfg.server.check_interval);

                serverTask = scheduler.scheduleAtFixedRate(() -> {
                    Server sv = Server.getInstance();
                    if (sv != null && sv.getExecutor().getProcess().isAlive()) {
                        Core.atInfo(Log.UPDATER).log("Enviando comando de chequeo al servidor...");
                        sv.getExecutor().sendCommand(cfg.server.check_command);
                    }
                }, 15000, timeMs, TimeUnit.MILLISECONDS);
            }
        } else if (serverTask != null) {
            serverTask.cancel(false);
            serverTask = null;
            Core.atInfo(Log.UPDATER).log("Auto-Check Server detenido.");
        }
    }

    public void stop() {
        updateCurseForgeTask(false);
        updateGitHubTask(false);
        updateServerTask(false);
    }

    public void restart() {
        Core.atWarning(Log.UPDATER).log("Reiniciando...");
        stop();
        start();
    }

    public void processServerLogForUpdates(String line) {
        UpdatesConfig cfg = WorkspaceSetup.getUpdates().getConfig();
        if (cfg == null || !cfg.server.enable_console_listener) return;
        if (line.contains("Console executed command:")) return;

        Server sv = Server.getInstance();
        if (sv == null) return;

        if (!isDownloadingUpdate) {
            if (line.contains(cfg.server.trigger_update_found) && !line.toLowerCase().contains("already running the latest version")) {
                Core.atWarning(Log.UPDATER).log("¡Actualización de servidor detectada! Iniciando descarga...");
                sv.getExecutor().sendCommand(cfg.server.download_command);
                isDownloadingUpdate = true;
            }
        } else {
            boolean downloadFinished = false;
            for (String trigger : cfg.server.trigger_download_complete) {
                if (line.contains(trigger)) { downloadFinished = true; break; }
            }
            if (downloadFinished) {
                Core.atWarning(Log.UPDATER).log("¡Descarga completada al 100%! Aplicando actualización y reiniciando...");
                sv.getExecutor().sendCommand(cfg.server.apply_command);
                isDownloadingUpdate = false;
            }
        }
    }
}