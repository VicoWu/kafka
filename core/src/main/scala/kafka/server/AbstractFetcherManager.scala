/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.server

import scala.collection.mutable
import scala.collection.Set
import scala.collection.Map
import kafka.utils.Logging
import kafka.cluster.BrokerEndPoint
import kafka.metrics.KafkaMetricsGroup
import kafka.common.TopicAndPartition
import com.yammer.metrics.core.Gauge
import org.apache.kafka.common.utils.Utils

abstract class AbstractFetcherManager(protected val name: String, clientId: String, numFetchers: Int = 1)
  extends Logging with KafkaMetricsGroup {
  // map of (source broker_id, fetcher_id per source broker) => fetcher
  //这个map的key是fetcher的id，value是这个fetcher线程
  private val fetcherThreadMap = new mutable.HashMap[BrokerAndFetcherId, AbstractFetcherThread]
  private val mapLock = new Object
  this.logIdent = "[" + name + "] "

  newGauge(
    "MaxLag",
    new Gauge[Long] {
      // current max lag across all fetchers/topics/partitions
      def value = fetcherThreadMap.foldLeft(0L)((curMaxAll, fetcherThreadMapEntry) => {
        fetcherThreadMapEntry._2.fetcherLagStats.stats.foldLeft(0L)((curMaxThread, fetcherLagStatsEntry) => {
          curMaxThread.max(fetcherLagStatsEntry._2.lag)
        }).max(curMaxAll)
      })
    },
    Map("clientId" -> clientId)
  )

  newGauge(
  "MinFetchRate", {
    new Gauge[Double] {
      // current min fetch rate across all fetchers/topics/partitions
      def value = {
        val headRate: Double =
          fetcherThreadMap.headOption.map(_._2.fetcherStats.requestRate.oneMinuteRate).getOrElse(0)

        fetcherThreadMap.foldLeft(headRate)((curMinAll, fetcherThreadMapEntry) => {
          fetcherThreadMapEntry._2.fetcherStats.requestRate.oneMinuteRate.min(curMinAll)
        })
      }
    }
  },
  Map("clientId" -> clientId)
  )

  /**
    * fetcherid的决定因子是topic和partition，因此，一个topicpartitiion只有一个FetcherId
    * 查看AbstractFetcherManager.addFetcherForPartitions()方法
    * @param topic
    * @param partitionId
    * @return
    */
  private def getFetcherId(topic: String, partitionId: Int) : Int = {
    Utils.abs(31 * topic.hashCode() + partitionId) % numFetchers //默认情况下，num.replica.fetchers的默认值是1，即numFetchers是1
  }

  // to be defined in subclass to create a specific fetcher
  def createFetcherThread(fetcherId: Int, sourceBroker: BrokerEndPoint): AbstractFetcherThread

  //为某个partition创建Fetcher线程，具体实现可以看ReplicaFetcherManager，然后启动Fetcher 线程
  def addFetcherForPartitions(partitionAndOffsets: Map[TopicAndPartition, BrokerAndInitialOffset]) {
    mapLock synchronized {
      //partitionAndOffsets记录了TopicPartition和它的LeadeBroker以及offset的对应关系
      //将这些partition根据brokerAndInitialOffset.broker和FetcherId进行group，这里的brokerAndInitialOffset.broker对应的是其leader broker的id
      val partitionsPerFetcher = partitionAndOffsets.groupBy{ case(topicAndPartition, brokerAndInitialOffset) =>
        BrokerAndFetcherId(brokerAndInitialOffset.broker, getFetcherId(topicAndPartition.topic, topicAndPartition.partition))}
      for ((brokerAndFetcherId, partitionAndOffsets) <- partitionsPerFetcher) {
        var fetcherThread: AbstractFetcherThread = null
        fetcherThreadMap.get(brokerAndFetcherId) match {
          case Some(f) => fetcherThread = f //如果已经存在了针对这个brokerAndFetcherId的fetcher thread,则直接使用，不用创建
          case None =>
            //如果是ReplicaFetcherManager,这fetcherThread是ReplicaFetcherThread
            fetcherThread = createFetcherThread(brokerAndFetcherId.fetcherId, brokerAndFetcherId.broker) //调用具体实现类的createFetcherThread()方法
            fetcherThreadMap.put(brokerAndFetcherId, fetcherThread) //将新创建的Fetcher保存到fetcherThreadMap中
            fetcherThread.start //线程启动
        }

        fetcherThreadMap(brokerAndFetcherId).addPartitions(partitionAndOffsets.map { case (topicAndPartition, brokerAndInitOffset) =>
          topicAndPartition -> brokerAndInitOffset.initOffset
        })
      }
    }

    info("Added fetcher for partitions %s".format(partitionAndOffsets.map{ case (topicAndPartition, brokerAndInitialOffset) =>
      "[" + topicAndPartition + ", initOffset " + brokerAndInitialOffset.initOffset + " to broker " + brokerAndInitialOffset.broker + "] "}))
  }

  /**
    * 删除针对partitions的FetcherThread,注意，并不是删除线程，而是，将这些线程中的partitions进行删除。每一个partition肯定是被某一个FetcherThread所处理的
    * @param partitions
    */
  def removeFetcherForPartitions(partitions: Set[TopicAndPartition]) {
    mapLock synchronized {
      for ((key, fetcher) <- fetcherThreadMap) {
        fetcher.removePartitions(partitions)
      }
    }
    info("Removed fetcher for partitions %s".format(partitions.mkString(",")))
  }

  def shutdownIdleFetcherThreads() {
    mapLock synchronized {
      val keysToBeRemoved = new mutable.HashSet[BrokerAndFetcherId]
      for ((key, fetcher) <- fetcherThreadMap) {
        if (fetcher.partitionCount <= 0) {
          fetcher.shutdown()
          keysToBeRemoved += key
        }
      }
      fetcherThreadMap --= keysToBeRemoved
    }
  }

  def closeAllFetchers() {
    mapLock synchronized {
      for ( (_, fetcher) <- fetcherThreadMap) {
        fetcher.shutdown()
      }
      fetcherThreadMap.clear()
    }
  }
}

//一个BrokerAndFetcherId，代表了唯一一个Fetcher，这个fetcher 为某个特定的TopicPartition(fetcherId决定)从broker上获取数据
case class BrokerAndFetcherId(broker: BrokerEndPoint, fetcherId: Int)

case class BrokerAndInitialOffset(broker: BrokerEndPoint, initOffset: Long)
