package com.partyboard.controllers

import javax.inject.{Inject, Singleton}

import scala.concurrent.Future
import scala.language.postfixOps

import play.api.libs.EventSource
import play.api.libs.EventSource.{EventDataExtractor, EventNameExtractor}
import play.api.libs.functional.syntax._
import play.api.libs.iteratee.{Concurrent, Enumerator, Enumeratee}
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.mvc.{Controller, Action}

import play.modules.reactivemongo.json._
import play.modules.reactivemongo.json.collection._
import play.modules.reactivemongo.{MongoController, ReactiveMongoApi, ReactiveMongoComponents}

import com.google.inject.ImplementedBy

import reactivemongo.bson.BSONObjectID
import reactivemongo.api.indexes.{Index, IndexType}

@ImplementedBy(classOf[EventsController])
trait EventsStream {
    def publish: Concurrent.Channel[(String, String, JsValue)]
    def subscribe: Enumerator[(String, String, JsValue)]
}

@Singleton
class EventsController @Inject() (val reactiveMongoApi: ReactiveMongoApi)
    extends Controller with EventsStream with MongoController with ReactiveMongoComponents {

    import play.api.libs.concurrent.Execution.Implicits.defaultContext
    import JsCursor._

    val (enumerator, channel) = Concurrent.broadcast[(String, String, JsValue)]
    override def publish = channel
    override def subscribe = enumerator

    def collection: JSONCollection = db.collection[JSONCollection]("events")

    collection.indexesManager.ensure(Index(Seq("slug" -> IndexType.Ascending), unique = true))


    def eventBySlug(slug: String): Future[Option[JsObject]] = collection.find(Json.obj("slug" -> slug)).cursor[JsObject]().headOption

    def eventBySlug(slug: JsString): Future[Option[JsObject]] = eventBySlug(slug.value)


    val removeId: Reads[JsObject] = (__ \ '_id).json.prune

    val validateEvent: Reads[JsObject] =
        (__ \ 'slug).json.pickBranch and
        (__ \ 'title).json.pickBranch reduce

    val generateId: Reads[JsObject] = __.json.update((__ \ '_id).json.put(JsString(BSONObjectID.generate.stringify)))

    val extractId: Reads[JsValue] = (__ \ '_id).json.pick

    def addPictures(pictures: Seq[JsValue]): Reads[JsObject] =
        __.json.update((__ \ 'pictures).json.put(JsArray(pictures.map(_.transform(extractId).get))))



    def index = Action.async {
        collection
            .find(Json.obj())
            .cursor[JsObject]()
            .jsArray()
            .map(Ok(_))
    }

    def create = Action.async(parse.json) { request =>
        request.body.transform(removeId andThen validateEvent andThen generateId).map { result =>
            eventBySlug((result \ "slug").as[JsString]).flatMap { exists =>
                if (!exists.isEmpty) Future.successful(Conflict)
                else collection.insert(result).map { err =>
                    Created(result)
                }
            }
        }.getOrElse(Future.successful(BadRequest("Bad Request")))
    }

    def read(slug: String) = Action.async {
        eventBySlug(slug).flatMap(_.map { event =>
            collection
                .sibling("pictures")
                .find(JsObject(Seq("event" -> (event \ "_id").as[JsValue])))
                .cursor[JsObject]()
                .collect[List]()
                .map { pictures =>
                    Ok(event.transform(addPictures(pictures)).get)
                }
        }.getOrElse(Future.successful(NotFound)))
    }

    def update(slug: String) = TODO

    def delete(slug: String) = Action.async {
        eventBySlug(slug).flatMap(_.map { event =>
            collection.sibling("pictures").remove(JsObject(Seq("event" -> (event \ "_id").as[JsValue])))
            collection.remove(JsObject(Seq("event" -> (event \ "_id").as[JsValue]))).map { err =>
                NoContent
            }
        }.getOrElse(Future.successful(NotFound)))
    }

    def stream(slug: String) = Action {
        implicit val dataExtractor = EventDataExtractor[(String, String, JsValue)](d => d._3.toString)
        implicit val nameExtractor = EventNameExtractor[(String, String, JsValue)](d => Some(d._1))
        Ok.chunked(subscribe
            &> Enumeratee.filter[(String, String, JsValue)] { case (_, event, _) => event == slug }
                &> EventSource())
    }
}
