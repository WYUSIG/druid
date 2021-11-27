## Druid源码学习系列

我们在Spring应用中集成Druid时，可以通过开启监控，就能通过web访问监控信息，那么Druid背后是如何实现这一功能的呢

我们打开druid-spring-boot-starter，在DruidStatViewServletConfiguration.java文件中可以看到

```java
@ConditionalOnWebApplication
//只有spring.datasource.druid.stat-view-servlet.enabled = true时才加载该配置
@ConditionalOnProperty(name = "spring.datasource.druid.stat-view-servlet.enabled", havingValue = "true")
public class DruidStatViewServletConfiguration {
    private static final String DEFAULT_ALLOW_IP = "127.0.0.1";

    //注册Servlet
    @Bean
    public ServletRegistrationBean statViewServletRegistrationBean(DruidStatProperties properties) {
        //获取spring.datasource.druid.stat-view-servlet配置
        DruidStatProperties.StatViewServlet config = properties.getStatViewServlet();
        ServletRegistrationBean registrationBean = new ServletRegistrationBean();
        //设置自定义Servlet实现类
        registrationBean.setServlet(new StatViewServlet());
        //访问路径
        registrationBean.addUrlMappings(config.getUrlPattern() != null ? config.getUrlPattern() : "/druid/*");
        //给目标Servlet传参数
        if (config.getAllow() != null) {
            registrationBean.addInitParameter("allow", config.getAllow());
        } else {
            registrationBean.addInitParameter("allow", DEFAULT_ALLOW_IP);
        }
        if (config.getDeny() != null) {
            registrationBean.addInitParameter("deny", config.getDeny());
        }
        if (config.getLoginUsername() != null) {
            registrationBean.addInitParameter("loginUsername", config.getLoginUsername());
        }
        if (config.getLoginPassword() != null) {
            registrationBean.addInitParameter("loginPassword", config.getLoginPassword());
        }
        if (config.getResetEnable() != null) {
            registrationBean.addInitParameter("resetEnable", config.getResetEnable());
        }
        return registrationBean;
    }
}
```

DruidStatViewServletConfiguration里面构建了一个ServletRegistrationBean.class类型的Bean，ServletRegistrationBean是Spring提供的一种往Servlet3.0注册Servlet的方式

作用类似于ServletContext，只不过对Spring容器更友好

其中ServletRegistrationBean里面设置了一个StatViewServlet，这是一个ResourceServlet, 指向的资源就是Druid的前端页面

```java
public class StatViewServlet extends ResourceServlet {

    private final static Log      LOG                     = LogFactory.getLog(StatViewServlet.class);

    private static final long     serialVersionUID        = 1L;

    public static final String    PARAM_NAME_RESET_ENABLE = "resetEnable";

    public static final String    PARAM_NAME_JMX_URL      = "jmxUrl";
    public static final String    PARAM_NAME_JMX_USERNAME = "jmxUsername";
    public static final String    PARAM_NAME_JMX_PASSWORD = "jmxPassword";

    private DruidStatService      statService             = DruidStatService.getInstance();

    /** web.xml中配置的jmx的连接地址 */
    private String                jmxUrl                  = null;
    /** web.xml中配置的jmx的用户名 */
    private String                jmxUsername             = null;
    /** web.xml中配置的jmx的密码 */
    private String                jmxPassword             = null;
    private MBeanServerConnection conn                    = null;

    public StatViewServlet(){
        //资源文件路径
        super("support/http/resources");
    }

    /**
     * 初始化
     */
    public void init() throws ServletException {
        super.init();

        try {
            //是否支持重置监控状态
            String param = getInitParameter(PARAM_NAME_RESET_ENABLE);
            if (param != null && param.trim().length() != 0) {
                param = param.trim();
                boolean resetEnable = Boolean.parseBoolean(param);
                statService.setResetEnable(resetEnable);
            }
        } catch (Exception e) {
            String msg = "initParameter config error, resetEnable : " + getInitParameter(PARAM_NAME_RESET_ENABLE);
            LOG.error(msg, e);
        }

        // 获取jmx的连接配置信息
        String param = readInitParam(PARAM_NAME_JMX_URL);
        if (param != null) {
            jmxUrl = param;
            jmxUsername = readInitParam(PARAM_NAME_JMX_USERNAME);
            jmxPassword = readInitParam(PARAM_NAME_JMX_PASSWORD);
            try {
                initJmxConn();
            } catch (IOException e) {
                LOG.error("init jmx connection error", e);
            }
        }

    }

    /**
     * 读取servlet中的配置参数.
     * 
     * @param key 配置参数名
     * @return 配置参数值，如果不存在当前配置参数，或者为配置参数长度为0，将返回null
     */
    private String readInitParam(String key) {
        String value = null;
        try {
            String param = getInitParameter(key);
            if (param != null) {
                param = param.trim();
                if (param.length() > 0) {
                    value = param;
                }
            }
        } catch (Exception e) {
            String msg = "initParameter config [" + key + "] error";
            LOG.warn(msg, e);
        }
        return value;
    }

    /**
     * 初始化jmx连接
     * 
     * @throws IOException
     */
    private void initJmxConn() throws IOException {
        if (jmxUrl != null) {
            JMXServiceURL url = new JMXServiceURL(jmxUrl);
            Map<String, String[]> env = null;
            if (jmxUsername != null) {
                env = new HashMap<String, String[]>();
                String[] credentials = new String[] { jmxUsername, jmxPassword };
                env.put(JMXConnector.CREDENTIALS, credentials);
            }
            JMXConnector jmxc = JMXConnectorFactory.connect(url, env);
            conn = jmxc.getMBeanServerConnection();
        }
    }

    /**
     * 根据指定的url来获取jmx服务返回的内容.
     * 
     * @param connetion jmx连接
     * @param url url内容
     * @return the jmx返回的内容
     * @throws Exception the exception
     */
    private String getJmxResult(MBeanServerConnection connetion, String url) throws Exception {
        ObjectName name = new ObjectName(DruidStatService.MBEAN_NAME);

        String result = (String) conn.invoke(name, "service", new String[] { url },
                                             new String[] { String.class.getName() });
        return result;
    }

    /**
     * 程序首先判断是否存在jmx连接地址，如果不存在，则直接调用本地的druid服务； 如果存在，则调用远程jmx服务。在进行jmx通信，首先判断一下jmx连接是否已经建立成功，如果已经
     * 建立成功，则直接进行通信，如果之前没有成功建立，则会尝试重新建立一遍。.
     * 
     * @param url 要连接的服务地址
     * @return 调用服务后返回的json字符串
     */
    protected String process(String url) {
        String resp = null;
        if (jmxUrl == null) {
            resp = statService.service(url);
        } else {
            if (conn == null) {// 连接在初始化时创建失败
                try {// 尝试重新连接
                    initJmxConn();
                } catch (IOException e) {
                    LOG.error("init jmx connection error", e);
                    resp = DruidStatService.returnJSONResult(DruidStatService.RESULT_CODE_ERROR,
                                                             "init jmx connection error" + e.getMessage());
                }
                if (conn != null) {// 连接成功
                    try {
                        resp = getJmxResult(conn, url);
                    } catch (Exception e) {
                        LOG.error("get jmx data error", e);
                        resp = DruidStatService.returnJSONResult(DruidStatService.RESULT_CODE_ERROR, "get data error:"
                                                                                                     + e.getMessage());
                    }
                }
            } else {// 连接成功
                try {
                    resp = getJmxResult(conn, url);
                } catch (Exception e) {
                    LOG.error("get jmx data error", e);
                    resp = DruidStatService.returnJSONResult(DruidStatService.RESULT_CODE_ERROR,
                                                             "get data error" + e.getMessage());
                }
            }
        }
        return resp;
    }

    public void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String contextPath = request.getContextPath();
        String servletPath = request.getServletPath();
        String requestURI = request.getRequestURI();

        response.setCharacterEncoding("utf-8");

        if (contextPath == null) { // root context
            contextPath = "";
        }
        String uri = contextPath + servletPath;
        String path = requestURI.substring(contextPath.length() + servletPath.length());

        //如果是/druid，直接定向到druid主页
        if ("".equals(path)) {
            if (contextPath.equals("") || contextPath.equals("/")) {
                response.sendRedirect("/druid/index.html");
            } else {
                response.sendRedirect("druid/index.html");
            }
            return;
        }

        if ("/".equals(path)) {
            response.sendRedirect("index.html");
            return;
        }

        //否则调用父类service方法
        super.service(request, response);
    }

}
```
这里看到一个比较有意思的设计，jmx远程调用

接下来我们看一下它的父类ResourceServlet，这个类里面包含了大量的接口跳转逻辑

```java
public abstract class ResourceServlet extends HttpServlet {
    public void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String servletPath = request.getServletPath();
        handler.service(request, response, servletPath, new ProcessCallback() {

            @Override
            public String process(String url) {
                return ResourceServlet.this.process(url);
            }
        });
    }

    public static class ResourceHandler {
        public void service(HttpServletRequest request
                , HttpServletResponse response
                , String servletPath
                , ProcessCallback processCallback
        ) throws ServletException, IOException {
            String contextPath = request.getContextPath();
            String requestURI = request.getRequestURI();

            response.setCharacterEncoding("utf-8");

            if (contextPath == null) { // root context
                contextPath = "";
            }
            String uri = contextPath + servletPath;
            String path = requestURI.substring(contextPath.length() + servletPath.length());

            if (!isPermittedRequest(request)) {
                path = "/nopermit.html";
                returnResourceFile(path, uri, response);
                return;
            }

            if ("/submitLogin".equals(path)) {
                String usernameParam = request.getParameter(PARAM_NAME_USERNAME);
                String passwordParam = request.getParameter(PARAM_NAME_PASSWORD);
                if (username.equals(usernameParam) && password.equals(passwordParam)) {
                    request.getSession().setAttribute(SESSION_USER_KEY, username);
                    response.getWriter().print("success");
                } else {
                    response.getWriter().print("error");
                }
                return;
            }

            //如果需要登录，且不是css、js等资源文件、登录界面等，跳转到登录页面
            if (isRequireAuth() //
                    && !containsUser(request)//
                    && !checkLoginParam(request)//
                    && !("/login.html".equals(path) //
                    || path.startsWith("/css")//
                    || path.startsWith("/js") //
                    || path.startsWith("/img"))) {
                if (contextPath.equals("") || contextPath.equals("/")) {
                    response.sendRedirect("/druid/login.html");
                } else {
                    if ("".equals(path)) {
                        response.sendRedirect("druid/login.html");
                    } else {
                        response.sendRedirect("login.html");
                    }
                }
                return;
            }

            //初始页面跳转到index.html
            if ("".equals(path) || "/".equals(path)) {
                returnResourceFile("/index.html", uri, response);
                return;
            }

            //如果以.json结尾，则是获取数据请求
            if (path.contains(".json")) {
                //构造完整路径与参数
                String fullUrl = path;
                if (request.getQueryString() != null && request.getQueryString().length() > 0) {
                    fullUrl += "?" + request.getQueryString();
                }
                response.getWriter().print(processCallback.process(fullUrl));
                return;
            }

            // find file in resources path

            returnResourceFile(path, uri, response);
        }
    }
}
```

最后来看一下获取监控数据代码

```java
public final class DruidStatService implements DruidStatServiceMBean {
    public String service(String url) {

        Map<String, String> parameters = getParameters(url);

        if (url.equals("/basic.json")) {
            return returnJSONResult(RESULT_CODE_SUCCESS, statManagerFacade.returnJSONBasicStat());
        }

        if (url.equals("/reset-all.json")) {
            statManagerFacade.resetAll();

            return returnJSONResult(RESULT_CODE_SUCCESS, null);
        }

        if (url.equals("/log-and-reset.json")) {
            statManagerFacade.logAndResetDataSource();

            return returnJSONResult(RESULT_CODE_SUCCESS, null);
        }

        if (url.equals("/datasource.json")) {
            return returnJSONResult(RESULT_CODE_SUCCESS, statManagerFacade.getDataSourceStatDataList());
        }

        if (url.equals("/activeConnectionStackTrace.json")) {
            return returnJSONResult(RESULT_CODE_SUCCESS, statManagerFacade.getActiveConnStackTraceList());
        }

        if (url.startsWith("/datasource-")) {
            Integer id = StringUtils.subStringToInteger(url, "datasource-", ".");
            Object result = statManagerFacade.getDataSourceStatData(id);
            return returnJSONResult(result == null ? RESULT_CODE_ERROR : RESULT_CODE_SUCCESS, result);
        }

        if (url.startsWith("/connectionInfo-") && url.endsWith(".json")) {
            Integer id = StringUtils.subStringToInteger(url, "connectionInfo-", ".");
            List<?> connectionInfoList = statManagerFacade.getPoolingConnectionInfoByDataSourceId(id);
            return returnJSONResult(connectionInfoList == null ? RESULT_CODE_ERROR : RESULT_CODE_SUCCESS,
                    connectionInfoList);
        }

        if (url.startsWith("/activeConnectionStackTrace-") && url.endsWith(".json")) {
            Integer id = StringUtils.subStringToInteger(url, "activeConnectionStackTrace-", ".");
            return returnJSONActiveConnectionStackTrace(id);
        }

        if (url.startsWith("/sql.json")) {
            return returnJSONResult(RESULT_CODE_SUCCESS, getSqlStatDataList(parameters));
        }

        if (url.startsWith("/wall.json")) {
            return returnJSONResult(RESULT_CODE_SUCCESS, getWallStatMap(parameters));
        }

        if (url.startsWith("/wall-") && url.indexOf(".json") > 0) {
            Integer dataSourceId = StringUtils.subStringToInteger(url, "wall-", ".json");
            Object result = statManagerFacade.getWallStatMap(dataSourceId);
            return returnJSONResult(result == null ? RESULT_CODE_ERROR : RESULT_CODE_SUCCESS, result);
        }

        if (url.startsWith("/sql-") && url.indexOf(".json") > 0) {
            Integer id = StringUtils.subStringToInteger(url, "sql-", ".json");
            return getSqlStat(id);
        }

        if (url.startsWith("/weburi.json")) {
            return returnJSONResult(RESULT_CODE_SUCCESS, getWebURIStatDataList(parameters));
        }

        if (url.startsWith("/weburi-") && url.indexOf(".json") > 0) {
            String uri = StringUtils.subString(url, "weburi-", ".json", true);
            return returnJSONResult(RESULT_CODE_SUCCESS, getWebURIStatData(uri));
        }

        if (url.startsWith("/webapp.json")) {
            return returnJSONResult(RESULT_CODE_SUCCESS, getWebAppStatDataList(parameters));
        }

        if (url.startsWith("/websession.json")) {
            return returnJSONResult(RESULT_CODE_SUCCESS, getWebSessionStatDataList(parameters));
        }

        if (url.startsWith("/websession-") && url.indexOf(".json") > 0) {
            String id = StringUtils.subString(url, "websession-", ".json");
            return returnJSONResult(RESULT_CODE_SUCCESS, getWebSessionStatData(id));
        }

        if (url.startsWith("/spring.json")) {
            return returnJSONResult(RESULT_CODE_SUCCESS, getSpringStatDataList(parameters));
        }

        if (url.startsWith("/spring-detail.json")) {
            String clazz = parameters.get("class");
            String method = parameters.get("method");
            return returnJSONResult(RESULT_CODE_SUCCESS, getSpringMethodStatData(clazz, method));
        }

        return returnJSONResult(RESULT_CODE_ERROR, "Do not support this request, please contact with administrator.");
    }
}
```