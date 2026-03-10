package org.astral.core.config.curseforge;

import java.util.ArrayList;
import java.util.List;

public class CurseForgeConfig {
    public String apiKey = "INSERT_YOUR_KEY_HERE";
    public List<Integer> modIds = new ArrayList<>();
    public boolean autoUpdate = true;

    public CurseForgeConfig() {
        modIds.add(12345);
    }
}