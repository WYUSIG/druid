## Druid源码学习系列

上一篇我们对DruidDataSource进行了一些简单的了解，今天我们再来对DruidPooledConnection进行一些简单分析

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/img.png)

### 1、实现接口

```java
public class DruidPooledConnection extends PoolableWrapper implements javax.sql.PooledConnection, Connection {
    ...
}
```

从DruidPooledConnection实现的接口我们可以看出，其不仅实现了java.sql.Connection，还实现了javax的PooledConnection

代表着这是一个池化连接，既然实现jdbc的Connection接口，那么我们来看一下prepareStatement方法

### 2、prepareStatement方法

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

可以看到这里也有一个holder：PreparedStatementHolder，记录的是关于这个Statement的信息，与之前的DruidConnectionHolder类似

同时我们可以看到一个缓存设计：druidConnectionHolder.getStatementPool().get(key)

这是为了当短时间内大量相同的sql不用频繁创建Statement，之前从缓存里面获取，我们来看一下这个缓存是怎么设计的

### 3、PreparedStatementPool

```java
public class PreparedStatementPool {

    private final LRUCache                map;
    private final DruidAbstractDataSource dataSource;

    public PreparedStatementPool(DruidConnectionHolder holder){
        this.dataSource = holder.getDataSource();
        int initCapacity = holder.getDataSource().getMaxPoolPreparedStatementPerConnectionSize();
        if (initCapacity <= 0) {
            initCapacity = 16;
        }
        map = new LRUCache(initCapacity);
    }

    public PreparedStatementHolder get(PreparedStatementKey key) throws SQLException {
        ...
    }

    public void remove(PreparedStatementHolder stmtHolder) throws SQLException {
        ...
    }

    public void put(PreparedStatementHolder stmtHolder) throws SQLException {
        ...
    }

    /**
     * 关闭Statement
     */
    public void closeRemovedStatement(PreparedStatementHolder holder) {
        ...
    }

    /**
     * LRU缓存，基于LinkedHashMap实现
     */
    public class LRUCache extends LinkedHashMap<PreparedStatementKey, PreparedStatementHolder> {

        private static final long serialVersionUID = 1L;

        public LRUCache(int maxSize){
            super(maxSize, 0.75f, true);
        }

        protected boolean removeEldestEntry(Entry<PreparedStatementKey, PreparedStatementHolder> eldest) {
            boolean remove = (size() > dataSource.getMaxPoolPreparedStatementPerConnectionSize());

            if (remove) {
                closeRemovedStatement(eldest.getValue());
            }

            return remove;
        }
    }
}
```

可以看到，里面就是个LRU缓存，容量由maxPoolPreparedStatementPerConnectionSize配置