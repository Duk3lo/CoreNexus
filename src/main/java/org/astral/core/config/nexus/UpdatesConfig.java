package org.astral.core.config.nexus;

public class UpdatesConfig {
    public CurseForgeUpdate curseforge = new CurseForgeUpdate();
    public GitHubUpdate github = new GitHubUpdate();
    public ServerUpdate server = new ServerUpdate();

    public static class CurseForgeUpdate {
        public boolean enable = true;
        public String check_interval = "12H";
    }

    public static class GitHubUpdate {
        public boolean enable = true;
        public String check_interval = "1D";
    }

    public static class ServerUpdate {
        public boolean enable = false;
        public String check_interval = "6H";
        public String check_command = "version check";
        public String update_trigger_phrase = "new update found";
        public String apply_command = "version apply";
    }

    public UpdatesConfig() {}
}