package org.conan.kafka;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import kafka.api.FetchRequest;
import kafka.api.FetchRequestBuilder;
import kafka.api.PartitionOffsetRequestInfo;
import kafka.cluster.Broker;
import kafka.common.ErrorMapping;
import kafka.common.TopicAndPartition;
import kafka.javaapi.FetchResponse;
import kafka.javaapi.OffsetResponse;
import kafka.javaapi.PartitionMetadata;
import kafka.javaapi.TopicMetadata;
import kafka.javaapi.TopicMetadataRequest;
import kafka.javaapi.TopicMetadataResponse;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.message.MessageAndOffset;

/**
 * Created by zhanghang on 2014/9/17.
 */
public class SingleTopicConsumer implements Runnable {
    private List<String> _replicaBrokers = new ArrayList<String>();
    private long lastOffset;
    private int _partition;
    private Queue<String> messages = new ConcurrentLinkedQueue<String>();
    private Long _lastTime = null;
    private int sleepTimes = 0;
    public SingleTopicConsumer(int partition) {
        logger.info("Initial Partition "+partition+" ...");
        _partition = partition;
        lastOffset =  _zk.getLastOffset("/hdfs/" + conf.groupId() + "/" + _topic + "/" + partition);
    }
    private static String _topic;
    private static List<String> _seeds;
    private static int _port;
    private static int fetchSize;
    private static ZkUtil _zk;
    private static Logger logger;
    private static ConsumerConfig conf;
    private static HdfsUtil hdfs;
    public static void main(String[] args) throws Exception {
        String path = "";
        if (args != null && args.length == 2) {
            _topic = args[0];
            path = args[1];
        }
        else {
            System.out.println("Invalid parameters");
            System.exit(0);
        }
        conf = new ConsumerConfig(path);
        logger = logger.getLogger(SingleTopicConsumer.class.toString());
        _seeds = conf.brokers();
        _port = conf.brokerPort();
        fetchSize = conf.fetchSize();
        _zk = new ZkUtil(conf.zkServer());
        hdfs = new HdfsUtil(conf.hdfs(), conf.job());
        List<String>partitions = _zk.getPartitions(_topic);
        //创建线程池
        ExecutorService pool = Executors.newCachedThreadPool();
        for (String partition : partitions) {
            pool.execute(new Thread(new SingleTopicConsumer(Integer.parseInt(partition))));
        }
    }

    public void run() {
        PartitionMetadata metadata = findLeader(_seeds, _port, _topic, _partition);
        if (metadata == null) {logger.info("Can't find metadata for Topic and Partition. Exiting");return;}
        if (metadata.leader() == null) {logger.info("Can't find Leader for Topic and Partition. Exiting");return;}
        String leadBroker = metadata.leader().host();
        String clientName = "Client_" + _topic + "_" + _partition;
        SimpleConsumer consumer = new SimpleConsumer(leadBroker, _port, 1000000, 64 * 1024, clientName);
        if (lastOffset == 0) {
            lastOffset = getLastOffset(consumer, _topic, _partition,kafka.api.OffsetRequest.LatestTime(), clientName);
        }
        int numErrors = 0;
        while (fetchSize>0) {
            if (consumer == null) {consumer = new SimpleConsumer(leadBroker, _port, 1000000, 64 * 1024, clientName);}
            // Note: this fetchSize of 100000 might need to be increased if large batches are written to Kafka
            FetchRequest req = new FetchRequestBuilder().clientId(clientName).addFetch(_topic, _partition, lastOffset, fetchSize).build();
            FetchResponse fetchResponse = consumer.fetch(req);
            //something wrong
            if (fetchResponse.hasError()) {
                numErrors++;
                short code = fetchResponse.errorCode(_topic, _partition);
                System.out.println("Error fetching data from the Broker:"+ leadBroker + " Reason: " + code);
                if (numErrors > 5) break;
                if (code == ErrorMapping.OffsetOutOfRangeCode()) {
                    lastOffset = getLastOffset(consumer, _topic, _partition,kafka.api.OffsetRequest.LatestTime(), clientName);
                    continue;
                }
                consumer.close();
                consumer = null;
                try {
                    leadBroker = findNewLeader(leadBroker, _topic, _partition, _port);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                continue;
            }
            numErrors = 0;
            long numRead = 0;
            for (MessageAndOffset messageAndOffset : fetchResponse.messageSet(_topic, _partition)) {
                long currentOffset = messageAndOffset.offset();
                if (currentOffset < lastOffset) {
                    System.out.println("Found an old offset: " + currentOffset+ " Expecting: " + lastOffset);
                    continue;
                }
                lastOffset = messageAndOffset.nextOffset();
                ByteBuffer payload = messageAndOffset.message().payload();
                byte[] bytes = new byte[payload.limit()];
                payload.get(bytes);
                String mes = null;
                try {
                    mes = new String(bytes,"UTF-8");
                    System.out.println(mes);
                    //System.out.println("当前JVM的默认字符集：" + Charset.defaultCharset().newEncoder());
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                boolean inQueue = messages.offer(mes+'\n');
                Long currentTime = System.currentTimeMillis() / 1000;
                if (_lastTime==null || currentTime >= _lastTime + 30 || !inQueue) {
                    _lastTime = currentTime;
                    hdfsWriter();
                }
                numRead++;
                fetchSize--;
                sleepTimes = 0;
            }
            if (numRead == 0) {
                sleepTimes++;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {}
                //if fetch no message in 60s, then clear the queue and reset the sleepTimes
                if (sleepTimes >= 30) {
                    int queueSize = messages.size();
                    if (queueSize > 0) {
                        System.out.println("..........."+_topic+" sleep too long with queue " + queueSize + "..............");
                        hdfsWriter();
                    }
                    System.out.println("======reset the sleepTimes======");
                    sleepTimes = 0;
                }
                //System.out.println("............sleepTimes:"+sleepTimes+".............");
            }
        }
        consumer.close();
    }
    public synchronized void hdfsWriter() {
        int queueSize = messages.size();
        try {
            hdfs.batchWrite(conf.hdfsTopicPath(_topic,_partition), messages);
        } catch (IOException e) {
            e.printStackTrace();
        }
        _zk.setOffset("/hdfs/" + conf.groupId() + "/" + _topic + "/" + _partition, lastOffset);
        System.out.println("..."+_topic+":"+_partition+":"+lastOffset+":"+queueSize+"...");
    }

    public static long getLastOffset(SimpleConsumer consumer, String topic,int partition, long whichTime, String clientName) {
        TopicAndPartition topicAndPartition = new TopicAndPartition(topic,partition);
        Map<TopicAndPartition, PartitionOffsetRequestInfo> requestInfo = new HashMap<TopicAndPartition, PartitionOffsetRequestInfo>();
        requestInfo.put(topicAndPartition, new PartitionOffsetRequestInfo(whichTime, 1));
        kafka.javaapi.OffsetRequest request = new kafka.javaapi.OffsetRequest(requestInfo, kafka.api.OffsetRequest.CurrentVersion(),clientName);
        OffsetResponse response = consumer.getOffsetsBefore(request);
        if (response.hasError()) {
            System.out.println("Error fetching data Offset Data the Broker. Reason: "+ response.errorCode(topic, partition));
            return 0;
        }
        long[] offsets = response.offsets(topic, partition);
        return offsets[0];
    }

    private String findNewLeader(String a_oldLeader, String _topic,
                                 int _partition, int _port) throws Exception {
        for (int i = 0; i < 3; i++) {
            boolean goToSleep = false;
            PartitionMetadata metadata = findLeader(_replicaBrokers, _port,
                    _topic, _partition);
            if (metadata == null) {
                goToSleep = true;
            } else if (metadata.leader() == null) {
                goToSleep = true;
            } else if (a_oldLeader.equalsIgnoreCase(metadata.leader().host())
                    && i == 0) {
                // first time through if the leader hasn't changed give
                // ZooKeeper a second to recover
                // second time, assume the broker did recover before failover,
                // or it was a non-Broker issue
                //
                goToSleep = true;
            } else {
                return metadata.leader().host();
            }
            if (goToSleep) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                }
            }
        }
        System.out
                .println("Unable to find new leader after Broker failure. Exiting");
        throw new Exception(
                "Unable to find new leader after Broker failure. Exiting");
    }

    private PartitionMetadata findLeader(List<String> a_seedBrokers,int _port, String _topic, int _partition) {
        PartitionMetadata returnMetaData = null;
        loop: for (String seed : a_seedBrokers) {
            kafka.javaapi.consumer.SimpleConsumer consumer = null;
            try {
                consumer = new kafka.javaapi.consumer.SimpleConsumer(seed, _port, 100000, 64 * 1024,"leaderLookup");
                List<String> topics = Collections.singletonList(_topic);
                TopicMetadataRequest req = new TopicMetadataRequest(topics);
                TopicMetadataResponse resp = consumer.send(req);

                List<TopicMetadata> metaData = resp.topicsMetadata();
                for (TopicMetadata item : metaData) {
                    for (PartitionMetadata part : item.partitionsMetadata()) {
                        if (part.partitionId() == _partition) {
                            returnMetaData = part;
                            break loop;
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("Error communicating with Broker [" + seed
                        + "] to find Leader for [" + _topic + ", "
                        + _partition + "] Reason: " + e);
            } finally {
                if (consumer != null)
                    consumer.close();
            }
        }
        if (returnMetaData != null) {
            _replicaBrokers.clear();
            for (Broker replica : returnMetaData.replicas()) {
                _replicaBrokers.add(replica.host());
            }
        }
        return returnMetaData;
    }
}