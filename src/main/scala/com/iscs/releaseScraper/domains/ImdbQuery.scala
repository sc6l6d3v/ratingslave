package com.iscs.releaseScraper.domains

import cats.effect.{Concurrent, ConcurrentEffect, Sync}
import cats.implicits._
import com.iscs.releaseScraper.model.Requests.ReqParams
import com.iscs.releaseScraper.util.{DbClient, asInt}
import com.typesafe.scalalogging.Logger
import fs2.{Pipe, Stream}
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import org.bson.conversions.Bson
import org.http4s.circe._
import org.http4s.{EntityDecoder, EntityEncoder}
import org.mongodb.scala.bson.{BsonDocument, BsonNumber, BsonString, conversions}
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.Filters.{and, exists, gte, in, lte, regex, text, eq => mdbeq, ne => mdne}
import org.mongodb.scala.model._

import scala.language.implicitConversions

abstract class QueryObj
case object TitleQuery extends QueryObj
case object NameQuery extends QueryObj

trait ImdbQuery[F[_]] {
  def getByTitle(title: String, rating: Double, params: ReqParams): Stream[F,Json]
  def getByName(name: String, rating: Double, params: ReqParams): Stream[F,Json]
  def getAutosuggestTitle(titlePrefix: String): Stream[F,Json]
  def getAutosuggestName(titlePrefix: String): Stream[F,Json]
}

object ImdbQuery {
  private val L = Logger[this.type]

  val DOCLIMIT = 200
  val AUTOSUGGESTLIMIT = 20

  def apply[F[_]](implicit ev: ImdbQuery[F]): ImdbQuery[F] = ev

  final case class TitleRec(averageRating: Option[Double], numVotes: Option[Int],
                            titleType: String, primaryTitle: String, originalTitle: String,
                            isAdult: Int, startYear: Int, endYear: String, runTimeMinutes: Int,
                            genresList: List[String])

  final case class NameTitleRec(primaryName: String, birthYear: Int,
                                matchedTitles: List[TitleRec])

  object TitleRec {
    implicit val titleRecDecoder: Decoder[TitleRec] = deriveDecoder[TitleRec]
    implicit def titleRecEntityDecoder[F[_]: Sync]: EntityDecoder[F, TitleRec] = jsonOf
    implicit val titleRecEncoder: Encoder[TitleRec] = deriveEncoder[TitleRec]
    implicit def titleRecEntityEncoder[F[_]: Sync]: EntityEncoder[F, TitleRec] = jsonEncoderOf
  }

  object NameTitleRec {
    implicit val nameTitleRecDecoder: Decoder[NameTitleRec] = deriveDecoder[NameTitleRec]
    implicit def nameTitleRecEntityDecoder[F[_]: Sync]: EntityDecoder[F, NameTitleRec] = jsonOf
    implicit val nameTitleRecEncoder: Encoder[NameTitleRec] = deriveEncoder[NameTitleRec]
    implicit def nameTitleRecEntityEncoder[F[_]: Sync]: EntityEncoder[F, NameTitleRec] = jsonEncoderOf
  }

  def impl[F[_]: Concurrent: Sync: ConcurrentEffect](dbClient: DbClient[F]): ImdbQuery[F] =
    new ImdbQuery[F] {
      val nameCollection = "name_basics"
      val titleCollection = "title_basics_ratings"

      val id = "_id"
      val birthYear = "birthYear"
      val deathYear = "deathYear"
      val firstName = "firstName"
      val lastName = "lastName"
/*      val primaryProfession = "primaryProfession"
      val primaryProfessionList = "primaryProfessionList"*/
      val knownForTitles = "knownForTitles"
      val knownForTitlesList = "knownForTitlesList"
      val primaryTitle = "primaryTitle"
      val primaryName = "primaryName"
      val matchedTitles = "matchedTitles"

      val averageRating = "averageRating"
      val matchedTitles_averageRating = "matchedTitles.averageRating"
      val genres = "genres"
      val matchedTitles_genres = "matchedTitles.genres"
      val genresList = "genresList"
      val matchedTitles_genresList = "matchedTitles.genresList"
      val isAdult = "isAdult"
      val matchedTitles_isAdult = "matchedTitles.isAdult"
      val startYear = "startYear"
      val matchedTitles_startYear = "matchedTitles.startYear"
      val titleType = "titleType"
      val matchedTitles_titleType = "matchedTitles.titleType"
      private val titleFx = dbClient.fxMap(titleCollection)
      private val nameFx = dbClient.fxMap(nameCollection)

      private def docToJson[F[_]: ConcurrentEffect]: Pipe[F, Document, Json] = strDoc => {
        for {
          doc <- strDoc
          json <- Stream.eval(Concurrent[F].delay(parse(doc.toJson()) match {
            case Right(validJson) => validJson
            case Left(parseFailure) =>
              L.error(""""Parsing failure" exception={} """, parseFailure.toString)
              Json.Null
          }))
        } yield json
      }

      def condenseSingleLists(bsonList: List[Bson]): F[Bson] =
        for {
          bson <- Concurrent[F].delay{
            if (bsonList.size == 1) bsonList.head
            else and(bsonList: _*)
          }
        } yield bson

      def getTitleModelFilters(title: String): F[Bson] = for {
        textBson <- Concurrent[F].delay(text(title))
        regexBson <- Concurrent[F].delay(regex(primaryTitle, title))
        combined <- condenseSingleLists(List(textBson, regexBson))
      } yield combined

      implicit def convertBooleanToInt(b: Boolean): asInt = new asInt(b)

      private def mapQtype(qType: QueryObj, titleString: String, nameString: String): String =
        qType match {
          case TitleQuery => titleString
          case NameQuery  => nameString
        }

      private def inOrEq[T](inputList: List[T], fieldName: String): conversions.Bson = inputList match {
        case manyElements:List[T] if manyElements.size > 1    => in(fieldName, manyElements:_*)
        case singleElement:List[T] if singleElement.size == 1 => mdbeq(fieldName, singleElement.head)
      }

      def getParamList(params: ReqParams, qType: QueryObj): F[List[Bson]] = {
        Concurrent[F].delay(
          List(
            params.year.map(yr => gte(mapQtype(qType, startYear, matchedTitles_startYear), yr.head)),
            params.year.map(yr => lte(mapQtype(qType, startYear, matchedTitles_startYear), yr.last)),
            params.genre.map{ genre =>
              val finalString = mapQtype(qType, genresList, matchedTitles_genresList)
              inOrEq(genre, finalString)
            },
            params.titleType.map { tt =>
              val finalString = mapQtype(qType, titleType, matchedTitles_titleType)
              inOrEq(tt, finalString)
            },
            params.isAdult.map(isadult =>
              mdbeq(mapQtype(qType, isAdult, matchedTitles_isAdult), isadult.toInt))
          ).flatten)
      }

      def getParamModelFilters(params: ReqParams, qType: QueryObj): F[Bson] = for {
        bsonList <- getParamList(params, qType)
        condensedBson <- condenseSingleLists(bsonList)
      } yield condensedBson

      /**
       * {
       * "$and":[
       *           {
       *             "$text":{ "$search":"Gone with the W" }
       *           },
       *          {
       *             "averageRating":{ "$gte":7.0 }
       *          },
       *          {
       *             "$and":[
       *                     { "startYear":{ "$gte":1924 } },
       *                     { "startYear":{ "$lte":2021 } },
       *                     { "genresList":"Crime" },
       *                     { "titleType":"movie" },
       *                     { "isAdult": 0 }
       *                    ]
       *          }
       *      ]
       * }
       *
       * @param title
       * @param rating
       * @param params
       * @return
       */
      override def getByTitle(title: String, rating: Double, params: ReqParams): Stream[F, Json] = for {
        paramBson <- Stream.eval(getParamModelFilters(params, TitleQuery))
        ratingBson <- Stream.eval(Concurrent[F].delay(gte(averageRating, rating)))
        titleBson <- Stream.eval(getTitleModelFilters(title))
        dbList <- Stream.eval(titleFx.find(
          and(titleBson, ratingBson, paramBson),
          DOCLIMIT, 0, Map(genres -> false))
          .through(docToJson)
          .compile.toList)
        json <- Stream.emits(dbList)
      } yield json

      /**
       * {
       *    $match: { $and: [
       *                { 'knownForTitles': { $exists: true, $ne: "" }},
       *                { 'primaryName': 'Tom Cruise' }
       *              ]
       *            }
       * }, {
       *    $lookup: {
       *       'from': 'title_basics_ratings',
       *       'localField': 'knownForTitlesList',
       *       'foreignField': '_id',
       *       'as': 'matchedTitles'
       * }, {
       *    $project: {
       *        "matchedTitles.genres": 0  }
       *    }
       * }, {
       *    $unwind: '$matchedTitles'
       * }, {
       *    $match: { $and: [
       *                    { "matchedTitles.averageRating", 7.0 },
       *                    { "matchedTitles.startYear": {$gte: 1986, $lte: 2001} },
       *                    { "matchedTitles.genresList": {$in: ["Drama", "Comedy"] },
       *                    { "$$matchedTitles.titleType": {$in: ["movie", "tvSeries"] },
       *                    { "$$matchedTitles.isAdult": 1 },
       *              ]
       *            }
       * }, {
       *    $group: {
       *       _id: "$_id",
       *       primaryName: { $first: "$primaryName" },
       *       firstName: { $first: "$firstName" },
       *       lastName: { $first: "$firstName" },
       *       birthYear: { $first: "$birthYear" },
       *       deathYear: { $first: "$deathYear" },
       *       matchedTitles: { $push: "$matchedTitles" },
       *    }
       * }
       *
       * @param name of actor
       * @param rating IMDB
       * @param params body
       * @return
       */
      override def getByName(name: String, rating: Double, params: ReqParams): Stream[F, Json] = for {
        matchTitleWithName <- Stream.eval(Concurrent[F].delay(
          and(
            exists(knownForTitles, exists = true),
            mdne(knownForTitles, ""),
            mdbeq(primaryName, name)
          )
        ))
        titleMatchFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.filter(matchTitleWithName)
        ))

        lookupFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.lookup(titleCollection,
            knownForTitlesList,
            "_id",
            matchedTitles)
        ))

        paramsList <- Stream.eval(getParamList(params, NameQuery))
        ratingBson <- Stream.eval(Concurrent[F].delay(gte(matchedTitles_averageRating, rating)))
        bsonCondensedList <- Stream.eval(condenseSingleLists(paramsList :+ ratingBson))
        matchLookupsFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.filter(and(bsonCondensedList))
        ))

        projectionsList <- Stream.eval(Concurrent[F].delay(
          nameFx.getProjectionFields(Map(
            matchedTitles_genres -> false
          ))
        ))
        projectionFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.project(nameFx.getProjections(projectionsList))
        ))

        unwindFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.unwind(s"$$$matchedTitles")
        ))

        groupFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.group("$_id",
            Accumulators.first(primaryName, s"$$$primaryName"),
            Accumulators.first(firstName, s"$$$firstName"),
            Accumulators.first(lastName, s"$$$lastName"),
            Accumulators.first(birthYear, s"$$$birthYear"),
            Accumulators.first(deathYear, s"$$$deathYear"),
            Accumulators.push(matchedTitles, s"$$$matchedTitles")
          )
        ))

        dbList <- Stream.eval(nameFx.aggregate(
          Seq(
            titleMatchFilter,
            lookupFilter,
            projectionFilter,
            unwindFilter,
            matchLookupsFilter,
            groupFilter
          )
        )
          .through(docToJson)
          .compile.toList)
        json <- Stream.emits(dbList)
      } yield json

      /**
       * {
       *   $match: {
       *            'lastName': {$regex: /^Cra/},
       *            'firstName': 'Daniel'
       *           }
       *  }, {
       *   $project: {
       *            firstName: 1,
       *            lastName: 1
       *             }
       *  }, {
       *   $group: {
       *            _id: "$lastName",
       *            firstName: { $first: "$firstName" }
       *           }
       *  }, {
       *   $sort: {
       *            firstName: 1,
       *            _id: 1
       *          }
       *  }, {
       *   $project: {
       *            _id: 0,
       *            firstName: 1,
       *            lastName: 1
       *             }
       *  }, {
       *   $limit: 20
       *  }
       * @param namePrefix first' '[lastname]
       * @return list
       */
      override def getAutosuggestName(namePrefix: String): Stream[F, Json] = for {
        names <- Stream.eval(Concurrent[F].delay(
          if (namePrefix contains " ")
            namePrefix.split(" ").toList
          else
            List(namePrefix)
        ))

        lastFirst <- Stream.eval(Concurrent[F].delay(
          if (names.size == 1)
            regex(lastName, s"""^${names.head}""")
          else
            and(
              regex(lastName, s"""^${names.last}"""),
              mdbeq(firstName, names.head)
            )
        ))

        nameMatchFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.filter(lastFirst)
        ))

        projectionsList <- Stream.eval(Concurrent[F].delay(
          nameFx.getProjectionFields(Map(
            firstName -> true,
            lastName -> true
          ))
        ))

        projectionFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.project(nameFx.getProjections(projectionsList))
        ))

        groupFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.group("$lastName",
            Accumulators.first(firstName, s"$$$firstName"))
        ))

        sortFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.sort(Document(
            id -> BsonNumber(1),
            firstName -> BsonNumber(1)))
        ))

        lastProjectionsList <- Stream.eval(Concurrent[F].delay(
          nameFx.getProjectionFields(Map(
            id -> false,
            firstName -> true,
          )) ++ List(BsonDocument((lastName, BsonString("$_id"))))
        ))

        lastProjectFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.project(nameFx.getProjections(lastProjectionsList))
        ))

        limitFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.limit(AUTOSUGGESTLIMIT)
        ))

        dbList <- Stream.eval(nameFx.aggregate(
          Seq(
            nameMatchFilter,
            projectionFilter,
            groupFilter,
            sortFilter,
            lastProjectFilter,
            limitFilter
          )
        )
          .through(docToJson)
          .compile.toList)
        json <- Stream.emits(dbList)
      } yield json

      /**
       * {
       *   $match: {
       *              $and: [
       *                      {$text: {$search: 'Gone with the W'}},
       *                      {'primaryTitle': {$regex: /^Gone with the W/}}
       *              ]
       *           }
       *  }, {
       *   $project: {
       *               _id: 0,
       *               'primaryTitle' : 1
       *             }
       *  }, {
       *    $group: {
       *               _id: '$primaryTitle',
       *               titles: { $first: "$primaryTitle" }
       *            }
       *  }, {
       *     $sort: {
       *               titles: 1
       *            }
       *  }, {
       *     $limit: 20
       *  }
       * @param titlePrefix substring with whitespace
       * @return sorted, distinct titles
       */
      override def getAutosuggestTitle(titlePrefix: String): Stream[F, Json] = for {
        titleTextRegex <- Stream.eval(Concurrent[F].delay(
          and(
            text(titlePrefix),
            regex(primaryTitle, s"""^$titlePrefix""")
          )
        ))

        titleMatchFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.filter(titleTextRegex)
        ))

        projectionsList <- Stream.eval(Concurrent[F].delay(
          titleFx.getProjectionFields(Map(
            id -> false,
            primaryTitle -> true
          ))
        ))

        projectionFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.project(titleFx.getProjections(projectionsList))
        ))

        groupFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.group("$primaryTitle",
            Accumulators.first(primaryTitle, s"$$$primaryTitle"))
        ))

        sortFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.sort(Document(primaryTitle -> BsonNumber(1)))
        ))

        limitFilter <- Stream.eval(Concurrent[F].delay(
          Aggregates.limit(AUTOSUGGESTLIMIT)
        ))

        dbList <- Stream.eval(titleFx.aggregate(
          Seq(
            titleMatchFilter,
            projectionFilter,
            groupFilter,
            sortFilter,
            limitFilter,
            projectionFilter
          )
        )
          .through(docToJson)
          .compile.toList)
        json <- Stream.emits(dbList)
      } yield json
    }
}
