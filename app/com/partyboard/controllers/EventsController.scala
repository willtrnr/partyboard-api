package com.partyboard.controllers

import javax.inject.{Inject, Singleton}

import scala.concurrent.Future
import scala.language.postfixOps

import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.mvc.{Controller, Action}

import play.modules.reactivemongo.json._
import play.modules.reactivemongo.json.collection._
import play.modules.reactivemongo.{MongoController, ReactiveMongoApi, ReactiveMongoComponents}

import reactivemongo.bson.BSONObjectID
import reactivemongo.api.indexes.{Index, IndexType}

@Singleton
class EventsController @Inject() (val reactiveMongoApi: ReactiveMongoApi)
    extends Controller with MongoController with ReactiveMongoComponents {

    import play.api.libs.concurrent.Execution.Implicits.defaultContext
    import JsCursor._


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

    def stream(slug: String) = TODO
}
