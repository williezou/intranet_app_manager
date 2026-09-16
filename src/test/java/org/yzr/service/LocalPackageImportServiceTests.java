package org.yzr.service;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.yzr.config.PackageImportProperties;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class LocalPackageImportServiceTests {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void requiresDedicatedImportToken() {
        PackageImportProperties properties = properties(true, temporaryFolder.getRoot());
        LocalPackageImportService service = new LocalPackageImportService(properties);

        assertTrue(service.isAuthorized("jenkins-import-secret"));
        assertFalse(service.isAuthorized("ordinary-user-token"));
        assertFalse(service.isAuthorized(null));
    }

    @Test
    public void preparesPrivateCopyInsideAllowedRootAndKeepsOriginal() throws Exception {
        File root = temporaryFolder.newFolder("jenkins-workspace");
        File apk = createZip(root, "app.apk", "payload.txt", "package-content");

        LocalPackageImportService service = new LocalPackageImportService(properties(true, root));
        Path prepared = service.prepare(apk.getAbsolutePath());
        try {
            assertNotEquals(apk.toPath().toRealPath(), prepared);
            assertTrue(apk.exists());
            assertArrayEquals(Files.readAllBytes(apk.toPath()), Files.readAllBytes(prepared));
        } finally {
            service.cleanup(prepared);
        }
        assertFalse(Files.exists(prepared));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPackageOutsideAllowedRoot() throws Exception {
        File root = temporaryFolder.newFolder("jenkins-workspace");
        File outside = createZip(temporaryFolder.getRoot(), "outside.apk", "payload.txt", "data");

        new LocalPackageImportService(properties(true, root)).prepare(outside.getAbsolutePath());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsImportWhenDisabled() throws Exception {
        File root = temporaryFolder.newFolder("jenkins-workspace");
        File apk = createZip(root, "app.apk", "payload.txt", "data");

        new LocalPackageImportService(properties(false, root)).prepare(apk.getAbsolutePath());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPackageLargerThanConfiguredLimit() throws Exception {
        File root = temporaryFolder.newFolder("jenkins-workspace");
        File apk = createZip(root, "app.apk", "payload.txt", "data");
        PackageImportProperties properties = properties(true, root);
        properties.setMaxFileSizeBytes(1);

        new LocalPackageImportService(properties).prepare(apk.getAbsolutePath());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsArchiveWhoseExpandedSizeExceedsLimit() throws Exception {
        File root = temporaryFolder.newFolder("jenkins-workspace");
        File ipa = createZip(root, "app.ipa", "Payload/data.bin", "1234567890");
        PackageImportProperties properties = properties(true, root);
        properties.setMaxExtractedSizeBytes(5);

        new LocalPackageImportService(properties).prepare(ipa.getAbsolutePath());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsArchiveWithTooManyEntries() throws Exception {
        File root = temporaryFolder.newFolder("jenkins-workspace");
        File apk = new File(root, "app.apk");
        try (ZipOutputStream outputStream = new ZipOutputStream(new FileOutputStream(apk))) {
            outputStream.putNextEntry(new ZipEntry("one"));
            outputStream.closeEntry();
            outputStream.putNextEntry(new ZipEntry("two"));
            outputStream.closeEntry();
        }
        PackageImportProperties properties = properties(true, root);
        properties.setMaxArchiveEntries(1);

        new LocalPackageImportService(properties).prepare(apk.getAbsolutePath());
    }

    private PackageImportProperties properties(boolean enabled, File root) {
        PackageImportProperties properties = new PackageImportProperties();
        properties.setEnabled(enabled);
        properties.setToken("jenkins-import-secret");
        properties.setAllowedRoots(Collections.singletonList(root.getAbsolutePath()));
        return properties;
    }

    private File createZip(File root, String name, String entryName, String content) throws Exception {
        File file = new File(root, name);
        try (ZipOutputStream outputStream = new ZipOutputStream(new FileOutputStream(file))) {
            outputStream.putNextEntry(new ZipEntry(entryName));
            outputStream.write(content.getBytes(StandardCharsets.UTF_8));
            outputStream.closeEntry();
        }
        return file;
    }
}
