package io.ailink.agentforge.bitcask.value;

import java.math.BigDecimal;
import java.math.BigInteger;

/** 精确有理数（duration 的小数分量层层下推用；浮点参数按十进制值换成比值，同 Clojure 的 rationalize）。 */
record Frac(BigInteger num, BigInteger den) {
    static final Frac ZERO = new Frac(BigInteger.ZERO, BigInteger.ONE);

    Frac {
        if (den.signum() == 0) throw new ArithmeticException("Divide by zero");
        if (den.signum() < 0) {
            num = num.negate();
            den = den.negate();
        }
        BigInteger g = num.gcd(den);
        if (!g.equals(BigInteger.ONE) && g.signum() != 0) {
            num = num.divide(g);
            den = den.divide(g);
        }
    }

    static Frac of(long n) {
        return new Frac(BigInteger.valueOf(n), BigInteger.ONE);
    }

    static Frac of(long n, long d) {
        return new Frac(BigInteger.valueOf(n), BigInteger.valueOf(d));
    }

    static boolean isIntegral(Object x) {
        return x instanceof Long || x instanceof Integer || x instanceof Short || x instanceof Byte || x instanceof BigInteger;
    }

    /** 数 ⇒ 精确值：整数原样；Float / Double 按十进制表示（{@code BigDecimal.valueOf}）；BigDecimal 精确。 */
    static Frac of(Number x) {
        if (x instanceof BigInteger b) return new Frac(b, BigInteger.ONE);
        if (isIntegral(x)) return of(x.longValue());
        BigDecimal d = x instanceof BigDecimal bd ? bd : BigDecimal.valueOf(x.doubleValue());
        if (d.scale() <= 0) return new Frac(d.toBigIntegerExact(), BigInteger.ONE);
        return new Frac(d.unscaledValue(), BigInteger.TEN.pow(d.scale()));
    }

    Frac add(Frac o) {
        return new Frac(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
    }

    Frac sub(Frac o) {
        return add(o.negate());
    }

    Frac mul(Frac o) {
        return new Frac(num.multiply(o.num), den.multiply(o.den));
    }

    Frac div(Frac o) {
        return new Frac(num.multiply(o.den), den.multiply(o.num));
    }

    Frac negate() {
        return new Frac(num.negate(), den);
    }

    /** 向下取整。 */
    BigInteger floor() {
        BigInteger[] qr = num.divideAndRemainder(den);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    Frac floorFrac() {
        return new Frac(floor(), BigInteger.ONE);
    }

    boolean isWhole() {
        return den.equals(BigInteger.ONE);
    }
}
