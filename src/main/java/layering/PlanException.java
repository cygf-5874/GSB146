package layering;

/**
 * 解析期异常，携带出错的行号。调用方据此打印 {@code error: <行号>: <信息>} 并以退出码 2 结束，
 * 不允许抛栈或静默吞掉。
 */
public final class PlanException extends Exception {

    private final int line;

    public PlanException(int line, String message) {
        super(message);
        this.line = line;
    }

    /** 出错行号（1 起）；-1 表示与具体输入行无关（如「有环」在测试 API 里）。 */
    public int line() {
        return line;
    }
}
