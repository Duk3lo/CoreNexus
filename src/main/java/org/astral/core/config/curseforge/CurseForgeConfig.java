package org.astral.core.config.curseforge;

import org.astral.core.setup.WorkspaceSetup;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CurseForgeConfig {
    public String global_api_key = "INSERT_YOUR_KEY_HERE";
    public int global_game_id = 70216;

    public boolean auto_search_untracked_mods = true;

    public List<String> ignored_untracked_files = new ArrayList<>();

    public Map<String, CurseForgeResource> resources = new LinkedHashMap<>();

    public CurseForgeConfig() {
        CurseForgeResource defaultResource = createResource(
                123456,
                WorkspaceSetup.relativize(WorkspaceSetup.getLocalModsPath())
        );
        resources.put(WorkspaceSetup.getDefaultCurseForgePrefix(), defaultResource);
    }

    public static class CurseForgeResource {
        public boolean enable;
        public int project_id;
        public String destination_path;
        public boolean keep_backup;
        public int local_file_id;
        public String local_file_name;
        public boolean verify_file_integrity;

        public CurseForgeResource() {
        }
    }

    public static @NotNull CurseForgeResource createResource(int projectId, String dest) {
        CurseForgeResource r = new CurseForgeResource();
        r.enable = true;
        r.project_id = projectId;
        r.destination_path = dest;
        r.keep_backup = false;
        r.local_file_id = 0;
        r.local_file_name = "";
        r.verify_file_integrity = false;
        return r;
    }
}