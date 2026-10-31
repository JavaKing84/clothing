package com.yilian.clothing.infrastructure.persistence;

import com.yilian.clothing.common.BizException;
import com.yilian.clothing.common.ResultCode;
import com.yilian.clothing.domain.model.SkuView;
import com.yilian.clothing.domain.model.Spu;
import com.yilian.clothing.domain.model.Supplier;
import com.yilian.clothing.domain.repository.CatalogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 商品目录仓储实现。
 *
 * <p>注意所有 SQL 里的 {@code costCol}：当调用者是店员时，SELECT 列表里根本
 * 没有 purchase_price 这一列（ADR-06 第三层）。这不是"查出来再置空"，
 * 而是"数据库都不往外吐"——即使上层装配器写错，内存里也没有值可泄漏。</p>
 */
@Repository
public class CatalogRepositoryImpl extends JdbcSupport implements CatalogRepository {

    /** 店员视图下用 NULL 顶上，保持 ResultSet 列数一致，RowMapper 不用写两套 */
    private static final String COST_HIDDEN = "NULL AS purchase_price";

    public CatalogRepositoryImpl(JdbcTemplate jdbc) {
        super(jdbc);
    }

    private static final RowMapper<Spu> SPU_MAPPER = (rs, i) -> new Spu(
            rs.getLong("id"),
            rs.getString("spu_no"),
            rs.getString("spu_name"),
            rs.getString("category"),
            rs.getString("season"),
            rs.getLong("supplier_id"),
            rs.getString("supplier_name"),
            rs.getBigDecimal("purchase_price"),
            rs.getBigDecimal("retail_price"),
            rs.getBigDecimal("special_price"),
            rs.getDate("purchase_date") == null ? null : rs.getDate("purchase_date").toLocalDate(),
            rs.getInt("status"),
            rs.getInt("version"),
            rs.getTimestamp("create_time").toLocalDateTime()
    );

    private static final RowMapper<SkuView> SKU_MAPPER = (rs, i) -> new SkuView(
            rs.getLong("id"),
            rs.getLong("spu_id"),
            rs.getString("sku_code"),
            rs.getString("spu_no"),
            rs.getString("spu_name"),
            rs.getString("size"),
            rs.getString("color"),
            rs.getString("category"),
            rs.getString("season"),
            rs.getLong("supplier_id"),
            rs.getBigDecimal("retail_price"),
            rs.getBigDecimal("special_price"),
            rs.getBigDecimal("purchase_price"),
            rs.getInt("status")
    );

    @Override
    public Optional<Spu> findSpuByNo(String spuNo, boolean withCost) {
        String sql = """
                SELECT s.id, s.spu_no, s.spu_name, s.category, s.season, s.supplier_id,
                       sup.supplier_name,
                       """ + (withCost ? "s.purchase_price" : COST_HIDDEN) + """
                       , s.retail_price, s.special_price, s.purchase_date, s.status, s.version, s.create_time
                FROM cat_spu s LEFT JOIN cat_supplier sup ON sup.id = s.supplier_id
                WHERE s.spu_no = ? AND s.del_flag = 0
                """;
        return queryOne(sql, SPU_MAPPER, spuNo);
    }

    @Override
    public List<Spu> listSpus(String keyword, Long supplierId, String season, String category,
                              int limit, int offset, boolean withCost) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.spu_no, s.spu_name, s.category, s.season, s.supplier_id,
                       sup.supplier_name,
                """);
        sql.append(withCost ? "s.purchase_price" : COST_HIDDEN);
        sql.append("""
                , s.retail_price, s.special_price, s.purchase_date, s.status, s.version, s.create_time
                FROM cat_spu s LEFT JOIN cat_supplier sup ON sup.id = s.supplier_id
                WHERE s.del_flag = 0
                """);
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, keyword, supplierId, season, category);
        sql.append(" ORDER BY s.update_time DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return queryList(sql.toString(), SPU_MAPPER, args.toArray());
    }

    @Override
    public long countSpus(String keyword, Long supplierId, String season, String category) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM cat_spu s WHERE s.del_flag = 0");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, keyword, supplierId, season, category);
        return queryLong(sql.toString(), args.toArray());
    }

    private void appendFilters(StringBuilder sql, List<Object> args, String keyword,
                               Long supplierId, String season, String category) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (s.spu_no LIKE ? OR s.spu_name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (supplierId != null) {
            sql.append(" AND s.supplier_id = ?");
            args.add(supplierId);
        }
        if (season != null && !season.isBlank()) {
            sql.append(" AND s.season = ?");
            args.add(season);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND s.category = ?");
            args.add(category);
        }
    }

    @Override
    public Optional<SkuView> findSkuById(long skuId, boolean withCost) {
        String sql = skuSelect(withCost) + " WHERE k.id = ? AND k.del_flag = 0";
        return queryOne(sql, SKU_MAPPER, skuId);
    }

    @Override
    public Optional<SkuView> findSkuByBarcode(String barcode, boolean withCost) {
        String sql = skuSelect(withCost) + """
                 JOIN cat_barcode b ON b.sku_id = k.id AND b.del_flag = 0
                 WHERE b.barcode = ? AND k.del_flag = 0
                """;
        return queryOne(sql, SKU_MAPPER, barcode);
    }

    @Override
    public List<SkuView> listSkusBySpu(long spuId, boolean withCost) {
        return queryList(skuSelect(withCost) + " WHERE k.spu_id = ? AND k.del_flag = 0 ORDER BY k.id",
                SKU_MAPPER, spuId);
    }

    @Override
    public List<SkuView> searchSkusByKeyword(String keyword, int limit, boolean withCost) {
        String like = "%" + keyword.trim() + "%";
        String sql = skuSelect(withCost) + """
                 WHERE k.del_flag = 0 AND (s.spu_no LIKE ? OR s.spu_name LIKE ? OR k.sku_code LIKE ?)
                 ORDER BY s.spu_no, k.size LIMIT ?
                """;
        return queryList(sql, SKU_MAPPER, like, like, like, limit);
    }

    private String skuSelect(boolean withCost) {
        return """
                SELECT k.id, k.spu_id, k.sku_code, s.spu_no, s.spu_name, k.size, k.color,
                       s.category, s.season, s.supplier_id, s.retail_price, s.special_price,
                """ + (withCost ? "s.purchase_price" : COST_HIDDEN) + """
                 , k.status
                FROM cat_sku k JOIN cat_spu s ON s.id = k.spu_id
                """;
    }

    @Override
    public Long insertSpu(Spu spu) {
        try {
            return insert("""
                            INSERT INTO cat_spu (spu_no, spu_name, category, season, supplier_id,
                                                 purchase_price, retail_price, special_price, purchase_date, status)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                            """,
                    spu.spuNo(), spu.spuName(), spu.category(), spu.season(), spu.supplierId(),
                    spu.purchasePrice(), spu.retailPrice(), spu.specialPrice(), spu.purchaseDate());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw BizException.of(ResultCode.SPU_NO_DUPLICATED, "货号 " + spu.spuNo() + " 已存在");
        }
    }

    @Override
    public Long insertSku(long spuId, String skuCode, String size, String color) {
        return insert("INSERT INTO cat_sku (spu_id, sku_code, size, color) VALUES (?, ?, ?, ?)",
                spuId, skuCode, size, color);
    }

    @Override
    public Long insertBarcode(String barcode, long skuId, int source) {
        try {
            return insert("INSERT INTO cat_barcode (barcode, sku_id, source) VALUES (?, ?, ?)",
                    barcode, skuId, source);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return null;   // 码已存在：扫码场景下这是正常现象，不是错误
        }
    }

    @Override
    public Optional<Long> findSkuIdByBarcode(String barcode) {
        return queryOne("SELECT sku_id FROM cat_barcode WHERE barcode = ? AND del_flag = 0",
                (rs, i) -> rs.getLong(1), barcode);
    }

    @Override
    public void updateSpuPrice(long spuId, BigDecimal purchasePrice, BigDecimal retailPrice,
                               BigDecimal specialPrice, int expectedVersion) {
        int rows = update("""
                        UPDATE cat_spu SET purchase_price = ?, retail_price = ?, special_price = ?,
                                           version = version + 1
                        WHERE id = ? AND version = ?
                        """,
                purchasePrice, retailPrice, specialPrice, spuId, expectedVersion);
        if (rows == 0) {
            // 乐观锁失败：两个店主同时改价，后提交的必须重新读一遍再改，
            // 否则会把先提交的价格静默覆盖掉
            throw BizException.of(ResultCode.CONFLICT, "商品资料已被他人修改，请刷新后重试");
        }
    }

    @Override
    public List<Supplier> listSuppliers() {
        return queryList("SELECT id, supplier_name, contact, phone FROM cat_supplier WHERE del_flag = 0 ORDER BY id",
                (rs, i) -> new Supplier(rs.getLong("id"), rs.getString("supplier_name"),
                        rs.getString("contact"), rs.getString("phone")));
    }

    @Override
    public Long insertSupplier(String name, String contact, String phone) {
        return insert("INSERT INTO cat_supplier (supplier_name, contact, phone) VALUES (?, ?, ?)",
                name, contact, phone);
    }

    @Override
    public List<String> distinctSeasons() {
        return queryList("SELECT DISTINCT season FROM cat_spu WHERE del_flag = 0 ORDER BY season",
                (rs, i) -> rs.getString(1));
    }

    @Override
    public List<String> distinctCategories() {
        return queryList("SELECT DISTINCT category FROM cat_spu WHERE del_flag = 0 ORDER BY category",
                (rs, i) -> rs.getString(1));
    }

    /** 未使用但保留：某些测试需要直接读 ResultSet */
    private static java.time.LocalDate toDate(ResultSet rs, String col) throws SQLException {
        java.sql.Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }
}
