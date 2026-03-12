package org.astral.core.command;

import org.astral.core.api.github.GItHubApi;
import org.astral.core.config.ConfigService;
import org.astral.core.config.curseforge.CurseForgeConfig;
import org.astral.core.config.nexus.HealingConfig;
import org.astral.core.config.nexus.NexusConfig;
import org.astral.core.file.WatcherManager;
import org.astral.core.healing.HealthMonitor;
import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.astral.core.process.Server;
import org.astral.core.setup.WorkspaceSetup;
import org.jetbrains.annotations.NotNull;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Scanner;

public class CommandTerminal {
    private static CommandTerminal instance;
    private PrintWriter currentWriter;
    private Process currentProcess;

    private CommandTerminal() {}

    public static CommandTerminal getInstance() {
        if (instance == null) instance = new CommandTerminal();
        return instance;
    }

    public void connectProcess(@NotNull Process process) {
        this.currentProcess = process;
        this.currentWriter = new PrintWriter(new OutputStreamWriter(process.getOutputStream()), true);
        Core.atInfo(Log.SYSTEM).log("Terminal vinculada al nuevo proceso.");
    }

    public void startListening() {
        Thread terminalThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            Core.atInfo(Log.SYSTEM).log("Consola de comandos global iniciada.");
            while (true) {
                try {
                    if (scanner.hasNextLine()) {
                        String input = scanner.nextLine().trim();
                        if (input.isEmpty()) continue;
                        if (isInternalCommand(input)) {
                            handleCoreCommand(input);
                        } else {
                            sendToProcess(input);
                        }
                    }
                } catch (Exception e) {
                    Core.atError(Log.SYSTEM).log("Error en terminal: " + e.getMessage());
                }
            }
        }, "Global-Terminal");
        terminalThread.setDaemon(false);
        terminalThread.start();
    }

    private void handleCoreCommand(@NotNull String input) {
        String[] parts = input.split("\\s+", 3);
        String command = parts[0].toLowerCase();
        String subCommand = parts.length > 1 ? parts[1].toLowerCase() : "";
        String extraArgs = parts.length > 2 ? parts[2] : "";

        ConfigService<NexusConfig> nexus = WorkspaceSetup.getNexus();
        NexusConfig cfg = nexus.getConfig();

        switch (command) {
            case "core-health" -> handleHealthCommand(subCommand, extraArgs);

            case "core-curseforge" -> handleCurseForgeCommand(subCommand, extraArgs);

            case "core-github" -> handleGitHubCommand(subCommand, extraArgs);

            case "core-watcher" -> handleWatcherCommand(subCommand, extraArgs, nexus);

            case "core-setpathserver" -> {
                if (subCommand.isEmpty()) {
                    Core.atWarning(Log.CONFIG).log("Uso: Core-SetPathServer <ruta>");
                    return;
                }
                Server.getInstance().stopServer();
                cfg.server_path = subCommand;
                WorkspaceSetup.applyAutoDetection();
                nexus.save();
                Core.atInfo(Log.CONFIG).log("Ruta del servidor actualizada: " + cfg.server_path);
            }

            case "core-setjar" -> {
                if (subCommand.isEmpty()) {
                    Core.atWarning(Log.CONFIG).log("Uso: Core-SetJar <nombre.jar>");
                    return;
                }
                cfg.jar_name = subCommand;
                nexus.save();
                Core.atInfo(Log.CONFIG).log("JAR actualizado y guardado: " + subCommand);
            }

            case "core-status" -> {
                Core.atInfo(Log.SYSTEM).log("--- Estado del Core ---");
                Core.atInfo(Log.SYSTEM).log("Path Server: " + cfg.server_path);
                Core.atInfo(Log.SYSTEM).log("JAR: " + cfg.jar_name);
                Core.atInfo(Log.SYSTEM).log("Watchers Activos: " + cfg.watchers.size());
            }

            case "core-reload" -> {
                nexus.load();
                WorkspaceSetup.applyAutoDetection();
                Core.atInfo(Log.CONFIG).log("Configuración recargada.");
            }

            case "start-server" -> Server.startServer(cfg.server_path, cfg.jar_name, cfg.args);
            case "stop-server" -> { if (Server.getInstance() != null) Server.getInstance().stopServer(); }
            case "exit-core" -> shutdownSystem();
            default -> sendToProcess(input);
        }
    }

    private void handleHealthCommand(@NotNull String sub, String args) {
        switch (sub) {
            case "status", "" -> HealthMonitor.getInstance().printHealthStatus();
            case "enable" -> {
                if (args.isEmpty()) {
                    Core.atWarning(Log.HEALTH).log("Uso: core-health enable <true/false>");
                    return;
                }
                boolean state = Boolean.parseBoolean(args.split("\\s+")[0]);
                HealingConfig config = WorkspaceSetup.getHealing().getConfig();
                if (config != null) {
                    config.enable = state;
                    WorkspaceSetup.getHealing().save();
                    if (state) {
                        HealthMonitor.getInstance().start();
                        Core.atInfo(Log.HEALTH).log("Monitor encendido y configuración guardada.");
                    } else {
                        HealthMonitor.getInstance().stop();
                        Core.atInfo(Log.HEALTH).log("Monitor apagado y configuración guardada.");
                    }
                }
            }
            default -> Core.atInfo(Log.HEALTH).log("Sub-comandos de Health: status, enable <true/false>");
        }
    }

    private void handleCurseForgeCommand(@NotNull String sub, String args) {
        switch (sub) {
            case "sync-all" -> {
                Core.atInfo(Log.CURSEFORGE).log("Forzando sincronización de todos los mods de CurseForge...");
                org.astral.core.api.curseforge.CurseForgeAPI.getInstance().syncAll();
            }
            case "sync" -> {
                if (args.isEmpty()) {
                    Core.atWarning(Log.CURSEFORGE).log("Uso: core-curseforge sync <nombre_en_yml>");
                    return;
                }
                String modKey = args.split("\\s+")[0];
                Core.atInfo(Log.CURSEFORGE).log("Sincronizando el mod: " + modKey);
                org.astral.core.api.curseforge.CurseForgeAPI.getInstance().syncMod(modKey);
            }
            case "add" -> {
                if (args.isEmpty()) {
                    Core.atWarning(Log.CURSEFORGE).log("Uso: core-curseforge add <id_proyecto>");
                    return;
                }
                try {
                    int projectId = Integer.parseInt(args.split("\\s+")[0]);
                    String key = "mod_" + projectId; // Creamos una llave automática

                    CurseForgeConfig cfCfg = WorkspaceSetup.getCurseForge().getConfig();
                    if (cfCfg == null) return;

                    if (cfCfg.resources.containsKey(key)) {
                        Core.atWarning(Log.CURSEFORGE).log("El mod ID " + projectId + " ya existe en tu configuración.");
                        return;
                    }

                    CurseForgeConfig.CurseForgeResource newResource =
                            CurseForgeConfig.createResource(
                                    projectId,
                                    WorkspaceSetup.relativize(WorkspaceSetup.getLocalModsPath())
                            );

                    cfCfg.resources.put(key, newResource);
                    WorkspaceSetup.getCurseForge().save();

                    Core.atInfo(Log.CURSEFORGE).log("✅ Mod " + projectId + " agregado exitosamente como '" + key + "'.");
                    Core.atInfo(Log.CURSEFORGE).log("Tip: Usa 'core-curseforge sync " + key + "' para descargarlo.");
                } catch (NumberFormatException e) {
                    Core.atError(Log.CURSEFORGE).log("El ID del proyecto debe ser un número.");
                }
            }
            default -> Core.atInfo(Log.CURSEFORGE).log("Sub-comandos de CurseForge: sync-all, sync <nombre>, add <id>");
        }
    }

    private void handleGitHubCommand(@NotNull String sub, String args) {
        switch (sub) {
            case "sync-all" -> {
                Core.atInfo(Log.GITHUB).log("Forzando sincronización de todos los repositorios...");
                GItHubApi.getInstance().syncAll();
            }
            case "sync" -> {
                if (args.isEmpty()) {
                    Core.atWarning(Log.GITHUB).log("Uso: core-github sync <nombre_en_yml>");
                    return;
                }
                String repoKey = args.split("\\s+")[0];
                Core.atInfo(Log.GITHUB).log("Sincronizando el repositorio: " + repoKey);
                GItHubApi.getInstance().syncRepo(repoKey);
            }
            case "add" -> {
                if (!args.contains("/")) {
                    Core.atWarning(Log.GITHUB).log("Uso correcto: core-github add <usuario/repositorio>");
                    return;
                }
                String repoSlug = args.split("\\s+")[0];
                String key = repoSlug.substring(repoSlug.lastIndexOf("/") + 1);

                org.astral.core.config.github.GitHubConfig githubCfg = WorkspaceSetup.getGithub().getConfig();
                if (githubCfg == null) return;
                if (githubCfg.resources.containsKey(key)) {
                    Core.atWarning(Log.GITHUB).log("El repositorio '" + key + "' ya existe en tu configuración.");
                    return;
                }
                org.astral.core.config.github.GitHubConfig.RepositoryResource newResource =
                        org.astral.core.config.github.GitHubConfig.createResource(
                                repoSlug,
                                WorkspaceSetup.relativize(WorkspaceSetup.getLocalModsPath())
                        );
                githubCfg.resources.put(key, newResource);
                WorkspaceSetup.getGithub().save();

                Core.atInfo(Log.GITHUB).log("✅ Repositorio '" + repoSlug + "' agregado exitosamente.");
                Core.atInfo(Log.GITHUB).log("Tip: Usa 'core-github sync " + key + "' para descargarlo ahora mismo.");
            }
            default -> Core.atInfo(Log.GITHUB).log("Sub-comandos de GitHub: sync-all, sync <nombre>, add <usuario/repo>");
        }
    }

    private void handleWatcherCommand(@NotNull String sub, String args, @NotNull ConfigService<NexusConfig> nexus) {
        NexusConfig cfg = nexus.getConfig();

        switch (sub) {

            case "enable" -> {
                if (args.isEmpty()) {
                    Core.atWarning(Log.WATCHER).log("Uso: core-watcher enable <nombre> <true|false>");
                    return;
                }
                String[] parts = args.split("\\s+");
                String name = parts[0];
                boolean state = parts.length == 1 || Boolean.parseBoolean(parts[1]);

                NexusConfig.Watcher w = cfg.watchers.get(name);
                if (w != null) {
                    w.enable = state;
                    nexus.save();
                    Core.atInfo(Log.WATCHER).log("Watcher '" + name + "' marcado como " + (state ? "ON" : "OFF"));
                    if (state) {
                        WatcherManager.getInstance().addWatcher(w);
                    } else {
                        WatcherManager.getInstance().removeWatcher(w.path);
                        if (w.path_destination != null) {
                            WatcherManager.getInstance().removeWatcher(w.path_destination);
                        }
                    }
                } else {
                    Core.atError(Log.WATCHER).log("No existe el watcher: " + name);
                }
            }
            case "list" -> {
                Core.atInfo(Log.WATCHER).log("--- Lista de Watchers Registrados ---");
                if (cfg.watchers.isEmpty()) {
                    Core.atInfo(Log.WATCHER).log("  (No hay watchers configurados)");
                } else {
                    cfg.watchers.forEach((name, w) -> {
                        String status = w.enable ? "✅ [ACTIVO]  " : "❌ [APAGADO] ";
                        Core.atInfo(Log.WATCHER).log(status + "Nombre: " + name);
                        Core.atInfo(Log.WATCHER).log("     |-- Origen:  " + w.path);
                        String dest = (w.path_destination == null || w.path_destination.isEmpty())
                                ? "NO DEFINIDA (Auto-detectar)"
                                : w.path_destination;
                        Core.atInfo(Log.WATCHER).log("     |-- Destino: " + dest);
                        if (w.path_sync) {
                            Core.atInfo(Log.WATCHER).log("     |-- Modo:    Sincronización Bidireccional (Espejo)");
                        }
                        Core.atInfo(Log.WATCHER).log("     ---------------------------------------------------");
                    });
                }
            }
            case "add" -> {
                if (args.isEmpty()) {
                    Core.atWarning(Log.WATCHER).log("Uso: core-watcher add <nombre> <ruta>");
                    return;
                }
                String[] subParts = args.split("\\s+", 2);
                if (subParts.length < 2) {
                    Core.atWarning(Log.WATCHER).log("Falta la ruta de destino.");
                    return;
                }
                String name = subParts[0];
                String destination = subParts[1];

                NexusConfig.Watcher newWatcher = NexusConfig.createWatcher(
                        destination,
                        WorkspaceSetup.relativize(WorkspaceSetup.getLocalModsPath()),
                        true
                );
                cfg.watchers.put(name, newWatcher);
                nexus.save();
                Core.atInfo(Log.WATCHER).log("Watcher '" + name + "' agregado con éxito.");
            }
            case "remove" -> {
                if (args.isEmpty()) {
                    Core.atWarning(Log.WATCHER).log("Uso: core-watcher remove <nombre>");
                    return;
                }
                if (cfg.watchers.remove(args) != null) {
                    nexus.save();
                    Core.atInfo(Log.WATCHER).log("Watcher '" + args + "' eliminado.");
                } else {
                    Core.atError(Log.WATCHER).log("No se encontró el watcher: " + args);
                }
            }
            default -> Core.atInfo(Log.WATCHER).log("Sub-comandos: list, add, remove");
        }
    }

    private boolean isInternalCommand(@NotNull String input) {
        String cmd = input.split(" ")[0].toLowerCase();
        return cmd.startsWith("core-") || cmd.equals("stop-server") ||
                cmd.equals("start-server") || cmd.equals("exit-core");
    }

    private void sendToProcess(String cmd) {
        if (currentProcess != null && currentProcess.isAlive() && currentWriter != null) {
            currentWriter.println(cmd);
        } else {
            Core.atWarning(Log.SYSTEM).log("No hay proceso activo. El comando '" + cmd + "' fue ignorado.");
        }
    }

    private void shutdownSystem() {
        Core.atWarning(Log.SYSTEM).log("Cerrando aplicación completa...");
        HealthMonitor.getInstance().stop();
        if (Server.getInstance() != null) Server.getInstance().stopServer();
        if (WatcherManager.getInstance() != null) WatcherManager.getInstance().stopAll();
        System.exit(0);
    }

    public void disconnectProcess() {
        this.currentProcess = null;
        this.currentWriter = null;
        Core.atWarning(Log.SYSTEM).log("Terminal desconectada del proceso.");
    }
}