/*
 * Bitronix Transaction Manager
 *
 * Copyright (c) 2010, Bitronix Software.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA 02110-1301 USA
 */
package bitronix.tm.mock;

import java.sql.Connection;
import java.util.ArrayList;

import javax.transaction.Transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bitronix.tm.BitronixTransactionManager;
import bitronix.tm.TransactionManagerServices;
import bitronix.tm.resource.jdbc.PooledConnectionProxy;

/**
 *
 * @author lorban
 */
public class JdbcSharedConnectionTest extends AbstractMockJdbcTest {
    private final static Logger log = LoggerFactory.getLogger(NewJdbcProperUsageMockTest.class);

    public void testSharedConnectionMultithreaded() throws Exception {
        if (log.isDebugEnabled()) log.debug("*** Starting testSharedConnectionMultithreaded: getting TM");
        final BitronixTransactionManager tm = TransactionManagerServices.getTransactionManager();
        tm.setTransactionTimeout(120);

        if (log.isDebugEnabled()) log.debug("*** before begin");
        tm.begin();
        if (log.isDebugEnabled()) log.debug("*** after begin");

        final Transaction suspended = tm.suspend();

        final ArrayList twoConnections = new ArrayList();
        Thread thread1 = new Thread() {
        	public void run() {
        		try {
					tm.resume(suspended);
			        if (log.isDebugEnabled()) log.debug("*** getting connection from DS1");
			        Connection connection = poolingDataSource1.getConnection();
			        connection.createStatement();
			        twoConnections.add(connection);
				} catch (Exception e) {
					e.printStackTrace();
					fail(e.getMessage());
				}
        	}
        };
        thread1.start();
        thread1.join();

        Thread thread2 = new Thread() {
        	public void run() {
        		try {
					tm.resume(suspended);
			        if (log.isDebugEnabled()) log.debug("*** getting connection from DS1");
			        Connection connection = poolingDataSource1.getConnection();
			        connection.createStatement();
			        twoConnections.add(connection);
			        tm.commit();
				} catch (Exception e) {
					e.printStackTrace();
					fail(e.getMessage());
				}
        	}
        };
        thread2.start();
        thread2.join();

        PooledConnectionProxy handle1 = (PooledConnectionProxy) twoConnections.get(0);
        PooledConnectionProxy handle2 = (PooledConnectionProxy) twoConnections.get(1);
        assertNotSame(handle1.getProxiedDelegate(), handle2.getProxiedDelegate());

    }

    public void testUnSharedConnection() throws Exception {
        if (log.isDebugEnabled()) log.debug("*** Starting testUnSharedConnection: getting TM");
        BitronixTransactionManager tm = TransactionManagerServices.getTransactionManager();
        tm.setTransactionTimeout(120);

        if (log.isDebugEnabled()) log.debug("*** before begin");
        tm.begin();
        if (log.isDebugEnabled()) log.debug("*** after begin");

        if (log.isDebugEnabled()) log.debug("*** getting connection from DS2");
        Connection connection1 = poolingDataSource2.getConnection();
        // createStatement causes enlistment
        connection1.createStatement();

        if (log.isDebugEnabled()) log.debug("*** getting second connection from DS2");
        Connection connection2 = poolingDataSource2.getConnection();

        PooledConnectionProxy handle1 = (PooledConnectionProxy) connection1;
        PooledConnectionProxy handle2 = (PooledConnectionProxy) connection2;
        assertNotSame(handle1.getProxiedDelegate(), handle2.getProxiedDelegate());

        connection1.close();
        connection2.close();

        tm.commit();
    }

    public void testSharedConnectionInLocalTransaction() throws Exception {

        if (log.isDebugEnabled()) log.debug("*** Starting testSharedConnectionInLocalTransaction: getting connection from DS1");
        Connection connection1 = poolingDataSource1.getConnection();
        // createStatement causes enlistment
        connection1.createStatement();

        if (log.isDebugEnabled()) log.debug("*** getting second connection from DS1");
        Connection connection2 = poolingDataSource1.getConnection();

        PooledConnectionProxy handle1 = (PooledConnectionProxy) connection1;
        PooledConnectionProxy handle2 = (PooledConnectionProxy) connection2;
        assertNotSame(handle1.getProxiedDelegate(), handle2.getProxiedDelegate());

        connection1.close();
        connection2.close();
    }

    public void testUnSharedConnectionInLocalTransaction() throws Exception {

        if (log.isDebugEnabled()) log.debug("*** Starting testUnSharedConnectionInLocalTransaction: getting connection from DS2");
        Connection connection1 = poolingDataSource2.getConnection();
        // createStatement causes enlistment
        connection1.createStatement();

        if (log.isDebugEnabled()) log.debug("*** getting second connection from DS2");
        Connection connection2 = poolingDataSource2.getConnection();

        PooledConnectionProxy handle1 = (PooledConnectionProxy) connection1;
        PooledConnectionProxy handle2 = (PooledConnectionProxy) connection2;
        assertNotSame(handle1.getProxiedDelegate(), handle2.getProxiedDelegate());

        connection1.close();
        connection2.close();
    }

    public void testSharedConnectionInGlobal() throws Exception {
        if (log.isDebugEnabled()) log.debug("*** testSharedConnectionInGlobal: Starting getting TM");
        BitronixTransactionManager tm = TransactionManagerServices.getTransactionManager();
        tm.setTransactionTimeout(120);

        if (log.isDebugEnabled()) log.debug("*** before begin");
        tm.begin();
        if (log.isDebugEnabled()) log.debug("*** after begin");

        if (log.isDebugEnabled()) log.debug("*** getting connection from DS1");
        Connection connection1 = poolingDataSource1.getConnection();

        if (log.isDebugEnabled()) log.debug("*** getting second connection from DS1");
        Connection connection2 = poolingDataSource1.getConnection();

        PooledConnectionProxy handle1 = (PooledConnectionProxy) connection1;
        PooledConnectionProxy handle2 = (PooledConnectionProxy) connection2;
        assertSame(handle1.getProxiedDelegate(), handle2.getProxiedDelegate());

        connection1.close();
        connection2.close();

        tm.commit();
    }
}
