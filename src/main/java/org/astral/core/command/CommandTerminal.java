package org.astral.core.command;

import org.astral.core.config.ConfigService;
import org.astral.core.config.nexus.NexusConfig;
import org.astral.core.file.WatcherManager;
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
        String[] parts = input.split("\\s+", 3); // Dividimos hasta en 3 partes
        String command = parts[0].toLowerCase();
        String subCommand = parts.length > 1 ? parts[1].toLowerCase() : "";
        String extraArgs = parts.length > 2 ? parts[2] : "";

        ConfigService<NexusConfig> nexus = WorkspaceSetup.getNexus();
        NexusConfig cfg = nexus.getConfig();

        switch (command) {
            case "core-watcher" -> handleWatcherCommand(subCommand, extraArgs, nexus);

            case "core-setpathserver" -> {
                if (subCommand.isEmpty()) { // Aquí subCommand actúa como el primer argumento
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
                    nexus.save(); // Guardamos el cambio en el YAML
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

                        // Log principal con nombre y estado
                        Core.atInfo(Log.WATCHER).log(status + "Nombre: " + name);

                        // Detalles de rutas (con sangría para que se vea ordenado)
                        Core.atInfo(Log.WATCHER).log("     |-- Origen:  " + w.path);

                        String dest = (w.path_destination == null || w.path_destination.isEmpty())
                                ? "NO DEFINIDA (Auto-detectar)"
                                : w.path_destination;
                        Core.atInfo(Log.WATCHER).log("     |-- Destino: " + dest);

                        // Opcional: mostrar si la sincronización bidireccional está activa
                        if (w.path_sync) {
                            Core.atInfo(Log.WATCHER).log("     |-- Modo:    Sincronización Bidireccional (Espejo)");
                        }

                        Core.atInfo(Log.WATCHER).log("     ---------------------------------------------------");
                    });
                }
            }
            case "add" -> {
                if (args.isEmpty()) {
                    Core.atWarning(Log.WATCHER).log("Uso: core-watcher add <nombre> <ruta_destino>");
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
                        WorkspaceSetup.getLocalModsPath().toString(),
                        destination,
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