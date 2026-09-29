package layering;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 模块依赖图的行式解析器（给定全，勿改语义）。
 *
 * <p>输入格式（README「对外契约」第 1 条，固定不可改）：
 * <ul>
 *   <li>{@code module <name>} 声明一个模块；</li>
 *   <li>{@code edge <from> -> <to> : api|impl} 声明一条依赖边；</li>
 *   <li>以 {@code #} 起头的行与空行被忽略；</li>
 *   <li>未知模块 / 重复声明 / 非法 scope → 抛 {@link PlanException}（带行号），调用方据此退出码 2。</li>
 * </ul>
 *
 * <p>解析结果用确定性容器保存：{@link #modules()} 是 {@link TreeSet}（名字升序），
 * {@link #adj()} 是 {@link TreeMap}（按 from 升序），每条边按声明顺序追加。所有下游算法
 * 都只依赖这两个有序结构，不依赖任何 HashMap/HashSet 的迭代顺序。
 */
public final class ModuleGraph {

    private final TreeSet<String> modules = new TreeSet<>();
    private final TreeMap<String, List<Edge>> adj = new TreeMap<>();
    private final List<String> order = new ArrayList<>();

    private ModuleGraph() {}

    /** 一条依赖边：{@code from} 依赖 {@code to}，{@code scope ∈ {api, impl}}。 */
    public static final class Edge {
        public final String from;
        public final String to;
        public final String scope;

        public Edge(String from, String to, String scope) {
            this.from = from;
            this.to = to;
            this.scope = scope;
        }
    }

    public TreeSet<String> modules() {
        return modules;
    }

    public TreeMap<String, List<Edge>> adj() {
        return adj;
    }

    /** 模块声明顺序（1 起、按输入物理顺序）。下游算法不应依赖它来保证确定性。 */
    public List<String> declaredOrder() {
        return order;
    }

    public static ModuleGraph parse(Reader in) throws IOException, PlanException {
        ModuleGraph g = new ModuleGraph();
        BufferedReader br = new BufferedReader(in);
        String raw;
        int lineno = 0;
        while ((raw = br.readLine()) != null) {
            lineno++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] tok = line.split("\\s+");
            if (tok[0].equals("module")) {
                if (tok.length != 2) {
                    throw new PlanException(lineno, "bad module declaration: " + line);
                }
                String name = tok[1];
                if (!isValidName(name)) {
                    throw new PlanException(lineno, "illegal module name: " + name);
                }
                if (g.modules.contains(name)) {
                    throw new PlanException(lineno, "duplicate module: " + name);
                }
                g.modules.add(name);
                g.order.add(name);
                g.adj.putIfAbsent(name, new ArrayList<>());
            } else if (tok[0].equals("edge")) {
                if (tok.length != 6 || !tok[2].equals("->") || !tok[4].equals(":")) {
                    throw new PlanException(lineno, "bad edge declaration: " + line);
                }
                String from = tok[1];
                String to = tok[3];
                String scope = tok[5];
                if (!isValidName(from) || !isValidName(to)) {
                    throw new PlanException(lineno, "illegal module name");
                }
                if (!g.modules.contains(from)) {
                    throw new PlanException(lineno, "unknown module: " + from);
                }
                if (!g.modules.contains(to)) {
                    throw new PlanException(lineno, "unknown module: " + to);
                }
                if (!scope.equals("api") && !scope.equals("impl")) {
                    throw new PlanException(lineno, "illegal scope: " + scope);
                }
                g.adj.computeIfAbsent(from, k -> new ArrayList<>()).add(new Edge(from, to, scope));
                g.adj.computeIfAbsent(to, k -> new ArrayList<>());
            } else {
                throw new PlanException(lineno, "unexpected line: " + tok[0]);
            }
        }
        return g;
    }

    private static boolean isValidName(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-')) {
                return false;
            }
        }
        return true;
    }
}
