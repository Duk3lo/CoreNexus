package org.astral.core.healing;

import org.astral.core.config.nexus.HealingConfig;
import org.astral.core.config.nexus.NexusConfig;
import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.astral.core.process.Server;
import org.astral.core.setup.WorkspaceSetup;
import org.astral.core.utility.Parser;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HealthMonitor {
    private static HealthMonitor instance;
    private ScheduledExecutorService scheduler;
    private long serverStartTime = 0;
    private int tpsStrikes = 0;

    private HealthMonitor() {}

    public static HealthMonitor getInstance() {
        if (instance == null) instance = new HealthMonitor();
        return instance;
    }

    public void start() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }

        HealingConfig config = WorkspaceSetup.getHealing().getConfig();
        if (config == null || !config.enable) {
            Core.atWarning(Log.HEALTH).log("Monitor de salud desactivado en la configuración.");
            return;
        }

        long intervalMillis = Parser.parseTime(config.check_interval);
        if (intervalMillis <= 0) intervalMillis = 60000;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Health-Monitor-Thread");
            t.setDaemon(true);
            return t;
        });

        Core.atInfo(Log.HEALTH).log("Monitor iniciado. Chequeos cada " + (intervalMillis / 1000) + " segundos.");
        scheduler.scheduleAtFixedRate(this::performCheck, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            Core.atInfo(Log.HEALTH).log("Monitor de salud detenido.");
        }
    }

    private void performCheck() {
        try {
            HealingConfig config = WorkspaceSetup.getHealing().getConfig();
            if (config == null || !config.enable || serverStartTime <= 0) return;

            long now = System.currentTimeMillis();
            long uptime = now - serverStartTime;
            long initialDelay = Parser.parseTime(config.initial_delay);

            if (uptime <= initialDelay) return;

            Server server = Server.getInstance();
            if (server != null && server.getExecutor().getProcess().isAlive()) {

                long scheduledTime = Parser.parseTime(config.scheduled_restart);
                if (scheduledTime > 0 && uptime >= scheduledTime) {
                    Core.atWarning(Log.HEALTH).log("Tiempo máximo alcanzado (" + config.scheduled_restart + "). Iniciando reinicio programado...");
                    executeRestart();
                    return;
                }

                server.getExecutor().sendCommand("world perf");
            }
        } catch (Exception e) {
            Core.atError(Log.HEALTH).log("Fallo en la rutina de chequeo: " + e.getMessage());
        }
    }

    public void notifyServerStarted() {
        this.serverStartTime = System.currentTimeMillis();
        this.tpsStrikes = 0;
        Core.atInfo(Log.HEALTH).log("Reloj de actividad del servidor reiniciado.");
    }

    public void processServerLog(String line) {
        HealingConfig config = WorkspaceSetup.getHealing().getConfig();
        if (config == null || !config.enable) return;

        if (line.contains("TPS (1 min):")) {
            try {
                String[] parts = line.split("Avg:");
                if (parts.length > 1) {
                    String avgStr = parts[1].split(",")[0].trim();
                    double avgTps = Double.parseDouble(avgStr);

                    if (avgTps < config.min_tps_threshold) {
                        tpsStrikes++;
                        Core.atWarning(Log.HEALTH).log("¡ALERTA! TPS promedio bajó a " + avgTps + ". Advertencia " + tpsStrikes + "/" + config.max_strikes);

                        if (tpsStrikes >= config.max_strikes) {
                            Core.atError(Log.HEALTH).log("Límite de fallos de rendimiento alcanzado. Iniciando reinicio de emergencia...");
                            executeRestart();
                        }
                    } else {
                        if (tpsStrikes > 0) {
                            Core.atInfo(Log.HEALTH).log("Rendimiento estabilizado (" + avgTps + " TPS). Alertas canceladas.");
                            tpsStrikes = 0;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void executeRestart() {
        this.serverStartTime = 0;
        this.tpsStrikes = 0;

        Server server = Server.getInstance();
        if (server != null) {
            server.stopServer();
        }

        NexusConfig cfg = WorkspaceSetup.getNexus().getConfig();
        if (cfg != null) {
            Core.atInfo(Log.HEALTH).log("Levantando servidor tras el reinicio...");
            Server.startServer(cfg.server_path, cfg.jar_name, cfg.args);
        }
    }

    public void printHealthStatus() {
        Core.atInfo(Log.HEALTH).log("=== Estado de Salud del Sistema ===");
        Runtime runtime = Runtime.getRuntime();
        long usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L;
        long maxMemoryMb = runtime.maxMemory() / 1048576L;

        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        double cpuLoad = osBean.getSystemLoadAverage();

        Core.atInfo(Log.HEALTH).log("[CORE] RAM Usada : " + usedMemoryMb + "MB / " + maxMemoryMb + "MB");
        Core.atInfo(Log.HEALTH).log("[CORE] Carga CPU : " + (cpuLoad < 0 ? "No disponible" : String.format("%.2f", cpuLoad)));

        HealingConfig config = WorkspaceSetup.getHealing().getConfig();
        boolean isEnabled = config != null && config.enable;
        Core.atInfo(Log.HEALTH).log("[MONITOR] Estado  : " + (isEnabled ? "✅ ACTIVO" : "❌ APAGADO"));
        if (isEnabled) {
            Core.atInfo(Log.HEALTH).log("[MONITOR] Fallos TPS: " + tpsStrikes + " / " + config.max_strikes);
        }
        Server server = Server.getInstance();
        if (server != null && server.getExecutor().getProcess().isAlive()) {
            Process p = server.getExecutor().getProcess();
            ProcessHandle handle = p.toHandle();
            String commandName = handle.info().command().orElse("Desconocido");
            String simpleName = commandName.substring(commandName.lastIndexOf(File.separator) + 1);

            String rawMem = getServerMemoryUsage(handle.pid());
            String formattedMem = formatMemory(rawMem);

            long cpuSeconds = handle.info().totalCpuDuration().map(Duration::toSeconds).orElse(0L);
            String cpuUsage = getServerCpuUsage(handle.pid());

            Core.atInfo(Log.HEALTH).log("[SERVER] Proceso  : " + simpleName + " (PID: " + handle.pid() + ")");
            Core.atInfo(Log.HEALTH).log("[SERVER] RAM Proc : " + formattedMem);
            Core.atInfo(Log.HEALTH).log("[SERVER] CPU Uso  : " + cpuUsage);
            Core.atInfo(Log.HEALTH).log("[SERVER] CPU Tiempo Total: " + cpuSeconds + "s");

            if (serverStartTime > 0) {
                long uptime = (System.currentTimeMillis() - serverStartTime) / 1000;
                Core.atInfo(Log.HEALTH).log("[SERVER] Uptime   : " + (uptime/3600) + "h " + ((uptime%3600)/60) + "m");
            }
            Core.atInfo(Log.HEALTH).log("[SERVER] Pidiendo TPS en vivo...");
            server.getExecutor().sendCommand("world perf");
        } else {
            Core.atWarning(Log.HEALTH).log("[SERVER] Estado   : ❌ APAGADO");
        }
        Core.atInfo(Log.HEALTH).log("===================================");
    }

    private String getServerCpuUsage(long pid) {
        String os = System.getProperty("os.name").toLowerCase();
        String pidStr = String.valueOf(pid);

        try {
            List<String> command = getCommandsConsult(os, pidStr);
            Process p = new ProcessBuilder(command).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                String result = "0%";
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.equalsIgnoreCase("PercentProcessorTime")) continue;
                    result = line + "%";
                    break;
                }
                return result;
            }
        } catch (Exception e) {
            return "N/A";
        }
    }

    private static @NotNull List<String> getCommandsConsult(@NotNull String os, String pidStr) {
        List<String> command = new ArrayList<>();

        if (os.contains("win")) {
            command.add("wmic");
            command.add("path");
            command.add("Win32_PerfFormattedData_PerfProc_Process");
            command.add("where");
            command.add("IDProcess=" + pidStr);
            command.add("get");
            command.add("PercentProcessorTime");
        } else {
            command.add("ps");
            command.add("-p");
            command.add(pidStr);
            command.add("-o");
            command.add("%cpu=");
        }
        return command;
    }

    private String getServerMemoryUsage(long pid) {
        String pidStr = String.valueOf(pid);
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            command.add("tasklist");
            command.add("/FI");
            command.add("PID eq " + pidStr);
            command.add("/NH");
        } else {
            command.add("ps");
            command.add("-o");
            command.add("rss=");
            command.add("-p");
            command.add(pidStr);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    String result = line.trim();
                    if (System.getProperty("os.name").toLowerCase().contains("win")) {
                        String[] parts = result.split("\\s+");
                        if (parts.length > 4) return parts[4];
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            return "N/A";
        }
        return "N/A";
    }

    private @NotNull String formatMemory(String kbStr) {
        if (kbStr == null || kbStr.equals("N/A")) return "N/A";
        try {
            long kb = Long.parseLong(kbStr.replaceAll("[^0-9]", ""));

            if (kb >= 1024 * 1024) {
                return String.format("%.2f GB", kb / (1024.0 * 1024.0));
            } else if (kb >= 1024) {
                return String.format("%.2f MB", kb / 1024.0);
            } else {
                return kb + " KB";
            }
        } catch (NumberFormatException e) {
            return kbStr + " KB";
        }
    }

}