package com.partyboard.domain

import scala.concurrent.duration._

import akka.actor.{ActorLogging, PoisonPill, ReceiveTimeout, Status}
import akka.persistence.{PersistentActor, SnapshotOffer}
import akka.cluster.sharding.ShardRegion

case class EventState(val slug: String, val title: String, val pictures: Seq[String] = Nil) extends Serializable

class Event extends PersistentActor with ActorLogging {
    import Event._

    context.setReceiveTimeout(5.minutes)

    var state: EventState = null

    override def persistenceId: String = s"event-${self.path.name}"

    def updateState(evt: EventEvent): Unit = evt match {
        case Created(slug, title) => {
            state = EventState(slug, title, Seq("http://lorempixel.com/300/300/", "http://lorempixel.com/301/300/", "http://lorempixel.com/300/301/", "http://lorempixel.com/301/301/"))
            context become initialized
        }
    }

    override def receiveRecover: Receive = {
        case SnapshotOffer(metadata, event: EventState) => {
            state = event
            context become initialized
        }
        case evt: EventEvent => updateState(evt)
    }

    def uninitialized: Receive = {
        case Create(slug, title) => persist(Created(slug, title)) { evt =>
            updateState(evt)
            context.system.eventStream.publish(evt)
        }
        case _: Get => sender() ! Status.Failure(NotInitializedException())
    }

    def initialized: Receive = {
        case _: Create => sender() ! Status.Failure(DuplicateException())
        case _: Get => sender() ! state
    }

    override def receiveCommand: Receive = uninitialized

    override def unhandled(message: Any) = message match {
        case ReceiveTimeout => context.parent ! ShardRegion.Passivate(stopMessage = PoisonPill)
        case _ => super.unhandled(message)
    }
}

object Event {
    trait EventCommand { def slug: String }
    case class Create(val slug: String, val title: String) extends EventCommand
    case class Get(val slug: String) extends EventCommand
    case class AddPicture(val slug: String, val url: String) extends EventCommand

    trait EventEvent extends Serializable { def slug: String }
    case class Created(val slug: String, val title: String) extends EventEvent
    case class PictureAdded(val slug: String, val url: String) extends EventEvent

    abstract class EventException(msg: String) extends Exception(msg)
    case class NotInitializedException() extends EventException("Event does not exist")
    case class DuplicateException() extends EventException("Event slug already exists")

    val shards: Int = 127

    val extractEntityId: ShardRegion.ExtractEntityId = {
        case msg: EventCommand => (msg.slug, msg)
    }

    val extractShardId: ShardRegion.ExtractShardId = {
        case msg: EventCommand => (msg.slug.hashCode & shards).toString
    }
}
