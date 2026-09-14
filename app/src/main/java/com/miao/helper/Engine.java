package com.miao.helper;

/**
 * 一个可用的翻译引擎配置（P2-2 多引擎 fallback）。纯 JDK 值对象，不依赖 Android/网络，
 * 因此切换编排逻辑可在本地 JVM 单测中直接验证。
 *
 * 字段不可变；model 允许为空——为空时由请求层回落到全局默认模型。
 */
public final class Engine {
    /** 展示名（如「主引擎 / DeepSeek 备用」），仅用于日志与设置页 */
    public final String name;
    /** 接口根地址（不含 /chat/completions） */
    public final String baseUrl;
    /** 该引擎的 API Key（已在存储层解密后的明文，禁止写日志） */
    public final String apiKey;
    /** 该引擎指定的模型 ID，可空；空则用全局模型 */
    public final String model;

    public Engine(String name, String baseUrl, String apiKey, String model) {
        this.name = name == null ? "" : name.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
    }

    /** 是否配置了可用的 Key（没有 Key 的引擎无法发起请求，构建链时会被剔除） */
    public boolean hasKey() {
        return apiKey != null && !apiKey.isEmpty();
    }

    /** 该引擎是否配置了独立根地址 */
    public boolean hasBaseUrl() {
        return baseUrl != null && !baseUrl.isEmpty();
    }

    /** 引擎自带模型优先；为空回落到调用方给的全局模型 */
    public String effectiveModel(String globalFallback) {
        return (model != null && !model.isEmpty()) ? model : globalFallback;
    }

    public String display() { return name.isEmpty() ? baseUrl : name; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Engine)) return false;
        Engine e = (Engine) o;
        return name.equals(e.name) && baseUrl.equals(e.baseUrl)
                && apiKey.equals(e.apiKey) && model.equals(e.model);
    }

    @Override
    public int hashCode() {
        int h = name.hashCode();
        h = 31 * h + baseUrl.hashCode();
        h = 31 * h + apiKey.hashCode();
        h = 31 * h + model.hashCode();
        return h;
    }

    @Override
    public String toString() {
        // 绝不打印 apiKey
        return "Engine{name=" + name + ", base=" + baseUrl + ", model=" + model + ", keySet=" + hasKey() + "}";
    }
}
