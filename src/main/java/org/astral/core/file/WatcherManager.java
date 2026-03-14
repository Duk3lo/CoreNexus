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

public class WatcherManager {
    private static WatcherManager instance;
    private final Map<Path, DirectoryWatcher> watchers = new ConcurrentHashMap<>();

    private WatcherManager() {}

    public static WatcherManager getInstance() {
        if (instance == null) instance = new WatcherManager();
        return instance;
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
        boolean isMainWatcher = watcherName.equals(WorkspaceSetup.getDefaultWatchPrefix());
        Path sourcePath = WorkspaceSetup.resolve(config.path);
        Path destPath = WorkspaceSetup.resolve(config.path_destination);

        if (Files.exists(sourcePath)) {
            watchers.computeIfAbsent(sourcePath, k -> {
                Core.atInfo(Log.WATCHER).log("Iniciando vigilancia ORIGEN: " + k.getFileName());
                DirectoryWatcher watcher = new DirectoryWatcher(k, destPath, true, config, mainConfig, isMainWatcher);
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
                    DirectoryWatcher syncWatcher = new DirectoryWatcher(k, sourcePath, false, config, mainConfig, false);
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
    }
}