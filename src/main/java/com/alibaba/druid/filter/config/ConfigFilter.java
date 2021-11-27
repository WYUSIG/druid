/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.filter.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URL;
import java.security.PublicKey;
import java.sql.SQLException;
import java.util.Properties;

import com.alibaba.druid.filter.FilterAdapter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidDataSourceFactory;
import com.alibaba.druid.proxy.jdbc.DataSourceProxy;
import com.alibaba.druid.support.logging.Log;
import com.alibaba.druid.support.logging.LogFactory;
import com.alibaba.druid.util.JdbcUtils;
import com.alibaba.druid.util.StringUtils;

/**
 * <pre>
 * 这个类主要是负责两个事情, 解密, 和下载远程的配置文件
 * [解密]
 * 
 * DruidDataSource dataSource = new DruidDataSource();
 * //dataSource.setXXX 其他设置
 * //下面两步很重要
 * //启用config filter
 * dataSource.setFilters("config");
 * //使用RSA解密(使用默认密钥）
 * dataSource.setConnectionPropertise("config.decrypt=true");
 * dataSource.setPassword("加密的密文");
 * 
 * [远程配置文件]
 * DruidDataSource dataSource = new DruidDataSource();
 * //下面两步很重要
 * //启用config filter
 * dataSource.setFilters("config");
 * //使用RSA解密(使用默认密钥）
 * dataSource.setConnectionPropertise("config.file=http://localhost:8080/remote.propreties;");
 * 
 * [Spring的配置解密]
 * 
 * &lt;bean id="dataSource" class="com.alibaba.druid.pool.DruidDataSource" init-method="init" destroy-method="close"&gt;
 *     &lt;property name="password" value="加密的密文" /&gt;
 *     &lt;!-- 其他的属性设置 --&gt;
 *     &lt;property name="filters" value="config" /&gt;
 *     &lt;property name="connectionProperties" value="config.decrypt=true" /&gt;
 * &lt;/bean&gt;
 * 
 * [Spring的配置远程配置文件]
 * 
 * &lt;bean id="dataSource" class="com.alibaba.druid.pool.DruidDataSource" init-method="init" destroy-method="close"&gt;
 *     &lt;property name="filters" value="config" /&gt;
 *     &lt;property name="connectionProperties" value="config.file=http://localhost:8080/remote.propreties; /&gt;
 * &lt;/bean&gt;
 * 
 * [使用系统属性配置远程文件]
 * java -Ddruid.config.file=file:///home/test/my.properties ...
 * 
 * 远程配置文件格式:
 * 1. 其他的属性KEY请查看 @see com.alibaba.druid.pool.DruidDataSourceFactory
 * 2. config filter 相关设置:
 * #远程文件路径
 * config.file=http://xxxxx(http://开头或者file:开头)
 * 
 * #RSA解密, Key不指定, 使用默认的
 * config.decrypt=true
 * config.decrypt.key=密钥字符串
 * config.decrypt.keyFile=密钥文件路径
 * config.decrypt.x509File=证书路径
 * 
 * </pre>
 * 
 * @author Jonas Yang
 */
public class ConfigFilter extends FilterAdapter {

    private static Log         LOG                     = LogFactory.getLog(ConfigFilter.class);

    public static final String CONFIG_FILE             = "config.file";
    public static final String CONFIG_DECRYPT          = "config.decrypt";
    public static final String CONFIG_KEY              = "config.decrypt.key";

    public static final String SYS_PROP_CONFIG_FILE    = "druid.config.file";
    public static final String SYS_PROP_CONFIG_DECRYPT = "druid.config.decrypt";
    public static final String SYS_PROP_CONFIG_KEY     = "druid.config.decrypt.key";

    public ConfigFilter(){
    }

    /**
     * 初始化
     * @param dataSourceProxy Druid DataSource
     */
    public void init(DataSourceProxy dataSourceProxy) {
        if (!(dataSourceProxy instanceof DruidDataSource)) {
            LOG.error("ConfigLoader only support DruidDataSource");
        }

        DruidDataSource dataSource = (DruidDataSource) dataSourceProxy;
        //数据源连接参数
        Properties connectionProperties = dataSource.getConnectProperties();
        //加载配置文件
        Properties configFileProperties = loadPropertyFromConfigFile(connectionProperties);

        // 判断是否配置进行了加密
        boolean decrypt = isDecrypt(connectionProperties, configFileProperties);

        if (configFileProperties == null) {
            if (decrypt) {
                //解密
                decrypt(dataSource, null);
            }
            return;
        }

        //如果配置文件不为空，则进行解密，并且将配置文件内容配置到数据源
        if (decrypt) {
            //解密
            decrypt(dataSource, configFileProperties);
        }

        try {
            DruidDataSourceFactory.config(dataSource, configFileProperties);
        } catch (SQLException e) {
            throw new IllegalArgumentException("Config DataSource error.", e);
        }
    }

    /**
     * 判断是否进行了加密
     * @param connectionProperties 程序自带的配置文件的属性
     * @param configFileProperties config.file配置文件配置的属性
     */
    public boolean isDecrypt(Properties connectionProperties, Properties configFileProperties) {
        //程序自带的配置config.decrypt是否为true
        String decrypterId = connectionProperties.getProperty(CONFIG_DECRYPT);
        if (decrypterId == null || decrypterId.length() == 0) {
            if (configFileProperties != null) {
                //config.file配置文件的config.decrypt是否为true
                decrypterId = configFileProperties.getProperty(CONFIG_DECRYPT);
            }
        }

        if (decrypterId == null || decrypterId.length() == 0) {
            //系统环境变量druid.config.decrypt是否为true
            decrypterId = System.getProperty(SYS_PROP_CONFIG_DECRYPT);
        }

        return Boolean.valueOf(decrypterId);
    }

    /**
     * 获取配置文件属性
     * @param connectionProperties 数据源配置属性
     * @return 配置文件属性
     */
    Properties loadPropertyFromConfigFile(Properties connectionProperties) {
        //数据源配置获取config.file
        String configFile = connectionProperties.getProperty(CONFIG_FILE);

        if (configFile == null) {
            //系统环境变量获取config.file
            configFile = System.getProperty(SYS_PROP_CONFIG_FILE);
        }

        if (configFile != null && configFile.length() > 0) {
            if (LOG.isInfoEnabled()) {
                LOG.info("DruidDataSource Config File load from : " + configFile);
            }

            //加载配置文件
            Properties info = loadConfig(configFile);

            if (info == null) {
                throw new IllegalArgumentException("Cannot load remote config file from the [config.file=" + configFile
                                                   + "].");
            }

            return info;
        }

        return null;
    }

    public void decrypt(DruidDataSource dataSource, Properties info) {

        try {
            String encryptedPassword = null;
            if (info != null) {
                //从配置文件中获取数据源密码
                encryptedPassword = info.getProperty(DruidDataSourceFactory.PROP_PASSWORD);
            }

            if (encryptedPassword == null || encryptedPassword.length() == 0) {
                //从数据源的配置中获取数据源密码
                encryptedPassword = dataSource.getConnectProperties().getProperty(DruidDataSourceFactory.PROP_PASSWORD);
            }

            if (encryptedPassword == null || encryptedPassword.length() == 0) {
                //直接获取数据源密码
                encryptedPassword = dataSource.getPassword();
            }

            //获取公钥
            PublicKey publicKey = getPublicKey(dataSource.getConnectProperties(), info);

            //通过公钥对数据源密码解密
            String passwordPlainText = ConfigTools.decrypt(publicKey, encryptedPassword);

            //把解密后的密码设置到配置属性
            if (info != null) {
                info.setProperty(DruidDataSourceFactory.PROP_PASSWORD, passwordPlainText);
            } else {
                dataSource.setPassword(passwordPlainText);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to decrypt.", e);
        }
    }

    /**
     * 获取公钥
     * @param connectionProperties 程序自带的配置文件的属性
     * @param configFileProperties config.file配置文件配置的属性
     */
    public PublicKey getPublicKey(Properties connectionProperties, Properties configFileProperties) {
        String key = null;
        if (configFileProperties != null) {
            //配置文件获取公钥(config.decrypt.key)
            key = configFileProperties.getProperty(CONFIG_KEY);
        }

        if (StringUtils.isEmpty(key) && connectionProperties != null) {
            //数据源配置获取公钥(config.decrypt.key)
            key = connectionProperties.getProperty(CONFIG_KEY);
        }

        if (StringUtils.isEmpty(key)) {
            //如果公钥为空，则尝试从系统环境变量获取(druid.config.decrypt.key)
            key = System.getProperty(SYS_PROP_CONFIG_KEY);
        }

        return ConfigTools.getPublicKey(key);
    }

    /**
     * 加载配置文件
     * @param filePath 文件路径
     * @return 配置属性
     */
    public Properties loadConfig(String filePath) {
        Properties properties = new Properties();

        InputStream inStream = null;
        try {
            //是否是xml文件
            boolean xml = false;
            //如果文件路径是文件协议
            if (filePath.startsWith("file://")) {
                //去掉协议前缀
                filePath = filePath.substring("file://".length());
                inStream = getFileAsStream(filePath);
                xml = filePath.endsWith(".xml");
            } else if (filePath.startsWith("http://") || filePath.startsWith("https://")) { //如果文件路径是http网络协议
                //网络请求得到输入流
                URL url = new URL(filePath);
                inStream = url.openStream();
                xml = url.getPath().endsWith(".xml");
            } else if (filePath.startsWith("classpath:")) { //如果文件路径是classpath协议
                //去掉协议前缀
                String resourcePath = filePath.substring("classpath:".length());
                //用当前线程上下文类加载器去加载这个文件
                inStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
                // 在classpath下应该也可以配置xml文件吧？
                xml = resourcePath.endsWith(".xml");
            } else {
                //直接获取文件输入流
                inStream = getFileAsStream(filePath);
                xml = filePath.endsWith(".xml");
            }

            if (inStream == null) {
                LOG.error("load config file error, file : " + filePath);
                return null;
            }

            if (xml) {
                //如果是xml文件
                properties.loadFromXML(inStream);
            } else {
                properties.load(inStream);
            }

            return properties;
        } catch (Exception ex) {
            LOG.error("load config file error, file : " + filePath, ex);
            return null;
        } finally {
            //关闭输入流
            JdbcUtils.close(inStream);
        }
    }

    /**
     * 根据文件路径获取输入流
     */
    private InputStream getFileAsStream(String filePath) throws FileNotFoundException {
        InputStream inStream = null;
        File file = new File(filePath);
        if (file.exists()) {
            inStream = new FileInputStream(file);
        } else {
            inStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(filePath);
        }
        return inStream;
    }
}
