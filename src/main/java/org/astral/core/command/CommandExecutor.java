package org.astral.core.command;

import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

public class CommandExecutor<T> {

    private final List<String> command;
    private final File directory;

    private volatile Process process;
    private volatile PrintWriter processWriter;

    public CommandExecutor(List<String> command, File directory) {
        this.command = command;
        this.directory = directory;
    }

    public void run(Function<String, T> parser, Consumer<T> callback) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(directory);
            pb.redirectErrorStream(true);

            this.process = pb.start();
            this.processWriter = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)),
                    true
            );

            Thread readerThread = getThread(parser, callback);
            readerThread.start();

            CommandTerminal.getInstance().connectProcess(this.process);
        } catch (IOException e) {
            Core.atError(Log.SYSTEM).log("Error fatal al iniciar el proceso, " + e);
        }
    }

    private @NotNull Thread getThread(Function<String, T> parser, Consumer<T> callback) {
        Thread readerThread = new Thread(() -> {
            Process localProcess = this.process;
            if (localProcess == null) return;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(localProcess.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    T result = parser.apply(line);
                    callback.accept(result);
                }
            } catch (IOException ignored) {
            }
        }, "Process-Reader");

        readerThread.setDaemon(true);
        return readerThread;
    }

    public Process getProcess() {
        return process;
    }

    public void stop() {
        Process localProcess = this.process;
        if (localProcess != null && localProcess.isAlive()) {
            try {
                if (processWriter != null) {
                    Core.atInfo(Log.SERVER).log("Enviando comando 'stop' al servidor...");
                    processWriter.println("stop");
                }

                if (localProcess.waitFor(15, TimeUnit.SECONDS)) {
                    Core.atInfo(Log.SERVER).log("El servidor se cerró limpiamente.");
                } else {
                    Core.atWarning(Log.SERVER).log("El servidor no respondió al 'stop'. Forzando cierre...");
                    localProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Core.atError(Log.SYSTEM).log("Error durante el apagado: " + e.getMessage());
                localProcess.destroyForcibly();
            } finally {
                CommandTerminal.getInstance().disconnectProcess();
            }
        }
    }

    public void sendCommand(String cmd) {
        Process localProcess = this.process;
        PrintWriter writer = this.processWriter;

        if (localProcess != null && localProcess.isAlive() && writer != null) {
            try {
                writer.println(cmd);
            } catch (Exception e) {
                Core.atError(Log.SYSTEM).log("Error enviando comando: " + e.getMessage());
            }
        }
    }
}