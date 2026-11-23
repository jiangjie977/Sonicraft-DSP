package com.jiangjie.Sonicraft

/**
 * 自定义脚本效果器（SCVM，类 C）的内置示例。
 *
 * 排版规范：4 空格缩进、函数之间空一行、函数首行写用途注释。
 * 与 C++ `ScriptEffectNode::kDefaultScript` 文本保持一致，改动需同步。
 */
object ScriptDefaults {
    val DEFAULT_SCRIPT: String = """
        // 内置示例：软削波 + 可调驱动
        // 语法：void init() 加载时执行一次；void sample() 每个样本执行一次
        // 参数声明：// sliderN: min, max（N 从 1 开始，最多 8 个）
        // slider1: 0.0, 8.0
        void init() {
            // 可在此预计算系数
        }

        void sample() {
            // g = 1.0 为原声；slider1 越大，软削波失真越明显
            float g = 1.0 + slider1;
            float x0 = spl0 * g;
            spl0 = x0 / (1.0 + abs(x0));
            float x1 = spl1 * g;
            spl1 = x1 / (1.0 + abs(x1));
        }
    """.trimIndent()
}

/** 示例库条目：点选后写入编辑器（不自动应用，便于先改再点「应用脚本」）。 */
data class ScriptSample(val title: String, val code: String)

/** 内置示例库：覆盖 控件 / 内建 biquad / 内建延迟 / EEL 语法。 */
val ScriptSamples: List<ScriptSample> = listOf(
    ScriptSample(
        "音量 + 静音开关",
        """
            // 音量控制 + 静音开关
            // slider1: 0.0, 2.0, 1.0
            // toggle1: 静音
            void init() {
            }

            void sample() {
                if (toggle1 > 0.5) {
                    spl0 = 0.0;
                    spl1 = 0.0;
                } else {
                    spl0 = spl0 * slider1;
                    spl1 = spl1 * slider1;
                }
            }
        """.trimIndent(),
    ),
    ScriptSample(
        "软削波 + 驱动",
        """
            // 软削波：g = 1 + slider1 作为驱动
            // slider1: 0.0, 8.0, 1.0
            void init() {
            }

            void sample() {
                float g = 1.0 + slider1;
                float x0 = spl0 * g;
                spl0 = x0 / (1.0 + abs(x0));
                float x1 = spl1 * g;
                spl1 = x1 / (1.0 + abs(x1));
            }
        """.trimIndent(),
    ),
    ScriptSample(
        "双二阶低通（内建 bq_lp）",
        """
            // 双二阶低通：内建 bq_lp(id, freq, q, x)
            // slider1: 20.0, 18000.0, 1000.0
            // slider2: 0.1, 8.0, 0.707
            void init() {
            }

            void sample() {
                spl0 = bq_lp(0, slider1, slider2, spl0);
                spl1 = bq_lp(1, slider1, slider2, spl1);
            }
        """.trimIndent(),
    ),
    ScriptSample(
        "延迟混合（内建延迟线）",
        """
            // 延迟混合：dl_write / dl_read（4 条线，各 1 秒）
            // slider1: 1.0, 40000.0, 12000.0
            // slider2: 0.0, 1.0, 0.35
            void init() {
            }

            void sample() {
                float d = dl_read(0, slider1);
                dl_write(0, spl0);
                spl0 = spl0 * (1.0 - slider2) + d * slider2;
                spl1 = spl0;
            }
        """.trimIndent(),
    ),
    ScriptSample(
        "块卷积（内建 conv）",
        """
            // 块卷积：2 抽头 FIR（0.5, 0.5），可换成自己的 IR
            // 需要 block() 段：conv_run 处理整块
            void init() {
                conv_setup(0, 2);
                conv_load(0, 0, 0.5);
                conv_load(0, 1, 0.5);
            }

            void block() {
                conv_run(0, 0);
                conv_run(0, 1);
            }
        """.trimIndent(),
    ),
    ScriptSample(
        "EEL 语法示例",
        """
            desc:gain
            slider1:0.5<0<2>gain
            @init
              g = 1.0;
            @sample
              spl0 = spl0 * g * slider1;
              spl1 = spl0;
        """.trimIndent(),
    ),
)
