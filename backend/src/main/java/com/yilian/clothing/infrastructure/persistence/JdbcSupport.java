package com.yilian.clothing.infrastructure.persistence;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * JDBC 仓储基类。
 *
 * <p>只做三件事：拿自增主键、把 EmptyResult 翻译成 Optional、把可变参数收口。
 * 刻意**不**做"通用 CRUD 基类"——那种基类最后会长成一个残缺的 ORM，
 * 既没有 ORM 的映射能力，又丢掉了手写 SQL 的可见性。</p>
 *
 * <p>为什么用 JdbcTemplate 而不是 JdbcClient：{@code update(PreparedStatementCreator, KeyHolder)}
 * 这个组合在需要自增主键时最直接，JdbcClient 的 API 在这里反而绕。</p>
 */
abstract class JdbcSupport {

    protected final JdbcTemplate jdbc;

    protected JdbcSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入并返回自增主键。 */
    protected long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入未返回主键: " + sql);
        }
        return key.longValue();
    }

    protected int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    protected <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, mapper, args));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    protected <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... args) {
        return jdbc.query(sql, mapper, args);
    }

    protected long queryLong(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0L : v;
    }

    protected int queryInt(String sql, Object... args) {
        Integer v = jdbc.queryForObject(sql, Integer.class, args);
        return v == null ? 0 : v;
    }
}
