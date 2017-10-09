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


import java.util.concurrent.TimeUnit

import com.yammer.metrics.core.Meter
import kafka.metrics.KafkaMetricsGroup
import kafka.utils.Pool
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.requests.ProduceResponse.PartitionResponse

import scala.collection._

case class ProducePartitionStatus(requiredOffset: Long, responseStatus: PartitionResponse) {
  @volatile var acksPending = false

  override def toString = "[acksPending: %b, error: %d, startOffset: %d, requiredOffset: %d]"
    .format(acksPending, responseStatus.errorCode, responseStatus.baseOffset, requiredOffset)
}

/**
 * The produce metadata maintained by the delayed produce operation
 */
case class ProduceMetadata(produceRequiredAcks: Short,
                           produceStatus: Map[TopicPartition, ProducePartitionStatus]) {

  override def toString = "[requiredAcks: %d, partitionStatus: %s]"
    .format(produceRequiredAcks, produceStatus)
}

/**
 * A delayed produce operation that can be created by the replica manager and watched
 * in the produce operation purgatory
  *可以看ReplicaManager.appendMessages()对其进行构造的过程
  *构造DelayedProduce对象的时候，会将每一个没有发生错误的的TopicParitition 的acksPending置为true
 */
class DelayedProduce(delayMs: Long, //延迟时间，比如，向offset topic中produce相关元数据信息，则delayMs是config.offsetCommitTimeoutMs.toLong
                     produceMetadata: ProduceMetadata,
                     replicaManager: ReplicaManager,
                     responseCallback: Map[TopicPartition, PartitionResponse] => Unit)
  extends DelayedOperation(delayMs) {

  // first update the acks pending variable according to the error code
  //根据error code更新acksPending变量
  produceMetadata.produceStatus.foreach { case (topicPartition, status) =>
    if (status.responseStatus.errorCode == Errors.NONE.code) {
      // Timeout error state will be cleared when required acks are received
      status.acksPending = true
      status.responseStatus.errorCode = Errors.REQUEST_TIMED_OUT.code
    } else {
      status.acksPending = false
    }

    trace("Initial partition status for %s is %s".format(topicPartition, status))
  }

  /**
    *
    * 实现了DelayedOperation的tryComplete()方法
    * 返回值代表了是否已经被执行了。true代表已经被立刻执行了，false代表还需要延时执行
   * The delayed produce operation can be completed if every partition
   * it produces to is satisfied by one of the following:
   *
   * Case A: This broker is no longer the leader: set an error in response
   * Case B: This broker is the leader:
   *   B.1 - If there was a local error thrown while checking if at least requiredAcks
   *         replicas have caught up to this operation: set an error in response
   *   B.2 - Otherwise, set the response with no error.
   */
  override def tryComplete(): Boolean = {
    // check for each partition if it still has pending acks
    produceMetadata.produceStatus.foreach { case (topicAndPartition, status) =>  //对于每一个TopicPartition
      trace("Checking produce satisfaction for %s, current status %s"
        .format(topicAndPartition, status))
      // skip those partitions that have already been satisfied
      if (status.acksPending) {
        val partitionOpt = replicaManager.getPartition(topicAndPartition.topic, topicAndPartition.partition)
        val (hasEnough, errorCode) = partitionOpt match {
          case Some(partition) =>
            partition.checkEnoughReplicasReachOffset(status.requiredOffset)
          case None =>
            // Case A   //这个broker已经不是leader了
            (false, Errors.UNKNOWN_TOPIC_OR_PARTITION.code)
        }
        //只有以下两种情况，这个DelayedProduce会被立即执行，否则，如果没有发生错误，并且hasEnough=false，无法被立即执行
        if (errorCode != Errors.NONE.code) { //发生错误，会被立即执行
          // Case B.1
          status.acksPending = false
          status.responseStatus.errorCode = errorCode
        } else if (hasEnough) { //没有发生错误，并且有足够的ISR，会被立即执行
          // Case B.2
          status.acksPending = false
          status.responseStatus.errorCode = Errors.NONE.code
        }
      }
    }

    // check if each partition has satisfied at lease one of case A and case B
    if (! produceMetadata.produceStatus.values.exists(p => p.acksPending)) //如果不存在acksPending=true的metadata
      forceComplete() //会被立刻执行
    else
      false  //不满足立刻执行的条件，比如，errorCode=Errors.NONE.code，但是hasEnough=false，这时候还得延时执行
  }

  override def onExpiration() {
    produceMetadata.produceStatus.foreach { case (topicPartition, status) =>
      if (status.acksPending) {
        DelayedProduceMetrics.recordExpiration(topicPartition)
      }
    }
  }

  /**
   * Upon completion, return the current response status along with the error code per partition
   */
  override def onComplete() {
    val responseStatus = produceMetadata.produceStatus.mapValues(status => status.responseStatus)
    responseCallback(responseStatus)
  }
}

object DelayedProduceMetrics extends KafkaMetricsGroup {

  private val aggregateExpirationMeter = newMeter("ExpiresPerSec", "requests", TimeUnit.SECONDS)

  private val partitionExpirationMeterFactory = (key: TopicPartition) =>
    newMeter("ExpiresPerSec",
             "requests",
             TimeUnit.SECONDS,
             tags = Map("topic" -> key.topic, "partition" -> key.partition.toString))
  private val partitionExpirationMeters = new Pool[TopicPartition, Meter](valueFactory = Some(partitionExpirationMeterFactory))

  def recordExpiration(partition: TopicPartition) {
    aggregateExpirationMeter.mark()
    partitionExpirationMeters.getAndMaybePut(partition).mark()
  }
}

