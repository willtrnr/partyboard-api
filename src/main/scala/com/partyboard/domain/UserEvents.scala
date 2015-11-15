package com.partyboard.domain

import scala.concurrent.duration._

import akka.actor.{ActorLogging, PoisonPill, ReceiveTimeout, Status}
import akka.persistence.{PersistentActor, SnapshotOffer}
import akka.cluster.sharding.ShardRegion

case class EventRef(val slug: String, val title: String) extends Serializable
case class UserEventsState(val user: String, val events: Seq[EventRef] = Nil) extends Serializable

class UserEvents extends PersistentActor with ActorLogging {
    import UserEvents._

    var state: UserEventsState = UserEventsState(self.path.name)

    context.setReceiveTimeout(5.minutes)

    override def persistenceId: String = s"userevent-${self.path.name}"

    def updateState(evt: UserEventsEvent): Unit = evt match {
        case EventAdded(_, eventRef) => state = state.copy(events = state.events :+ eventRef)
    }

    override def receiveRecover: Receive = {
        case SnapshotOffer(metadata, userEvent: UserEventsState) => {
            state = userEvent
        }
        case evt: UserEventsEvent => updateState(evt)
    }

    override def receiveCommand: Receive = {
        case _: Get => sender() ! state
        case AddEvent(_, eventRef) => persist(EventAdded(state.user, eventRef)) { evt =>
            updateState(evt)
            context.system.eventStream.publish(evt)
        }
    }

    override def unhandled(message: Any) = message match {
        case ReceiveTimeout => context.parent ! ShardRegion.Passivate(stopMessage = PoisonPill)
        case _ => super.unhandled(message)
    }
}

object UserEvents {
    trait UserEventsCommand { def user: String }
    case class Get(val user: String) extends UserEventsCommand
    case class AddEvent(val user: String, val eventRef: EventRef) extends UserEventsCommand

    trait UserEventsEvent extends Serializable { def user: String }
    case class EventAdded(val user: String, val eventRef: EventRef) extends UserEventsEvent

    val shards: Int = 127

    val extractEntityId: ShardRegion.ExtractEntityId = {
        case msg: UserEventsCommand => (msg.user, msg)
    }

    val extractShardId: ShardRegion.ExtractShardId = {
        case msg: UserEventsCommand => (msg.user.hashCode & shards).toString
    }
}
