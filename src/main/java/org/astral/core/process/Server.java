package org.astral.core.process;

import org.astral.core.api.Updater;
import org.astral.core.command.CommandExecutor;
import org.astral.core.healing.HealthMonitor;
import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public final class Server {

    private static Server instance = null;
    private final CommandExecutor<String> executor;

    public static void startServer(String path, String jar, String args) {
        if (path == null || path.trim().isEmpty()) {
            Core.atError(Log.SERVER).log("No se puede iniciar el servidor: La ruta está vacía en la configuración.");
            return;
        }

        File directory = new File(path);

        if (instance != null && instance.executor.getProcess() != null && instance.executor.getProcess().isAlive()) {
            Core.atWarning(Log.SERVER).log("El servidor ya está en ejecución.");
            return;
        }
        if (!directory.exists() || !directory.isDirectory()) {
            Core.atError(Log.SERVER).log("La ruta proporcionada no es un directorio válido.");
            return;
        }
        File jarFile = new File(directory, jar);
        if (!jarFile.exists()) {
            Core.atError(Log.SERVER).log("No se encontró " + jar + " en: " + directory.getAbsolutePath());
            return;
        }
        instance = new Server(directory, jar, args);
        Core.atInfo(Log.SERVER).log("Nueva instancia de servidor iniciada.");
    }

    private Server(File directory, String jarName, String args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-jar");
        cmd.add(jarName);
        if (args != null && !args.isEmpty()) {
            String[] splitArgs = args.split(" ");
            for (String arg : splitArgs) {
                if (!arg.trim().isEmpty()) {
                    cmd.add(arg);
                }
            }
        }
        this.executor = new CommandExecutor<>(cmd, directory);
        this.executor.run(line -> line, rawLine -> {
            String cleanLine = cleanAnsi(rawLine).trim();
            if (!cleanLine.isEmpty()) {
                Core.atInfo(Log.SERVER).log(cleanLine);
                HealthMonitor.getInstance().processServerLog(cleanLine);
                Updater.getInstance().processServerLogForUpdates(cleanLine);
            }
        });
        HealthMonitor.getInstance().notifyServerStarted();
    }

    private @NotNull String cleanAnsi(String text) {
        if (text == null) return "";
        return text.replaceAll("\u001B\\[[;\\d]*[A-Za-z]", "");
    }

    public CommandExecutor<String> getExecutor() {
        return executor;
    }

    public void stopServer() {
        if (executor != null) {
            executor.stop();
        }
        instance = null;
        Core.atInfo(Log.SERVER).log("Instance Server Close.");
    }

    public static Server getInstance() {
        return instance;
    }
}