package com.miao.helper;

import org.junit.Test;
import org.junit.Before;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * StyleManager 单元测试（P0-1-1 单元测试基线）
 * 测试范围：纯逻辑方法 + mock Prefs 后的依赖方法
 * 覆盖目标：核心方法 ≥ 60%
 */
public class StyleManagerTest {

    @Before
    public void setUp() {
        // 每个测试前重置 mock 状态
    }

    // ==================== 纯逻辑方法测试（不依赖 Prefs）====================

    @Test
    public void testPersonaBuiltinCount() {
        // 内置人设数量应为 PERSONA.length - 1（去掉最后一个「自定义」入口）
        int count = StyleManager.personaBuiltinCount();
        assertTrue("内置人设数量应大于 0", count > 0);
        assertEquals("内置人设数量应为 10", 10, count);
    }

    @Test
    public void testTailChanceOf_knownStyle() {
        // 已知风格的句尾口癖概率
        assertEquals(60, StyleManager.tailChanceOf("中性傲娇"));
        assertEquals(60, StyleManager.tailChanceOf("傲娇"));
        assertEquals(65, StyleManager.tailChanceOf("正太"));
        assertEquals(60, StyleManager.tailChanceOf("萝莉"));
        assertEquals(65, StyleManager.tailChanceOf("御姐"));
        assertEquals(70, StyleManager.tailChanceOf("古风"));
        assertEquals(60, StyleManager.tailChanceOf("毒舌"));
        assertEquals(60, StyleManager.tailChanceOf("翻译腔"));
        assertEquals(70, StyleManager.tailChanceOf("雌小鬼"));
    }

    @Test
    public void testTailChanceOf_unknownStyle() {
        // 未知风格默认返回 100（必定加口癖）
        assertEquals(100, StyleManager.tailChanceOf("不存在的风格"));
        assertEquals(100, StyleManager.tailChanceOf("自定义"));
        assertEquals(100, StyleManager.tailChanceOf(""));
    }

    @Test
    public void testParseKeyIndex() {
        assertEquals(0, StyleManager.parseKeyIndex("p0"));
        assertEquals(5, StyleManager.parseKeyIndex("p5"));
        assertEquals(13, StyleManager.parseKeyIndex("p13"));
        // 非法 key 默认返回 0
        assertEquals(0, StyleManager.parseKeyIndex(""));
        assertEquals(0, StyleManager.parseKeyIndex("abc"));
        assertEquals(0, StyleManager.parseKeyIndex("p"));
    }

    @Test
    public void testCurrentIsCat_alwaysFalse() {
        // 猫娘系已移除，恒返回 false
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::styleIndex).thenReturn(0);
            assertFalse(StyleManager.currentIsCat());

            prefsMock.when(Prefs::styleIndex).thenReturn(5);
            assertFalse(StyleManager.currentIsCat());
        }
    }

    @Test
    public void testIsCatKey_alwaysFalse() {
        // 猫娘系已移除，恒返回 false
        assertFalse(StyleManager.isCatKey("p0"));
        assertFalse(StyleManager.isCatKey("p5"));
        assertFalse(StyleManager.isCatKey("p13"));
    }

    // ==================== 依赖 Prefs 的方法测试（mock Prefs）====================

    @Test
    public void testPersonaCount_withNoCustom() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());
            // 内置 10 + 自定义 0 + 「自定义」入口 1 = 11
            assertEquals(11, StyleManager.personaCount());
        }
    }

    @Test
    public void testPersonaCount_withCustom() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            java.util.List<String[]> custom = new java.util.ArrayList<>();
            custom.add(new String[]{"自定义人设1", "prompt1"});
            custom.add(new String[]{"自定义人设2", "prompt2"});
            prefsMock.when(Prefs::customPersonas).thenReturn(custom);
            // 内置 10 + 自定义 2 + 「自定义」入口 1 = 13
            assertEquals(13, StyleManager.personaCount());
        }
    }

    @Test
    public void testPersonaPrompt_builtin() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());
            // 内置人设的 prompt 不应为空
            String prompt = StyleManager.personaPrompt(0);
            assertNotNull(prompt);
            assertFalse("内置人设 prompt 不应为空", prompt.isEmpty());
            assertTrue("prompt 应包含铁律", prompt.contains("铁律"));
        }
    }

    @Test
    public void testPersonaPrompt_customEntry() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());
            // 最后一个「自定义」入口返回空字符串（索引 10）
            String prompt = StyleManager.personaPrompt(10);
            assertEquals("", prompt);
        }
    }

    @Test
    public void testPersonaPrompt_outOfRange() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());
            // 越界返回空字符串
            assertEquals("", StyleManager.personaPrompt(-1));
            assertEquals("", StyleManager.personaPrompt(100));
        }
    }

    @Test
    public void testPersonaLocal_builtin() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());
            // 内置人设应有本地规则
            StyleManager.LocalRule rule = StyleManager.personaLocal(0);
            assertNotNull(rule);
            assertNotNull(rule.me);
            assertNotNull(rule.you);
            assertNotNull(rule.tail);
        }
    }

    @Test
    public void testPersonaLocal_customEntry() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());
            // 「自定义」入口返回 null（索引 10）
            assertNull(StyleManager.personaLocal(10));
        }
    }

    @Test
    public void testCurrentName() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());
            prefsMock.when(Prefs::styleIndex).thenReturn(0);
            assertEquals("中性傲娇", StyleManager.currentName());

            prefsMock.when(Prefs::styleIndex).thenReturn(1);
            assertEquals("傲娇", StyleManager.currentName());

            prefsMock.when(Prefs::styleIndex).thenReturn(10);
            assertEquals("自定义", StyleManager.currentName());
        }
    }

    @Test
    public void testCurrentTailChance() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());

            prefsMock.when(Prefs::styleIndex).thenReturn(0);
            assertEquals(60, StyleManager.currentTailChance());

            prefsMock.when(Prefs::styleIndex).thenReturn(5);
            assertEquals(70, StyleManager.currentTailChance());

            // 「自定义」入口默认 100（索引 10）
            prefsMock.when(Prefs::styleIndex).thenReturn(10);
            assertEquals(100, StyleManager.currentTailChance());
        }
    }

    @Test
    public void testNameOfKey() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());
            assertEquals("中性傲娇", StyleManager.nameOfKey("p0"));
            assertEquals("傲娇", StyleManager.nameOfKey("p1"));
            assertEquals("自定义", StyleManager.nameOfKey("p10"));
            // 越界返回空字符串
            assertEquals("", StyleManager.nameOfKey("p100"));
        }
    }

    @Test
    public void testAllEntries() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());
            java.util.List<StyleManager.StyleEntry> entries = StyleManager.allEntries();
            assertNotNull(entries);
            assertEquals(11, entries.size());
            // 验证 key 格式
            for (int i = 0; i < entries.size(); i++) {
                assertEquals("p" + i, entries.get(i).key);
                assertEquals(i, entries.get(i).index);
                assertFalse(entries.get(i).dialect); // 方言已移除，恒 false
            }
        }
    }

    @Test
    public void testShotsFor_builtin() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());
            String[][] shots = StyleManager.shotsFor(0);
            assertNotNull(shots);
            assertEquals(4, shots.length); // 4 组示例
            // 每组示例应有 input 和 output
            for (String[] shot : shots) {
                assertEquals(2, shot.length);
                assertNotNull(shot[0]);
                assertNotNull(shot[1]);
            }
        }
    }

    @Test
    public void testShotsFor_customEntry() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());
            // 「自定义」入口（无本地规则）应返回中性示例（索引 10）
            String[][] shots = StyleManager.shotsFor(10);
            assertNotNull(shots);
            assertEquals(4, shots.length);
            // 自定义风格的示例应只示范"翻译而非对话"
            assertEquals("你问我是谁", shots[0][1]);
        }
    }

    @Test
    public void testBuildPrompt_containsIronRule() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());
            prefsMock.when(Prefs::styleIndex).thenReturn(0);
            prefsMock.when(Prefs::expandLevel).thenReturn(0);
            prefsMock.when(Prefs::styleIntensity).thenReturn(3);

            String prompt = StyleManager.buildPrompt(0);
            assertNotNull(prompt);
            assertTrue("prompt 应包含铁律", prompt.contains("铁律"));
            assertTrue("prompt 应包含改写强度", prompt.contains("改写强度"));
            assertTrue("prompt 应包含篇幅要求", prompt.contains("篇幅要求"));
            assertTrue("prompt 应包含禁止颜文字", prompt.contains("不要使用任何颜文字"));
        }
    }

    @Test
    public void testBuildPrompt_intensityClamping() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());
            prefsMock.when(Prefs::expandLevel).thenReturn(0);

            // 强度 0 应被 clamp 到 1
            prefsMock.when(Prefs::styleIntensity).thenReturn(0);
            String prompt0 = StyleManager.buildPrompt(0);
            assertTrue(prompt0.contains("改写强度 1/5"));

            // 强度 6 应被 clamp 到 5
            prefsMock.when(Prefs::styleIntensity).thenReturn(6);
            String prompt6 = StyleManager.buildPrompt(0);
            assertTrue(prompt6.contains("改写强度 5/5"));
        }
    }

    @Test
    public void testBuildPrompt_expandClamping() {
        try (MockedStatic<Prefs> prefsMock = Mockito.mockStatic(Prefs.class)) {
            prefsMock.when(Prefs::customPersonas).thenReturn(new java.util.ArrayList<>());
            prefsMock.when(Prefs::styleIntensity).thenReturn(3);

            // 手动扩写等级 0 时使用风格默认
            prefsMock.when(Prefs::expandLevel).thenReturn(0);
            String promptDefault = StyleManager.buildPrompt(0);
            // 中性傲娇 expand=1 → 映射到 3
            assertTrue(promptDefault.contains("篇幅要求·适度发挥"));

            // 手动扩写等级 1 应覆盖风格默认
            prefsMock.when(Prefs::expandLevel).thenReturn(1);
            String prompt1 = StyleManager.buildPrompt(0);
            assertTrue(prompt1.contains("篇幅要求·严格"));

            // 手动扩写等级 5
            prefsMock.when(Prefs::expandLevel).thenReturn(5);
            String prompt5 = StyleManager.buildPrompt(0);
            assertTrue(prompt5.contains("篇幅要求·自由发挥"));
        }
    }
}
