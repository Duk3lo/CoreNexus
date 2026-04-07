package org.astral.core.file;

import org.astral.core.config.nexus.NexusConfig;
import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.astral.core.setup.WorkspaceSetup;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class WatcherManager {
    private static volatile WatcherManager instance;

    private final Map<Path, DirectoryWatcher> watchers = new ConcurrentHashMap<>();
    private volatile ScheduledThreadPoolExecutor globalScheduler;

    private WatcherManager() {
        createScheduler();
    }

    public static WatcherManager getInstance() {
        WatcherManager local = instance;
        if (local == null) {
            synchronized (WatcherManager.class) {
                local = instance;
                if (local == null) {
                    local = new WatcherManager();
                    instance = local;
                }
            }
        }
        return local;
    }

    private synchronized void createScheduler() {
        if (globalScheduler != null && !globalScheduler.isShutdown()) {
            return;
        }

        globalScheduler = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "Watcher-Scheduler");
            t.setDaemon(true);
            return t;
        });
        globalScheduler.setRemoveOnCancelPolicy(true);
    }

    private ScheduledThreadPoolExecutor getScheduler() {
        ScheduledThreadPoolExecutor scheduler = globalScheduler;
        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
            synchronized (this) {
                scheduler = globalScheduler;
                if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
                    createScheduler();
                    scheduler = globalScheduler;
                }
            }
        }
        return scheduler;
    }

    public synchronized void restartScheduler() {
        shutdownScheduler();
        createScheduler();
    }

    private void shutdownScheduler() {
        ScheduledThreadPoolExecutor scheduler = globalScheduler;
        if (scheduler == null) {
            return;
        }

        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    public void addWatcher(String watcherName, NexusConfig.@NotNull Watcher config) {
        if (!config.enable) {
            return;
        }

        if (config.path == null || config.path.trim().isEmpty()) {
            Core.atWarning(Log.WATCHER).log("Ruta de origen vacía en la configuración. Ignorado.");
            return;
        }

        NexusConfig mainConfig = WorkspaceSetup.getNexus().getConfig();
        if (mainConfig == null) {
            Core.atWarning(Log.WATCHER).log("Configuración principal no disponible. Ignorado.");
            return;
        }

        boolean isMainWatcher = watcherName.equals(WorkspaceSetup.getDefaultWatchPrefix());
        Path sourcePath = WorkspaceSetup.resolve(config.path);
        Path destPath = WorkspaceSetup.resolve(config.path_destination);

        ScheduledThreadPoolExecutor scheduler = getScheduler();

        if (Files.exists(sourcePath)) {
            watchers.computeIfAbsent(sourcePath, k -> {
                Core.atInfo(Log.WATCHER).log("Iniciando vigilancia ORIGEN: " + k.getFileName());
                DirectoryWatcher watcher = new DirectoryWatcher(
                        k, destPath, true, config, mainConfig, isMainWatcher, scheduler
                );
                watcher.start();
                return watcher;
            });
        } else {
            Core.atError(Log.WATCHER).log("La ruta de origen no existe: " + sourcePath);
        }

        if (config.bidirectional_sync && destPath != null) {
            if (Files.exists(destPath)) {
                watchers.computeIfAbsent(destPath, k -> {
                    Core.atInfo(Log.WATCHER).log("Iniciando vigilancia ESPEJO (Sync): " + k.getFileName());
                    DirectoryWatcher syncWatcher = new DirectoryWatcher(
                            k, sourcePath, false, config, mainConfig, false, scheduler
                    );
                    syncWatcher.start();
                    return syncWatcher;
                });
            } else {
                Core.atWarning(Log.WATCHER).log("No se pudo iniciar Sync: La ruta destino no existe físicamente: " + destPath);
            }
        }
    }

    public void removeWatcher(String pathStr) {
        Path p = Path.of(pathStr).toAbsolutePath().normalize();
        DirectoryWatcher dw = watchers.remove(p);
        if (dw != null) {
            dw.stop();
            Core.atInfo(Log.WATCHER).log("Vigilante en " + pathStr + " detenido con éxito.");
        }
    }

    public void stopAll() {
        watchers.values().forEach(DirectoryWatcher::stop);
        watchers.clear();
        shutdownScheduler();
    }

    public void resetAfterReload() {
        restartScheduler();
    }
}