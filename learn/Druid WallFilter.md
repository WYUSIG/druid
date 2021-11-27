## Druid源码学习系列

上一篇文章我们了解了StatFilter，今天我们来看一下同样是Druid中很重要的一个Filter: WallFilter

WallFilter主要功能是防sql注入，那么我们来看一下它是如何进行检查的

```java
public class WallConfig implements WallConfigMBean {

    private boolean noneBaseStatementAllow = false;

    //是否允许执行存储过程，默认允许
    private boolean callAllow = true;
    //是否允许执行select语句，默认允许
    private boolean selelctAllow = true;
    //是否允许select语句中包含into，默认允许
    private boolean selectIntoAllow = true;
    private boolean selectIntoOutfileAllow = false;
    //是否允许select语句的where子句为永真，默认允许
    private boolean selectWhereAlwayTrueCheck = true;
    //是否允许select语句的having子句为永真，默认允许
    private boolean selectHavingAlwayTrueCheck = true;
    private boolean selectUnionCheck = true;
    private boolean selectMinusCheck = true;
    private boolean selectExceptCheck = true;
    private boolean selectIntersectCheck = true;
    //是否允许执行create table语句，默认允许
    private boolean createTableAllow = true;
    //是否允许执行drop table语句，默认允许
    private boolean dropTableAllow = true;
    //是否允许执行alter table语句，默认允许
    private boolean alterTableAllow = true;
    //是否允许执行rename table语句，默认允许
    private boolean renameTableAllow = true;
    private boolean hintAllow = true;
    //是否允许lock table，默认允许
    private boolean lockTableAllow = true;
    //是否允许begin开启新事务，默认允许
    private boolean startTransactionAllow = true;
    private boolean blockAllow = true;
    //是否允许where，having子句中是否包含and永真条件，默认不允许
    private boolean conditionAndAlwayTrueAllow = false;
    //是否允许where，having子句中是否包含and永假条件，默认不允许
    private boolean conditionAndAlwayFalseAllow = false;
    private boolean conditionDoubleConstAllow = false;
    //是否允许where，having子句中是否包含like永真条件, 默认允许
    private boolean conditionLikeTrueAllow = true;
    //是否允许select *查询所有列，默认允许
    private boolean selectAllColumnAllow = true;
    //是否允许执行delete语句
    private boolean deleteAllow = true;
    //是否进行delete语句的where含有永真条件检查，默认进行检查，不允许出现
    private boolean deleteWhereAlwayTrueCheck = true;
    //是否进行delete语句没有where检查，默认不检查
    private boolean deleteWhereNoneCheck = false;
    //是否允许执行update语句
    private boolean updateAllow = true;
    //是否进行update语句中的where含有永真条件检查，默认进行检查，不允许出现
    private boolean updateWhereAlayTrueCheck = true;
    //是否进行update没有where检查，默认不检查
    private boolean updateWhereNoneCheck = false;
    //是否允许执行insert语句，默认允许
    private boolean insertAllow = true;
    private boolean mergeAllow = true;
    private boolean minusAllow = true;
    private boolean intersectAllow = true;
    //是否允许执行replace语句，默认允许
    private boolean replaceAllow = true;
    //是否允许执行set语句，如set global xxx = , 默认允许
    private boolean setAllow = true;
    //是否允许执行commit语句，默认允许
    private boolean commitAllow = true;
    //是否允许执行rollback,默认允许
    private boolean rollbackAllow = true;
    //是否允许执行use语句，默认允许
    private boolean useAllow = true;

    private boolean multiStatementAllow = false;

    //是否允许执行truncate语句，默认允许
    private boolean truncateAllow = true;
    //是否允许执行comment语句，默认不允许
    private boolean commentAllow = false;
    private boolean strictSyntaxCheck = true;
    private boolean constArithmeticAllow = true;
    private boolean limitZeroAllow = false;

    private boolean describeAllow = true;
    //是否允许执行show语句，默认允许
    private boolean showAllow = true;

    public WallConfig(String dir){
        this();
        this.dir = dir;
        this.init();
    }
}
```

之前我们说过，在WallConfig里面有大量自定义sql检查规则，我们只需要构造自己的WallConfig，打开wall filter即可，下面我们来看一下WallFilter代码

```java
public class WallFilter extends FilterAdapter implements WallFilterMBean {

    ...

    public WallFilter() {
        configFromProperties(System.getProperties());
    }

    /**
     * 加载配置
     */
    @Override
    public void configFromProperties(Properties properties) {
        {
            //是否打印违法的sql
            Boolean value = getBoolean(properties, "druid.wall.logViolation");
            if (value != null) {
                this.logViolation = value;
            }
        }
        {
            //出现违法sql是否抛出异常
            Boolean value = getBoolean(properties, "druid.wall.throwException");
            if (value != null) {
                this.throwException = value;
            }
        }

        if (this.config != null) {
            this.config.configFromProperties(properties);
        }
    }

    /**
     * 根据数据库类型初始化WallConfig
     */
    @Override
    public synchronized void init(DataSourceProxy dataSource) {

        if (dataSource == null) {
            LOG.error("dataSource should not be null");
            return;
        }

        if (this.dbTypeName == null || this.dbTypeName.trim().length() == 0) {
            if (dataSource.getDbType() != null) {
                this.dbTypeName = dataSource.getDbType();
            } else {
                this.dbTypeName = JdbcUtils.getDbType(dataSource.getRawJdbcUrl(), "");
            }
        }

        if (dbTypeName == null) {
            dbTypeName = JdbcUtils.getDbType(dataSource.getUrl(), null);
        }

        //获取数据库类型
        DbType dbType = DbType.of(this.dbTypeName);

        switch (dbType) {
            case mysql:
            case oceanbase:
            case drds:
            case mariadb:
            case h2:
            case presto:
            case trino:
                if (config == null) {
                    config = new WallConfig(MySqlWallProvider.DEFAULT_CONFIG_DIR);
                }

                provider = new MySqlWallProvider(config);
                break;
            case oracle:
            case ali_oracle:
            case oceanbase_oracle:
                if (config == null) {
                    config = new WallConfig(OracleWallProvider.DEFAULT_CONFIG_DIR);
                }

                provider = new OracleWallProvider(config);
                break;
            case sqlserver:
            case jtds:
                if (config == null) {
                    config = new WallConfig(SQLServerWallProvider.DEFAULT_CONFIG_DIR);
                }

                provider = new SQLServerWallProvider(config);
                break;
            case postgresql:
            case edb:
            case polardb:
            case greenplum:
            case gaussdb:
                if (config == null) {
                    config = new WallConfig(PGWallProvider.DEFAULT_CONFIG_DIR);
                }

                provider = new PGWallProvider(config);
                break;
            case db2:
                if (config == null) {
                    config = new WallConfig(DB2WallProvider.DEFAULT_CONFIG_DIR);
                }

                provider = new DB2WallProvider(config);
                break;
            case sqlite:
                if (config == null) {
                    config = new WallConfig(SQLiteWallProvider.DEFAULT_CONFIG_DIR);
                }

                provider = new SQLiteWallProvider(config);
                break;
            case clickhouse:
                if (config == null) {
                    config = new WallConfig(ClickhouseWallProvider.DEFAULT_CONFIG_DIR);
                }
                provider = new ClickhouseWallProvider(config);
                break;
            default:
                throw new IllegalStateException("dbType not support : " + dbType + ", url " + dataSource.getUrl());
        }

        provider.setName(dataSource.getName());

        this.inited = true;
    }

    ...

    /**
     * Statement#addBatch回调，检查sql
     */
    @Override
    public void statement_addBatch(FilterChain chain, StatementProxy statement, String sql) throws SQLException {
        createWallContext(statement);
        try {
            //检查sql
            sql = check(sql);
            //下一个filter处理
            chain.statement_addBatch(statement, sql);
        } finally {
            WallContext.clearContext();
        }
    }
}
```

跟其他Filter一样都是在configFromProperties加载配置，init进行初始化，在Statement#addBatch的回调方法中我们可以看到，调用了check(sql)来进行sql检查

我们来看一下里面的代码

```java
public class WallFilter extends FilterAdapter implements WallFilterMBean {
    public String check(String sql) throws SQLException {
        return checkInternal(sql)
                .getSql();
    }

    private WallCheckResult checkInternal(String sql) throws SQLException {
        //检查sql，拿到结果
        WallCheckResult checkResult = provider.check(sql);
        //从结果中取出sql违法原因列表
        List<Violation> violations = checkResult.getViolations();
        //如果SQL违法
        if (violations.size() > 0) {
            //取出第一个违法的信息
            Violation firstViolation = violations.get(0);
            //如果配置打印sql违法信息
            if (isLogViolation()) {
                LOG.error("sql injection violation, dbType "
                        + getDbType()
                        + ", druid-version "
                        + VERSION.getVersionNumber()
                        + ", "
                        + firstViolation.getMessage() + " : " + sql);
            }

            //如果配置遇到违法sql抛出异常
            if (throwException) {
                if (violations.get(0) instanceof SyntaxErrorViolation) {
                    SyntaxErrorViolation violation = (SyntaxErrorViolation) violations.get(0);
                    throw new SQLException("sql injection violation, dbType "
                            + getDbType() + ", "
                            + ", druid-version "
                            + VERSION.getVersionNumber()
                            + ", "
                            + firstViolation.getMessage() + " : " + sql,
                            violation.getException());
                } else {
                    throw new SQLException("sql injection violation, dbType "
                            + getDbType()
                            + ", druid-version "
                            + VERSION.getVersionNumber()
                            + ", "
                            + firstViolation.getMessage()
                            + " : " + sql);
                }
            }
        }
        //返回检查结果
        return checkResult;
    }
}
```

从里面的细节可以主要是调用WallProvider#check进行检查，从而获取sql非法信息列表，我们继续往下追

```java
public abstract class WallProvider {
    public WallCheckResult check(String sql) {
        //获得当前防火墙上下文
        WallContext originalContext = WallContext.current();

        try {
            //如果上下文为空，则进行创建
            WallContext.createIfNotExists(dbType);
            return checkInternal(sql);
        } finally {
            if (originalContext == null) {
                WallContext.clearContext();
            }
        }
    }

    private WallCheckResult checkInternal(String sql) {
        //检查数量++
        checkCount.incrementAndGet();
        //获取当前防火墙上下文
        WallContext context = WallContext.current();
        //如果允许自定义校验且已经自定义校验过，直接返回
        if (config.isDoPrivilegedAllow() && ispPrivileged()) {
            WallCheckResult checkResult = new WallCheckResult();
            checkResult.setSql(sql);
            return checkResult;
        }

        //如果白名单不为空
        boolean mulltiTenant = config.getTenantTablePattern() != null && config.getTenantTablePattern().length() > 0;
        if (!mulltiTenant) {
            //检查黑白名单
            WallCheckResult checkResult = checkWhiteAndBlackList(sql);
            //如果黑白名单找到，直接返回
            if (checkResult != null) {
                checkResult.setSql(sql);
                return checkResult;
            }
        }
        //深度检查数++
        hardCheckCount.incrementAndGet();
        final List<Violation> violations = new ArrayList<Violation>();
        List<SQLStatement> statementList = new ArrayList<SQLStatement>();
        boolean syntaxError = false;
        boolean endOfComment = false;
        try {
            SQLStatementParser parser = createParser(sql);
            parser.getLexer().setCommentHandler(WallCommentHandler.instance);

            if (!config.isCommentAllow()) {
                parser.getLexer().setAllowComment(false); // deny comment
            }
            if (!config.isCompleteInsertValuesCheck()) {
                parser.setParseCompleteValues(false);
                parser.setParseValuesSize(config.getInsertValuesCheckSize());
            }

            parser.parseStatementList(statementList);

            //最后一个单词
            final Token lastToken = parser.getLexer().token();
            if (lastToken != Token.EOF && config.isStrictSyntaxCheck()) {
                violations.add(new IllegalSQLObjectViolation(ErrorCode.SYNTAX_ERROR, "not terminal sql, token "
                        + lastToken, sql));
            }
            endOfComment = parser.getLexer().isEndOfComment();
        } catch (NotAllowCommentException e) {
            violations.add(new IllegalSQLObjectViolation(ErrorCode.COMMENT_STATEMENT_NOT_ALLOW, "comment not allow", sql));
            incrementCommentDeniedCount();
        } catch (ParserException e) {
            syntaxErrorCount.incrementAndGet();
            syntaxError = true;
            if (config.isStrictSyntaxCheck()) {
                violations.add(new SyntaxErrorViolation(e, sql));
            }
        } catch (Exception e) {
            if (config.isStrictSyntaxCheck()) {
                violations.add(new SyntaxErrorViolation(e, sql));
            }
        }

        //如果有多条sql而且配置不允许多条sql
        if (statementList.size() > 1 && !config.isMultiStatementAllow()) {
            //添加违法信息
            violations.add(new IllegalSQLObjectViolation(ErrorCode.MULTI_STATEMENT, "multi-statement not allow", sql));
        }

        //创建防火墙的sql访问器
        WallVisitor visitor = createWallVisitor();
        visitor.setSqlEndOfComment(endOfComment);

        if (statementList.size() > 0) {
            boolean lastIsHint = false;
            //遍历每一条sql
            for (int i=0; i<statementList.size(); i++) {
                SQLStatement stmt = statementList.get(i);
                if ((i == 0 || lastIsHint) && stmt instanceof MySqlHintStatement) {
                    lastIsHint = true;
                    continue;
                }
                try {
                    stmt.accept(visitor);
                } catch (ParserException e) {
                    //发生异常则为遇到非法sql
                    violations.add(new SyntaxErrorViolation(e, sql));
                }
            }
        }

        if (visitor.getViolations().size() > 0) {
            violations.addAll(visitor.getViolations());
        }

        ...

        return result;
    }
}
```

在这里我们可以看到一个黑白名单设计，它们是两个LRU缓存，记录了短期的非法、合法sql及检查信息，主要是为了快速判断sql是否违法

同是我们可以看到一个关键的类WallVisitor对象，它是自定义的sql解析器访问视图，允许我们自定义访问逻辑，获取到sql的各个部分

其中mysql数据库类型对应的实现类是MySqlWallVisitor

```java
public class MySqlWallVisitor extends WallVisitorBase implements WallVisitor, MySqlASTVisitor {
    public MySqlWallVisitor(WallProvider provider) {
        super(provider);
    }

    @Override
    public boolean visit(MySqlInsertStatement x) {
        return visit((SQLInsertStatement) x);
    }
    
    default boolean visit(SQLInsertStatement x) {
        WallVisitorUtils.initWallTopStatementContext();
        WallVisitorUtils.checkInsert(this, x);

        return true;
    }
}
public class WallVisitorUtils {
    public static void checkInsert(WallVisitor visitor, SQLInsertInto x) {
        checkReadOnly(visitor, x.getTableSource());

        if (!visitor.getConfig().isInsertAllow()) {
            addViolation(visitor, ErrorCode.INSERT_NOT_ALLOW, "insert not allow", x);
        }

        checkInsertForMultiTenant(visitor, x);
    }
}
```

里面主要是调用根据sql类型不同，调用WallVisitorUtils，判断配置是否允许此类sql，如果不允许则添加到非法信息到violationList,最后返回给上层方法