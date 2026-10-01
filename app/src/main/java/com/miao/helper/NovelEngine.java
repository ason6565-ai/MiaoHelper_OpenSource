package com.miao.helper;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v5.1 小说生成引擎（参考开源 ZenStory / InkOS 审计-修订闭环 / AI_xiaoshuo 思路）。
 * 能力：
 *  1. generateSetup()：给定故事大体 + 角色 → 自动生成章节大纲 + 事件池（全部未引出）
 *  2. generateChapter()：按大纲推进 + 事件池联动 → 生成一章正文，返回正文与引出的事件
 *  3. 角色口风：生成时注入每角色口风卡与台词样本；生成后从正文提取台词回填样本（持续学习）
 *  4. 伏笔管理：未引出事件在 prompt 中列为"可选伏笔池"，模型自然引出时输出【引出事件】标记 → 状态流转 unused→introduced；再次使用 → resolved
 *  5. 上下文：设定 + 大纲 + 角色卡 + 剧情摘要 + 最近正文 + 未引出事件池 全量注入
 *  6. 乱码防护：硬规则 + 审计 + 自动重试一次
 */
public class NovelEngine {

    public interface Callback {
        void onDone(String msg);
        void onError(String msg);
    }

    private static final String HARD_RULES =
            "【硬性规则】\n"
          + "1. 输出必须为通顺连贯的中文文本：禁止乱码、禁止无意义字符堆叠、禁止把词语打碎成碎片乱序拼接、禁止连续重复同一字词制造伪节奏\n"
          + "2. 禁止自报身份（我是AI/模型/助手等）、禁止对用户说话、禁止反问用户（你觉得呢/对不对）、禁止寒暄、禁止括号动作描写\n"
          + "3. 不同角色台词必须区分：每个角色的说话方式遵循该角色【口风】，对话中保持角色人称与性格一致\n"
          + "4. 只输出正文本身，不要解释、不要加前缀标题（除非任务要求）、不要总结升华、不要预告“接下来……”\n"
          + "5. 保持前后文一致：已发生的事实不得推翻，人物称呼/性别/关系以设定为准\n"
          + "6. 写不出的地方宁可平实直说也不要生造";

    /** 文风规则（降低AI味，依据真实读者审阅反馈的五条痕迹收敛）：
     *  破折号密集 / 修饰语堆叠 / 情绪概括标准 / 段落节奏均匀 / “像”字比喻过载 */
    private static final String STYLE_RULES =
            "【文风·像人写的】\n"
          + "1. 全段破折号『——』最多用一次；能用逗号或句号断开就不要用破折号插入语\n"
          + "2. 修饰语克制：一句话最多一个修饰；优先用动词与具体名词推进，删掉多余的副词和形容词堆叠\n"
          + "3. 情绪不直接概括：不写『他感到慌乱/恐惧/紧张』『眼神里带着……』这类总结句，用动作、停顿、生理细节让情绪自己显现\n"
          + "4. 打乱段落节奏：允许一句成段，也允许长段压过来；不要每段三到五句、长短交替太规律\n"
          + "5. 『像』字比喻整章不超过三次，禁止『像……一样/似的』整齐比喻句；能用动作或实物呈现就不用比喻\n"
          + "6. 禁止排比堆叠、禁止总结升华句、禁止『不是……而是……』句式";

    /** 叙事规则（像会写小说的人，依据第二轮审阅反馈收敛）：
     *  逻辑不闭环 / 能力突然升级 / 线索太巧合 / 空间矛盾 / 事件堆散 / 无松弛 / 断章不收 */
    private static final String NARRATIVE_RULES =
            "【叙事·像会写小说的人】\n"
          + "1. 逻辑闭环：写下的细节必须有下文——伏笔在本章或后续回收；只为氛围的诡异细节一章最多一个，且必须有交代，不能无端消失\n"
          + "2. 能力一致：角色能力以【角色卡】为准，不得中途改变或突然升级；出现新用法必须在本章内有明确因果承接\n"
          + "3. 线索要藏：关键线索的出场必须自然，禁止『恰好/正好/刚好』式巧合送线索；可以让角色错过、误解、事后才想起\n"
          + "4. 空间自洽：动作、位置、方向、先后顺序前后一致，不得互相矛盾\n"
          + "5. 一章只写一件事：聚焦大纲当前章或选中的事件，其他事件不得在同一章展开\n"
          + "6. 允许松弛：保留一些不服务主线的日常细节、冗余动作和闲话，不要每个句子都为剧情推进服务\n"
          + "7. 收束：章节结尾要有明确的停顿或落点（一个动作、一个决定、一句余味），不能悬在半空直接断";

    /** 三四轮合并收敛（依据审阅反馈：线索全有用 / 对话功能太强 / 主角太稳 /
     *  反应太正确 / 结尾钩子收尾）。核心方向：把『不要X』改成『必须做X』的硬性指令。 */
    private static final String REFINEMENT_RULES =
            "【叙事·像人（硬性要求）】\n"
          + "1. 主角必须犯错：本章必须让主角犯至少一个可察觉的判断错误（误读线索、过早下结论、忽略某处细节），并留下痕迹，后续可被推翻或修正\n"
          + "2. 废线索强制：本章必须出现至少一条与主线无关、本章不解释、后续也不回收的细节（翻烂的书/半瓶水/路人动作/一句闲话），它真实存在但什么都不指向\n"
          + "3. 对话可错位：调查对话必须至少有一处误解或跑题（问A答B、记错、岔开话题、答非所问），禁止每句问话都得到精确回答\n"
          + "4. 反应可错位：角色的紧张、沉默、发问不必总在正确时机，允许该紧张时不紧张、该沉默时发问\n"
          + "5. 结尾禁金句禁钩子：禁止对称总结、升华式悬念句，禁止在结尾抛出新的悬念钩子（『另一本无字书』『而某人刚刚开始』这类）；结尾朴素，落在一个动作、一个物件或一句没说完的话上\n"
          + "6. 场景跳步：不要按步骤平铺（进门→坐下→说话→动作），省略中间环节直接切到关键场景\n"
          + "7. 允许不完美：可以有被打断的对话、话说到一半、没解释完的细节；不要每个句子都闭合\n"
          + "8. 禁替读者总结：不写『她不会追问』『不像是巧合』这类归纳感受的句子，也不写『说不上来/没接话/没有否认/没有追问/沉默了一会儿』这类万金油过渡句，用具体动作或直接切场代替";

    /** 生成故事大纲 + 事件池（一次性）：premise 为故事大体，charNames 为参与角色名列表 */
    public static void generateSetup(final Context ctx, final String premise,
                                     final List<String> charNames, final String storyTitle,
                                     final Callback cb) {
        StringBuilder user = new StringBuilder();
        user.append("【故事设定】\n").append(premise).append("\n\n");
        if (charNames != null && !charNames.isEmpty()) {
            user.append("【主要角色】\n");
            for (int i = 0; i < charNames.size(); i++) {
                user.append(i + 1).append(". ").append(charNames.get(i)).append("\n");
            }
            user.append("\n");
        }
        user.append("请产出以下两部分：\n")
            .append("一、章节大纲：5-8 章，每章一行，格式「第N章 标题」，一句话概括该章要发生的事；\n")
            .append("二、事件池：8-15 个可触发事件，每行一个，格式「事件：描述」，覆盖主线推进、角色冲突、日常互动、伏笔悬念；\n")
            .append("大纲与事件池分开输出，先大纲后事件池。");

        String system = "你是小说创作助手。根据用户给出的故事设定与角色，产出【章节大纲】和【事件池】。\n"
                + "事件池中的事件初始均为“未引出”，后续章节可被自然引出或回收。\n"
                + HARD_RULES;

        ApiMiaoifier.chat(system, user.toString(), 1600, new ApiMiaoifier.Callback() {
            @Override public void onSuccess(String text) {
                NovelStore.Story story = NovelStore.create(ctx, storyTitle);
                story.premise = premise;
                parseSetup(text, story);
                // 注入角色骨架（空人设，由角色管理补齐）
                if (charNames != null) {
                    for (String n : charNames) {
                        NovelStore.Character c = new NovelStore.Character();
                        c.name = n;
                        story.characters.add(c);
                    }
                }
                NovelStore.save(ctx, story);
                AppLog.i("Novel", "大纲/事件池生成完成 story=" + story.id
                        + " 大纲=" + story.outline.size() + " 事件=" + story.events.size());
                cb.onDone(story.id);
            }
            @Override public void onError(String msg) {
                AppLog.w("Novel", "大纲/事件池生成失败：" + msg);
                cb.onError(msg);
            }
        });
    }

    /** 解析 setup 输出：大纲章节 + 事件池 */
    static void parseSetup(String text, NovelStore.Story story) {
        if (text == null) return;
        String[] lines = text.split("\n");
        boolean inEvents = false;
        Pattern ch = Pattern.compile("^\\s*第[0-9一二三四五六七八九十百]+章[\\s　]*[^：:]*");
        Pattern ev = Pattern.compile("^\\s*事件[：:\\s]+(.*)$");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.contains("事件池")) { inEvents = true; continue; }
            if (line.contains("章节大纲") || line.contains("大纲")) { inEvents = false; continue; }
            if (inEvents) {
                Matcher m = ev.matcher(line);
                if (m.find()) {
                    NovelStore.Event e = new NovelStore.Event();
                    e.text = m.group(1).trim();
                    e.status = "unused";
                    story.events.add(e);
                }
                continue;
            }
            Matcher mc = ch.matcher(line);
            if (mc.find()) {
                story.outline.add(line);
            }
        }
        if (story.outline.isEmpty()) {
            // 兜底：无「第N章」时按行收录，最多 8 行
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.contains("事件")) continue;
                story.outline.add(line);
                if (story.outline.size() >= 8) break;
            }
        }
        if (story.events.isEmpty()) {
            // 兜底：无「事件：」时，把剩余行中非大纲行作为事件
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || story.outline.contains(line)) continue;
                NovelStore.Event e = new NovelStore.Event();
                e.text = line;
                e.status = "unused";
                story.events.add(e);
                if (story.events.size() >= 15) break;
            }
        }
    }

    /** 联动生成一章：eventText 非空则指定推进该事件；chapterIdx 为大纲当前章（-1 自动取已生成数） */
    public static void generateChapter(final Context ctx, final NovelStore.Story story,
                                       final String eventText, final Callback cb) {
        final String key = Prefs.apiKey();
        if (key == null || key.trim().isEmpty()) {
            cb.onError(Prefs.getContext().getString(R.string.err_no_api_key_fill));
            return;
        }
        int idx = Math.min(story.chapters.size(), story.outline.size() - 1);
        String chapterTitle = (idx >= 0 && idx < story.outline.size()) ? story.outline.get(idx) : "第" + (idx + 1) + "章";

        StringBuilder system = new StringBuilder();
        system.append("你是小说续写助手。根据【故事设定】【大纲】【角色卡】【剧情摘要】【最近正文】【未引出事件池】，续写当前章节。\n");
        system.append(HARD_RULES);
        system.append("\n");
        system.append(STYLE_RULES);
        system.append("\n");
        system.append(NARRATIVE_RULES);
        system.append("\n");
        system.append(REFINEMENT_RULES);

        StringBuilder user = new StringBuilder();
        user.append("【故事设定】\n").append(story.premise).append("\n\n");
        if (!story.outline.isEmpty()) {
            user.append("【大纲】\n");
            for (int i = 0; i < story.outline.size(); i++) {
                String mark = (i == idx) ? "（当前章）" : "";
                user.append(i + 1).append(". ").append(story.outline.get(i)).append(mark).append("\n");
            }
            user.append("\n");
        }
        if (!story.characters.isEmpty()) {
            user.append("【角色卡】\n");
            for (NovelStore.Character c : story.characters) {
                if (c.name.isEmpty()) continue;
                user.append("- ").append(c.name).append("：").append(c.desc.isEmpty() ? "（人设待补）" : c.desc);
                if (c.voice != null && !c.voice.trim().isEmpty()) {
                    user.append("。口风：").append(c.voice);
                }
                if (!c.samples.isEmpty()) {
                    user.append("。近期台词：");
                    for (int i = 0; i < c.samples.size(); i++) {
                        if (i > 0) user.append(" / ");
                        user.append("“").append(c.samples.get(i)).append("”");
                    }
                }
                user.append("\n");
            }
            user.append("\n");
        }
        if (!story.summary.trim().isEmpty()) {
            user.append("【剧情摘要】\n").append(story.summary).append("\n\n");
        }
        if (!story.chapters.isEmpty()) {
            NovelStore.Chapter last = story.chapters.get(story.chapters.size() - 1);
            String tail = last.content;
            if (tail.length() > 2000) tail = tail.substring(tail.length() - 2000);
            user.append("【最近正文】\n").append(tail).append("\n\n");
        }
        List<NovelStore.Event> unused = new ArrayList<>();
        for (NovelStore.Event e : story.events) if ("unused".equals(e.status)) unused.add(e);
        if (!unused.isEmpty()) {
            user.append("【未引出事件池（可作伏笔，不强制全部使用）】\n");
            for (int i = 0; i < unused.size(); i++) {
                user.append(i + 1).append(". ").append(unused.get(i).text).append("\n");
            }
            user.append("\n");
        }
        user.append("请续写「").append(chapterTitle).append("」");
        if (eventText != null && !eventText.trim().isEmpty()) {
            user.append("，本段推进事件：").append(eventText.trim());
        }
        user.append("。正文长度约 2000-4000 字，宁长勿短，写成完整的一章。\n");
        user.append("若本段自然引出了【未引出事件池】中的某个事件（角色提到、情节触及），请在正文最后单独一行输出【引出事件：事件内容】；未引出则不输出。");

        final String evt = (eventText == null) ? "" : eventText.trim();
        final int fidx = idx;
        ApiMiaoifier.chat(system.toString(), user.toString(), 4000, new ApiMiaoifier.Callback() {
            boolean retried = false;

            @Override public void onSuccess(String text) {
                String out = text == null ? "" : text.trim();
                if (looksBad(out) && !retried) {
                    retried = true;
                    AppLog.w("Novel", "章节输出异常（乱码/对话化），自动重试一轮");
                    retry();
                    return;
                }
                commit(out);
            }

            @Override public void onError(String msg) {
                AppLog.w("Novel", "章节生成失败：" + msg);
                cb.onError(msg);
            }

            private void retry() {
                String sys2 = system.toString() + "\n【上次输出不合格】上次生成的文本出现乱码、碎片化或变成了对话腔，请重新写一遍：必须是通顺连贯的中文小说正文，禁止乱码与碎片堆叠，禁止对用户说话。";
                ApiMiaoifier.chat(sys2, user.toString(), 4000, new ApiMiaoifier.Callback() {
                    @Override public void onSuccess(String t2) {
                        String o2 = t2 == null ? "" : t2.trim();
                        commit(o2);
                    }
                    @Override public void onError(String m2) { cb.onError(m2); }
                });
            }

            private void commit(String out) {
                // 提取【引出事件】标记
                List<String> refs = new ArrayList<>();
                Pattern p = Pattern.compile("【引出事件[：:\\s]*(.+?)】");
                Matcher m = p.matcher(out);
                while (m.find()) {
                    String ref = m.group(1).trim();
                    if (!ref.isEmpty()) refs.add(ref);
                }
                // 正文去掉标记行
                String body = p.matcher(out).replaceAll("").trim();
                body = body.replaceAll("(?m)^\\s*【引出事件[^】]*】\\s*$", "").trim();

                NovelStore.Chapter ch = new NovelStore.Chapter();
                ch.title = (fidx >= 0 && fidx < story.outline.size()) ? story.outline.get(fidx) : ("第" + (fidx + 1) + "章");
                ch.content = body;
                ch.eventRefs = refs;
                story.chapters.add(ch);

                // 事件状态流转
                for (String ref : refs) {
                    for (NovelStore.Event e : story.events) {
                        if ("unused".equals(e.status) && e.text.contains(ref.substring(0, Math.min(ref.length(), 6)))) {
                            e.status = "introduced";
                            break;
                        }
                    }
                }
                if (!evt.isEmpty()) {
                    for (NovelStore.Event e : story.events) {
                        if (e.text.equals(evt) || e.text.contains(evt.substring(0, Math.min(evt.length(), 8)))) {
                            if ("unused".equals(e.status)) e.status = "introduced";
                            else if ("introduced".equals(e.status)) e.status = "resolved";
                            break;
                        }
                    }
                }

                // 口风学习：从正文提取角色台词样本（最近 3 条）
                learnVoices(body, story);

                // 剧情摘要更新（每章一行，总量封顶约 900 字）
                String oneLine = body.replace('\n', ' ');
                if (oneLine.length() > 60) oneLine = oneLine.substring(0, 60) + "……";
                story.summary = story.summary + "\n" + ch.title + "：" + oneLine;
                if (story.summary.length() > 900) {
                    int cut = story.summary.indexOf('\n', story.summary.length() - 900);
                    story.summary = (cut > 0 ? story.summary.substring(cut + 1) : story.summary.substring(story.summary.length() - 900));
                }

                NovelStore.save(ctx, story);
                AppLog.i("Novel", "章节生成完成 story=" + story.id + " 章节数=" + story.chapters.size()
                        + " 引出事件=" + refs.size());
                cb.onDone(ch.title);
            }
        });
    }

    /** 口风学习：把正文中「角色名：台词」或「角色名说/道+台词」的台词回填到对应角色 samples（上限 3） */
    static void learnVoices(String body, NovelStore.Story story) {
        if (body == null || story.characters.isEmpty()) return;
        Pattern q = Pattern.compile("[“\"]([^”\"]{2,60})[”\"]");
        Matcher m = q.matcher(body);
        List<String[]> hits = new ArrayList<>(); // {name, quote}
        while (m.find()) {
            String quote = m.group(1).trim();
            if (quote.isEmpty()) continue;
            // 找台词前面最近的说话人（向前 40 字符内找角色名）
            int start = Math.max(0, m.start() - 40);
            String before = body.substring(start, m.start());
            String owner = null;
            for (NovelStore.Character c : story.characters) {
                if (c.name.isEmpty()) continue;
                if (before.contains(c.name)) { owner = c.name; break; }
            }
            if (owner != null) hits.add(new String[]{owner, quote});
        }
        for (String[] h : hits) {
            for (NovelStore.Character c : story.characters) {
                if (c.name.equals(h[0])) {
                    if (!c.samples.contains(h[1])) c.samples.add(h[1]);
                    while (c.samples.size() > 3) c.samples.remove(0);
                    break;
                }
            }
        }
    }

    /** 乱码/对话化/自报身份审计（保守启发式，宁可放过不误伤） */
    static boolean looksBad(String s) {
        if (s == null || s.trim().isEmpty()) return true;
        String t = s.trim();
        String low = t.toLowerCase();
        if (low.contains("我是ai") || low.contains("我是人工智能") || low.contains("作为ai")
                || low.contains("作为助手")) return true;
        // 对话腔反问词在长文正文里是角色口语（"对不对/你说呢"），只有短输出或几乎无叙述句才判对话化
        if (low.contains("你觉得呢") || low.contains("你说呢") || low.contains("对不对")) {
            int dots = 0;
            for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '。') dots++;
            if (t.length() < 300 || dots < 5) return true;
        }
        int cn = 0, letters = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (c >= '\u4e00' && c <= '\u9fff') cn++;
            }
        }
        if (letters > 0 && cn * 100 / letters < 40) return true;
        for (int i = 2; i < t.length(); i++) {
            if (t.charAt(i) == t.charAt(i - 1) && t.charAt(i) == t.charAt(i - 2)) {
                // 拟声/语气字豁免：哈哈哈、呜呜呜等合法口语不算乱码
                char c = t.charAt(i);
                if ("哈呜嘿嘻嗯啊哎哦哼呀哇唉".indexOf(c) >= 0) continue;
                return true;
            }
        }
        return false;
    }
}
