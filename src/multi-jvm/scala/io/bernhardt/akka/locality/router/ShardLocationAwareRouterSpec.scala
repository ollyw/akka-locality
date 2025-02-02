package io.bernhardt.akka.locality.router

import akka.actor.{Actor, ActorRef, Address, Props}
import akka.cluster.Cluster
import akka.cluster.routing.{ClusterRouterGroup, ClusterRouterGroupSettings}
import akka.cluster.sharding.{MultiNodeClusterShardingConfig, MultiNodeClusterShardingSpec, ShardRegion}
import akka.pattern.ask
import akka.remote.testconductor.RoleName
import akka.remote.testkit.STMultiNodeSpec
import akka.routing.{GetRoutees, Routees}
import akka.serialization.jackson.CborSerializable
import akka.testkit.{DefaultTimeout, ImplicitSender, TestProbe}
import io.bernhardt.akka.locality.Locality

import scala.concurrent.Await
import scala.concurrent.duration._

object ShardLocationAwareRouterSpec {

  class TestEntity extends Actor {
    val cluster = Cluster(context.system)
    def receive: Receive = {

      case ping: Ping =>
        sender() ! Pong(ping.id, ping.sender, cluster.selfAddress, cluster.selfAddress)
    }
  }

  final case class Ping(id: Int, sender: ActorRef) extends CborSerializable
  final case class Pong(id: Int, sender: ActorRef, routeeAddress: Address, entityAddress: Address) extends CborSerializable

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg@Ping(id, _) => (id.toString, msg)
    case msg@Pong(id, _, _, _) => (id.toString, msg)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    // take this simplest mapping on purpose
    case Ping(id, _) => id.toString
    case Pong(id, _, _, _) => id.toString
  }

  class TestRoutee(region: ActorRef) extends Actor {
    val cluster = Cluster(context.system)
    def receive: Receive = {
      case msg: Ping =>
        region ! msg
      case pong: Pong =>
        pong.sender ! pong.copy(routeeAddress = cluster.selfAddress)
    }
  }

}


object ShardLocationAwareRouterSpecConfig extends MultiNodeClusterShardingConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")
}

class ShardLocationAwareRouterSpecMultiJvmNode1 extends ShardLocationAwareRouterSpec
class ShardLocationAwareRouterSpecMultiJvmNode2 extends ShardLocationAwareRouterSpec
class ShardLocationAwareRouterSpecMultiJvmNode3 extends ShardLocationAwareRouterSpec
class ShardLocationAwareRouterSpecMultiJvmNode4 extends ShardLocationAwareRouterSpec
class ShardLocationAwareRouterSpecMultiJvmNode5 extends ShardLocationAwareRouterSpec

class ShardLocationAwareRouterSpec extends MultiNodeClusterShardingSpec(ShardLocationAwareRouterSpecConfig)
  with STMultiNodeSpec
  with DefaultTimeout
  with ImplicitSender {

  import ShardLocationAwareRouterSpec._
  import ShardLocationAwareRouterSpecConfig._

  var region: Option[ActorRef] = None

  var router: ActorRef = ActorRef.noSender

  Locality(system)

  def joinAndAllocate(node: RoleName, entityIds: Range): Unit = {
    within(10.seconds) {
      join(node, first)
      runOn(node) {
        val region = startSharding(
          sys = system,
          entityProps = Props[TestEntity],
          dataType = "TestEntity",
          extractEntityId = extractEntityId,
          extractShardId = extractShardId)

        this.region = Some(region)

        entityIds.map { entityId =>
          val probe = TestProbe("test")
          val msg = Ping(entityId, ActorRef.noSender)
          probe.send(region, msg)
          probe.expectMsgType[Pong]
          probe.lastSender.path should be(region.path / s"$entityId" / s"$entityId")
        }
      }
    }
    enterBarrier(s"started")
  }

  "allocate shards" in {

    joinAndAllocate(first, (1 to 10))
    joinAndAllocate(second, (11 to 20))
    joinAndAllocate(third, (21 to 30))
    joinAndAllocate(fourth, (31 to 40))
    joinAndAllocate(fifth, (41 to 50))

    enterBarrier("shards-allocated")

  }

  "route by taking into account shard location" in {
    within(20.seconds) {

      region.map { r =>
        system.actorOf(Props(new TestRoutee(r)), "routee")
        enterBarrier("routee-started")

        router = system.actorOf(ClusterRouterGroup(ShardLocationAwareGroup(
          routeePaths = Nil,
          shardRegion = r,
          extractEntityId = extractEntityId,
          extractShardId = extractShardId
        ), ClusterRouterGroupSettings(
          totalInstances = 5,
          routeesPaths = List("/user/routee"),
          allowLocalRoutees = true
        )).props(), "sharding-aware-router")

        awaitAssert {
          currentRoutees(router).size shouldBe 5
        }

        enterBarrier("router-started")

        runOn(first) {
          val probe = TestProbe("probe")
          for (i <- 1 to 50) {
            probe.send(router, Ping(i, probe.ref))
          }

          val msgs: Seq[Pong] = probe.receiveN(50).collect { case p: Pong => p }

          val (same: Seq[Pong], different) = msgs.partition { case Pong(_, _, routeeAddress, entityAddress) =>
            routeeAddress.hostPort == entityAddress.hostPort && routeeAddress.hostPort.nonEmpty
          }

          different.isEmpty shouldBe true

          val byAddress = same.groupBy(_.routeeAddress)

          awaitAssert { byAddress(first).size shouldBe 10 }
          byAddress(first).map(_.id).toSet shouldEqual (1 to 10).toSet
          awaitAssert { byAddress(second).size shouldBe 10 }
          byAddress(second).map(_.id).toSet shouldEqual (11 to 20).toSet
          awaitAssert { byAddress(third).size shouldBe 10 }
          byAddress(third).map(_.id).toSet shouldEqual (21 to 30).toSet
          awaitAssert { byAddress(fourth).size shouldBe 10 }
          byAddress(fourth).map(_.id).toSet shouldEqual (31 to 40).toSet
          awaitAssert { byAddress(fifth).size shouldBe 10 }
          byAddress(fifth).map(_.id).toSet shouldEqual (41 to 50).toSet
        }
        enterBarrier("test-done")

      } getOrElse {
        fail("Region not set")
      }
    }

  }

  "adjust routing after a topology change" in {
    awaitMemberRemoved(fourth)
    awaitAllReachable()

    runOn(first) {
      // trigger rebalancing the shards of the removed node
      val rebalanceProbe = TestProbe("rebalance")
      for (i <- 31 to 40) {
        rebalanceProbe.send(router, Ping(i, rebalanceProbe.ref))
      }

      // we should be receiving messages even in the absence of the updated shard location information
      // random routing should kick in, i.e. we won't have perfect matches
      val randomRoutedMessages: Seq[Pong] = rebalanceProbe.receiveN(10, 15.seconds).collect { case p: Pong => p }
      val (_, differentMsgs) = partitionByAddress(randomRoutedMessages)
      differentMsgs.nonEmpty shouldBe true

      // now give time to the new shards to be allocated and time to the router to retrieve new information
      // TODO find a better way to determine that the router is ready

      Thread.sleep(10000)

      val probe = TestProbe("probe")
      for (i <- 1 to 50) {
        probe.send(router, Ping(i, probe.ref))
      }

      val msgs: Seq[Pong] = probe.receiveN(50, 15.seconds).collect { case p: Pong => p }

      val (_, different) = msgs.partition { case Pong(_, _, routeeAddress, entityAddress) =>
        routeeAddress.hostPort == entityAddress.hostPort && routeeAddress.hostPort.nonEmpty
      }

      different.isEmpty shouldBe true

    }

    enterBarrier("finished")
  }


  def currentRoutees(router: ActorRef) =
    Await.result(router ? GetRoutees, timeout.duration).asInstanceOf[Routees].routees

  def partitionByAddress(msgs: Seq[Pong]) = msgs.partition { case Pong(_, _, routeeAddress, entityAddress) =>
    routeeAddress.hostPort == entityAddress.hostPort && routeeAddress.hostPort.nonEmpty
  }

}
