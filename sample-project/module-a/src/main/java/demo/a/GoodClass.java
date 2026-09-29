package demo.a;

/**
 * Calculates something.
 *
 * @param foo input
 * @param bar input
 * @return result
 * @since 1.3
 */
public class GoodClass {
    /**
     * Calculates.
     *
     * @param foo input
     * @param bar input
     * @return result
     * @since 1.3
     */
    public int calculate(int foo, int bar) {
        return foo + bar;
    }

    /**
     * Does something.
     */
    public int fallback(int value) {
        return value;
    }
}
