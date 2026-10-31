package com.yilian.clothing.domain.repository;

import com.yilian.clothing.domain.model.Spu;
import com.yilian.clothing.domain.model.SkuView;
import com.yilian.clothing.domain.model.Supplier;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 商品目录仓储端口（端口 = 领域层定义的接口，实现放 infrastructure）。
 *
 * <p>所有查询都带 {@code withCost} 参数，这是 Q68 的**第三层防御**：
 * 不传 true，SQL 里就没有 purchase_price 这一列，内存里根本不存在这个值。
 * 比起"查出来再删掉"，"根本不查"才是安全边界。</p>
 */
public interface CatalogRepository {

    Optional<Spu> findSpuByNo(String spuNo, boolean withCost);

    List<Spu> listSpus(String keyword, Long supplierId, String season, String category,
                       int limit, int offset, boolean withCost);

    long countSpus(String keyword, Long supplierId, String season, String category);

    Optional<SkuView> findSkuById(long skuId, boolean withCost);

    Optional<SkuView> findSkuByBarcode(String barcode, boolean withCost);

    List<SkuView> listSkusBySpu(long spuId, boolean withCost);

    List<SkuView> searchSkusByKeyword(String keyword, int limit, boolean withCost);

    Long insertSpu(Spu spu);

    Long insertSku(long spuId, String skuCode, String size, String color);

    Long insertBarcode(String barcode, long skuId, int source);

    Optional<Long> findSkuIdByBarcode(String barcode);

    void updateSpuPrice(long spuId, BigDecimal purchasePrice, BigDecimal retailPrice, BigDecimal specialPrice,
                        int expectedVersion);

    List<Supplier> listSuppliers();

    Long insertSupplier(String name, String contact, String phone);

    List<String> distinctSeasons();

    List<String> distinctCategories();
}
