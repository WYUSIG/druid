## Druid源码学习系列

> druid是我们常用的数据库连接池之一，作为一款经典好用的数据库连接池框架，druid至今依然很火，里面不仅有经典的数据库连接池化实现，还有手写的sql解析、监控等，非常值得学习



### 1、下载源码

1.1、fork druid

druid github地址：https://github.com/alibaba/druid

1.1、克隆源码

```sql
git clone fork出来的地址
```

1.3、编译源码

执行以下命令

```
mvn install -Dmaven.test.skip=true -Dmaven.javadoc.skip=true
```

idea继续maven add druid-admin、druid-spring-boot-starter、druid-wrapper便可以愉快地阅读源码啦



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

可以看到druid在DruidDataSourceAutoConfigure创建了一个DataSourse类型的Bean，当其他框架注入DataSource时将会注入druid创建的DataSource。

我们都知道Spring-Boot默认不配做数据库连接池类型会默认使用Hikaricp，那么它的逻辑到底怎样的呢？

我们来分析一下Spring-Boot自动配置代码：org.springframework.boot:spring-boot-autoconfigure:2.1.13.RELEASE

在DataSourceAutoConfiguration类中，有以下代码

```java
public class DataSourceAutoConfiguration {

	@Configuration
	@Conditional(EmbeddedDatabaseCondition.class)
	@ConditionalOnMissingBean({ DataSource.class, XADataSource.class })
	@Import(EmbeddedDataSourceConfiguration.class)
	protected static class EmbeddedDatabaseConfiguration {

	}

	@Configuration
	@Conditional(PooledDataSourceCondition.class)
	@ConditionalOnMissingBean({ DataSource.class, XADataSource.class })
	@Import({ DataSourceConfiguration.Hikari.class, DataSourceConfiguration.Tomcat.class,
			DataSourceConfiguration.Dbcp2.class, DataSourceConfiguration.Generic.class,
			DataSourceJmxConfiguration.class })
	protected static class PooledDataSourceConfiguration {

	}
    ...
}
```

可以看到@Import导入了多种数据连接池的配置，那么回到最初的问题，Spring-Boot是如何在不配置任何属性情况下能自动获取到Hikaricp作为默认数据库连接池的呢？

原因就在于spring-boot-starter-jdbc默认引入了HikariCP，而org.springframework.boot:spring-boot-autoconfigure里面的HikariCP等连接池都加了<optional>true</optional>，并不会使得我们的应用程序带上依赖

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starters</artifactId>
    <version>2.2.2.RELEASE</version>
  </parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-jdbc</artifactId>
  <version>2.2.2.RELEASE</version>
  <name>Spring Boot JDBC Starter</name>
  ...
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter</artifactId>
      <version>2.2.2.RELEASE</version>
      <scope>compile</scope>
    </dependency>
    <dependency>
      <groupId>com.zaxxer</groupId>
      <artifactId>HikariCP</artifactId>
      <version>3.4.1</version>
      <scope>compile</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework</groupId>
      <artifactId>spring-jdbc</artifactId>
      <version>5.2.2.RELEASE</version>
      <scope>compile</scope>
    </dependency>
  </dependencies>
</project>
```



### 3、DruidDataSourceWrapper

```java
@ConfigurationProperties("spring.datasource.druid")
public class DruidDataSourceWrapper extends DruidDataSource implements InitializingBean {
    
    //注入Spring的数据源属性Bean
    @Autowired
    private DataSourceProperties basicProperties;

    @Override
    public void afterPropertiesSet() throws Exception {
        //如果用户没有配置'spring.datasource.druid', 那么将使用'spring.datasource‘
        if (super.getUsername() == null) {
            super.setUsername(basicProperties.determineUsername());
        }
        if (super.getPassword() == null) {
            super.setPassword(basicProperties.determinePassword());
        }
        if (super.getUrl() == null) {
            super.setUrl(basicProperties.determineUrl());
        }
        if (super.getDriverClassName() == null) {
            super.setDriverClassName(basicProperties.getDriverClassName());
        }
    }

    //配置的druid filter，filter是druid拓展性、监控的关键
    @Autowired(required = false)
    public void autoAddFilters(List<Filter> filters){
        super.filters.addAll(filters);
    }

    /**
     * Ignore the 'maxEvictableIdleTimeMillis &lt; minEvictableIdleTimeMillis' validate,
     * it will be validated again in {@link DruidDataSource#init()}.
     *
     * for fix issue #3084, #2763
     *
     * @since 1.1.14
     */
    @Override
    public void setMaxEvictableIdleTimeMillis(long maxEvictableIdleTimeMillis) {
        try {
            super.setMaxEvictableIdleTimeMillis(maxEvictableIdleTimeMillis);
        } catch (IllegalArgumentException ignore) {
            super.maxEvictableIdleTimeMillis = maxEvictableIdleTimeMillis;
        }
    }
}
```

druid的池化重点就是重写JDBC实现，实现池化技术，下篇将会对com.alibaba.druid.pool.DruidDataSource进行源码分析