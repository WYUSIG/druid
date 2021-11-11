## Druid源码学习系列

Druid对整个JDBC接口都进行了重写，一方面是为了满足监控的需求，而另一方面则是为了池化与缓存。今天我们来看一下Druid对Statement的实现

### 1、DruidPooledStatement

Druid实现Statement的基础类，代理模式，里面的主要方法是通过代理Statement来实现的，除了查询、执行sql等功能外，还实现了异常栈信息、异常数记录

```java
public class DruidPooledStatement extends PoolableWrapper implements Statement {

    private final static Log        LOG            = LogFactory.getLog(DruidPooledStatement.class);

    private final Statement         stmt;
    protected DruidPooledConnection conn;
    protected List<ResultSet>       resultSetTrace;
    protected boolean               closed         = false;
    protected int                   fetchRowPeak   = -1;
    protected int                   exceptionCount = 0;

    public DruidPooledStatement(DruidPooledConnection conn, Statement stmt){
        super(stmt);

        this.conn = conn;
        this.stmt = stmt;
    }

    /**
     * 把结果集添加到可追踪列表
     */
    protected void addResultSetTrace(ResultSet resultSet) {
        if (resultSetTrace == null) {
            resultSetTrace = new ArrayList<ResultSet>(1);
        } else if (resultSetTrace.size() > 0) {
            int lastIndex = resultSetTrace.size() - 1;
            ResultSet lastResultSet = resultSetTrace.get(lastIndex);
            try {
                if (lastResultSet.isClosed()) {
                    resultSetTrace.set(lastIndex, resultSet);
                    return;
                }
            } catch (SQLException ex) {
                // skip
            }
        }

        resultSetTrace.add(resultSet);
    }

    protected void recordFetchRowCount(int fetchRowCount) {
        if (fetchRowPeak < fetchRowCount) {
            fetchRowPeak = fetchRowCount;
        }
    }

    public int getFetchRowPeak() {
        return fetchRowPeak;
    }

    /**
     * 检查异常类型
     */
    protected SQLException checkException(Throwable error) throws SQLException {
        String sql = null;
        if (this instanceof DruidPooledPreparedStatement) {
            sql = ((DruidPooledPreparedStatement) this).getSql();
        }

        handleSocketTimeout(error);

        exceptionCount++;
        return conn.handleException(error, sql);
    }
    
    public DruidPooledConnection getPoolableConnection() {
        return conn;
    }

    public Statement getStatement() {
        return stmt;
    }

    protected void checkOpen() throws SQLException {
        if (closed) {
            Throwable disableError = null;
            if (this.conn != null) {
                disableError = this.conn.getDisableError();
            }

            if (disableError != null) {
                throw new SQLException("statement is closed", disableError);
            } else {
                throw new SQLException("statement is closed");
            }
        }
    }

    protected void clearResultSet() {
        if (resultSetTrace == null) {
            return;
        }

        for (ResultSet rs : resultSetTrace) {
            try {
                if (!rs.isClosed()) {
                    rs.close();
                }
            } catch (SQLException ex) {
                LOG.error("clearResultSet error", ex);
            }
        }
        resultSetTrace.clear();
    }

    /**
     * 记录执行数
     */
    public void incrementExecuteCount() {
        final DruidPooledConnection conn = this.getPoolableConnection();
        if (conn == null) {
            return;
        }

        final DruidConnectionHolder holder = conn.getConnectionHolder();
        if (holder == null) {
            return;
        }

        final DruidAbstractDataSource dataSource = holder.getDataSource();
        if (dataSource == null) {
            return;
        }

        dataSource.incrementExecuteCount();
    }

    /**
     * 记录批处理数
     */
    public void incrementExecuteBatchCount() {
        final DruidPooledConnection conn = this.getPoolableConnection();
        if (conn == null) {
            return;
        }

        final DruidConnectionHolder holder = conn.getConnectionHolder();
        if (holder == null) {
            return;
        }

        if (holder.getDataSource() == null) {
            return;
        }

        final DruidAbstractDataSource dataSource = holder.getDataSource();
        if (dataSource == null) {
            return;
        }

        dataSource.incrementExecuteBatchCount();
    }

    /**
     * 记录查询数
     */
    public void incrementExecuteQueryCount() {
        final DruidPooledConnection conn = this.getPoolableConnection();
        if (conn == null) {
            return;
        }

        final DruidConnectionHolder holder = conn.getConnectionHolder();
        if (holder == null) {
            return;
        }

        final DruidAbstractDataSource dataSource = holder.getDataSource();
        if (dataSource == null) {
            return;
        }

        dataSource.incrementExecuteQueryCount();
    }

    /**
     * 记录事务sql
     */
    protected void transactionRecord(String sql) throws SQLException {
        conn.transactionRecord(sql);
    }

    @Override
    public final ResultSet executeQuery(String sql) throws SQLException {
        checkOpen();

        incrementExecuteQueryCount();
        transactionRecord(sql);

        conn.beforeExecute();
        try {
            ResultSet rs = stmt.executeQuery(sql);

            if (rs == null) {
                return rs;
            }

            DruidPooledResultSet poolableResultSet = new DruidPooledResultSet(this, rs);
            addResultSetTrace(poolableResultSet);

            return poolableResultSet;
        } catch (Throwable t) {
            errorCheck(t);

            throw checkException(t, sql);
        } finally {
            conn.afterExecute();
        }
    }
}

```

### 2、DruidPooledPreparedStatement

因为PreparedStatement有sql预检查的功能，因此这里有一个缓存设计点，把短时间相同的sql只创建一个PreparedStatement，这就是DruidPooledPreparedStatement除了实现PrepareStatement接口外，额外的设计点

```java
public class DruidPooledConnection {
    /**
     * 创建PreparedStatement
     * @param sql sql语句
     * @return PreparedStatement对象
     */
    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        //检查连接状态
        checkState();

        //PreparedStatementHolder，记录Statement信息，创建Statement需先创建PreparedStatementHolder
        PreparedStatementHolder stmtHolder = null;
        //根据sql、目录(数据库名)、方法类型生成一个key，用于缓存PreparedStatementHolder的key
        PreparedStatementKey key = new PreparedStatementKey(sql, getCatalog(), MethodType.M1);

        //如果开启缓存PreparedStatementHolder，默认开启
        boolean poolPreparedStatements = holder.isPoolPreparedStatements();

        if (poolPreparedStatements) {
            //尝试通过key在缓存里面拿到PreparedStatementHolder
            stmtHolder = holder.getStatementPool().get(key);
        }

        if (stmtHolder == null) {
            try {
                //如果缓存没有，则进行创建
                stmtHolder = new PreparedStatementHolder(key, conn.prepareStatement(sql));
                holder.getDataSource().incrementPreparedStatementCount();
            } catch (SQLException ex) {
                handleException(ex, sql);
            }
        }

        //初始化Statement一些属性(设置超时时间)
        initStatement(stmtHolder);

        //把PreparedStatementHolder包装成DruidPooledPreparedStatement
        DruidPooledPreparedStatement rtnVal = new DruidPooledPreparedStatement(this, stmtHolder);

        holder.addTrace(rtnVal);

        return rtnVal;
    }
}
```

```java
public class DruidPooledPreparedStatement extends DruidPooledStatement implements PreparedStatement {
    @Override
    public void close() throws SQLException {
        if (isClosed()) {
            return;
        }

        boolean connectionClosed = this.conn.isClosed();
        // 设置回初始值
        if (pooled && !connectionClosed) {
            try {
                if (defaultMaxFieldSize != currentMaxFieldSize) {
                    stmt.setMaxFieldSize(defaultMaxFieldSize);
                    currentMaxFieldSize = defaultMaxFieldSize;
                }
                if (defaultMaxRows != currentMaxRows) {
                    stmt.setMaxRows(defaultMaxRows);
                    currentMaxRows = defaultMaxRows;
                }
                if (defaultQueryTimeout != currentQueryTimeout) {
                    stmt.setQueryTimeout(defaultQueryTimeout);
                    currentQueryTimeout = defaultQueryTimeout;
                }
                if (defaultFetchDirection != currentFetchDirection) {
                    stmt.setFetchDirection(defaultFetchDirection);
                    currentFetchDirection = defaultFetchDirection;
                }
                if (defaultFetchSize != currentFetchSize) {
                    stmt.setFetchSize(defaultFetchSize);
                    currentFetchSize = defaultFetchSize;
                }
            } catch (Exception e) {
                this.conn.handleException(e, null);
            }
        }
        //添加到StatementPool
        conn.closePoolableStatement(this);
    }
}
```