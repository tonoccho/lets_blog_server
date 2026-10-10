package com.letsblog.common.db;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 全{@link DataSource} Beanを包み、JDBCの実行を{@link DbQueryRecorder}へ記録する(issue #1736)。
 * Hibernate・Spring Data・JdbcTemplate・Flywayのどれが実行しても同じ経路で数えられ、新しい依存も要らない
 * (JDK動的プロキシのみ)。{@code unwrap}/{@code isWrapperFor}は元のDataSourceへ委譲するので、
 * Hikariのメトリクス等が元の型を取り出せる。{@link DbQueryRecorder#begin}されていないスレッドでは
 * 記録が捨てられるだけで、挙動は変わらない。
 *
 * <p>SQLテンプレート: PreparedStatement/CallableStatementは作成時のSQL(バインド前)。素の{@link Statement}は
 * 実行時の文字列に値が埋まっているので、文字列リテラルと数値を{@code ?}に伏せてから使う。
 * どちらも空白は1つに畳み、同じ文を同じテンプレートとして数える。バッチは1回の実行と数える。
 */
public class DbQueryMetricsDataSourcePostProcessor implements BeanPostProcessor {

    private static final Set<String> EXECUTE_METHODS = Set.of(
            "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch");
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String BATCH_TEMPLATE = "(statement batch)";

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource) {
            // 対象の全インターフェースを実装する。DataSourceだけだとAutoCloseable(HikariDataSource等)が失われ、
            // 推論されるdestroyメソッド(close)でプールを閉じられなくなる。
            return proxy(ClassUtils.getAllInterfaces(dataSource), dataSource, (method, args, target) -> {
                Object result = invoke(method, args, target);
                return result instanceof Connection connection ? wrapConnection(connection) : result;
            });
        }
        return bean;
    }

    /** プロキシ呼び出しを、結果の後処理つきで委譲するための小さな関数型。 */
    @FunctionalInterface
    private interface Handler<T> {
        Object handle(Method method, Object[] args, T target) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, T target, Handler<T> handler) {
        return type.cast(proxy(new Class<?>[] {type}, target, handler));
    }

    private static <T> Object proxy(Class<?>[] interfaces, T target, Handler<T> handler) {
        InvocationHandler invocationHandler = (proxy, method, args) -> handler.handle(method, args, target);
        return Proxy.newProxyInstance(
                DbQueryMetricsDataSourcePostProcessor.class.getClassLoader(), interfaces, invocationHandler);
    }

    private static Object invoke(Method method, Object[] args, Object target) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Connection wrapConnection(Connection connection) {
        return proxy(Connection.class, connection, (method, args, target) -> {
            Object result = invoke(method, args, target);
            return switch (method.getName()) {
                case "prepareCall" -> wrapStatement(
                        CallableStatement.class, (CallableStatement) result, template((String) args[0], false));
                case "prepareStatement" -> wrapStatement(
                        PreparedStatement.class, (PreparedStatement) result, template((String) args[0], false));
                case "createStatement" -> wrapStatement(Statement.class, (Statement) result, null);
                default -> result;
            };
        });
    }

    private static <S extends Statement> S wrapStatement(Class<S> type, S statement, String preparedTemplate) {
        return proxy(type, statement, (method, args, target) -> {
            if (!EXECUTE_METHODS.contains(method.getName())) {
                return invoke(method, args, target);
            }
            String sql = preparedTemplate != null ? preparedTemplate : plainStatementTemplate(args);
            long start = System.nanoTime();
            try {
                return invoke(method, args, target);
            } finally {
                DbQueryRecorder.record(sql, System.nanoTime() - start);
            }
        });
    }

    private static String plainStatementTemplate(Object[] args) {
        if (args != null && args[0] instanceof String sql) {
            return template(sql, true);
        }
        return BATCH_TEMPLATE;
    }

    private static String template(String sql, boolean maskLiterals) {
        String result = sql;
        if (maskLiterals) {
            result = NUMBER.matcher(STRING_LITERAL.matcher(result).replaceAll("?")).replaceAll("?");
        }
        return WHITESPACE.matcher(result).replaceAll(" ").trim();
    }
}
