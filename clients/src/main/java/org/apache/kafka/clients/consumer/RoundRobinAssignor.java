/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.clients.consumer;

import org.apache.kafka.clients.consumer.internals.AbstractPartitionAssignor;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.CircularIterator;
import org.apache.kafka.common.utils.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The roundrobin assignor lays out all the available partitions and all the available consumers. It
 * then proceeds to do a roundrobin assignment from partition to consumer. If the subscriptions of all consumer
 * instances are identical, then the partitions will be uniformly distributed. (i.e., the partition ownership counts
 * will be within a delta of exactly one across all consumers.)
 *
 * For example, suppose there are two consumers C0 and C1, two topics t0 and t1, and each topic has 3 partitions,
 * resulting in partitions t0p0, t0p1, t0p2, t1p0, t1p1, and t1p2.
 *
 * The assignment will be:
 * C0: [t0p0, t0p2, t1p1]
 * C1: [t0p1, t1p0, t1p2]
 */
public class RoundRobinAssignor extends AbstractPartitionAssignor {

    @Override
    public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic,
                                                    Map<String, List<String>> subscriptions) {
        Map<String, List<TopicPartition>> assignment = new HashMap<>();
        for (String memberId : subscriptions.keySet())
            assignment.put(memberId, new ArrayList<TopicPartition>());//初始化分配规则

        CircularIterator<String> assigner = new CircularIterator<>(Utils.sorted(subscriptions.keySet()));//assigner存放了所有的consumer
        for (TopicPartition partition : allPartitionsSorted(partitionsPerTopic, subscriptions)) {//所有的consumer订阅的所有的TopicPartition的List
            final String topic = partition.topic();
            while (!subscriptions.get(assigner.peek()).contains(topic))// 如果当前这个assigner(consumer)没有订阅这个topic，直接跳过
                assigner.next();
            //跳出循环，表示终于找到了订阅过这个TopicPartition对应的topic的assigner
            //将这个partition分派给对应的assigner
            assignment.get(assigner.next()).add(partition);
        }
        return assignment;
    }


    public List<TopicPartition> allPartitionsSorted(Map<String, Integer> partitionsPerTopic,
                                                    Map<String, List<String>> subscriptions) {
        SortedSet<String> topics = new TreeSet<>();
        for (List<String> subscription : subscriptions.values())//对于所有的订阅的topic的list
            topics.addAll(subscription);//收集所有的topic，并去重

        List<TopicPartition> allPartitions = new ArrayList<>();
        for (String topic : topics) {
            Integer numPartitionsForTopic = partitionsPerTopic.get(topic);// 当前这个topic的数量
            if (numPartitionsForTopic != null)
                allPartitions.addAll(AbstractPartitionAssignor.partitions(topic, numPartitionsForTopic));
        }
        return allPartitions;//所有的partition的List
    }

    @Override
    public String name() {
        return "roundrobin";
    }

}
