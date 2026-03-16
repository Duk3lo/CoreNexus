# CoreNexus

CoreNexus is a simple server management wrapper designed to automate administration, synchronize resources, and monitor system health. It provides a centralized interface to manage server lifecycles and external dependencies.

## Requirements
* **Java Runtime:** This application requires Java 25 or higher.
* **License:** Licensed under the GNU General Public License v3.0 (GPL-3.0).

## Installation and Auto-Detection
For optimal integration, place the CoreNexus executable in the root directory of your server (the same folder as your server's primary JAR file).

The system uses an intelligent auto-detection routine to locate the server path and executable files automatically. If the wrapper is placed in a different directory, manual configuration of paths will be required using internal commands.

## Execution
To run the application, use the following command in your terminal:

```bash
java -jar CoreNexus-<version>.jar
```

---

## Command Reference

### Server Control
* **start-server**: Initializes the server process using the configured JAR and arguments.
* **stop-server**: Sends a graceful shutdown signal to the running server.
* **exit-core**: Shuts down the server, stops all monitors, and closes the CoreNexus wrapper.

### Core Configuration
* **core-status**: Displays the current operational status, including paths and active watchers.
* **core-reload**: Reloads all configuration files and restarts internal services without exiting.
* **core-setjar <name.jar>**: Manually defines the filename of the server executable.
* **core-setpathserver <path>**: Manually sets the absolute directory for the server files.

### Update Management
* **core-updater <enable|disable> <module>**: Controls automated background updates for specific modules (github, curseforge, server, or all).
* **core-updater restart**: Refreshes the update schedules according to the current configuration.

### System Health
* **core-health status**: Provides real-time data on CPU usage, Memory (RAM) consumption, and performance alerts.
* **core-health enable <true|false>**: Activates or deactivates the automated health monitoring system.

### File Watcher
* **core-watcher list**: Lists all directories currently being observed for changes.
* **core-watcher add <path> [name]**: Creates a new rule to monitor a local path and sync it with the server.
* **core-watcher remove <name>**: Removes an existing observation rule.
* **core-watcher enable <name> <true|false>**: Pauses or resumes a specific watcher.

### CurseForge Integration
* **core-curseforge add <id|name>**: Adds a mod to the tracking database for automated updates.
* **core-curseforge sync-all**: Triggers an immediate update check for all registered CurseForge mods.
* **core-curseforge remove <key>**: Deletes a mod from the update registry.
* **core-curseforge restore <key>**: Rolls back a mod to its previous version from the backup directory.

### GitHub Integration
* **core-github add <user/repo>**: Registers a repository to track and download its latest releases.
* **core-github sync <key>**: Manually checks for updates for a specific repository.
* **core-github sync-all**: Runs an update check across all registered GitHub projects.

---
*CoreNexus — Built for stability and automated server administration.*
