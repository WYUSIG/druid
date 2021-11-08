## Druid源码学习

druid是我们常用的数据库连接池之一，

### 1、下载源码

1.1、fork druid

druid github地址：

1.1、克隆源码

```sql
git clone 
```

1.3、编译源码

执行以下命令

```
mvn install -Dmaven.test.skip=true -Dmaven.javadoc.skip=true
```

执行完成便可以愉快地阅读源码啦


### 2、druid-spring-boot-starter

我们使用druid连接池一般都是结合Spring使用，那么当我们配置Spring数据源时，Druid是如何获取数据源信息并且把数据源变成池化连接的呢？

首先我们来分析一下druid-spring-boot-starter模块，看一下AutoConfiguration

```java
public class DruidDataSourceAutoConfigure {

    private static final Logger LOGGER = LoggerFactory.getLogger(DruidDataSourceAutoConfigure.class);

    @Bean(initMethod = "init")
    @ConditionalOnMissingBean
    public DataSource dataSource() {
        LOGGER.info("Init DruidDataSource");
        return new DruidDataSourceWrapper();
    }
}
```

可以看到




参考链接：https://www.cnblogs.com/xingguoblog/p/14136726.html