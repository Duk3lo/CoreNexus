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
        if (path == null || path.isBlank()) {
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
        List<String> cmd = getStrings(jarName, args);

        this.executor = new CommandExecutor<>(cmd, directory);
        this.executor.run(line -> line, rawLine -> {
            if (rawLine == null || rawLine.isBlank()) return;
            String repairedLine = repairBrokenAnsi(rawLine);
            String visualLine = repairedLine + "\u001B[0m";
            Core.atInfo(Log.SERVER).log(visualLine);
            String cleanLine = cleanAnsi(repairedLine).strip();
            if (!cleanLine.isEmpty()) {
                HealthMonitor.getInstance().processServerLog(cleanLine);
                Updater.getInstance().processServerLogForUpdates(cleanLine);
            }
        });

        HealthMonitor.getInstance().notifyServerStarted();
    }

    private static @NotNull List<String> getStrings(String jarName, String args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Djansi.force=true");
        cmd.add("-Dlog4j.skipJansi=false");
        cmd.add("-Dlog4j2.skipJansi=false");
        cmd.add("-Dterminal.jline=false");
        cmd.add("-Dterminal.ansi=true");
        cmd.add("-jar");
        cmd.add(jarName);
        if (args != null && !args.isBlank()) {
            String[] splitArgs = args.split(" ");
            for (String arg : splitArgs) {
                if (!arg.isBlank()) {
                    cmd.add(arg);
                }
            }
        }
        return cmd;
    }

    private @NotNull String repairBrokenAnsi(@NotNull String text) {
        return text.replaceAll("(?<!\u001B)\\[(\\d{1,3}(?:;\\d{1,3})*[mK])", "\u001B[$1");
    }

    private @NotNull String cleanAnsi(String text) {
        if (text == null) return "";
        return text.replaceAll("\u001B\\[[;\\d]*[a-zA-Z]", "");
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