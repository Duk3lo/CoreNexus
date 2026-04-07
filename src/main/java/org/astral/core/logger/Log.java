package org.astral.core.logger;
import org.jetbrains.annotations.NotNull;

public enum Log {
    SERVER("Hytale-Server", ansi(80, 200, 120)),
    SYSTEM("System", ansi(189, 147, 249)),
    WATCHER("File-Watcher", ansi(120, 180, 255)),
    CONFIG("Config-Manager", ansi(255, 255, 120)),
    CURSEFORGE("CurseForge", ansi(255, 165, 0)),
    GITHUB("GitHub-Service", ansi(240, 246, 252)),
    UPDATER("Auto-Updater", ansi(135, 206, 235)),
    HEALTH("Health-Monitor", ansi(255, 105, 180));

    private final String label;
    private final String ansi;

    Log(String label, String ansi) {
        this.label = label;
        this.ansi = ansi;
    }

    public String getLabel() {
        return label;
    }

    public String getAnsi() {
        return ansi;
    }

    private static @NotNull String ansi(int r, int g, int b) {
        return "\u001B[38;2;" + r + ';' + g + ';' + b + 'm';
    }
}