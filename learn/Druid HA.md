## Druid源码学习系列

今天我们来看一下Druid的一个不常用的feature，HA DataSource，可以认为是数据多节点的一个client

```java
public class HighAvailableDataSource extends WrapperAdapter implements DataSource {

    private volatile boolean inited = false;

    private PoolUpdater poolUpdater = new PoolUpdater(this);
    //文件、zookeeper
    private NodeListener nodeListener;

    /**
     * 初始化
     */
    public void init() {
        if (inited) {
            return;
        }
        //加锁
        synchronized (this) {
            if (inited) {
                return;
            }
            //如果数据源map为空
            if (dataSourceMap == null || dataSourceMap.isEmpty()) {
                //数据源检查间隔
                poolUpdater.setIntervalSeconds(poolPurgeIntervalSeconds);
                //是否允许一个可用数据源都没有
                poolUpdater.setAllowEmptyPool(allowEmptyPoolWhenUpdate);
                //初始化数据源更新器
                poolUpdater.init();
                //创建观察者监听
                createNodeMap();
            }
            if (selector == null) {
                //选择数据库策略
                setSelector(DataSourceSelectorEnum.RANDOM.getName());
            }
            if (dataSourceMap == null || dataSourceMap.isEmpty()) {
                LOG.warn("There is NO DataSource available!!! Please check your configuration.");
            }
            inited = true;
        }
    }

    public void setTargetDataSource(String targetName) {
        selector.setTarget(targetName);
    }

    @Override
    public Connection getConnection() throws SQLException {
        //如果还没初始化进行初始化
        init();
        //负载均衡得到一个DataSource
        DataSource dataSource = selector.get();
        if (dataSource == null) {
            LOG.warn("Can NOT obtain DataSource, return null.");
            return null;
        }
        return dataSource.getConnection();
    }
}
```
整体设计与DruidDataSource类似，比较特别的就是这里有个观察者模式，NodeListener，它有两个实现，File和Zookeeper

File的话就是定期读文件，刷新

```java
public class FileNodeListener extends NodeListener {
    private final static Log LOG = LogFactory.getLog(FileNodeListener.class);

    private Lock lock = new ReentrantLock();
    private String file = null;
    private int intervalSeconds = 60;
    private ScheduledExecutorService executor;

    /**
     * Start a Scheduler to check the specified file.
     *
     * @see #setIntervalSeconds(int)
     * @see #update()
     */
    @Override
    public void init() {
        super.init();
        if (intervalSeconds <= 0) {
            intervalSeconds = 60;
        }
        //定时任务，定期执行被观察者的update，达到通知观察者的效果
        executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                LOG.debug("Checking file " + file + " every " + intervalSeconds + "s.");
                if (!lock.tryLock()) {
                    LOG.info("Can not acquire the lock, skip this time.");
                    return;
                }
                try {
                    //观察者更新
                    update();
                } catch (Exception e) {
                    LOG.error("Can NOT update the node list.", e);
                } finally {
                    lock.unlock();
                }
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }
}
```

Zookeeper的话就是监听节点变化

```java
public class ZookeeperNodeListener extends NodeListener {
    private final static Log LOG = LogFactory.getLog(ZookeeperNodeListener.class);
    private String zkConnectString;
    private String path = "/ha-druid-datasources";
    private Lock lock = new ReentrantLock();
    private boolean privateZkClient = false; // Should I close the client?
    private PathChildrenCache cache;
    private CuratorFramework client;
    /**
     * URL Template, e.g.
     * jdbc:mysql://${host}:${port}/${database}?useUnicode=true
     * ${host}, ${port} and ${database} will be replaced by values in ZK
     * ${host} can also be #{host} and #host#
     */
    private String urlTemplate;

    /**
     * Init a PathChildrenCache to watch the given path.
     */
    @Override
    public void init() {
        checkParameters();
        super.init();
        //连接Zookeeper
        if (client == null) {
            client = CuratorFrameworkFactory.builder()
                    .canBeReadOnly(true)
                    .connectionTimeoutMs(5000)
                    .connectString(zkConnectString)
                    .retryPolicy(new RetryForever(10000))
                    .sessionTimeoutMs(30000)
                    .build();
            client.start();
            privateZkClient = true;
        }
        cache = new PathChildrenCache(client, path, true);
        //监听节点变化
        cache.getListenable().addListener(new PathChildrenCacheListener() {
            @Override
            public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
                try {
                    LOG.info("Receive an event: " + event.getType());
                    lock.lock();
                    PathChildrenCacheEvent.Type eventType = event.getType();
                    switch (eventType) {
                        case CHILD_REMOVED:
                            updateSingleNode(event, NodeEventTypeEnum.DELETE);
                            break;
                        case CHILD_ADDED:
                            updateSingleNode(event, NodeEventTypeEnum.ADD);
                            break;
                        case CONNECTION_RECONNECTED:
                            refreshAllNodes();
                            break;
                        default:
                            // CHILD_UPDATED
                            // INITIALIZED
                            // CONNECTION_LOST
                            // CONNECTION_SUSPENDED
                            LOG.info("Received a PathChildrenCacheEvent, IGNORE it: " + event);
                    }
                } finally {
                    lock.unlock();
                    LOG.info("Finish the processing of event: " + event.getType());
                }
            }
        });
        try {
            // Use BUILD_INITIAL_CACHE to force build cache in the current Thread.
            // We don't use POST_INITIALIZED_EVENT, so there's no INITIALIZED event.
            cache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
        } catch (Exception e) {
            LOG.error("Can't start PathChildrenCache", e);
        }
    }
}
```

接下来我们看一下负载均衡器DataSourceSelector

它有三个实现类，分别是NamedDataSourceSelector、RandomDataSourceSelector、StickyRandomDataSourceSelector

NamedDataSourceSelector的话可以设置DefaultName, 在访问之前需要先调用setTarget，设置目标数据库名，否则使用DefaultName，这在主从架构写语句需要访问主库场景下特别有用

RandomDataSourceSelector就是单纯的随机负载均衡

StickyRandomDataSourceSelector是一个记录目标数据库到ThreadLocal，实现同一线程前后获取的数据源一致



