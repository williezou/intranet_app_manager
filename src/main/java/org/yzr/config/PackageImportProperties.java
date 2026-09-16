package org.yzr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Jenkins 与本服务部署在同一台机器时，从受信任目录导入安装包的配置。
 */
@Component
@ConfigurationProperties(prefix = "package-import")
public class PackageImportProperties {
    private boolean enabled = false;
    private String token;
    private List<String> allowedRoots = new ArrayList<>();
    private long maxFileSizeBytes = 1024L * 1024L * 1024L;
    private long maxExtractedSizeBytes = 2L * 1024L * 1024L * 1024L;
    private int maxArchiveEntries = 20000;
    private long minFreeSpaceBytes = 20L * 1024L * 1024L * 1024L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public List<String> getAllowedRoots() {
        return allowedRoots;
    }

    public void setAllowedRoots(List<String> allowedRoots) {
        this.allowedRoots = allowedRoots;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public long getMaxExtractedSizeBytes() {
        return maxExtractedSizeBytes;
    }

    public void setMaxExtractedSizeBytes(long maxExtractedSizeBytes) {
        this.maxExtractedSizeBytes = maxExtractedSizeBytes;
    }

    public int getMaxArchiveEntries() {
        return maxArchiveEntries;
    }

    public void setMaxArchiveEntries(int maxArchiveEntries) {
        this.maxArchiveEntries = maxArchiveEntries;
    }

    public long getMinFreeSpaceBytes() {
        return minFreeSpaceBytes;
    }

    public void setMinFreeSpaceBytes(long minFreeSpaceBytes) {
        this.minFreeSpaceBytes = minFreeSpaceBytes;
    }
}
