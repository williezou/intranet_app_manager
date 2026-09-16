package org.yzr.utils.file;

import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ZipUtil {
    public static final int MAX_ENTRIES = 20000;
    public static final long MAX_EXTRACTED_BYTES = 2L * 1024L * 1024L * 1024L;

    public static String unzip(String path) {
        return unzip(path, MAX_ENTRIES, MAX_EXTRACTED_BYTES);
    }

    static String unzip(String path, int maxEntries, long maxExtractedBytes) {
        Path destDir = null;
        try {
            destDir = Files.createTempDirectory("ipa-unpack-");
            long extractedBytes = 0;
            int entryCount = 0;
            try (ZipFile zipFile = new ZipFile(path)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    entryCount++;
                    if (entryCount > maxEntries) {
                        throw new IOException("ZIP entry count exceeds limit");
                    }

                    Path target = safeTarget(destDir, entry.getName());
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }

                    Files.createDirectories(target.getParent());
                    try (InputStream inputStream = zipFile.getInputStream(entry);
                         OutputStream outputStream = Files.newOutputStream(target,
                                 StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = inputStream.read(buffer)) != -1) {
                            extractedBytes += length;
                            if (extractedBytes > maxExtractedBytes) {
                                throw new IOException("ZIP extracted size exceeds limit");
                            }
                            outputStream.write(buffer, 0, length);
                        }
                    }
                }
            }
            return destDir.toString();
        } catch (Exception e) {
            e.printStackTrace();
            if (destDir != null) {
                try {
                    FileUtils.deleteDirectory(destDir.toFile());
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    private static Path safeTarget(Path destination, String entryName) throws IOException {
        Path target = destination.resolve(entryName.replace('\\', '/')).normalize();
        if (!target.startsWith(destination)) {
            throw new IOException("ZIP entry is outside destination: " + entryName);
        }
        return target;
    }
}
