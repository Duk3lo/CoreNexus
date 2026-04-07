package org.astral.core.api;

import org.astral.core.api.curseforge.CurseForgeAPI;
import org.astral.core.api.github.GItHubApi;
import org.astral.core.config.nexus.UpdatesConfig;
import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.astral.core.process.Server;
import org.astral.core.setup.WorkspaceSetup;
import org.astral.core.utility.Parser;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Updater {

    private static volatile Updater instance;

    private final ScheduledThreadPoolExecutor scheduler;

    private volatile ScheduledFuture<?> curseForgeTask;
    private volatile ScheduledFuture<?> githubTask;
    private volatile ScheduledFuture<?> serverTask;

    private final AtomicBoolean isDownloadingUpdate = new AtomicBoolean(false);

    private Updater() {
        this.scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "Updater-Thread");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    public static Updater getInstance() {
        Updater local = instance;
        if (local == null) {
            synchronized (Updater.class) {
                local = instance;
                if (local == null) {
                    local = new Updater();
                    instance = local;
                }
            }
        }
        return local;
    }

    public synchronized void start() {
        UpdatesConfig cfg = WorkspaceSetup.getUpdates().getConfig();
        if (cfg == null) return;

        updateCurseForgeTask(cfg.curseforge.enable);
        updateGitHubTask(cfg.github.enable);
        updateServerTask(cfg.server.enable_periodic_check);
    }

    public synchronized void updateCurseForgeTask(boolean enable) {
        if (!enable) {
            if (curseForgeTask != null) {
                curseForgeTask.cancel(false);
                curseForgeTask = null;
                Core.atInfo(Log.UPDATER).log("Auto-Check CurseForge detenido.");
            }
            return;
        }

        if (curseForgeTask != null && !curseForgeTask.isDone()) return;

        UpdatesConfig cfg = WorkspaceSetup.getUpdates().getConfig();
        if (cfg == null) return;

        long timeMs = Parser.parseTime(cfg.curseforge.check_interval);
        if (timeMs <= 0) timeMs = TimeUnit.MINUTES.toMillis(30);

        curseForgeTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                Core.atInfo(Log.UPDATER).log("Auto-Check: CurseForge...");
                CurseForgeAPI.getInstance().syncAll();
            } catch (Throwable t) {
                Core.atError(Log.UPDATER).log("Error en auto-check CurseForge: " + t.getMessage());
            }
        }, 10_000L, timeMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void updateGitHubTask(boolean enable) {
        if (!enable) {
            if (githubTask != null) {
                githubTask.cancel(false);
                githubTask = null;
                Core.atInfo(Log.UPDATER).log("Auto-Check GitHub detenido.");
            }
            return;
        }

        if (githubTask != null && !githubTask.isDone()) return;

        UpdatesConfig cfg = WorkspaceSetup.getUpdates().getConfig();
        if (cfg == null) return;

        long timeMs = Parser.parseTime(cfg.github.check_interval);
        if (timeMs <= 0) timeMs = TimeUnit.MINUTES.toMillis(30);

        githubTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                Core.atInfo(Log.UPDATER).log("Auto-Check: GitHub...");
                GItHubApi.getInstance().syncAll();
            } catch (Throwable t) {
                Core.atError(Log.UPDATER).log("Error en auto-check GitHub: " + t.getMessage());
            }
        }, 12_000L, timeMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void updateServerTask(boolean enable) {
        if (!enable) {
            if (serverTask != null) {
                serverTask.cancel(false);
                serverTask = null;
                Core.atInfo(Log.UPDATER).log("Auto-Check Server detenido.");
            }
            return;
        }

        if (serverTask != null && !serverTask.isDone()) return;

        UpdatesConfig cfg = WorkspaceSetup.getUpdates().getConfig();
        if (cfg == null) return;

        long timeMs = Parser.parseTime(cfg.server.check_interval);
        if (timeMs <= 0) timeMs = TimeUnit.MINUTES.toMillis(30);

        serverTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                Server sv = Server.getInstance();
                if (sv != null && sv.getExecutor() != null && sv.getExecutor().getProcess() != null && sv.getExecutor().getProcess().isAlive()) {
                    Core.atInfo(Log.UPDATER).log("Enviando comando de chequeo al servidor...");
                    sv.getExecutor().sendCommand(cfg.server.check_command);
                }
            } catch (Throwable t) {
                Core.atError(Log.UPDATER).log("Error en auto-check Server: " + t.getMessage());
            }
        }, 15_000L, timeMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (curseForgeTask != null) {
            curseForgeTask.cancel(false);
            curseForgeTask = null;
        }
        if (githubTask != null) {
            githubTask.cancel(false);
            githubTask = null;
        }
        if (serverTask != null) {
            serverTask.cancel(false);
            serverTask = null;
        }
        scheduler.shutdownNow();
    }

    public synchronized void restart() {
        Core.atWarning(Log.UPDATER).log("Reiniciando...");
        stop();
        if (!scheduler.isShutdown()) {
            return;
        }
        instance = new Updater();
        instance.start();
    }

    public void processServerLogForUpdates(String line) {
        UpdatesConfig cfg = WorkspaceSetup.getUpdates().getConfig();
        if (cfg == null || !cfg.server.enable_console_listener) return;
        if (line == null || line.isBlank()) return;
        if (line.contains("Console executed command:")) return;

        Server sv = Server.getInstance();
        if (sv == null || sv.getExecutor() == null) return;

        if (!isDownloadingUpdate.get()) {
            if (line.contains(cfg.server.trigger_update_found)
                    && !line.toLowerCase().contains("already running the latest version")) {
                if (isDownloadingUpdate.compareAndSet(false, true)) {
                    Core.atWarning(Log.UPDATER).log("¡Actualización de servidor detectada! Iniciando descarga...");
                    sv.getExecutor().sendCommand(cfg.server.download_command);
                }
            }
            return;
        }

        for (String trigger : cfg.server.trigger_download_complete) {
            if (line.contains(trigger)) {
                Core.atWarning(Log.UPDATER).log("¡Descarga completada al 100%! Aplicando actualización y reiniciando...");
                sv.getExecutor().sendCommand(cfg.server.apply_command);
                isDownloadingUpdate.set(false);
                break;
            }
        }
    }
}