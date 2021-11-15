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
package com.alibaba.druid.bvt.filter.wall;

import junit.framework.TestCase;

import org.junit.Assert;

import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallUtils;

public class WallInsertTest extends TestCase {
    //insert语句
    private String sql = "INSERT INTO T (F1, F2) VALUES (1, 2)";

    //new一个防火墙配置
    private WallConfig config = new WallConfig();

    //单元测试前置方法
    protected void setUp() throws Exception {
        //把防火墙配置的是否允许执行insert语句改成不允许
        config.setInsertAllow(false);
    }

    public void testMySql() throws Exception {
        //测试，期望结果为false，语句不合法
        Assert.assertFalse(WallUtils.isValidateMySql(sql, config));
    }
    
    public void testORACLE() throws Exception {
        
        Assert.assertFalse(WallUtils.isValidateOracle(sql, config));
    }
}
