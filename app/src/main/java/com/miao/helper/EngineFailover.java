package com.miao.helper;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * 多引擎故障转移编排（P2-2）。纯 JDK、不碰网络与 Android：只负责「按优先级给出当前该用的引擎、
 * 失败后推进到下一个、成功后复位」，以及「哪些错误值得切换、哪些换了也没用」的判定。
 *
 * <p>一次用户请求对应一个 EngineFailover 实例（非线程安全，请求结束即弃）；
 * 切换顺序即列表顺序——主引擎在前，备用引擎按用户配置的优先级在后。</p>
 *
 * <pre>
 *   EngineFailover f = EngineFailover.start(Prefs.engineChain(primaryKey));
 *   while (f.hasCurrent()) {
 *       Engine e = f.current();
 *       ... 用 e 发请求 ...
 *       成功 -> f.reset(); onSuccess(); return;
 *       可切换错误且还有下一个 -> f.advance()（循环自然取下一个）;
 *       不可切换错误 -> 直接 onError();
 *   }
 *   // 走到这里说明所有引擎都失败
 * </pre>
 */
public final class EngineFailover {

    private final List<Engine> engines;
    private int cursor = 0;

    private EngineFailover(List<Engine> engines) {
        this.engines = engines;
    }

    /**
     * 依据有序引擎列表构建一次故障转移序列：自动剔除没有 Key（无法发请求）的引擎，
     * 并去重（相同 Key+地址+模型只保留优先级最靠前的一个）。
     */
    public static EngineFailover start(List<Engine> chain) {
        List<Engine> clean = new ArrayList<>();
        if (chain != null) {
            for (Engine e : chain) {
                if (e == null || !e.hasKey()) continue;
                if (!clean.contains(e)) clean.add(e);
            }
        }
        return new EngineFailover(clean);
    }

    /** 是否还有可尝试的引擎 */
    public boolean hasCurrent() { return cursor < engines.size(); }

    /** 当前应使用的引擎；耗尽后返回 null */
    public Engine current() { return hasCurrent() ? engines.get(cursor) : null; }

    /** 当前是第几个尝试（从 1 开始，用于日志） */
    public int attemptNo() { return cursor + 1; }

    /** 可用引擎总数 */
    public int size() { return engines.size(); }

    /**
     * 推进到下一个引擎。
     * @return true 表示还有下一个引擎可试；false 表示已全部耗尽
     */
    public boolean advance() {
        cursor++;
        return hasCurrent();
    }

    /** 一次请求成功后调用，游标回到主引擎（下次请求仍从优先级最高的开始） */
    public void reset() { cursor = 0; }

    /** 是否存在备用引擎（多于一个可用引擎时 fallback 才有实际意义） */
    public boolean hasBackup() { return engines.size() > 1; }

    // ---------------- 错误是否值得切换（纯判定，便于单测） ----------------

    /** HTTP 状态码层面：鉴权失败 / 限流 / 服务端错误值得换引擎；其它 4xx 是请求本身问题，换了也没用 */
    public static boolean isSwitchableCode(int httpCode) {
        if (httpCode == 401 || httpCode == 403 || httpCode == 408 || httpCode == 429) return true;
        return httpCode >= 500;
    }

    /** 异常层面：网络可达性 / 超时类问题值得换；参数构造、JSON 解析等本地错误不值得换 */
    public static boolean isSwitchableError(Throwable t) {
        if (t == null) return false;
        // 主动中断（如线程被取消）不算可切换故障
        if (t instanceof InterruptedIOException && !(t instanceof java.net.SocketTimeoutException)) return false;
        return t instanceof UnknownHostException
                || t instanceof ConnectException
                || t instanceof java.net.SocketTimeoutException
                || t instanceof IOException;
    }
}
