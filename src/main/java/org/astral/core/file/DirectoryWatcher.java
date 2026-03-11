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
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4); // Aumentado para logs fluidos
    private final ConcurrentHashMap<Path, Long> lastKnownSizes = new ConcurrentHashMap<>();
    private final Set<Path> checkingFiles = Collections.synchronizedSet(new HashSet<>());
    private final Set<Path> ignoreEvents = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final String folderName;

    private final NexusConfig.Watcher config;
    private final Set<String> allowedExtensions;
    private final boolean watchAll;

    public DirectoryWatcher(@NotNull Path directory, Path targetDirectory, boolean isSource, NexusConfig.@NotNull Watcher config) {
        this.directory = directory.toAbsolutePath().normalize();

        if (targetDirectory != null && !targetDirectory.toString().trim().isEmpty()) {
            this.targetDirectory = targetDirectory.toAbsolutePath().normalize();
        } else {
            this.targetDirectory = null;
        }

        this.config = config;
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

        // --- INFO DE INICIO DETALLADA ---
        Core.atInfo(Log.WATCHER, folderName).log("Vigilante [" + threadName + "] en línea.");
        Core.atInfo(Log.WATCHER, folderName).log("  -> Origen:  " + directory);

        // Mostramos el destino siempre que exista en la configuración
        if (targetDirectory != null) {
            String syncType = config.path_sync ? " (Sincronización Activa)" : " (Solo Envío)";
            Core.atInfo(Log.WATCHER, folderName).log("  -> Destino: " + targetDirectory + syncType);


        } else {
            Core.atInfo(Log.WATCHER, folderName).log("  -> Destino: NO CONFIGURADO");
        }

        Core.atInfo(Log.WATCHER, folderName).log("---------------------------------------------------------");

        // --- LISTADO INICIAL DE CONTENIDO ---
        boolean hasFiles = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (!hasFiles) {
                    Core.atInfo(Log.WATCHER, folderName).log("Contenido actual de la carpeta:");
                    hasFiles = true;
                }
                String type = Files.isDirectory(entry) ? "DIR " : "FILE";
                long size = getRealSize(entry);
                Core.atInfo(Log.WATCHER, folderName).log(String.format("  -> [%s] %-25s | %s",
                        type, entry.getFileName(), formatSize(size)));
            }
        } catch (IOException ignored) {}

        // Si la carpeta estaba vacía, ponemos un aviso en lugar de solo la línea
        if (!hasFiles) {
            Core.atInfo(Log.WATCHER, folderName).log("  (Carpeta de origen vacía)");
        }

        Core.atInfo(Log.WATCHER, folderName).log("---------------------------------------------------------");

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
                            .sorted(Comparator.reverseOrder()) // Borra archivos primero, luego carpetas
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

    private void handleDeletion(Path fullPath) {
        Path relativePath = directory.relativize(fullPath);

        // No borramos de inmediato, esperamos 300ms para ver si es un reemplazo de editor
        scheduler.schedule(() -> {
            // Si el archivo ya volvió a existir, es que fue un Rename/Save de un editor. No borramos.
            if (Files.exists(fullPath)) {
                return;
            }

            Core.atInfo(Log.WATCHER, folderName).log("[ELIMINADO] -> " + relativePath);

            if (config.path_sync && targetDirectory != null) {
                Path targetFile = targetDirectory.resolve(relativePath);
                try {
                    if (Files.exists(targetFile)) {
                        if (relativePath.toString().endsWith(config.apply_Actions_Only) && config.path_safe_delete) {
                            performSafeDeleteAction(targetFile);
                        } else {
                            Files.deleteIfExists(targetFile);
                            Core.atInfo(Log.WATCHER, folderName).log("Espejo actualizado: " + relativePath);
                        }
                    }
                } catch (IOException e) {
                    Core.atWarning(Log.WATCHER, folderName).log("Archivo bloqueado. Intentando Safe-Delete...");
                    try { performSafeDeleteAction(targetFile); } catch (IOException ignored) {}
                }
            }
        }, 300, TimeUnit.MILLISECONDS); // 300.ms es suficiente para cualquier editor
    }

    private void executeWatcherLogic(Path filePath, long size) {
        if (!config.path_sync || targetDirectory == null) return;

        Path relativePath = directory.relativize(filePath.toAbsolutePath().normalize());
        Path targetPath = targetDirectory.resolve(relativePath);

        try {
            if (Files.exists(targetPath) && Files.size(filePath) == Files.size(targetPath)) return;

            // REGISTRAMOS QUE VAMOS A TOCAR EL DESTINO
            ignoreEvents.add(targetPath);

            Path parentDir = targetPath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) Files.createDirectories(parentDir);

            if (filePath.getFileName().toString().endsWith(config.apply_Actions_Only)) {
                for (NexusConfig.ActionType action : config.actions) {
                    performAction(action, filePath, targetPath);
                }
            } else {
                Files.copy(filePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                Core.atInfo(Log.WATCHER, folderName).log("Sincronizado (" + formatSize(size) + ") -> " + relativePath);
            }

            // Quitamos él ignore después de un breve tiempo para permitir cambios reales del usuario
            scheduler.schedule(() -> ignoreEvents.remove(targetPath), 2, TimeUnit.SECONDS);

        } catch (IOException e) {
            ignoreEvents.remove(targetPath); // Limpiar si hubo error
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
                        // Log de progreso de escritura
                        Core.atInfo(Log.WATCHER, folderName).update("[MODIFICADO] -> " + relativePath + " | " + formatSize(currentSize) + "...");
                        scheduler.schedule(this, 1, TimeUnit.SECONDS);
                    }
                } catch (IOException e) {
                    checkingFiles.remove(absPath);
                }
            }
        }, 1, TimeUnit.SECONDS);
    }

    // --- FORMATEO Y TAMAÑO ---

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

    // --- MÉTODOS DE APOYO RESTANTES ---

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

        // Si SÍ debemos escuchar carpetas, mantenemos el comportamiento actual
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
                case STOP_SERVER -> { if (Server.getInstance() != null) Server.getInstance().stopServer(); }
                case DELETE -> Files.deleteIfExists(target);
                case COPY -> Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                case START_SERVER -> {
                    var cfg = org.astral.core.setup.WorkspaceSetup.getNexus().getConfig();
                    Server.startServer(cfg.server_path, cfg.jar_name, cfg.args);
                }
            }
        } catch (IOException ignored) {}
    }

    private void performSafeDeleteAction(@NotNull Path target) throws IOException {
        Core.atInfo(Log.SERVER).log("Safe-Delete en curso...");
        if (Server.getInstance() != null) Server.getInstance().stopServer();
        Files.deleteIfExists(target);
        var cfg = org.astral.core.setup.WorkspaceSetup.getNexus().getConfig();
        Server.startServer(cfg.server_path, cfg.jar_name, cfg.args);
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