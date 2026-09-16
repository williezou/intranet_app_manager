package org.yzr.service;

import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.yzr.config.PackageImportProperties;
import org.yzr.utils.file.FileType;
import org.yzr.utils.file.FileUtil;
import org.yzr.utils.file.ZipUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 校验本机安装包路径。只有配置的 Jenkins 工作目录中的 APK/IPA 才允许被导入。
 */
@Service
public class LocalPackageImportService {
    private final PackageImportProperties properties;

    public LocalPackageImportService(PackageImportProperties properties) {
        this.properties = properties;
    }

    /**
     * 使用独立于普通用户 token 的密钥授权 Jenkins 本机导入。
     */
    public boolean isAuthorized(String token) {
        String expected = properties.getToken();
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(token)) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 将受信任目录中的安装包复制到服务私有临时文件，并对复制后的同一文件执行校验。
     */
    public Path prepare(String filePath) throws IOException {
        if (!properties.isEnabled()) {
            throw new IllegalArgumentException("本机文件导入功能未启用");
        }
        if (!StringUtils.hasText(filePath)) {
            throw new IllegalArgumentException("filePath 不能为空");
        }

        List<String> allowedRoots = properties.getAllowedRoots();
        if (allowedRoots == null || allowedRoots.isEmpty()) {
            throw new IllegalArgumentException("未配置允许导入的目录");
        }

        // toRealPath 会拒绝不存在的文件并解析 .. 和符号链接，避免越权读取其他目录。
        validateLimits();

        Path source = Paths.get(filePath).toRealPath();
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IllegalArgumentException("安装包不存在或不可读");
        }
        if (!isInAllowedRoot(source, allowedRoots)) {
            throw new IllegalArgumentException("安装包不在允许导入的目录中");
        }

        String extension = FilenameUtils.getExtension(source.getFileName().toString());
        if (!("apk".equalsIgnoreCase(extension) || "ipa".equalsIgnoreCase(extension))) {
            throw new IllegalArgumentException("只允许导入 APK 或 IPA 文件");
        }

        Path prepared = Files.createTempFile("package-import-", "." + extension.toLowerCase());
        try {
            ensureFreeSpace(prepared);
            copyWithLimit(source, prepared);
            if (FileUtil.getType(prepared.toString()) != FileType.ZIP) {
                throw new IllegalArgumentException("文件内容不是有效的 APK/IPA 压缩包");
            }
            validateArchive(prepared);
            return prepared;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(prepared);
            throw e;
        }
    }

    private boolean isInAllowedRoot(Path source, List<String> allowedRoots) throws IOException {
        boolean hasConfiguredRoot = false;
        for (String rootValue : allowedRoots) {
            if (!StringUtils.hasText(rootValue)) {
                continue;
            }
            hasConfiguredRoot = true;
            try {
                Path root = Paths.get(rootValue.trim()).toRealPath();
                if (Files.isDirectory(root) && source.startsWith(root)) {
                    return true;
                }
            } catch (IOException ignored) {
                // 某个挂载点暂时不可用时，继续检查其余允许目录。
            }
        }
        if (!hasConfiguredRoot) {
            throw new IllegalArgumentException("未配置有效的允许导入目录");
        }
        return false;
    }

    private void validateLimits() {
        if (properties.getMaxFileSizeBytes() <= 0
                || properties.getMaxExtractedSizeBytes() <= 0
                || properties.getMaxArchiveEntries() <= 0
                || properties.getMinFreeSpaceBytes() < 0) {
            throw new IllegalArgumentException("本机导入大小限制配置无效");
        }
        if (properties.getMaxFileSizeBytes() > Integer.MAX_VALUE
                || properties.getMaxExtractedSizeBytes() > ZipUtil.MAX_EXTRACTED_BYTES
                || properties.getMaxArchiveEntries() > ZipUtil.MAX_ENTRIES) {
            throw new IllegalArgumentException("本机导入大小限制超过系统安全上限");
        }
    }

    private void ensureFreeSpace(Path target) throws IOException {
        long requiredSpace;
        try {
            requiredSpace = Math.addExact(properties.getMinFreeSpaceBytes(),
                    Math.addExact(properties.getMaxFileSizeBytes(), properties.getMaxExtractedSizeBytes()));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("本机导入磁盘空间配置无效", e);
        }
        if (Files.getFileStore(target).getUsableSpace() < requiredSpace) {
            throw new IllegalArgumentException("磁盘可用空间不足，已停止导入");
        }
    }

    private void copyWithLimit(Path source, Path target) throws IOException {
        long maxBytes = properties.getMaxFileSizeBytes();
        if (Files.size(source) > maxBytes) {
            throw new IllegalArgumentException("安装包超过允许大小");
        }

        Set<OpenOption> options = new HashSet<>();
        options.add(StandardOpenOption.READ);
        options.add(LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(source, options);
             InputStream inputStream = Channels.newInputStream(channel);
             OutputStream outputStream = Files.newOutputStream(target,
                     StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            long copied = 0;
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                copied += length;
                if (copied > maxBytes) {
                    throw new IllegalArgumentException("安装包超过允许大小");
                }
                outputStream.write(buffer, 0, length);
            }
        }
    }

    private void validateArchive(Path archive) throws IOException {
        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            int entryCount = 0;
            long extractedSize = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                entryCount++;
                if (entryCount > properties.getMaxArchiveEntries()) {
                    throw new IllegalArgumentException("压缩包文件数量超过限制");
                }
                validateEntryPath(entry.getName());
                if (!entry.isDirectory()) {
                    long entrySize = entry.getSize();
                    if (entrySize < 0) {
                        throw new IllegalArgumentException("压缩包包含无法确定大小的文件");
                    }
                    if (entrySize > properties.getMaxExtractedSizeBytes() - extractedSize) {
                        throw new IllegalArgumentException("压缩包解压后大小超过限制");
                    }
                    extractedSize += entrySize;
                }
            }
        } catch (ZipException e) {
            throw new IllegalArgumentException("文件不是有效的 ZIP 安装包", e);
        }
    }

    private void validateEntryPath(String entryName) {
        Path entryPath = Paths.get(entryName.replace('\\', '/')).normalize();
        if (entryPath.isAbsolute() || entryPath.startsWith("..")) {
            throw new IllegalArgumentException("压缩包包含非法文件路径");
        }
    }

    public void cleanup(Path prepared) {
        if (prepared == null) {
            return;
        }
        try {
            Files.deleteIfExists(prepared);
        } catch (IOException ignored) {
        }
    }
}
