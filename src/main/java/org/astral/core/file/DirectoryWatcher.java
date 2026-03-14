package org.astral.core.file;

import org.astral.core.config.nexus.NexusConfig;
import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.astral.core.process.Server;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

public class DirectoryWatcher {
    private final Path directory;
    private final Path targetDirectory;
    private volatile boolean running = true;
    private final String threadName;
    private Thread watcherThread;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final ConcurrentHashMap<Path, Long> lastKnownSizes = new ConcurrentHashMap<>();
    private final Set<Path> checkingFiles = Collections.synchronizedSet(new HashSet<>());
    private final Set<Path> ignoreEvents = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final String folderName;

    private final NexusConfig mainConfig;
    private final boolean isMainWatcher;
    private final NexusConfig.Watcher config;
    private final Set<String> allowedExtensions;
    private final boolean watchAll;

    public DirectoryWatcher(@NotNull Path directory, Path targetDirectory, boolean isSource,
                            NexusConfig.@NotNull Watcher config, NexusConfig mainConfig, boolean isMainWatcher) {
        this.directory = directory.toAbsolutePath().normalize();
        this.targetDirectory = targetDirectory;
        this.config = config;
        this.mainConfig = mainConfig;
        this.isMainWatcher = isMainWatcher;
        this.folderName = directory.getFileName().toString();
        this.threadName = "Watcher-" + (isSource ? "Src-" : "Dest-") + folderName;

        this.allowedExtensions = new HashSet<>();
        if (config.filter_extensions != null && !config.filter_extensions.isEmpty()) {
            String[] parts = config.filter_extensions.toLowerCase().split("\\s+");
            Collections.addAll(allowedExtensions, parts);
        }
        this.watchAll = allowedExtensions.contains(".*") || allowedExtensions.isEmpty();
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
                try (Stream<Path> paths = Files.walk(targetDirectory)) {
                    paths.filter(p -> !p.equals(targetDirectory))
                            .sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.delete(p); } catch (IOException ignored) {}
                            });
                }
            }
            if (sync) {
                Core.atInfo(Log.WATCHER, folderName).log("  [Sync] Copiando archivos iniciales...");
                try (Stream<Path> paths = Files.walk(directory)) {
                    paths.forEach(source -> {
                        try {
                            Path relative = directory.relativize(source);
                            Path destination = targetDirectory.resolve(relative);

                            if (Files.isDirectory(source)) {
                                if (!Files.exists(destination)) Files.createDirectories(destination);
                            } else {
                                if(destination.getParent() != null && !Files.exists(destination.getParent())) {
                                    Files.createDirectories(destination.getParent());
                                }
                                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            Core.atWarning(Log.WATCHER, folderName).log("Fallo al copiar: " + source.getFileName());
                        }
                    });
                }
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
                if (key == null) continue;

                Path watchablePath = ((Path) key.watchable()).toAbsolutePath().normalize();

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                    Path contextPath = (Path) event.context();
                    Path fullPath = watchablePath.resolve(contextPath).normalize();

                    if (ignoreEvents.contains(fullPath)) {
                        ignoreEvents.remove(fullPath);
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
                if (!key.reset()) break;
            }
        } catch (Exception e) {
            if (running) {
                Core.atError(Log.WATCHER, folderName).log("Error crítico en el bucle: " + e.getMessage());
            }
        }
    }

    private boolean shouldApplyActions(String fileName) {
        if (!isMainWatcher) return false;
        if (mainConfig.apply_Actions_Only == null || mainConfig.apply_Actions_Only.trim().isEmpty()) return false;

        String[] exts = mainConfig.apply_Actions_Only.toLowerCase().split("\\s+");
        String lowerName = fileName.toLowerCase();
        for (String ext : exts) {
            if (lowerName.endsWith(ext)) return true;
        }
        return false;
    }

    private void handleDeletion(Path fullPath) {
        Path relativePath = directory.relativize(fullPath);
        scheduler.schedule(() -> {
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
                    try { performSafeDeleteAction(targetFile); } catch (IOException ignored) {}
                }
            }
        }, 300, TimeUnit.MILLISECONDS);
    }

    private void executeWatcherLogic(Path filePath, long size) {
        if (targetDirectory == null) return;

        Path relativePath = directory.relativize(filePath.toAbsolutePath().normalize());
        Path targetPath = targetDirectory.resolve(relativePath);

        try {
            if (Files.exists(targetPath) && Files.size(filePath) == Files.size(targetPath)) return;
            ignoreEvents.add(targetPath);
            Path parentDir = targetPath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) Files.createDirectories(parentDir);

            if (shouldApplyActions(filePath.getFileName().toString())) {
                Core.atInfo(Log.WATCHER, folderName).log("Ejecutando secuencia de acciones para: " + filePath.getFileName());
                for (NexusConfig.ActionType action : mainConfig.actions) {
                    performAction(action, filePath, targetPath);
                }
                Core.atInfo(Log.WATCHER, folderName).log("Secuencia completada con éxito (" + formatSize(size) + ") -> " + relativePath);
            } else {
                Files.copy(filePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                Core.atInfo(Log.WATCHER, folderName).log("Copiado (" + formatSize(size) + ") -> " + relativePath);
            }

            scheduler.schedule(() -> ignoreEvents.remove(targetPath), 2, TimeUnit.SECONDS);

        } catch (IOException e) {
            ignoreEvents.remove(targetPath);
            Core.atError(Log.WATCHER, folderName).log("Error de copia en " + relativePath);
        }
    }

    private void checkFileStability(@NotNull Path path) {
        Path absPath = path.toAbsolutePath().normalize();
        if (!checkingFiles.add(absPath)) return;

        Path relativePath = directory.relativize(absPath);

        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!running || !Files.exists(absPath)) {
                        checkingFiles.remove(absPath);
                        return;
                    }

                    long currentSize = getRealSize(absPath);
                    Long lastSize = lastKnownSizes.get(absPath);

                    if (lastSize != null && currentSize == lastSize) {
                        String type = Files.isDirectory(absPath) ? "DIR " : "FILE";

                        Core.atInfo(Log.WATCHER, folderName).log(String.format("✅ [%s ESTABLE] -> %s (%s)",
                                type, relativePath, formatSize(currentSize)));

                        executeWatcherLogic(absPath, currentSize);

                        lastKnownSizes.remove(absPath);
                        checkingFiles.remove(absPath);
                    } else {
                        lastKnownSizes.put(absPath, currentSize);
                        Core.atInfo(Log.WATCHER, folderName).update("[MODIFICADO] -> " + relativePath + " | " + formatSize(currentSize) + "...");
                        scheduler.schedule(this, 1, TimeUnit.SECONDS);
                    }
                } catch (IOException e) {
                    checkingFiles.remove(absPath);
                }
            }
        }, 1, TimeUnit.SECONDS);
    }

    private @NotNull String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[] { "B", "kB", "MB", "GB", "TB" };
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1000));
        return String.format("%.2f %s", bytes / Math.pow(1000, digitGroups), units[digitGroups]);
    }

    private long getRealSize(@NotNull Path path) throws IOException {
        if (!Files.exists(path)) return 0L;
        if (Files.isRegularFile(path)) return Files.size(path);
        try (var stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0L; }
            }).sum();
        }
    }

    private boolean isFileAllowed(Path path) {
        if (Files.isDirectory(path)) return config.path_listen_Folders;
        if (watchAll) return true;
        String fileName = path.getFileName().toString().toLowerCase();
        return allowedExtensions.stream().anyMatch(fileName::endsWith);
    }

    private void registerRecursive(Path root, WatchService watchService) throws IOException {
        if (!config.path_listen_Folders) {
            root.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            return;
        }

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) throws IOException {
                dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }
        });
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
                    if (target.getParent() != null && !Files.exists(target.getParent())) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                case START_SERVER -> {
                    Core.atInfo(Log.WATCHER, folderName).log("  [Acción] Iniciando servidor nuevamente...");
                    var cfg = org.astral.core.setup.WorkspaceSetup.getNexus().getConfig();
                    Server.startServer(cfg.server_path, cfg.jar_name, cfg.args);
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
        Server.startServer(cfg.server_path, cfg.jar_name, cfg.args);

        Core.atInfo(Log.WATCHER, folderName).log("  [Safe-Delete] Proceso completado.");
    }

    public void stop() {
        Core.atInfo(Log.WATCHER, folderName).log("Deteniendo vigilante [" + threadName + "]...");
        this.running = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
            Core.atInfo(Log.WATCHER, folderName).log("  -> Hilo de monitoreo interrumpido.");
        }
        try {
            int pendingTasks = checkingFiles.size();
            scheduler.shutdown();

            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            Core.atInfo(Log.WATCHER, folderName).log(String.format(
                    "  -> Planificador detenido. (Tareas de estabilidad canceladas: %d)",
                    pendingTasks
            ));
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        checkingFiles.clear();
        lastKnownSizes.clear();
        ignoreEvents.clear();
        Core.atInfo(Log.WATCHER, folderName).log("Vigilante [" + threadName + "] fuera de línea.");
    }
}