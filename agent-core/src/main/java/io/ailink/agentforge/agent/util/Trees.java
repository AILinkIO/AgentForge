package io.ailink.agentforge.agent.util;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 删一棵树，**不跟软链**：软链本身删掉，不进它指向的目录（否则一条指向 ~ 的链能删掉家目录）。 */
public final class Trees {
    private Trees() { }

    public static void rmNoFollow(Path p) throws IOException {
        if (Files.isSymbolicLink(p)) { Files.delete(p); return; }
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) rmNoFollow(c);
            }
        }
        Files.deleteIfExists(p);
    }
}
