layering 是 Java 17 的模块依赖分层计划工具，仅使用 JDK 标准库。README「对外契约」有 10 条，Planner.java 当前未满足全部语义。

任务：重写 Planner.java，实现 SCC 缩点、最长路径分层、确定的同层排序、含 impl 边的具体环路径搜索、api 传递闭包增量重建，以及 --check / --plan / --out 行为。

验收：构建成功；既有用例全绿；bash scripts/check.sh 退出码 0，10 个场景全过（parse 2 + layer 3 + cycle 3 + incremental 2）；同一输入两次输出逐字节相同。

约束：
1. 不改 check/、输入解析与 CLI 标志；可以新增类。
2. 只用 JDK，不引入 Maven/Gradle；禁止依赖 HashMap/HashSet 迭代顺序。
3. 环路径按最短长度、起点名、完整路径字典序裁决；搜索必须确定且有界。
4. impl 边不参与重建传递，但参与环检测，两张图必须分别维护。
