package org.astral.core.logger;

import java.awt.Color;

public enum Log {
    SERVER("Hytale-Server", Color.GREEN),
    SYSTEM("System", new Color(189, 147, 249)), // Purple (Dracula Theme)
    WATCHER("File-Watcher", Color.CYAN),
    CONFIG("Config-Manager", Color.YELLOW),
    CURSEFORGE("CurseForge", Color.ORANGE);

    public final String label;
    public final Color color;

    Log(String label, Color color) {
        this.label = label;
        this.color = color;
    }
}