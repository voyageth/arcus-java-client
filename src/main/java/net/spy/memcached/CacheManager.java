/*
 * arcus-java-client : Arcus Java client
 * Copyright 2010-2014 NAVER Corp.
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
package net.spy.memcached;

/**
 * A program to use CacheMonitor to start and
 * stop memcached node based on a znode. The program watches the
 * specified znode and saves the znode that corresponds to the
 * memcached server in the remote machine. It also changes the 
 * previous ketama node
 */
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import net.spy.memcached.ArcusClientException.InitializeClientException;
import net.spy.memcached.compat.SpyThread;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

public class CacheManager extends SpyThread implements Watcher,
		CacheMonitor.CacheMonitorListener {
	private static final String ARCUS_BASE_CACHE_LIST_ZPATH = "/arcus/cache_list/";

	private static final String ARCUS_BASE_CLIENT_INFO_ZPATH = "/arcus/client_list/";

	/* ENABLE_REPLICATION start */
	private static final String ARCUS_REPL_CACHE_LIST_ZPATH = "/arcus_repl/cache_list/";

	private static final String ARCUS_REPL_CLIENT_INFO_ZPATH = "/arcus_repl/client_list/";
	/* ENABLE_REPLICATION end */

	private static final int ZK_SESSION_TIMEOUT = 15000;
	
	private static final long ZK_CONNECT_TIMEOUT = ZK_SESSION_TIMEOUT;

	private final String hostPort;

	private final String serviceCode;

	private CacheMonitor cacheMonitor;

	private ZooKeeper zk;

	private ArcusClient[] client;

	private final CountDownLatch clientInitLatch;

	private final ConnectionFactoryBuilder cfb;

	private final int waitTimeForConnect;

	private final int poolSize;

	private volatile boolean shutdownRequested = false;

	private CountDownLatch zkInitLatch;

	/* ENABLE_REPLICATION start */
	private boolean arcus17 = false;
	/* ENABLE_REPLICATION end */
	
	public CacheManager(String hostPort, String serviceCode,
			ConnectionFactoryBuilder cfb, CountDownLatch clientInitLatch, int poolSize,
			int waitTimeForConnect) {

		this.hostPort = hostPort;
		this.serviceCode = serviceCode;
		this.cfb = cfb;
		this.clientInitLatch = clientInitLatch;
		this.poolSize = poolSize;
		this.waitTimeForConnect = waitTimeForConnect;

		initZooKeeperClient();

		setName("Cache Manager IO for " + serviceCode + "@" + hostPort);
		setDaemon(true);
		start();

		getLogger().info(
				"CacheManager started. (" + serviceCode + "@" + hostPort + ")");
		
	}
	
	private void initZooKeeperClient() {
		try {
			getLogger().info("Trying to connect to Arcus admin(%s@%s)", serviceCode, hostPort);
			
			zkInitLatch = new CountDownLatch(1);
			zk = new ZooKeeper(hostPort, ZK_SESSION_TIMEOUT, this);

			try {
				/* In the above ZooKeeper() internals, reverse DNS lookup occurs
				 * when the getHostName() of InetSocketAddress class is called.
				 * In Windows, the reverse DNS lookup includes NetBIOS lookup
				 * that bring delay of 5 seconds (as well as dns and host file lookup).
				 * So, ZK_CONNECT_TIMEOUT is set as much like ZK session timeout.
				 */
				if (zkInitLatch.await(ZK_CONNECT_TIMEOUT, TimeUnit.MILLISECONDS) == false) {
					getLogger().fatal("Connecting to Arcus admin(%s) timed out : %d miliseconds",
							hostPort, ZK_CONNECT_TIMEOUT);
					throw new AdminConnectTimeoutException(hostPort);
				}
				
				/* ENABLE_REPLICATION start */
				// Check /arcus_repl/cache_list/{svc} first
				// If it exists, the service code belongs to a repl cluster
				if (zk.exists(ARCUS_REPL_CACHE_LIST_ZPATH + serviceCode, false) != null) {
					arcus17 = true;
					cfb.setArcus17(true);
					getLogger().info("Connecting to Arcus repl cluster");
				}
				else if (zk.exists(ARCUS_BASE_CACHE_LIST_ZPATH + serviceCode, false) != null) {
					arcus17 = false;
					cfb.setArcus17(false);
				}
				else {
					getLogger().fatal("Service code not found. (" + serviceCode + ")");
					throw new NotExistsServiceCodeException(serviceCode);
				}
				/* ENABLE_REPLICATION else */
				/*
				if (zk.exists(ARCUS_BASE_CACHE_LIST_ZPATH + serviceCode, false) == null) {
					getLogger().fatal(
							"Service code not found. (" + serviceCode + ")");
					throw new NotExistsServiceCodeException(serviceCode);
				}
				*/
				/* ENABLE_REPLICATION end */


				String path = getClientInfo();
				if (path.isEmpty()) {
					getLogger().fatal(
							"Can't create the znode of client info (" + path
									+ ")");
					throw new InitializeClientException(
							"Can't initialize Arcus client.");
				}
				
				if (zk.exists(path, false) == null) {
					zk.create(path, null, Ids.OPEN_ACL_UNSAFE,
							CreateMode.EPHEMERAL);
				}
			} catch (AdminConnectTimeoutException e) {
				shutdownZooKeeperClient();
				throw e;
			} catch (NotExistsServiceCodeException e) {
				shutdownZooKeeperClient();
				throw e;
			} catch (InterruptedException ie) {
				getLogger().fatal("Can't connect to Arcus admin(%s@%s) %s", serviceCode, hostPort, ie.getMessage());
				shutdownZooKeeperClient();
				return;
			} catch (Exception e) {
				getLogger().fatal(
						"Unexpected exception. contact to Arcus administrator");

				shutdownZooKeeperClient();
				throw new InitializeClientException(
						"Can't initialize Arcus client.", e);
			}

			/* ENABLE_REPLICATION start */
			String cachePath = arcus17 ? ARCUS_REPL_CACHE_LIST_ZPATH 
                                       : ARCUS_BASE_CACHE_LIST_ZPATH;
			cacheMonitor = new CacheMonitor(zk, cachePath, serviceCode, this);
			/* ENABLE_REPLICATION else */
			/*
			cacheMonitor = new CacheMonitor(zk, ARCUS_BASE_CACHE_LIST_ZPATH, serviceCode, this);
			*/
			/* ENABLE_REPLICATION end */
		} catch (IOException e) {
			throw new InitializeClientException(
					"Can't initialize Arcus client.", e);
		}
	}

	private String getClientInfo() {
		String path = "";
		
		try {
			SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
			Date currentTime = new Date();
			
			// create the ephemeral znode 
			// "/arcus/client_list/{service_code}/{client hostname}_{ip address}_{pool size}_java_{client version}_{YYYYMMDDHHIISS}_{zk session id}"
			/* ENABLE_REPLICATION start */
			if (arcus17)
				path = ARCUS_REPL_CLIENT_INFO_ZPATH; // /arcus_repl/client_list/...
			else
				path = ARCUS_BASE_CLIENT_INFO_ZPATH; // /arcus/client_list/...
			path = path + serviceCode + "/"
			/* ENABLE_REPLICATION else */
			/*
			path = ARCUS_BASE_CLIENT_INFO_ZPATH + serviceCode + "/"
			*/
			/* ENABLE_REPLICATION end */
					+ InetAddress.getLocalHost().getHostName() + "_"
					+ InetAddress.getLocalHost().getHostAddress() + "_"
					+ this.poolSize
					+ "_java_"
					+ ArcusClient.VERSION + "_"
					+ simpleDateFormat.format(currentTime) + "_" 
					+ zk.getSessionId();
			
		} catch (UnknownHostException e) {
			return null;
		}

		return path;
	}

	/***************************************************************************
	 * We do process only child node change event ourselves, we just need to
	 * forward them on.
	 * 
	 */
	public void process(WatchedEvent event) {
		if (event.getType() == Event.EventType.None) {
			switch (event.getState()) {
			case SyncConnected:
				getLogger().info("Connected to Arcus admin. (%s@%s)", serviceCode, hostPort);
				zkInitLatch.countDown();
			}
		}
		
		if (cacheMonitor != null) {
			cacheMonitor.process(event);
		} else {
			getLogger().debug(
					"cm is null, servicecode : %s, state:%s, type:%s",
					serviceCode, event.getState(), event.getType());
		}
	}

	public void run() {
		try {
			synchronized (this) {
				while (!shutdownRequested) {
					if (zk == null) {
						getLogger().info("Arcus admin connection is not established. (%s@%s)", serviceCode, hostPort);
						initZooKeeperClient();
					}
					
					if (!cacheMonitor.dead) {
						wait();
					} else {
						getLogger().warn("Unexpected disconnection from Arcus admin. Trying to reconnect to Arcus admin.");
						try {
							shutdownZooKeeperClient();
							initZooKeeperClient();
						} catch (AdminConnectTimeoutException e) {
							Thread.sleep(5000L);
						} catch (NotExistsServiceCodeException e) {
							Thread.sleep(5000L);
						} catch (InitializeClientException e) {
							Thread.sleep(5000L);
						}
					}
				}
			}
		} catch (InterruptedException e) {
			getLogger().warn("current arcus admin is interrupted : %s",
					e.getMessage());
		} finally {
			shutdownZooKeeperClient();
		}
	}

	public void closing() {
		synchronized (this) {
			notifyAll();
		}
	}

	/**
	 * Change current MemcachedNodes to new MemcachedNodes but intersection of
	 * current and new will be ruled out.
	 * 
	 * @param children
	 *            new children node list
	 */
	public void commandNodeChange(List<String> children) {
		/* ENABLE_REPLICATION start */
		// children is the current list of znodes in the cache_list directory
		// Arcus base cluster and repl cluster use different znode names.
		//
		// Arcus base cluster
		// Znode names are ip:port-hostname.  Just remove -hostname and concat
		// all names separated by commas.  AddrUtil turns ip:port into InetSocketAddress.
		//
		// Arcus repl cluster
		// Znode names are group^{M,S}^ip:port-hostname.  Concat all names separated
		// by commas.  Arcus17NodeAddress turns these names into Arcus17NodeAddress.
		/* ENABLE_REPLICATION end */

		String addrs = "";
		/* ENABLE_REPLICATION start */
		if (arcus17) {
			for (int i = 0; i < children.size(); i++) {
				if (i > 0)
					addrs = addrs + ",";
				addrs = addrs + children.get(i);
			}
		}
		else {
			for (int i = 0; i < children.size(); i++) {
				String[] temp = children.get(i).split("-");
				if (i != 0) {
					addrs = addrs + "," + temp[0];
				} else {
					addrs = temp[0];
				}
			}
		}
		/* ENABLE_REPLICATION else */
		/*
		for (int i = 0; i < children.size(); i++) {
			String[] temp = children.get(i).split("-");
			if (i != 0) {
				addrs = addrs + "," + temp[0];
			} else {
				addrs = temp[0];
			}
		}
		*/
		/* ENABLE_REPLICATION end */

		if (client == null) {
			createArcusClient(addrs);
			return;
		}

		for (ArcusClient ac : client) {
			MemcachedConnection conn = ac.getMemcachedConnection();
			conn.putMemcachedQueue(addrs);
			conn.getSelector().wakeup();
		}
	}

	/**
	 * Create a ArcusClient
	 * 
	 * @param addrs
	 *            current available Memcached Addresses
	 */
	private void createArcusClient(String addrs) {
		/* ENABLE_REPLICATION start */
		List<InetSocketAddress> socketList;
		int count;
		if (arcus17) {
			socketList = Arcus17NodeAddress.getAddresses(addrs);

			// Exclude fake server addresses (slaves) in the initial latch count.
			// Otherwise we may block here for a while trying to connect to
			// slave-only groups.
			count = 0;
			for (InetSocketAddress a : socketList) {
				// See TCPMemcachedNodeImpl:TCPMemcachedNodeImpl().
				boolean isFake = ("/" + CacheMonitor.FAKE_SERVER_NODE).equals(a.toString());
				if (!isFake)
					count++;
			}
		}
		else {
			socketList = AddrUtil.getAddresses(addrs);
			// Preserve base cluster behavior.  The initial latch count
			// includes fake server addresses.
			count = socketList.size();
		}

		final CountDownLatch latch = new CountDownLatch(count);
		/* ENABLE_REPLICATION else */
		/*
		List<InetSocketAddress> socketList = AddrUtil.getAddresses(addrs);

		final CountDownLatch latch = new CountDownLatch(socketList.size());
		*/
		/* ENABLE_REPLICATION end */
		final ConnectionObserver observer = new ConnectionObserver() {

			@Override
			public void connectionLost(SocketAddress sa) {

			}

			@Override
			public void connectionEstablished(SocketAddress sa,
					int reconnectCount) {
				latch.countDown();
			}
		};

		cfb.setInitialObservers(Collections.singleton(observer));

		int _awaitTime = 0;
		/* ENABLE_REPLICATION start */
		if (waitTimeForConnect == 0)
			_awaitTime = 50 * count;
		else
			_awaitTime = waitTimeForConnect;
		/* ENABLE_REPLICATION else */
		/*
		if (waitTimeForConnect == 0)
			_awaitTime = 50 * socketList.size();
		else
			_awaitTime = waitTimeForConnect;
		*/
		/* ENABLE_REPLICATION end */

		client = new ArcusClient[poolSize];
		for (int i = 0; i < poolSize; i++) {
			try {
				client[i] = ArcusClient.getInstance(cfb.build(), socketList);
				client[i].setName("Memcached IO for " + serviceCode);
				client[i].setCacheManager(this);
			} catch (IOException e) {
				getLogger()
						.fatal("Arcus Connection has critical problems. contact arcus manager.");
			}
		}
		try {
			if (latch.await(_awaitTime, TimeUnit.MILLISECONDS)) {
				getLogger().warn("All arcus connections are established.");
			} else {
				getLogger()
						.error("Some arcus connections are not established.");
			}
			// Success signal for initial connections to Zookeeper and
			// Memcached.
		} catch (InterruptedException e) {
			getLogger()
					.fatal("Arcus Connection has critical problems. contact arcus manager.");
		}
		this.clientInitLatch.countDown();

	}

	/**
	 * Returns current ArcusClient
	 * 
	 * @return current ArcusClient
	 */
	public ArcusClient[] getAC() {
		return client;
	}

	private void shutdownZooKeeperClient() {
		if (zk == null) {
			return;
		}

		try {
			getLogger().info("Close the ZooKeeper client. serviceCode=" + serviceCode + ", adminSessionId=0x" + Long.toHexString(zk.getSessionId()));
			zk.close();
			zk = null;
		} catch (InterruptedException e) {
			getLogger().warn(
					"An exception occured while closing ZooKeeper client.", e);
		}
	}

	public void shutdown() {
		if (!shutdownRequested) {
			getLogger().info("Shut down cache manager.");
			shutdownRequested = true;
			closing();
		}
	}
}
