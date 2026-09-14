package com.miao.helper.jieba;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 精简分词器（vendored，源自 huaban/jieba-analysis 1.0.2，MIT）。
 * 去掉 HMM 未登录词识别（FinalSeg/Viterbi）与索引模式 n-gram，只保留精确模式，
 * 未登录的连续单字按字切分；输出带原文偏移的 SegToken，便于按词替换后无损拼回。
 */
public class JiebaSegmenter {

    /** 词典未就绪返回 null，由上层回退到整串替换，保证永不因分词器失效而罢工。 */
    public static List<SegToken> tokenize(String paragraph) {
        WordDictionary wd = WordDictionary.get();
        if (paragraph == null || paragraph.isEmpty() || !WordDictionary.isReady() || wd == null)
            return null;
        List<SegToken> tokens = new ArrayList<SegToken>();
        StringBuilder sb = new StringBuilder();
        int offset = 0;
        for (int i = 0; i < paragraph.length(); ++i) {
            char ch = CharacterUtil.regularize(paragraph.charAt(i));
            if (CharacterUtil.ccFind(ch)) {
                sb.append(ch);
            } else {
                if (sb.length() > 0) {
                    for (String word : sentenceProcess(wd, sb.toString())) {
                        tokens.add(new SegToken(word, offset, offset += word.length()));
                    }
                    sb = new StringBuilder();
                    offset = i;
                }
                tokens.add(new SegToken(paragraph.substring(i, i + 1), offset, ++offset));
            }
        }
        if (sb.length() > 0) {
            for (String token : sentenceProcess(wd, sb.toString())) {
                tokens.add(new SegToken(token, offset, offset += token.length()));
            }
        }
        return tokens;
    }

    private static Map<Integer, List<Integer>> createDAG(WordDictionary wd, String sentence) {
        Map<Integer, List<Integer>> dag = new HashMap<Integer, List<Integer>>();
        DictSegment trie = wd.getTrie();
        char[] chars = sentence.toCharArray();
        int N = chars.length;
        int i = 0, j = 0;
        while (i < N) {
            Hit hit = trie.match(chars, i, j - i + 1);
            if (hit.isPrefix() || hit.isMatch()) {
                if (hit.isMatch()) {
                    if (!dag.containsKey(i)) {
                        List<Integer> value = new ArrayList<Integer>();
                        dag.put(i, value);
                        value.add(j);
                    } else {
                        dag.get(i).add(j);
                    }
                }
                j += 1;
                if (j >= N) {
                    i += 1;
                    j = i;
                }
            } else {
                i += 1;
                j = i;
            }
        }
        for (i = 0; i < N; ++i) {
            if (!dag.containsKey(i)) {
                List<Integer> value = new ArrayList<Integer>();
                value.add(i);
                dag.put(i, value);
            }
        }
        return dag;
    }

    private static Map<Integer, Pair<Integer>> calc(WordDictionary wd, String sentence,
                                                    Map<Integer, List<Integer>> dag) {
        int N = sentence.length();
        HashMap<Integer, Pair<Integer>> route = new HashMap<Integer, Pair<Integer>>();
        route.put(N, new Pair<Integer>(0, 0.0));
        for (int i = N - 1; i > -1; i--) {
            Pair<Integer> candidate = null;
            for (Integer x : dag.get(i)) {
                double freq = wd.getFreq(sentence.substring(i, x + 1)) + route.get(x + 1).freq;
                if (null == candidate) {
                    candidate = new Pair<Integer>(x, freq);
                } else if (candidate.freq < freq) {
                    candidate.freq = freq;
                    candidate.key = x;
                }
            }
            route.put(i, candidate);
        }
        return route;
    }

    private static List<String> sentenceProcess(WordDictionary wd, String sentence) {
        List<String> tokens = new ArrayList<String>();
        int N = sentence.length();
        Map<Integer, List<Integer>> dag = createDAG(wd, sentence);
        Map<Integer, Pair<Integer>> route = calc(wd, sentence, dag);

        int x = 0, y;
        StringBuilder sb = new StringBuilder();
        while (x < N) {
            y = route.get(x).key + 1;
            String lWord = sentence.substring(x, y);
            if (y - x == 1) {
                sb.append(lWord);
            } else {
                if (sb.length() > 0) {
                    flushBuf(wd, sb, tokens);
                    sb = new StringBuilder();
                }
                tokens.add(lWord);
            }
            x = y;
        }
        if (sb.length() > 0) flushBuf(wd, sb, tokens);
        return tokens;
    }

    /** 连续单字缓冲收尾：整串在词典里则成词，否则逐字切分（替代 HMM）。 */
    private static void flushBuf(WordDictionary wd, StringBuilder sb, List<String> tokens) {
        String buf = sb.toString();
        if (buf.length() == 1 || wd.containsWord(buf)) {
            tokens.add(buf);
        } else {
            for (int k = 0; k < buf.length(); k++) tokens.add(buf.substring(k, k + 1));
        }
    }
}
