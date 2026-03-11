package org.astral.core.config.github;

import org.astral.core.setup.WorkspaceSetup;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

public class GitHubConfig {
    public String personal_token = "";
    public Map<String, RepositoryResource> resources = new LinkedHashMap<>();

    public GitHubConfig() {
        RepositoryResource defaultResource = createResource(
                "usuario/repositorio",
                WorkspaceSetup.relativize(WorkspaceSetup.getLocalModsPath())
        );
        resources.put(WorkspaceSetup.getDefaultGitHubPrefix(), defaultResource);
    }

    public static class RepositoryResource {
        public boolean enable;
        public String repo_slug;
        public String custom_token;
        public String destination_path;
        public boolean keep_backup;
        public String local_version_tag;
        public String local_file_name;

        public RepositoryResource() {
        }
    }

    public static @NotNull RepositoryResource createResource(String slug, String dest) {
        RepositoryResource r = new RepositoryResource();
        r.enable = true;
        r.repo_slug = slug;
        r.custom_token = "";
        r.destination_path = dest;
        r.keep_backup = true;
        r.local_version_tag = "";
        r.local_file_name = "";
        return r;
    }
}