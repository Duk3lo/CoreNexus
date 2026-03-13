package org.astral.core.logger;

import java.awt.Color;

public enum Log {
    SERVER("Hytale-Server", Color.GREEN),
    SYSTEM("System", new Color(189, 147, 249)),
    WATCHER("File-Watcher", Color.CYAN),
    CONFIG("Config-Manager", Color.YELLOW),
    CURSEFORGE("CurseForge", Color.ORANGE),
    GITHUB("GitHub-Service", new Color(240, 246, 252)),
    UPDATER("Auto-Updater", new Color(135, 206, 235)),
    HEALTH("Health-Monitor", new Color(255, 105, 180));

    public final String label;
    public final Color color;

    Log(String label, Color color) {
        this.label = label;
        this.color = color;
    }
}