package com.github.drinkjava2.functionstest;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.github.drinkjava2.config.DataSourceConfig.MySqlDataSourceBox;
import com.github.drinkjava2.jbeanbox.BeanBox;
import com.github.drinkjava2.jdialects.annotation.jpa.Table;
import com.github.drinkjava2.jsqlbox.ActiveRecord;
import com.github.drinkjava2.jsqlbox.SqlBoxContext;

/**
 * This is Batch operation function test
 * 
 * @author Yong Zhu
 * @since 1.7.0
 */
public class BatchTest {
	SqlBoxContext ctx = new SqlBoxContext((DataSource) BeanBox.getBean(MySqlDataSourceBox.class));
	{
		SqlBoxContext.setGlobalSqlBoxContext(ctx);
	}

	@Before
	public void setupDB() {
		ctx.quiteExecute("drop table batch_test_tb");
		ctx.quiteExecute("create table batch_test_tb (name varchar(40), address varchar(40))");
	}

	@After
	public void cleanUp() {
		ctx.quiteExecute("drop table batch_test_tb");
		BeanBox.defaultContext.close();
	}

	@Table(name = "batch_test_tb")
	public static class User extends ActiveRecord {
		String name;
		String address;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getAddress() {
			return address;
		}

		public void setAddress(String address) {
			this.address = address;
		}
	}

	@Test
	public void testBatch() {
		int repeat = 1000;

		User user = new User();
		user.setName("Sam");
		user.setAddress("Canada");

		ctx.nExecute("delete from batch_test_tb");
		long start = System.currentTimeMillis();
		for (long i = 0; i < repeat; i++) {
			user.setName("Name" + i);
			user.setAddress("Address" + i);
			user.insert();
		}

		long end = System.currentTimeMillis();
		String timeused = "" + (end - start) / 1000 + "." + (end - start) % 1000;
		System.out.println(String.format("Non-Batch execute " + repeat + " SQLs time used: %6s s", timeused));
		Assert.assertEquals(repeat, ctx.nQueryForLongValue("select count(*) from batch_test_tb"));

		ctx.nExecute("delete from batch_test_tb");
		start = System.currentTimeMillis();
		ctx.nBatchBegin();
		for (long i = 0; i < repeat; i++) {
			user.setName("Name" + i);
			user.setAddress("Address" + i);
			user.insert();
		}

		ctx.nBatchEnd();
		end = System.currentTimeMillis();
		timeused = "" + (end - start) / 1000 + "." + (end - start) % 1000;
		System.out
				.println(String.format("nBatchBegin/nBatchEnd execute " + repeat + " SQLs time used: %6s s", timeused));
		Assert.assertEquals(repeat, ctx.nQueryForLongValue("select count(*) from batch_test_tb"));

		ctx.nExecute("delete from batch_test_tb");
		start = System.currentTimeMillis();
		List<Object[]> params = new ArrayList<Object[]>();
		for (long i = 0; i < repeat; i++)
			params.add(new Object[] { "Name" + i, "Address" + i });
		ctx.nBatch("insert into batch_test_tb (name, address) values(?,?)", params);
		end = System.currentTimeMillis();
		timeused = "" + (end - start) / 1000 + "." + (end - start) % 1000;
		System.out.println(String
				.format("nBatch(Sql, List<Object[])) method execute " + repeat + " SQLs time used: %6s s", timeused));
		Assert.assertEquals(repeat, ctx.nQueryForLongValue("select count(*) from batch_test_tb"));

		ctx.nExecute("delete from batch_test_tb");
		start = System.currentTimeMillis();
		Object[][] paramsArray = new Object[repeat][2];
		for (int i = 0; i < repeat; i++) {
			paramsArray[i][0] = "Name" + i;
			paramsArray[i][1] = "Address" + i;
		}
		try {
			ctx.batch("insert into batch_test_tb (name, address) values(?,?)", paramsArray);
		} catch (SQLException e) {
			e.printStackTrace();
		}
		end = System.currentTimeMillis();
		timeused = "" + (end - start) / 1000 + "." + (end - start) % 1000;
		System.out.println(
				String.format("batch(Sql, Object[][]) method execute " + repeat + " SQLs time used: %6s s", timeused));
		Assert.assertEquals(repeat, ctx.nQueryForLongValue("select count(*) from batch_test_tb"));
	}

}