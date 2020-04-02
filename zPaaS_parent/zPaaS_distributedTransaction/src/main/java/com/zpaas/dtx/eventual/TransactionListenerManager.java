package com.zpaas.dtx.eventual;

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

import net.sf.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;

import com.zpaas.ConfigurationCenter;
import com.zpaas.ConfigurationWatcher;
import com.zpaas.PaasException;
import com.zpaas.dtx.common.TransactionContext;
import com.zpaas.dtx.common.TransactionListener;
import com.zpaas.dtx.common.TransactionPublisher;
import com.zpaas.dtx.common.TransactionStatus;
import com.zpaas.utils.CommonUtil;



public class TransactionListenerManager implements ConfigurationWatcher{
	public static final Logger log = LoggerFactory.getLogger(TransactionListenerManager.class);
	
	private static final String TRANSACTION_TOPIC = "transaction.topic";
	private static final String LISTENER_NUM = "listener.num";
	private static final String ABNORMAL_LISTENER_NUM = "abnormal.listener.num";
	public static final String ZK_SERVER_KEY = "zk.server";
	
	public static final String ROOT_PATH = "/transactionManager";
	public static final String TOPIC_PATH = "/transactionManager/participants";
	

	private String confPath = "/com/zpaas/tx/transactionListenerManager";	
	private ConfigurationCenter cc = null;	
	private TransactionListener listener = null;
	private TransactionPublisher publisher = null;
	private ArrayList<String> transactionList = null;
	private ArrayList<String> abnormalTransactionList = null;
	private String participant = null;
	
	
	private String transactionTopic = "distribute_transaction_manager_topic";	
	private int lintenerNum = 1;
	private int abnormalListenerNum = 1;
	private String zkServer = null;
	private Properties kafkaProps = null;
	
	private List<KafkaConsumer<String, TransactionContext>> consumers = null;
	private ExecutorService executor = null;
	
	private ZooKeeper zk = null;
	private String address = null;
	
	public TransactionListenerManager() {
		if(log.isInfoEnabled()) {
			log.info("starting TransactionListenerManager...");
		}
		address = CommonUtil.getHostAddr();
	}
	
	public void init() {
		if(log.isInfoEnabled()) {
			log.info("init TransactionListenerManager...");
		}
		if(transactionList != null) {
			abnormalTransactionList = new ArrayList<String>();
			for(String topic : transactionList) {
				abnormalTransactionList.add(topic+"_abnormal_"+participant);
			}
		}
		try {
			process(cc.getConfAndWatch(confPath, this));
		} catch (PaasException e) {
			e.printStackTrace();
		}		
	}
	
	
	
	public void process(String conf) {
		if(log.isInfoEnabled()) {
			log.info("new TransactionListenerManager configuration is received: {}", conf);
		}		
		JSONObject json = JSONObject.fromObject(conf);
		@SuppressWarnings("rawtypes")
		Iterator keys = json.keys();
		boolean threadNumChanged = false;
		boolean changed = false;
		boolean zkChanged = false;
		if(kafkaProps == null) {
			kafkaProps = new Properties();
			changed = true;
		}
		kafkaProps.put("group.id", participant);
		if(keys != null) {
			String key = null;
			while(keys.hasNext()) {
				key = (String)keys.next();
				if(TRANSACTION_TOPIC.equals(key)) {
					this.transactionTopic = json.getString(key);
				}else if(LISTENER_NUM.equals(key) ) {
					int n = json.getInt(key);
					if(n != this.lintenerNum) {
						this.lintenerNum = n;
						threadNumChanged = true;
					}
				}else if(ABNORMAL_LISTENER_NUM.equals(key) ) {
					int n = json.getInt(key);
					if(n != this.abnormalListenerNum) {
						this.abnormalListenerNum = n;
						threadNumChanged = true;
					}
				}else if(ZK_SERVER_KEY.equals(key)) {
					if(json.getString(key) != null && !json.getString(key).equals(zkServer)) {
						zkChanged = true;
						zkServer = json.getString(ZK_SERVER_KEY);
					}
				}else {
					if(kafkaProps.containsKey(key)) {
						if(kafkaProps.get(key) == null || !kafkaProps.get(key).equals(json.getString(key))) {
							kafkaProps.put(key, json.getString(key));
							changed = true;
						}
					}else {
						kafkaProps.put(key, json.getString(key));
						changed = true;
					}
				}
			}
		}
		if(zkChanged && zkServer != null && zkServer.length() > 0) {
			try {
				zk = new ZooKeeper(zkServer, 3000, null);
			} catch (IOException e) {
				e.printStackTrace();
			}
			createPath();
			subscribeTopic();
		}
		if(changed || threadNumChanged) {
			stopTransactionListener(executor, consumers);
			consumers = new ArrayList<>();
			startTransactionListener();
		}
	}
	
	public void createPath() {
		Stat stat = null;
		try {
			stat = zk.exists(ROOT_PATH, true);
		} catch (Exception e) {			
		} 
		if(stat == null) {
			try {
				zk.create(ROOT_PATH, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			} catch (Exception e) {
			} 
		}
		
		try {
			stat = zk.exists(TOPIC_PATH, true);
		} catch (Exception e) {			
		} 
		if(stat == null) {
			try {
				zk.create(TOPIC_PATH, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
			} catch (Exception e) {
			} 
		}
		
	}
	
	public void subscribeTopic() {
		for(String topic : transactionList) {
			Stat stat = null;
			try {
				stat = zk.exists(TOPIC_PATH + "/" + topic, false);
			} catch (Exception e) {			
			} 
			if(stat == null) {
				try {
					zk.create(TOPIC_PATH + "/" + topic, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
				} catch (Exception e) {
				} 
			}
			try {
				zk.create(TOPIC_PATH + "/" + topic + "/" + participant + "_" + address + ".", 
						null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
			} catch (Exception e) {
				e.printStackTrace();
			} 
		}
		
	}
	
	public void startTransactionListener() {
		if(log.isInfoEnabled()) {
			log.info("start TransactionListener...");
		}
		if(transactionList == null || transactionList.size() == 0) {
			return;
		}
		executor = Executors.newFixedThreadPool(lintenerNum * transactionList.size() + 
				abnormalListenerNum*abnormalTransactionList.size());
		
		int i=0;
		for(String topic : transactionList) {
			for(int j=0; j<lintenerNum; j++) {
				KafkaConsumer<String, TransactionContext> consumer = new KafkaConsumer<>(kafkaProps);
				consumer.subscribe(Arrays.asList(topic));
				consumers.add(consumer);
				executor.execute(new TransactionListenerProcessor(i, consumer, 
						publisher, transactionTopic, listener,participant));
				i++;
			}
		}
		for(String topic : abnormalTransactionList) {
			for(int j=0; j<abnormalListenerNum; j++) {
				KafkaConsumer<String, TransactionContext> consumer = new KafkaConsumer<>(kafkaProps);
				consumer.subscribe(Arrays.asList(topic));
				consumers.add(consumer);
				executor.execute(new TransactionListenerProcessor(i, consumer, 
						publisher, transactionTopic, listener,participant));
				i++;
			}
		}
		
	}
	
	public void stopTransactionListener(ExecutorService oldExecutor, List<KafkaConsumer<String, TransactionContext>> oldConsumers) {
		if(log.isInfoEnabled()) {
			log.info("stop old TransactionListener...");
		}
		if(oldConsumers != null && oldConsumers.size() > 0) {
			
			for(KafkaConsumer<String, TransactionContext> oldConsumer : oldConsumers) {
				if(log.isDebugEnabled()) {
					log.debug("old consumer is closed: {}", oldConsumers);
				}
				oldConsumer.close();
			}
		}
		if(oldExecutor != null) {
			if(log.isDebugEnabled()) {
				log.debug("begin to close old executor: {}", oldExecutor);
			}
			oldExecutor.shutdown();
			try {
				while(!oldExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
					if(log.isDebugEnabled()) {
						log.debug("old executor is not closed: {}", oldExecutor);
					}
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			if(log.isDebugEnabled()) {
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

	public TransactionListener getListener() {
		return listener;
	}

	public void setListener(TransactionListener listener) {
		this.listener = listener;
	}

	public String getParticipant() {
		return participant;
	}

	public void setParticipant(String participant) {
		this.participant = participant;
	}

	public TransactionPublisher getPublisher() {
		return publisher;
	}

	public void setPublisher(TransactionPublisher publisher) {
		this.publisher = publisher;
	}

	public ArrayList<String> getTransactionList() {
		return transactionList;
	}

	public void setTransactionList(ArrayList<String> transactionList) {
		this.transactionList = transactionList;
	}

	
}

class TransactionListenerProcessor implements Runnable {
	public static final Logger log = LoggerFactory.getLogger(TransactionListenerProcessor.class);
	
	private String listenerName = null;
	private KafkaConsumer<String, TransactionContext> consumer = null;
	private TransactionListener listener = null;
	private TransactionPublisher publisher = null;
	private String transactionManagerTopic = null;
	private String participant = null;
	
	public TransactionListenerProcessor(int lintenerId, KafkaConsumer<String, TransactionContext> consumer, 
			TransactionPublisher publisher, String transactionManagerTopic, TransactionListener listener, String participant) {
		this.listenerName = "TransactionListenerProcessor " + lintenerId;
		this.consumer = consumer;
		this.publisher = publisher;
		this.transactionManagerTopic = transactionManagerTopic;
		this.listener = listener;
		this.participant = participant;
		if(log.isInfoEnabled()) {
			log.info("{} started", this.listenerName);
		}
	}
	public void run() {
		ConsumerRecords<String, TransactionContext> records = consumer.poll(Duration.ofMillis(100));
		TransactionContext msg = null;
		
		if(records != null && !records.isEmpty()) {
			for(ConsumerRecord<String, TransactionContext> record : records) {
				try {
					msg = record.value();
					log.info("{} TransactionListenerProcessor process transaction:{}",this.listenerName, msg.toString());
					JSONObject content = null;
					if(msg.getContent() != null && msg.getContent().trim().length() > 0) {
						content = JSONObject.fromObject(msg.getContent());
					}
					TransactionStatus status = new TransactionStatus();
					if(msg.getStartTime() !=  null) {
						log.warn("transaction:{} cost:{}",msg.getTransactionId(), (System.currentTimeMillis()-msg.getStartTime()));
					}
					listener.joinTransaction(content, status,msg.getName());
					if(status.isRollbackOnly()) {
						msg.setStatus(TransactionContext.TRANSACTION_STATUS_PART_FAILED);
						if(log.isDebugEnabled()) {
							log.debug("listener rollback the message:{}", msg.getTransactionId());
						}
					}else {
						msg.setStatus(TransactionContext.TRANSACTION_STATUS_PART_SUCCEED);									
						if(log.isDebugEnabled()) {
							log.debug("listener commit the message:{}", msg.getTransactionId());
						}
					}
					msg.setParticipant(participant);	
					ProducerRecord<String, TransactionContext> transactionMessage = new ProducerRecord<String, TransactionContext>(transactionManagerTopic, String.valueOf(msg.getTransactionId()), msg);
					if(!publisher.publish(transactionMessage)) {
						log.error("publish transaction failed: {} new status:{}",msg.getTransactionId(), msg.getStatus());
					}
				} catch (Exception e) {
					log.error(e.getMessage(),e);
				} catch (Error e) {
					log.error(e.getMessage(),e);
				}
			}
		}
		
		if(log.isInfoEnabled()) {
			log.info("{} is stopped.", listenerName);
		}
	}

}
