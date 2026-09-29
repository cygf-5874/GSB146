package layering;

import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 既有用例（12 个，手写断言 runner，无第三方依赖）。只断言「起点已有能力」：
 * 解析语义、拓扑分层层号、环存在性、api 传递闭包。所有用例在朴素实现与参考实现下都全绿，
 * 不会给「待补的 impl 区分 / 环路径 / 确定性」能力判死刑。
 */
public final class PlannerTest {

    static int pass = 0;
    static int fail = 0;

    static ModuleGraph g(String src) throws Exception {
        return ModuleGraph.parse(new StringReader(src));
    }

    static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("PASS " + name);
        } else {
            fail++;
            System.out.println("FAIL " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        // 1. 模块声明
        check("parse/modules", g("module A\nmodule B\n").modules().size() == 2);

        // 2. 重复声明 → 带行号的 PlanException
        try {
            g("module A\nmodule A\n");
            check("parse/dup", false);
        } catch (PlanException pe) {
            check("parse/dup", pe.line() == 2);
        }

        // 3. 未知模块 → 带行号的 PlanException
        try {
            g("module A\nedge A -> Z : api\n");
            check("parse/unknown", false);
        } catch (PlanException pe) {
            check("parse/unknown", pe.line() == 2 && pe.getMessage().contains("unknown"));
        }

        // 4. 非法 scope → 带行号的 PlanException
        try {
            g("module A\nedge A -> A : xyz\n");
            check("parse/badscope", false);
        } catch (PlanException pe) {
            check("parse/badscope", pe.line() == 2);
        }

        // 5. 链式分层层号
        ModuleGraph chain = g("module A\nmodule B\nmodule C\nedge C -> B : api\nedge B -> A : api\n");
        TreeMap<String, Integer> lc = Planner.layers(chain);
        check("layer/chain", lc.get("A") == 0 && lc.get("B") == 1 && lc.get("C") == 2);

        // 6. 菱形分层层号
        ModuleGraph dia = g("module A\nmodule B\nmodule C\nmodule D\n"
                + "edge B -> A : api\nedge C -> A : api\nedge D -> B : api\nedge D -> C : api\n");
        TreeMap<String, Integer> ld = Planner.layers(dia);
        check("layer/diamond", ld.get("A") == 0 && ld.get("B") == 1 && ld.get("C") == 1 && ld.get("D") == 2);

        // 7. 无环
        check("cycle/none", !Planner.hasCycle(chain));

        // 8. 有环
        ModuleGraph cyc = g("module A\nmodule B\nmodule C\n"
                + "edge A -> B : api\nedge B -> C : api\nedge C -> A : api\n");
        check("cycle/yes", Planner.hasCycle(cyc));

        // 9. api 传递闭包（多分支）
        ModuleGraph inc = g("module A\nmodule B\nmodule C\nmodule D\n"
                + "edge B -> A : api\nedge C -> A : api\nedge D -> B : api\n");
        TreeSet<String> c1 = Planner.changedClosure(inc, Collections.singleton("A"));
        check("inc/api", c1.equals(new TreeSet<>(Arrays.asList("A", "B", "C", "D"))));

        // 10. api 闭包单点
        TreeSet<String> c2 = Planner.changedClosure(inc, Collections.singleton("B"));
        check("inc/single", c2.equals(new TreeSet<>(Arrays.asList("B", "D"))));

        // 11. 注释与空行被忽略
        ModuleGraph cm = g("# header\n\nmodule A\n  # mid\nmodule B\n");
        check("parse/comments", cm.modules().size() == 2);

        // 12. 分层覆盖全部模块
        check("layer/count", Planner.layers(dia).size() == 4);

        System.out.println("结果：通过 " + pass + "/" + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }
}
