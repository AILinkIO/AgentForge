package io.ailink.agentforge.agent.codemode;

import io.ailink.agentforge.agent.codemode.Datum.Condition;
import io.ailink.agentforge.agent.codemode.Datum.Hashtable;
import io.ailink.agentforge.agent.codemode.Datum.MultipleValues;
import io.ailink.agentforge.agent.codemode.Datum.Nil;
import io.ailink.agentforge.agent.codemode.Datum.Pair;
import io.ailink.agentforge.agent.codemode.Datum.Procedure;
import io.ailink.agentforge.agent.codemode.Datum.SChar;
import io.ailink.agentforge.agent.codemode.Datum.Sym;
import io.ailink.agentforge.agent.codemode.Datum.Void;

import java.util.List;

/** 值 → 文本（Chez 的 {@code write} / {@code display} 形状，表 / 向量 / 数都是模型认得的形）。 */
final class Printer {
    private Printer() { }

    /** 与 coxswain 的 {@code (print-length 2000) (print-level 20)} 同。 */
    static final int PRINT_LENGTH = 2000;
    static final int PRINT_LEVEL = 20;

    static String write(Object v) {
        StringBuilder b = new StringBuilder();
        print(v, b, true, 0);
        return b.toString();
    }

    static String display(Object v) {
        StringBuilder b = new StringBuilder();
        print(v, b, false, 0);
        return b.toString();
    }

    static void print(Object v, StringBuilder b, boolean write, int level) {
        if (b.length() > 4_000_000) {
            b.append("...");
            return;
        }
        if (v instanceof String s) {
            if (write) writeString(s, b); else b.append(s);
        } else if (v instanceof Long || v instanceof Double) {
            b.append(number(v));
        } else if (v instanceof Boolean x) {
            b.append(x ? "#t" : "#f");
        } else if (v instanceof Sym s) {
            b.append(s.name);
        } else if (v instanceof SChar c) {
            if (write) b.append(charName(c.cp())); else b.appendCodePoint(c.cp());
        } else if (v == Nil.NIL) {
            b.append("()");
        } else if (v instanceof Pair p) {
            if (level >= PRINT_LEVEL) { b.append("..."); return; }
            if (p.car instanceof Sym s && p.cdr instanceof Pair p2 && p2.cdr == Nil.NIL) {
                String pre = switch (s.name) {
                    case "quote" -> "'";
                    case "quasiquote" -> "`";
                    case "unquote" -> ",";
                    case "unquote-splicing" -> ",@";
                    default -> null;
                };
                if (pre != null) {
                    b.append(pre);
                    print(p2.car, b, write, level + 1);
                    return;
                }
            }
            b.append('(');
            Object x = p;
            int n = 0;
            while (x instanceof Pair q) {
                if (n > 0) b.append(' ');
                if (n >= PRINT_LENGTH) { b.append("..."); x = Nil.NIL; break; }
                print(q.car, b, write, level + 1);
                x = q.cdr;
                n++;
            }
            if (x != Nil.NIL) {
                b.append(" . ");
                print(x, b, write, level + 1);
            }
            b.append(')');
        } else if (v instanceof Object[] a) {
            if (level >= PRINT_LEVEL) { b.append("..."); return; }
            b.append("#(");
            for (int k = 0; k < a.length; k++) {
                if (k > 0) b.append(' ');
                if (k >= PRINT_LENGTH) { b.append("..."); break; }
                print(a[k], b, write, level + 1);
            }
            b.append(')');
        } else if (v == Void.VOID) {
            b.append("#<void>");
        } else if (v instanceof Procedure p) {
            b.append(p.name == null ? "#<procedure>" : "#<procedure " + p.name + ">");
        } else if (v instanceof Hashtable) {
            b.append("#<hashtable>");
        } else if (v instanceof Condition c) {
            b.append("#<condition ").append(conditionText(c)).append('>');
        } else if (v instanceof MultipleValues mv) {
            for (int k = 0; k < mv.vals().length; k++) {
                if (k > 0) b.append(' ');
                print(mv.vals()[k], b, write, level);
            }
        } else {
            b.append("#<").append(v == null ? "null" : v.getClass().getSimpleName()).append('>');
        }
    }

    static String number(Object v) {
        if (v instanceof Long l) return Long.toString(l);
        double d = (Double) v;
        if (Double.isNaN(d)) return "+nan.0";
        if (Double.isInfinite(d)) return d > 0 ? "+inf.0" : "-inf.0";
        if (d == Math.rint(d) && Math.abs(d) < 1e21) {
            return (d == 0 && 1 / d < 0 ? "-" : "") + Long.toString((long) Math.abs(d) * (d < 0 ? -1 : 1)) + ".0";
        }
        String s = Double.toString(d);
        int e = s.indexOf('E');
        if (e >= 0) {
            String mant = s.substring(0, e);
            if (mant.endsWith(".0")) mant = mant.substring(0, mant.length() - 2);
            s = mant + "e" + s.substring(e + 1);
        }
        return s;
    }

    private static void writeString(String s, StringBuilder b) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                case '\r' -> b.append("\\r");
                default -> {
                    if (c < 0x20) b.append("\\x").append(Integer.toHexString(c)).append(';');
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }

    private static String charName(int cp) {
        return switch (cp) {
            case ' ' -> "#\\space";
            case '\n' -> "#\\newline";
            case '\t' -> "#\\tab";
            case '\r' -> "#\\return";
            case 0 -> "#\\nul";
            case 7 -> "#\\alarm";
            case 8 -> "#\\backspace";
            case 127 -> "#\\delete";
            case 27 -> "#\\esc";
            default -> cp < 0x20 ? "#\\x" + Integer.toHexString(cp) : "#\\" + new String(Character.toChars(cp));
        };
    }

    /**
     * 一个 raise 出来的东西 → 一行诊断（Chez {@code display-condition} 的形状）：
     * {@code Exception in who: message with irritants (…)}。消息里有 {@code ~} 就当格式串用 irritants 填。
     */
    static String conditionText(Object payload) {
        if (payload instanceof Condition c) {
            String msg = c.message == null ? "" : c.message;
            String body;
            List<Object> irr = c.irritants;
            if (msg.indexOf('~') >= 0) {
                try {
                    body = Builtins.format(msg, irr.toArray());
                } catch (RuntimeException e) {
                    body = msg;
                }
            } else if (!irr.isEmpty()) {
                body = msg + " with irritants " + write(Datum.fromList(irr));
            } else {
                body = msg;
            }
            String who = c.who instanceof Sym s ? s.name : c.who instanceof String s ? s : null;
            return (who == null ? "Exception: " : "Exception in " + who + ": ") + body;
        }
        return "Exception occurred with non-condition value " + write(payload);
    }
}
