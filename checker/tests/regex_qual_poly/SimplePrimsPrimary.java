import org.checkerframework.checker.regex.qual.*;
import org.checkerframework.qualframework.poly.qual.Wildcard;

// Test primary annotations with primitives
// primitives don't have non-primary qualifiers
class SimplePrims {

    void execute(@Regex(1) int s) {}

    void tainted(int s) {}

    void intLiteral() {
        //:: error: (argument.type.incompatible)
        execute(5);
        tainted(6);
    }

    void intRef(int ref) {
        //:: error: (argument.type.incompatible)
        execute(ref);
        tainted(ref);
    }

    void untaintedRef(@Regex(1) int ref) {
        execute(ref);
        tainted(ref);
    }

    void concatenation(@Regex(1) int s1, int s2) {
        execute(s1 + s1);
        execute(s1 += s1);
        //:: error: (argument.type.incompatible)
        execute(s1 + 3);

        //:: error: (argument.type.incompatible)
        execute(s1 + s2);

        //:: error: (argument.type.incompatible)
        execute(s2 + s1);
        //:: error: (argument.type.incompatible)
        execute(s2 + 4);
        //:: error: (argument.type.incompatible)
        execute(s2 + s2);

        tainted(s1 + s1);
        tainted(s1 + 7);
        tainted(s1 + s2);

        tainted(s2 + s1);
        tainted(s2 + 8);
        tainted(s2 + s2);
    }
}
