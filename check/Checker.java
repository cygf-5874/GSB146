import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * layering 的固定验收程序（勿改）。
 *
 * <p>10 个场景覆盖 README「对外契约」：parse 2 / layer 3 / cycle 3 / incremental 2。
 * 逐场景打印 {@code PASS <组>/<名>} 或 {@code FAIL <组>/<名>  期望=… 实际=…}；
 * 结尾 {@code 结果：通过 x/N}；全过 {@code exit 0}，否则 {@code exit 1}。
 * 支持 {@code -list} 与 {@code --only <组名>}；失败不早退（每个场景在独立子进程跑，互不影响）。
 *
 * <p>判据全部确定性：不依赖墙钟、机器速度或 HashMap/HashSet 迭代顺序。每个场景把输入写到临时
 * 文件，用 {@link ProcessBuilder} 启动子进程跑 {@code layering.Planner} 并比对 stdout / 退出码。
 *
 * <p>用法：java -cp out Checker [-list] [--only &lt;组名&gt;[,&lt;组名&gt;...]]
 */
public final class Checker {

    private interface Body {
        /** 返回 null 表示 PASS；否则返回形如 {@code 期望=… 实际=…} 的失败说明。 */
        String run() throws Exception;
    }

    private static final class Scenario {
        final String name;
        final String group;
        final Body body;

        Scenario(String name, Body body) {
            this.name = name;
            this.body = body;
            int slash = name.indexOf('/');
            this.group = slash < 0 ? name : name.substring(0, slash);
        }
    }

    private static final List<Scenario> SCENARIOS = new ArrayList<>();

    // ============================ 场景输入 ============================

    private static final String PARSE_VALID =
            "module A\nmodule B\nmodule C\nedge C -> B : api\nedge B -> A : api\n";
    private static final String PARSE_ERR =
            "module A\nmodule B\nedge A -> C : api\n";

    private static final String LAYER_BASIC =
            "module A\nmodule B\nmodule C\nmodule D\n"
                    + "edge A -> C : api\nedge B -> C : api\nedge C -> D : api\n";
    private static final String LAYER_SCRAMBLED =
            "module D\nmodule C\nmodule B\nmodule A\n"
                    + "edge A -> C : api\nedge B -> C : api\nedge C -> D : api\n";

    private static final String CYCLE_PATH =
            "module A\nmodule B\nmodule C\n"
                    + "edge A -> B : api\nedge B -> C : api\nedge C -> A : api\n";
    private static final String CYCLE_MIN =
            "module A\nmodule B\nmodule C\nmodule D\n"
                    + "edge A -> B : api\nedge B -> A : api\n"
                    + "edge B -> C : api\nedge C -> D : api\nedge D -> B : api\n";
    private static final String CYCLE_IMPL =
            "module A\nmodule B\nedge A -> B : impl\nedge B -> A : api\n";

    private static final String INC_API =
            "module A\nmodule B\nmodule C\nmodule D\nmodule E\n"
                    + "edge B -> A : api\nedge C -> A : api\nedge D -> B : api\nedge E -> C : api\n";
    private static final String INC_IMPL =
            "module A\nmodule B\nmodule C\nmodule D\nmodule E\n"
                    + "edge B -> A : api\nedge C -> A : impl\nedge D -> B : api\nedge E -> C : api\n";

    // ============================ 子进程驱动 ============================

    private static final class RunResult {
        int code;
        String out;
    }

    private static RunResult runPlanner(String inputContent, String... cliArgs) throws Exception {
        File inFile = File.createTempFile("layering-in-", ".txt");
        inFile.deleteOnExit();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(inFile), StandardCharsets.UTF_8)) {
            w.write(inputContent);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("layering.Planner");
        for (String a : cliArgs) {
            cmd.add(a);
        }
        cmd.add(inFile.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append("\n");
            }
            out = sb.toString();
        }
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            RunResult rr = new RunResult();
            rr.code = -1;
            rr.out = out + "<timeout>";
            return rr;
        }
        RunResult rr = new RunResult();
        rr.code = p.exitValue();
        rr.out = out;
        return rr;
    }

    private static Set<String> lineSet(String out) {
        Set<String> s = new LinkedHashSet<>();
        for (String l : out.split("\n")) {
            if (!l.isEmpty()) {
                s.add(l);
            }
        }
        return s;
    }

    private static String exp(String exp, String act) {
        return "期望=" + exp + " 实际=" + act;
    }

    // ============================ 场景注册 ============================

    private static void register() {
        // ---- parse 2 ----
        SCENARIOS.add(new Scenario("parse/valid", () -> {
            RunResult r = runPlanner(PARSE_VALID, "--check");
            if (r.code != 0) {
                return exp("退出码 0", "退出码 " + r.code);
            }
            String trimmed = r.out.trim();
            // 模块数 3，层数 3（A0 B1 C2）
            if (!trimmed.equals("OK 3 3")) {
                return exp("OK 3 3", trimmed);
            }
            return null;
        }));

        SCENARIOS.add(new Scenario("parse/error", () -> {
            RunResult r = runPlanner(PARSE_ERR, "--check");
            if (r.code != 2) {
                return exp("退出码 2", "退出码 " + r.code);
            }
            // 第 3 行 edge A -> C 引用了未声明模块 C
            if (!r.out.contains("3")) {
                return exp("报错含行号 3", r.out.trim());
            }
            return null;
        }));

        // ---- layer 3 ----
        SCENARIOS.add(new Scenario("layer/basic", () -> {
            RunResult r = runPlanner(LAYER_BASIC, "--plan");
            if (r.code != 0) {
                return exp("退出码 0", "退出码 " + r.code);
            }
            Set<String> got = lineSet(r.out);
            Set<String> want = new LinkedHashSet<>();
            want.add("L0 D");
            want.add("L1 C");
            want.add("L2 A");
            want.add("L2 B");
            if (!got.equals(want)) {
                return exp(want.toString(), got.toString());
            }
            return null;
        }));

        SCENARIOS.add(new Scenario("layer/determinism", () -> {
            // 同一份图，仅打乱边的声明顺序（及模块声明顺序），输出必须逐字节相同
            RunResult a = runPlanner(LAYER_BASIC, "--plan");
            RunResult b = runPlanner(LAYER_SCRAMBLED, "--plan");
            if (a.code != 0 || b.code != 0) {
                return exp("退出码 0/0", "退出码 " + a.code + "/" + b.code);
            }
            if (!a.out.equals(b.out)) {
                return exp("两次输出逐字节相同", "A=[" + a.out + "] B=[" + b.out + "]");
            }
            return null;
        }));

        SCENARIOS.add(new Scenario("layer/golden", () -> {
            RunResult r = runPlanner(LAYER_BASIC, "--plan");
            if (r.code != 0) {
                return exp("退出码 0", "退出码 " + r.code);
            }
            String want = "L0 D\nL1 C\nL2 A\nL2 B\n";
            if (!r.out.equals(want)) {
                return exp("固定格式 golden", "[" + r.out + "]");
            }
            return null;
        }));

        // ---- cycle 3 ----
        SCENARIOS.add(new Scenario("cycle/path", () -> {
            RunResult r = runPlanner(CYCLE_PATH, "--plan");
            if (r.code != 1) {
                return exp("退出码 1", "退出码 " + r.code);
            }
            if (!r.out.trim().equals("CYCLE A -> B -> C -> A")) {
                return exp("CYCLE A -> B -> C -> A", r.out.trim());
            }
            return null;
        }));

        SCENARIOS.add(new Scenario("cycle/minimal", () -> {
            RunResult r = runPlanner(CYCLE_MIN, "--plan");
            if (r.code != 1) {
                return exp("退出码 1", "退出码 " + r.code);
            }
            // 最短为 2 环 A<->B；起点取字典序最小的 A
            if (!r.out.trim().equals("CYCLE A -> B -> A")) {
                return exp("CYCLE A -> B -> A", r.out.trim());
            }
            return null;
        }));

        SCENARIOS.add(new Scenario("cycle/impl", () -> {
            RunResult r = runPlanner(CYCLE_IMPL, "--plan");
            if (r.code != 1) {
                return exp("退出码 1", "退出码 " + r.code);
            }
            // impl 边必须计入环检测：A --impl--> B --api--> A
            if (!r.out.trim().equals("CYCLE A -> B -> A")) {
                return exp("CYCLE A -> B -> A", r.out.trim());
            }
            return null;
        }));

        // ---- incremental 2 ----
        SCENARIOS.add(new Scenario("incremental/api", () -> {
            RunResult r = runPlanner(INC_API, "--changed", "A");
            if (r.code != 0) {
                return exp("退出码 0", "退出码 " + r.code);
            }
            Set<String> got = lineSet(r.out);
            Set<String> want = new LinkedHashSet<>();
            for (String m : new String[]{"A", "B", "C", "D", "E"}) {
                want.add(m);
            }
            if (!got.equals(want)) {
                return exp(want.toString(), got.toString());
            }
            return null;
        }));

        SCENARIOS.add(new Scenario("incremental/impl", () -> {
            RunResult r = runPlanner(INC_IMPL, "--changed", "A");
            if (r.code != 0) {
                return exp("退出码 0", "退出码 " + r.code);
            }
            // A 变了：B(C 通过 api 依赖 A) 与 C(通过 impl 依赖 A) 重建；
            // 但 C 的 api 依赖者 E 不应被拖入（impl 不向上传播）。
            Set<String> got = lineSet(r.out);
            Set<String> want = new LinkedHashSet<>();
            for (String m : new String[]{"A", "B", "C", "D"}) {
                want.add(m);
            }
            if (!got.equals(want)) {
                return exp(want.toString(), got.toString());
            }
            return null;
        }));
    }

    // ============================ 入口 ============================

    public static void main(String[] args) throws Exception {
        List<String> onlyGroups = new ArrayList<>();
        boolean list = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-list".equals(a)) {
                list = true;
            } else if ("--only".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--only 需要一个组名");
                    System.exit(2);
                }
                for (String grp : args[++i].split(",")) {
                    if (!grp.isEmpty()) {
                        onlyGroups.add(grp);
                    }
                }
            }
        }

        register();

        if (list) {
            for (Scenario s : SCENARIOS) {
                System.out.println(s.name);
            }
            return;
        }

        int pass = 0;
        int fail = 0;
        for (Scenario s : SCENARIOS) {
            if (!onlyGroups.isEmpty() && !onlyGroups.contains(s.group)) {
                continue;
            }
            String msg;
            try {
                msg = s.body.run();
            } catch (Throwable t) {
                msg = exp("通过", "抛出异常 " + t);
            }
            if (msg == null) {
                pass++;
                System.out.println("PASS " + s.name);
            } else {
                fail++;
                System.out.println("FAIL " + s.name + "  " + msg);
            }
        }
        System.out.println("结果：通过 " + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
