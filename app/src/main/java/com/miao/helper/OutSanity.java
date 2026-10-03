package com.miao.helper;

/**
 * 输出健全性检查：译文是否跑偏成对话、视角翻转、篇幅离谱、反问用户。
 * 从 MiaoService 拆出，纯函数为主，唯一外部依赖 Prefs.expandLevel()。
 */
final class OutSanity {

    public static boolean looksSuspicious(String trans, String orig, int strictness) {
        if (trans == null || trans.trim().isEmpty()) return true;
        double lenMult; int sentDiff; boolean checkParen; boolean checkRhetoric;
        switch (strictness) {
            case 1:  lenMult = 3.0; sentDiff = 5; checkParen = false; checkRhetoric = false; break;
            case 2:  lenMult = 2.2; sentDiff = 3; checkParen = true;  checkRhetoric = false; break;
            case 4:  lenMult = 1.3; sentDiff = 1; checkParen = true;  checkRhetoric = true;  break;
            case 5:  lenMult = 1.1; sentDiff = 1; checkParen = true;  checkRhetoric = true;  break;
            default: lenMult = 1.6; sentDiff = 2; checkParen = true;  checkRhetoric = true;
        }
        if (checkParen && (trans.indexOf('(') >= 0 || trans.indexOf('（') >= 0)) return true;
        if (orig != null && orig.length() > 0 && trans.length() > orig.length() * lenMult + 6) return true;
        if (checkRhetoric) {
            boolean origQ = orig != null && (orig.indexOf('？') >= 0 || orig.indexOf('?') >= 0);
            String[] sus = {"对不对", "是不是", "你觉得", "主人觉得", "对吧", "是吧", "你说呢",
                    "怎么样呢", "好不好", "要不要", "？", "?"};
            for (String k : sus) {
                boolean isQ = k.equals("？") || k.equals("?");
                if (trans.contains(k)) {
                    if (isQ && origQ) continue;
                    return true;
                }
            }
        }
        if (strictness >= 5) {
            String[] emo = {"觉得", "认为", "开心", "难过", "生气", "伤心", "高兴", "兴奋", "害怕", "担心", "好奇", "委屈", "害羞"};
            for (String k : emo) {
                if (trans.contains(k) && (orig == null || !orig.contains(k))) return true;
            }
        }
        boolean oq = orig != null && (orig.indexOf('？') >= 0 || orig.indexOf('?') >= 0
                || orig.matches(".*(吗|呢|怎么|什么|为什么|干嘛|干什么|做什么|如何|谁|哪|几|多少).*"));
        boolean tq = trans.indexOf('？') >= 0 || trans.indexOf('?') >= 0;
        if (oq && !tq) return true;
        if (trans.matches(".*(主人|你).{0,5}(问|说).{0,8}(本喵|人家|吾|本小姐|本大爷|本系统|我).*")) return true;
        if (countSentences(trans) >= countSentences(orig) + sentDiff) return true;
        return false;
    }

    public static boolean looksReworkSuspicious(String trans, String orig, int strictness) {
        if (trans == null || trans.trim().isEmpty()) return true;
        double lenMult;
        switch (strictness) {
            case 1:  lenMult = 8.0; break;
            case 2:  lenMult = 6.0; break;
            case 4:  lenMult = 4.0; break;
            case 5:  lenMult = 3.0; break;
            default: lenMult = 5.0;
        }
        if (orig != null && orig.length() > 0 && trans.length() > orig.length() * lenMult + 10) return true;
        String[] askUser = {"你觉得", "主人觉得", "你说呢", "怎么样呢", "要不要我", "需要我帮", "请问你", "我来帮你"};
        for (String k : askUser) if (trans.contains(k)) return true;
        boolean oq = orig != null && (orig.indexOf('？') >= 0 || orig.indexOf('?') >= 0
                || orig.matches(".*(吗|呢|怎么|什么|为什么|干嘛|干什么|做什么|如何|谁|哪|几|多少).*"));
        boolean tq = trans.indexOf('？') >= 0 || trans.indexOf('?') >= 0;
        if (oq && !tq && startsWithPersonaSelf(trans)) return true;
        if (trans.matches(".*(主人|你).{0,5}(问|说).{0,8}(本喵|人家|吾|本小姐|本大爷|本系统|我).*")) return true;
        if (strictness >= 4 && Prefs.expandLevel() <= 2 && countSentences(trans) >= countSentences(orig) + 3) return true;
        return false;
    }

    public static int countSentences(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                    || c == '…' || c == '\n') n++;
        }
        return n;
    }

    public static boolean isDialogueResponse(String resp, String orig) {
        return isDialogueResponse(resp, orig, false);
    }

    public static boolean isDialogueResponse(String resp, String orig, boolean allowExpand) {
        if (resp == null || resp.trim().isEmpty()) return true;
        String r = resp.trim();
        if (r.matches(".*我是(DeepSeek|deepseek|AI|ai|人工智能|大模型|语言模型|模型|助手|机器人|豆包|ChatGPT|GPT|一个|一款|由|OpenAI|字节|深度求索).*"))
            return true;
        if (r.matches(".*(抱歉，?我是|很抱歉，?我是|你好！?我是|你好呀！?我是|我是你的|很高兴认识你，?我是).*"))
            return true;
        String[] assistantTone = {
            "作为一个AI", "作为一个ai", "作为AI", "作为人工智能", "作为大模型", "作为语言模型", "作为模型", "作为助手",
            "我能帮你", "我可以帮您", "我可以帮你", "有什么可以帮你", "有什么可以为您", "请问有什么", "需要我帮忙", "有什么能帮",
            "很高兴为您", "很高兴为你", "为您服务", "为你服务", "我是一个语言模型", "我是一个大模型", "我是个人工智能",
            "我无法透露", "我不能透露", "我没有感情", "我没有实体", "根据我的训练", "训练数据",
            "以下是", "希望这能帮到", "希望可以帮到", "如果还有", "需要其他帮助", "请问还有什么", "还有什么问题", "随时为您",
            "我是虚拟助手", "作为一个虚拟"
        };
        for (String kw : assistantTone) {
            if (r.contains(kw) && (orig == null || !orig.contains(kw))) return true;
        }
        String[] cloudTone = {
            "没有在使用", "不在使用", "未在使用", "没在使用",
            "不在运行", "未在运行", "没有在运行", "没在运行",
            "不在云端", "没有在云端", "未在云端", "没在云端", "并不在云端",
            "未连接云端", "没有连接云端", "没连上云端", "未连接到云端",
            "本地运行", "本地部署", "本地模型", "本地执行",
            "不需要云端", "无法在云端", "不能在云端", "未在云端使用"
        };
        for (String kw : cloudTone) {
            if (r.contains(kw) && (orig == null || !orig.contains(kw))) return true;
        }
        if (r.matches(".*(没有|不在|未在|没在|并未|并不|无法|不能)(在)?(云端|使用中|运行中|在线|被使用|被运行).*"))
            return true;
        if (orig != null && orig.length() > 0) {
            if (!allowExpand) {
                int mult = orig.length() <= 6 ? 15 : 6;
                if (r.length() > orig.length() * mult) return true;
            }
        }
        boolean origAsk = isQuestionText(orig);
        boolean transAsk = isQuestionText(r);
        if (origAsk && !transAsk && startsWithPersonaSelf(r)) return true;
        if (r.matches(".*(主人|你).{0,5}(问|说)([:：\"\"]|道|过).{0,8}(本喵|人家|吾|本小姐|本大爷|本系统|本姑娘|我).{0,12}(正在|在想|觉得|认为|这就|来告诉|当然|答案|可以|来帮|马上).*"))
            return true;
        if (r.matches(".*(主人|你).{0,5}问.{0,8}(本喵|人家|吾|本小姐|本大爷|本系统|本姑娘|我).{0,12}(正在|在想|觉得|认为|这就|来告诉|当然|答案|可以|来帮|马上).*"))
            return true;
        if (origAsk && !transAsk
                && r.matches(".*(本喵|人家|吾|本小姐|本大爷|本系统|本姑娘|我).{0,3}(正在|在想|觉得|认为|这就|来告诉你|当然是|答案是|可以帮|来帮你|马上).*"))
            return true;
        return false;
    }

    public static boolean isQuestionText(String s) {
        if (s == null) return false;
        if (s.indexOf('?') >= 0 || s.indexOf('？') >= 0) return true;
        return s.matches(".*(吗|呢|怎么|什么|为什么|咋|如何|谁|哪|几|多少|是不是|有没有|干嘛|干什么|做什么|咋样).*");
    }

    public static boolean startsWithPersonaSelf(String s) {
        if (s == null) return false;
        String[] self = {"本喵", "人家", "吾", "本小姐", "本大爷", "本系统", "本姑娘", "咱", "阿拉", "本蹦", "在下", "鄙人", "我"};
        for (String z : self) if (s.startsWith(z)) return true;
        return false;
    }
}
