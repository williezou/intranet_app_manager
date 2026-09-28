package org.yzr.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalStorageTests {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void identicalPngFilesShareTheSameDiskData() throws Exception {
        LocalStorage storage = storage();
        byte[] content = "same-png-content".getBytes(StandardCharsets.UTF_8);

        storage.store(new ByteArrayInputStream(content), content.length, "application/png", "first.png");
        storage.store(new ByteArrayInputStream(content), content.length, "application/png", "second.png");

        Path first = storage.load("first.png");
        Path second = storage.load("second.png");
        assertTrue(Files.isSameFile(first, second));

        storage.delete("first.png");
        assertArrayEquals(content, Files.readAllBytes(second));
    }

    @Test
    public void differentPngFilesRemainIndependent() throws Exception {
        LocalStorage storage = storage();
        byte[] firstContent = "first-png".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "second-png".getBytes(StandardCharsets.UTF_8);

        storage.store(new ByteArrayInputStream(firstContent), firstContent.length, "application/png", "first.png");
        storage.store(new ByteArrayInputStream(secondContent), secondContent.length, "application/png", "second.png");

        assertFalse(Files.isSameFile(storage.load("first.png"), storage.load("second.png")));
    }

    private LocalStorage storage() {
        LocalStorage storage = new LocalStorage();
        storage.setStoragePath(temporaryFolder.getRoot().getAbsolutePath());
        return storage;
    }
}
