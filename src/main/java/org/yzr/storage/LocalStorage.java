package org.yzr.storage;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 服务器本地对象存储服务
 */
public class LocalStorage implements IStorage {


    private final Log logger = LogFactory.getLog(LocalStorage.class);

    private String storagePath;
    private String address;

    private Path rootLocation;

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;

        this.rootLocation = Paths.get(storagePath);
        try {
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    @Override
    public void store(InputStream inputStream, long contentLength, String contentType, String keyName) {
        Path target = rootLocation.resolve(keyName);
        try {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            if (keyName.toLowerCase(Locale.ROOT).endsWith(".png")) {
                try {
                    deduplicatePng(target, keyName);
                } catch (IOException e) {
                    // 去重失败不能影响正常上传，保留刚写入的文件即可。
                    logger.warn("Failed to deduplicate PNG " + keyName + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file " + keyName, e);
        }
    }

    /**
     * 相同 PNG 保留各自的存储 key，但通过硬链接共享同一份磁盘数据。
     * 不支持硬链接时保留刚写入的普通文件，不影响上传结果。
     */
    private synchronized void deduplicatePng(Path target, String keyName) throws IOException {
        Path duplicate = findDuplicatePng(target);
        if (duplicate == null) {
            return;
        }

        Path link = rootLocation.resolve("." + keyName + "." + UUID.randomUUID() + ".link");
        try {
            Files.createLink(link, duplicate);
            try {
                Files.move(link, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(link, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | UnsupportedOperationException e) {
            // 目标文件仍然存在；文件系统不支持硬链接时仅跳过去重。
            logger.warn("Failed to deduplicate PNG " + keyName + ": " + e.getMessage());
        } finally {
            Files.deleteIfExists(link);
        }
    }

    private Path findDuplicatePng(Path target) throws IOException {
        long size = Files.size(target);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(rootLocation)) {
            for (Path candidate : files) {
                if (candidate.equals(target)
                        || !candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")
                        || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(candidate) != size) {
                    continue;
                }
                if (FileUtils.contentEquals(target.toFile(), candidate.toFile())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    @Override
    public Stream<Path> loadAll() {
        try {
            return Files.walk(rootLocation, 1)
                    .filter(path -> !path.equals(rootLocation))
                    .map(path -> rootLocation.relativize(path));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read stored files", e);
        }

    }

    @Override
    public Path load(String filename) {
        return rootLocation.resolve(filename);
    }

    @Override
    public Resource loadAsResource(String filename) {
        try {
            Path file = load(filename);
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() || resource.isReadable()) {
                return resource;
            } else {
                return null;
            }
        } catch (MalformedURLException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void delete(String filename) {
        Path file = load(filename);
        try {
            Files.delete(file);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    @Override
    public String generateUrl(String keyName) {

        return address + keyName;
    }
}
