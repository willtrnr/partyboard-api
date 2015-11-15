package com.partyboard.protocol

import spray.json.DefaultJsonProtocol
import spray.httpx.SprayJsonSupport

import com.partyboard.domain._

object Json extends DefaultJsonProtocol with SprayJsonSupport {
    implicit val eventFormat = jsonFormat3(EventState)
    implicit val eventCreateFormat = jsonFormat2(Event.Create)
    implicit val eventPictureAddedFormat = jsonFormat3(Event.PictureAdded)
    implicit val eventRefFormat = jsonFormat2(EventRef)
    implicit val userEventsFormat = jsonFormat2(UserEventsState)
}
