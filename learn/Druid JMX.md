## Druid源码学习系列

JMX，全称为Java Management Extensions，在Druid的源码中我们可以看到对JMX有很好的支持

![](https://sign-pic-1.oss-cn-shenzhen.aliyuncs.com/img/20211114040728.png)

如上图所示，给com.alibaba.druid.pool.DruidTest主线程最后加上等待，运行，打开jconsole连接对应的应用，便可以看到jmx的效果

那么Druid源码在哪里对JMX进行支持，今天我们就来探讨一下

### 1、DruidDriver

从前面的文章我们可以知道，追寻源码的入口在com.alibaba.druid.pool.DruidDataSource.getConnection()方法，里面会有判断是否已经初始化，如果没有初始化，将会调用初始化方法

在初始化方法里面我们可以看到

```java
public void init() throws SQLException{
    if(inited){
        //已经初始化过，则直接返回
        return;
    }
    
    // 重点就是这句，初始化DruidDriver
    DruidDriver.getInstance();
}
```

进入DruidDriver类，便可以看到以下代码

```java
public class DruidDriver implements Driver, DruidDriverMBean {
    static {
        AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                //注册驱动
                registerDriver(instance);
                return null;
            }
        });
    }

    public static boolean registerDriver(Driver driver) {
        try {
            //注册驱动
            DriverManager.registerDriver(driver);

            try {
                //获取MBeanServer
                MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();

                //构造ObjectName
                ObjectName objectName = new ObjectName(MBEAN_NAME);
                if (!mbeanServer.isRegistered(objectName)) {
                    //使用对象和object便可以注册到mbeanServer
                    mbeanServer.registerMBean(instance, objectName);
                }
            } catch (Throwable ex) {
                if (LOG == null) {
                    LOG = LogFactory.getLog(DruidDriver.class);
                }
                LOG.warn("register druid-driver mbean error", ex);
            }

            return true;
        } catch (Exception e) {
            if (LOG == null) {
                LOG = LogFactory.getLog(DruidDriver.class);
            }

            LOG.error("registerDriver error", e);
        }

        return false;
    }
}
```

```java
public interface DruidDriverMBean {
    String getDruidVersion();

    long getConnectCount();
    
    void resetStat();

    String getAcceptPrefix();

    boolean jdbcCompliant();

    int getMinorVersion();

    int getMajorVersion();

    String[] getDataSourceUrls();
}
```

其中jmx能显示的属性是以MBean结尾的类或接口的get方法，操作则是非get方法，看完DruidDriver，我们再来看一下DruidDataSource是如何注册到JMX的

### 2、DruidDataSource

在DruidDataSource类里面，我们可以看到一个registerMbean方法，他是在init()里面调用的

```java
public class DruidDataSource {
    public void init() throws SQLException {
        ...
        registerMbean();
        ...
    }
    public void registerMbean() {
        if (!mbeanRegistered) {
            AccessController.doPrivileged(new PrivilegedAction<Object>() {

                @Override
                public Object run() {
                    //注册到MBeanServer并返回ObjectName
                    ObjectName objectName = DruidDataSourceStatManager.addDataSource(DruidDataSource.this,
                            DruidDataSource.this.name);

                    DruidDataSource.this.setObjectName(objectName);
                    DruidDataSource.this.mbeanRegistered = true;

                    return null;
                }
            });
        }
    }
}
```

其他一些MBean都与之类似，这里便不一一赘述