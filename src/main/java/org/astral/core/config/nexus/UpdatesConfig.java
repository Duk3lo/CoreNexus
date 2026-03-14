package org.astral.core.config.nexus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UpdatesConfig {
    public CurseForgeUpdate curseforge = new CurseForgeUpdate();
    public GitHubUpdate github = new GitHubUpdate();
    public ServerUpdate server = new ServerUpdate();

    public static class CurseForgeUpdate {
        public boolean enable = false;
        public String check_interval = "12H";
    }

    public static class GitHubUpdate {
        public boolean enable = false;
        public String check_interval = "1D";
    }

    public static class ServerUpdate {
        public boolean enable_periodic_check = false;
        public boolean enable_console_listener = true;

        public String check_interval = "6H";
        public String check_command = "update check";

        public String trigger_update_found = "new version found:";
        public String download_command = "update download";

        public List<String> trigger_download_complete = new ArrayList<>(Arrays.asList("100%", "to apply download use"));
        public String apply_command = "update apply --confirm";
    }

    public UpdatesConfig() {}
}