package com.partyboard

import scala.concurrent.duration._

import akka.actor.{ActorRef, ActorSystem, Props, Actor, ActorLogging}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout

import spray.can.Http

import com.partyboard.api.ApiService
import com.partyboard.domain._

object Application extends App {
    implicit val system = ActorSystem("application")

    val events = ClusterSharding(system).start(
        typeName = "events",
        entityProps = Props[Event],
        settings = ClusterShardingSettings(system),
        extractEntityId = Event.extractEntityId,
        extractShardId = Event.extractShardId)

    val service = system.actorOf(Props(classOf[ApiService], events), name = "api")

    IO(Http) ! Http.Bind(service, "localhost", port = 8080)

    sys.addShutdownHook(system.terminate())
}
