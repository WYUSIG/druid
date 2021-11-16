## Druid源码学习系列

druid中的filter可以认为是监控、防火墙、编码、配置的开关，同时也是高拓展性、可插拔的设计

```properties
spring.datasource.druid.filter.stat.enabled=true
spring.datasource.druid.filter.config.enabled=true
spring.datasource.druid.filter.encoding.enabled=true
spring.datasource.druid.filter.wall.enabled=true
```

从druid-spring-boot-starter的单元测试示例配置我们可以看到，druid一共提供