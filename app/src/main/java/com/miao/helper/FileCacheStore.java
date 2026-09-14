package com.miao.helper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 基于文件目录的磁盘缓存实现（P2-1），纯 java.io、不依赖 Android Context，
 * 因此既能在 Android 端传入 context.getCacheDir()/getFilesDir() 子目录，也能在本地 JVM 单测里用临时目录验证。
 *
 * <ul>
 *   <li>每个 key 一个文件，文件名取 key 的 SHA-256，规避特殊字符与超长文件名；</li>
 *   <li>写入走「临时文件 + renameTo」，避免读到写了一半的半截缓存；</li>
 *   <li>磁盘条数有上限（默认 {@link #DEFAULT_MAX_DISK}），超出按最近修改时间淘汰最老的；</li>
 *   <li>所有 IO 异常吞掉并返回 null/静默——缓存是优化，绝不能因磁盘问题影响翻译主流程。</li>
 * </ul>
 */
public class FileCacheStore implements TranslationCache.Store {

    public static final int DEFAULT_MAX_DISK = 512;
    /** 鉴 P0-4：磁盘缓存容量上限 50MB，超出按最近读取时间淘汰最旧文件 */
    public static final long DEFAULT_MAX_BYTES = 50L * 1024 * 1024;
    /** 鉴 P0-4：单文件上限 10MB——超大翻译结果不入磁盘缓存，避免一个文件撑爆容量上限 */
    public static final long MAX_SINGLE_BYTES = 10L * 1024 * 1024;
    private static final String SUFFIX = ".tc";
    private static final String TMP_SUFFIX = ".tmp";
    /** P2-22：磁盘缓存内容混淆（XOR），避免译文以明文留在缓存目录；防随手翻文件，非强加密。 */
    private static final byte[] XOR_KEY = "miao.trans.cache.obf.v1".getBytes(StandardCharsets.UTF_8);
    private static byte[] obfuscate(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) out[i] = (byte) (in[i] ^ XOR_KEY[i % XOR_KEY.length]);
        return out;
    }

    private final File dir;
    private final int maxDisk;
    private final long maxBytes;
    private volatile String lastError;

    public FileCacheStore(File dir) {
        this(dir, DEFAULT_MAX_DISK, DEFAULT_MAX_BYTES);
    }

    public FileCacheStore(File dir, int maxDiskFiles) {
        this(dir, maxDiskFiles, DEFAULT_MAX_BYTES);
    }

    public FileCacheStore(File dir, int maxDiskFiles, long maxBytes) {
        this.dir = dir;
        this.maxDisk = Math.max(1, maxDiskFiles);
        this.maxBytes = Math.max(1L, maxBytes);
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    public String lastError() { return lastError; }

    private File fileFor(String key) {
        return new File(dir, sha256(key) + SUFFIX);
    }

    @Override
    public String load(String key) {
        if (key == null || dir == null || !dir.isDirectory()) return null;
        File f = fileFor(key);
        if (!f.exists()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(32, (int) Math.min(f.length(), 1 << 20)));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            // 鉴 P0-4：命中即刷新访问时间——淘汰按「最近读取时间」而非写入时间，
            // 避免用户改系统时间或批量写入时间戳相同时淘汰逻辑失效
            try {
                long now = System.currentTimeMillis();
                if (now > f.lastModified()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.setLastModified(now);
                }
            } catch (Throwable ignored) { }
            return new String(obfuscate(bos.toByteArray()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    @Override
    public void save(String key, String encoded) {
        if (key == null || encoded == null || dir == null) return;
        // 鉴 P0-4：单文件超过 10MB 直接跳过磁盘缓存，防止一个巨型文件撑爆容量上限
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_SINGLE_BYTES) {
            lastError = "单文件超 10MB，跳过磁盘缓存";
            return;
        }
        try {
            if (!dir.exists() && !dir.mkdirs()) return;
            File target = fileFor(key);
            File tmp = new File(dir, sha256(key) + TMP_SUFFIX);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(obfuscate(encoded.getBytes(StandardCharsets.UTF_8)));
                out.flush();
            }
            // 同目录原子替换；个别机型 rename 失败时退回直接覆盖写
            if (!tmp.renameTo(target)) {
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(obfuscate(encoded.getBytes(StandardCharsets.UTF_8)));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            evictIfNeeded();
        } catch (Throwable t) {
            lastError = String.valueOf(t);
        }
    }

    @Override
    public void remove(String key) {
        if (key == null || dir == null) return;
        File f = fileFor(key);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Override
    public void clear() {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles((d, name) -> name.endsWith(SUFFIX) || name.endsWith(TMP_SUFFIX));
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** 当前磁盘条目数（测试/调试用）。 */
    public int count() {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles((d, name) -> name.endsWith(SUFFIX));
        return files == null ? 0 : files.length;
    }

    /** 当前磁盘缓存占用字节数（供"缓存占用：X MB / 50 MB"UI 显示）。 */
    public long bytes() {
        if (dir == null || !dir.isDirectory()) return 0L;
        File[] files = dir.listFiles((d, name) -> name.endsWith(SUFFIX));
        if (files == null) return 0L;
        long total = 0L;
        for (File f : files) total += f.length();
        return total;
    }

    /** 超过条数或容量上限时，按最近修改时间从老到新删除，直到两个条件都满足。 */
    private void evictIfNeeded() {
        File[] files = dir.listFiles((d, name) -> name.endsWith(SUFFIX));
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (files.length <= maxDisk && total <= maxBytes) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (files.length <= maxDisk && total <= maxBytes) break;
            long len = f.length();
            if (f.delete()) {
                total -= len;
                files = dir.listFiles((d, name) -> name.endsWith(SUFFIX));
                if (files == null) return;
            }
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            // SHA-256 是 JDK 必备算法，理论不会到这；兜底用 hashCode 保证仍可用
            return "h" + Integer.toHexString(s.hashCode());
        }
    }
}
