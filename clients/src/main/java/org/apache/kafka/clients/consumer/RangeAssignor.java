/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.consumer;

import org.apache.kafka.clients.consumer.internals.AbstractPartitionAssignor;
import org.apache.kafka.common.TopicPartition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The range assignor works on a per-topic basis. For each topic, we lay out the available partitions in numeric order
 * and the consumers in lexicographic order. We then divide the number of partitions by the total number of
 * consumers to determine the number of partitions to assign to each consumer. If it does not evenly
 * divide, then the first few consumers will have one extra partition.
 *
 * For example, suppose there are two consumers C0 and C1, two topics t0 and t1, and each topic has 3 partitions,
 * resulting in partitions t0p0, t0p1, t0p2, t1p0, t1p1, and t1p2.
 *
 * The assignment will be:
 * C0: [t0p0, t0p1, t1p0, t1p1]
 * C1: [t0p2, t1p2]
 */
public class RangeAssignor extends AbstractPartitionAssignor {

    @Override
    public String name() {
        return "range";
    }

    //这个方法的输入输出都是Map<String, List<String>> ，主要是防止输入参数中存在某一个key对应的value是null的情况
    private Map<String, List<String>> consumersPerTopic(Map<String, List<String>> consumerMetadata) {
        Map<String, List<String>> res = new HashMap<>();
        for (Map.Entry<String, List<String>> subscriptionEntry : consumerMetadata.entrySet()) {
            String consumerId = subscriptionEntry.getKey();
            for (String topic : subscriptionEntry.getValue())
                put(res, topic, consumerId);
        }
        return res;
    }

    @Override
    /*
     * (non-Javadoc)
     * partitionsPerTopic中保存了每一个topic的partition数量
     * Map<String, List<String>> subscriptions的key是consumerId，代表了一个consumer，value是一个list，代表这个consumer订阅的topic的list
     * 返回一个map,key为consumerid，value为分配给这个consumer的TopicPartition的List
     * 
     */
    public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic,
                                                    Map<String, List<String>> subscriptions) {
    	
        Map<String, List<String>> consumersPerTopic = consumersPerTopic(subscriptions);
        Map<String, List<TopicPartition>> assignment = new HashMap<>();//key为consumerID，value为分配给该consumer的TopicPartition
        for (String memberId : subscriptions.keySet())//对于每一个consumer
            assignment.put(memberId, new ArrayList<TopicPartition>());//初始化

        //对于每一个topic，进行分配
        for (Map.Entry<String, List<String>> topicEntry : consumersPerTopic.entrySet()) {
            String topic = topicEntry.getKey();
            List<String> consumersForTopic = topicEntry.getValue();

            //这个topic的partition数量
            Integer numPartitionsForTopic = partitionsPerTopic.get(topic);
            if (numPartitionsForTopic == null)//partition数量为null，直接跳过，忽略
                continue;

            Collections.sort(consumersForTopic);//对consumer进行排序

            //计算每个consumer分到的partition数量
            int numPartitionsPerConsumer = numPartitionsForTopic / consumersForTopic.size();
            //计算平均以后剩余partition数量
            int consumersWithExtraPartition = numPartitionsForTopic % consumersForTopic.size();

            //从0开始作为Partition Index, 构造TopicPartition对象
            List<TopicPartition> partitions = AbstractPartitionAssignor.partitions(topic, numPartitionsForTopic);
            for (int i = 0, n = consumersForTopic.size(); i < n; i++) {//对于当前这个topic的每一个consumer
            	//一定是前面几个consumer会被分配一个额外的TopicPartitiion
                int start = numPartitionsPerConsumer * i + Math.min(i, consumersWithExtraPartition);
                int length = numPartitionsPerConsumer + (i + 1 > consumersWithExtraPartition ? 0 : 1);
                assignment.get(consumersForTopic.get(i)).addAll(partitions.subList(start, start + length));
            }
        }
        return assignment;
    }

}
