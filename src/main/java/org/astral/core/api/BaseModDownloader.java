package org.astral.core.api;

import org.astral.core.logger.Core;
import org.astral.core.logger.Log;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

public abstract class BaseModDownloader {

    protected void performBackupAndReplace(Path destinationDir, String oldFileName, Path downloadedFile, boolean keepBackup, Path backupDir, Log logTag) throws Exception {
        if (!Files.exists(destinationDir)) Files.createDirectories(destinationDir);

        if (oldFileName != null && !oldFileName.isEmpty()) {
            Path oldFile = destinationDir.resolve(oldFileName);
            if (Files.exists(oldFile)) {
                if (keepBackup) {
                    if (!Files.exists(backupDir)) Files.createDirectories(backupDir);
                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                    Path backupFile = backupDir.resolve(oldFileName + ".backup_" + timeStamp);
                    Files.copy(oldFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
                    Core.atInfo(logTag).log("Backup guardado: " + backupFile.getFileName());
                }
                Files.delete(oldFile);
                Core.atInfo(logTag).log("Versión antigua eliminada.");
            }
        }

        Path finalDestination = destinationDir.resolve(downloadedFile.getFileName());
        Files.move(downloadedFile, finalDestination, StandardCopyOption.REPLACE_EXISTING);
        Core.atInfo(logTag).log("Nuevo mod instalado: " + finalDestination.getFileName());
    }

    protected void downloadStream(InputStream in, Path destination) throws Exception {
        try (InputStream input = in;
             FileOutputStream out = new FileOutputStream(destination.toFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }
}