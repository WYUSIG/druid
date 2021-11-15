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
package com.alibaba.druid.pool;

import com.alibaba.druid.wall.WallFilter;

import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;

public class DruidTest {

    private static DruidDataSource DS;

    public DruidTest(String connectURI) throws SQLException {
        initDS(connectURI);
    }

    public DruidTest(String connectURI, String username, String pswd, String driverClass, int initialSize,
                     int maxActive, int maxIdle, int minIdle, int maxWait) throws SQLException {
        initDS(connectURI, username, pswd, driverClass, initialSize, maxActive, minIdle, maxIdle, maxWait);
    }

    public Connection getConn() {
        Connection con = null;
        if (DS != null) {
            try {
                con = DS.getConnection();
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }

            try {
                con.setAutoCommit(false);
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return con;
        }
        return con;
    }

    public static void initDS(String connectURI, String username, String pswd, String driverClass, int initialSize,
                              int maxActive, int maxIdle, int minIdle, int maxWait) throws SQLException {
        DruidDataSource ds = new DruidDataSource();
        //驱动类名
        ds.setDriverClassName(driverClass);
        //数据库用户名
        ds.setUsername(username);
        //数据库密码
        ds.setPassword(pswd);
        //数据库jdbc链接
        ds.setUrl(connectURI);
        //初始的连接数
        ds.setInitialSize(initialSize);
        //最大连接数
        ds.setMaxActive(maxActive);
        //最小连接数
        ds.setMinIdle(minIdle);
        //最大等待线程数
        ds.setMaxWait(maxWait);
        //设置filter，这里设置了防火墙的，多个filter英文都好隔开
        ds.setFilters("wall");
        //获取防火墙filter
        WallFilter wallFilter = (WallFilter) ds.getProxyFilters().get(0);
        //自定义sql防注入规则
        wallFilter.getConfig().setInsertAllow(false);
        DS = ds;
    }

    public static void initDS(String connectURI) throws SQLException {
        initDS(connectURI, "root", "", "com.mysql.jdbc.Driver", 40, 40, 40, 10, 5);
    }

    public static void main(String[] args) throws IOException, SQLException {

        DruidTest db = new DruidTest("jdbc:mysql://localhost:3306/amoeba?serverTimezone=CTT");
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        FileWriter fileWriter = new FileWriter("D:\\data.txt");
        try {
            conn = db.getConn();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

        long sum = 0;
        for (int i = 1; i < 10; i++) {
            try {
                stmt = conn.createStatement();
                Date start = new Date();
                rs = stmt.executeQuery("select * from offer where member_id = 'forwd251'");
                Date end = new Date();
                sum = sum + (end.getTime() - start.getTime());
                fileWriter.write(String.valueOf((end.getTime() - start.getTime())));
                fileWriter.write("/\n");

            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        System.out.println((float) sum / 10);

        conn.close();
        stmt.close();
        rs.close();
        fileWriter.flush();
        fileWriter.close();
        try {
            Thread.sleep(100000000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}
