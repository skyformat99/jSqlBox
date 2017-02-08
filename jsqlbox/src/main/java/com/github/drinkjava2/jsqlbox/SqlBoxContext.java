/**
 * Copyright (C) 2016 Yong Zhu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.drinkjava2.jsqlbox;

import static com.github.drinkjava2.jsqlbox.SqlBoxException.throwEX;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @author Yong Zhu
 * @version 1.0.0
 * @since 1.0.0
 */
@SuppressWarnings("unchecked")
public class SqlBoxContext {
	private static final SqlBoxLogger log = SqlBoxLogger.getLog(SqlBoxContext.class);
	private static SqlBoxContext defaultSqlBoxContext;

	// print SQL to console or log depends logging.properties
	private Boolean showSql = false;
	private Boolean formatSql = false;

	public static final String SQLBOX_IDENTITY = "BX";

	private JdbcTemplate jdbc = new JdbcTemplate();
	private DataSource dataSource = null;

	private DBMetaData metaData;

	/**
	 * Store paging pageNumber in ThreadLocal
	 */
	protected static ThreadLocal<String> paginationEndCache = new ThreadLocal<String>() {
		@Override
		protected String initialValue() {
			return null;
		}
	};

	/**
	 * Store order by SQL piece, only needed for SQL Server 2005 and later
	 */
	protected static ThreadLocal<String> paginationOrderByCache = new ThreadLocal<String>() {
		@Override
		protected String initialValue() {
			return null;
		}
	};

	/**
	 * Store boxes binded on entities
	 */
	protected static ThreadLocal<Map<Object, SqlBox>> boxCache = new ThreadLocal<Map<Object, SqlBox>>() {
		@Override
		protected Map<Object, SqlBox> initialValue() {
			return new HashMap<>();
		}
	};

	public static final ThreadLocal<HashMap<Object, Object>> classExistCache = new ThreadLocal<HashMap<Object, Object>>() {
		@Override
		protected HashMap<Object, Object> initialValue() {
			return new HashMap<>();
		}
	};

	protected static ThreadLocal<Integer> circleDependencyCache = new ThreadLocal<Integer>() {
		@Override
		protected Integer initialValue() {
			return 0;
		}
	};

	public SqlBoxContext() {
		// Default constructor
	}

	/**
	 * Create a SqlBoxContext and register dataSoruce & DB class
	 */
	public SqlBoxContext(DataSource dataSource) {
		this.dataSource = dataSource;
		if (dataSource != null) {
			this.jdbc.setDataSource(dataSource);
			refreshMetaData();
		}
	}

	public static SqlBoxContext getDefaultSqlBoxContext() {
		if (defaultSqlBoxContext == null)
			defaultSqlBoxContext = new SqlBoxContext();
		return defaultSqlBoxContext;

	}

	// ================== getter & setters below============
	public DataSource getDataSource() {
		return dataSource;
	}

	/**
	 * Set DataSource for SqlBoxContext
	 */
	public SqlBoxContext setDataSource(DataSource dataSource) {
		this.dataSource = dataSource;
		this.jdbc.setDataSource(dataSource);
		refreshMetaData();
		return this;
	}

	public Boolean getShowSql() {
		return showSql;
	}

	public SqlBoxContext setShowSql(Boolean showSql) {
		this.showSql = showSql;
		return this;
	}

	public Boolean getFormatSql() {
		return formatSql;
	}

	public SqlBoxContext setFormatSql(Boolean formatSql) {
		this.formatSql = formatSql;
		return this;
	}

	public JdbcTemplate getJdbc() {
		return jdbc;
	}

	public SqlBoxContext setJdbc(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
		return this;
	}

	public DBMetaData getMetaData() {
		return metaData;
	}

	public SqlBoxContext setMetaData(DBMetaData metaData) {
		this.metaData = metaData;
		return this;
	}

	/**
	 * Put a box instance into thread local cache for a bean
	 */
	public void bind(Object bean, SqlBox box) {
		if (bean == null)
			throwEX("SqlBoxContext putBox error, entityBean can not be null");
		else {
			box.setEntityBean(bean);
			box.setEntityClass(bean.getClass());
			boxCache.get().put(bean, box);
		}
	}

	/**
	 * Get a box instance from thread local cache for a bean
	 */
	public static SqlBox getBindedBox(Object bean) {
		if (bean == null)
			return (SqlBox) throwEX("SqlBoxContext putBox error, entityBean can not be null");
		else
			return boxCache.get().get(bean);
	}

	/**
	 * Set default SqlBoxContext
	 */
	public static <T> void setDefaultSqlBoxContext(T sqlBoxContext) {
		defaultSqlBoxContext = (SqlBoxContext) sqlBoxContext;
	}

	/**
	 * Release resources (DataSource handle), usually no need call this method except use multiple SqlBoxContext
	 */
	public void close() {
		this.dataSource = null;
		this.metaData = null;
		this.showSql = false;
	}

	/**
	 * Get a box instance from thread local cache for a bean
	 */
	public SqlBox getBox(Object bean) {
		SqlBox box = getBindedBox(bean);
		if (box != null)
			return box;
		box = findAndBuildSqlBox(bean.getClass());
		SqlBoxContext.bindBoxToBean(bean, box);
		return box;
	}

	/**
	 * Get a box instance from thread local cache for a bean
	 */
	public static SqlBox getDefaultBox(Object bean) {
		SqlBox box = getBindedBox(bean);
		if (box != null)
			return box;
		box = defaultSqlBoxContext.findAndBuildSqlBox(bean.getClass());
		SqlBoxContext.bindBoxToBean(bean, box);
		return box;
	}

	/**
	 * Create an entity instance
	 */
	public <T> T createEntity(Class<?> entityOrBoxClass) {
		SqlBox box = findAndBuildSqlBox(entityOrBoxClass);
		Object bean = null;
		try {
			bean = box.getEntityClass().newInstance();
			// Trick here: if already used defaultBox (through its constructor or static block) then
			// change to use this context
			SqlBox box2 = getBindedBox(bean);
			if (box2 == null)
				bindBoxToBean(bean, box);
			else
				box2.setContext(this);
		} catch (Exception e) {
			SqlBoxException.throwEX(e, "SqlBoxContext create error");
		}
		return (T) bean;

	}

	public static void bindBoxToBean(Object bean, SqlBox box) {
		box.setEntityBean(bean);
		box.getSqlBoxContext().bind(bean, box);
	}

	/**
	 * Find and create a SqlBox instance according bean class or SqlBox Class
	 */
	public SqlBox findAndBuildSqlBox(Class<?> entityOrBoxClass) {
		Class<?> boxClass = null;
		if (entityOrBoxClass == null) {
			SqlBoxException.throwEX("SqlBoxContext findAndBuildSqlBox error! Bean Or SqlBox Class not set");
			return null;
		}
		if (SqlBox.class.isAssignableFrom(entityOrBoxClass))
			boxClass = entityOrBoxClass;
		if (boxClass == null)
			boxClass = SqlBoxUtils.checkSqlBoxClassExist(entityOrBoxClass.getName() + SQLBOX_IDENTITY);
		if (boxClass == null)
			boxClass = SqlBoxUtils.checkSqlBoxClassExist(
					entityOrBoxClass.getName() + "$" + entityOrBoxClass.getSimpleName() + SQLBOX_IDENTITY);
		SqlBox box = null;
		if (boxClass == null) {
			box = new SqlBox(this);
			box.setEntityClass(entityOrBoxClass);
		} else {
			try {
				box = (SqlBox) boxClass.newInstance();
				if (box.getEntityClass() == null)
					box.setEntityClass(entityOrBoxClass);
				box.setContext(this);
			} catch (Exception e) {
				SqlBoxException.throwEX(e,
						"SqlBoxContext findAndBuildSqlBox error! Can not create SqlBox instance: " + entityOrBoxClass);
			}
		}
		return box;
	}

	/**
	 * Find real table name from database meta data
	 */
	protected String findRealTableName(String tableName) {
		String realTableName;
		DBMetaData meta = this.getMetaData();
		realTableName = meta.getTableNames().get(tableName.toLowerCase());
		if (!SqlBoxUtils.isEmptyStr(realTableName))
			return realTableName;
		realTableName = meta.getTableNames().get(tableName.toLowerCase() + 's');
		if (!SqlBoxUtils.isEmptyStr(realTableName))
			return realTableName;
		return null;
	}

	public DatabaseType getDatabaseType() {
		return this.getMetaData().getDatabaseType();
	}

	public void refreshMetaData() {
		this.metaData = DBMetaData.getMetaData(this);
	}

	// ========JdbcTemplate wrap methods begin============
	/**
	 * Print SQL and parameters to console, usually used for debug <br/>
	 * Use context.setShowSql to control, Default showSql is "false"
	 */
	protected void logSql(SqlAndParameters sp) {
		// check if allowed print SQL
		if (!this.getShowSql())
			return;
		StringBuilder sb = new StringBuilder(sp.getSql());
		Object[] args = sp.getParameters();
		if (args.length > 0) {
			sb.append("\r\nParameters: ");
			for (int i = 0; i < args.length; i++) {
				sb.append("" + args[i]);
				if (i != args.length - 1)
					sb.append(",");
				else
					sb.append("\r\n");
			}
		}
		String sql = sb.toString();
		if (getFormatSql())
			sql = SqlBoxUtils.formatSQL(sql);
		log.info(sql);
	}

	private void logCachedSQL(List<List<SqlAndParameters>> subSPlist) {
		if (this.getShowSql()) {
			if (subSPlist != null) {
				List<SqlAndParameters> l = subSPlist.get(0);
				if (l != null) {
					SqlAndParameters sp = l.get(0);
					log.info("First Cached SQL:");
					logSql(sp);
				}
			}
			if (subSPlist != null) {
				List<SqlAndParameters> l = subSPlist.get(subSPlist.size() - 1);
				if (l != null) {
					SqlAndParameters sp = l.get(l.size() - 1);
					log.info("Last Cached SQL:");
					logSql(sp);
				}
			}
		}
	}

	// Only wrap some common used JdbcTemplate methods
	public Integer queryForInteger(String... sql) {
		return this.queryForObject(Integer.class, sql);
	}

	/**
	 * Return String type query result, sql be translated to prepared statement
	 */
	public String queryForString(String... sql) {
		return this.queryForObject(String.class, sql);
	}

	/**
	 * Return Object type query result, sql be translated to prepared statement
	 */
	public <T> T queryForObject(Class<?> clazz, String... sql) {
		SqlAndParameters sp = SqlHelper.prepareSQLandParameters(sql);
		logSql(sp);
		if (sp.getParameters().length != 0)
			return (T) getJdbc().queryForObject(sp.getSql(), sp.getParameters(), clazz);
		else {
			try {
				return (T) getJdbc().queryForObject(sp.getSql(), clazz);
			} catch (EmptyResultDataAccessException e) {
				SqlBoxException.eatException(e);
				return null;
			}
		}
	}

	/**
	 * Cache SQL in memory for executeCachedSQLs call, sql be translated to prepared statement
	 * 
	 * @param sql
	 */
	public void cacheSQL(String... sql) {
		SqlHelper.cacheSQL(sql);
	}

	/**
	 * Execute sql and return how many record be affected, sql be translated to prepared statement<br/>
	 * Return -1 if no parameters sql executed<br/>
	 * 
	 */
	public Integer execute(String... sql) {
		SqlAndParameters sp = SqlHelper.prepareSQLandParameters(sql);
		logSql(sp);
		if (sp.getParameters().length != 0)
			return getJdbc().update(sp.getSql(), sp.getParameters());
		else {
			getJdbc().execute(sp.getSql());
			return -1;
		}
	}

	/**
	 * Execute sql and return how many record be affected, sql be translated to prepared statement<br/>
	 * Return -1 if no parameters sql executed<br/>
	 * 
	 */
	public Integer executeInsert(String... sql) {
		SqlAndParameters sp = SqlHelper.prepareSQLandParameters(sql);
		logSql(sp);
		if (sp.getParameters().length != 0)
			return getJdbc().update(sp.getSql(), sp.getParameters());
		else {
			getJdbc().execute(sp.getSql());
			return -1;
		}
	}

	/**
	 * Execute sql without exception threw, return -1 if no parameters sql executed, return -2 if exception found
	 */
	public Integer executeQuiet(String... sql) {
		try {
			return execute(sql);
		} catch (Exception e) {
			SqlBoxException.eatException(e);
			return -2;
		}
	}

	/**
	 * Transfer cached SQLs to Prepared Statement and batch execute these SQLs
	 */
	public void executeCachedSQLs() {
		String sql = SqlHelper.getAndClearBatchSqlString();
		List<List<SqlAndParameters>> subSPlist = SqlHelper.getAndClearBatchSQLs();
		logCachedSQL(subSPlist);
		for (List<SqlAndParameters> splist : subSPlist) {
			getJdbc().batchUpdate(sql, new BatchPreparedStatementSetter() {
				@Override
				public void setValues(PreparedStatement ps, int i) throws SQLException {
					SqlAndParameters sp = splist.get(i);
					int index = 1;
					for (Object parameter : sp.getParameters()) {
						ps.setObject(index++, parameter);
					}
				}

				@Override
				public int getBatchSize() {
					return splist.size();
				}
			});
		}
	}

	/**
	 * Store "order by xxx desc" in ThreadLocal, return "", this is for MSSQL2005+ only <br/>
	 */
	public String orderBy(String... orderBy) {
		StringBuilder sb = new StringBuilder(" order by ");
		for (String str : orderBy)
			sb.append(str);
		if (this.getDatabaseType().isMsSQLSERVER()) {
			paginationOrderByCache.set(sb.toString());
			return " ";
		} else
			return sb.toString();
	}

	/**
	 * Return pagination SQL depends different database type <br/>
	 */
	public String pagination(int pageNumber, int pageSize) {
		String start;
		String end;
		if (this.getDatabaseType().isH2() || this.getDatabaseType().isMySql()) {
			start = " ";
			end = " limit " + (pageNumber - 1) * pageSize + ", " + pageSize + " ";
		} else if (this.getDatabaseType().isMsSQLSERVER()) {
			// For SQL Server 2005 and later
			start = " a_tb.* from (select row_number() over(__orderby__) as rownum, ";
			end = ") as a_tb where rownum between " + ((pageNumber - 1) * pageSize + 1) + " and "
					+ pageNumber * pageSize + " ";
			/**
			 * For SqlServer 2012 and later can also use <br/>
			 * start = " "; <br/>
			 * end = " offset " + (pageNumber - 1) * pageSize + " rows fetch next " + pageSize + " rows only ";
			 */
		} else if (this.getDatabaseType().isOracle()) {
			start = " * FROM (SELECT a_tb.*, ROWNUM r_num FROM ( SELECT ";
			end = " ) a_tb WHERE ROWNUM <= " + pageNumber * pageSize + ") WHERE r_num > " + (pageNumber - 1) * pageSize
					+ " ";
		} else
			return (String) SqlBoxException.throwEX("pagination error: so far do not support this database.");
		paginationEndCache.set(end);
		return start;
	}

	/**
	 * For circle dependency check
	 */
	private int increaseCircleDependency() {
		int count = circleDependencyCache.get();
		circleDependencyCache.set(count + 1);
		if (count > 2000)
			SqlBoxException.throwEX("Error: circle dependency or too deep tree(>2000).");
		return count;
	}

	/**
	 * Here build Child Entity List
	 */
	private List<Map<String, Object>> buildChildEntity(Entity parent, List<Map<String, Object>> list,
			List<Mapping> mappingList) {
		int count = increaseCircleDependency();

		List<Map<String, Object>> thisList = new ArrayList<Map<String, Object>>();
		// find direct nodes which parent is parent
		// thisList

		// TODO add real code here to fill the thisList

		circleDependencyCache.set(count);
		return thisList;
	}

	/**
	 * Here build Root Entity List
	 */
	private <T> List<T> buildRootEntity(Mapping rootMapping, List<Map<String, Object>> list,
			List<Mapping> mappingList) {
		Entity root = (Entity) rootMapping.getThisEntity();
		String rootFieldID = rootMapping.getThisField();
		List<T> resultList = new ArrayList<>();

		String rootAliasColumnName = root.aliasByFieldID(rootFieldID).toUpperCase();
		Map<Object, Map<String, Object>> rootValueMap = new HashMap<>();

		for (Map<String, Object> m : list) {// get the unique root entities
			Object value = m.get(rootAliasColumnName);
			if (value != null && !rootValueMap.containsKey(value))
				rootValueMap.put(value, m);
		}
		for (Map<String, Object> oneLine : rootValueMap.values()) {// set value for root entities
			Entity entity = this.createEntity(root.getClass());
			SqlBox box = entity.box();
			box.configAlias(root.box().getAlias());
			Map<String, Column> realColumns = box.buildRealColumns();
			for (Column col : realColumns.values()) {
				String aiasColUp = entity.aliasByFieldID(col.getFieldID()).toUpperCase();
				if (oneLine.containsKey(aiasColUp))
					box.setFieldRealValue(col, oneLine.get(aiasColUp));
			}
			// add child entity node list
			List<Map<String, Object>> childList = buildChildEntity(entity, list, mappingList);
			box.setChildEntityList(childList);
			resultList.add((T) entity);
		}
		return resultList;
	}

	/**
	 * Transfer List<Map<String, Object>> list to List<T>, you can call it O-R Mapping
	 * 
	 * @param list
	 *            The SQL query List
	 * @param mappingList
	 *            The the mapping list in SQL
	 * @return The root entity list, each entity has it's child node list stored in box, use
	 *         entity.box().getChildEntityList() to get child nodes, each child node point to its parent node, use
	 *         entity.box().getParentEntity() to get parent node
	 */
	public <T> List<T> transfer(List<Map<String, Object>> list, List<Mapping> mappingList) {// NOSONAR
		if (list.size() > 10000)
			log.warn("SqlBoxContext Warning: transfer for list size >10000 is strongly not recommanded.");
		if (list.size() > 100000)
			SqlBoxException.throwEX("SqlBoxContext transfer Error: transfer for list size >100000 is not supported.");
		int isRootCount = 0;
		Mapping root = null;
		for (Mapping mp1 : mappingList) {
			boolean isRoot = true;
			for (Mapping mp2 : mappingList) {
				if (mp2.getOtherEntity() == mp1.getThisEntity()) {
					isRoot = false;
					break;
				}
			}
			if (isRoot) {
				mp1.setIsRoot(isRoot);
				root = mp1;
				isRootCount++;
			}
		}
		if (isRootCount != 1)
			SqlBoxException
					.throwEX("SqlBoxContext checkAndBuildEntityList error: should have and only have 1 root entity");
		return buildRootEntity(root, list, mappingList);
	}

	/**
	 * Query for get Entity List
	 */
	public <T> List<T> queryForEntityList(String... sql) {
		SqlAndParameters sp = SqlHelper.prepareSQLandParameters(sql);
		logSql(sp);
		List<Map<String, Object>> list = getJdbc().queryForList(sp.getSql(), sp.getParameters());
		return transfer(list, sp.getMappingList());
	}

	/**
	 * Query for get a List<Map<String, Object>> List
	 */
	public List<Map<String, Object>> queryForList(String... sql) {
		SqlAndParameters sp = SqlHelper.prepareSQLandParameters(sql);
		logSql(sp);
		return getJdbc().queryForList(sp.getSql(), sp.getParameters());
	}

	public <T> T load(Class<?> entityOrBoxClass, Object entityID) {
		T bean = (T) createEntity(entityOrBoxClass);
		SqlBox box = SqlBoxContext.getBindedBox(bean);
		return box.load(entityID);
	}
	// ========JdbcTemplate wrap methods End============

}