package io.ailink.agentforge.bitcask.ffm;

import static io.ailink.agentforge.bitcask.ffm.NativeTypes.DOC_FIELD;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.DOC_INPUT_EX;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.SIZE_T;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.off;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.MemorySegment;
import java.util.Map;

import io.ailink.agentforge.bitcask.Document;

/** {@link Document} ⇒ {@code bitcask_doc_input_ex_t}（全部进 arena）。 */
final class DocMarshal {
    private static final long TEXT = off(DOC_INPUT_EX, "text");
    private static final long META = off(DOC_INPUT_EX, "meta");
    private static final long VECTOR = off(DOC_INPUT_EX, "vector");
    private static final long VECTOR_LEN = off(DOC_INPUT_EX, "vector_len");
    private static final long EXPIRY_AT = off(DOC_INPUT_EX, "expiry_at");
    private static final long FIELDS = off(DOC_INPUT_EX, "fields");
    private static final long FIELDS_COUNT = off(DOC_INPUT_EX, "fields_count");
    private static final long FIELD_NAME = off(DOC_FIELD, "name");
    private static final long FIELD_VALUE = off(DOC_FIELD, "value");

    private DocMarshal() {
    }

    static MemorySegment toNative(Document d, NativeArena arena) {
        MemorySegment doc = arena.alloc(DOC_INPUT_EX);
        arena.setUtf8Slice(doc, TEXT, d.text());
        arena.setBytesSlice(doc, META, d.meta(), 0, d.meta().length);
        doc.set(ADDRESS, VECTOR, arena.copy(d.vector()));
        doc.set(SIZE_T, VECTOR_LEN, d.vector().length);
        doc.set(JAVA_LONG, EXPIRY_AT, d.expiresAt());
        Map<String, String> fields = d.fields();
        if (!fields.isEmpty()) {
            MemorySegment fp = arena.alloc(DOC_FIELD, fields.size());
            int i = 0;
            for (var e : fields.entrySet()) {
                long base = i++ * DOC_FIELD.byteSize();
                arena.setUtf8Slice(fp, base + FIELD_NAME, e.getKey());
                arena.setUtf8Slice(fp, base + FIELD_VALUE, e.getValue());
            }
            doc.set(ADDRESS, FIELDS, fp);
            doc.set(SIZE_T, FIELDS_COUNT, fields.size());
        }
        return doc;
    }
}
