package layering;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 模块分层构建计划工具（朴素起点实现 —— 待重写）。
 *
 * <p>这是出题时的「既有实现」：直接遍历邻接表分层，边不分 scope，有环只打印一句「有环」，
 * 且打印顺序依赖模块声明顺序（非确定性）。12 个既有用例全绿，但固定件只有 4/10 过：
 * <ul>
 *   <li>parse 2/2、layer 1/3（确定性 2 项挂）、cycle 0/3（只报「有环」不给路径）、
 *       incremental 1/2（impl 项爆炸）。</li>
 * </ul>
 *
 * <p>测试 API 签名（{@link #layers}、{@link #hasCycle}、{@link #changedClosure}）与参考实现一致，
 * 便于既有用例在重写前后都全绿。重写只需替换本文件的方法体，无需改签名。
 */
public final class Planner {

    // ============================ 测试 API（签名固定，起点/参考共用） ============================

    /** 朴素拓扑分层：最长路径松弛，不缩点；有环抛 {@link PlanException}。 */
    public static TreeMap<String, Integer> layers(ModuleGraph g) throws PlanException {
        if (hasCycle(g)) {
            throw new PlanException(-1, "graph has a cycle");
        }
        TreeMap<String, Integer> layer = new TreeMap<>();
        for (String m : g.modules()) {
            layer.put(m, 0);
        }
        List<ModuleGraph.Edge> edges = allEdges(g);
        boolean changed = true;
        int guard = g.modules().size() + 2;
        while (changed && guard-- > 0) {
            changed = false;
            for (ModuleGraph.Edge e : edges) {
                int cand = layer.get(e.to) + 1;
                if (cand > layer.get(e.from)) {
                    layer.put(e.from, cand);
                    changed = true;
                }
            }
        }
        return layer;
    }

    /** 朴素环检测（DFS 三色，不区分 scope，也不给路径）。 */
    public static boolean hasCycle(ModuleGraph g) {
        Set<String> white = new HashSet<>(g.modules());
        Set<String> gray = new HashSet<>();
        Set<String> black = new HashSet<>();
        for (String m : g.modules()) {
            if (white.contains(m) && dfsCycle(g, m, white, gray, black)) {
                return true;
            }
        }
        return false;
    }

    /** 朴素重建集：用「全邻接」做传递闭包（impl 边也向上传播 → 会爆炸）。 */
    public static TreeSet<String> changedClosure(ModuleGraph g, Collection<String> changed) {
        Map<String, List<String>> dependents = new HashMap<>();
        for (ModuleGraph.Edge e : allEdges(g)) {
            dependents.computeIfAbsent(e.to, k -> new ArrayList<>()).add(e.from);
        }
        TreeSet<String> result = new TreeSet<>();
        Deque<String> q = new ArrayDeque<>();
        for (String c : changed) {
            if (g.modules().contains(c) && result.add(c)) {
                q.add(c);
            }
        }
        while (!q.isEmpty()) {
            String m = q.poll();
            for (String d : dependents.getOrDefault(m, Collections.emptyList())) {
                if (result.add(d)) {
                    q.add(d);
                }
            }
        }
        return result;
    }

    // ============================ 朴素 CLI ============================

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (PlanException pe) {
            System.err.println("error: " + pe.line() + ": " + pe.getMessage());
            System.exit(2);
        } catch (IOException ioe) {
            System.err.println("io error: " + ioe.getMessage());
            System.exit(2);
        }
    }

    private static int run(String[] args) throws PlanException, IOException {
        String mode = null;
        String input = null;
        String outFile = null;
        String changed = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--check")) {
                mode = "check";
            } else if (a.equals("--plan")) {
                mode = "plan";
            } else if (a.equals("--changed")) {
                mode = "changed";
                changed = args[++i];
            } else if (a.equals("--out")) {
                outFile = args[++i];
            } else if (input == null) {
                input = a;
            } else {
                throw new PlanException(-1, "unexpected argument: " + a);
            }
        }
        if (mode == null) {
            System.err.println("usage: Planner --check|--plan|--changed A,B INPUT");
            return 2;
        }
        if (input == null) {
            System.err.println("missing input file");
            return 2;
        }

        ModuleGraph g = ModuleGraph.parse(
                new InputStreamReader(new FileInputStream(input), StandardCharsets.UTF_8));

        if (mode.equals("changed")) {
            TreeSet<String> ch = changedClosure(g, java.util.Arrays.asList(changed.split(",")));
            StringBuilder sb = new StringBuilder();
            for (String m : ch) {
                sb.append(m).append("\n");
            }
            System.out.print(sb);
            return 0;
        }

        if (hasCycle(g)) {
            // 朴素：只报「有环」，不给具体路径
            System.out.println("CYCLE DETECTED");
            return 1;
        }

        TreeMap<String, Integer> layer = layers(g);

        if (mode.equals("check")) {
            int maxLayer = 0;
            for (int v : layer.values()) {
                maxLayer = Math.max(maxLayer, v);
            }
            System.out.println("OK " + g.modules().size() + " " + (maxLayer + 1));
            return 0;
        }

        // 朴素：按模块声明顺序打印（依赖输入物理顺序 → 非确定性；且未同层排序）
        StringBuilder sb = new StringBuilder();
        for (String m : g.declaredOrder()) {
            sb.append("L").append(layer.get(m)).append(" ").append(m).append("\n");
        }
        String text = sb.toString();
        if (outFile != null) {
            writeOut(outFile, text);
        }
        System.out.print(text);
        return 0;
    }

    private static void writeOut(String outFile, String text) throws IOException {
        Files.write(Paths.get(outFile + ".tmp"), text.getBytes(StandardCharsets.UTF_8));
        Files.move(Paths.get(outFile + ".tmp"), Paths.get(outFile), StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<ModuleGraph.Edge> allEdges(ModuleGraph g) {
        List<ModuleGraph.Edge> es = new ArrayList<>();
        for (List<ModuleGraph.Edge> l : g.adj().values()) {
            es.addAll(l);
        }
        return es;
    }

    private static boolean dfsCycle(ModuleGraph g, String u, Set<String> white, Set<String> gray, Set<String> black) {
        white.remove(u);
        gray.add(u);
        for (ModuleGraph.Edge e : g.adj().getOrDefault(u, Collections.emptyList())) {
            String v = e.to;
            if (gray.contains(v)) {
                return true;
            }
            if (white.contains(v) && dfsCycle(g, v, white, gray, black)) {
                return true;
            }
        }
        gray.remove(u);
        black.add(u);
        return false;
    }
}
