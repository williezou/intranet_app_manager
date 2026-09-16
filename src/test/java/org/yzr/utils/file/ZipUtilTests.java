package org.yzr.utils.file;

import org.apache.commons.io.FileUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class ZipUtilTests {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rejectsEntryOutsideExtractionDirectory() throws Exception {
        String escapedName = "zip-slip-" + UUID.randomUUID() + ".txt";
        File archive = createZip("../" + escapedName, "payload");
        Path escaped = Paths.get(FileUtils.getTempDirectoryPath(), escapedName);

        try {
            assertNull(ZipUtil.unzip(archive.getAbsolutePath(), 10, 1024));
            assertFalse(Files.exists(escaped));
        } finally {
            Files.deleteIfExists(escaped);
        }
    }

    @Test
    public void rejectsExpandedContentOverRuntimeLimit() throws Exception {
        File archive = createZip("Payload/data.bin", "1234567890");

        assertNull(ZipUtil.unzip(archive.getAbsolutePath(), 10, 5));
    }

    private File createZip(String entryName, String content) throws Exception {
        File archive = temporaryFolder.newFile("archive-" + UUID.randomUUID() + ".zip");
        try (ZipOutputStream outputStream = new ZipOutputStream(new FileOutputStream(archive))) {
            outputStream.putNextEntry(new ZipEntry(entryName));
            outputStream.write(content.getBytes(StandardCharsets.UTF_8));
            outputStream.closeEntry();
        }
        return archive;
    }
}
