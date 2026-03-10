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

                if (scanner.hasNextLine()) {
                    String cmd = scanner.nextLine();

                    if (cmd.startsWith(("Core-SetPathServer "))){

                    }

                    if (cmd.startsWith("Core-SetJar ")) {
                        ConfigService<NexusConfig> nexus = WorkspaceSetup.getNexus();

                        // Ejemplo: "Core-SetJar nuevo_server.jar"
                        String nuevoJar = cmd.replace("Core-SetJar ", "").trim();

                        // 1. Modificamos el objeto en memoria
                        nexus.getConfig().jar_name = nuevoJar;

                        // 2. Lo guardamos físicamente en el config.yml
                        nexus.save();

                        Core.atInfo(Log.CONFIG).log("Nombre del JAR actualizado y guardado: " + nuevoJar);
                        continue;
                    }

                    if (cmd.equalsIgnoreCase("Core-Reload")) {
                        Core.atInfo(Log.CONFIG).log("Recargando configuración desde el disco...");
                        ConfigService<NexusConfig> nexus = WorkspaceSetup.getNexus();

                        // Simplemente llamamos al método load que ya tienes
                        nexus.load();

                        // Opcional: Mostrar un dato para confirmar la recarga
                        String jarActual = nexus.getConfig().jar_name;
                        Core.atInfo(Log.CONFIG).log("Configuración actualizada. JAR objetivo: " + jarActual);
                        continue;
                    }

                    if (cmd.equalsIgnoreCase("Core-Status")) {
                        Core.atInfo(Log.SYSTEM).log("--- Estado del Core ---");
                        Core.atInfo(Log.SYSTEM).log("Path: " + Server.getInstance().getDirectory());
                        Core.atInfo(Log.SYSTEM).log("JAR: " + Server.getInstance().getJarName());
                        Core.atInfo(Log.SYSTEM).log("args: " + Server.getInstance().getArgs());
                        continue;
                    }

                    if (cmd.equalsIgnoreCase("Stop-Server")) {
                        Server server = Server.getInstance();
                        if (server != null) {
                            server.stopServer();
                        } else {
                            Core.atWarning(Log.SYSTEM).log("El servidor ya está apagado.");
                        }
                        continue;
                    }

                    if (cmd.equalsIgnoreCase("Start-Server")) {
                        NexusConfig cfg = WorkspaceSetup.getNexus().getConfig();
                        Server.startServer(cfg.server_path, cfg.jar_name, cfg.args);
                        continue;
                    }

                    if (cmd.equalsIgnoreCase("Exit-Core")) {
                        Core.atWarning(Log.SYSTEM).log("Cerrando aplicación completa...");
                        if (Server.getInstance() != null) Server.getInstance().stopServer();
                        if (WatcherManager.getInstance() != null) WatcherManager.getInstance().stopAll();
                        System.exit(0);
                        break;
                    }

                    if (currentProcess != null && currentProcess.isAlive() && currentWriter != null) {
                        currentWriter.println(cmd);
                    } else {
                        Core.atWarning(Log.SYSTEM).log("No hay proceso activo. Usa 'Start-Server' para iniciar.");
                    }
                }
            }
        }, "Global-Terminal");
        terminalThread.setDaemon(false);
        terminalThread.start();
    }

    public void disconnectProcess() {
        this.currentProcess = null;
        this.currentWriter = null;
        Core.atWarning(Log.SYSTEM).log("Terminal desconectada del proceso.");
    }
}