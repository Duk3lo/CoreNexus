package org.astral.core.setup;

import org.astral.core.Main;
import org.astral.core.config.ConfigService;
import org.astral.core.config.curseforge.CurseForgeConfig;
import org.astral.core.config.github.GitHubConfig;
import org.astral.core.config.nexus.HealingConfig;
import org.astral.core.config.nexus.NexusConfig;
import org.astral.core.config.nexus.UpdatesConfig;
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

    private static final String DefaultWatchPrefix = "_Default_";
    private static final String DefaultGitHubPrefix = "_example_";
    private static final String DefaultCurseForgePrefix = "_mod_example_";

    private static Path workspacePath;
    private static Path localModsPath;
    private static Path curseForgePath;
    private static Path curseForgeDownloadsPath;
    private static Path curseForgeBackupPath;
    private static Path githubPath;
    private static Path githubDownloadsPath;
    private static Path githubBackupPath;

    private static ConfigService<NexusConfig> nexus;
    private static ConfigService<CurseForgeConfig> curseForge;
    private static ConfigService<GitHubConfig> github;
    private static ConfigService<HealingConfig> healing;
    private static ConfigService<UpdatesConfig> updates;

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
        curseForgeDownloadsPath = curseForgePath.resolve("Downloads");
        curseForgeBackupPath = curseForgePath.resolve("Backup");
        githubPath = workspacePath.resolve("GitHub");
        githubDownloadsPath = githubPath.resolve("Downloads");
        githubBackupPath = githubPath.resolve("Backup");
    }

    private static void loadConfigs() {
        nexus = new ConfigService<>(NexusConfig.class, "config.yml", NexusConfig::new, workspacePath);
        nexus.load();
        if (applyAutoDetection()) {
            nexus.save();
            Core.atInfo(Log.CONFIG).log("Configuración completada automáticamente y guardada en config.yml.");
        }

        curseForge = new ConfigService<>(CurseForgeConfig.class, "curseforge.yml", CurseForgeConfig::new, curseForgePath);
        curseForge.load();

        github = new ConfigService<>(GitHubConfig.class, "github.yml", GitHubConfig::new, githubPath);
        github.load();

        healing = new ConfigService<>(HealingConfig.class, "healing.yml", HealingConfig::new, workspacePath);
        healing.load();

        updates = new ConfigService<>(UpdatesConfig.class, "Updates.yml", UpdatesConfig::new, workspacePath);
        updates.load();
    }

    public static boolean applyAutoDetection() {
        if (nexus == null || nexus.getConfig() == null) return false;
        NexusConfig cfg = nexus.getConfig();
        boolean modified = false;
        Path currentExecPath = workspacePath.getParent();
        if (cfg.server_path == null || cfg.server_path.trim().isEmpty()) {
            Path potentialServer = currentExecPath.resolve("Server");
            if (!Files.exists(potentialServer)) potentialServer = currentExecPath.resolve("server");

            if (Files.exists(potentialServer) && Files.isDirectory(potentialServer)) {
                cfg.server_path = potentialServer.toAbsolutePath().normalize().toString();
            } else {
                cfg.server_path = currentExecPath.toAbsolutePath().normalize().toString();
            }
            modified = true;
        }

        Path serverPath = Path.of(cfg.server_path);
        if (Files.isDirectory(serverPath)) {
            if (tryAutoDetectJar(serverPath.toString()).isEmpty()) {
                Path subServer = serverPath.resolve("server");
                if (!Files.exists(subServer)) subServer = serverPath.resolve("Server");

                if (Files.exists(subServer) && Files.isDirectory(subServer)) {
                    cfg.server_path = subServer.toAbsolutePath().normalize().toString();
                    serverPath = subServer;
                    modified = true;
                    Core.atInfo(Log.CONFIG).log("Ruta del servidor auto-ajustada profundizando a: " + cfg.server_path);
                }
            }
        }

        if (cfg.jar_name == null || cfg.jar_name.trim().isEmpty()) {
            String detectedJar = tryAutoDetectJar(serverPath.toString());
            if (!detectedJar.isEmpty()) {
                cfg.jar_name = detectedJar;
                modified = true;
                Core.atInfo(Log.CONFIG).log("JAR detectado automáticamente: " + cfg.jar_name);
            }
        }

        NexusConfig.Watcher w = cfg.watchers.get(DefaultWatchPrefix);
        if (w != null) {
            if (w.path_destination == null || w.path_destination.trim().isEmpty() || w.path_destination.equals("./SyncMods")) {
                Path modsFolder = serverPath.resolve("mods");
                w.path_destination = modsFolder.toAbsolutePath().normalize().toString();
                modified = true;
                Core.atInfo(Log.CONFIG).log("Ruta destino del Watcher principal ajustada a: " + w.path_destination);
                if (!Files.exists(modsFolder)) {
                    try {
                        Files.createDirectories(modsFolder);
                        Core.atInfo(Log.SYSTEM).log("Se creó la carpeta mods en el servidor: " + modsFolder);
                    } catch (IOException ignored) {}
                }
            }
        }

        return modified;
    }

    private static void createHierarchy() {
        try {
            Files.createDirectories(workspacePath);
            Files.createDirectories(localModsPath);
            Files.createDirectories(curseForgePath);
            Files.createDirectories(githubPath);

            Core.atInfo(Log.SYSTEM).log("Entorno CoreNexus preparado:");
            Core.atInfo(Log.SYSTEM).log("  -> [RAÍZ]  : " + workspacePath.toAbsolutePath());
            Core.atInfo(Log.SYSTEM).log("  -> [MODS]  : " + localModsPath.toAbsolutePath());
            Core.atInfo(Log.SYSTEM).log("---------------------------------------------------------");
        } catch (IOException e) {
            Core.atError(Log.SYSTEM).log("Error crítico al crear directorios: " + e.getMessage());
        }
    }

    public static Path resolve(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) return null;
        if (rawPath.startsWith("./") || rawPath.startsWith(".\\")) {
            return workspacePath.resolve(rawPath.substring(2)).toAbsolutePath().normalize();
        }
        Path path = Path.of(rawPath);
        if (!path.isAbsolute()) {
            return workspacePath.resolve(path).toAbsolutePath().normalize();
        }
        return path.toAbsolutePath().normalize();
    }

    public static @NotNull String relativize(Path absolutePath) {
        if (absolutePath == null) return "";
        if (absolutePath.startsWith(workspacePath)) {
            return "./" + workspacePath.relativize(absolutePath).toString().replace("\\", "/");
        }
        return absolutePath.toAbsolutePath().toString();
    }

    public static ConfigService<NexusConfig> getNexus() {
        return nexus;
    }

    public static ConfigService<CurseForgeConfig> getCurseForge() {
        return curseForge;
    }

    public static ConfigService<GitHubConfig> getGithub (){
        return github;
    }

    public static ConfigService<HealingConfig> getHealing() {
        return healing;
    }

    public static ConfigService<UpdatesConfig> getUpdates() {
        return updates;
    }

    public static Path getLocalModsPath() { return localModsPath;}
    public static Path getGithubDownloadsPath() { return githubDownloadsPath; }
    public static Path getGithubBackupPath() { return githubBackupPath; }
    public static Path getCurseForgeDownloadsPath() { return curseForgeDownloadsPath; }
    public static Path getCurseForgeBackupPath() { return curseForgeBackupPath; }

    public static String tryAutoDetectJar(String specificPath) {
        if (specificPath == null || specificPath.trim().isEmpty()) return "";
        Path path = Path.of(specificPath);
        if (!Files.exists(path) || !Files.isDirectory(path)) return "";

        try (Stream<Path> stream = Files.list(path)) {
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

    public static String getDefaultWatchPrefix() {return DefaultWatchPrefix;}
    public static String getDefaultGitHubPrefix() {return DefaultGitHubPrefix;}
    public static String getDefaultCurseForgePrefix(){return DefaultCurseForgePrefix;}
}