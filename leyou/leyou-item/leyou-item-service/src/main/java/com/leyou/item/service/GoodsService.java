package com.leyou.item.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.leyou.common.pojo.PageResult;
import com.leyou.item.bo.Spubo;
import com.leyou.item.mapper.*;
import com.leyou.item.pojo.*;
import org.apache.commons.lang.StringUtils;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tk.mybatis.mapper.entity.Example;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class GoodsService {
    @Autowired
    private SpuMapper spuMapper;

    @Autowired
    private BrandMapper brandMapper;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private SpuDetailMapper spuDetailMapper;

    @Autowired
    private SkuMapper skuMapper;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private AmqpTemplate amqpTemplate;

    /**
     * 根据条件分页查询spu
     * @param key
     * @param saleable
     * @param page
     * @param rows
     * @return
     */
    public PageResult<Spubo> querySpuByPage(String key, Boolean saleable, Integer page, Integer rows) {
        Example example = new Example(Spu.class);
        Example.Criteria criteria = example.createCriteria();

        //添加查询条件
        if(StringUtils.isNotBlank(key)){
            criteria.andLike("title","%"+key+"%");
        }

        //添加上下架的过滤条件
        if(saleable != null){
            criteria.andEqualTo("saleable",saleable);
        }

        //添加分页
        PageHelper.startPage(page,rows);

        //执行查询，获取spu集合
        List<Spu> spus=this.spuMapper.selectByExample(example);
        PageInfo<Spu> pageInfo = new PageInfo<>(spus);

        //spu集合转化成spubo集合
        List<Spubo> spuBos = spus.stream().map(spu -> {
            Spubo spubo = new Spubo();
            BeanUtils.copyProperties(spu, spubo);
            //查询品牌名称
            Brand brand = this.brandMapper.selectByPrimaryKey(spu.getBrandId());
            spubo.setCname(brand.getName());
            //查询分类名称
            List<String> names = this.categoryService.queryNameByIds(Arrays.asList(spu.getCid1(), spu.getCid2(), spu.getCid3()));
            spubo.setBname(StringUtils.join(names, "-"));
            return spubo;
        }).collect(Collectors.toList());

        //返回pageResult<spubo>
        return new PageResult<>(pageInfo.getTotal(),spuBos);
    }

    /**
     * 新增商品
     * @param spubo
     */
    @Transactional
    public void saveGoods(Spubo spubo) {
        //1、新增spu
        spubo.setId(null);
        spubo.setSaleable(true);
        spubo.setValid(true);
        spubo.setCreateTime(new Date());
        spubo.setLastUpdateTime(spubo.getCreateTime());
        this.spuMapper.insertSelective(spubo);

        //2、spuDetail
        SpuDetail spuDetail = spubo.getSpuDetail();
        spuDetail.setSpuId(spubo.getId());
        this.spuDetailMapper.insertSelective(spuDetail);

        saveSkuAndStock(spubo);

        sendMsg("insert",spubo.getId());
    }

    private void sendMsg(String type,Long id) {
        try {
            this.amqpTemplate.convertAndSend("item."+type,id);
        } catch (AmqpException e) {
            e.printStackTrace();
        }
    }

    private void saveSkuAndStock(Spubo spubo) {
        spubo.getSkus().forEach(sku -> {
            //3、sku
            sku.setId(null);
            sku.setSpuId(spubo.getId());
            sku.setCreateTime(new Date());
            sku.setLastUpdateTime(sku.getCreateTime());
            this.skuMapper.insertSelective(sku);

            //4、stock
            Stock stock = new Stock();
            stock.setSkuId(sku.getId());
            stock.setStock(sku.getStock());
            this.stockMapper.insertSelective(stock);
        });
    }

    /**
     * 根据spuId查询spuDetail
     * @param spuId
     * @return
     */
    public SpuDetail querySpuDetailBySpuId(Long spuId) {
        return this.spuDetailMapper.selectByPrimaryKey(spuId);
    }

    /**
     * 根据spuId查询sku集合
     * @param spuId
     * @return
     */
    public List<Sku> querySkusBySpuId(Long spuId) {
        Sku record = new Sku();
        record.setSpuId(spuId);
        List<Sku> skus = this.skuMapper.select(record);
        skus.forEach(sku -> {
            Stock stock = this.stockMapper.selectByPrimaryKey(sku.getId());
            sku.setStock(stock.getStock());
        });
        return skus;
    }

    /**
     * 更新商品信息
     * @param spubo
     * @return
     */
    @Transactional
    public void updateGoods(Spubo spubo) {
        //根据spuId查询要删除的sku
        Sku record = new Sku();
        record.setSpuId(spubo.getId());
        List<Sku> skus = this.skuMapper.select(record);
        skus.forEach(sku -> {
            //删除stock
            this.stockMapper.deleteByPrimaryKey(sku.getId());
        });

        //删除sku
        Sku sku = new Sku();
        sku.setSpuId(spubo.getId());
        this.skuMapper.delete(sku);

        //新增stock和spu
        this.saveSkuAndStock(spubo);

        //更新spu和spuDetail
        spubo.setCreateTime(null);
        spubo.setLastUpdateTime(new Date());
        spubo.setValid(null);
        spubo.setSaleable(null);
        this.spuMapper.updateByPrimaryKeySelective(spubo);

        this.spuDetailMapper.updateByPrimaryKeySelective(spubo.getSpuDetail());

        sendMsg("update",spubo.getId());
    }

    public Spu querySpuById(Long id) {
        return this.spuMapper.selectByPrimaryKey(id);
    }
}



















