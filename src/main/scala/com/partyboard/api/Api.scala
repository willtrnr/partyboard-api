package com.partyboard.api

import scala.concurrent.duration._

import akka.actor.{Actor, ActorRef, ActorLogging, Props}
import akka.pattern.ask
import akka.util.Timeout

import spray.can.Http
import spray.http._
import spray.httpx.marshalling._
import spray.httpx.unmarshalling._
import spray.json._
import spray.routing.{HttpServiceActor, RequestContext}

import com.partyboard.domain.{Event, EventState}
import com.partyboard.protocol.Json._

class StreamActor(val slug: String, val ctx: RequestContext) extends Actor with ActorLogging {
    override def preStart(): Unit = {
        super.preStart
        context.system.eventStream.subscribe(self, classOf[Event.PictureAdded])
        ctx.responder ! ChunkedResponseStart(HttpResponse(entity = HttpEntity(MediaType.custom("text/event-stream"), "")))
    }

    override def postStop(): Unit = {
        context.system.eventStream.unsubscribe(self)
        super.postStop
    }

    override def receive: Receive = {
        case e @ Event.PictureAdded(s, _) if s == slug => ctx.responder ! eventChunk("picture", e.toJson.toString)
        case _: Http.ConnectionClosed => context.stop(self)
    }

    def eventChunk(event: String, data: String) = MessageChunk(s"event: ${event}\r\ndata: ${data}\r\n\r\n")
}

class ApiService(events: ActorRef) extends HttpServiceActor {
    implicit val timeout = Timeout(2.seconds)

    implicit val ec = context.dispatcher

    val AccessControlAllowAll = HttpHeaders.RawHeader("Access-Control-Allow-Origin", "http://localhost:8000")
    val AccessControlAllowHeadersAll = HttpHeaders.RawHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept")
    val AccessControlAllowCredentialsAll = HttpHeaders.RawHeader("Access-Control-Allow-Credentials", "true")

    override def receive = runRoute {
        respondWithHeaders(AccessControlAllowAll, AccessControlAllowHeadersAll, AccessControlAllowCredentialsAll) {
            options {
                complete {
                    ""
                }
            } ~
            path("events") {
                get {
                    complete {
                        "ehjfgeh"
                    }
                } ~
                post {
                    entity(as[Event.Create]) { cmd =>
                        events ! cmd
                        complete {
                            (StatusCodes.Accepted, cmd.slug)
                        }
                    }
                }
            } ~
            path("events" / Segment) { slug =>
                get {
                    complete {
                        (events ? Event.Get(slug)).mapTo[EventState]
                    }
                } ~
                put {
                    complete {
                        "ejrfhuejf"
                    }
                }
            } ~
            path("events" / Segment / "stream") { slug =>
                get { ctx =>
                    actorRefFactory.actorOf(Props(classOf[StreamActor], slug, ctx))
                }
            }
        }
    }
}
