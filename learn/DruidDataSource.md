## Druid源码学习系列

今天我们对Druid的一个重点类DruidDataSource进行分析，来看看Druid是如何管理创建的连接、如何进行"close"

### 1、getConnection方法

这是javax.DataSource的一个重要方法，Druid不仅实现了DataSource来实现类似DriverManger#getConnection的功能,还对ConnectionPoolDataSource进行了支持

```java
public class DruidDataSource {
    /**
     * 实现javax的DataSource接口的getConnection方法
     */
    @Override
    public DruidPooledConnection getConnection() throws SQLException {
        return getConnection(maxWait);
    }

    public DruidPooledConnection getConnection(long maxWaitMillis) throws SQLException {
        //初始化
        init();

        if (filters.size() > 0) {
            //如果filter长度大于0，构建责任链
            FilterChainImpl filterChain = new FilterChainImpl(this);
            //开始执行责任链
            return filterChain.dataSource_connect(this, maxWaitMillis);
        } else {
            //直接获取数据库连接
            return getConnectionDirect(maxWaitMillis);
        }
    }

    /**
     * 实现javax的ConnectionPoolDataSource，获取池化数据库连接接口
     * @return 池化的数据库连接
     */
    @Override
    public PooledConnection getPooledConnection() throws SQLException {
        return getConnection(maxWait);
    }

    /**
     * 实现javax的ConnectionPoolDataSource，获取池化数据库连接接口
     * @param user 用户名
     * @param password 密码
     * @return 池化的数据库连接
     */
    @Override
    public PooledConnection getPooledConnection(String user, String password) throws SQLException {
        throw new UnsupportedOperationException("Not supported by DruidDataSource");
    }
}
```

下面我们继续看init()方法，来看看是如何初始化创建连接的

### 2、init()方法

```java
public class DruidDataSource {
    public void init() throws SQLException {
        if (inited) {
            //已经初始化过，则直接返回
            return;
        }

        // bug fixed for dead lock, for issue #2980
        DruidDriver.getInstance();

        final ReentrantLock lock = this.lock;
        try {
            //加锁
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            throw new SQLException("interrupt", e);
        }

        boolean init = false;
        try {
            //双重锁校验
            if (inited) {
                return;
            }

            initStackTrace = Utils.toString(Thread.currentThread().getStackTrace());
            //生成数据源id
            this.id = DruidDriver.createDataSourceId();
            if (this.id > 1) {
                long delta = (this.id - 1) * 100000;
                this.connectionIdSeedUpdater.addAndGet(this, delta);
                this.statementIdSeedUpdater.addAndGet(this, delta);
                this.resultSetIdSeedUpdater.addAndGet(this, delta);
                this.transactionIdSeedUpdater.addAndGet(this, delta);
            }

            if (this.jdbcUrl != null) {
                this.jdbcUrl = this.jdbcUrl.trim();
                initFromWrapDriverUrl();
            }

            for (Filter filter : filters) {
                filter.init(this);
            }

            if (this.dbTypeName == null || this.dbTypeName.length() == 0) {
                //如果数据库类型缺失，则尝试去获取
                this.dbTypeName = JdbcUtils.getDbType(jdbcUrl, null);
            }

            DbType dbType = DbType.of(this.dbTypeName);
            ...
            if (maxActive <= 0) {
                //最大活跃连接数小于等于0，非法
                throw new IllegalArgumentException("illegal maxActive " + maxActive);
            }

            if (maxActive < minIdle) {
                //最大活跃连接数小于最小连接数，非法
                throw new IllegalArgumentException("illegal maxActive " + maxActive);
            }

            if (getInitialSize() > maxActive) {
                //初始化连接数大于最大活跃连接数，非法
                throw new IllegalArgumentException("illegal initialSize " + this.initialSize + ", maxActive " + maxActive);
            }

            if (timeBetweenLogStatsMillis > 0 && useGlobalDataSourceStat) {
                //配置了useGlobalDataSourceStat=true就不能配置timeBetweenLogStatsMillis
                throw new IllegalArgumentException("timeBetweenLogStatsMillis not support useGlobalDataSourceStat=true");
            }
            ...
            if (this.driverClass != null) {
                this.driverClass = driverClass.trim();
            }

            //初始化Druid Filter的SPI，并添加到filter列表中
            initFromSPIServiceLoader();

            //创建数据库驱动对象
            resolveDriver();

            //进行一些检查
            initCheck();

            //初始化异常分类器
            initExceptionSorter();
            //初始化连接检查器(通过查询select 1)
            initValidConnectionChecker();
            validationQueryCheck();

            if (isUseGlobalDataSourceStat()) {
                dataSourceStat = JdbcDataSourceStat.getGlobal();
                if (dataSourceStat == null) {
                    dataSourceStat = new JdbcDataSourceStat("Global", "Global", this.dbTypeName);
                    JdbcDataSourceStat.setGlobal(dataSourceStat);
                }
                if (dataSourceStat.getDbType() == null) {
                    dataSourceStat.setDbType(this.dbTypeName);
                }
            } else {
                dataSourceStat = new JdbcDataSourceStat(this.name, this.jdbcUrl, this.dbTypeName, this.connectProperties);
            }
            dataSourceStat.setResetStatEnable(this.resetStatEnable);

            //创建最大活跃连接数的DruidConnectionHolder数组
            connections = new DruidConnectionHolder[maxActive];
            evictConnections = new DruidConnectionHolder[maxActive];
            keepAliveConnections = new DruidConnectionHolder[maxActive];

            SQLException connectError = null;

            if (createScheduler != null && asyncInit) {
                for (int i = 0; i < initialSize; ++i) {
                    submitCreateTask(true);
                }
            } else if (!asyncInit) {
                // 初始化数据库连接
                while (poolingCount < initialSize) {
                    try {
                        //创建真实连接
                        PhysicalConnectionInfo pyConnectInfo = createPhysicalConnection();
                        //把真实的连接放到holder
                        DruidConnectionHolder holder = new DruidConnectionHolder(this, pyConnectInfo);
                        //把该holder赋值给之前创建的DruidConnectionHolder数组
                        connections[poolingCount++] = holder;
                    } catch (SQLException ex) {
                        LOG.error("init datasource error, url: " + this.getUrl(), ex);
                        if (initExceptionThrow) {
                            connectError = ex;
                            break;
                        } else {
                            Thread.sleep(3000);
                        }
                    }
                }

                if (poolingCount > 0) {
                    //连接数
                    poolingPeak = poolingCount;
                    poolingPeakTime = System.currentTimeMillis();
                }
            }

            createAndLogThread();
            createAndStartCreatorThread();
            createAndStartDestroyThread();

            //等待上面创建任务完成
            initedLatch.await();
            init = true;

            initedTime = new Date();
            registerMbean();

            if (connectError != null && poolingCount == 0) {
                throw connectError;
            }

            if (keepAlive) {
                // async fill to minIdle
                if (createScheduler != null) {
                    for (int i = 0; i < minIdle; ++i) {
                        submitCreateTask(true);
                    }
                } else {
                    this.emptySignal();
                }
            }

        } catch (SQLException e) {
            LOG.error("{dataSource-" + this.getID() + "} init error", e);
            throw e;
        } catch (InterruptedException e) {
            throw new SQLException(e.getMessage(), e);
        } catch (RuntimeException e){
            LOG.error("{dataSource-" + this.getID() + "} init error", e);
            throw e;
        } catch (Error e){
            LOG.error("{dataSource-" + this.getID() + "} init error", e);
            throw e;

        } finally {
            //已初始化
            inited = true;
            //解锁
            lock.unlock();
            ...
        }
    }
}
```

可以看到，这是一个只会执行一次的方法，使用了Lock和加锁后的状态检查来确保。同时我们也可以看到，创建的真实物理连接被放到了DruidConnectionHolder里面，再保存DruidConnectionHolder数组

这是Druid的一个设计，在DataSource与Connection之间加了一层Holder

DruidDataSource <-> DruidConnectionHolder <-> PooledConnection

Druid为什么这么设计呢，因为一个Connection对象可以产生多个Statement对象，当我们想同时保存Connection和对应的多个Statement的时候，就会比较纠结，所以我们可以把像Statement这些保存在Holder里面

```java
public final class DruidConnectionHolder {
    
    protected final List<ConnectionEventListener> connectionEventListeners = new CopyOnWriteArrayList<ConnectionEventListener>();
    
    protected final List<StatementEventListener>  statementEventListeners  = new CopyOnWriteArrayList<StatementEventListener>();
    
    protected final List<Statement>               statementTrace           = new ArrayList<Statement>(2);
}
```

### 3、getConnectionDirect()方法

getConnectionDirect方法是如果没有filter链直接获取数据库连接的方法，我们来看一下是如何获取连接的

```java
public class DruidDataSource {
    /**
     * 直接获取数据库连接
     * @param maxWaitMillis 最长等待时间
     * @return druid池化的数据库连接
     */
    public DruidPooledConnection getConnectionDirect(long maxWaitMillis) throws SQLException {
        int notFullTimeoutRetryCnt = 0;
        for (;;) {
            // handle notFullTimeoutRetry
            DruidPooledConnection poolableConnection;
            try {
                //调用getConnectionInternal来获取连接
                poolableConnection = getConnectionInternal(maxWaitMillis);
            } catch (GetConnectionTimeoutException ex) {
                if (notFullTimeoutRetryCnt <= this.notFullTimeoutRetryCount && !isFull()) {
                    notFullTimeoutRetryCnt++;
                    if (LOG.isWarnEnabled()) {
                        LOG.warn("get connection timeout retry : " + notFullTimeoutRetryCnt);
                    }
                    continue;
                }
                throw ex;
            }
            ...
            return poolableConnection;
        }
    }
}
```

可以看到主要就是调用getConnectionInternal来获取连接，我们来看一下getConnectionInternal方法

```java
public class DruidDataSource {
    private DruidPooledConnection getConnectionInternal(long maxWait) throws SQLException {
        if (closed) {
            //如果数据源已关闭，连接错误数++，抛出异常
            connectErrorCountUpdater.incrementAndGet(this);
            throw new DataSourceClosedException("dataSource already closed at " + new Date(closeTimeMillis));
        }

        //如果数据源不可用
        if (!enable) {
            connectErrorCountUpdater.incrementAndGet(this);

            if (disableException != null) {
                //不可用异常信息不为空则抛出该异常
                throw disableException;
            }

            throw new DataSourceDisableException();
        }

        //转化为纳秒
        final long nanos = TimeUnit.MILLISECONDS.toNanos(maxWait);
        //最大等待线程数
        final int maxWaitThreadCount = this.maxWaitThreadCount;

        DruidConnectionHolder holder;

        for (boolean createDirect = false;;) {
            if (createDirect) {
                //直接创建物理连接逻辑
                createStartNanosUpdater.set(this, System.nanoTime());
                if (creatingCountUpdater.compareAndSet(this, 0, 1)) {
                    //创建物理连接
                    PhysicalConnectionInfo pyConnInfo = DruidDataSource.this.createPhysicalConnection();
                    //把该物理连接放到holder
                    holder = new DruidConnectionHolder(this, pyConnInfo);
                    holder.lastActiveTimeMillis = System.currentTimeMillis();
                    creatingCountUpdater.decrementAndGet(this);
                    directCreateCountUpdater.incrementAndGet(this);

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("conn-direct_create ");
                    }

                    boolean discard = false;
                    lock.lock();
                    try {
                        if (activeCount < maxActive) {
                            activeCount++;
                            holder.active = true;
                            if (activeCount > activePeak) {
                                activePeak = activeCount;
                                activePeakTime = System.currentTimeMillis();
                            }
                            break;
                        } else {
                            discard = true;
                        }
                    } finally {
                        lock.unlock();
                    }

                    if (discard) {
                        JdbcUtils.close(pyConnInfo.getPhysicalConnection());
                    }
                }
            }

            try {
                //获取锁
                lock.lockInterruptibly();
            } catch (InterruptedException e) {
                //需要获取锁的线程中断异常
                connectErrorCountUpdater.incrementAndGet(this);
                throw new SQLException("interrupt", e);
            }

            try {
                if (maxWaitThreadCount > 0
                        && notEmptyWaitThreadCount >= maxWaitThreadCount) {
                    //如果最大等待线程数大于0 并且 已在等待的线程数大于等于最大等待线程数，则连接异常数++，抛出异常
                    connectErrorCountUpdater.incrementAndGet(this);
                    throw new SQLException("maxWaitThreadCount " + maxWaitThreadCount + ", current wait Thread count "
                            + lock.getQueueLength());
                }

                ...

                //连接数++
                connectCount++;

                //如果创建连接调度器不为空 并且 DataSourse的连接数为0等，则走直接创建物理连接逻辑
                if (createScheduler != null
                        && poolingCount == 0
                        && activeCount < maxActive
                        && creatingCountUpdater.get(this) == 0
                        && createScheduler instanceof ScheduledThreadPoolExecutor) {
                    ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) createScheduler;
                    if (executor.getQueue().size() > 0) {
                        createDirect = true;
                        continue;
                    }
                }

                //获取一个holder(holder数组在初始化是创建并放入物理连接)
                if (maxWait > 0) {
                    holder = pollLast(nanos);
                } else {
                    holder = takeLast();
                }

                ...
            } catch (InterruptedException e) {
                connectErrorCountUpdater.incrementAndGet(this);
                throw new SQLException(e.getMessage(), e);
            } catch (SQLException e) {
                connectErrorCountUpdater.incrementAndGet(this);
                throw e;
            } finally {
                lock.unlock();
            }

            break;
        }

        ...

        //该holder使用数++
        holder.incrementUseCount();

        //把holder包装成DruidPooledConnection返回
        DruidPooledConnection poolalbeConnection = new DruidPooledConnection(holder);
        return poolalbeConnection;
    }
}
```

里面的逻辑就是获取到DruidConnectionHolder，然后包装成DruidPooledConnection返回，如果初始化工作已经完成，则从初始化创建的DruidPooledConnection数组去拿，否则走直接创建逻辑

### 4、DruidPooledConnection#recycle方法

在连接池中，关闭连接并不是真实的关闭物理连接，那么Druid在关闭连接处是怎么做到连接复用的呢？

```java
public class DruidPooledConnection {
    @Override
    public void close() throws SQLException {
        if (this.disable) {
            return;
        }

        DruidConnectionHolder holder = this.holder;
        ...
        try {
            //回调连接事件监听器的连接关闭方法
            for (ConnectionEventListener listener : holder.getConnectionEventListeners()) {
                listener.connectionClosed(new ConnectionEvent(this));
            }
            //执行filter链的连接关闭方法
            List<Filter> filters = dataSource.getProxyFilters();
            if (filters.size() > 0) {
                FilterChainImpl filterChain = new FilterChainImpl(dataSource);
                filterChain.dataSource_recycle(this);
            } else {
                //调用recycle方法，来进行连接“关闭”
                recycle();
            }
        } finally {
            CLOSING_UPDATER.set(this, 0);
        }

        this.disable = true;
    }

    public void recycle() throws SQLException {
        if (this.disable) {
            return;
        }

        DruidConnectionHolder holder = this.holder;
        if (holder == null) {
            if (dupCloseLogEnable) {
                LOG.error("dup close");
            }
            return;
        }

        if (!this.abandoned) {
            DruidAbstractDataSource dataSource = holder.getDataSource();
            dataSource.recycle(this);
        }

        //断开holder联系
        this.holder = null;
        conn = null;
        transactionInfo = null;
        closed = true;
    }
}
```

可以看到，“关闭”连接其实就是断开与DruidConnectionHolder的关系，与获取DruidPooledConnection时，将DruidConnectionHolder包装成DruidPooledConnection刚好对应，设计非常巧妙



