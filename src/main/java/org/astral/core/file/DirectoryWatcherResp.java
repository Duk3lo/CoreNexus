/*package org.astral.core.file;

import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DirectoryWatcherResp {
    private final Path directory;
    private volatile boolean running = true;
    private final String threadName;
    private Thread watcherThread;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final ConcurrentHashMap<Path, Long> lastKnownSizes = new ConcurrentHashMap<>();
    private final Set<Path> checkingFiles = Collections.synchronizedSet(new HashSet<>());
    private final String folderName;

    // --- NUEVOS CAMPOS DE CONFIGURACIÓN ---
    private final boolean listenFolders;
    private final Set<String> allowedExtensions;
    private final boolean watchAll;

    public DirectoryWatcherResp(@NotNull Path directory, boolean listenFolders, String extensions) {
        this.directory = directory;
        this.folderName = directory.getFileName().toString();
        this.threadName = "Watcher-" + folderName;
        this.listenFolders = listenFolders;
        this.allowedExtensions = new HashSet<>();
        if (extensions != null && !extensions.isEmpty()) {
            String[] parts = extensions.toLowerCase().split("\\s+");
            for (String part : parts) {
                allowedExtensions.add(part.trim());
            }
        }
        this.watchAll = allowedExtensions.contains(".*") || allowedExtensions.isEmpty();

    }

    private boolean isFileAllowed(Path path) {
        if (Files.isDirectory(path)) return listenFolders;
        if (watchAll) return true;

        String fileName = path.getFileName().toString().toLowerCase();
        return allowedExtensions.stream().anyMatch(fileName::endsWith);
    }

    public void start() {
        watcherThread = new Thread(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                directory.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);

                // --- INFO DE INICIO CON RUTA ABSOLUTA ---
                Core.atInfo(Log.WATCHER, folderName).log("Vigilante [" + threadName + "] iniciado.");
                Core.atInfo(Log.WATCHER, folderName).log("Ruta: " + directory.toAbsolutePath());
                Core.atInfo(Log.WATCHER, folderName).log("------------------------------------------------");

                // --- LISTADO DETALLADO DE ARCHIVOS EXISTENTES ---
                Core.atInfo(Log.WATCHER, folderName).log("Contenido actual:");

                try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                    boolean empty = true;
                    for (Path entry : stream) {
                        empty = false;
                        String type = Files.isDirectory(entry) ? "DIR " : "FILE";
                        long size = getRealSize(entry);

                        Core.atInfo(Log.WATCHER, folderName).log(String.format("  -> [%s] %-25s | %s",
                                type,
                                entry.getFileName(),
                                formatSize(size)));
                    }
                    if (empty) {
                        Core.atInfo(Log.WATCHER, folderName).log("  (Directorio vacío)");
                    }
                } catch (IOException e) {
                    Core.atError(Log.WATCHER, folderName).log("Error al leer archivos pre-existentes.");
                }

                Core.atInfo(Log.WATCHER, folderName).log("------------------------------------------------");
                Core.atInfo(Log.WATCHER, folderName).log("Esperando nuevos eventos...");
                // ------------------------------------------------

                while (running && !Thread.currentThread().isInterrupted()) {
                    WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                    if (key == null) continue;

                    // Se define una sola vez aquí afuera
                    Path watchablePath = (Path) key.watchable();

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                        Path fileName = (Path) event.context();
                        Path fullPath = watchablePath.resolve(fileName);
                        Path relativePath = directory.relativize(fullPath);

                        // 1. Manejo de ELIMINACIÓN (No necesita check de extensiones porque el archivo ya no existe)
                        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                            Core.atInfo(Log.WATCHER, folderName).log("[ELIMINADO] -> " + relativePath);
                            continue; // Pasamos al siguiente evento
                        }

                        // 2. Filtro de EXTENSIONES / CARPETAS
                        if (!isFileAllowed(fullPath)) {
                            Core.atInfo(Log.WATCHER, folderName).log("[IGNORADO] -> " + relativePath + " (Extensión no permitida)");
                            continue;
                        }

                        // 3. Procesamiento de archivos PERMITIDOS
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                            Core.atInfo(Log.WATCHER, folderName).log("[NUEVO] -> " + relativePath);

                            if (Files.isDirectory(fullPath) && listenFolders) {
                                registerRecursive(fullPath, watchService);
                                Core.atInfo(Log.WATCHER, folderName).log("📁 Subcarpeta registrada: " + relativePath);
                            }
                        }

                        // Si el archivo existe y es permitido (Create o Modify), verificamos estabilidad
                        if (Files.exists(fullPath)) {
                            checkFileStability(fullPath);
                        }
                    }
                    if (!key.reset()) break;
                }
            } catch (InterruptedException e) {
                if (running) {
                    Core.atError(Log.WATCHER, folderName).log("El vigilante fue interrumpido inesperadamente.");
                }
            } catch (IOException e) {
                if (running) {
                    Core.atError(Log.WATCHER, folderName).log("Error de E/S en el vigilante: " + e.getMessage());
                }
            } finally {
                Core.atInfo(Log.WATCHER, folderName).log("Hilo del vigilante [" + threadName + "] finalizado.");
            }
        }, threadName);

        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private void checkFileStability(@NotNull Path path) {
        Path absPath = path.toAbsolutePath();
        if (!checkingFiles.add(absPath)) return;

        // Calculamos la ruta relativa una vez para usarla en los logs del scheduler
        Path relativePath = directory.toAbsolutePath().relativize(absPath);

        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!running || !Files.exists(absPath)) {
                        checkingFiles.remove(absPath);
                        lastKnownSizes.remove(absPath);
                        return;
                    }

                    long currentSize = getRealSize(absPath);
                    Long lastSize = lastKnownSizes.get(absPath);

                    if (lastSize != null && currentSize == lastSize) {
                        System.out.print("\r" + " ".repeat(110) + "\r");

                        String type = Files.isDirectory(absPath) ? "CARPETA" : "ARCHIVO";
                        // USAMOS relativePath AQUÍ
                        Core.atInfo(Log.WATCHER, folderName).log(String.format("✅ [%s ESTABLE] -> %s (%s)",
                                type, relativePath, formatSize(currentSize)));

                        lastKnownSizes.remove(absPath);
                        checkingFiles.remove(absPath);
                    } else {
                        lastKnownSizes.put(absPath, currentSize);

                        // USAMOS relativePath AQUÍ
                        String status = String.format("[MODIFICADO] -> %s | %s...",
                                relativePath, formatSize(currentSize));
                        Core.atInfo(Log.WATCHER, folderName).update(status);

                        scheduler.schedule(this, 1, TimeUnit.SECONDS);
                    }
                } catch (IOException e) {
                    checkingFiles.remove(absPath);
                    lastKnownSizes.remove(absPath);
                }
            }
        }, 1, TimeUnit.SECONDS);
    }

    private void registerRecursive(Path root, WatchService watchService) throws IOException {
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

    private @NotNull String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[] { "B", "kB", "MB", "GB", "TB" };
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1000));
        return String.format("%.2f %s", bytes / Math.pow(1000, digitGroups), units[digitGroups]);
    }

    private long getRealSize(@NotNull Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return Files.size(path);
        } else if (Files.isDirectory(path)) {
            try (var stream = Files.walk(path)) {
                return stream.filter(Files::isRegularFile)
                        .mapToLong(p -> {
                            try { return Files.size(p); }
                            catch (IOException e) { return 0L; }
                        }).sum();
            }
        }
        return 0L;
    }

    public void stop() {
        this.running = false;
        if (watcherThread != null) watcherThread.interrupt();
        scheduler.shutdownNow();
        Core.atInfo(Log.WATCHER, folderName).log("Vigilante y planificador detenidos.");
    }
}*/