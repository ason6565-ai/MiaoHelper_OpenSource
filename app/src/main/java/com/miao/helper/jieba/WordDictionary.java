package com.miao.helper.jieba;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * 前缀词典单例（vendored，精简自 huaban/jieba-analysis 1.0.2，MIT）。
 * 改造点：字典不再走 class.getResourceAsStream（Android 上对 apk 内资源不可靠），
 * 改为由 App 启动时从 assets/dict.txt 打开 InputStream 喂入；去掉用户词典/文件系统逻辑。
 */
public final class WordDictionary {

    private static volatile WordDictionary instance;

    public final Map<String, Double> freqs = new HashMap<String, Double>();
    private Double minFreq = Double.MAX_VALUE;
    private Double total = 0.0;
    private DictSegment dict;
    private volatile boolean ready = false;

    private WordDictionary() {
        dict = new DictSegment((char) 0);
    }

    /** 由后台线程调用一次：从 assets 流加载主词典。任何异常都保持未就绪状态，绝不抛出。 */
    public static synchronized void load(InputStream is) {
        if (instance != null && instance.ready) return;
        WordDictionary w = new WordDictionary();
        w.doLoad(is);
        instance = w;
    }

    public static WordDictionary get() {
        return instance;
    }

    public static boolean isReady() {
        return instance != null && instance.ready;
    }

    private void doLoad(InputStream is) {
        if (is == null) return;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            // 不用 br.ready()：Android 上对压缩 assets 流会提前返回 false
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("[\t ]+");
                if (tokens.length < 2) continue;
                String word = addWord(tokens[0]);
                if (word == null) continue;
                double freq;
                try {
                    freq = Double.parseDouble(tokens[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                total += freq;
                freqs.put(word, freq);
            }
            for (Entry<String, Double> entry : freqs.entrySet()) {
                entry.setValue(Math.log(entry.getValue() / total));
                minFreq = Math.min(entry.getValue(), minFreq);
            }
            ready = !freqs.isEmpty();
        } catch (Throwable t) {
            ready = false;
        } finally {
            try {
                if (br != null) br.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String addWord(String word) {
        if (word != null && !"".equals(word.trim())) {
            String key = word.trim().toLowerCase(java.util.Locale.getDefault());
            dict.fillSegment(key.toCharArray());
            return key;
        }
        return null;
    }

    public DictSegment getTrie() {
        return this.dict;
    }

    public boolean containsWord(String word) {
        return freqs.containsKey(word);
    }

    public Double getFreq(String key) {
        if (containsWord(key)) return freqs.get(key);
        return minFreq;
    }
}
