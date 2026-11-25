package io.ailink.agentforge.bitcask.cypher;

import java.util.ArrayList;
import java.util.Collection;

/**
 * 模式表达式的值：路径列表，带「模式」标记（clj 里是带 ::x/pattern 元数据的序列）——布尔场合按「非空」当真假（openCypher）。
 */
final class PatternList extends ArrayList<Object> {
    PatternList() {
    }

    PatternList(Collection<?> xs) {
        super(xs);
    }
}
