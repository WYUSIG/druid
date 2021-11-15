## Druid源码学习系列

因为Druid自带了sql解析，因此非常适合在连接池里加上sql审计、防止Sql注入等安全特性

今天我们就来学习一下druid的防sql注入该如何使用，开始之前，我们先来看一个源码里面的单元测试

```java
public class WallInsertTest extends TestCase {
    //insert语句
    private String sql = "INSERT INTO T (F1, F2) VALUES (1, 2)";

    //new一个防火墙配置
    private WallConfig config = new WallConfig();

    //单元测试前置方法
    protected void setUp() throws Exception {
        //把防火墙配置的是否允许执行insert语句改成不允许
        config.setInsertAllow(false);
    }

    public void testMySql() throws Exception {
        //测试，期望结果为false，语句不合法
        Assert.assertFalse(WallUtils.isValidateMySql(sql, config));
    }
}
```

从上面的单元测试可以看到，检查sql是否符合我们配置的防注入规则主要是com.alibaba.druid.wall.WallUtils#isValidateMySql

支持传入WallConfig参数，如果不传WallConfig，则默认使用druid内置的规则，我们来简单看一下druid的防sql注入都有哪些配置

```java
public class WallConfig implements WallConfigMBean {

    private boolean noneBaseStatementAllow = false;

    //是否允许执行存储过程，默认允许
    private boolean callAllow = true;
    //是否允许执行select语句，默认允许
    private boolean selelctAllow = true;
    //是否允许select语句中包含into，默认允许
    private boolean selectIntoAllow = true;
    private boolean selectIntoOutfileAllow = false;
    //是否允许select语句的where子句为永真，默认允许
    private boolean selectWhereAlwayTrueCheck = true;
    //是否允许select语句的having子句为永真，默认允许
    private boolean selectHavingAlwayTrueCheck = true;
    private boolean selectUnionCheck = true;
    private boolean selectMinusCheck = true;
    private boolean selectExceptCheck = true;
    private boolean selectIntersectCheck = true;
    //是否允许执行create table语句，默认允许
    private boolean createTableAllow = true;
    //是否允许执行drop table语句，默认允许
    private boolean dropTableAllow = true;
    //是否允许执行alter table语句，默认允许
    private boolean alterTableAllow = true;
    //是否允许执行rename table语句，默认允许
    private boolean renameTableAllow = true;
    private boolean hintAllow = true;
    //是否允许lock table，默认允许
    private boolean lockTableAllow = true;
    //是否允许begin开启新事务，默认允许
    private boolean startTransactionAllow = true;
    private boolean blockAllow = true;
    //是否允许where，having子句中是否包含and永真条件，默认不允许
    private boolean conditionAndAlwayTrueAllow = false;
    //是否允许where，having子句中是否包含and永假条件，默认不允许
    private boolean conditionAndAlwayFalseAllow = false;
    private boolean conditionDoubleConstAllow = false;
    //是否允许where，having子句中是否包含like永真条件, 默认允许
    private boolean conditionLikeTrueAllow = true;
    //是否允许select *查询所有列，默认允许
    private boolean selectAllColumnAllow = true;
    //是否允许执行delete语句
    private boolean deleteAllow = true;
    //是否进行delete语句的where含有永真条件检查，默认进行检查，不允许出现
    private boolean deleteWhereAlwayTrueCheck = true;
    //是否进行delete语句没有where检查，默认不检查
    private boolean deleteWhereNoneCheck = false;
    //是否允许执行update语句
    private boolean updateAllow = true;
    //是否进行update语句中的where含有永真条件检查，默认进行检查，不允许出现
    private boolean updateWhereAlayTrueCheck = true;
    //是否进行update没有where检查，默认不检查
    private boolean updateWhereNoneCheck = false;
    //是否允许执行insert语句，默认允许
    private boolean insertAllow = true;
    private boolean mergeAllow = true;
    private boolean minusAllow = true;
    private boolean intersectAllow = true;
    //是否允许执行replace语句，默认允许
    private boolean replaceAllow = true;
    //是否允许执行set语句，如set global xxx = , 默认允许
    private boolean setAllow = true;
    //是否允许执行commit语句，默认允许
    private boolean commitAllow = true;
    //是否允许执行rollback,默认允许
    private boolean rollbackAllow = true;
    //是否允许执行use语句，默认允许
    private boolean useAllow = true;

    private boolean multiStatementAllow = false;

    //是否允许执行truncate语句，默认允许
    private boolean truncateAllow = true;
    //是否允许执行comment语句，默认不允许
    private boolean commentAllow = false;
    private boolean strictSyntaxCheck = true;
    private boolean constArithmeticAllow = true;
    private boolean limitZeroAllow = false;

    private boolean describeAllow = true;
    //是否允许执行show语句，默认允许
    private boolean showAllow = true;

    public WallConfig(String dir){
        this();
        this.dir = dir;
        this.init();
    }
}
```

其中属性包含了绝大部分sql场景，可以对这些属性进行配置，除了属性，我们还可以看到WallConfig有一个传入目录的构造方法，传进来的这个目录就是专门为各类数据库额额外定制的安全规则

比如mysql，在druid src下的 META-INF/druid/wall/mysql，存放了mysql的一些规则，比如不能建立mysql另有用途的数据库名

deny-schema.txt：

```
information_schema
mysql
performance_schema
```

了解了，我们来看一下如何让在实际中自定义规则

搜先我们先演示纯java api方式

```java
public class DruidTest {
    public static void initDS(String connectURI, String username, String pswd, String driverClass, int initialSize,
                              int maxActive, int minIdle, int maxWait) throws SQLException {
        DruidDataSource ds = new DruidDataSource();
        //驱动类名
        ds.setDriverClassName(driverClass);
        //数据库用户名
        ds.setUsername(username);
        //数据库密码
        ds.setPassword(pswd);
        //数据库jdbc链接
        ds.setUrl(connectURI);
        //初始的连接数
        ds.setInitialSize(initialSize);
        //最大连接数
        ds.setMaxActive(maxActive);
        //最小连接数
        ds.setMinIdle(minIdle);
        //最大等待线程数
        ds.setMaxWait(maxWait);
        //设置filter，这里设置了防火墙的，多个filter英文都好隔开
        ds.setFilters("wall");
        //获取防火墙filter
        WallFilter wallFilter = (WallFilter) ds.getProxyFilters().get(0);
        //自定义sql防注入规则
        wallFilter.getConfig().setInsertAllow(false);
        DS = ds;
    }
}
```

拿到DruidDataSource，直接getConnection即可获得池化连接

接下来我们看一下在spring-boot + druid下，是如何配置自定义防注入规则

首先我们需要开启wall filter

```properties
spring.datasource.druid.filter.wall.enabled=true
```
然后我们需要

```java
@Bean
public WallConfig wallConfig() {
    WallConfig wallConfig = new WallConfig();
    wallConfig.setInsertAllow(false);
    return wallConfig;
}
```

因为在DruidFilterConfiguration源码中，WallConfig Bean的创建加了@ConditionalOnMissingBean注解

```java
public class DruidFilterConfiguration {
    @Bean
    @ConfigurationProperties(FILTER_WALL_CONFIG_PREFIX)
    @ConditionalOnProperty(prefix = FILTER_WALL_PREFIX, name = "enabled")
    @ConditionalOnMissingBean
    public WallConfig wallConfig() {
        return new WallConfig();
    }
}
```