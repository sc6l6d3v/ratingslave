package com.iscs.ratingslave.domains

import com.iscs.ratingslave.model.Requests.ReqParams
import com.iscs.ratingslave.util.asInt
import mongo4cats.bson.Document
import mongo4cats.bson.syntax._
import org.bson.conversions.Bson
import org.mongodb.scala.model.Projections.{computed, exclude, fields, include}
import org.mongodb.scala.model.{Accumulators, Aggregates, BsonField, Projections, Sorts}
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Filters.{eq => feq}

import scala.language.implicitConversions

trait QuerySetup {
  val id = "_id"
  private val averageRating = "averageRating"
  private val firstName = "firstName"
  private val genresList = "genresList"
  private val lastName = "lastName"
  val isAdult = "isAdult"
  private val numVotes = "numVotes"
  private val tconst = "tconst"
  private val primaryName = "primaryName"
  private val originalTitle = "originalTitle"
  private val primaryTitle = "primaryTitle"
  private val startYear = "startYear"
  private val endYear = "endYear"
  private val runtimeMinutes = "runtimeMinutes"
  val titleType = "titleType"
  private val AUTOSUGGESTLIMIT = 20

  implicit def convertBooleanToInt(b: Boolean): asInt = new asInt(b)

  private def between(fieldName: String, startyear: Int, stopyear: Int): Bson =
    and(gte(fieldName, startyear), lte(fieldName, stopyear))

  private def inOrEqList(fieldName: String, inputList: List[String]): Bson = inputList match {
    case manyElements: List[String] if manyElements.size > 1    =>
      in(fieldName, List(manyElements): _*)
    case singleElement: List[String] if singleElement.size == 1 =>
      feq(fieldName, singleElement.head)
  }

  private def buildAccums(fields: List[String]): Seq[BsonField] = fields
    .map(field => Accumulators.first(field, s"$$$field"))

  private val groupAccums: Seq[BsonField] = buildAccums(List(averageRating, numVotes, titleType, primaryTitle, originalTitle,
    isAdult, startYear, endYear, runtimeMinutes, genresList))

  private val projections: Bson = getProjections(
    computes = List(averageRating, originalTitle, numVotes, runtimeMinutes),
    includes = List(titleType, primaryTitle, isAdult, startYear, endYear, genresList),
    excludes = List.empty[String])

  private def dblExists(fieldName: String, dbl: Double): Bson =
    or(
      exists(fieldName, exists = false),
      gte(fieldName, dbl)
    )

  private def combineBson(bsonList: List[Bson], bson1: Bson, bson2: Bson): List[Bson] = bsonList ::: (bson1 :: bson2 :: Nil)

  private def getProjections(computes: List[String], includes: List[String], excludes: List[String]): Bson = {
    val fields = computes.map(comp => Projections.computed(comp, Document("$ifNull" := List(s"$$$comp", "$$REMOVE")))) ++
      List(Projections.include(includes: _*)) ++
      List(Projections.exclude(excludes: _*))
    Projections.fields(fields: _*)
  }

  private val sorting: Bson = Sorts.descending(startYear, numVotes)

  private def getParamList(params: ReqParams): List[Bson] = {
    List(
      params.year.map(yr => between(startYear, yr.head, yr.last)),
      params.genre.map(genre => inOrEqList(genresList, genre)),
      params.titleType.map(tt => inOrEqList(titleType, tt)),
      params.isAdult.map(isAdlt => feq(isAdult, isAdlt.toInt)),
      params.votes.map(v => gte(numVotes, v))
    ).flatten
  }

  private def getOptFilters(optTitle: Option[String]): Option[Bson] = optTitle.map(title => regex(primaryTitle, title))

  def genAutonameFilter(namePrefix: String, rating: Double, params: ReqParams): Seq[Bson] = {
    val names = if (namePrefix contains " ")
      namePrefix.split(" ").toList
    else
      List(namePrefix)

    val lastFirstElt = if (names.size == 1)
      regex(lastName, s"""^${names.head}""")
    else
      and(
        regex(lastName, s"""^${names.last}"""),
        feq(firstName, names.head))

    val nonEmptyElts = combineBson(getParamList(params), dblExists(averageRating, rating), lastFirstElt)
    val matchBson = and(nonEmptyElts: _*)

    val sortElt = Sorts.ascending(id, firstName)

    val lastProjectionsBson = fields(
      exclude(id),
      include(firstName),
      computed(lastName, "$_id")
    )

    Seq(
      Aggregates.`match`(matchBson),
      Aggregates.group("$lastName", Accumulators.first(firstName, s"$$$firstName")),
      Aggregates.sort(sortElt),
      Aggregates.project(lastProjectionsBson),
      Aggregates.limit(AUTOSUGGESTLIMIT)
    )
  }

  def genAutotitleFilter(titlePrefix: String, rating: Double, params: ReqParams): Seq[Bson] = {
    val titleElt = regex(primaryTitle, s"""^$titlePrefix""")

    val nonEmptyElts = combineBson(getParamList(params), dblExists(averageRating, rating), titleElt)
    val matchBson = and(nonEmptyElts: _*)

    val sortBson = Sorts.ascending(id)

    Seq(
      Aggregates.`match`(matchBson),
      Aggregates.group("$primaryTitle"),
      Aggregates.sort(sortBson),
      Aggregates.limit(AUTOSUGGESTLIMIT)
    )
  }

  def genNameFilter(name: String, rating: Double, params: ReqParams): Bson = {
    val nonEmptyElts = getParamList(params) ::: (dblExists(averageRating, rating) :: feq(primaryName, name) :: Nil)
    and(nonEmptyElts: _*)
  }

  def genTitleFilter(optTitle: Option[String], rating: Double, params: ReqParams): Bson = {
    val paramWithRating = getParamList(params) :+ dblExists(averageRating, rating)
    val nonEmptyElts = getOptFilters(optTitle).map { titleElts =>
      paramWithRating :+ titleElts
    }.getOrElse(paramWithRating)
    and(nonEmptyElts: _*)
  }

  def genQueryPipeline(matchVariable: Bson): Seq[Bson] =
    Seq(
      Aggregates.`match`(matchVariable),
      Aggregates.group(s"$$$tconst", groupAccums: _*),
      Aggregates.project(projections),
      Aggregates.sort(sorting)
    )
}