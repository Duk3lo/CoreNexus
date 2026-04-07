package org.astral.core.file;

import org.astral.core.config.nexus.NexusConfig;
import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.astral.core.process.Server;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class DirectoryWatcher {
    private static final String[] SIZE_UNITS = { "B", "kB", "MB", "GB", "TB" };

    private final Path directory;
    private final Path targetDirectory;
    private volatile boolean running = true;
    private final String threadName;
    private Thread watcherThread;

    private final ConcurrentHashMap<Path, Long> lastKnownSizes = new ConcurrentHashMap<>();
    private final Set<Path> ignoreEvents = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Path, ScheduledFuture<?>> scheduledChecks = new ConcurrentHashMap<>();

    private final String folderName;

    private final NexusConfig mainConfig;
    private final boolean isMainWatcher;
    private final NexusConfig.Watcher config;
    private final Set<String> allowedExtensions;
    private final boolean watchAll;
    private final Set<String> applyActionsOnlyExtensions;

    private final ConcurrentHashMap<Path, Long> lastChangeNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, Long> lastKnownModifiedTimes = new ConcurrentHashMap<>();
    private static final long STABLE_DELAY_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final ScheduledExecutorService scheduler;

    public DirectoryWatcher(@NotNull Path directory,
                            Path targetDirectory,
                            boolean isSource,
                            NexusConfig.@NotNull Watcher config,
                            NexusConfig mainConfig,
                            boolean isMainWatcher,
                            ScheduledExecutorService sharedScheduler) {
        this.directory = directory.toAbsolutePath().normalize();
        this.targetDirectory = targetDirectory;
        this.config = config;
        this.mainConfig = mainConfig;
        this.isMainWatcher = isMainWatcher;
        this.scheduler = sharedScheduler;
        this.folderName = directory.getFileName().toString();
        this.threadName = "Watcher-" + (isSource ? "Src-" : "Dest-") + folderName;

        this.allowedExtensions = new HashSet<>();
        if (config.filter_extensions != null && !config.filter_extensions.isBlank()) {
            String[] parts = config.filter_extensions.toLowerCase().split("\\s+");
            Collections.addAll(allowedExtensions, parts);
        }
        this.watchAll = allowedExtensions.contains(".*") || allowedExtensions.isEmpty();

        this.applyActionsOnlyExtensions = new HashSet<>();
        if (mainConfig != null && mainConfig.apply_Actions_Only != null && !mainConfig.apply_Actions_Only.isBlank()) {
            String[] parts = mainConfig.apply_Actions_Only.toLowerCase().split("\\s+");
            Collections.addAll(applyActionsOnlyExtensions, parts);
        }
    }

    public void start() {
        if (!Files.exists(directory)) {
            Core.atError(Log.WATCHER, folderName).log("Ruta no encontrada: " + directory);
            return;
        }

        Core.atInfo(Log.WATCHER, folderName).log("Vigilante [" + threadName + "] en línea.");
        Core.atInfo(Log.WATCHER, folderName).log("  -> Origen:  " + directory);

        if (targetDirectory != null) {
            String syncType = config.bidirectional_sync ? " (Sincronización Activa)" : " (Solo Envío)";
            Core.atInfo(Log.WATCHER, folderName).log("  -> Destino: " + targetDirectory + syncType);
        } else {
            Core.atInfo(Log.WATCHER, folderName).log("  -> Destino: NO CONFIGURADO");
        }

        Core.atInfo(Log.WATCHER, folderName).log("---------------------------------------------------------");

        if (config.copy_on_start) {
            Core.atInfo(Log.WATCHER, folderName).log("Ejecutando copia inicial (copy_on_start)...");
            boolean cleanDest = isMainWatcher && mainConfig.clearDefaultDestination;
            performInitialSync(cleanDest, true);
        }

        watcherThread = new Thread(this::watchLoop, threadName);
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    public void performInitialSync(boolean clean, boolean sync) {
        if (!Files.exists(directory) || targetDirectory == null || !Files.exists(targetDirectory)) {
            Core.atWarning(Log.WATCHER, folderName).log("  [Aviso] Sincronización inicial cancelada: Origen o Destino no existen.");
            return;
        }

        try {
            if (clean) {
                Core.atInfo(Log.WATCHER, folderName).log("  [Limpieza] Vaciando carpeta destino...");
                Files.walkFileTree(targetDirectory, new SimpleFileVisitor<>() {
                    @Override
                    public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException ignored) {
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) {
                        if (!dir.equals(targetDirectory)) {
                            try {
                                Files.deleteIfExists(dir);
                            } catch (IOException ignored) {
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            if (sync) {
                Core.atInfo(Log.WATCHER, folderName).log("  [Sync] Copiando archivos iniciales...");
                Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                    @Override
                    public @NotNull FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) throws IOException {
                        Path relative = directory.relativize(dir);
                        Path destination = targetDirectory.resolve(relative);
                        if (!Files.exists(destination)) {
                            Files.createDirectories(destination);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public @NotNull FileVisitResult visitFile(@NotNull Path source, @NotNull BasicFileAttributes attrs) {
                        try {
                            Path relative = directory.relativize(source);
                            Path destination = targetDirectory.resolve(relative);

                            Path parent = destination.getParent();
                            if (parent != null && !Files.exists(parent)) {
                                Files.createDirectories(parent);
                            }

                            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            Core.atWarning(Log.WATCHER, folderName).log("Fallo al copiar: " + source.getFileName());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });

                Core.atInfo(Log.WATCHER, folderName).log("  [Sync] Sincronización inicial completada.");
            }
        } catch (IOException e) {
            Core.atError(Log.WATCHER, folderName).log("Error crítico en fase inicial: " + e.getMessage());
        }
    }

    private void watchLoop() {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            registerRecursive(directory, watchService);

            while (running && !Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) {
                    continue;
                }

                Path watchablePath = ((Path) key.watchable()).toAbsolutePath().normalize();

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    Path contextPath = (Path) event.context();
                    Path fullPath = watchablePath.resolve(contextPath).normalize();

                    if (ignoreEvents.remove(fullPath)) {
                        continue;
                    }

                    Path relativePath = directory.relativize(fullPath);

                    if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        handleDeletion(fullPath);
                        continue;
                    }

                    if (isFileAllowed(fullPath)) {
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                            Core.atInfo(Log.WATCHER, folderName).log("[NUEVO] -> " + relativePath);
                            if (config.path_listen_Folders && Files.isDirectory(fullPath)) {
                                registerRecursive(fullPath, watchService);
                            }
                        }
                        checkFileStability(fullPath);
                    } else {
                        Core.atInfo(Log.WATCHER, folderName).log("[IGNORADO] -> " + relativePath + " (Filtro)");
                    }
                }

                if (!key.reset()) {
                    break;
                }
            }
        } catch (Exception e) {
            if (running) {
                Core.atError(Log.WATCHER, folderName).log("Error crítico en el bucle: " + e.getMessage());
            }
        }
    }

    private boolean shouldApplyActions(String fileName) {
        if (!isMainWatcher) {
            return false;
        }
        if (applyActionsOnlyExtensions.isEmpty()) {
            return false;
        }

        String lowerName = fileName.toLowerCase();
        for (String ext : applyActionsOnlyExtensions) {
            if (lowerName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private void handleDeletion(Path fullPath) {
        Path relativePath = directory.relativize(fullPath);

        safeSchedule(() -> {
            if (Files.exists(fullPath)) {
                return;
            }

            Core.atInfo(Log.WATCHER, folderName).log("[ELIMINADO] -> " + relativePath);

            if (targetDirectory != null) {
                Path targetFile = targetDirectory.resolve(relativePath);
                try {
                    if (Files.exists(targetFile)) {
                        if (shouldApplyActions(relativePath.getFileName().toString()) && config.path_safe_delete) {
                            Core.atInfo(Log.WATCHER, folderName).log("Iniciando Safe-Delete para: " + relativePath.getFileName());
                            performSafeDeleteAction(targetFile);
                        } else {
                            Files.deleteIfExists(targetFile);
                            Core.atInfo(Log.WATCHER, folderName).log("Espejo actualizado (Eliminado en servidor): " + relativePath);
                        }
                    }
                } catch (IOException e) {
                    Core.atWarning(Log.WATCHER, folderName).log("Archivo bloqueado. Intentando Safe-Delete forzado...");
                    try {
                        performSafeDeleteAction(targetFile);
                    } catch (IOException ignored) {
                    }
                }
            }
        }, 300, TimeUnit.MILLISECONDS);
    }

    private void checkFileStability(@NotNull Path path) {
        Path absPath = path.toAbsolutePath().normalize();
        Path relativePath = directory.relativize(absPath);

        lastChangeNanos.put(absPath, System.nanoTime());

        ScheduledFuture<?> existing = scheduledChecks.get(absPath);
        if (existing != null && !existing.isDone()) {
            return;
        }

        ScheduledFuture<?> future = safeSchedule(() -> processStabilityCheck(absPath, relativePath), 1, TimeUnit.SECONDS);
        if (future != null) {
            scheduledChecks.put(absPath, future);
        }
    }

    private void processStabilityCheck(@NotNull Path absPath, @NotNull Path relativePath) {
        scheduledChecks.remove(absPath);

        try {
            if (!running || !Files.exists(absPath)) {
                cleanupStabilityState(absPath);
                return;
            }

            long currentSize = getRealSize(absPath);
            long currentModified = Files.getLastModifiedTime(absPath).toMillis();

            Long lastSize = lastKnownSizes.put(absPath, currentSize);
            Long lastModified = lastKnownModifiedTimes.put(absPath, currentModified);
            long lastChange = lastChangeNanos.getOrDefault(absPath, 0L);

            boolean unchanged = lastSize != null
                    && lastModified != null
                    && lastSize == currentSize
                    && lastModified == currentModified;

            boolean quietEnough = (System.nanoTime() - lastChange) >= STABLE_DELAY_NANOS;

            if (unchanged && quietEnough) {
                String type = Files.isDirectory(absPath) ? "DIR " : "FILE";

                Core.atInfo(Log.WATCHER, folderName).log(
                        "✅ [" + type + " ESTABLE] -> " + relativePath + " (" + formatSize(currentSize) + ")"
                );

                executeWatcherLogic(absPath, currentSize);
                cleanupStabilityState(absPath);
            } else {
                Core.atInfo(Log.WATCHER, folderName).update(
                        "[MODIFICADO] -> " + relativePath + " | " + formatSize(currentSize) + "..."
                );

                ScheduledFuture<?> next = safeSchedule(() -> processStabilityCheck(absPath, relativePath), 1, TimeUnit.SECONDS);
                if (next != null) {
                    scheduledChecks.put(absPath, next);
                } else {
                    cleanupStabilityState(absPath);
                }
            }
        } catch (IOException e) {
            cleanupStabilityState(absPath);
        }
    }

    private void cleanupStabilityState(Path path) {
        lastKnownSizes.remove(path);
        lastKnownModifiedTimes.remove(path);
        lastChangeNanos.remove(path);

        ScheduledFuture<?> future = scheduledChecks.remove(path);
        if (future != null) {
            future.cancel(false);
        }
    }

    private @Nullable ScheduledFuture<?> safeSchedule(Runnable task, long delay, TimeUnit unit) {
        if (!running) {
            return null;
        }

        ScheduledExecutorService exec = scheduler;
        if (exec == null || exec.isShutdown() || exec.isTerminated()) {
            return null;
        }

        try {
            return exec.schedule(task, delay, unit);
        } catch (RejectedExecutionException ignored) {
            return null;
        }
    }

    private @NotNull String formatSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }

        if (bytes < 1000) {
            return bytes + " B";
        }

        int unit = 0;
        double value = bytes;

        while (value >= 1000 && unit < SIZE_UNITS.length - 1) {
            value /= 1000.0;
            unit++;
        }

        long scaled = Math.round(value * 100);
        long whole = scaled / 100;
        long frac = scaled % 100;

        return whole + "." + (frac < 10 ? "0" : "") + frac + " " + SIZE_UNITS[unit];
    }

    private long getRealSize(@NotNull Path path) throws IOException {
        if (!Files.exists(path)) {
            return 0L;
        }

        if (Files.isRegularFile(path)) {
            return Files.size(path);
        }

        final long[] total = { 0L };

        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    try {
                        total[0] += Files.size(file);
                    } catch (IOException ignored) {
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return total[0];
    }

    private boolean isFileAllowed(Path path) {
        if (Files.isDirectory(path)) {
            return config.path_listen_Folders;
        }

        if (watchAll) {
            return true;
        }

        String fileName = path.getFileName().toString().toLowerCase();
        for (String ext : allowedExtensions) {
            if (fileName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private void registerRecursive(Path root, WatchService watchService) throws IOException {
        if (!config.path_listen_Folders) {
            root.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
            return;
        }

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) throws IOException {
                dir.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE
                );
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void executeWatcherLogic(Path filePath, long size) {
        if (targetDirectory == null) {
            return;
        }

        Path relativePath = directory.relativize(filePath.toAbsolutePath().normalize());
        Path targetPath = targetDirectory.resolve(relativePath);

        try {
            ignoreEvents.add(targetPath);

            Path parentDir = targetPath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            if (shouldApplyActions(filePath.getFileName().toString())
                    && mainConfig != null
                    && mainConfig.actions != null
                    && !mainConfig.actions.isEmpty()) {

                Core.atInfo(Log.WATCHER, folderName).log("Ejecutando secuencia de acciones para: " + filePath.getFileName());

                for (NexusConfig.ActionType action : mainConfig.actions) {
                    performAction(action, filePath, targetPath);
                }

                Core.atInfo(Log.WATCHER, folderName).log("Secuencia completada con éxito (" + formatSize(size) + ") -> " + relativePath);
            } else {
                if (Files.exists(targetPath) && Files.size(filePath) == Files.size(targetPath)) {
                    return;
                }

                Files.copy(filePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                Core.atInfo(Log.WATCHER, folderName).log("Copiado (" + formatSize(size) + ") -> " + relativePath);
            }

            safeSchedule(() -> ignoreEvents.remove(targetPath), 2, TimeUnit.SECONDS);

        } catch (IOException e) {
            ignoreEvents.remove(targetPath);
            Core.atError(Log.WATCHER, folderName).log("Error de copia en " + relativePath);
        }
    }

    private void performAction(NexusConfig.@NotNull ActionType action, Path source, Path target) {
        try {
            switch (action) {
                case STOP_SERVER -> {
                    if (Server.getInstance() != null) {
                        Core.atInfo(Log.WATCHER, folderName).log("  [Acción] Apagando servidor...");
                        Server.getInstance().stopServer();
                    }
                }
                case DELETE -> {
                    Core.atInfo(Log.WATCHER, folderName).log("  [Acción] Eliminando versión antigua en destino...");
                    Files.deleteIfExists(target);
                }
                case COPY -> {
                    Core.atInfo(Log.WATCHER, folderName).log("  [Acción] Copiando nueva versión al servidor...");
                    Path parent = target.getParent();
                    if (parent != null && !Files.exists(parent)) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                case START_SERVER -> {
                    Core.atInfo(Log.WATCHER, folderName).log("  [Acción] Iniciando servidor nuevamente...");
                    var cfg = org.astral.core.setup.WorkspaceSetup.getNexus().getConfig();
                    if (cfg != null) {
                        Server.startServer(cfg.server_path, cfg.jar_name, cfg.args);
                    }
                }
            }
        } catch (IOException e) {
            Core.atError(Log.WATCHER, folderName).log("Fallo al ejecutar la acción " + action.name() + ": " + e.getMessage());
        }
    }

    private void performSafeDeleteAction(@NotNull Path target) throws IOException {
        if (Server.getInstance() != null) {
            Core.atInfo(Log.WATCHER, folderName).log("  [Safe-Delete] Apagando servidor...");
            Server.getInstance().stopServer();
        }

        Core.atInfo(Log.WATCHER, folderName).log("  [Safe-Delete] Eliminando archivo antiguo...");
        Files.deleteIfExists(target);

        Core.atInfo(Log.WATCHER, folderName).log("  [Safe-Delete] Reiniciando servidor...");
        var cfg = org.astral.core.setup.WorkspaceSetup.getNexus().getConfig();
        if (cfg != null) {
            Server.startServer(cfg.server_path, cfg.jar_name, cfg.args);
        }

        Core.atInfo(Log.WATCHER, folderName).log("  [Safe-Delete] Proceso completado.");
    }

    public void stop() {
        Core.atInfo(Log.WATCHER, folderName).log("Deteniendo vigilante [" + threadName + "]...");
        this.running = false;

        if (watcherThread != null) {
            watcherThread.interrupt();
            Core.atInfo(Log.WATCHER, folderName).log("  -> Hilo de monitoreo interrumpido.");
        }

        for (ScheduledFuture<?> future : scheduledChecks.values()) {
            future.cancel(false);
        }
        scheduledChecks.clear();
        lastKnownSizes.clear();
        ignoreEvents.clear();

        Core.atInfo(Log.WATCHER, folderName).log(
                "Vigilante [" + threadName + "] fuera de línea."
        );
    }
}