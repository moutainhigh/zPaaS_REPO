package com.zpaas.dtx.server;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.zpaas.ConfigurationCenter;
import com.zpaas.ConfigurationWatcher;
import com.zpaas.PaasException;
import com.zpaas.cache.remote.RemoteCacheSVC;
import com.zpaas.dtx.common.Transaction;
import com.zpaas.dtx.common.TransactionContext;
import com.zpaas.dtx.common.TransactionPublisher;
import com.zpaas.dtx.server.dao.TransactionDAO;
import com.zpaas.message.MessageStatus;

public class TransactionManagerServer implements ConfigurationWatcher {
	public static final Logger log = LoggerFactory.getLogger(TransactionManagerServer.class);

	private static final String TRANSACTION_TOPIC = "transaction.topic";
	private static final String PROCESSOR_NUM = "processor.num";
	public static final String ZK_SERVER_KEY = "zk.server";
	public static final String TOPIC_PATH = "/transactionManager/participants";
	public static final String LEADER_PATH = "/transactionManager/leader";

	private String confPath = "/com/zpaas/tx/transactionManagerServer";
	private ConfigurationCenter cc = null;
	private TransactionPublisher publisher = null;
	private TransactionProcessor<TransactionContext> newTransactionProcessor = null;
	private TransactionProcessor<TransactionContext> chgTransactionProcessor = null;
	private TransactionDAO transactionDAO = null;
	private RemoteCacheSVC cacheSvc = null;

	
	private String transactionTopic = "distribute_transaction_manager_topic";
	private int processorNum = 1;
	private Properties kafkaProps = null;
	private String zkServer = null;
	private ZooKeeper zk = null;
	
	private List<KafkaConsumer<String, TransactionContext>> consumers = null;
	private ExecutorService executor = null;
		
	private Object lock = new Object();
	private boolean isLeader = false;
	private Thread selectLeaderThread = null;
	

	private Watcher wh = new Watcher() {
		public void process(WatchedEvent event) {
			if (log.isDebugEnabled()) {
				log.debug("receive watch event:{}", event.toString());
			}
			if (LEADER_PATH.equals(event.getPath()) && EventType.NodeDeleted.equals(event.getType())) {
				if (log.isDebugEnabled()) {
					log.debug("Leader is down, notify to select leader.");
				}
				synchronized (lock) {
					lock.notifyAll();
				}
			} else if (EventType.NodeChildrenChanged.equals(event.getType()) && TOPIC_PATH.equals(event.getPath())) {
				watchTopicPath();
			} else if (EventType.NodeChildrenChanged.equals(event.getType())
					&& event.getPath().startsWith(TOPIC_PATH + "/")) {
				watchTopicPath();
			} else {
				if (log.isDebugEnabled()) {
					log.debug("do nothing event:{}", event.toString());
				}
			}
		}
	};

	public TransactionManagerServer() {
		if (log.isInfoEnabled()) {
			log.info("starting TransactionManagerServer...");
		}
	}

	public void init() {
		if (log.isInfoEnabled()) {
			log.info("init TransactionManagerServer...");
		}
		try {
			process(cc.getConfAndWatch(confPath, this));
		} catch (PaasException e) {
			e.printStackTrace();
		}
	}

	public void process(String conf) {
		if (log.isInfoEnabled()) {
			log.info("new TransactionManagerServer configuration is received: {}", conf);
		}
		JSONObject json = JSONObject.fromObject(conf);
		@SuppressWarnings("rawtypes")
		Iterator keys = json.keys();
		boolean threadNumChanged = false;
		boolean changed = false;
		boolean zkChanged = false;
		if (keys != null) {
			String key = null;
			while (keys.hasNext()) {
				key = (String) keys.next();
				if (TRANSACTION_TOPIC.equals(key)) {
					this.transactionTopic = json.getString(key);
				} else if (PROCESSOR_NUM.equals(key)) {
					int n = json.getInt(key);
					if (n != this.processorNum) {
						this.processorNum = n;
						threadNumChanged = true;
					}
				} else if (ZK_SERVER_KEY.equals(key)) {
					if (json.getString(key) != null && !json.getString(key).equals(zkServer)) {
						zkChanged = true;
						zkServer = json.getString(ZK_SERVER_KEY);
					}
				} else {
					if (kafkaProps == null) {
						kafkaProps = new Properties();
						changed = true;
					}
					if (kafkaProps.containsKey(key)) {
						if (kafkaProps.get(key) == null || !kafkaProps.get(key).equals(json.getString(key))) {
							kafkaProps.put(key, json.getString(key));
							changed = true;
						}
					} else {
						kafkaProps.put(key, json.getString(key));
						changed = true;
					}
				}
			}
		}
		if (zkChanged && zkServer != null && zkServer.length() > 0) {
			try {
				zk = new ZooKeeper(zkServer, 3000, wh);
			} catch (IOException e) {
				e.printStackTrace();
			}
			if (selectLeaderThread != null) {
				selectLeaderThread.interrupt();
			}
			selectLeaderThread = new Thread() {
				public void run() {
					while (true && !this.isInterrupted()) {
						try {
							selectLeader();
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}
			};
			selectLeaderThread.start();

		}

		if (changed || threadNumChanged) {
			stopProcessTransaction(executor, consumers);
			consumers = new ArrayList<>();
			startProcessTransaction();
		}
	}

	public void watchTopicPath() {
		if (!isLeader) {
			return;
		}
		synchronized (lock) {
			if (log.isDebugEnabled()) {
				log.debug("begin watchTopicPath");
			}
			List<String> topics = null;
			try {
				topics = zk.getChildren(TOPIC_PATH, wh);
			} catch (Exception e) {
				//e.printStackTrace();
			}
			if (topics == null || topics.size() == 0) {
				return;
			}
			log.debug(JSONArray.fromObject(topics).toString());
			try {
				updateTopics(topics);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public void updateTopics(List<String> topics) {
		for (String topic : topics) {
			updateTopic(topic);
		}
	}

	public void updateTopic(String name) {
		List<String> participants = null;
		try {
			participants = zk.getChildren(TOPIC_PATH + "/" + name, wh);
		} catch (Exception e) {
			e.printStackTrace();
		}
		Transaction transaction = new Transaction();
		transaction.setName(name);
		if (participants == null || participants.size() == 0) {
			transaction.setParticipantNum(0);
			transaction.setParticipants("[]");
		} else {
			JSONArray array = new JSONArray();
			for (String participant : participants) {
				if (log.isDebugEnabled()) {
					log.debug("process subscriber:{}", participant);
				}
				participant = participant.substring(0, participant.lastIndexOf("_"));
				if (!array.contains(participant)) {
					array.add(participant);
				}
			}
			transaction.setParticipantNum(array.size());
			transaction.setParticipants(array.toString());
		}
		if (log.isDebugEnabled()) {
			log.debug("update transaction:{}", transaction);
		}
		cacheSvc.addItem(Transaction.CACHE_PREFIX + name, transaction);
		int i = transactionDAO.update(transaction);
		if (i <= 0) {
			transactionDAO.insert(transaction);
		}
	}

	public void selectLeader() {
		if (log.isDebugEnabled()) {
			log.debug("begin to select leader...");
		}
		try {
			Stat stat = zk.exists(LEADER_PATH, wh);
			String newLeader = null;
			if (stat == null) {
				newLeader = zk.create(LEADER_PATH, null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
			}
			if (newLeader != null) {
				if (log.isDebugEnabled()) {
					log.debug("I'm the leader");
				}
				isLeader = true;
				watchTopicPath();
				synchronized (lock) {
					lock.wait();
				}
			} else {
				if (log.isDebugEnabled()) {
					log.debug("follow leader.");
				}
				isLeader = false;
				synchronized (lock) {
					lock.wait();
				}
			}
		} catch (Exception e) {
			// e.printStackTrace();
		}
	}

	public void startProcessTransaction() {
		if (log.isInfoEnabled()) {
			log.info("start to process transaction...");
		}
		executor = Executors.newFixedThreadPool(processorNum);
		
		int i = 0;
		for (int j = 0; j < processorNum; j++) {
			KafkaConsumer<String, TransactionContext> consumer = new KafkaConsumer<>(kafkaProps);
			consumer.subscribe(Arrays.asList(transactionTopic));
			consumers.add(consumer);
			executor.execute(new TransactionServerProcessor(i, consumer, publisher, this.newTransactionProcessor,
					this.chgTransactionProcessor));
			i++;
		}
	}

	public void stopProcessTransaction(ExecutorService oldExecutor, List<KafkaConsumer<String, TransactionContext>> oldConsumers) {
		if (log.isInfoEnabled()) {
			log.info("stop processing transaction...");
		}
		if(oldConsumers != null && oldConsumers.size() > 0) {
			
			for(KafkaConsumer<String, TransactionContext> oldConsumer : oldConsumers) {
				if(log.isDebugEnabled()) {
					log.debug("old consumer is closed: {}", oldConsumers);
				}
				oldConsumer.close();
			}
		}
		if (oldExecutor != null) {
			if (log.isDebugEnabled()) {
				log.debug("begin to close old executor: {}", oldExecutor);
			}
			oldExecutor.shutdown();
			try {
				while (!oldExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
					if (log.isDebugEnabled()) {
						log.debug("old executor is not closed: {}", oldExecutor);
					}
				}
			} catch (InterruptedException e) {
				log.error(e.getMessage(),e);
			}
			if (log.isDebugEnabled()) {
				log.debug("old executor is closed: {}", oldExecutor);
			}
		}
	}

	public String getConfPath() {
		return confPath;
	}

	public void setConfPath(String confPath) {
		this.confPath = confPath;
	}

	public ConfigurationCenter getCc() {
		return cc;
	}

	public void setCc(ConfigurationCenter cc) {
		this.cc = cc;
	}

	public TransactionPublisher getPublisher() {
		return publisher;
	}

	public void setPublisher(TransactionPublisher publisher) {
		this.publisher = publisher;
	}

	public TransactionProcessor<TransactionContext> getNewTransactionProcessor() {
		return newTransactionProcessor;
	}

	public void setNewTransactionProcessor(TransactionProcessor<TransactionContext> newTransactionProcessor) {
		this.newTransactionProcessor = newTransactionProcessor;
	}

	public TransactionProcessor<TransactionContext> getChgTransactionProcessor() {
		return chgTransactionProcessor;
	}

	public void setChgTransactionProcessor(TransactionProcessor<TransactionContext> chgTransactionProcessor) {
		this.chgTransactionProcessor = chgTransactionProcessor;
	}

	public TransactionDAO getTransactionDAO() {
		return transactionDAO;
	}

	public void setTransactionDAO(TransactionDAO transactionDAO) {
		this.transactionDAO = transactionDAO;
	}

	public RemoteCacheSVC getCacheSvc() {
		return cacheSvc;
	}

	public void setCacheSvc(RemoteCacheSVC cacheSvc) {
		this.cacheSvc = cacheSvc;
	}

	public static void main(String args[]) {
		ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(new String[] { "transactionManagerServer.xml" });
		@SuppressWarnings("unused")
		TransactionManagerServer server = (TransactionManagerServer) ctx.getBean("transactionManagerServer");
		while (true) {
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}

class TransactionServerProcessor implements Runnable {
	public static final Logger log = LoggerFactory.getLogger(TransactionServerProcessor.class);
	
	private String processorName = null;
	private KafkaConsumer<String, TransactionContext> consumer = null;
	private TransactionProcessor<TransactionContext> newTransactionProcessor = null;
	private TransactionProcessor<TransactionContext> chgTransactionProcessor = null;
	private TransactionPublisher publisher = null;
	
	public TransactionServerProcessor(int processorId, KafkaConsumer<String, TransactionContext> consumer, 
			TransactionPublisher publisher, TransactionProcessor<TransactionContext> newTransactionProcessor,
			TransactionProcessor<TransactionContext> chgTransactionProcessor) {
		this.processorName = "TransactionProcessor " + processorId;
		this.consumer = consumer;
		this.publisher = publisher;
		this.newTransactionProcessor = newTransactionProcessor;
		this.chgTransactionProcessor = chgTransactionProcessor;
		if(log.isInfoEnabled()) {
			log.info("{} started", this.processorName);
		}
	}
	public void run() {
		ConsumerRecords<String, TransactionContext> records = consumer.poll(Duration.ofMillis(100));
		TransactionContext msg = null;
		
		if(records != null && !records.isEmpty()) {
			for(ConsumerRecord<String, TransactionContext> record : records) {
				try {
					msg = record.value();
					log.info("{} process transaction:",this.processorName, msg.toString());
					MessageStatus status = new MessageStatus();
					if(TransactionContext.TRANSACTION_STATUS_NEW.equals(msg.getStatus()) || 
							TransactionContext.ASSURED_TRANSACTION_STATUS_NEW.equals(msg.getStatus())) {
						newTransactionProcessor.processTransaction(msg, status);
					}else {
						if(TransactionContext.TRANSACTION_STATUS_COMMIT.equals(msg.getStatus())) {
							publisher.publish(
									new ProducerRecord<String, TransactionContext>(msg.getName(), String.valueOf(msg.getTransactionId()), msg));
						}
						chgTransactionProcessor.processTransaction(msg, status);				
					}
				} catch (Exception e) {
					log.error(e.getMessage(),e);
				} catch (Error e) {
					log.error(e.getMessage(),e);
				}
			}
		}
		
		if(log.isInfoEnabled()) {
			log.info("{} is stopped", processorName);
		}
	}

}