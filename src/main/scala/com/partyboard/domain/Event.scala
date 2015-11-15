package com.partyboard.domain

import scala.concurrent.duration._

import akka.actor.{ActorLogging, PoisonPill, ReceiveTimeout, Status}
import akka.persistence.{PersistentActor, SnapshotOffer}
import akka.cluster.sharding.ShardRegion

case class EventState(val slug: String, val title: String, val pictures: Seq[String] = Nil) extends Serializable

class Event extends PersistentActor with ActorLogging {
    import Event._

    var state: EventState = _

    context.setReceiveTimeout(5.minutes)

    override def persistenceId: String = s"event-${self.path.name}"

    def updateState(evt: EventEvent): Unit = evt match {
        case Created(slug, title) => {
            state = EventState(slug = slug, title = title)
            context become initialized
        }
        case PictureAdded(_, _, url) => state = state.copy(pictures = state.pictures :+ url)
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
        case _: EventCommand => sender() ! Status.Failure(NotInitializedException())
    }

    def initialized: Receive = {
        case _: Create => sender() ! Status.Failure(DuplicateException())
        case _: Get => sender() ! state
        case AddPicture(_, url) => persist(PictureAdded(state.slug, state.pictures.length, url)) { evt =>
            updateState(evt)
            context.system.eventStream.publish(evt)
        }
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
    case class PictureAdded(val slug: String, val idx: Long, val url: String) extends EventEvent

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
