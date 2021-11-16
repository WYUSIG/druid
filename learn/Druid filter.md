## Druid源码学习系列

druid中的filter可以认为是监控、防火墙、编码、配置的开关，同时也是高拓展性、可插拔的设计

```properties
spring.datasource.druid.filter.stat.enabled=true
spring.datasource.druid.filter.config.enabled=true
spring.datasource.druid.filter.encoding.enabled=true
spring.datasource.druid.filter.wall.enabled=true
```

从druid-spring-boot-starter的单元测试示例配置我们可以看到四种filter，其实除了这四种，druid还提供了以下filter，分别是：

| Filter                | 功能                           |
| --------------------- | ------------------------------ |
| StatFilter            | 监控                           |
| ConfigFilter          | druid配置方式之一              |
| EncodingConvertFilter | 字符编码转化                   |
| Slf4jLogFilter        | slf4j日志打印                  |
| Log4jFilter           | log4j日志打印                  |
| Log4j2Filter          | log4j2日志打印                 |
| CommonsLogFilter      | apache.commons.logging日志打印 |
| WallFilter            | 防火墙、防sql注入              |

我们来看一下Filter接口

```java
public interface Filter extends Wrapper {

    /**
     * DruidDataSource初始化时行
     * @param dataSource DruidDataSource
     */
    void init(DataSourceProxy dataSource);

    /**
     * DruidDataSource#close时执行
     */
    void destroy();

    /**
     * DruidDataSource#setConnectProperties执行
     * @param properties 配置属性
     */
    void configFromProperties(Properties properties);

    boolean isWrapperFor(java.lang.Class<?> iface);

    <T> T unwrap(java.lang.Class<T> iface);

    /**
     * 连接创建时回调
     */
    ConnectionProxy connection_connect(FilterChain chain, Properties info) throws SQLException;

    /**
     * Connection#createStatement时回调
     */
    StatementProxy connection_createStatement(FilterChain chain, ConnectionProxy connection) throws SQLException;

    /**
     * Connection#prepareStatement时回调
     */
    PreparedStatementProxy connection_prepareStatement(FilterChain chain, ConnectionProxy connection, String sql)
    
    ...
        
}
```

几乎JDBC的每一个重要动作都有回调，而利用这些回调做什么，就是不同Filter做的事情



我们来看一下StatFilter利用Filter责任链回调都做了些什么



```java
public class StatFilter extends FilterEventAdapter implements StatFilterMBean {

    private final static Log          LOG                         = LogFactory.getLog(StatFilter.class);

    private static final String       SYS_PROP_LOG_SLOW_SQL       = "druid.stat.logSlowSql";
    private static final String       SYS_PROP_SLOW_SQL_MILLIS    = "druid.stat.slowSqlMillis";
    private static final String       SYS_PROP_SLOW_SQL_LOG_LEVEL = "druid.stat.slowSqlLogLevel";
    private static final String       SYS_PROP_MERGE_SQL          = "druid.stat.mergeSql";

    public final static String        ATTR_NAME_CONNECTION_STAT   = "stat.conn";
    public final static String        ATTR_TRANSACTION            = "stat.tx";

    private final Lock                lock                        = new ReentrantLock();

    // protected JdbcDataSourceStat dataSourceStat;

    @Deprecated
    protected final JdbcStatementStat statementStat               = JdbcStatManager.getInstance().getStatementStat();

    @Deprecated
    protected final JdbcResultSetStat resultSetStat               = JdbcStatManager.getInstance().getResultSetStat();

    private boolean                   connectionStackTraceEnable  = false;

    // 3 seconds is slow sql
    protected long                    slowSqlMillis               = 3 * 1000;

    protected boolean                 logSlowSql                  = false;

    protected String                  slowSqlLogLevel             = "ERROR";

    private DbType                    dbType;

    private boolean                   mergeSql                    = false;

    public StatFilter(){
    }
    
    ...

    /**
     * 初始化，在DruidDataSource#init时执行
     * @param dataSource druid data source
     */
    @Override
    public void init(DataSourceProxy dataSource) {
        lock.lock();
        try {
            if (this.dbType == null) {
                this.dbType = DbType.of(dataSource.getDbType());
            }
            //刷新StatFilter配置
            configFromProperties(dataSource.getConnectProperties());
            configFromProperties(System.getProperties());
        } finally {
            lock.unlock();
        }
    }

    public void configFromProperties(Properties properties) {
        if (properties == null) {
            return;
        }

        {
            //druid.stat.mergeSql
            String property = properties.getProperty(SYS_PROP_MERGE_SQL);
            if ("true".equals(property)) {
                this.mergeSql = true;
            } else if ("false".equals(property)) {
                this.mergeSql = false;
            }
        }

        {
            //druid.stat.slowSqlMillis，慢sql阈值
            String property = properties.getProperty(SYS_PROP_SLOW_SQL_MILLIS);
            if (property != null && property.trim().length() > 0) {
                property = property.trim();
                try {
                    this.slowSqlMillis = Long.parseLong(property);
                } catch (Exception e) {
                    LOG.error("property 'druid.stat.slowSqlMillis' format error");
                }
            }
        }

        {
            //druid.stat.logSlowSql，是否打印慢sql
            String property = properties.getProperty(SYS_PROP_LOG_SLOW_SQL);
            if ("true".equals(property)) {
                this.logSlowSql = true;
            } else if ("false".equals(property)) {
                this.logSlowSql = false;
            }
        }

        {
            //druid.stat.slowSqlLogLevel，慢sql日志打印级别
            String property = properties.getProperty(SYS_PROP_SLOW_SQL_LOG_LEVEL);
            if ("error".equalsIgnoreCase(property)) {
                this.slowSqlLogLevel = "ERROR";
            } else if ("warn".equalsIgnoreCase(property)) {
                this.slowSqlLogLevel = "WARN";
            } else if ("info".equalsIgnoreCase(property)) {
                this.slowSqlLogLevel = "INFO";
            } else if ("debug".equalsIgnoreCase(property)) {
                this.slowSqlLogLevel = "DEBUG";
            }
        }
    }

    /**
     * 创建物理连接时，记录连接性能数据
     */
    public ConnectionProxy connection_connect(FilterChain chain, Properties info) throws SQLException {
        ConnectionProxy connection = null;

        long startNano = System.nanoTime();
        long startTime = System.currentTimeMillis();

        long nanoSpan;
        long nowTime = System.currentTimeMillis();

        JdbcDataSourceStat dataSourceStat = chain.getDataSource().getDataSourceStat();
        dataSourceStat.getConnectionStat().beforeConnect();
        try {
            connection = chain.connection_connect(info);
            nanoSpan = System.nanoTime() - startNano;
        } catch (SQLException ex) {
            dataSourceStat.getConnectionStat().connectError(ex);
            throw ex;
        }
        dataSourceStat.getConnectionStat().afterConnected(nanoSpan);

        if (connection != null) {
            JdbcConnectionStat.Entry statEntry = getConnectionInfo(connection);

            dataSourceStat.getConnections().put(connection.getId(), statEntry);

            //连接时间
            statEntry.setConnectTime(new Date(startTime));
            //连接花费时间
            statEntry.setConnectTimespanNano(nanoSpan);
            statEntry.setEstablishNano(System.nanoTime());
            statEntry.setEstablishTime(nowTime);
            statEntry.setConnectStackTrace(new Exception());

            dataSourceStat.getConnectionStat().setActiveCount(dataSourceStat.getConnections().size());
        }

        return connection;
    }

    /**
     * 连接关闭时记录性能数据
     */
    @Override
    public void connection_close(FilterChain chain, ConnectionProxy connection) throws SQLException {
        if (connection.getCloseCount() == 0) {
            long nowNano = System.nanoTime();

            JdbcDataSourceStat dataSourceStat = chain.getDataSource().getDataSourceStat();
            //连接关闭数量
            dataSourceStat.getConnectionStat().incrementConnectionCloseCount();

            JdbcConnectionStat.Entry connectionInfo = getConnectionInfo(connection);

            //连接存活时间
            long aliveNanoSpan = nowNano - connectionInfo.getEstablishNano();

            JdbcConnectionStat.Entry existsConnection = dataSourceStat.getConnections().remove(connection.getId());
            if (existsConnection != null) {
                dataSourceStat.getConnectionStat().afterClose(aliveNanoSpan);
            }
        }

        chain.connection_close(connection);
        // duplicate close, C3P0等连接池，在某些情况下会关闭连接多次。
    }

    /**
     * 事务commit时记录数量
     */
    @Override
    public void connection_commit(FilterChain chain, ConnectionProxy connection) throws SQLException {
        chain.connection_commit(connection);

        JdbcDataSourceStat dataSourceStat = chain.getDataSource().getDataSourceStat();
        dataSourceStat.getConnectionStat().incrementConnectionCommitCount();
    }

    /**
     * 事务rollback时记录数量
     */
    @Override
    public void connection_rollback(FilterChain chain, ConnectionProxy connection) throws SQLException {
        chain.connection_rollback(connection);

        JdbcDataSourceStat dataSourceStat = chain.getDataSource().getDataSourceStat();
        dataSourceStat.getConnectionStat().incrementConnectionRollbackCount();
    }

    /**
     * 安全点事务rollback时记录数量
     */
    @Override
    public void connection_rollback(FilterChain chain, ConnectionProxy connection, Savepoint savepoint)
                                                                                                       throws SQLException {
        chain.connection_rollback(connection, savepoint);

        JdbcDataSourceStat dataSourceStat = connection.getDirectDataSource().getDataSourceStat();
        dataSourceStat.getConnectionStat().incrementConnectionRollbackCount();
    }

    /**
     * 创建Statement后记录Statement创建数
     */
    @Override
    public void statementCreateAfter(StatementProxy statement) {
        JdbcDataSourceStat dataSourceStat = statement.getConnectionProxy().getDirectDataSource().getDataSourceStat();
        dataSourceStat.getStatementStat().incrementCreateCounter();

        super.statementCreateAfter(statement);
    }

    /**
     * 执行存储过程后，记录执行存储过程数量
     */
    @Override
    public void statementPrepareCallAfter(CallableStatementProxy statement) {
        JdbcDataSourceStat dataSourceStat = statement.getConnectionProxy().getDirectDataSource().getDataSourceStat();
        dataSourceStat.getStatementStat().incrementPrepareCallCount();

        JdbcSqlStat sqlStat = createSqlStat(statement, statement.getSql());
        statement.setSqlStat(sqlStat);
    }

    /**
     * 创建PrepareStatement后，记录创建PrepareStatement数量
     */
    @Override
    public void statementPrepareAfter(PreparedStatementProxy statement) {
        JdbcDataSourceStat dataSourceStat = statement.getConnectionProxy().getDirectDataSource().getDataSourceStat();
        dataSourceStat.getStatementStat().incrementPrepareCounter();
        JdbcSqlStat sqlStat = createSqlStat(statement, statement.getSql());
        statement.setSqlStat(sqlStat);
    }

    /**
     * 记录Statement关闭数量
     */
    @Override
    public void statement_close(FilterChain chain, StatementProxy statement) throws SQLException {
        chain.statement_close(statement);

        JdbcDataSourceStat dataSourceStat = chain.getDataSource().getDataSourceStat();
        dataSourceStat.getStatementStat().incrementStatementCloseCounter();
        JdbcStatContext context = JdbcStatManager.getInstance().getStatContext();
        if (context != null) {
            context.setName(null);
            context.setFile(null);
            context.setSql(null);
        }
    }
    
    ....
        
}
```

StatFilter的主要工作就是在回调中记录监控数据，并保存到Druid重写jdbc接口的哪些实现类对象中

