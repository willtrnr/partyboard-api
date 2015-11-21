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
import play.modules.reactivemongo.JSONFileToSave
import play.modules.reactivemongo.{MongoController, ReactiveMongoApi, ReactiveMongoComponents}

import reactivemongo.api.gridfs.ReadFile
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID

@Singleton
class PicturesController @Inject() (val reactiveMongoApi: ReactiveMongoApi, val eventsStream: EventsStream)
    extends Controller with MongoController with ReactiveMongoComponents {

    import play.api.libs.concurrent.Execution.Implicits.defaultContext
    import MongoController.readFileReads

    def collection: JSONCollection = db.collection[JSONCollection]("pictures")

    val fsParser = gridFSBodyParser(reactiveMongoApi.gridFS, (filename, contentType) => {
        new JSONFileToSave(filename = None, contentType = contentType, uploadDate = Some(System.currentTimeMillis), id = JsString(BSONObjectID.generate.stringify))
    })

    val fsServe = serve[JsString, ReadFile[JSONSerializationPack.type, JsString]](reactiveMongoApi.gridFS) _

    def eventBySlug(slug: String): Future[Option[JsObject]] = collection.sibling("events").find(Json.obj("slug" -> slug)).cursor[JsObject]().headOption


    val extractId: Reads[JsValue] = (__ \ '_id).json.pick

    val generateId: Reads[JsObject] = __.json.update((__ \ '_id).json.put(JsString(BSONObjectID.generate.stringify)))

    def addEventId(event: JsObject): Reads[JsObject] = __.json.update((__ \ 'event).json.put((event \ "_id").as[JsValue]))

    val pictureToFile: Reads[JsObject] = (__ \ '_id).json.copyFrom((__ \ 'file).json.pick)


    def index(slug: String) = Action.async {
        eventBySlug(slug).flatMap(_.map { event =>
            collection
                .find(JsObject(Seq("event" -> (event \ "_id").as[JsValue])))
                .cursor[JsObject]()
                .collect[List]()
                .map(p => Ok(JsArray(p.map(_.transform(extractId).get))))
        }.getOrElse(Future.successful(NotFound)))
    }

    def create(slug: String) = Action.async(fsParser) { request =>
        eventBySlug(slug).flatMap(_.map { event =>
            request.body.files.head.ref.flatMap { file =>
                val result = JsObject(Seq("file" -> file.id)).transform(generateId andThen addEventId(event)).get
                collection.insert(result).map { err =>
                    eventsStream.publish.push(("picture", slug, result.transform(extractId).get))
                    Created(result.transform(extractId).get)
                }
            }.recover {
                case e: Throwable => InternalServerError(e.getMessage)
            }
        }.getOrElse(Future.successful(NotFound)))
    }

    def read(slug: String, id: String) = Action.async {
        eventBySlug(slug).flatMap(_.map { event =>
            collection
                .find(JsObject(Seq("_id" -> JsString(id), "event" -> (event \ "_id").as[JsValue])))
                .cursor[JsObject]()
                .headOption
                .flatMap(_.map { picture =>
                    fsServe(reactiveMongoApi.gridFS.find(picture.transform(pictureToFile).get), CONTENT_DISPOSITION_INLINE)
                }.getOrElse(Future.successful(NotFound)))
        }.getOrElse(Future.successful(NotFound)))
    }

    def delete(slug: String, id: String) = Action.async {
        eventBySlug(slug).flatMap(_.map { event =>
            collection
                .find(JsObject(Seq("_id" -> JsString(id), "event" -> (event \ "_id").as[JsValue])))
                .cursor[JsObject]()
                .headOption
                .flatMap(_.map { picture =>
                    collection.remove(JsObject(Seq("_id" -> (picture \ "_id").as[JsValue]))).map { err =>
                        NoContent
                    }
                }.getOrElse(Future.successful(NotFound)))
        }.getOrElse(Future.successful(NotFound)))
    }
}
