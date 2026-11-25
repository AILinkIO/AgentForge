package io.ailink.agentforge.bitcask;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 批里的一个 op（批就是 {@code List<BatchOp>}：拼接 / 复用都是值操作）。四种：{@link Put} {@link Delete} {@link PutDoc}
 * 各自可带一个前置条件（null = 无条件），{@link Check} 只校验不写（条件必填）。key / value 是 byte[]，字符串按 UTF-8。
 */
public sealed interface BatchOp permits BatchOp.Put, BatchOp.Delete, BatchOp.PutDoc, BatchOp.Check {
    byte[] key();

    /** 前置条件（null = 无）。 */
    Condition condition();

    record Put(byte[] key, byte[] value, Condition condition) implements BatchOp {
        public Put {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    record Delete(byte[] key, Condition condition) implements BatchOp {
        public Delete {
            Objects.requireNonNull(key, "key");
        }
    }

    record PutDoc(byte[] key, Document doc, Condition condition) implements BatchOp {
        public PutDoc {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(doc, "doc");
        }
    }

    record Check(byte[] key, Condition condition) implements BatchOp {
        public Check {
            Objects.requireNonNull(key, "key");
            if (condition == null) throw BitcaskException.of("bitcask/batch-cond-invalid", "check without condition");
        }
    }

    private static byte[] u(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static BatchOp put(byte[] key, byte[] value) {
        return new Put(key, value, null);
    }

    static BatchOp put(byte[] key, byte[] value, Condition condition) {
        return new Put(key, value, condition);
    }

    static BatchOp put(String key, String value) {
        return new Put(u(key), u(value), null);
    }

    static BatchOp put(String key, String value, Condition condition) {
        return new Put(u(key), u(value), condition);
    }

    static BatchOp put(String key, byte[] value) {
        return new Put(u(key), value, null);
    }

    static BatchOp put(String key, byte[] value, Condition condition) {
        return new Put(u(key), value, condition);
    }

    static BatchOp delete(byte[] key) {
        return new Delete(key, null);
    }

    static BatchOp delete(byte[] key, Condition condition) {
        return new Delete(key, condition);
    }

    static BatchOp delete(String key) {
        return new Delete(u(key), null);
    }

    static BatchOp delete(String key, Condition condition) {
        return new Delete(u(key), condition);
    }

    static BatchOp putDoc(byte[] key, Document doc) {
        return new PutDoc(key, doc, null);
    }

    static BatchOp putDoc(byte[] key, Document doc, Condition condition) {
        return new PutDoc(key, doc, condition);
    }

    static BatchOp putDoc(String key, Document doc) {
        return new PutDoc(u(key), doc, null);
    }

    static BatchOp putDoc(String key, Document doc, Condition condition) {
        return new PutDoc(u(key), doc, condition);
    }

    static BatchOp check(byte[] key, Condition condition) {
        return new Check(key, condition);
    }

    static BatchOp check(String key, Condition condition) {
        return new Check(u(key), condition);
    }
}
