package io.ailink.agentforge.agent.tools.file;

import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Args;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.Deletion;
import io.ailink.agentforge.agent.tools.Limit;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import static io.ailink.agentforge.agent.tools.ToolText.q;

/**
 * delete-path（对应 coxswain {@code tools/delete.ss}）。
 *
 * <p>三层：分级（{@link Deletion#risk}）→ 高风险（软链 / 越界 / 悬空）一律拒、等审批闸 → 闸放过之后
 * 按确认格里用户点过头的那个目标删，执行前再复核一次（TOCTOU，fail closed）。删除原语从不跟着链接走。
 * 不并发（单个确认格靠"带闸的工具从不同时跑两件"成立）、不 stageable（删除攒不住）。
 */
public final class DeleteTool {
    private DeleteTool() { }

    public static List<AgentTool> tools() { return List.of(deletePath()); }

    /** 整棵删，不跟链接：路径本身是软链只删链接；目录里的软链也只删链接。不存在是 no-op。 */
    public static void rmTreeNoFollow(Path p) throws IOException {
        if (Files.isSymbolicLink(p)) { Files.deleteIfExists(p); return; }
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) { Files.deleteIfExists(p); return; }
        Files.walkFileTree(p, new SimpleFileVisitor<>() {   // walkFileTree 默认不跟链接
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) throws IOException {
                if (e instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                throw e;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null && !(e instanceof NoSuchFileException)) throw e;
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean nonEmptyDir(Path p) {
        if (!Files.isDirectory(p)) return false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
            return ds.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    private static ToolException needsGate(String path, Deletion.Risk risk) {
        String why = switch (risk.kind()) {
            case SYMLINK -> "它是软链接（实际指向 " + risk.real() + "）";
            case ESCAPE -> "它会落到工作根目录之外（" + (risk.real() != null ? risk.real() : "路径不存在") + "）";
            case UNRESOLVABLE -> "它的真实指向解不出来（悬空软链，或路径已损坏）";
        };
        return new ToolException("没有删 " + q(path) + "：" + why
                + "。该路径需要用户审批，须通过 gate 后重试 —— 重新调用一次，审批闸会把风险摆给用户看；不要改用 run-command 的 rm 绕过去");
    }

    private static String deleteNormal(String path, String full, boolean recursive) throws IOException {
        Path p = Path.of(full);
        // 幂等："已经没有了"不是错误
        if (!Files.exists(p)) return full + " 本来就不存在，无需删除";
        if (!recursive && nonEmptyDir(p)) {
            throw new ToolException(q(path) + " 是一个非空目录，没有删。要连同里面的内容整棵删掉就再调一次，并加 recursive=true");
        }
        boolean dir = Files.isDirectory(p);
        rmTreeNoFollow(p);
        return dir ? "删除了目录 " + full + "（连同里面的全部内容）" : "删除了 " + full;
    }

    private static String deleteLinkOnly(String full) throws IOException {
        String real = Limit.realpath(Path.of(full));
        rmTreeNoFollow(Path.of(full));
        return real != null
                ? "只删了链接本身 " + full + "（真实指向 " + real + " 未动）"
                : "只删了链接本身 " + full + "（悬空链接，本来就没有指向的东西）";
    }

    private static String deleteContents(Workspace ws, String path, String full, String target) throws IOException {
        // TOCTOU 复核：现在再解一遍必须还是同一个目标；解不出或解到别处都说明用户点头的那一份已经不在了
        String real = Limit.realpath(Path.of(full));
        if (real == null || !real.equals(target)) {
            throw new ToolException(q(path) + " 在审批之后被改了指向（现在解到 " + (real != null ? real : "悬空") + "，审批时是 " + target
                    + "），没有删任何东西；请重新发起一次，让用户再确认");
        }
        rmTreeNoFollow(Path.of(target));
        rmTreeNoFollow(Path.of(full));
        String realRoot = Limit.realpath(ws.root());
        boolean inside = realRoot != null && Limit.pathContains(realRoot, target);
        return inside
                ? "连内容一起删了（真实指向 " + target + "）：链接 " + full + " 和它指向的内容都已删除"
                : "删除了 " + target + "（工作根目录之外的路径，这次删除经过用户审批）";
    }

    private static String deleteConfirmed(Workspace ws, String path, String full, String target) throws IOException {
        if (target == null) throw new ToolException("确认格里给的目标不是路径（#f），没有删任何东西");
        // 审批之后链接被换成真目录的话这里就不再是链接，落到 contents 那支的 realpath 复核 → fail closed
        if (Files.isSymbolicLink(Path.of(full))) {
            return target.equals(full) ? deleteLinkOnly(full) : deleteContents(ws, path, full, target);
        }
        return deleteContents(ws, path, full, target);
    }

    public static AgentTool deletePath() {
        return AgentTool.builder("delete-path")
                .description("删除一个文件或目录。删东西优先用这件工具，不要用 run-command 跑 rm / rm -rf。"
                        + "路径相对于工作根目录；非空目录要 recursive=true 才会整棵删掉，不加会报错；"
                        + "不存在的路径直接算成功（幂等，删两遍不会第二遍报错）。"
                        + "⚠️ 会先解析真实路径（realpath）：软链接默认只删链接本身、不跟过去；"
                        + "指向工作根目录之外、或解不出来的路径（悬空软链），删除前要先过用户审批 —— "
                        + "被拦下时照错误信息说的重试一次，让审批闸把它摆给用户看，不要换 rm 绕路。")
                .required("path", "string", "要删除的文件或目录，相对于工作根目录")
                .optional("recursive", "boolean", "非空目录要连同内容整棵删掉时传 true；不传则非空目录会报错（空目录与文件不用传）", false)
                .sensitive()
                .handler(DeleteTool::run)
                .build();
    }

    static Object run(ToolEnv env, Args a) throws IOException {
        Workspace ws = env.workspace();
        String path = a.requireStr("path");
        boolean recursive = a.bool("recursive", false);
        String full = Deletion.fullPath(ws.root().toString(), path);
        // 先取确认格（一次性）：认了账的这趟不再重新分级 —— 用户看见过风险了
        Deletion.Confirmed ov = Deletion.of(ws).take();
        if (ov != null && ov.origPath().equals(path)) return deleteConfirmed(ws, path, full, ov.target());
        // 没过闸或对不上号（串线当作没过闸）：现场分级，高风险一律拦下
        Deletion.Risk risk = Deletion.risk(ws, path);
        if (risk != null) throw needsGate(path, risk);
        return deleteNormal(path, full, recursive);
    }
}
