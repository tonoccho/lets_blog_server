package com.letsblog.publishing.cms;

import com.letsblog.publishing.cms.ssh.SshCommandExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSH境界({@link SshCommandExecutor})の手書きフェイク(issue #1814)。リモートホストの代わりに、
 * 実行されたコマンドを記録し、コマンドに含まれる文字列(needle)ごとに登録した結果を返す。
 * 登録順に最初に一致した結果を返し、どれにも一致しないコマンドは失敗(終了ステータス1)にする
 * (登録漏れがテストの成功に化けないようにするため)。
 */
public class FakeSshCommandExecutor implements SshCommandExecutor {

    private record Rule(String needle, java.util.ArrayDeque<SshCommandResult> results) {
        SshCommandResult next() {
            return results.size() > 1 ? results.poll() : results.peek();
        }
    }

    private final List<Rule> rules = new ArrayList<>();
    public final List<String> commands = new ArrayList<>();
    public final Map<String, byte[]> stdins = new LinkedHashMap<>();
    public final Map<String, byte[]> uploaded = new LinkedHashMap<>();
    public final List<String> removed = new ArrayList<>();

    public FakeSshCommandExecutor when(String needle, SshCommandResult result) {
        return whenSeq(needle, result);
    }

    /** 一致するたびに先頭から順に結果を返し、最後の結果は以降も返し続ける。 */
    public FakeSshCommandExecutor whenSeq(String needle, SshCommandResult... results) {
        rules.add(new Rule(needle, new java.util.ArrayDeque<>(List.of(results))));
        return this;
    }

    public FakeSshCommandExecutor whenOk(String needle, String stdout) {
        return when(needle, ok(stdout));
    }

    public FakeSshCommandExecutor whenFail(String needle, String stderr) {
        return when(needle, fail(stderr));
    }

    public static SshCommandResult ok(String stdout) {
        return new SshCommandResult(0, stdout, "", "SHA256:observed");
    }

    public static SshCommandResult fail(String stderr) {
        return new SshCommandResult(1, "", stderr, null);
    }

    public boolean ran(String needle) {
        return commands.stream().anyMatch(c -> c.contains(needle));
    }

    public int indexOf(String needle) {
        for (int i = 0; i < commands.size(); i++) {
            if (commands.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public SshCommandResult exec(SshConnectionParams params, String command, byte[] stdin) {
        commands.add(command);
        if (stdin != null) {
            stdins.put(command, stdin);
        }
        return resolve(command);
    }

    @Override
    public List<SshCommandResult> execAll(SshConnectionParams params, List<String> commands) {
        List<SshCommandResult> results = new ArrayList<>();
        for (String command : commands) {
            this.commands.add(command);
            results.add(resolve(command));
        }
        return results;
    }

    @Override
    public void putFile(SshConnectionParams params, byte[] data, String remotePath) {
        uploaded.put(remotePath, data);
    }

    @Override
    public byte[] getFile(SshConnectionParams params, String remotePath) {
        return uploaded.get(remotePath);
    }

    @Override
    public void removeFile(SshConnectionParams params, String remotePath) {
        removed.add(remotePath);
    }

    private SshCommandResult resolve(String command) {
        for (Rule rule : rules) {
            if (command.contains(rule.needle())) {
                return rule.next();
            }
        }
        return fail("FakeSshCommandExecutor: 結果が登録されていないコマンドです: " + command);
    }
}
