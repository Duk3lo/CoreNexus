package org.astral.core.setup;

import org.astral.core.Main;
import org.astral.core.config.ConfigService;
import org.astral.core.config.curseforge.CurseForgeConfig;
import org.astral.core.config.nexus.NexusConfig;
import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class WorkspaceSetup {

    private static final String DefaultPrefix = "Default";

    private static Path workspacePath;
    private static Path localModsPath;
    private static Path curseForgePath;
    private static Path githubPath;

    private static ConfigService<NexusConfig> nexus;
    private static ConfigService<CurseForgeConfig> curseForge;


    public static void init() {
        calculatePaths();
        createHierarchy();
        loadConfigs();
    }

    private static void calculatePaths() {
        Path baseExecutionPath;
        try {
            File sourceFile = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            baseExecutionPath = sourceFile.toPath().toAbsolutePath().normalize();
            String pathStr = baseExecutionPath.toString().toLowerCase();
            if (pathStr.contains("/build/") || pathStr.contains("/out/") || pathStr.contains("\\build\\") || pathStr.contains("\\out\\")) {
                while (baseExecutionPath.getFileName().toString().matches("main|java|classes|build|out|bin")) {
                    baseExecutionPath = baseExecutionPath.getParent();
                }
            } else {
                if (Files.isRegularFile(baseExecutionPath)) {
                    baseExecutionPath = baseExecutionPath.getParent();
                }
            }
        } catch (URISyntaxException e) {
            baseExecutionPath = Path.of("").toAbsolutePath();
            Core.atWarning(Log.SYSTEM).log("No se pudo determinar la ruta raíz, usando relativa.");
        }
        workspacePath = baseExecutionPath.resolve("CoreNexus");
        localModsPath = workspacePath.resolve("SyncMods");
        curseForgePath = workspacePath.resolve("CurseForge");
        githubPath = workspacePath.resolve("GitHub");
    }

    private static void loadConfigs() {
        nexus = new ConfigService<>(NexusConfig.class, "config.yml", NexusConfig::new, workspacePath);
        nexus.load();

        NexusConfig cfg = nexus.getConfig();
        boolean autoUpdated = false;

        // Si el usuario definió un server_path (o se autodetectó)
        if (cfg.server_path != null && !cfg.server_path.isEmpty()) {

            // 1. Auto-detectar JAR si está vacío o es el por defecto y no existe
            if (cfg.jar_name == null || cfg.jar_name.isEmpty() || cfg.jar_name.equals("HytaleServer.jar")) {
                String detectedJar = tryAutoDetectJar(cfg.server_path);
                if (!detectedJar.isEmpty() && !detectedJar.equals(cfg.jar_name)) {
                    cfg.jar_name = detectedJar;
                    autoUpdated = true;
                }
            }

            // 2. Auto-detectar path_destination para los watchers
            for (NexusConfig.Watcher w : cfg.watchers.values()) {
                // Si el destino está vacío, intentamos encontrar la carpeta /mods dentro del server_path
                if (w.path_destination == null || w.path_destination.isEmpty()) {
                    String detectedMods = tryAutoDetectModsServer(cfg.server_path);
                    if (!detectedMods.isEmpty()) {
                        w.path_destination = detectedMods;
                        autoUpdated = true;
                    }
                }
            }
        }

        // Si hubo cambios automáticos, guardamos el YML actualizado para que el usuario lo vea
        if (autoUpdated) {
            nexus.save();
            Core.atInfo(Log.CONFIG).log("Configuración completada automáticamente basándose en server_path.");
        }

        curseForge = new ConfigService<>(CurseForgeConfig.class, "curseforge.yml", CurseForgeConfig::new, curseForgePath);
        curseForge.load();
    }

    private static void createHierarchy() {
        try {
            Files.createDirectories(workspacePath);
            Files.createDirectories(localModsPath);
            Files.createDirectories(curseForgePath);
            Files.createDirectories(githubPath);

            Core.atInfo(Log.SYSTEM).log("⚓ Entorno CoreNexus preparado:");
            Core.atInfo(Log.SYSTEM).log("  -> [RAÍZ]  : " + workspacePath.toAbsolutePath());
            Core.atInfo(Log.SYSTEM).log("  -> [MODS]  : " + localModsPath.toAbsolutePath());
            Core.atInfo(Log.SYSTEM).log("---------------------------------------------------------");
        } catch (IOException e) {
            Core.atError(Log.SYSTEM).log("Error crítico al crear directorios: " + e.getMessage());
        }
    }

    // --- ACCESO DIRECTO Y ESTÁTICO ---
    public static ConfigService<NexusConfig> getNexus() {
        return nexus;
    }

    public static ConfigService<CurseForgeConfig> getCurseForge() {
        return curseForge;
    }

    public static Path getLocalModsPath() {
        return localModsPath;
    }
    //public static Path getWorkspacePath() { return workspacePath; }

    public static @NotNull String tryAutoDetectPathServer() {
        Path current = getExecutionPath();
        if (hasServerJar(current)) return current.toAbsolutePath().toString();
        Path subServer = current.resolve("Server");
        if (hasServerJar(subServer)) return subServer.toAbsolutePath().toString();
        Path parent = current.getParent();
        if (hasServerJar(parent)) return parent.toAbsolutePath().toString();
        return "";
    }

    // Versión mejorada que acepta una ruta específica
    public static @NotNull String tryAutoDetectModsServer(String specificPath) {
        if (specificPath == null || specificPath.isEmpty()) return "";

        Path mods = Path.of(specificPath).resolve("mods");
        return (Files.exists(mods) && Files.isDirectory(mods)) ? mods.toAbsolutePath().toString() : "";
    }

    public static String tryAutoDetectJar(String specificPath) {
        if (specificPath == null || specificPath.isEmpty()) return "";

        try (Stream<Path> stream = Files.list(Path.of(specificPath))) {
            return stream
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.toLowerCase().endsWith(".jar"))
                    .filter(name -> !name.toLowerCase().contains("corenexus"))
                    .filter(name -> name.toLowerCase().contains("hytale") || name.toLowerCase().contains("server"))
                    .findFirst()
                    .orElse("");
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean hasServerJar(Path dir) {
        if (dir == null || !Files.exists(dir) || !Files.isDirectory(dir)) return false;
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.anyMatch(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".jar") &&
                        (name.contains("hytale") || name.contains("server")) &&
                        !name.contains("corenexus");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static Path getExecutionPath() {
        try {
            File sourceFile = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return sourceFile.isDirectory() ? sourceFile.toPath() : sourceFile.getParentFile().toPath();
        } catch (Exception e) {
            return Path.of("").toAbsolutePath();
        }
    }


    public static String getDefaultPrefix() {
        return DefaultPrefix;
    }
}